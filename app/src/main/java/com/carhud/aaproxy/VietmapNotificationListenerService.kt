package com.carhud.aaproxy

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

class VietmapNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WazeModNotifService"

        val WAZE_PACKAGES = setOf(
            "com.waze",
            "com.waze.mod",
            "com.waze.vietnam",
            "com.waze.vn",
            "com.waze.c4a"
        )

        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        private val SPEED_REGEX = Regex("""(?i)(?:t\u1ED1c\s*đ\u1ED9(?:\s*hi\u1EC7n\s*t\u1EA1i)?|speed|hi\u1EC7n\s*t\u1EA1i)[:\s]*(\d{1,3})""")
        private val SPEED_KMH_REGEX = Regex("""\b(\d{1,3})\s*km/h\b""", RegexOption.IGNORE_CASE)
        private val LIMIT_REGEX = Regex("""(?i)(?:t\u1ED1i\s*đa|gi\u1EDBi\s*h\u1EA1n(?:\s*t\u1ED1c\s*đ\u1ED9)?|limit|max|speed\s*limit|bi\u1EC3n\s*b\u00E1o)[:\s]*(\d{1,3})""")
        private val DISTANCE_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*(m|km|met)\b""", RegexOption.IGNORE_CASE)

        private val STANDARD_LIMITS = setOf(30, 40, 50, 60, 70, 80, 90, 100, 110, 120)

        @Volatile
        var isServiceConnected = false
            private set

        val IGNORED_NOTIFICATION_TEXTS = listOf(
            "đã tắt",
            "tắt âm",
            "âm thanh đã tắt",
            "đang chạy trong nền",
            "đang chạy ngầm",
            "chạy trong nền",
            "chạy ngầm",
            "running in background",
            "running in the background",
            "chạm để mở",
            "nhấn để mở",
            "tap to open",
            "click to open",
            "đang sử dụng gps",
            "using gps",
            "chế độ lái xe",
            "driving mode",
            "notification settings",
            "cài đặt thông báo"
        )

        fun isIgnoredNotificationText(str: String?): Boolean {
            if (str.isNullOrBlank()) return true
            val lower = str.lowercase().trim()
            return IGNORED_NOTIFICATION_TEXTS.any { lower.contains(it) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private val activeWazeAlertNotificationKeys =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun updateWazeAlertNotificationKey(key: String, hasAlert: Boolean) {
        if (hasAlert) {
            activeWazeAlertNotificationKeys.add(key)
            return
        }
        activeWazeAlertNotificationKeys.remove(key)
        if (activeWazeAlertNotificationKeys.isEmpty()) {
            // Source-specific clear is safe even while HLP is connected: it only
            // removes the active warning when notification fallback owns it.
            VietmapStateRepository.clearAlertFromSource("WAZE_NOTIFICATION")
        }
    }

    private val wazeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val action = intent.action ?: return
            Log.d(TAG, "Received Waze Mod broadcast: $action")

            var speed: Int? = null
            var limit: Int? = null
            var warning: String? = null
            var distance: String? = null
            var road: String? = null

            if (intent.hasExtra("speed")) {
                speed = when (val s = intent.extras?.get("speed")) {
                    is Number -> s.toInt()
                    is String -> s.toIntOrNull()
                    else -> null
                }
            } else if (intent.hasExtra("current_speed")) {
                speed = intent.getIntExtra("current_speed", 0).takeIf { it > 0 }
            }

            if (intent.hasExtra("limit")) {
                limit = when (val l = intent.extras?.get("limit")) {
                    is Number -> l.toInt()
                    is String -> l.toIntOrNull()
                    else -> null
                }
            } else if (intent.hasExtra("speed_limit")) {
                limit = intent.getIntExtra("speed_limit", 0).takeIf { it > 0 }
            } else if (intent.hasExtra("max_speed")) {
                limit = intent.getIntExtra("max_speed", 0).takeIf { it > 0 }
            }

            warning = intent.getStringExtra("warning")
                ?: intent.getStringExtra("alert")
                ?: intent.getStringExtra("camera")
                ?: intent.getStringExtra("type")

            distance = intent.getStringExtra("distance")
                ?: intent.getStringExtra("dist")

            road = intent.getStringExtra("road")
                ?: intent.getStringExtra("street")
                ?: intent.getStringExtra("road_name")

            if (speed != null || limit != null || warning != null || distance != null || road != null) {
                mainHandler.post {
                    val cleanWarning = warning?.trim()
                    val explicitClear = !cleanWarning.isNullOrBlank() && (
                        cleanWarning.equals("none", ignoreCase = true) ||
                        cleanWarning.equals("clear", ignoreCase = true) ||
                        cleanWarning.equals("off", ignoreCase = true)
                    )

                    val classified = if (!cleanWarning.isNullOrBlank() && !explicitClear) {
                        val byIcon = VietmapIconClassifier.classifyFromText(cleanWarning)
                        if (byIcon != VietmapWarningType.NONE) byIcon
                        else WazeHlpWebSocketManager.mapWarningType(cleanWarning)
                    } else null

                    Log.d(TAG, "WAZE_BROADCAST action=$action extras=${intent.extras?.keySet()?.joinToString()} speed=$speed limit=$limit warning=$cleanWarning distance=$distance road=$road")

                    // Merge only the fields that are actually present. Do not replace the
                    // whole repository state, otherwise rich HLP fields (turn/ETA/alerts)
                    // get wiped by a simple speed broadcast.
                    VietmapStateRepository.mergeUpdate(
                        speed = speed,
                        limit = limit,
                        distanceText = if (classified != null) distance else null,
                        warningType = classified,
                        alertTitle = if (classified != null) (cleanWarning ?: classified.label) else null,
                        alertDescription = if (classified != null) cleanWarning else null,
                        roadName = road,
                        clearAlert = explicitClear,
                        source = "WAZE_BROADCAST"
                    )
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(TAG, "NotificationListenerService connected!")
        CarTtsManager.init(applicationContext)
        CarTtsManager.startListeningToRepository()
        registerWazeReceiver()
        WazeHlpWebSocketManager.start()
        checkActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
        Log.i(TAG, "NotificationListenerService disconnected.")
        unregisterWazeReceiver()
    }

    private fun registerWazeReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction("com.waze.SPEED_UPDATE")
                addAction("com.waze.SPEED_LIMIT")
                addAction("com.waze.ALERT")
                addAction("com.waze.mod.SPEED_UPDATE")
                addAction("com.waze.mod.ALERT")
                addAction("com.carhud.SPEED_ALERT")
                addAction("android.intent.action.SPEED_LIMIT_CHANGED")
            }
            ContextCompat.registerReceiver(
                this,
                wazeBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
            Log.i(TAG, "Waze Mod BroadcastReceiver registered successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Waze Mod receiver", e)
        }
    }

    private fun unregisterWazeReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(wazeBroadcastReceiver)
            isReceiverRegistered = false
        } catch (e: Exception) {}
    }

    private fun isWazePackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return WAZE_PACKAGES.contains(lower) || lower.contains("waze")
    }

    private fun isGoogleMapsPackage(pkg: String, notif: Notification? = null): Boolean {
        if (pkg == GOOGLE_MAPS_PACKAGE) return true
        if (pkg == ANDROID_AUTO_PACKAGE) {
            if (notif?.category == Notification.CATEGORY_NAVIGATION) return true
            val title = notif?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = notif?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            return title.isNotEmpty() && (
                title.contains("hướng", ignoreCase = true) ||
                title.contains("rẽ", ignoreCase = true) ||
                text.contains("km", ignoreCase = true) ||
                text.contains("phút", ignoreCase = true)
            )
        }
        if (notif?.category == Notification.CATEGORY_NAVIGATION && !isWazePackage(pkg)) return true
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (isWazePackage(pkg)) {
            processWazeNotification(sbn)
        } else if (isGoogleMapsPackage(pkg, sbn.notification)) {
            processGoogleMapsNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (isWazePackage(pkg)) {
            Log.d(TAG, "Waze notification removed from: $pkg key=${sbn.key}")
            mainHandler.post { updateWazeAlertNotificationKey(sbn.key, false) }
        } else if (isGoogleMapsPackage(pkg, sbn.notification)) {
            Log.d(TAG, "Google Maps notification removed from: $pkg")
            GoogleMapsStateRepository.clearNavigation()
        }
    }

    private fun checkActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (isWazePackage(sbn.packageName)) {
                    processWazeNotification(sbn)
                } else if (isGoogleMapsPackage(sbn.packageName, sbn.notification)) {
                    processGoogleMapsNotification(sbn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active notifications", e)
        }
    }

    private fun processGoogleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val linesJoined = textLines?.joinToString(" | ") { it.toString().trim() } ?: ""

        val combined = "$title | $text | $subText | $bigText | $linesJoined"
        Log.d(TAG, "Google Maps Notification: $combined")

        // Parse Distance
        var distanceStr = ""
        val distMatch = DISTANCE_REGEX.find(combined)
        if (distMatch != null) {
            distanceStr = distMatch.value.trim()
        }

        // Parse ETA
        var etaStr = ""
        val etaMatch = Regex("""\b(\d{1,2}:\d{2})\b""").find(combined)
        if (etaMatch != null) {
            etaStr = etaMatch.groupValues[1].trim()
        }

        // Parse Remaining Time (e.g. "1 giờ 36 phút", "36 phút", "1 h 36 min")
        var remainingTimeStr = ""
        val timeMatch = Regex("""(?:\d+\s*(?:gi\u1EDD|h|ti\u1EBFng|hr)\s*)?\d+\s*(?:ph\u00FAt|ph|min)""", RegexOption.IGNORE_CASE).find(combined)
        if (timeMatch != null) {
            remainingTimeStr = timeMatch.value.trim()
        }

        // Parse Remaining Distance (e.g. "78 km")
        var remainingDistStr = ""
        val kmMatch = Regex("""(\d+(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE).find(combined)
        if (kmMatch != null && kmMatch.value != distanceStr) {
            remainingDistStr = kmMatch.value.trim()
        }

        // Parse Traffic delay (e.g. "Chậm hơn 18 phút ở Trạm thu phí")
        var trafficDelayStr = ""
        val delayMatch = Regex("""(?:ch\u1EADm h\u01A1n|\+)\s*\d+\s*(?:ph\u00FAt|ph|min)[^|]*""", RegexOption.IGNORE_CASE).find(combined)
        if (delayMatch != null) {
            trafficDelayStr = delayMatch.value.trim()
        }

        // Parse Next Step (e.g. "Sau đó ↱", "Sau đó rẽ phải")
        var nextStepStr = ""
        val nextMatch = Regex("""(?:Sau \u0111\u00F3|Then)[^|]+""", RegexOption.IGNORE_CASE).find(combined)
        if (nextMatch != null) {
            nextStepStr = nextMatch.value.trim()
        }

        var maneuverStr = title
        var roadStr = text

        if (title.matches(Regex("""^[\d.,\s]+(?:m|km)$""", RegexOption.IGNORE_CASE))) {
            distanceStr = title
            maneuverStr = text
            roadStr = ""
        }

        if (maneuverStr.isEmpty()) {
            maneuverStr = "D\u1EABn \u0111\u01B0\u1EDDng Google Maps"
        }

        var iconBmp: Bitmap? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                iconBmp = extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                iconBmp = extras.getParcelable(Notification.EXTRA_LARGE_ICON)
            }
        } catch (e: Exception) {
            // fallback
        }

        val summaryParts = mutableListOf<String>()
        if (remainingTimeStr.isNotEmpty()) summaryParts.add(remainingTimeStr)
        if (remainingDistStr.isNotEmpty()) summaryParts.add(remainingDistStr)
        if (etaStr.isNotEmpty()) summaryParts.add(etaStr)

        val fullSummary = summaryParts.joinToString(" \u2022 ")

        mainHandler.post {
            val navData = GoogleMapsNavData(
                isConnected = true,
                isNavigating = true,
                maneuverText = maneuverStr,
                nextStepText = nextStepStr,
                distanceToTurn = distanceStr,
                roadName = roadStr,
                eta = etaStr,
                remainingDistance = remainingDistStr,
                remainingTime = remainingTimeStr,
                trafficDelay = trafficDelayStr,
                fullSummary = fullSummary,
                maneuverIcon = iconBmp,
                lastUpdateTime = System.currentTimeMillis()
            )
            GoogleMapsStateRepository.updateNavData(navData)
        }
    }

    private fun processWazeNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""
        val tickerText = notification.tickerText?.toString()?.trim() ?: extras.getCharSequence("android.tickerText")?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val linesJoined = textLines?.joinToString(" | ") { it.toString().trim() } ?: ""

        val combined = "$title | $text | $subText | $bigText | $infoText | $tickerText | $linesJoined"
        Log.d(TAG, "Waze Mod Notification Received: $combined")

        // Filter out pure status/background notifications
        if (isIgnoredNotificationText(title) && isIgnoredNotificationText(text)) {
            Log.d(TAG, "Ignoring Waze status notification: $combined")
            mainHandler.post { updateWazeAlertNotificationKey(sbn.key, false) }
            return
        }

        var parsedSpeed: Int? = null
        var parsedLimit: Int? = null
        var parsedDistanceStr: String? = null
        var parsedWarningType = VietmapWarningType.NONE
        var parsedAlertTitle: String? = null
        var parsedRoadName: String? = null

        // 1. Parse Current Speed
        val speedMatch = SPEED_REGEX.find(combined)
        if (speedMatch != null) {
            parsedSpeed = speedMatch.groupValues[1].toIntOrNull()
        }

        // 2. Parse Speed Limit
        val limitMatch = LIMIT_REGEX.find(combined)
        if (limitMatch != null) {
            parsedLimit = limitMatch.groupValues[1].toIntOrNull()
        }

        // 3. Fallback: Parse km/h values
        val kmhMatches = SPEED_KMH_REGEX.findAll(combined).toList()
        if (kmhMatches.isNotEmpty()) {
            val numbers = kmhMatches.mapNotNull { it.groupValues[1].toIntOrNull() }
            for (num in numbers) {
                if (parsedLimit == null && STANDARD_LIMITS.contains(num)) {
                    parsedLimit = num
                } else if (parsedSpeed == null && num != parsedLimit) {
                    parsedSpeed = num
                }
            }
        }

        // 4. Standalone speed-limit badge fallback. Only trust a value when the
        // field itself is basically just the badge ("60", "[60]", "(60)").
        // The old broad number scan could mistake ETA/distance/road numbers for a
        // speed limit.
        if (parsedLimit == null) {
            val targetForNum = when {
                subText.isNotEmpty() -> subText
                text.isNotEmpty() -> text
                else -> title
            }.trim()

            val badgeMatch = Regex("""^[\[\(]?\s*(\d{2,3})\s*[\]\)]?$""").matchEntire(targetForNum)
            val n = badgeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (n != null && STANDARD_LIMITS.contains(n)) {
                parsedLimit = n
            }
        }

        // 5. Parse Distance
        val distMatch = DISTANCE_REGEX.find(combined)
        if (distMatch != null) {
            parsedDistanceStr = distMatch.value.trim()
        }

        // 6. Parse Warning Type
        parsedWarningType = VietmapIconClassifier.classifyFromText(combined)
        if (parsedWarningType != VietmapWarningType.NONE) {
            parsedAlertTitle = parsedWarningType.label
        } else {
            val wazeFallback = WazeHlpWebSocketManager.mapWarningType(combined)
            if (wazeFallback != VietmapWarningType.NONE) {
                parsedWarningType = wazeFallback
                parsedAlertTitle = wazeFallback.label
            } else {
                val lower = combined.lowercase()
                if (lower.contains("bắn tốc độ") || lower.contains("camera") || lower.contains("phạt nguội")) {
                    parsedWarningType = VietmapWarningType.SPEED_CAMERA
                    parsedAlertTitle = "Camera đo tốc độ"
                }
            }
        }

        // 7. Parse Road Name (filter out ignored strings)
        if (title.isNotEmpty() && !title.contains("km/h", ignoreCase = true) && !title.contains("waze", ignoreCase = true) && !title.matches(Regex("""^\d+.*""")) && !isIgnoredNotificationText(title)) {
            parsedRoadName = title
        } else if (text.isNotEmpty() && !text.contains("km/h", ignoreCase = true) && !text.contains("waze", ignoreCase = true) && !text.matches(Regex("""^\d+.*""")) && !isIgnoredNotificationText(text)) {
            parsedRoadName = text
        }

        // If no meaningful data extracted, ignore
        if (parsedSpeed == null && parsedLimit == null && parsedWarningType == VietmapWarningType.NONE && parsedRoadName == null) {
            return
        }

        // Commit state to repository by MERGING only fields found in this notification.
        // Notification updates are often partial. Replacing the entire state here used to
        // erase turn/ETA/lane/alert data received from HLP/1.
        mainHandler.post {
            val hasNewAlert = (parsedWarningType != VietmapWarningType.NONE)
            updateWazeAlertNotificationKey(sbn.key, hasNewAlert)
            Log.d(TAG, "WAZE_NOTIF_PARSED speed=$parsedSpeed limit=$parsedLimit alert=$parsedWarningType title=$parsedAlertTitle dist=$parsedDistanceStr road=$parsedRoadName")

            VietmapStateRepository.mergeUpdate(
                speed = parsedSpeed,
                limit = parsedLimit,
                distanceText = if (hasNewAlert) parsedDistanceStr else null,
                warningType = if (hasNewAlert) parsedWarningType else null,
                alertTitle = if (hasNewAlert) (parsedAlertTitle ?: parsedWarningType.label) else null,
                alertDescription = if (hasNewAlert) parsedAlertTitle else null,
                roadName = parsedRoadName,
                clearAlert = false,
                source = "WAZE_NOTIFICATION"
            )
        }
    }
}