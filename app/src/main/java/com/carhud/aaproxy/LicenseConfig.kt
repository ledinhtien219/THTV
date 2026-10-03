package com.carhud.aaproxy

import android.content.Context

object LicenseConfig {
    /**
     * URL Web App Google Apps Script kết nối Google Sheets & Telegram Bot.
     * Người dùng sau khi tạo Apps Script sẽ dán URL vào đây.
     */
    const val DEFAULT_API_URL = "https://script.google.com/macros/s/AKfycbx3VghzkpJBZWku2wN82l3tmJI1IwHSqhPJF6ad4FnW9DKpS-yblaDTDGKw31s4EngNZA/exec"
    
    // Khóa lưu URL tùy chỉnh trong SharedPreferences
    private const val KEY_CUSTOM_API_URL = "license_custom_api_url"
    private const val KEY_PREFS = "tcar_license_config"

    // Thông tin liên hệ hỗ trợ kích hoạt
    const val SUPPORT_NAME = "Mr Tiến"
    const val SUPPORT_PHONE = "0366717255"
    const val SUPPORT_PHONE_FORMATTED = "0366.717.255"
    const val SUPPORT_ZALO_URL = "https://zalo.me/0366717255"
    const val SUPPORT_TELEGRAM = "https://t.me"

    // Thời gian cho phép chạy offline sau khi đã duyệt thành công (mặc định 14 ngày)
    const val OFFLINE_GRACE_PERIOD_MILLIS = 14L * 24 * 60 * 60 * 1000

    fun getApiUrl(context: Context): String {
        val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_API_URL, null) ?: DEFAULT_API_URL
    }

    fun setCustomApiUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_API_URL, url.trim()).apply()
    }
}
