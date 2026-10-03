package com.carhud.aaproxy

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Manages Automotive Audio & Phone Call interruptions.
 * 
 * CRITICAL ARCHITECTURAL NOTE:
 * In Android WebView, Chromium's internal engine (org.chromium.content.browser.AudioFocusDelegate)
 * ALREADY requests and holds AUDIOFOCUS_GAIN for media playback directly from Android OS / Android Auto.
 * Calling audioManager.requestAudioFocus(AUDIOFOCUS_GAIN) in this class would steal focus from Chromium,
 * triggering an immediate AUDIOFOCUS_LOSS (-1) callback in Chromium which pauses HTML5 video playback,
 * causing a severe, infinite Play/Pause flapping cycle!
 * 
 * Therefore, this manager deliberately relies on Chromium's native AudioFocus holding,
 * and handles Phone Calls (SIM & VoIP) via OnModeChangedListener and TelephonyManager.
 */
class CarAudioManager(
    private val context: Context,
    private val onVolumeDuckChange: (Float) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasPlayingBeforeCall = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        // 1. Listen to Audio Mode changes on Android 12+ (API 31+) to cleanly pause during phone/VoIP calls and resume when ended
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.addOnModeChangedListener(
                    context.mainExecutor,
                    AudioManager.OnModeChangedListener { mode ->
                        android.util.Log.d("CarHudAudio", "Audio mode changed to: $mode, wasPlaying: ${CarMediaManager.isPlaying}")
                        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) {
                            if (CarMediaManager.isPlaying || CarMediaManager.userWantsPlayback) {
                                wasPlayingBeforeCall = true
                                CarMediaManager.pausePlayback()
                            }
                        } else if (mode == AudioManager.MODE_NORMAL) {
                            if (wasPlayingBeforeCall) {
                                wasPlayingBeforeCall = false
                                handler.postDelayed({
                                    CarMediaManager.resumePlayback()
                                }, 400L)
                                handler.postDelayed({
                                    if (!CarMediaManager.isPlaying) {
                                        CarMediaManager.resumePlayback()
                                    }
                                }, 1000L)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Telephony listener for cellular call states across all Android versions
        try {
            val telephonyManager = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager.registerTelephonyCallback(
                        context.mainExecutor,
                        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                            override fun onCallStateChanged(state: Int) {
                                handleTelephonyCallState(state)
                            }
                        }
                    )
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(
                        object : PhoneStateListener() {
                            @Deprecated("Deprecated in Java")
                            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                                handleTelephonyCallState(state)
                            }
                        },
                        PhoneStateListener.LISTEN_CALL_STATE
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleTelephonyCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (CarMediaManager.isPlaying || CarMediaManager.userWantsPlayback) {
                    wasPlayingBeforeCall = true
                    CarMediaManager.pausePlayback()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasPlayingBeforeCall) {
                    wasPlayingBeforeCall = false
                    handler.postDelayed({
                        CarMediaManager.resumePlayback()
                    }, 400L)
                    handler.postDelayed({
                        if (!CarMediaManager.isPlaying) {
                            CarMediaManager.resumePlayback()
                        }
                    }, 1000L)
                }
            }
        }
    }

    /**
     * Safe no-op focus request to avoid stealing audio focus from Chromium WebView.
     */
    fun requestFocus(): Boolean {
        return true
    }

    /**
     * Safe no-op focus release.
     */
    fun abandonFocus() {
    }
}

