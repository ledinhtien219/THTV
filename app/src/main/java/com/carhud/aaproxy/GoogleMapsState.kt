package com.carhud.aaproxy

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GoogleMapsNavData(
    val isConnected: Boolean = false,
    val isNavigating: Boolean = false,
    val maneuverText: String = "",       // e.g. "Đi về hướng Đông Bắc"
    val nextStepText: String = "",       // e.g. "Sau đó ↱"
    val distanceToTurn: String = "",     // e.g. "250 m"
    val roadName: String = "",           // e.g. "Đường Lý Nam Đế"
    val eta: String = "",                // e.g. "12:19"
    val remainingDistance: String = "",  // e.g. "78 km"
    val remainingTime: String = "",      // e.g. "1 giờ 36 phút"
    val trafficDelay: String = "",       // e.g. "Chậm hơn 18 phút"
    val fullSummary: String = "",        // e.g. "1 giờ 36 phút • 78 km • 12:19"
    val maneuverIcon: Bitmap? = null,
    val lastUpdateTime: Long = 0L
)

object GoogleMapsStateRepository {
    private val _navState = MutableStateFlow(GoogleMapsNavData())
    val navState: StateFlow<GoogleMapsNavData> = _navState.asStateFlow()

    fun updateNavData(data: GoogleMapsNavData) {
        _navState.value = data
    }

    fun clearNavigation() {
        _navState.value = _navState.value.copy(
            isNavigating = false,
            maneuverText = "",
            nextStepText = "",
            distanceToTurn = "",
            roadName = "",
            eta = "",
            remainingDistance = "",
            remainingTime = "",
            trafficDelay = "",
            fullSummary = "",
            maneuverIcon = null,
            lastUpdateTime = System.currentTimeMillis()
        )
    }
}