package com.freereading.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.TtsVoiceUiState
import com.freereading.data.BookFormat
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.ui.FreeReadingTheme
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** PDF 使用固定页面排版；后台朗读沿用同一个播放服务，阅读页码与音频游标分别保存。 */
class PdfReaderActivity : ComponentActivity() {
    private val repository by lazy { (application as ReadingApplication).repository }
    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK).orEmpty() }
    private var document by mutableStateOf<PdfDocumentReader?>(null)
    private var record by mutableStateOf<BookRecord?>(null)
    private var pageIndex by mutableIntStateOf(0)
    private var openError by mutableStateOf<String?>(null)
    private var listening by mutableStateOf(false)
    private var startingAudio by mutableStateOf(false)
    private var audioIndex: PdfSpeechIndex? = null
    private var audioIndexTask: Deferred<PdfSpeechIndex>? = null
    private var audioStartTask: Job? = null
    private var passiveAudioTask: Job? = null
    private var passiveAudioFailed = false
    private var pendingStartId: String? = null
    private var deferFollowing: PdfAudioDeferral? = null
    private var playFromReading = false
    private var lastPageSaveAt = 0L
    private var pageRevision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        listening = savedInstanceState?.getBoolean(STATE_LISTENING)
            ?: intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        playFromReading = savedInstanceState?.getBoolean(STATE_FROM_READING) ?: false
        pendingStartId = savedInstanceState?.getString(STATE_PENDING_START)
            ?.takeIf(PlaybackHub::isCurrentPlaybackSessionId)
        startingAudio = pendingStartId != null
        deferFollowing = savedInstanceState?.getBundle(STATE_DEFER_FOLLOW)?.let { hold ->
            hold.getString("session")?.takeIf(PlaybackHub::isCurrentPlaybackSessionId)?.let { session ->
                PdfAudioDeferral(session, hold.getInt("sentence", -1).takeIf { it >= 0 })
            }
        }
        setContent { FreeReadingTheme { Reader() } }
        if (bookId.isBlank()) {
            openError = "找不到书籍，请从书架重新打开。"
            return
        }
        lifecycleScope.launch { openBook(savedInstanceState?.getInt(STATE_PAGE, -1)?.takeIf { it >= 0 }) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackHub.state.map {
                    PdfAudioCursor(it.bookId, it.playbackSessionId, it.sentenceIndex, it.sentenceCount,
                        it.wantsPlayback, it.isPlaying, it.startFailure?.requestId)
                }.distinctUntilChanged().collect { synchronizeAudio() }
            }
        }
    }

    private suspend fun openBook(restoredPage: Int?) {
        try {
            val (bootstrap, savedPage, file) = withContext(Dispatchers.IO) {
                Triple(repository.prepareReadingBook(bookId), repository.pdfReadingPage(bookId),
                    repository.bookFile(bookId))
            }
            val book = bootstrap.record
            if (book.format != BookFormat.PDF || book.pdfPageCount <= 0) throw IOException("无效的 PDF 书籍")
            document = PdfDocumentReader(file, book.pdfPageCount)
            bootstrap.cachedSpeechBook?.let { audioIndex = PdfSpeechIndex(it) }
            val audioPage = bootstrap.progress.locatorJson?.let(::pdfPageFromLocator)
            pageIndex = (restoredPage ?: savedPage ?: audioPage ?: 0).coerceIn(0, book.pdfPageCount - 1)
            record = book
            if (book.sentenceCount == 0) listening = false
            synchronizeAudio()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            openError = error.message ?: "PDF 暂时无法打开，请检查文件后重试。"
        }
    }

    private fun turnTo(page: Int, manual: Boolean = false) {
        val book = record ?: return
        if (page !in 0 until book.pdfPageCount || page == pageIndex) return
        if (manual) {
            val audio = PlaybackHub.state.value
            val follows = audio.bookId == book.id && audio.wantsPlayback
            playFromReading = !follows
            deferFollowing = if (follows) {
                deferFollowing?.takeIf { it.delays(audio) }
                    ?: PdfAudioDeferral(audio.playbackSessionId, audio.sentenceIndex.takeIf { audio.isPlaying })
            } else null
        }
        pageIndex = page
        pageRevision++
        savePage(book, page)
    }

    private fun savePage(book: BookRecord, page: Int) {
        val store = repository
        val capturedAt = maxOf(System.currentTimeMillis(), lastPageSaveAt + 1L)
        lastPageSaveAt = capturedAt
        val task = diskTasks.launch(start = CoroutineStart.LAZY) {
            store.savePdfReadingPage(book.id, page, book.addedAt, capturedAt)
        }
        store.registerReadingSave(book.id, book.addedAt, task)
        task.start()
    }

    private fun synchronizeAudio() {
        val book = record ?: return
        val audio = PlaybackHub.state.value
        val pending = pendingStartId
        // 从新页面起播时，旧会话不能在索引准备阶段抢回页码。
        if (startingAudio && pending == null) return
        if (pending != null) {
            if (audio.startFailure?.requestId == pending) {
                pendingStartId = null
                startingAudio = false
                toast(audio.error ?: "播放未开始，请重试。")
                return
            }
            // 页面停在后台时可能错过本书的确认；更新的其他书会话也能解除等待。
            if (!PlaybackHub.isPlaybackStartConfirmed(pending, audio.playbackSessionId)) return
            pendingStartId = null
            startingAudio = false
        }
        if (audio.bookId != book.id || !audio.wantsPlayback) {
            deferFollowing = null
            return
        }
        if (audio.sentenceCount <= 0) return
        if (audioIndex == null) {
            if (!passiveAudioFailed && passiveAudioTask?.isActive != true) {
                passiveAudioTask = lifecycleScope.launch {
                    try {
                        requireAudioIndex(book)
                        synchronizeAudio()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // 阅读仍可用。听书页上的明确操作会重试并报告错误。
                        passiveAudioFailed = true
                    }
                }
            }
            return
        }
        if (deferFollowing?.delays(audio) == true) return
        deferFollowing = null
        val destination = audioIndex?.pageFor(audio.sentenceIndex) ?: return
        playFromReading = false
        turnTo(destination)
    }

    private suspend fun requireAudioIndex(book: BookRecord): PdfSpeechIndex {
        audioIndex?.let { return it }
        val task = audioIndexTask ?: lifecycleScope.async {
            val indexed = repository.loadSpeechBook(book.id)
            if (indexed.book.addedAt != book.addedAt) {
                throw IOException("书籍已重新导入，请返回书架后重新打开。")
            }
            PdfSpeechIndex(indexed)
        }.also { audioIndexTask = it }
        try {
            return task.await().also { audioIndex = it }
        } finally {
            if (audioIndexTask === task && task.isCompleted) audioIndexTask = null
        }
    }

    private fun toggleListening() {
        val book = record ?: return
        val audio = PlaybackHub.state.value
        if (audio.bookId == book.id && (audio.wantsPlayback || !playFromReading)) {
            if (!startingAudio) PlaybackHub.toggle(this)
        } else {
            playCurrentPage(explicitPage = playFromReading)
        }
    }

    private fun playCurrentPage(explicitPage: Boolean) {
        val book = record ?: return
        if (startingAudio || book.sentenceCount == 0) return
        val page = pageIndex
        val revision = pageRevision
        startingAudio = true
        val task = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val index = requireAudioIndex(book)
                val saved = if (explicitPage) null else withContext(Dispatchers.IO) { repository.progress(book.id) }
                // 用户在加载时翻页，以新页面为准，不迟到启动旧页的朗读。
                if (pageRevision != revision) return@launch
                val resume = saved?.takeIf { index.pageFor(it.sentenceIndex) == page }
                val sentence = resume?.sentenceIndex ?: index.firstSentenceFrom(page)
                if (sentence == null) {
                    toast("从当前页到文档末尾没有可朗读文字。")
                    return@launch
                }
                val request = PlaybackHub.newPlaybackSessionId()
                pendingStartId = request
                deferFollowing = null
                playFromReading = false
                if (!PlaybackHub.playBook(this@PdfReaderActivity, book.id, sentence, resume?.audioOffsetMs ?: 0L, request)) {
                    synchronizeAudio()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                toast(error.message ?: "PDF 听书索引无法读取。")
            } finally {
                if (audioStartTask === coroutineContext[Job]) {
                    audioStartTask = null
                    if (pendingStartId == null && !isDestroyed) {
                        startingAudio = false
                        synchronizeAudio()
                    }
                }
            }
        }
        audioStartTask = task
        task.start()
    }

    /** 结束听书也取消正在等待索引的起播，避免停止后旧请求又启动播放。 */
    private fun stopListening() {
        val task = audioStartTask
        audioStartTask = null
        pendingStartId = null
        startingAudio = false
        task?.cancel()
        listening = false
        if (PlaybackHub.state.value.bookId == bookId) PlaybackHub.stop(this)
    }

    private fun locateAudioPage() {
        val book = record ?: return
        lifecycleScope.launch {
            try {
                val index = requireAudioIndex(book)
                val audio = PlaybackHub.state.value
                if (audio.bookId != book.id || audio.sentenceCount == 0) return@launch
                val destination = index.pageFor(audio.sentenceIndex) ?: return@launch
                deferFollowing = null
                playFromReading = false
                turnTo(destination)
                listening = false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                toast(error.message ?: "PDF 听书索引无法读取。")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (record != null) outState.putInt(STATE_PAGE, pageIndex)
        outState.putBoolean(STATE_LISTENING, listening)
        outState.putBoolean(STATE_FROM_READING, playFromReading)
        outState.putString(STATE_PENDING_START, pendingStartId)
        deferFollowing?.let {
            outState.putBundle(STATE_DEFER_FOLLOW, Bundle().apply {
                putString("session", it.sessionId)
                putInt("sentence", it.spokenSentence ?: -1)
            })
        }
    }

    override fun onDestroy() {
        document?.let { doc -> diskTasks.launch { doc.close() } }
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    @Composable
    private fun Reader() {
        val book = record
        val reader = document
        var showPageChooser by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书架") }
                    Text(book?.title ?: "PDF 阅读", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (book?.sentenceCount?.let { it > 0 } == true) {
                        TextButton(onClick = { listening = true }) { Text("听书") }
                    }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    when {
                        openError != null -> PdfPageMessage(checkNotNull(openError))
                        book == null || reader == null -> CircularProgressIndicator()
                        else -> PdfPageView(reader, pageIndex) { delta -> turnTo(pageIndex + delta, manual = true) }
                    }
                }
                if (book != null) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { turnTo(pageIndex - 1, manual = true) }, enabled = pageIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一页")
                        }
                        TextButton(onClick = { showPageChooser = true }) { Text("第 ${pageIndex + 1} / ${book.pdfPageCount} 页") }
                        IconButton(onClick = { turnTo(pageIndex + 1, manual = true) }, enabled = pageIndex + 1 < book.pdfPageCount) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一页")
                        }
                    }
                    if (book.sentenceCount == 0) {
                        Text("此 PDF 未包含可提取的文字", modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (showPageChooser && book != null) {
            PdfPageChooser(pageIndex, book.pdfPageCount, onDismiss = { showPageChooser = false }) {
                turnTo(it, manual = true)
                showPageChooser = false
            }
        }
        if (listening && book != null && book.sentenceCount > 0) {
            PdfListening(book)
        }
    }

    @Composable
    private fun PdfListening(book: BookRecord) {
        val playback by PlaybackHub.state.collectAsStateWithLifecycle()
        val voices by PlaybackHub.voiceState.collectAsStateWithLifecycle()
        val active = playback.bookId == book.id
        Dialog(
            onDismissRequest = { listening = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            ListeningPlayerScreen(
                bookTitle = book.title,
                author = book.author,
                coverPath = book.coverPath,
                snapshot = playback,
                activeBook = active,
                enabled = !startingAudio,
                paper = MaterialTheme.colorScheme.surface,
                ink = MaterialTheme.colorScheme.onSurface,
                accent = MaterialTheme.colorScheme.primary,
                onClose = { listening = false },
                onToggle = ::toggleListening,
                onSeek = { if (PlaybackHub.state.value.bookId == book.id) PlaybackHub.seekBy(this, it) },
                onSpeed = { if (PlaybackHub.state.value.bookId == book.id) PlaybackHub.setSpeed(this, it) },
                onFromReading = { playCurrentPage(explicitPage = true) },
                onLocate = ::locateAudioPage,
                onStop = ::stopListening,
                onVoiceSettings = { PlaybackHub.openVoiceSettings(this) },
                voiceState = voices.takeIf { it.bookId == book.id && it.language == book.language } ?: TtsVoiceUiState(),
                onLoadVoices = { PlaybackHub.loadVoices(this, book.id, book.language) },
                onSelectVoice = { PlaybackHub.selectVoice(this, book.id, book.language, it) },
                onPreviousChapter = { PlaybackHub.previousChapter(this, book.id) },
                onNextChapter = { PlaybackHub.nextChapter(this, book.id) },
                pageBased = true,
            )
        }
    }

    companion object {
        private const val EXTRA_BOOK = "book_id"
        private const val EXTRA_OPEN_PLAYER = "open_player"
        private const val STATE_PAGE = "pdf_reader_page"
        private const val STATE_LISTENING = "pdf_reader_listening"
        private const val STATE_FROM_READING = "pdf_reader_from_reading"
        private const val STATE_PENDING_START = "pdf_reader_pending_start"
        private const val STATE_DEFER_FOLLOW = "pdf_reader_defer_follow"
        private val diskTasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, bookId: String, openPlayer: Boolean = false): Intent =
            Intent(context, PdfReaderActivity::class.java)
                .putExtra(EXTRA_BOOK, bookId)
                .putExtra(EXTRA_OPEN_PLAYER, openPlayer)
    }
}

private data class PdfAudioCursor(
    val bookId: String?, val sessionId: String?, val sentence: Int, val sentenceCount: Int,
    val wantsPlayback: Boolean, val isPlaying: Boolean, val failureId: String?,
)

private data class PdfAudioDeferral(val sessionId: String?, val spokenSentence: Int?) {
    fun delays(audio: PlaybackSnapshot): Boolean = audio.playbackSessionId == sessionId &&
        audio.wantsPlayback && (!audio.isPlaying || audio.sentenceIndex == spokenSentence)
}

private class PdfSpeechIndex(private val speech: SpeechBook) {
    fun firstSentenceFrom(page: Int): Int? {
        var low = 0
        var high = speech.sentences.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (speech.sentences[mid].chapterIndex < page) low = mid + 1 else high = mid
        }
        return low.takeIf { it < speech.sentences.size }
    }
    fun pageFor(sentence: Int): Int? = speech.sentences.getOrNull(sentence)?.chapterIndex
}

private fun pdfPageFromLocator(locatorJson: String): Int? =
    runCatching { org.json.JSONObject(locatorJson).optJSONObject("locations")?.optInt("position", 0) }
        .getOrNull()?.takeIf { it > 0 }?.minus(1)

/** 同一文档只允许打开一个渲染页；翻页、打开和释放都在后台串行。 */
private class PdfDocumentReader(private val file: File, val count: Int) {
    private val mutex = Mutex()
    private var renderer: PdfRenderer? = null
    private var closed = false

    suspend fun render(page: Int, viewport: IntSize): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            val document = renderer ?: open().also { renderer = it }
            document.openPage(page).use { source ->
                val size = pdfBitmapSize(source.width, source.height, viewport)
                val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }

    private fun open(): PdfRenderer {
        val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val document = try {
            PdfRenderer(fileDescriptor)
        } catch (error: Throwable) {
            runCatching { fileDescriptor.close() }
            throw error
        }
        if (document.pageCount != count) {
            document.close()
            throw IOException("PDF 页数已变化，请重新导入。")
        }
        return document
    }

    suspend fun close() {
        mutex.withLock {
            closed = true
            renderer?.close()
            renderer = null
        }
    }
}

private const val PDF_MAX_RENDER_DIMENSION = 3072
private const val PDF_MAX_RENDER_PIXELS = 4_194_304

internal fun pdfBitmapSize(pageWidth: Int, pageHeight: Int, viewport: IntSize): IntSize {
    require(pageWidth > 0 && pageHeight > 0 && viewport.width > 0 && viewport.height > 0)
    val fit = min(viewport.width.toDouble() / pageWidth, viewport.height.toDouble() / pageHeight)
    val pixelCap = sqrt(PDF_MAX_RENDER_PIXELS / (pageWidth.toDouble() * pageHeight))
    val dimensionCap = PDF_MAX_RENDER_DIMENSION.toDouble() / maxOf(pageWidth, pageHeight)
    val scale = minOf(fit * 1.5, pixelCap, dimensionCap)
    return IntSize((pageWidth * scale).toInt().coerceAtLeast(1), (pageHeight * scale).toInt().coerceAtLeast(1))
}

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data class Ready(val bitmap: Bitmap) : PdfPageState
    data class Failed(val message: String) : PdfPageState
}

@Composable
private fun PdfPageView(document: PdfDocumentReader, page: Int, onSwipe: (Int) -> Unit) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var retry by remember(page) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.clipToBounds(),
        contentAlignment = Alignment.Center) {
        if (viewport.width <= 0 || viewport.height <= 0) return@Box
        key(document, page, viewport, retry) {
            val displayed by produceState<PdfPageState>(PdfPageState.Loading) {
                value = try {
                    PdfPageState.Ready(document.render(page, viewport))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: SecurityException) {
                    PdfPageState.Failed("这个 PDF 受密码保护，无法打开。")
                } catch (_: OutOfMemoryError) {
                    PdfPageState.Failed("设备内存不足，无法显示这一页。")
                } catch (_: Exception) {
                    PdfPageState.Failed("这一页暂时无法显示，可重试或跳到其他页。")
                }
            }
            when (val state = displayed) {
                PdfPageState.Loading -> CircularProgressIndicator()
                is PdfPageState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PdfPageMessage(state.message)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
                is PdfPageState.Ready -> PdfZoomablePage(state.bitmap, viewport, page, onSwipe)
            }
        }
    }
}

@Composable
private fun PdfZoomablePage(bitmap: Bitmap, viewport: IntSize, page: Int, onSwipe: (Int) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var swipe by remember { mutableStateOf(Offset.Zero) }
    var wasZooming by remember { mutableStateOf(false) }
    val swipeDistance = with(LocalDensity.current) { 56.dp.toPx() }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val nextScale = (scale * zoom).coerceIn(1f, 4f)
        if (nextScale > 1f || scale > 1f || abs(zoom - 1f) > 0.02f) {
            wasZooming = true
            scale = nextScale
            val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
            val maxX = ((bitmap.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
            val maxY = ((bitmap.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        } else if (!wasZooming) swipe += pan
    }
    LaunchedEffect(transform.isTransformInProgress) {
        if (!transform.isTransformInProgress) {
            val movement = swipe
            val turn = !wasZooming && abs(movement.x) > swipeDistance && abs(movement.x) > abs(movement.y)
            swipe = Offset.Zero
            wasZooming = false
            if (turn) onSwipe(if (movement.x < 0f) 1 else -1)
        }
    }
    Box(Modifier.fillMaxSize().transformable(transform, lockRotationOnZoomPan = true),
        contentAlignment = Alignment.Center) {
        Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = "第 ${page + 1} 页",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            })
    }
}

@Composable
private fun PdfPageMessage(message: String) {
    Text(message, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PdfPageChooser(current: Int, count: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var input by remember { mutableStateOf((current + 1).toString()) }
    val page = input.toIntOrNull()?.takeIf { it in 1..count }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("跳到页面") }, text = {
        OutlinedTextField(value = input, onValueChange = { input = it.filter(Char::isDigit).take(9) },
            label = { Text("页码（1–$count）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }, confirmButton = {
        TextButton(onClick = { page?.let { onPage(it - 1) } }, enabled = page != null) { Text("跳转") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
