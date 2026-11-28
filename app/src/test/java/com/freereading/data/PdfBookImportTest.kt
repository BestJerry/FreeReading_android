package com.freereading.data

import android.net.Uri
import com.freereading.ReadingApplication
import com.freereading.epub.BookImportException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class PdfBookImportTest {
    private lateinit var app: ReadingApplication
    private lateinit var repository: BookRepository

    @Before fun clearLibrary() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        File(app.filesDir, "books").deleteRecursively()
        PDFBoxResourceLoader.init(app)
        repository = BookRepository(app)
    }

    @Test fun realPdfKeepsItsOriginalBytesAndPhysicalPageNumbersAfterReopen() = runBlocking {
        val source = makePdf(
            "opaque-document.bin",
            listOf("Read the first page. Then keep reading.", null, "The last page has text."),
            title = "Ordinary PDF",
        )
        val uri = Uri.fromFile(source)
        val book = repository.importBook(uri)
        assertEquals(book.id, repository.importBook(uri).id)
        assertEquals(BookFormat.PDF, book.format)
        assertEquals("Ordinary PDF", book.title)
        assertEquals("en", book.language)
        assertEquals(3, book.pdfPageCount)
        assertEquals(3, book.sentenceCount)
        assertEquals("book.pdf", repository.bookFile(book.id).name)
        assertArrayEquals(source.readBytes(), repository.bookFile(book.id).readBytes())

        val recreated = BookRepository(app)
        recreated.refresh()
        val prepared = recreated.prepareReadingBook(book.id)
        assertEquals(book, prepared.record)
        assertNull(prepared.cachedSpeechBook)
        val sentences = recreated.loadSpeechBook(book.id).sentences
        assertEquals(listOf(0, 0, 2), sentences.map { it.chapterIndex })
        assertEquals(listOf("Read the first page.", "Then keep reading.", "The last page has text."),
            sentences.map { it.text })
        assertEquals(listOf(1, 1, 3), sentences.map { sentence ->
            requireNotNull(Locator.fromJSON(JSONObject(sentence.locatorJson))).locations.position
        })
        val last = sentences.last()
        recreated.saveProgress(ReadingProgress(book.id, last.locatorJson, last.index, 400, 10), book.addedAt)
        recreated.savePdfReadingPage(book.id, 1, book.addedAt, updatedAt = 20)
        recreated.savePdfReadingPage(book.id, 0, book.addedAt, updatedAt = 19)
        recreated.savePdfReadingPage(book.id, 0, book.addedAt - 1, updatedAt = 30)
        val restored = BookRepository(app)
        assertEquals(1, restored.pdfReadingPage(book.id))
        assertEquals(last.index, restored.progress(book.id).sentenceIndex)
        assertEquals(400L, restored.progress(book.id).audioOffsetMs)
    }

    @Test fun pdfWithOnlyGraphicsIsImportedForReadingAndHasNoSpeech() = runBlocking {
        val source = makePdf("扫描图册.pdf", listOf(null, null))
        val book = repository.importBook(Uri.fromFile(source))
        assertEquals("扫描图册", book.title)
        assertEquals(2, book.pdfPageCount)
        assertEquals(0, book.sentenceCount)
        val recreated = BookRepository(app)
        assertTrue(recreated.loadSpeechBook(book.id).sentences.isEmpty())
        recreated.removeBook(book.id)
        assertNull(recreated.pdfReadingPage(book.id))
        assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    @Test fun encryptedAndMalformedPdfsLeaveNoPartialBook() = runBlocking {
        val encrypted = makePdf("locked.pdf", listOf("Confidential fixture."), password = "sample")
        val invalid = File(app.cacheDir, "broken.pdf").apply { writeText("%PDF-1.7\ninvalid") }
        for ((source, expected) in listOf(encrypted to "密码", invalid to "PDF")) {
            val error = runCatching { repository.importBook(Uri.fromFile(source)) }.exceptionOrNull()
            assertTrue(error is BookImportException && error.message.orEmpty().contains(expected))
            assertTrue(repository.books.value.isEmpty())
            assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())
        }
    }

    private fun makePdf(name: String, pages: List<String?>, title: String? = null, password: String? = null): File {
        val file = File(app.cacheDir, name)
        PDDocument().use { document ->
            document.documentInformation.title = title
            for (text in pages) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    if (text == null) {
                        content.addRect(0f, 0f, 50f, 50f)
                        content.fill()
                    } else {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(30f, 750f)
                        content.showText(text)
                        content.endText()
                    }
                }
            }
            if (password != null) {
                document.protect(StandardProtectionPolicy("fixture-owner", password, AccessPermission()))
            }
            document.save(file)
        }
        return file
    }
}
