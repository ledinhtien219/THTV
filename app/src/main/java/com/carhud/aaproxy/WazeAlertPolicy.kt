package com.carhud.aaproxy

import android.graphics.Color
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Centralized policy for Waze/HLP warnings.
 *
 * Keeps presentation, TTS and parser decisions consistent:
 * - adaptive alert lifetime (longer for distant warnings)
 * - distance/priority scoring for multiple simultaneous warnings
 * - overspeed hysteresis to avoid flicker around the speed limit
 * - distance buckets for concise, non-spammy TTS reminders
 */
object WazeAlertPolicy {

    private const val MIN_ALERT_TTL_MS = 12_000L
    private const val MAX_ALERT_TTL_MS = 75_000L

    fun priority(type: VietmapWarningType): Int = when (type) {
        VietmapWarningType.RED_LIGHT_CAMERA -> 100
        VietmapWarningType.SPEED_CAMERA -> 96
        VietmapWarningType.POLICE -> 94
        VietmapWarningType.ACCIDENT -> 92
        VietmapWarningType.HAZARD -> 90
        VietmapWarningType.CONSTRUCTION -> 82
        VietmapWarningType.SPEED_LIMIT_ZONE -> 80
        VietmapWarningType.NO_OVERTAKING -> 78
        VietmapWarningType.RESIDENTIAL_START -> 72
        VietmapWarningType.TRAFFIC_JAM -> 68
        VietmapWarningType.TOLL_BOOTH -> 55
        VietmapWarningType.TUNNEL -> 50
        VietmapWarningType.RESIDENTIAL_END -> 45
        VietmapWarningType.END_NO_OVERTAKING -> 45
        VietmapWarningType.GAS_STATION -> 25
        VietmapWarningType.UTURN,
        VietmapWarningType.TURN_LEFT,
        VietmapWarningType.TURN_RIGHT,
        VietmapWarningType.KEEP_LEFT,
        VietmapWarningType.KEEP_RIGHT,
        VietmapWarningType.ROUNDABOUT,
        VietmapWarningType.STRAIGHT -> 20
        VietmapWarningType.NONE -> 0
    }

    fun isRoadHazard(type: VietmapWarningType): Boolean = priority(type) >= 45

    fun isCritical(type: VietmapWarningType): Boolean = priority(type) >= 90

    fun isCameraCategory(type: VietmapWarningType): Boolean =
        type == VietmapWarningType.SPEED_CAMERA || type == VietmapWarningType.RED_LIGHT_CAMERA

    /**
     * Prefer warnings that are both close and important. A nearby accident/hazard
     * should beat a far-away camera, while two alerts in the same distance band are
     * ordered by safety priority.
     */
    fun score(type: VietmapWarningType, distanceMeters: Int?): Int {
        val d = distanceMeters?.coerceAtLeast(0)
        val distanceBand = when {
            d == null -> 1
            d <= 100 -> 7
            d <= 200 -> 6
            d <= 350 -> 5
            d <= 700 -> 4
            d <= 1_200 -> 3
            d <= 2_000 -> 2
            else -> 1
        }
        // Safety class is the main signal, distance is the urgency modifier.
        // This lets a very close crash/hazard beat a far camera, but prevents a
        // low-priority roadside item from hiding a serious warning just because
        // it is a little closer.
        return priority(type) * 10 + distanceBand * 10
    }

    fun alertTtlMs(data: VietmapAlertData): Long {
        val distance = effectiveAlertDistanceMeters(data)
        val type = data.warningType
        val base = when {
            distance == null -> 20_000L
            distance > 2_000 -> 75_000L
            distance > 1_000 -> 60_000L
            distance > 500 -> 45_000L
            distance > 200 -> 32_000L
            else -> 20_000L
        }
        val criticalBonus = if (isCritical(type)) 8_000L else 0L
        return (base + criticalBonus).coerceIn(MIN_ALERT_TTL_MS, MAX_ALERT_TTL_MS)
    }

    fun isAlertFresh(data: VietmapAlertData, now: Long = System.currentTimeMillis()): Boolean {
        if (data.warningType == VietmapWarningType.NONE && data.alertTitle.isNullOrBlank() && data.alertDescription.isNullOrBlank()) {
            return false
        }
        if (data.alertTimestamp <= 0L) return false
        return now - data.alertTimestamp <= alertTtlMs(data)
    }

    fun effectiveAlertDistanceMeters(data: VietmapAlertData): Int? {
        data.distanceMeters?.takeIf { it >= 0 }?.let { return it }
        return parseDistanceMeters(data.distanceText)
    }

    fun parseDistanceMeters(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val text = value.trim().lowercase(Locale.ROOT).replace(',', '.')
        val number = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
        return when {
            text.contains("km") -> (number * 1000f).roundToInt()
            text.contains("m") -> number.roundToInt()
            else -> number.roundToInt()
        }.coerceAtLeast(0)
    }

    fun formatDistance(distanceMeters: Int?): String {
        val d = distanceMeters ?: return ""
        if (d <= 0) return ""
        return when {
            d >= 10_000 -> "${(d / 1000f).roundToInt()} km"
            d >= 1_000 -> String.format(Locale.US, "%.1f km", d / 1000f)
            else -> "${d}m"
        }
    }

    /**
     * Start overspeed warning with a small margin and clear it with hysteresis.
     * This prevents the HUD/TTS from toggling rapidly at 59/60/61 km/h.
     */
    fun isOverspeed(speed: Int, limit: Int?, wasOverSpeed: Boolean): Boolean {
        val lim = limit?.takeIf { it > 0 } ?: return false
        val startDelta = when {
            lim <= 40 -> 3
            lim <= 70 -> 4
            else -> 5
        }
        val clearDelta = 1
        return if (wasOverSpeed) {
            speed > lim + clearDelta
        } else {
            speed >= lim + startDelta
        }
    }

    fun overspeedSeverity(speed: Int, limit: Int?): Int {
        val lim = limit?.takeIf { it > 0 } ?: return 0
        val delta = speed - lim
        return when {
            delta >= 20 -> 3
            delta >= 10 -> 2
            delta >= 3 -> 1
            else -> 0
        }
    }

    /**
     * Returns a stable reminder bucket. TTS only repeats when the car crosses a
     * meaningful distance threshold instead of every small distance update.
     */
    fun distanceBucket(type: VietmapWarningType, distanceMeters: Int?): Int {
        val d = distanceMeters ?: return -1
        val thresholds = if (isCritical(type)) {
            intArrayOf(1_500, 1_000, 700, 500, 300, 150, 80)
        } else {
            intArrayOf(1_000, 700, 400, 200, 100)
        }
        for (threshold in thresholds) {
            if (d >= threshold) return threshold
        }
        return 0
    }

    fun shortTitle(type: VietmapWarningType, fallback: String? = null): String {
        val specific = fallback
            ?.replace("VML-TPMS", "", ignoreCase = true)
            ?.replace("TPMS", "", ignoreCase = true)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.length in 3..60 && !it.equals("Waze Mod", ignoreCase = true) }

        return when (type) {
            VietmapWarningType.RESIDENTIAL_START -> "Khu dân cư"
            VietmapWarningType.RESIDENTIAL_END -> "Hết khu dân cư"
            VietmapWarningType.SPEED_CAMERA -> specific ?: "Camera tốc độ"
            VietmapWarningType.RED_LIGHT_CAMERA -> specific ?: "Camera đèn đỏ"
            VietmapWarningType.POLICE -> specific ?: "Chốt CSGT"
            VietmapWarningType.NO_OVERTAKING -> "Cấm vượt"
            VietmapWarningType.END_NO_OVERTAKING -> "Hết cấm vượt"
            VietmapWarningType.TOLL_BOOTH -> specific ?: "Trạm thu phí"
            VietmapWarningType.TUNNEL -> specific ?: "Hầm / Cầu vượt"
            VietmapWarningType.ACCIDENT -> specific ?: "Tai nạn phía trước"
            VietmapWarningType.CONSTRUCTION -> specific ?: "Công trường"
            VietmapWarningType.TRAFFIC_JAM -> specific ?: "Ùn tắc"
            VietmapWarningType.HAZARD -> specific ?: "Nguy hiểm phía trước"
            VietmapWarningType.SPEED_LIMIT_ZONE -> specific ?: "Giới hạn tốc độ"
            else -> specific ?: type.label
        }
    }

    fun accentColor(type: VietmapWarningType, distanceMeters: Int?): Int {
        val d = distanceMeters
        if (d != null && d in 0..150 && isRoadHazard(type)) {
            return Color.parseColor("#FF3B30")
        }
        return try {
            Color.parseColor(type.accentColor)
        } catch (_: Throwable) {
            Color.parseColor("#F59E0B")
        }
    }

    fun alertKey(type: VietmapWarningType, title: String?, road: String?): String {
        val cleanTitle = title.orEmpty().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        val cleanRoad = road.orEmpty().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        return "${type.name}|$cleanTitle|$cleanRoad"
    }

    /**
     * WazeMod may repeat the same report in alrs with slightly different payload
     * richness. Keep the original near-to-far ordering, but collapse duplicates
     * and retain the closest/richest copy.
     */
    fun dedupeAlerts(alerts: List<WazeAlertItem>): List<WazeAlertItem> {
        if (alerts.size < 2) return alerts

        fun normalizedRoad(item: WazeAlertItem): String =
            item.roadName.orEmpty()
                .lowercase(Locale.ROOT)
                .replace(Regex("""\s+"""), " ")
                .trim()

        fun sameIdentity(a: WazeAlertItem, b: WazeAlertItem): Boolean {
            val sameType = if (a.code > 0 && b.code > 0) {
                a.code == b.code
            } else {
                a.warningType == b.warningType &&
                    a.title.orEmpty().trim().equals(b.title.orEmpty().trim(), ignoreCase = true)
            }
            if (!sameType || normalizedRoad(a) != normalizedRoad(b)) return false

            val da = a.distanceMeters?.takeIf { it > 0 }
            val db = b.distanceMeters?.takeIf { it > 0 }
            return when {
                da != null && db != null -> kotlin.math.abs(da - db) <= 80
                da == null && db == null -> true
                else -> true
            }
        }

        val result = mutableListOf<WazeAlertItem>()
        for (item in alerts) {
            val duplicateIndex = result.indexOfFirst { sameIdentity(it, item) }
            if (duplicateIndex < 0) {
                result += item
                continue
            }

            val previous = result[duplicateIndex]
            val previousDistance = previous.distanceMeters?.takeIf { it > 0 }
            val incomingDistance = item.distanceMeters?.takeIf { it > 0 }
            val preferIncoming = when {
                previousDistance == null && incomingDistance != null -> true
                previousDistance != null && incomingDistance != null && incomingDistance < previousDistance -> true
                previous.title.isNullOrBlank() && !item.title.isNullOrBlank() -> true
                else -> false
            }
            if (preferIncoming) result[duplicateIndex] = item
        }
        return result
    }

    fun sourcePriority(source: String): Int = when (source.uppercase(Locale.ROOT)) {
        "MANUAL_TEST" -> 5
        "WAZE_HLP", "HLP" -> 4
        "WAZE_BROADCAST", "BROADCAST" -> 3
        "WAZE_NOTIFICATION", "NOTIFICATION" -> 2
        "GPS" -> 1
        else -> 0
    }
}
