package com.freereading.reader

import android.os.Looper
import com.freereading.ReadingApplication
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.audio.ReadingAudioService
import com.freereading.data.BookFormat
import com.freereading.data.BookRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
class PdfReaderAudioStartTest {
    private lateinit var app: ReadingApplication
    private lateinit var controller: ActivityController<PdfReaderActivity>
    private lateinit var activity: PdfReaderActivity
    private var wasAlive = false
    private val book = BookRecord("pdf-audio-probe", "PDF", "", "en", 1, 1,
        format = BookFormat.PDF, pdfPageCount = 2)

    @Before fun openReader() {
        app = RuntimeEnvironment.getApplication() as ReadingApplication
        controller = Robolectric.buildActivity(PdfReaderActivity::class.java,
            PdfReaderActivity.intent(app, book.id, openPlayer = true)).create()
        activity = controller.get()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setRecord",
            ClassParameter.from(BookRecord::class.java, book))
        wasAlive = PlaybackHub.serviceAlive
        shadowOf(app).clearStartedServices()
    }

    @After fun closeReader() {
        controller.destroy()
        PlaybackHub.publish(PlaybackSnapshot())
        PlaybackHub.serviceAlive = wasAlive
    }

    @Test fun stoppingPlaybackCancelsAnExplicitStartWaitingForTheSpeechIndex() {
        PlaybackHub.serviceAlive = true
        PlaybackHub.publish(PlaybackSnapshot(bookId = book.id, wantsPlayback = true))
        val loadingIndex = CompletableDeferred<Any>()
        ReflectionHelpers.setField(activity, "audioIndexTask", loadingIndex)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "playCurrentPage",
            ClassParameter.from(Boolean::class.java, true))
        shadowOf(Looper.getMainLooper()).idle()
        val start = ReflectionHelpers.getField<Job>(activity, "audioStartTask")
        assertTrue(start.isActive)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "stopListening")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(start.isCancelled)
        assertTrue(loadingIndex.isActive)
        assertNull(ReflectionHelpers.getField<Job?>(activity, "audioStartTask"))
        assertFalse(startingAudio())
        assertEquals(ReadingAudioService.ACTION_STOP, shadowOf(app).nextStartedService?.action)
        assertNull(shadowOf(app).nextStartedService)
        loadingIndex.cancel()
    }

    @Test fun pendingStartWaitsForOlderSessionAndClearsWhenAnotherBookTakesOver() {
        val earlier = PlaybackHub.newPlaybackSessionId()
        val requested = PlaybackHub.newPlaybackSessionId()
        val later = PlaybackHub.newPlaybackSessionId()
        ReflectionHelpers.setField(activity, "pendingStartId", requested)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setStartingAudio",
            ClassParameter.from(Boolean::class.java, true))
        PlaybackHub.publish(PlaybackSnapshot(bookId = "other-book", playbackSessionId = earlier))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "synchronizeAudio")
        assertTrue(startingAudio())

        PlaybackHub.publish(PlaybackSnapshot(bookId = "other-book", playbackSessionId = later))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "synchronizeAudio")

        assertNull(ReflectionHelpers.getField<String?>(activity, "pendingStartId"))
        assertFalse(startingAudio())
    }

    private fun startingAudio(): Boolean = ReflectionHelpers.callInstanceMethod(activity, "getStartingAudio")
}
