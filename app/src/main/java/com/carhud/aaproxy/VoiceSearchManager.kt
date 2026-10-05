package com.carhud.aaproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Partial hypotheses are previews only; only final results execute a search. */
class VoiceSearchManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStateChanged: (State, String) -> Unit
) {
    enum class State { IDLE, LISTENING, RECOGNIZING, SUCCESS, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var active = false
    private var retryCount = 0

    fun prewarm() = onMain {
        if (recognizer == null) try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {}
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun startListening() = onMain {
        generation++
        handler.removeCallbacksAndMessages(null)
        active = false
        releaseRecognizer()
        retryCount = 0
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStateChanged(State.ERROR, "Chưa cấp quyền Micro. Vui lòng cấp quyền trong Cài đặt.")
            return@onMain
        }
        active = true
        startAttempt(generation)
    }

    private fun startAttempt(token: Int) {
        if (token != generation || !active) return
        onStateChanged(State.LISTENING, "Đang mở micro... Hãy nói sau khi hiện Đang lắng nghe")
        try {
            val rec = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                private fun current() = token == generation && active && recognizer === rec
                override fun onReadyForSpeech(params: Bundle?) {
                    if (current()) onStateChanged(State.LISTENING, "Đang lắng nghe... Hãy nói đầy đủ câu tìm kiếm")
                }
                override fun onBeginningOfSpeech() {
                    if (current()) onStateChanged(State.RECOGNIZING, "Đang nghe...")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() {
                    if (!current()) return
                    onStateChanged(State.RECOGNIZING, "Đang nhận diện câu đầy đủ...")
                    // End of speech is not a final transcript; allow the service to correct it.
                    handler.postDelayed({
                        if (current()) fail(token, "Nhận diện quá lâu. Kiểm tra mạng và thử lại.")
                    }, 12000L)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!current()) return
                    val preview = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }?.trim()
                    if (!preview.isNullOrEmpty()) onStateChanged(State.RECOGNIZING, "🎙️ $preview")
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }?.trim()
                    if (query.isNullOrEmpty()) {
                        fail(token, "Không nhận rõ giọng nói, vui lòng nói lại.")
                        return
                    }
                    active = false
                    handler.removeCallbacksAndMessages(null)
                    releaseRecognizer()
                    onStateChanged(State.SUCCESS, "🔍 Đang tìm: $query")
                    onResult(query)
                    scheduleIdle(token)
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    handler.removeCallbacksAndMessages(null)
                    releaseRecognizer()
                    if ((error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 13) && retryCount < 1) {
                        retryCount++
                        onStateChanged(State.LISTENING, "Đang mở lại micro...")
                        handler.postDelayed({ startAttempt(token) }, 400L)
                        return
                    }
                    fail(token, when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng, vui lòng kiểm tra kết nối rồi nói lại."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không nghe thấy giọng nói, vui lòng thử lại."
                        SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm micro, vui lòng thử lại."
                        SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận rõ giọng nói, vui lòng nói lại."
                        12, 13 -> "Dịch vụ nhận diện chưa có tiếng Việt. Kiểm tra ứng dụng Google và ngôn ngữ giọng nói."
                        else -> "Không thể nhận diện giọng nói ($error), vui lòng thử lại."
                    })
                }
            })
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                // Use natural pause detection instead of truncating speech after 280ms.
            })
            handler.postDelayed({
                if (token == generation && active && recognizer === rec) fail(token, "Hết thời gian nghe. Vui lòng bấm mic để nói lại.")
            }, 30000L)
        } catch (_: Exception) {
            fail(token, "Không thể mở micro. Kiểm tra dịch vụ nhận diện và quyền Micro.")
        }
    }

    private fun fail(token: Int, message: String) {
        if (token != generation || !active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        onStateChanged(State.ERROR, message)
        scheduleIdle(token)
    }

    private fun scheduleIdle(token: Int) {
        handler.postDelayed({ if (token == generation && !active) onStateChanged(State.IDLE, "") }, 1800L)
    }

    private fun releaseRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: Exception) {}
        try { old?.destroy() } catch (_: Exception) {}
    }

    fun stop() = onMain {
        generation++
        active = false
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        onStateChanged(State.IDLE, "")
    }
}
