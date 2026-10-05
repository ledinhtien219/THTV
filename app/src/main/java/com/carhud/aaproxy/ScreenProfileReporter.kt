package com.carhud.aaproxy

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.View
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Captures the real Android Auto/head-unit display geometry and synchronizes it
 * to the same Google Apps Script used by the license system.
 *
 * Privacy: only the public THTV machine code is sent. Raw Widevine/ANDROID_ID
 * values are never included in the payload.
 */
object ScreenProfileReporter {

    private const val PREFS = "screen_profile_reporter"
    private const val KEY_LAST_SIGNATURE = "last_signature"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_SUMMARY = "last_summary"

    private const val SETTINGS_PREFS = SettingsActivity.PREFS
    private const val RESYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val RETRY_COOLDOWN_MS = 5L * 60L * 1000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class Profile(
        val deviceId: String,
        val appVersion: String,
        val buildNumber: Int,
        val deviceModel: String,
        val androidVersion: String,
        val sdkInt: Int,
        val carWidth: Int,
        val carHeight: Int,
        val carDpi: Int,
        val carDensity: Float,
        val xdpi: Float,
        val ydpi: Float,
        val aspectRatio: Float,
        val orientation: String,
        val formFactor: String,
        val refreshRate: Float,
        val rotation: Int,
        val usableWidth: Int,
        val usableHeight: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int,
        val webWidth: Int,
        val webHeight: Int,
        val phoneWidth: Int,
        val phoneHeight: Int,
        val phoneDpi: Int,
        val phoneDensity: Float,
        val hudStyleId: Int,
        val hudScale: Int,
        val hudOpacity: Int,
        val screenSignature: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("action", "screen_profile")
            put("deviceId", deviceId)
            put("appVersion", appVersion)
            put("buildNumber", buildNumber)
            put("deviceModel", deviceModel)
            put("androidVer", androidVersion)
            put("sdkInt", sdkInt)

            put("carWidth", carWidth)
            put("carHeight", carHeight)
            put("carDpi", carDpi)
            put("carDensity", round3(carDensity))
            put("xdpi", round2(xdpi))
            put("ydpi", round2(ydpi))
            put("aspectRatio", round3(aspectRatio))
            put("orientation", orientation)
            put("formFactor", formFactor)
            put("refreshRate", round2(refreshRate))
            put("rotation", rotation)

            put("usableWidth", usableWidth)
            put("usableHeight", usableHeight)
            put("insetLeft", insetLeft)
            put("insetTop", insetTop)
            put("insetRight", insetRight)
            put("insetBottom", insetBottom)
            put("webWidth", webWidth)
            put("webHeight", webHeight)

            put("phoneWidth", phoneWidth)
            put("phoneHeight", phoneHeight)
            put("phoneDpi", phoneDpi)
            put("phoneDensity", round3(phoneDensity))

            put("hudStyleId", hudStyleId)
            put("hudScale", hudScale)
            put("hudOpacity", hudOpacity)
            put("screenSignature", screenSignature)
        }

        fun summary(): String {
            val ratio = String.format(Locale.US, "%.2f", aspectRatio)
            return "$carWidth × $carHeight • ${carDpi}dpi • $ratio:1 • $formFactor"
        }
    }

    @Suppress("DEPRECATION")
    fun captureAndSync(
        context: Context,
        display: Display,
        root: View,
        webView: View
    ) {
        val appContext = context.applicationContext

        val real = android.util.DisplayMetrics()
        display.getRealMetrics(real)

        val carWidth = real.widthPixels.coerceAtLeast(1)
        val carHeight = real.heightPixels.coerceAtLeast(1)
        val carDpi = real.densityDpi.coerceAtLeast(1)
        val ratio = carWidth.toFloat() / carHeight.toFloat()
        val orientation = if (carWidth >= carHeight) "LANDSCAPE" else "PORTRAIT"
        val formFactor = classifyFormFactor(ratio)

        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) root.rootWindowInsets else null
        val insetLeft = insets?.systemWindowInsetLeft ?: 0
        val insetTop = insets?.systemWindowInsetTop ?: 0
        val insetRight = insets?.systemWindowInsetRight ?: 0
        val insetBottom = insets?.systemWindowInsetBottom ?: 0

        val rootWidth = root.width.takeIf { it > 0 } ?: carWidth
        val rootHeight = root.height.takeIf { it > 0 } ?: carHeight
        val usableWidth = (rootWidth - insetLeft - insetRight).coerceAtLeast(1)
        val usableHeight = (rootHeight - insetTop - insetBottom).coerceAtLeast(1)
        val webWidth = webView.width.takeIf { it > 0 } ?: usableWidth
        val webHeight = webView.height.takeIf { it > 0 } ?: usableHeight

        val phone = appContext.resources.displayMetrics
        val deviceId = LicenseManager.getDeviceId(appContext)

        val signatureInput = listOf(
            carWidth,
            carHeight,
            carDpi,
            usableWidth,
            usableHeight,
            webWidth,
            webHeight,
            formFactor,
            display.rotation,
            String.format(Locale.US, "%.2f", display.refreshRate)
        ).joinToString("|")
        val signature = sha256Short(signatureInput)

        val profile = Profile(
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE,
            deviceModel = LicenseManager.getDeviceModel(),
            androidVersion = LicenseManager.getAndroidVersion(),
            sdkInt = Build.VERSION.SDK_INT,
            carWidth = carWidth,
            carHeight = carHeight,
            carDpi = carDpi,
            carDensity = real.density,
            xdpi = real.xdpi,
            ydpi = real.ydpi,
            aspectRatio = ratio,
            orientation = orientation,
            formFactor = formFactor,
            refreshRate = display.refreshRate,
            rotation = display.rotation,
            usableWidth = usableWidth,
            usableHeight = usableHeight,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom,
            webWidth = webWidth,
            webHeight = webHeight,
            phoneWidth = phone.widthPixels,
            phoneHeight = phone.heightPixels,
            phoneDpi = phone.densityDpi,
            phoneDensity = phone.density,
            hudStyleId = WazeHudManager.getActiveStyleId(appContext),
            hudScale = WazeHudManager.getScale(appContext),
            hudOpacity = WazeHudManager.getOpacity(appContext),
            screenSignature = signature
        )

        persistLocalProfile(appContext, profile)

        if (!LicenseManager.isLicensed(appContext)) {
            saveStatus(appContext, "Chưa đồng bộ • máy chưa kích hoạt", "")
            return
        }

        maybeSync(appContext, profile, force = false)
    }

    fun syncLastProfileIfAvailable(context: Context, force: Boolean = true) {
        val appContext = context.applicationContext
        if (!LicenseManager.isLicensed(appContext)) {
            saveStatus(appContext, "Chưa đồng bộ • máy chưa kích hoạt", "")
            return
        }

        val settings = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val w = settings.getInt("car_screen_width", 0)
        val h = settings.getInt("car_screen_height", 0)
        val dpi = settings.getInt("car_screen_dpi", 0)
        val usableW = settings.getInt("car_screen_usable_width", w)
        val usableH = settings.getInt("car_screen_usable_height", h)
        val webW = settings.getInt("car_screen_web_width", usableW)
        val webH = settings.getInt("car_screen_web_height", usableH)
        if (w <= 0 || h <= 0 || dpi <= 0) {
            saveStatus(appContext, "Chưa có dữ liệu màn hình xe", "")
            return
        }

        val ratio = w.toFloat() / h.toFloat()
        val phone = appContext.resources.displayMetrics
        val signature = settings.getString("car_screen_signature", null)
            ?: sha256Short("$w|$h|$dpi|$usableW|$usableH|$webW|$webH")

        val profile = Profile(
            deviceId = LicenseManager.getDeviceId(appContext),
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE,
            deviceModel = LicenseManager.getDeviceModel(),
            androidVersion = LicenseManager.getAndroidVersion(),
            sdkInt = Build.VERSION.SDK_INT,
            carWidth = w,
            carHeight = h,
            carDpi = dpi,
            carDensity = dpi / 160f,
            xdpi = settings.getFloat("car_screen_xdpi", dpi.toFloat()),
            ydpi = settings.getFloat("car_screen_ydpi", dpi.toFloat()),
            aspectRatio = ratio,
            orientation = if (w >= h) "LANDSCAPE" else "PORTRAIT",
            formFactor = classifyFormFactor(ratio),
            refreshRate = settings.getFloat("car_screen_refresh_rate", 0f),
            rotation = settings.getInt("car_screen_rotation", 0),
            usableWidth = usableW,
            usableHeight = usableH,
            insetLeft = settings.getInt("car_screen_inset_left", 0),
            insetTop = settings.getInt("car_screen_inset_top", 0),
            insetRight = settings.getInt("car_screen_inset_right", 0),
            insetBottom = settings.getInt("car_screen_inset_bottom", 0),
            webWidth = webW,
            webHeight = webH,
            phoneWidth = phone.widthPixels,
            phoneHeight = phone.heightPixels,
            phoneDpi = phone.densityDpi,
            phoneDensity = phone.density,
            hudStyleId = WazeHudManager.getActiveStyleId(appContext),
            hudScale = WazeHudManager.getScale(appContext),
            hudOpacity = WazeHudManager.getOpacity(appContext),
            screenSignature = signature
        )
        maybeSync(appContext, profile, force)
    }

    fun getLastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATUS, "Chưa đồng bộ") ?: "Chưa đồng bộ"

    fun getLastSummary(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUMMARY, "") ?: ""

    fun getLastSyncTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_MS, 0L)

    fun getLastError(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, "") ?: ""

    private fun persistLocalProfile(context: Context, p: Profile) {
        val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        settings.edit()
            .putInt("car_screen_width", p.carWidth)
            .putInt("car_screen_height", p.carHeight)
            .putInt("car_screen_dpi", p.carDpi)
            .putBoolean("car_screen_connected", true)
            .putInt("car_screen_usable_width", p.usableWidth)
            .putInt("car_screen_usable_height", p.usableHeight)
            .putInt("car_screen_web_width", p.webWidth)
            .putInt("car_screen_web_height", p.webHeight)
            .putFloat("car_screen_xdpi", p.xdpi)
            .putFloat("car_screen_ydpi", p.ydpi)
            .putFloat("car_screen_refresh_rate", p.refreshRate)
            .putInt("car_screen_rotation", p.rotation)
            .putInt("car_screen_inset_left", p.insetLeft)
            .putInt("car_screen_inset_top", p.insetTop)
            .putInt("car_screen_inset_right", p.insetRight)
            .putInt("car_screen_inset_bottom", p.insetBottom)
            .putString("car_screen_form_factor", p.formFactor)
            .putString("car_screen_signature", p.screenSignature)
            .apply()

        CarMediaManager.carScreenWidth = p.carWidth
        CarMediaManager.carScreenHeight = p.carHeight
        CarMediaManager.carScreenDpi = p.carDpi

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SUMMARY, p.summary())
            .apply()
    }

    private fun maybeSync(context: Context, profile: Profile, force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousSignature = prefs.getString(KEY_LAST_SIGNATURE, "")
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L)

        if (!force) {
            val sameProfileFresh = previousSignature == profile.screenSignature &&
                now - lastSync < RESYNC_INTERVAL_MS
            if (sameProfileFresh) {
                saveStatus(context, "Đã đồng bộ • hồ sơ không đổi", "")
                return
            }
            if (now - lastAttempt < RETRY_COOLDOWN_MS) return
        }

        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_MS, now)
            .putString(KEY_LAST_STATUS, "Đang đồng bộ Google Sheet…")
            .putString(KEY_LAST_ERROR, "")
            .apply()

        val request = Request.Builder()
            .url(LicenseConfig.getApiUrl(context))
            .post(
                profile.toJson().toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                saveStatus(context, "Lỗi đồng bộ", e.localizedMessage.orEmpty())
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    try {
                        val json = JSONObject(body)
                        val status = json.optString("status", "")
                        val ok = it.isSuccessful &&
                            (json.optBoolean("success", false) ||
                                status == "SCREEN_PROFILE_SAVED" ||
                                status == "SCREEN_PROFILE_UPDATED")
                        if (ok) {
                            prefs.edit()
                                .putString(KEY_LAST_SIGNATURE, profile.screenSignature)
                                .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                                .putString(KEY_LAST_STATUS, "Đã đồng bộ Google Sheet")
                                .putString(KEY_LAST_ERROR, "")
                                .putString(KEY_LAST_SUMMARY, profile.summary())
                                .apply()
                        } else {
                            val error = json.optString("error", status.ifBlank { "HTTP ${it.code}" })
                            saveStatus(context, "Chưa đồng bộ", error)
                        }
                    } catch (e: Exception) {
                        saveStatus(context, "Lỗi phản hồi máy chủ", e.message.orEmpty())
                    }
                }
            }
        })
    }

    private fun saveStatus(context: Context, status: String, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, status)
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }

    private fun classifyFormFactor(ratio: Float): String = when {
        ratio < 0.95f -> "PORTRAIT"
        ratio < 1.25f -> "SQUARE"
        ratio < 1.55f -> "LANDSCAPE_4_3"
        ratio < 1.90f -> "WIDE_16_9"
        ratio < 2.40f -> "ULTRAWIDE"
        else -> "SUPER_ULTRAWIDE"
    }

    private fun sha256Short(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02X".format(it) }
    }

    private fun round2(v: Float): Double = String.format(Locale.US, "%.2f", v).toDouble()
    private fun round3(v: Float): Double = String.format(Locale.US, "%.3f", v).toDouble()
}
