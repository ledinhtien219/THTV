package com.carhud.aaproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class WeatherData(
    val tempC: Int = 0,
    val conditionText: String = "Đang cập nhật thời tiết",
    val iconEmoji: String = "🌤️",
    val humidity: Int = 0,
    val windSpeedKmh: Int = 0,
    val location: String = "Đang lấy vị trí",
    val lastUpdated: Long = 0L,
    val isLoaded: Boolean = false
) {
    val conditionSummary: String
        get() = "$conditionText • Độ ẩm $humidity%"
        
    val tempDisplay: String
        get() = "$tempC°C"
        
    val locationDisplay: String
        get() = "📍 $location"

    val windDisplay: String
        get() = "Gió $windSpeedKmh km/h"
}

object WeatherManager {

    private const val TAG = "WeatherManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _weatherState = MutableStateFlow(WeatherData())
    val weatherState: StateFlow<WeatherData> = _weatherState.asStateFlow()

    private const val PREFS = "weather_cache"
    private var isStarted = false
    private var appContext: Context? = null
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var lastFetchedLat: Double = 0.0
    private var lastFetchedLon: Double = 0.0
    private var lastFetchedTime: Long = 0
    private var cityName: String = "Đang lấy vị trí"

    fun start(context: Context? = null) {
        val ctx = context?.applicationContext
        if (ctx != null) {
            appContext = ctx
            restoreCachedWeather(ctx)
        }
        if (isStarted) {
            GpsSpeedManager.lastLocation.value?.let { loc ->
                appContext?.let { c -> updateFromLocation(c, loc) }
            }
            return
        }
        isStarted = true

        GpsSpeedManager.lastLocation.value?.let { loc ->
            appContext?.let { c -> updateFromLocation(c, loc) }
        }

        scope.launch {
            // Refresh every 30 minutes, but only after we have a real phone location.
            while (isActive) {
                delay(30 * 60 * 1000L)
                if (lastLat != 0.0 || lastLon != 0.0) {
                    fetchWeather(lastLat, lastLon)
                }
            }
        }
    }

    private fun restoreCachedWeather(context: Context) {
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            lastLat = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L))
            lastLon = java.lang.Double.longBitsToDouble(p.getLong("lon", 0L))
            lastFetchedLat = lastLat
            lastFetchedLon = lastLon
            lastFetchedTime = p.getLong("updated", 0L)
            cityName = p.getString("location", null)?.takeIf { it.isNotBlank() } ?: cityName
            if (p.getBoolean("loaded", false)) {
                _weatherState.value = WeatherData(
                    tempC = p.getInt("temp", 0),
                    conditionText = p.getString("condition", "Đang cập nhật thời tiết") ?: "Đang cập nhật thời tiết",
                    iconEmoji = p.getString("icon", "🌤️") ?: "🌤️",
                    humidity = p.getInt("humidity", 0),
                    windSpeedKmh = p.getInt("wind", 0),
                    location = cityName,
                    lastUpdated = lastFetchedTime,
                    isLoaded = true
                )
            }
        } catch (_: Exception) { }
    }

    private fun saveCache(data: WeatherData) {
        val context = appContext ?: return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lat", java.lang.Double.doubleToRawLongBits(lastLat))
                .putLong("lon", java.lang.Double.doubleToRawLongBits(lastLon))
                .putString("location", cityName)
                .putInt("temp", data.tempC)
                .putString("condition", data.conditionText)
                .putString("icon", data.iconEmoji)
                .putInt("humidity", data.humidity)
                .putInt("wind", data.windSpeedKmh)
                .putLong("updated", data.lastUpdated)
                .putBoolean("loaded", data.isLoaded)
                .apply()
        } catch (_: Exception) { }
    }

    /**
     * Auto-update weather from device GPS location with distance & time throttling
     */
    fun updateFromLocation(context: Context, location: android.location.Location) {
        val lat = location.latitude
        val lon = location.longitude
        if (lat == 0.0 && lon == 0.0) return

        val now = System.currentTimeMillis()
        val dist = FloatArray(1)
        if (lastFetchedLat != 0.0 || lastFetchedLon != 0.0) {
            android.location.Location.distanceBetween(lat, lon, lastFetchedLat, lastFetchedLon, dist)
            // Update if moved > 3km and at least 3 minutes passed, or routine 15 minutes
            val movedSignificantly = dist[0] >= 3000f && (now - lastFetchedTime) > 3 * 60 * 1000L
            val routinePeriodic = (now - lastFetchedTime) > 15 * 60 * 1000L
            if (!movedSignificantly && !routinePeriodic && lastFetchedTime > 0) {
                return
            }
        }

        scope.launch {
            var resolvedName: String? = null
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale("vi", "VN"))
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(lat, lon, 1)
                    if (!list.isNullOrEmpty()) {
                        val addr = list[0]
                        val subAdmin = addr.subAdminArea // e.g. "Huyện Lâm Thao"
                        val admin = addr.adminArea       // e.g. "Tỉnh Phú Thọ"
                        val locality = addr.locality     // e.g. "Việt Trì"
                        
                        val cleanLocality = locality?.replace("Thành phố ", "")?.replace("Tỉnh ", "")?.replace("Quận ", "")?.replace("Huyện ", "")?.trim()
                        val cleanAdmin = admin?.replace("Thành phố ", "")?.replace("Tỉnh ", "")?.trim()
                        val cleanSubAdmin = subAdmin?.replace("Quận ", "")?.replace("Huyện ", "")?.replace("Thành phố ", "")?.replace("Thị xã ", "")?.trim()

                        resolvedName = when {
                            !cleanLocality.isNullOrBlank() -> cleanLocality
                            !cleanSubAdmin.isNullOrBlank() -> cleanSubAdmin
                            !cleanAdmin.isNullOrBlank() -> cleanAdmin
                            else -> null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder lookup failed: ${e.message}")
            }

            updateLocation(lat, lon, resolvedName)
        }
    }

    fun updateLocation(latitude: Double, longitude: Double, name: String? = null) {
        lastLat = latitude
        lastLon = longitude
        lastFetchedLat = latitude
        lastFetchedLon = longitude
        lastFetchedTime = System.currentTimeMillis()

        if (!name.isNullOrBlank()) {
            cityName = name
        } else {
            cityName = when {
                latitude in 21.0..21.8 && longitude in 104.8..105.5 -> "Phú Thọ"
                latitude in 21.2..21.6 && longitude in 105.4..105.8 -> "Vĩnh Phúc"
                latitude in 20.8..21.4 && longitude in 105.5..106.1 -> "Hà Nội"
                latitude in 21.0..21.4 && longitude in 106.0..106.5 -> "Bắc Ninh"
                latitude in 20.6..21.2 && longitude in 106.4..107.1 -> "Hải Phòng"
                latitude in 20.7..21.6 && longitude in 106.8..108.0 -> "Quảng Ninh"
                latitude in 20.2..20.6 && longitude in 105.8..106.2 -> "Hà Nam"
                latitude in 20.0..20.5 && longitude in 105.7..106.1 -> "Ninh Bình"
                latitude in 19.5..20.2 && longitude in 105.3..106.0 -> "Thanh Hóa"
                latitude in 18.5..19.5 && longitude in 105.2..106.0 -> "Nghệ An"
                latitude in 15.8..16.5 && longitude in 107.8..108.6 -> "Đà Nẵng"
                latitude in 11.5..12.2 && longitude in 108.2..108.6 -> "Đà Lạt"
                latitude in 10.3..11.2 && longitude in 106.2..107.2 -> "TP. Hồ Chí Minh"
                latitude in 10.8..11.4 && longitude in 106.7..107.4 -> "Bình Dương"
                latitude in 10.3..10.6 && longitude in 107.0..107.4 -> "Bà Rịa - Vũng Tàu"
                latitude in 9.8..10.5 && longitude in 105.4..106.1 -> "Cần Thơ"
                else -> "Việt Nam"
            }
        }
        scope.launch {
            fetchWeather(lastLat, lastLon)
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        try {
            val urlString = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto".format(
                java.util.Locale.US, lat, lon
            )
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CARHUD/0.8.60")
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)
                val current = json.optJSONObject("current")
                if (current != null) {
                    val temp = current.optDouble("temperature_2m", 31.0).toInt()
                    val humidity = current.optInt("relative_humidity_2m", 68)
                    val wind = current.optDouble("wind_speed_10m", 12.0).toInt()
                    val code = current.optInt("weather_code", 0)
                    val (condition, emoji) = parseWmoCode(code)

                    val data = WeatherData(
                        tempC = temp,
                        conditionText = condition,
                        iconEmoji = emoji,
                        humidity = humidity,
                        windSpeedKmh = wind,
                        location = cityName,
                        lastUpdated = System.currentTimeMillis(),
                        isLoaded = true
                    )
                    _weatherState.value = data
                    saveCache(data)
                    Log.d(TAG, "Weather updated: $temp°C, $condition, $cityName, $wind km/h")
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch skipped or failed: ${e.message}")
        }
    }

    private fun parseWmoCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> "Trời quang đãng" to "☀️"
            1, 2 -> "Trời nắng ít mây" to "🌤️"
            3 -> "Trời nhiều mây" to "⛅"
            45, 48 -> "Có sương mù" to "🌫️"
            51, 53, 55 -> "Mưa phùn nhẹ" to "🌦️"
            61, 63 -> "Mưa rào" to "🌧️"
            65 -> "Mưa to" to "🌧️"
            80, 81, 82 -> "Mưa rào từng cơn" to "🌧️"
            95, 96, 99 -> "Có dông lốc" to "⛈️"
            else -> "Trời nắng ráo" to "☀️"
        }
    }
}
