package com.carhud.aaproxy

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object LicenseManager {

    private const val PREFS_NAME = "tcar_license_secure"
    private const val KEY_STATUS = "license_status"
    private const val KEY_PLAN = "license_plan"
    private const val KEY_EXPIRY = "license_expiry"
    private const val KEY_EMAIL = "license_email"
    private const val KEY_SIGNATURE = "license_signature"
    private const val KEY_LAST_VERIFIED = "license_last_verified"
    private const val KEY_DEVICE_ID_SCHEME = "device_id_scheme"
    private const val DEVICE_ID_SCHEME_HARDWARE_V2 = "hardware_v2"
    private const val SECRET_SALT = "TCar_Pro_Auto_2026_Secure_Key_!@#"

    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_REJECTED = "REJECTED"
    const val STATUS_EXPIRED = "EXPIRED"
    const val STATUS_UNLICENSED = "UNLICENSED"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private var cachedDeviceId: String? = null

    /**
     * Mã máy ổn định theo thiết bị và KHÔNG phụ thuộc chữ ký APK.
     *
     * ANDROID_ID trên Android 8+ bị scope theo signing key, vì vậy các build ký
     * certificate khác nhau có thể cho mã kích hoạt khác nhau trên cùng một máy.
     * Ưu tiên Widevine device unique id (hash cục bộ, không gửi raw id); nếu ROM
     * không hỗ trợ thì fallback sang profile phần cứng tĩnh.
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }

        val stableMaterial = getStableHardwareMaterial(context)
        val id = formatDeviceId(stableMaterial)
        cachedDeviceId = id

        // Marker only; the id itself remains deterministic and survives reinstall.
        getPrefs(context).edit().putString(KEY_DEVICE_ID_SCHEME, DEVICE_ID_SCHEME_HARDWARE_V2).apply()
        return id
    }

    @SuppressLint("HardwareIds")
    private fun getLegacyDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
        val rawHw = "$androidId|${Build.BOARD}|${Build.BRAND}|${Build.MANUFACTURER}|${Build.DEVICE}|${Build.HARDWARE}"
        return formatDeviceId(rawHw.toByteArray(Charsets.UTF_8))
    }

    private fun getStableHardwareMaterial(context: Context): ByteArray {
        // Widevine's deviceUniqueId is device-bound and not scoped to this APK's signer.
        try {
            val widevineUuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
            val drm = MediaDrm(widevineUuid)
            try {
                val unique = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                if (unique.isNotEmpty()) {
                    return unique
                }
            } finally {
                drm.release()
            }
        } catch (_: Throwable) {
            // Some head units/ROMs do not expose Widevine. Use stable build hardware
            // properties instead; intentionally exclude OS fingerprint/version.
        }

        val fallback = listOf(
            Build.BOARD,
            Build.BRAND,
            Build.MANUFACTURER,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MODEL,
            Build.PRODUCT
        ).joinToString("|")
        if (fallback.isNotBlank()) {
            return fallback.toByteArray(Charsets.UTF_8)
        }

        // Last resort only. This can be signing-scoped on modern Android.
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
        return androidId.toByteArray(Charsets.UTF_8)
    }

    private fun formatDeviceId(raw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val hex = digest.joinToString("") { "%02X".format(it) }
        return "THTV-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    fun getAndroidVersion(): String {
        return "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun computeSignature(deviceId: String, email: String, status: String, expiry: String): String {
        val raw = "$deviceId#$email#$status#$expiry#$SECRET_SALT"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * Kiểm tra ứng dụng đã được kích hoạt bản quyền hợp lệ hay chưa
     */
    fun isLicensed(context: Context): Boolean {
        val prefs = getPrefs(context)
        val status = prefs.getString(KEY_STATUS, STATUS_UNLICENSED)
        if (status != STATUS_APPROVED) return false

        val deviceId = getDeviceId(context)
        val email = prefs.getString(KEY_EMAIL, "") ?: ""
        val expiry = prefs.getString(KEY_EXPIRY, "") ?: ""
        val sig = prefs.getString(KEY_SIGNATURE, "") ?: ""

        val expectedSig = computeSignature(deviceId, email, STATUS_APPROVED, expiry)
        if (sig.isNotEmpty() && sig == expectedSig) return true

        // One-time migration from the old ANDROID_ID/signing-scoped machine code.
        // If the stored license was valid before updating, re-sign it locally against
        // the new stable hardware id so the user is not forced to activate again.
        val legacyId = getLegacyDeviceId(context)
        val legacySig = computeSignature(legacyId, email, STATUS_APPROVED, expiry)
        if (sig.isNotEmpty() && sig == legacySig) {
            getPrefs(context).edit()
                .putString(KEY_SIGNATURE, expectedSig)
                .putString(KEY_DEVICE_ID_SCHEME, DEVICE_ID_SCHEME_HARDWARE_V2)
                .apply()
            return true
        }

        return false
    }

    fun getLicenseEmail(context: Context): String {
        return getPrefs(context).getString(KEY_EMAIL, "") ?: ""
    }

    fun getLicensePlan(context: Context): String {
        return getPrefs(context).getString(KEY_PLAN, "") ?: ""
    }

    fun getLicenseExpiry(context: Context): String {
        val raw = getPrefs(context).getString(KEY_EXPIRY, "") ?: ""
        if (raw.contains("GMT", ignoreCase = true) || raw.contains("Giờ", ignoreCase = true)) {
            try {
                val inputSdf = SimpleDateFormat("EEE MMM dd yyyy", Locale.US)
                val date = inputSdf.parse(raw)
                if (date != null) {
                    val outputSdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    return outputSdf.format(date)
                }
            } catch (e: Exception) {}
        }
        return raw
    }

    fun getLicenseStatus(context: Context): String {
        return getPrefs(context).getString(KEY_STATUS, STATUS_UNLICENSED) ?: STATUS_UNLICENSED
    }

    /**
     * Lưu bản quyền đã được duyệt thành công
     */
    fun saveApproval(context: Context, email: String, plan: String, expiry: String) {
        val deviceId = getDeviceId(context)
        val sig = computeSignature(deviceId, email, STATUS_APPROVED, expiry)
        getPrefs(context).edit()
            .putString(KEY_STATUS, STATUS_APPROVED)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PLAN, plan)
            .putString(KEY_EXPIRY, expiry)
            .putString(KEY_SIGNATURE, sig)
            .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
            .apply()
    }

    /**
     * Gửi đăng ký kích hoạt lên Server Telegram / Google Apps Script
     */
    fun registerDevice(
        context: Context,
        email: String,
        onResult: (success: Boolean, status: String, message: String) -> Unit
    ) {
        val apiUrl = LicenseConfig.getApiUrl(context)
        val deviceId = getDeviceId(context)
        val deviceModel = getDeviceModel()
        val androidVer = getAndroidVersion()

        val json = JSONObject().apply {
            put("action", "register")
            put("deviceId", deviceId)
            put("email", email)
            put("deviceModel", deviceModel)
            put("androidVer", androidVer)
        }

        val request = Request.Builder()
            .url(apiUrl)
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Kiểm tra xem có cấu hình URL mẫu chưa
                if (apiUrl.contains("SAMPLE_YOUR_SCRIPT_ID")) {
                    onResult(false, STATUS_UNLICENSED, "Chưa thiết lập URL Google Apps Script trong LicenseConfig.kt!")
                } else {
                    onResult(false, STATUS_UNLICENSED, "Không thể kết nối máy chủ: ${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val resJson = JSONObject(body)
                    val status = resJson.optString("status", STATUS_PENDING)
                    val msg = resJson.optString("message", "Đã gửi yêu cầu kích hoạt")

                    // Lưu trạng thái PENDING & email vào máy
                    getPrefs(context).edit()
                        .putString(KEY_STATUS, status)
                        .putString(KEY_EMAIL, email)
                        .apply()

                    if (status == STATUS_APPROVED) {
                        val plan = resJson.optString("plan", "TRỌN ĐỜI")
                        val expiry = resJson.optString("expiry", "VĨNH VIỄN")
                        saveApproval(context, email, plan, expiry)
                    }

                    onResult(true, status, msg)
                } catch (e: Exception) {
                    onResult(false, STATUS_UNLICENSED, "Lỗi phân tích phản hồi máy chủ: ${e.message}")
                }
            }
        })
    }

    /**
     * Kiểm tra tình trạng bản quyền mới nhất từ máy chủ (GET check)
     */
    fun checkStatus(
        context: Context,
        onResult: (status: String, plan: String, expiry: String) -> Unit
    ) {
        val apiUrl = LicenseConfig.getApiUrl(context)
        val deviceId = getDeviceId(context)
        val email = getLicenseEmail(context)

        val fullUrl = if (apiUrl.contains("?")) {
            "$apiUrl&action=check&deviceId=$deviceId&email=$email"
        } else {
            "$apiUrl?action=check&deviceId=$deviceId&email=$email"
        }

        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult("ERROR_NETWORK", "", "")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val resJson = JSONObject(body)
                    val status = resJson.optString("status", "NOT_FOUND")
                    val plan = resJson.optString("plan", "")
                    val expiry = resJson.optString("expiry", "")
                    val resEmail = resJson.optString("email", email)

                    if (status == STATUS_APPROVED) {
                        saveApproval(context, resEmail.ifEmpty { email }, plan.ifEmpty { "TRỌN ĐỜI" }, expiry.ifEmpty { "VĨNH VIỄN" })
                    } else if (status == STATUS_REJECTED || status == STATUS_EXPIRED || status == "BANNED") {
                        getPrefs(context).edit().putString(KEY_STATUS, status).apply()
                    }

                    onResult(status, plan, expiry)
                } catch (e: Exception) {
                    onResult("ERROR_PARSE", "", "")
                }
            }
        })
    }

    /**
     * Kích hoạt chế độ dùng thử hoặc cấp phép nội bộ (Dành cho Developer/Admin test)
     */
    fun setDevBypass(context: Context, email: String = "admin@tcar.vn") {
        saveApproval(context, email, "DEV_PASS", "VĨNH VIỄN")
    }

    /**
     * Xóa bản quyền (Đưa về trạng thái chưa kích hoạt)
     */
    fun resetLicense(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
