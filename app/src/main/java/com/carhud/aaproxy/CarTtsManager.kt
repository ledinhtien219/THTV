package com.carhud.aaproxy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Automotive Vietnamese voice alerts for Waze/HLP data.
 *
 * The voice policy intentionally avoids speaking every telemetry update. It only
 * announces a new warning or when the vehicle crosses a meaningful distance
 * threshold, and uses overspeed hysteresis to avoid repeated chatter around the
 * exact speed limit.
 */
object CarTtsManager : TextToSpeech.OnInitListener {

    private const val TAG = "CarTtsManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var tts: TextToSpeech? = null
    @Volatile private var alertAudioTrack: AudioTrack? = null
    private var toneSequenceJob: Job? = null
    private var isInitialized = false
    private var isVietnameseSupported = false

    private var lastSpokenText: String = ""
    private var lastSpokenTime: Long = 0L

    private var lastAlertKey: String = ""
    private var lastAlertDistanceBucket: Int = Int.MIN_VALUE
    private var lastAlertSpeakTime: Long = 0L

    private var wasOverSpeeding = false
    private var lastOverspeedLimit: Int? = null
    private var lastOverspeedSeverity = 0
    private var lastOverspeedSpeakTime = 0L

    private const val SAME_TEXT_COOLDOWN_MS = 8_000L
    private const val ALERT_BUCKET_COOLDOWN_MS = 5_000L
    private const val OVERSPEED_REPEAT_MS = 30_000L
    private const val TONE_COOLDOWN_MS = 700L
    private var lastToneTime: Long = 0L

    private var appContext: Context? = null
    private var listenJob: Job? = null
    private var pendingSpeakText: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            try {
                Log.i(TAG, "Initializing TextToSpeech engine...")
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TextToSpeech", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val viLocale = Locale("vi", "VN")
            val result = tts?.setLanguage(viLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Vietnamese TTS not fully supported, trying default device locale")
                tts?.setLanguage(Locale.getDefault())
                isVietnameseSupported = false
            } else {
                isVietnameseSupported = true
            }

            // Slightly brisk, but still intelligible in a moving vehicle.
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)

            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set audio attributes", e)
            }

            try {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId?.startsWith("alert_") == true) {
                            CarMediaManager.beginNavigationDucking()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith("alert_") == true) {
                            CarMediaManager.endNavigationDucking()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith("alert_") == true) {
                            CarMediaManager.endNavigationDucking()
                        }
                    }
                })
            } catch (_: Exception) {}

            isInitialized = true
            pendingSpeakText?.let {
                speakAlert(it, isPriority = true)
                pendingSpeakText = null
            }
        } else {
            Log.e(TAG, "TextToSpeech onInit failed with status $status")
            isInitialized = true
        }
    }

    fun startListeningToRepository() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            VietmapStateRepository.alertState.collectLatest { alert ->
                handleAlertState(alert)
            }
        }
    }

    fun handleAlertState(alert: VietmapAlertData) {
        val ctx = appContext
        if (ctx != null && !WazeHudManager.isMasterAlertsEnabled(ctx)) return

        val audioMode = ctx?.let { WazeHudManager.getAlertAudioMode(it) }
            ?: WazeHudManager.ALERT_AUDIO_TONE
        if (audioMode == WazeHudManager.ALERT_AUDIO_OFF) return

        if ((audioMode == WazeHudManager.ALERT_AUDIO_VOICE ||
                audioMode == WazeHudManager.ALERT_AUDIO_BOTH) &&
            (!isInitialized || tts == null)
        ) {
            ctx?.let { init(it) }
        }

        val now = System.currentTimeMillis()

        // 1) Overspeed has highest voice priority.
        val overspeedAllowed = ctx == null ||
            (WazeHudManager.isOverspeedAlertEnabled(ctx) && WazeHudManager.isOverspeedAudioEnabled(ctx))

        if (alert.isOverSpeed && overspeedAllowed) {
            val limit = alert.speedLimit
            val severity = WazeAlertPolicy.overspeedSeverity(alert.currentSpeed, limit)
            val shouldSpeak = !wasOverSpeeding ||
                limit != lastOverspeedLimit ||
                severity > lastOverspeedSeverity ||
                (severity >= 2 && now - lastOverspeedSpeakTime >= OVERSPEED_REPEAT_MS)

            wasOverSpeeding = true
            lastOverspeedLimit = limit
            lastOverspeedSeverity = severity

            if (shouldSpeak) {
                lastOverspeedSpeakTime = now
                val limitPhrase = limit?.let { " $it ki lô mét trên giờ" }.orEmpty()
                playConfiguredAlert(
                    "Cảnh báo! Bạn đang vượt quá tốc độ cho phép$limitPhrase.",
                    isPriority = true
                )
                return
            }
        } else {
            wasOverSpeeding = false
            lastOverspeedLimit = null
            lastOverspeedSeverity = 0
        }

        // 2) Road warning. Navigation maneuvers are intentionally not read here;
        // Waze itself already handles turn guidance and duplicating it is distracting.
        val rawTitle = alert.alertTitle
            ?.replace("VML-TPMS", "")
            ?.replace("TPMS", "")
            ?.trim()

        val cleanAlertTitle = when {
            !rawTitle.isNullOrBlank() && !WazeHlpWebSocketManager.isIgnoredText(rawTitle) -> rawTitle
            !alert.alertDescription.isNullOrBlank() -> alert.alertDescription?.trim()
            else -> null
        }

        val effWarning = when {
            alert.warningType != VietmapWarningType.NONE -> alert.warningType
            !cleanAlertTitle.isNullOrBlank() -> {
                VietmapIconClassifier.classifyFromText(cleanAlertTitle)
                    .takeIf { it != VietmapWarningType.NONE }
                    ?: WazeHlpWebSocketManager.mapWarningType(cleanAlertTitle)
            }
            else -> VietmapWarningType.NONE
        }

        val alertFresh = WazeAlertPolicy.isAlertFresh(alert, now)
        val categoryAllowed = ctx == null ||
            !WazeAlertPolicy.isCameraCategory(effWarning) ||
            WazeHudManager.isSpeedCameraEnabled(ctx)
        val isSpeakableRoadWarning = WazeAlertPolicy.isRoadHazard(effWarning) ||
            (effWarning == VietmapWarningType.NONE && !cleanAlertTitle.isNullOrBlank())

        if (!alertFresh || !categoryAllowed || !isSpeakableRoadWarning) {
            if (!alertFresh) {
                lastAlertKey = ""
                lastAlertDistanceBucket = Int.MIN_VALUE
            }
            return
        }

        var rawPhrase = when {
            !cleanAlertTitle.isNullOrBlank() &&
                cleanAlertTitle.length >= 3 &&
                !cleanAlertTitle.contains("Waze Mod", ignoreCase = true) -> cleanAlertTitle
            effWarning.voicePhrase.isNotBlank() -> effWarning.voicePhrase
            effWarning.label.isNotBlank() -> effWarning.label
            else -> return
        }

        // Speed value is useful for camera/speed-zone warnings but noisy for other hazards.
        if (effWarning in setOf(
                VietmapWarningType.SPEED_CAMERA,
                VietmapWarningType.RED_LIGHT_CAMERA,
                VietmapWarningType.SPEED_LIMIT_ZONE
            )
        ) {
            val limit = alert.speedLimit
            if (limit != null && limit > 0 &&
                !rawPhrase.contains("km/h", ignoreCase = true) &&
                !rawPhrase.contains(limit.toString())
            ) {
                rawPhrase += " $limit km/h"
            }
        }

        val distanceMeters = WazeAlertPolicy.effectiveAlertDistanceMeters(alert)
        val bucket = WazeAlertPolicy.distanceBucket(effWarning, distanceMeters)
        val key = WazeAlertPolicy.alertKey(effWarning, cleanAlertTitle, alert.roadName)

        val isNewAlert = key != lastAlertKey
        val crossedMeaningfulDistance = !isNewAlert &&
            bucket != lastAlertDistanceBucket &&
            bucket < lastAlertDistanceBucket &&
            now - lastAlertSpeakTime >= ALERT_BUCKET_COOLDOWN_MS

        if (!isNewAlert && !crossedMeaningfulDistance) return

        val distText = WazeAlertPolicy.formatDistance(distanceMeters)
        val spokenDist = formatDistanceForVoice(distText)
        val spokenPhrase = formatPhraseForVoice(rawPhrase)

        val fullSentence = when {
            spokenDist.isNotEmpty() && (spokenPhrase.startsWith("Chú ý", true) || spokenPhrase.startsWith("Cảnh báo", true)) ->
                "$spokenPhrase, cách $spokenDist."
            spokenDist.isNotEmpty() -> "Chú ý! $spokenPhrase, cách $spokenDist."
            spokenPhrase.startsWith("Chú ý", true) || spokenPhrase.startsWith("Cảnh báo", true) -> "$spokenPhrase."
            else -> "Chú ý! $spokenPhrase."
        }

        lastAlertKey = key
        lastAlertDistanceBucket = bucket
        lastAlertSpeakTime = now
        playConfiguredAlert(fullSentence, isPriority = WazeAlertPolicy.isCritical(effWarning))
    }

    private fun playConfiguredAlert(text: String, isPriority: Boolean = false) {
        val ctx = appContext
        val mode = ctx?.let { WazeHudManager.getAlertAudioMode(it) }
            ?: WazeHudManager.ALERT_AUDIO_TONE

        when (mode) {
            WazeHudManager.ALERT_AUDIO_TONE -> playAlertTone(isPriority)
            WazeHudManager.ALERT_AUDIO_VOICE -> speakAlert(text, isPriority)
            WazeHudManager.ALERT_AUDIO_BOTH -> {
                playAlertTone(isPriority)
                speakAlert(text, isPriority)
            }
        }
    }

    private fun releaseAlertAudioTrack() {
        try {
            alertAudioTrack?.let { track ->
                try { track.stop() } catch (_: Exception) {}
                try { track.flush() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            alertAudioTrack = null
        }
    }

    fun playAlertTone(isPriority: Boolean = false) {
        playAlertTone(null, isPriority)
    }

    fun playAlertTone(overrideToneStyle: String?, isPriority: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!isPriority && now - lastToneTime < TONE_COOLDOWN_MS) return
        lastToneTime = now

        val ctx = appContext
        val toneStyle = overrideToneStyle ?: (ctx?.let { WazeHudManager.getAlertToneStyle(it) }
            ?: WazeHudManager.ALERT_TONE_BEEP)

        toneSequenceJob?.cancel()
        releaseAlertAudioTrack()

        toneSequenceJob = scope.launch(Dispatchers.Default) {
            var localTrack: AudioTrack? = null
            try {
                val segments: List<Triple<Double, Int, Int>> = when (toneStyle) {
                    WazeHudManager.ALERT_TONE_DOUBLE_BEEP -> listOf(
                        Triple(740.0, 95, 80),
                        Triple(1120.0, 95, 0)
                    )
                    WazeHudManager.ALERT_TONE_ACK -> listOf(
                        Triple(1280.0, 70, 55),
                        Triple(960.0, 70, 55),
                        Triple(1280.0, 105, 0)
                    )
                    WazeHudManager.ALERT_TONE_PROMPT -> listOf(
                        Triple(620.0, 260, 90),
                        Triple(1080.0, 90, 0)
                    )
                    WazeHudManager.ALERT_TONE_STRONG -> listOf(
                        Triple(520.0, 120, 75),
                        Triple(760.0, 120, 75),
                        Triple(980.0, 300, 0)
                    )
                    else -> listOf(
                        Triple(960.0, 120, 0)
                    )
                }

                val sampleRate = 44_100
                val peakAmplitude = 0.24
                val totalSamples = segments.sumOf { segment ->
                    (segment.second + segment.third) * sampleRate / 1000
                }.coerceAtLeast(1)

                val pcm = ShortArray(totalSamples)
                var cursor = 0

                for ((frequencyHz, durationMs, gapMs) in segments) {
                    val toneSamples = (durationMs * sampleRate / 1000).coerceAtLeast(1)
                    val fadeSamples = minOf(sampleRate / 200, toneSamples / 4).coerceAtLeast(1)

                    for (i in 0 until toneSamples) {
                        val envelope = when {
                            i < fadeSamples -> i.toDouble() / fadeSamples.toDouble()
                            i >= toneSamples - fadeSamples ->
                                (toneSamples - i - 1).coerceAtLeast(0).toDouble() / fadeSamples.toDouble()
                            else -> 1.0
                        }
                        val wave = sin(
                            2.0 * PI * frequencyHz * i.toDouble() / sampleRate.toDouble()
                        )
                        val value = (wave * Short.MAX_VALUE * peakAmplitude * envelope)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

                        if (cursor < pcm.size) {
                            pcm[cursor++] = value.toShort()
                        }
                    }

                    val silenceSamples = gapMs * sampleRate / 1000
                    cursor = (cursor + silenceSamples).coerceAtMost(pcm.size)
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()

                localTrack = track
                alertAudioTrack = track

                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    Log.w(TAG, "Alert PCM write failed: $written")
                    return@launch
                }

                CarMediaManager.beginNavigationDucking()
                track.play()
                val totalDurationMs = segments.sumOf { it.second + it.third }.toLong()
                delay(totalDurationMs + 80L)
                Log.i(TAG, "Car alert PCM pattern: $toneStyle")
            } catch (e: Exception) {
                Log.e(TAG, "Alert tone failed", e)
            } finally {
                localTrack?.let { track ->
                    try { track.stop() } catch (_: Exception) {}
                    try { track.flush() } catch (_: Exception) {}
                    try { track.release() } catch (_: Exception) {}
                    if (alertAudioTrack === track) {
                        alertAudioTrack = null
                    }
                    CarMediaManager.endNavigationDucking()
                }
            }
        }
    }

    fun speakAlert(text: String, isPriority: Boolean = false) {
        if (text.isBlank()) return
        if (!isInitialized || tts == null) {
            pendingSpeakText = text
            return
        }

        val now = System.currentTimeMillis()
        if (!isPriority && text == lastSpokenText && now - lastSpokenTime < SAME_TEXT_COOLDOWN_MS) {
            return
        }

        lastSpokenText = text
        lastSpokenTime = now

        try {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            val queueMode = if (isPriority) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, queueMode, params, "alert_${System.currentTimeMillis()}")
            Log.i(TAG, "CarTTS: $text")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
        }
    }

    fun speakTestWarning(
        context: Context,
        warningType: VietmapWarningType,
        isOverspeed: Boolean = false,
        customPhrase: String? = null
    ) {
        appContext = context.applicationContext
        val mode = WazeHudManager.getAlertAudioMode(context)
        if (mode == WazeHudManager.ALERT_AUDIO_VOICE || mode == WazeHudManager.ALERT_AUDIO_BOTH) {
            init(context)
        }
        if (isOverspeed) {
            playConfiguredAlert("Cảnh báo! Bạn đang vượt quá tốc độ cho phép 60!", isPriority = true)
            return
        }
        val phrase = customPhrase ?: warningType.voicePhrase
        if (phrase.isNotBlank()) {
            val voiceText = if (phrase.startsWith("Chú ý", true) || phrase.startsWith("Cảnh báo", true)) {
                phrase
            } else {
                "Chú ý! $phrase, cách 200 mét."
            }
            playConfiguredAlert(voiceText, isPriority = true)
        } else if (warningType.label.isNotBlank()) {
            playConfiguredAlert("Chú ý! ${warningType.label}, cách 200 mét.", isPriority = true)
        } else {
            playConfiguredAlert("Cảnh báo", isPriority = true)
        }
    }

    private fun formatPhraseForVoice(phrase: String): String {
        var s = phrase.trim()
        s = s.replace(Regex("""(?i)\bcsgt\b"""), "Cảnh sát giao thông")
        s = s.replace(Regex("""(?i)\bkdc\b"""), "Khu dân cư")
        s = s.replace(Regex("""(?i)\bcam\b"""), "Camera")
        s = s.replace(Regex("""(?i)\bbot\b"""), "Trạm thu phí")
        s = s.replace(Regex("""(?i)\b(\d{1,3})\s*km/h\b"""), "$1 ki lô mét trên giờ")
        s = s.replace(Regex("""(?i)\bkm/h\b"""), " ki lô mét trên giờ")
        s = s.replace(Regex("""(?i)\bkm\b"""), " ki lô mét")
        return s.trim()
    }

    private fun formatDistanceForVoice(dist: String?): String {
        if (dist.isNullOrBlank()) return ""
        var s = dist.trim()
        s = s.replace(Regex("""(?i)\bkm/h\b"""), " ki lô mét trên giờ")
        s = s.replace(Regex("""(?i)\bkm\b"""), " ki lô mét")
        s = s.replace(Regex("""(?i)(\d)\s*m\b"""), "$1 mét")
        s = s.replace(".", " phẩy ")
        s = s.replace(",", " phẩy ")
        return s.trim()
    }

    fun shutdown() {
        try {
            listenJob?.cancel()
            listenJob = null
            toneSequenceJob?.cancel()
            toneSequenceJob = null
            tts?.stop()
            tts?.shutdown()
            releaseAlertAudioTrack()
        } catch (_: Exception) {
        } finally {
            CarMediaManager.resetNavigationDucking()
            tts = null
            isInitialized = false
            pendingSpeakText = null
            lastAlertKey = ""
            lastAlertDistanceBucket = Int.MIN_VALUE
            wasOverSpeeding = false
        }
    }
}
