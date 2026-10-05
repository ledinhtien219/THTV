package com.carhud.aaproxy

import android.Manifest
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSearchManagerTest {
    private lateinit var manager: VoiceSearchManager
    private val results = mutableListOf<String>()
    private val states = mutableListOf<VoiceSearchManager.State>()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        manager = VoiceSearchManager(app, { results.add(it) }, { state, _ -> states.add(state) })
        manager.startListening()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun speech() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    private fun bundle(vararg text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*text))
    }
    @Test fun pauseInLongVietnameseSentenceDoesNotExecutePartial() {
        val speech = speech()
        speech.triggerOnPartialResults(bundle("mở bài"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertTrue(results.isEmpty())
        speech.triggerOnPartialResults(bundle("mở bài hát của Sơn"))
        speech.triggerOnEndOfSpeech()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(results.isEmpty())
        speech.triggerOnResults(bundle("mở bài hát của Sơn Tùng"))
        assertEquals(listOf("mở bài hát của Sơn Tùng"), results)
    }
    @Test fun networkErrorNeverSearchesUnconfirmedPartial() {
        speech().triggerOnPartialResults(bundle("mở bài hát sai"))
        speech().triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(results.isEmpty())
        assertEquals(VoiceSearchManager.State.ERROR, states.last())
    }
    @Test fun cancelledSessionCannotDispatchLateFinalOrRetry() {
        val old = speech()
        manager.stop()
        old.triggerOnResults(bundle("câu cũ"))
        manager.startListening()
        shadowOf(Looper.getMainLooper()).idle()
        old.triggerOnResults(bundle("câu cũ muộn"))
        speech().triggerOnResults(bundle("câu mới đầy đủ"))
        assertEquals(listOf("câu mới đầy đủ"), results)
    }
    @Test fun busyServiceRetriesOnlyOnceEvenAfterReady() {
        speech().triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        speech().triggerOnReadyForSpeech(Bundle())
        speech().triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        assertEquals(VoiceSearchManager.State.ERROR, states.last())
        assertTrue(results.isEmpty())
    }
    @Test fun finalTimeoutRestoresIdleWithoutExecutingPartial() {
        speech().triggerOnPartialResults(bundle("câu chưa xác nhận"))
        speech().triggerOnEndOfSpeech()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
        assertEquals(VoiceSearchManager.State.ERROR, states.last())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(VoiceSearchManager.State.IDLE, states.last())
        assertTrue(results.isEmpty())
    }
    @Test fun intentUsesVietnameseAndNaturalPauseDetection() {
        val intent = speech().lastRecognizerIntent
        assertEquals("vi-VN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS))
    }
}
