package com.carhud.aaproxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Selects exactly one primary traffic/HUD data source.
 *
 * GPS remains available as a speed fallback, while Waze/GOFA alerts are gated
 * by this selection so the two providers do not overwrite each other.
 */
object HudSourceManager {
    const val SOURCE_WAZE = "WAZE"
    const val SOURCE_GOFA = "GOFA"

    private const val PREFS = "hud_source_settings"
    private const val KEY_ACTIVE_SOURCE = "active_hud_source"

    private val _activeSource = MutableStateFlow(SOURCE_WAZE)
    val activeSource: StateFlow<String> = _activeSource.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val saved = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_SOURCE, SOURCE_WAZE)
                ?.uppercase()
            _activeSource.value = normalize(saved)
            initialized = true
        }
    }

    fun getActiveSource(context: Context): String {
        init(context)
        return _activeSource.value
    }

    fun isWaze(context: Context): Boolean = getActiveSource(context) == SOURCE_WAZE

    fun isGofa(context: Context): Boolean = getActiveSource(context) == SOURCE_GOFA

    fun isWazeActive(): Boolean = _activeSource.value == SOURCE_WAZE

    fun isGofaActive(): Boolean = _activeSource.value == SOURCE_GOFA

    fun setActiveSource(context: Context, source: String): Boolean {
        init(context)
        val normalized = normalize(source)
        val changed = _activeSource.value != normalized
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SOURCE, normalized)
            .apply()
        _activeSource.value = normalized
        return changed
    }

    /**
     * Applies the source immediately so switching in Settings does not require
     * reconnecting Android Auto or restarting the application.
     */
    fun activate(context: Context, source: String) {
        val appContext = context.applicationContext
        val normalized = normalize(source)
        setActiveSource(appContext, normalized)

        if (normalized == SOURCE_WAZE) {
            GofaHudManager.stop()
            VietmapStateRepository.updateState(
                VietmapAlertData(source = "WAZE_SELECTED")
            )
            WazeHlpWebSocketManager.start()
        } else {
            WazeHlpWebSocketManager.stop()
            VietmapStateRepository.updateState(
                VietmapAlertData(source = "GOFA_SELECTED")
            )
            GofaHudManager.start(appContext)
        }
    }

    fun displayName(source: String): String = when (normalize(source)) {
        SOURCE_GOFA -> "GOFA"
        else -> "Waze"
    }

    private fun normalize(source: String?): String {
        return if (source.equals(SOURCE_GOFA, ignoreCase = true)) SOURCE_GOFA else SOURCE_WAZE
    }
}
