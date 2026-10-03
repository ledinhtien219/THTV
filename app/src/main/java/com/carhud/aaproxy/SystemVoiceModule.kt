package com.carhud.aaproxy

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Receives commands forwarded by the system assistant; never takes over its mic. */
internal object SystemVoiceModule {
    const val PREF_ENABLED = "system_voice_commands_enabled"
    private const val STATUS_PREFS = "system_voice_diagnostics"
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: ((SystemVoiceCommand) -> Unit)? = null
    private var generation = 0L

    enum class Result(val message: String) {
        ACCEPTED("Đã nhận lệnh; xem kết quả trên Android Auto."),
        DISABLED("Mic hệ thống cho THTV đang tắt trong Cài đặt."),
        NOT_READY("Mở THTV trên Android Auto trước, rồi nói lại lệnh.")
    }

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_ENABLED, true)

    fun attach(listener: (SystemVoiceCommand) -> Unit) { receiver = listener }
    fun detach(listener: (SystemVoiceCommand) -> Unit) {
        if (receiver === listener) { receiver = null; generation++ }
    }

    fun submit(context: Context, query: String?, extras: Bundle? = null, source: String = "Mic hệ thống"): Result {
        val appContext = context.applicationContext
        val request = ++generation
        fun extra(key: String): String? = try { extras?.getString(key) } catch (_: Exception) { null }
        val command = SystemVoiceCommandParser.parse(query, extra(MediaStore.EXTRA_MEDIA_TITLE), extra(MediaStore.EXTRA_MEDIA_ARTIST), extra(MediaStore.EXTRA_MEDIA_GENRE), extra(MediaStore.EXTRA_MEDIA_PLAYLIST))
        val label = when (command) {
            is SystemVoiceCommand.Tv -> "TV: ${command.channel.ifBlank { "kênh gần nhất" }}"
            is SystemVoiceCommand.Music -> "Nhạc: ${command.query.ifBlank { "nhạc gần nhất" }}"
            SystemVoiceCommand.Resume -> "Tiếp tục phát"
        }
        appContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
            .putString("source", source).putString("query", query.orEmpty()).putString("target", label)
            .putLong("received_at", System.currentTimeMillis()).apply()
        val consumer = receiver
        val result = when {
            !isEnabled(appContext) -> Result.DISABLED
            consumer == null -> Result.NOT_READY
            else -> Result.ACCEPTED
        }
        report(appContext, result.message)
        if (result == Result.ACCEPTED) handler.post {
            if (generation != request) return@post
            if (!isEnabled(appContext)) { report(appContext, Result.DISABLED.message); return@post }
            if (receiver !== consumer) { report(appContext, Result.NOT_READY.message); return@post }
            CarMediaManager.cancelPendingSteeringNext()
            consumer?.invoke(command)
        }
        return result
    }

    fun report(context: Context, message: String) {
        context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit().putString("status", message).apply()
    }

    fun fail(context: Context, message: String) {
        report(context, message)
        CarMediaManager.notifyVoiceState(VoiceSearchManager.State.ERROR, message)
        CarMediaBrowserService.instance?.reportSystemVoiceError(message)
    }

    fun diagnostics(context: Context): String {
        val prefs = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("received_at", 0L)
        if (at == 0L) return "Chưa nhận lệnh nào từ mic hệ thống.\nMở THTV trên Android Auto, bấm mic hệ thống rồi nói: Phát VTV1 trên THTV Media."
        val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
        return "Lúc: $time\nNguồn: ${prefs.getString("source", "")}\nLệnh: ${prefs.getString("query", "")}\nXử lý: ${prefs.getString("target", "")}\nTrạng thái: ${prefs.getString("status", "")}"
    }
}
