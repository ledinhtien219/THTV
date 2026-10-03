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
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Ultra-Low Latency Voice Search Engine.
 * 
 * Features:
 * 1) 0ms instant dispatch on end of speech using partial stream cache.
 * 2) 350ms debounced auto-commit on stable partial speech phrases.
 * 3) Aggressive silence detection (300-500ms) to eliminate dead network waiting time.
 */
class VoiceSearchManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStateChanged: (State, String) -> Unit
) {
    enum class State {
        IDLE,
        LISTENING,
        RECOGNIZING,
        SUCCESS,
        ERROR
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var lastRecognizedText: String? = null
    private var hasDispatchedResult = false
    private var isListeningActive = false
    private var retryCount = 0

    private val autoCommitRunnable = Runnable {
        val candidate = lastRecognizedText?.trim()
        if (!hasDispatchedResult && !candidate.isNullOrEmpty() && candidate.length >= 2) {
            Log.d("VoiceSearchManager", "Ultra-low latency auto-commit: $candidate")
            dispatchSuccess(candidate)
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    init {
        prewarm()
    }

    fun prewarm() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureRecognizer()
        } else {
            handler.post { ensureRecognizer() }
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer != null) return recognizer
        try {
            recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            attachListener()
        } catch (e: Exception) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                attachListener()
            } catch (ex: Exception) {
                recognizer = null
            }
        }
        return recognizer
    }

    private fun attachListener() {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListeningActive = true
                retryCount = 0
                onStateChanged(State.LISTENING, "Đang lắng nghe... Hãy nói tên bài hát")
            }

            override fun onBeginningOfSpeech() {
                lastRecognizedText = null
                hasDispatchedResult = false
                handler.removeCallbacks(autoCommitRunnable)
                onStateChanged(State.RECOGNIZING, "Đang nghe...")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListeningActive = false
                handler.removeCallbacks(autoCommitRunnable)
                
                // INSTANT 0ms DISPATCH: Do not wait for slow server onResults if partial text exists!
                val fallback = lastRecognizedText?.trim()
                if (!hasDispatchedResult && !fallback.isNullOrEmpty() && fallback.length >= 2) {
                    dispatchSuccess(fallback)
                    return
                }

                onStateChanged(State.RECOGNIZING, "Đang xử lý...")
                try {
                    recognizer?.stopListening()
                } catch (_: Exception) {}
            }

            override fun onError(error: Int) {
                Log.w("VoiceSearchManager", "SpeechRecognizer onError code: $error")
                isListeningActive = false
                handler.removeCallbacks(autoCommitRunnable)

                val fallback = lastRecognizedText?.trim()
                if (!hasDispatchedResult && !fallback.isNullOrEmpty() && fallback.length >= 2) {
                    dispatchSuccess(fallback)
                    return
                }

                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 13) {
                    cleanupRecognizer()
                    if (retryCount < 1) {
                        retryCount++
                        handler.postDelayed({
                            startListening()
                        }, 200L)
                        return
                    }
                }
                retryCount = 0

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận rõ giọng nói, vui lòng thử lại"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không nghe thấy giọng nói"
                    SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm Micro"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi kết nối mạng"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Trình nhận diện đang bận"
                    SpeechRecognizer.ERROR_CLIENT -> "Đang mở lại micro, hãy thử lại"
                    13 -> "Chưa mở ứng dụng Google hoặc chưa cấp quyền Micro"
                    else -> "Lỗi nhận diện ($error)"
                }
                onStateChanged(State.ERROR, errorMsg)
                handler.postDelayed({ onStateChanged(State.IDLE, "") }, 1600)
            }

            override fun onResults(results: Bundle?) {
                isListeningActive = false
                handler.removeCallbacks(autoCommitRunnable)
                if (hasDispatchedResult) return

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var query = matches?.firstOrNull()?.trim()

                if (query.isNullOrEmpty()) {
                    query = lastRecognizedText?.trim()
                }

                if (!query.isNullOrEmpty()) {
                    dispatchSuccess(query)
                } else {
                    onStateChanged(State.ERROR, "Không nhận diện được giọng nói")
                    handler.postDelayed({ onStateChanged(State.IDLE, "") }, 1200)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0].trim()
                    if (partial.isNotEmpty()) {
                        lastRecognizedText = partial
                        onStateChanged(State.RECOGNIZING, "🎙️ $partial")

                        // Debounce 250ms: if partial result is stable and contains 2+ words or numbers, auto-commit
                        handler.removeCallbacks(autoCommitRunnable)
                        if (partial.contains(" ") || partial.length >= 4) {
                            handler.postDelayed(autoCommitRunnable, 250L)
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun cleanupRecognizer() {
        isListeningActive = false
        handler.removeCallbacks(autoCommitRunnable)
        try {
            recognizer?.setRecognitionListener(null)
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }

    fun startListening() {
        val action = Runnable {
            try {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    onStateChanged(State.ERROR, "Chưa cấp quyền Micro! Vui lòng cấp quyền trong Cài đặt.")
                    handler.postDelayed({ onStateChanged(State.IDLE, "") }, 2500)
                    return@Runnable
                }

                lastRecognizedText = null
                hasDispatchedResult = false
                handler.removeCallbacks(autoCommitRunnable)

                onStateChanged(State.LISTENING, "Đang lắng nghe... Hãy nói tên bài hát")

                // Keep existing recognizer warm instead of destroying IPC binder on every click!
                try {
                    recognizer?.cancel()
                } catch (_: Exception) {}
                val rec = ensureRecognizer()
                if (rec == null) {
                    onStateChanged(State.ERROR, "Thiết bị không hỗ trợ nhận diện giọng nói!")
                    handler.postDelayed({ onStateChanged(State.IDLE, "") }, 2000)
                    return@Runnable
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("vi-VN", "vi", "en-US"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    
                    // ULTRA-LOW LATENCY SILENCE SETTINGS (300ms - 400ms complete silence threshold)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 280L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 150L)
                }

                rec.startListening(intent)
            } catch (e: Exception) {
                cleanupRecognizer()
                try {
                    val fallbackRec = ensureRecognizer()
                    fallbackRec?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    })
                } catch (ex: Exception) {
                    onStateChanged(State.ERROR, "Không thể mở micro: ${ex.message}")
                    handler.postDelayed({ onStateChanged(State.IDLE, "") }, 2000)
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            handler.post(action)
        }
    }

    private fun dispatchSuccess(query: String) {
        if (hasDispatchedResult) return
        hasDispatchedResult = true
        isListeningActive = false
        retryCount = 0
        handler.removeCallbacks(autoCommitRunnable)
        onStateChanged(State.SUCCESS, "🔍 Đang tìm: $query")
        onResult(query)
        handler.postDelayed({ onStateChanged(State.IDLE, "") }, 1200)
    }

    fun stop() {
        val action = Runnable {
            handler.removeCallbacks(autoCommitRunnable)
            try {
                recognizer?.stopListening()
                recognizer?.cancel()
            } catch (_: Exception) {}
            isListeningActive = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            handler.post(action)
        }
    }
}
