package com.freereading.pdf

import android.content.Context
import com.freereading.data.SpeechIndexLimits
import com.freereading.data.SpeechSentence
import com.freereading.data.SpeechSource
import com.freereading.epub.BookImportException
import com.freereading.epub.SentenceRanges
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** 从 PDF 的文本层按页提取朗读句。图片页不产生句子，页码保持原样。 */
internal class PdfBookIndexer(context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    data class Index(
        val title: String?,
        val author: String?,
        val language: String?,
        val pageCount: Int,
        val sentences: List<SpeechSentence>,
    )

    suspend fun index(
        file: File,
        onPageCount: (Int) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): Index {
        val context = currentCoroutineContext()
        val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 256L * 1024 * 1024)
            .setTempDir(file.parentFile)
        try {
            return PDDocument.load(file, memory).use { document ->
                if (document.isEncrypted) throw BookImportException("PDF 已加密，请先解锁文件后重新导入")
                val pageCount = document.numberOfPages
                if (pageCount !in 1..MAX_PAGE_COUNT) {
                    throw BookImportException("PDF 没有可用页面，或页数超过 ${MAX_PAGE_COUNT} 页")
                }
                onPageCount(pageCount)
                val sentences = ArrayList<SpeechSentence>()
                val pageText = PageTextWriter()
                var sourceLocatorCharacters = 0L
                var textCharacters = 0L
                val stripper = object : PDFTextStripper() {
                    private var pageCharacters = 0L

                    override fun startPage(page: PDPage) {
                        context.ensureActive()
                        pageText.reset()
                        pageCharacters = 0L
                        super.startPage(page)
                    }

                    override fun processTextPosition(position: TextPosition) {
                        pageCharacters += (position.unicode?.length ?: 0).coerceAtLeast(1)
                        checkPageCharacters(pageCharacters)
                        context.ensureActive()
                        super.processTextPosition(position)
                    }

                    override fun endPage(page: PDPage) {
                        context.ensureActive()
                        val pageNumber = currentPageNo
                        if (pageNumber !in 1..pageCount) throw IOException("PDF page tree does not match page count")
                        val text = pageText.toString().trim()
                        val ranges = SentenceRanges.split(text)
                        if (ranges.isNotEmpty()) {
                            val locatorJson = JSONObject()
                                .put("href", "book.pdf")
                                .put("type", "application/pdf")
                                .put("locations", JSONObject().put("position", pageNumber))
                                .put("text", JSONObject().put("highlight", text))
                            sourceLocatorCharacters += locatorJson.toString().length
                            textCharacters += ranges.sumOf { it.last - it.first + 1 }
                            if (sourceLocatorCharacters > SpeechIndexLimits.MAX_SOURCE_LOCATOR_CHARACTERS ||
                                textCharacters > SpeechIndexLimits.MAX_TEXT_CHARACTERS ||
                                sentences.size + ranges.size > SpeechIndexLimits.MAX_SENTENCES) {
                                throw BookImportException("PDF 正文过大，无法建立听书索引")
                            }
                            val locator = Locator.fromJSON(locatorJson)
                                ?: throw IOException("Invalid PDF page locator")
                            val source = SpeechSource(locator, pageNumber - 1, "第 ${pageNumber} 页")
                            for (range in ranges) {
                                sentences.add(SpeechSentence(sentences.size, source, range.first, range.last + 1))
                            }
                        }
                        onProgress(pageNumber.toLong(), pageCount.toLong())
                        super.endPage(page)
                    }
                }
                stripper.sortByPosition = true
                // PDF 中的视觉换行通常不等于语义句尾。保留空格，仍由共用规则处理句末标点。
                stripper.lineSeparator = " "
                // 一次遍历文档，避免为每页从第一页重新扫描。
                onProgress(0, pageCount.toLong())
                stripper.writeText(document, pageText)
                context.ensureActive()
                onProgress(pageCount.toLong(), pageCount.toLong())
                val language = document.documentCatalog.language
                    ?.trim()?.take(64)?.replace('_', '-')
                    ?.takeIf { Locale.forLanguageTag(it).language.isNotBlank() }
                    ?: inferLanguage(sentences)
                Index(document.documentInformation.title, document.documentInformation.author,
                    language, pageCount, sentences)
            }
        } catch (error: InvalidPasswordException) {
            throw BookImportException("PDF 需要密码，请先解锁文件后重新导入", error)
        } catch (error: BookImportException) {
            throw error
        } catch (error: IOException) {
            throw BookImportException("无法打开或提取 PDF，请检查文件是否完整", error)
        }
    }

    /** PDF 经常省略 /Lang。用有限的样本选择常见文字体系对应的离线音色。 */
    private fun inferLanguage(sentences: List<SpeechSentence>): String? {
        var han = 0
        var latin = 0
        var kana = 0
        var hangul = 0
        for (sentence in sentences.take(24)) {
            val text = sentence.text
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                if (Character.isLetter(codePoint)) when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN -> han += 1
                    Character.UnicodeScript.LATIN -> latin += 1
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana += 1
                    Character.UnicodeScript.HANGUL -> hangul += 1
                    else -> Unit
                }
                offset += Character.charCount(codePoint)
            }
        }
        return when {
            kana > 0 && kana + han >= latin -> "ja"
            hangul > 0 && hangul >= latin -> "ko"
            han > 0 && han >= latin -> "zh"
            latin > 0 -> "en"
            else -> null
        }
    }

    /** 同时限制 PDFBox 的输出与字形计数，避免单个异常页面积累无界文本。 */
    private class PageTextWriter : Writer() {
        private val page = StringBuilder()
        fun reset() { page.setLength(0) }
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            checkPageCharacters(page.length.toLong() + len)
            page.append(cbuf, off, len)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = page.toString()
    }

    companion object {
        const val MAX_PAGE_COUNT = 20_000

        private fun checkPageCharacters(count: Long) {
            if (count > SpeechIndexLimits.MAX_BLOCK_CHARACTERS) {
                throw BookImportException("PDF 单页正文过长，无法建立听书索引")
            }
        }
    }
}
