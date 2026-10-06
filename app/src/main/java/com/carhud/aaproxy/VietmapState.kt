package com.carhud.aaproxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Waze / VietMap warning types used by HUD and TTS.
 */
enum class VietmapWarningType(
    val label: String,
    val iconEmoji: String,
    val voicePhrase: String = "",
    val category: String = "CẢNH BÁO",
    val accentColor: String = "#F59E0B"
) {
    NONE("Không có cảnh báo", "🛡️", "", "AN TOÀN", "#10B981"),
    SPEED_CAMERA("Camera phạt nguội tốc độ", "📷", "camera phạt nguội tốc độ", "CAMERA PHẠT NGUỘI", "#F59E0B"),
    RED_LIGHT_CAMERA("Camera vượt đèn đỏ", "🚦", "camera phạt vượt đèn đỏ", "CAMERA ĐÈN ĐỎ", "#EF4444"),
    POLICE("Cảnh sát giao thông / Bắn tốc độ", "👮", "cảnh sát giao thông phía trước", "CHỐT CSGT", "#F59E0B"),
    ACCIDENT("Tai nạn phía trước", "💥", "có tai nạn phía trước", "SỰ CỐ TAI NẠN", "#EF4444"),
    HAZARD("Chú ý nguy hiểm trên đường", "⚠️", "có nguy hiểm trên đường", "CHÚ Ý NGUY HIỂM", "#F59E0B"),
    CONSTRUCTION("Công trường đang thi công", "🚧", "công trường đang thi công", "CÔNG TRƯỜNG", "#F59E0B"),
    TRAFFIC_JAM("Ùn tắc giao thông", "🚗", "phía trước có ùn tắc giao thông", "ÙN TẮC GIAO THÔNG", "#EF4444"),
    SPEED_LIMIT_ZONE("Đoạn đường giới hạn tốc độ", "⛔", "đoạn đường giới hạn tốc độ", "GIỚI HẠN TỐC ĐỘ", "#EF4444"),
    RESIDENTIAL_START("Bắt đầu khu dân cư", "🏘️", "bắt đầu vào khu dân cư", "KHU DÂN CƯ", "#0284C7"),
    RESIDENTIAL_END("Hết khu dân cư", "🛣️", "hết khu dân cư", "HẾT KHU DÂN CƯ", "#10B981"),
    NO_OVERTAKING("Đoạn đường cấm vượt", "🚫", "đoạn đường cấm vượt", "CẤM VƯỢT", "#EF4444"),
    END_NO_OVERTAKING("Hết đoạn cấm vượt", "✅", "hết đoạn cấm vượt", "HẾT CẤM VƯỢT", "#10B981"),
    TOLL_BOOTH("Trạm thu phí BOT", "💰", "phía trước có trạm thu phí", "TRẠM THU PHÍ", "#8B5CF6"),
    TUNNEL("Hầm chui / Cầu vượt", "🚇", "phía trước có hầm chui hoặc cầu vượt", "CẦU VƯỢT / HẦM", "#0284C7"),
    UTURN("Quay đầu xe phía trước", "↩️", "chuẩn bị quay đầu xe", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    TURN_LEFT("Rẽ trái phía trước", "⬅️", "chuẩn bị rẽ trái", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    TURN_RIGHT("Rẽ phải phía trước", "➡️", "chuẩn bị rẽ phải", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    KEEP_LEFT("Đi chếch sang trái", "↖️", "đi chếch sang trái", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    KEEP_RIGHT("Đi chếch sang phải", "↗️", "đi chếch sang phải", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    ROUNDABOUT("Vào vòng xuyến / Bùng binh", "🔄", "chuẩn bị vào vòng xuyến", "VÒNG XUYẾN", "#06B6D4"),
    STRAIGHT("Tiếp tục đi thẳng", "⬆️", "tiếp tục đi thẳng", "CHỈ DẪN LỘ TRÌNH", "#06B6D4"),
    GAS_STATION("Trạm xăng phía trước", "⛽", "phía trước có trạm xăng", "DỊCH VỤ DỌC ĐƯỜNG", "#0284C7")
}

data class WazeAlertItem(
    val code: Int = 0,
    val warningType: VietmapWarningType = VietmapWarningType.NONE,
    val title: String? = null,
    val distanceMeters: Int? = null,
    val value: Int? = null,
    val jamSeverity: Int? = null,
    val jamDelayMinutes: Int? = null,
    val roadName: String? = null
)

data class VietmapAlertData(
    val isConnected: Boolean = false,
    val currentSpeed: Int = 0,
    val speedLimit: Int? = null,
    val secondarySpeedLimit: Int? = null,
    val turnCode: Int = 0,
    val turnAction: String = "straight",
    val turnDescription: String? = null,
    val distanceToTurnMeters: Int = 0,
    val etaTime: String? = null,
    val remainingMinutes: Int? = null,
    val remainingDistanceKm: Float? = null,
    val alertDescription: String? = null,
    val lastUpdatedFormatted: String? = null,
    val distanceText: String? = null,
    val distanceMeters: Int? = null,
    val warningType: VietmapWarningType = VietmapWarningType.NONE,
    val alertTitle: String? = null,
    val roadName: String? = null,
    val nextRoadName: String? = null,
    val laneGuidance: String? = null,
    val trafficLevel: Int? = null,
    val trafficDelayMinutes: Int? = null,
    val upcomingAlerts: List<WazeAlertItem> = emptyList(),
    val isOverSpeed: Boolean = false,
    /** Current best telemetry source. */
    val source: String = "UNKNOWN",
    /** Last time [source] itself produced telemetry. */
    val sourceUpdatedAt: Long = 0L,
    val lastUpdated: Long = 0L,
    /** Source that produced the currently displayed warning. */
    val alertSource: String = "UNKNOWN",
    /** Last time the active alert itself was observed/updated. */
    val alertTimestamp: Long = 0L
) {
    val isLiveFresh: Boolean
        get() {
            val telemetryTs = sourceUpdatedAt.takeIf { it > 0L } ?: lastUpdated
            return isConnected && telemetryTs > 0L && System.currentTimeMillis() - telemetryTs < 10_000L
        }

    val hasActiveAlert: Boolean
        get() = WazeAlertPolicy.isAlertFresh(this)
}

object VietmapStateRepository {
    private val _alertState = MutableStateFlow(VietmapAlertData())
    val alertState: StateFlow<VietmapAlertData> = _alertState.asStateFlow()

    fun updateState(newState: VietmapAlertData) {
        _alertState.value = newState
    }

    fun updateConnection(connected: Boolean) {
        val cur = _alertState.value
        _alertState.value = if (!connected && cur.source == "WAZE_HLP") {
            cur.copy(
                isConnected = false,
                source = "UNKNOWN",
                sourceUpdatedAt = 0L
            )
        } else {
            cur.copy(isConnected = connected)
        }
    }

    /**
     * Merge partial frames from HLP, Waze broadcasts and NotificationListener.
     *
     * Higher-quality HLP telemetry is protected from being overwritten by a stale
     * notification frame, while lower-priority sources are still allowed to add a
     * warning when HLP did not provide one.
     */
    fun mergeUpdate(
        speed: Int? = null,
        limit: Int? = null,
        secondaryLimit: Int? = null,
        turnCode: Int? = null,
        turnAction: String? = null,
        turnDescription: String? = null,
        distanceToTurnMeters: Int? = null,
        etaTime: String? = null,
        remainingMinutes: Int? = null,
        remainingDistanceKm: Float? = null,
        alertDescription: String? = null,
        lastUpdatedFormatted: String? = null,
        distanceText: String? = null,
        distanceMeters: Int? = null,
        warningType: VietmapWarningType? = null,
        alertTitle: String? = null,
        roadName: String? = null,
        nextRoadName: String? = null,
        laneGuidance: String? = null,
        trafficLevel: Int? = null,
        trafficDelayMinutes: Int? = null,
        upcomingAlerts: List<WazeAlertItem>? = null,
        clearAlert: Boolean = false,
        source: String = "UNKNOWN"
    ) {
        val cur = _alertState.value
        val now = System.currentTimeMillis()

        val incomingSourcePriority = WazeAlertPolicy.sourcePriority(source)
        val currentSourcePriority = WazeAlertPolicy.sourcePriority(cur.source)
        val currentAlertSourcePriority = WazeAlertPolicy.sourcePriority(cur.alertSource)
        val currentTelemetryFresh = cur.isConnected &&
            cur.sourceUpdatedAt > 0L &&
            now - cur.sourceUpdatedAt < 3_000L
        val protectHigherQualityTelemetry = currentTelemetryFresh && incomingSourcePriority < currentSourcePriority

        val acceptedSpeed = if (protectHigherQualityTelemetry && cur.currentSpeed > 0) null else speed
        val acceptedLimit = if (protectHigherQualityTelemetry && cur.speedLimit != null) null else limit
        val acceptedSecondary = if (protectHigherQualityTelemetry && cur.secondarySpeedLimit != null) null else secondaryLimit

        val newSpeed = acceptedSpeed ?: cur.currentSpeed
        val newLimit = acceptedLimit ?: cur.speedLimit
        val newSecLimit = when {
            source == "WAZE_HLP" && upcomingAlerts != null -> secondaryLimit
            else -> acceptedSecondary ?: cur.secondarySpeedLimit
        }

        // Navigation is particularly sensitive to partial notification frames.
        val allowNavigationOverride = !protectHigherQualityTelemetry || incomingSourcePriority >= currentSourcePriority
        val acceptedTurnCode = if (allowNavigationOverride) turnCode else null
        val acceptedTurnAction = if (allowNavigationOverride) turnAction else null
        val acceptedTurnDescription = if (allowNavigationOverride) turnDescription else null
        val acceptedTurnDistance = if (allowNavigationOverride) distanceToTurnMeters else null
        val acceptedEta = if (allowNavigationOverride) etaTime else null
        val acceptedRemainingMinutes = if (allowNavigationOverride) remainingMinutes else null
        val acceptedRemainingDistance = if (allowNavigationOverride) remainingDistanceKm else null
        val acceptedNextRoad = if (allowNavigationOverride) nextRoadName else null
        val acceptedLaneGuidance = if (allowNavigationOverride) laneGuidance else null
        val acceptedTrafficLevel = if (allowNavigationOverride) trafficLevel else null
        val acceptedTrafficDelay = if (allowNavigationOverride) trafficDelayMinutes else null

        val newTurnCode = acceptedTurnCode ?: cur.turnCode
        val newTurnAction = when {
            acceptedTurnCode != null && acceptedTurnCode == 0 -> "straight"
            !acceptedTurnAction.isNullOrBlank() -> acceptedTurnAction
            else -> cur.turnAction
        }
        val newTurnDesc = when {
            acceptedTurnCode != null && acceptedTurnCode == 0 -> null
            acceptedTurnDescription != null -> acceptedTurnDescription
            else -> cur.turnDescription
        }
        val newDistToTurn = acceptedTurnDistance ?: cur.distanceToTurnMeters

        val newEta = acceptedEta ?: cur.etaTime
        val newRemMin = acceptedRemainingMinutes ?: cur.remainingMinutes
        val newRemDist = acceptedRemainingDistance ?: cur.remainingDistanceKm

        val incomingRoad = roadName?.takeIf { it.isNotBlank() }
        val newRoad = when {
            incomingRoad == null -> cur.roadName
            protectHigherQualityTelemetry && !cur.roadName.isNullOrBlank() -> cur.roadName
            else -> incomingRoad
        }

        val isOver = WazeAlertPolicy.isOverspeed(newSpeed, newLimit, cur.isOverSpeed)

        val incomingWarning = warningType?.takeIf { it != VietmapWarningType.NONE }
        val incomingHasAlert = incomingWarning != null || !alertTitle.isNullOrBlank() || !alertDescription.isNullOrBlank()
        val currentAlertFresh = cur.hasActiveAlert

        val inferredIncomingType = when {
            incomingWarning != null -> incomingWarning
            !alertTitle.isNullOrBlank() -> WazeHlpWebSocketManager.mapWarningType(alertTitle)
            !alertDescription.isNullOrBlank() -> WazeHlpWebSocketManager.mapWarningType(alertDescription)
            else -> VietmapWarningType.NONE
        }

        val incomingDistance = distanceMeters ?: WazeAlertPolicy.parseDistanceMeters(distanceText)
        val currentDistance = WazeAlertPolicy.effectiveAlertDistanceMeters(cur)

        // Some Waze builds use distance=0 for "unknown/current" while the alert is
        // still active. Only treat zero as passed when this is the same alert from
        // the same source and we previously observed a positive distance.
        val distanceSaysPassed =
            incomingDistance == 0 &&
            currentDistance != null &&
            currentDistance > 0 &&
            inferredIncomingType != VietmapWarningType.NONE &&
            inferredIncomingType == cur.warningType &&
            source == cur.alertSource
        val isExplicitClear = clearAlert || distanceSaysPassed

        val incomingAlertScore = WazeAlertPolicy.score(inferredIncomingType, incomingDistance)
        val currentAlertScore = WazeAlertPolicy.score(cur.warningType, currentDistance)
        val incomingAlertRank = incomingAlertScore * 10 + incomingSourcePriority
        val currentAlertRank = currentAlertScore * 10 + currentAlertSourcePriority
        val canClearAlert = isExplicitClear && (
            !currentAlertFresh ||
                incomingSourcePriority >= currentAlertSourcePriority ||
                source == cur.alertSource
            )

        val allowIncomingAlert = when {
            canClearAlert -> true
            !incomingHasAlert -> false
            !currentAlertFresh -> true
            // Same warning: accept fresh distance/title updates regardless of source,
            // while protectCurrentAlertPayload below preserves better HLP payload.
            inferredIncomingType == cur.warningType -> true
            // Safety/urgency is the primary ranking; source quality is only a tie-breaker.
            incomingAlertRank > currentAlertRank -> true
            else -> false
        }

        val protectCurrentAlertPayload = protectHigherQualityTelemetry &&
            currentAlertFresh &&
            incomingSourcePriority < currentSourcePriority &&
            inferredIncomingType == cur.warningType

        val newWarning: VietmapWarningType
        val newTitle: String?
        val newAlertDesc: String?
        val newDistText: String?
        val newDistMeters: Int?
        val newAlertSource: String
        val newAlertTs: Long

        when {
            canClearAlert -> {
                newWarning = VietmapWarningType.NONE
                newTitle = null
                newAlertDesc = null
                newDistText = null
                newDistMeters = null
                newAlertSource = "UNKNOWN"
                newAlertTs = 0L
            }
            allowIncomingAlert -> {
                newWarning = if (inferredIncomingType != VietmapWarningType.NONE) inferredIncomingType else cur.warningType
                newTitle = if (protectCurrentAlertPayload && !cur.alertTitle.isNullOrBlank()) {
                    cur.alertTitle
                } else {
                    alertTitle ?: cur.alertTitle
                }
                newAlertDesc = if (protectCurrentAlertPayload && !cur.alertDescription.isNullOrBlank()) {
                    cur.alertDescription
                } else {
                    alertDescription ?: cur.alertDescription
                }
                newDistText = if (protectCurrentAlertPayload && cur.distanceMeters != null) {
                    cur.distanceText
                } else {
                    distanceText ?: cur.distanceText
                }
                newDistMeters = if (protectCurrentAlertPayload && cur.distanceMeters != null) {
                    cur.distanceMeters
                } else {
                    incomingDistance ?: cur.distanceMeters
                }
                newAlertSource = if (protectCurrentAlertPayload) cur.alertSource else source
                newAlertTs = if (protectCurrentAlertPayload) cur.alertTimestamp else now
            }
            else -> {
                newWarning = cur.warningType
                newTitle = cur.alertTitle
                newAlertDesc = cur.alertDescription
                newDistText = cur.distanceText
                newDistMeters = cur.distanceMeters
                newAlertSource = cur.alertSource
                newAlertTs = cur.alertTimestamp
            }
        }

        val effectiveSource = when {
            incomingSourcePriority > currentSourcePriority -> source
            !currentTelemetryFresh -> source
            cur.source == "UNKNOWN" -> source
            else -> cur.source
        }
        val effectiveSourceUpdatedAt = when {
            effectiveSource != cur.source -> now
            source == cur.source -> now
            else -> cur.sourceUpdatedAt
        }

        val newUpcomingAlerts = when {
            canClearAlert -> emptyList()
            upcomingAlerts != null && (!protectHigherQualityTelemetry || incomingSourcePriority >= currentSourcePriority || source == "WAZE_HLP") ->
                WazeAlertPolicy.dedupeAlerts(upcomingAlerts)
            else -> cur.upcomingAlerts
        }

        _alertState.value = cur.copy(
            isConnected = true,
            currentSpeed = newSpeed,
            speedLimit = newLimit,
            secondarySpeedLimit = newSecLimit,
            turnCode = newTurnCode,
            turnAction = newTurnAction,
            turnDescription = newTurnDesc,
            distanceToTurnMeters = newDistToTurn,
            etaTime = newEta,
            remainingMinutes = newRemMin,
            remainingDistanceKm = newRemDist,
            alertDescription = newAlertDesc,
            lastUpdatedFormatted = lastUpdatedFormatted ?: cur.lastUpdatedFormatted,
            distanceText = newDistText,
            distanceMeters = newDistMeters,
            warningType = newWarning,
            alertTitle = newTitle,
            roadName = newRoad,
            nextRoadName = acceptedNextRoad ?: cur.nextRoadName,
            laneGuidance = acceptedLaneGuidance ?: cur.laneGuidance,
            trafficLevel = acceptedTrafficLevel ?: cur.trafficLevel,
            trafficDelayMinutes = acceptedTrafficDelay ?: cur.trafficDelayMinutes,
            upcomingAlerts = newUpcomingAlerts,
            isOverSpeed = isOver,
            source = effectiveSource,
            sourceUpdatedAt = effectiveSourceUpdatedAt,
            lastUpdated = now,
            alertSource = newAlertSource,
            alertTimestamp = newAlertTs
        )
    }

    fun beginHlpSession() {
        val cur = _alertState.value
        val hlpOwnedAlert = cur.alertSource == "WAZE_HLP"
        val hlpOwnedTelemetry = cur.source == "WAZE_HLP"
        _alertState.value = cur.copy(
            isConnected = true,
            alertDescription = if (hlpOwnedAlert) null else cur.alertDescription,
            distanceText = if (hlpOwnedAlert) null else cur.distanceText,
            distanceMeters = if (hlpOwnedAlert) null else cur.distanceMeters,
            warningType = if (hlpOwnedAlert) VietmapWarningType.NONE else cur.warningType,
            alertTitle = if (hlpOwnedAlert) null else cur.alertTitle,
            upcomingAlerts = emptyList(),
            source = if (hlpOwnedTelemetry) "UNKNOWN" else cur.source,
            sourceUpdatedAt = if (hlpOwnedTelemetry) 0L else cur.sourceUpdatedAt,
            alertSource = if (hlpOwnedAlert) "UNKNOWN" else cur.alertSource,
            alertTimestamp = if (hlpOwnedAlert) 0L else cur.alertTimestamp
        )
    }

    fun clearHlpAlerts() {
        val cur = _alertState.value
        val hlpOwnedAlert = cur.alertSource == "WAZE_HLP"
        val hlpOwnedTelemetry = cur.source == "WAZE_HLP"
        if (!hlpOwnedAlert && !hlpOwnedTelemetry && cur.upcomingAlerts.isEmpty()) return

        _alertState.value = cur.copy(
            alertDescription = if (hlpOwnedAlert) null else cur.alertDescription,
            distanceText = if (hlpOwnedAlert) null else cur.distanceText,
            distanceMeters = if (hlpOwnedAlert) null else cur.distanceMeters,
            warningType = if (hlpOwnedAlert) VietmapWarningType.NONE else cur.warningType,
            alertTitle = if (hlpOwnedAlert) null else cur.alertTitle,
            upcomingAlerts = emptyList(),
            source = if (hlpOwnedTelemetry) "UNKNOWN" else cur.source,
            sourceUpdatedAt = if (hlpOwnedTelemetry) 0L else cur.sourceUpdatedAt,
            alertSource = if (hlpOwnedAlert) "UNKNOWN" else cur.alertSource,
            alertTimestamp = if (hlpOwnedAlert) 0L else cur.alertTimestamp
        )
    }

    fun clearAlertFromSource(source: String) {
        val cur = _alertState.value
        if (cur.alertSource != source) return
        _alertState.value = cur.copy(
            alertDescription = null,
            distanceText = null,
            distanceMeters = null,
            warningType = VietmapWarningType.NONE,
            alertTitle = null,
            alertSource = "UNKNOWN",
            alertTimestamp = 0L
        )
    }

    fun updateSpeed(speed: Int) {
        val cur = _alertState.value
        val isOver = WazeAlertPolicy.isOverspeed(speed, cur.speedLimit, cur.isOverSpeed)
        val now = System.currentTimeMillis()
        val gpsOwnsTelemetry = cur.source == "UNKNOWN" || cur.source == "GPS"
        _alertState.value = cur.copy(
            currentSpeed = speed,
            isOverSpeed = isOver,
            source = if (gpsOwnsTelemetry) "GPS" else cur.source,
            sourceUpdatedAt = if (gpsOwnsTelemetry) now else cur.sourceUpdatedAt,
            lastUpdated = now
        )
    }

    fun reset() {
        _alertState.value = VietmapAlertData()
    }
}
