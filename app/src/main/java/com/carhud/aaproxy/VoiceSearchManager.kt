package com.carhud.aaproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Low-latency Vietnamese voice recognition tuned for Android Auto.
 *
 * - Reuses the recognizer between searches instead of recreating it every time.
 * - Ranks multiple Google hypotheses using confidence + active-app context.
 * - Biases Android 13+ recognition toward IPTV channels and app names.
 * - Partial results are preview-only; only final results execute an action.
 */
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

    private val diagnostics by lazy {
        context.applicationContext.getSharedPreferences("voice_recognition_diagnostics", Context.MODE_PRIVATE)
    }

    fun prewarm() = onMain {
        if (recognizer == null) recognizer = createRecognizer()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun startListening() = onMain {
        generation++
        val token = generation
        handler.removeCallbacksAndMessages(null)
        active = false
        retryCount = 0

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStateChanged(State.ERROR, "Chưa cấp quyền Micro. Vui lòng cấp quyền trong Cài đặt.")
            return@onMain
        }

        try { recognizer?.cancel() } catch (_: Exception) {}
        active = true
        startAttempt(token, recreate = false)
    }

    private fun startAttempt(token: Int, recreate: Boolean) {
        if (token != generation || !active) return
        onStateChanged(State.LISTENING, "Đang mở micro...")

        try {
            if (recreate) destroyRecognizer()

            val rec = recognizer ?: createRecognizer()?.also { recognizer = it }
            if (rec == null) {
                fail(token, "Thiết bị không có dịch vụ nhận diện giọng nói.")
                return
            }

            rec.setRecognitionListener(object : RecognitionListener {
                private fun current() = token == generation && active && recognizer === rec

                override fun onReadyForSpeech(params: Bundle?) {
                    if (current()) onStateChanged(State.LISTENING, "Đang lắng nghe... Hãy nói tự nhiên")
                }

                override fun onBeginningOfSpeech() {
                    if (current()) onStateChanged(State.RECOGNIZING, "Đang nghe...")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onEndOfSpeech() {
                    if (!current()) return
                    onStateChanged(State.RECOGNIZING, "Đang nhận diện...")
                    handler.postDelayed({
                        if (current()) fail(token, "Nhận diện quá lâu. Kiểm tra mạng và thử lại.")
                    }, 12_000L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!current()) return
                    val preview = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                    if (!preview.isNullOrBlank()) {
                        onStateChanged(State.RECOGNIZING, "🎙️ $preview")
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!current()) return

                    val hypotheses = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                    val activeAppId = CarMediaManager.activeAppId.ifBlank {
                        WebAppManager.getActiveAppId(context)
                    }
                    val channels = try {
                        IptvManager.getCachedChannelsList(context).map { it.name }
                    } catch (_: Throwable) {
                        emptyList()
                    }

                    val resolved = VoiceQueryResolver.resolve(
                        hypotheses = hypotheses,
                        confidences = confidences,
                        activeAppId = activeAppId,
                        channelNames = channels
                    )

                    if (resolved == null || resolved.text.isBlank()) {
                        fail(token, "Không nhận rõ giọng nói, vui lòng nói lại.")
                        return
                    }

                    active = false
                    handler.removeCallbacksAndMessages(null)
                    saveDiagnostics(hypotheses, confidences, resolved.text, activeAppId)

                    onStateChanged(State.SUCCESS, "🔍 ${resolved.text}")
                    onResult(resolved.text)
                    scheduleIdle(token)
                }

                override fun onError(error: Int) {
                    if (!current()) return
                    handler.removeCallbacksAndMessages(null)

                    val canRetry =
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == 13

                    if (canRetry && retryCount < 1) {
                        retryCount++
                        onStateChanged(State.LISTENING, "Đang mở lại micro...")
                        handler.postDelayed({
                            if (token == generation && active) startAttempt(token, recreate = true)
                        }, 250L)
                        return
                    }

                    fail(
                        token,
                        when (error) {
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Lỗi mạng, vui lòng kiểm tra kết nối rồi nói lại."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Chưa cấp quyền Micro."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Không nghe thấy giọng nói, vui lòng thử lại."
                            SpeechRecognizer.ERROR_AUDIO ->
                                "Lỗi thu âm micro, vui lòng thử lại."
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "Không nhận rõ giọng nói, vui lòng nói lại."
                            12, 13 ->
                                "Dịch vụ nhận diện chưa sẵn sàng cho tiếng Việt. Kiểm tra ứng dụng Google."
                            else ->
                                "Không thể nhận diện giọng nói ($error), vui lòng thử lại."
                        }
                    )
                }
            })

            rec.startListening(buildIntent())

            handler.postDelayed({
                if (token == generation && active && recognizer === rec) {
                    fail(token, "Hết thời gian nghe. Vui lòng bấm mic để nói lại.")
                }
            }, 28_000L)
        } catch (_: Exception) {
            fail(token, "Không thể mở micro. Kiểm tra dịch vụ Google và quyền Micro.")
        }
    }

    private fun buildIntent(): Intent {
        val activeAppId = CarMediaManager.activeAppId.ifBlank {
            WebAppManager.getActiveAppId(context)
        }

        val channels = try {
            IptvManager.getCachedChannelsList(context).map { it.name }
        } catch (_: Throwable) {
            emptyList()
        }

        val appNames = try {
            WebAppManager.getEnabledApps(context).map { it.name }
        } catch (_: Throwable) {
            emptyList()
        }

        val bias = VoiceQueryResolver.buildBiasStrings(
            activeAppId = activeAppId,
            channelNames = channels,
            appNames = appNames
        )

        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 7)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && bias.isNotEmpty()) {
                putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", bias)
                putExtra("android.speech.extra.ENABLE_BIASING_DEVICE_CONTEXT", true)
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fail(token: Int, message: String) {
        if (token != generation || !active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        onStateChanged(State.ERROR, message)
        scheduleIdle(token)
    }

    private fun scheduleIdle(token: Int) {
        handler.postDelayed({
            if (token == generation && !active) onStateChanged(State.IDLE, "")
        }, 1500L)
    }

    private fun saveDiagnostics(
        hypotheses: List<String>,
        confidences: FloatArray?,
        selected: String,
        activeAppId: String
    ) {
        try {
            val raw = hypotheses.take(7).mapIndexed { index, text ->
                val conf = confidences?.getOrNull(index)
                if (conf != null && conf >= 0f) {
                    "$index:${"%.2f".format(java.util.Locale.US, conf)}:$text"
                } else {
                    "$index:?:$text"
                }
            }.joinToString(" | ")

            diagnostics.edit()
                .putString("last_candidates", raw)
                .putString("last_selected", selected)
                .putString("last_active_app", activeAppId)
                .putLong("last_at", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun destroyRecognizer() {
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: Exception) {}
        try { old?.destroy() } catch (_: Exception) {}
    }

    fun stop() = onMain {
        generation++
        active = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        onStateChanged(State.IDLE, "")
    }
}
