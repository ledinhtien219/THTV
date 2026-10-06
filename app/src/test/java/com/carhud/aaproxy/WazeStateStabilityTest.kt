package com.carhud.aaproxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WazeStateStabilityTest {

    @Before
    fun setUp() {
        VietmapStateRepository.reset()
    }

    @After
    fun tearDown() {
        VietmapStateRepository.reset()
    }

    @Test
    fun zeroDistanceDoesNotImmediatelyClearANewAlert() {
        VietmapStateRepository.mergeUpdate(
            warningType = VietmapWarningType.SPEED_CAMERA,
            alertTitle = "Camera tốc độ",
            distanceMeters = 0,
            source = "WAZE_HLP"
        )

        val state = VietmapStateRepository.alertState.value
        assertEquals(VietmapWarningType.SPEED_CAMERA, state.warningType)
        assertEquals("WAZE_HLP", state.alertSource)
    }

    @Test
    fun zeroDistanceClearsSameAlertAfterPositiveDistanceWasObserved() {
        VietmapStateRepository.mergeUpdate(
            warningType = VietmapWarningType.SPEED_CAMERA,
            alertTitle = "Camera tốc độ",
            distanceMeters = 180,
            source = "WAZE_HLP"
        )
        VietmapStateRepository.mergeUpdate(
            warningType = VietmapWarningType.SPEED_CAMERA,
            alertTitle = "Camera tốc độ",
            distanceMeters = 0,
            source = "WAZE_HLP"
        )

        val state = VietmapStateRepository.alertState.value
        assertEquals(VietmapWarningType.NONE, state.warningType)
        assertEquals("UNKNOWN", state.alertSource)
    }

    @Test
    fun clearingHlpDoesNotEraseNotificationOwnedAlert() {
        VietmapStateRepository.mergeUpdate(
            warningType = VietmapWarningType.POLICE,
            alertTitle = "Cảnh sát giao thông",
            distanceMeters = 400,
            source = "WAZE_NOTIFICATION"
        )
        VietmapStateRepository.mergeUpdate(
            upcomingAlerts = listOf(
                WazeAlertItem(
                    code = 2,
                    warningType = VietmapWarningType.SPEED_CAMERA,
                    title = "Camera tốc độ",
                    distanceMeters = 800
                )
            ),
            source = "WAZE_HLP"
        )

        VietmapStateRepository.clearHlpAlerts()

        val state = VietmapStateRepository.alertState.value
        assertEquals(VietmapWarningType.POLICE, state.warningType)
        assertEquals("WAZE_NOTIFICATION", state.alertSource)
        assertTrue(state.upcomingAlerts.isEmpty())
    }

    @Test
    fun duplicateUpcomingAlertsCollapseToNearestCopy() {
        val result = WazeAlertPolicy.dedupeAlerts(
            listOf(
                WazeAlertItem(
                    code = 2,
                    warningType = VietmapWarningType.SPEED_CAMERA,
                    title = "Camera tốc độ",
                    distanceMeters = 350,
                    roadName = "QL2"
                ),
                WazeAlertItem(
                    code = 2,
                    warningType = VietmapWarningType.SPEED_CAMERA,
                    title = "Camera tốc độ",
                    distanceMeters = 330,
                    roadName = "QL2"
                ),
                WazeAlertItem(
                    code = 2,
                    warningType = VietmapWarningType.SPEED_CAMERA,
                    title = "Camera tốc độ",
                    distanceMeters = 900,
                    roadName = "QL2"
                ),
                WazeAlertItem(
                    code = 5,
                    warningType = VietmapWarningType.ACCIDENT,
                    title = "Tai nạn",
                    distanceMeters = 500,
                    roadName = "QL2"
                )
            )
        )

        assertEquals(3, result.size)
        assertEquals(330, result[0].distanceMeters)
        assertEquals(900, result[1].distanceMeters)
        assertEquals(VietmapWarningType.ACCIDENT, result[2].warningType)
    }
}
