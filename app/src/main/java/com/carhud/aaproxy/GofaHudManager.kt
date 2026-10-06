package com.carhud.aaproxy

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GOFA HUD bridge.
 *
 * BLE discovery/GATT parsing will plug into [submitFrame] once the GOFA HUD
 * UUIDs and packet format are mapped. Keeping this bridge separate from the UI
 * lets GOFA and Waze share the existing VietmapHudOverlay renderer safely.
 */
object GofaHudManager {
    const val PACKAGE_NAME = "com.gofa.mobile"

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusText = MutableStateFlow("Chưa kết nối GOFA")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    fun start(context: Context) {
        HudSourceManager.init(context)
        if (!HudSourceManager.isGofa(context)) {
            stop()
            return
        }
        _isConnected.value = false
        _statusText.value = "Đã chọn GOFA • Chờ kết nối BLE HUD"
    }

    fun stop() {
        _isConnected.value = false
        _statusText.value = "GOFA đang tắt"
    }

    fun markConnected(connected: Boolean, detail: String? = null) {
        _isConnected.value = connected
        _statusText.value = detail ?: if (connected) {
            "Đã kết nối GOFA BLE"
        } else {
            "Mất kết nối GOFA BLE"
        }
        VietmapStateRepository.updateConnection(connected)
    }

    /**
     * Single entry point for decoded GOFA BLE data.
     * The BLE parser should never write to VietmapStateRepository directly.
     */
    fun submitFrame(
        context: Context,
        speed: Int? = null,
        speedLimit: Int? = null,
        secondarySpeedLimit: Int? = null,
        turnCode: Int? = null,
        turnAction: String? = null,
        turnDescription: String? = null,
        distanceToTurnMeters: Int? = null,
        etaTime: String? = null,
        distanceMeters: Int? = null,
        warningType: VietmapWarningType? = null,
        alertTitle: String? = null,
        alertDescription: String? = null,
        roadName: String? = null,
        clearAlert: Boolean = false
    ) {
        HudSourceManager.init(context)
        if (!HudSourceManager.isGofa(context)) return

        _isConnected.value = true
        _statusText.value = "Đang nhận dữ liệu GOFA BLE"

        VietmapStateRepository.mergeUpdate(
            speed = speed,
            limit = speedLimit,
            secondaryLimit = secondarySpeedLimit,
            turnCode = turnCode,
            turnAction = turnAction,
            turnDescription = turnDescription,
            distanceToTurnMeters = distanceToTurnMeters,
            etaTime = etaTime,
            distanceMeters = distanceMeters,
            warningType = warningType,
            alertTitle = alertTitle,
            alertDescription = alertDescription,
            roadName = roadName,
            clearAlert = clearAlert,
            source = "GOFA_BLE"
        )
    }

    fun openGofa(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
