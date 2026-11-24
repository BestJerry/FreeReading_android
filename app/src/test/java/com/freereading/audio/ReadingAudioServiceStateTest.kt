package com.freereading.audio

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.freereading.ReadingApplication
import com.freereading.data.BookRecord
import com.freereading.data.SpeechBook
import com.freereading.data.SpeechSentence
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Real service/player state changes, without preparing audio or initializing any TTS engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReadingApplication::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ReadingAudioServiceStateTest {
    private lateinit var controller: ServiceController<ReadingAudioService>
    private lateinit var service: ReadingAudioService
    private lateinit var player: ExoPlayer

    @Before fun createEmptyPlayerService() {
        controller = Robolectric.buildService(ReadingAudioService::class.java).create()
        service = controller.get()
        player = ReflectionHelpers.getField(service, "player")
        val sentences = listOf(
            SpeechSentence(0, "甲乙", "{}", 0, "本章"),
            SpeechSentence(1, "丙丁", "{}", 0, "本章"),
        )
        val book = SpeechBook(BookRecord("book-a", "测试书籍", "作者", "zh", 0, sentences.size), sentences)
        ReflectionHelpers.setField(service, "book", book)
        ReflectionHelpers.setField(service, "chapterProgressIndex", ChapterProgressIndex(sentences))
        assertEquals(0, player.mediaItemCount)
    }

    @After fun releaseServiceWithoutSavingTheFixtureBook() {
        ReflectionHelpers.setField(service, "book", null)
        ReflectionHelpers.setField(service, "transitionToken", null)
        controller.destroy()
    }

    @Test fun failedTransitionAndPeriodicTicksPreserveTheRequestedCursorWithAnEmptyPlayer() {
        val requested = pendingSnapshot()
        PlaybackHub.publish(requested)
        ReflectionHelpers.setField(service, "transitionToken", 7L)

        ReflectionHelpers.callInstanceMethod<Unit>(service, "reportFailure",
            ClassParameter.from(Exception::class.java, AudioFailure("测试语音合成失败")))
        val afterFailure = PlaybackHub.state.value
        assertEquals(requested.copy(isPreparing = false, error = "测试语音合成失败"), afterFailure)

        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals(0, player.mediaItemCount)
        assertEquals(afterFailure, PlaybackHub.state.value)
    }

    @Test fun changingActualPlayerSpeedWhilePreparingPublishesSpeedWithoutChangingTheCursor() {
        val pending = pendingSnapshot().copy(wantsPlayback = true)
        PlaybackHub.publish(pending)
        ReflectionHelpers.setField(service, "transitionToken", 8L)

        player.setPlaybackSpeed(1.5f)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1.5f, player.playbackParameters.speed, 0f)
        assertEquals(pending.copy(speed = 1.5f), PlaybackHub.state.value)
    }

    @Test fun systemMediaControlUpdatesPlaybackIntentDuringPreparationWithoutReplacingTheSession() {
        val accepted = pendingSnapshot().copy(playbackSessionId = PlaybackHub.newPlaybackSessionId())
        PlaybackHub.publish(accepted)
        ReflectionHelpers.setField(service, "transitionToken", 9L)
        val session: MediaSession = ReflectionHelpers.getField(service, "session")

        session.player.play()

        val resumed = PlaybackHub.state.value
        assertEquals(accepted.copy(wantsPlayback = true), resumed)
        session.player.play()
        assertEquals(resumed, PlaybackHub.state.value)
        session.player.pause()
        assertEquals(accepted, PlaybackHub.state.value)
        session.player.playWhenReady = true
        assertEquals(resumed, PlaybackHub.state.value)
    }

    @Test fun miniPlayerTogglePublishesWhetherPendingSpeechShouldBePlayed() {
        val accepted = pendingSnapshot()
        PlaybackHub.publish(accepted)
        ReflectionHelpers.setField(service, "transitionToken", 10L)
        val command = PlaybackHub.command(service, ReadingAudioService.ACTION_TOGGLE)

        service.onStartCommand(command, 0, 1)

        assertEquals(accepted.copy(wantsPlayback = true), PlaybackHub.state.value)
        service.onStartCommand(command, 0, 2)
        assertEquals(accepted, PlaybackHub.state.value)
    }

    @Test fun aNewBookStartPublishesPlaybackIntentBeforeTheSavedSentenceIsResolved() {
        PlaybackHub.publish(pendingSnapshot())
        val sessionId = PlaybackHub.newPlaybackSessionId()

        ReflectionHelpers.callInstanceMethod<Unit>(service, "playBook",
            ClassParameter.from(String::class.java, "another-book"),
            ClassParameter.from(Int::class.javaPrimitiveType, -1),
            ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            ClassParameter.from(String::class.java, sessionId))

        val starting = PlaybackHub.state.value
        assertEquals("another-book", starting.bookId)
        assertEquals(sessionId, starting.playbackSessionId)
        assertEquals(0, starting.sentenceCount)
        assertEquals(true, starting.wantsPlayback)
    }

    private fun pendingSnapshot() = PlaybackSnapshot(bookId = "book-a", bookTitle = "测试书籍",
        sentenceIndex = 1, sentenceCount = 2, chapterTitle = "本章", text = "丙丁",
        positionMs = 5_000, durationMs = 10_000, chapterProgress = 0.75f,
        chapterSentenceCount = 2, isPreparing = true)
}
