package com.carhud.aaproxy

import com.carhud.app.BuildConfig
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Automotive Crash Logger & Exception Analyzer for T-Car.
 * Captures uncaught exceptions, records system diagnostics, and persists error logs
 * for developer analysis and post-mortem bug fixes.
 */
object AppCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "AppCrashHandler"
    private const val PREFS_NAME = "carhud_crash_logs_prefs"
    private const val KEY_CRASH_LOGS = "crash_logs_json_array"
    private const val MAX_LOGS = 20

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (Thread.getDefaultUncaughtExceptionHandler() != this) {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
            Log.i(TAG, "AppCrashHandler global exception handler initialized.")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            logError(throwable, "UncaughtException in thread [${thread.name}]")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging crash", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logError(throwable: Throwable, contextInfo: String = "RuntimeError") {
        val ctx = appContext ?: return
        try {
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val stackTraceStr = Log.getStackTraceString(throwable)
            val errorMsg = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.simpleName

            val logObj = JSONObject().apply {
                put("timestamp", dateStr)
                put("timeMillis", System.currentTimeMillis())
                put("errorMsg", errorMsg)
                put("contextInfo", contextInfo)
                put("exceptionClass", throwable.javaClass.name)
                put("stackTrace", stackTraceStr.take(2000))
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                put("appVersion", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }

            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentJson = prefs.getString(KEY_CRASH_LOGS, "[]") ?: "[]"
            val array = JSONArray(currentJson)

            val newArray = JSONArray()
            newArray.put(logObj)
            for (i in 0 until array.length().coerceAtMost(MAX_LOGS - 1)) {
                newArray.put(array.get(i))
            }

            prefs.edit().putString(KEY_CRASH_LOGS, newArray.toString()).apply()
            Log.e(TAG, "Logged crash/error to storage: $errorMsg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save error log", e)
        }
    }

    fun getErrorLogs(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CRASH_LOGS, "[]") ?: "[]"
        val list = mutableListOf<JSONObject>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
        } catch (e: Exception) {}
        return list
    }

    fun getFormattedSummary(context: Context): String {
        val logs = getErrorLogs(context)
        if (logs.isEmpty()) {
            return "✅ Chưa phát hiện lỗi hệ thống nào. Ứng dụng đang hoạt động ổn định!"
        }

        val sb = StringBuilder()
        sb.append("📊 BÁO CÁO PHÂN TÍCH LỖI HỆ THỐNG (${logs.size} lỗi được ghi nhận):\n")
        sb.append("========================================\n\n")

        for ((idx, log) in logs.withIndex()) {
            val time = log.optString("timestamp", "N/A")
            val ctxInfo = log.optString("contextInfo", "Error")
            val msg = log.optString("errorMsg", "Unknown error")
            val cls = log.optString("exceptionClass", "")
            val dev = log.optString("deviceModel", "")
            val ver = log.optString("appVersion", "")
            val trace = log.optString("stackTrace", "").trim()

            sb.append("#${idx + 1}. [$time] - $ctxInfo\n")
            sb.append("📱 Thiết bị: $dev • Bản: $ver\n")
            sb.append("⚠️ Loại lỗi: $cls\n")
            sb.append("💬 Chi tiết: $msg\n")
            if (trace.isNotEmpty()) {
                val shortTrace = trace.lines().take(6).joinToString("\n")
                sb.append("📍 StackTrace:\n$shortTrace\n")
            }
            sb.append("----------------------------------------\n\n")
        }

        return sb.toString()
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CRASH_LOGS).apply()
    }
}
