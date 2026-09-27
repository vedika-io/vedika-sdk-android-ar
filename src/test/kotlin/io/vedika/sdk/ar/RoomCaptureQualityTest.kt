package io.vedika.sdk.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bare-JVM unit tests for [RoomCaptureQuality] — no ARCore/Android import
 * anywhere in the class under test, so these need no device or emulator.
 */
class RoomCaptureQualityTest {

    // ── pointCloudDensityPerM2 ───────────────────────────────────────────

    @Test fun densityIsCountOverArea() {
        assertEquals(400.0, RoomCaptureQuality.pointCloudDensityPerM2(4800, 12.0)!!, 1e-9)
    }

    @Test fun densityIsNullWithoutAnArea() {
        assertNull(RoomCaptureQuality.pointCloudDensityPerM2(4800, null))
        assertNull(RoomCaptureQuality.pointCloudDensityPerM2(4800, 0.0))
        assertNull(RoomCaptureQuality.pointCloudDensityPerM2(4800, -3.0))
    }

    @Test fun densityIsNullWithoutAnyPoints() {
        assertNull(RoomCaptureQuality.pointCloudDensityPerM2(0, 12.0))
        assertNull(RoomCaptureQuality.pointCloudDensityPerM2(-1, 12.0))
    }

    // ── coveragePercent ──────────────────────────────────────────────────

    private val square2x2 = listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0)

    @Test fun coverageIsNullForADegenerateOutline() {
        assertNull(RoomCaptureQuality.coveragePercent(emptyList(), listOf(1.0 to 1.0)))
        assertNull(RoomCaptureQuality.coveragePercent(listOf(0.0 to 0.0, 1.0 to 1.0), listOf(1.0 to 1.0)))
        assertNull(RoomCaptureQuality.coveragePercent(listOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0), listOf(1.0 to 1.0)))
    }

    @Test fun coverageIsNullWithoutAnyPoints() {
        assertNull(RoomCaptureQuality.coveragePercent(square2x2, emptyList()))
    }

    @Test fun coverageIsHundredWhenEveryCellHasAPoint() {
        // 2x2m outline, 0.5m cells -> 4x4 = 16 cells; one point per cell center.
        val points = mutableListOf<Pair<Double, Double>>()
        for (r in 0 until 4) for (c in 0 until 4) points.add((c + 0.5) * 0.5 to (r + 0.5) * 0.5)
        assertEquals(100.0, RoomCaptureQuality.coveragePercent(square2x2, points)!!, 1e-9)
    }

    @Test fun coverageIsHalfWhenOnlyHalfTheCellsHaveAPoint() {
        // Only the left half (columns 0-1 of 4) gets a point.
        val points = mutableListOf<Pair<Double, Double>>()
        for (r in 0 until 4) for (c in 0 until 2) points.add((c + 0.5) * 0.5 to (r + 0.5) * 0.5)
        assertEquals(50.0, RoomCaptureQuality.coveragePercent(square2x2, points)!!, 1e-9)
    }

    @Test fun pointsOutsideTheOutlineDoNotCount() {
        // All points fall well outside the 2x2 outline -> no inside cell is hit.
        val points = listOf(10.0 to 10.0, -5.0 to -5.0)
        assertNull(RoomCaptureQuality.coveragePercent(square2x2, points))
    }

    @Test fun duplicatePointsInTheSameCellCountOnce() {
        val points = List(50) { 0.25 to 0.25 }
        val pct = RoomCaptureQuality.coveragePercent(square2x2, points)!!
        // 1 of 16 cells hit.
        assertEquals(RoomCaptureGeometry.roundHalfUp(100.0 / 16.0, 1), pct, 1e-9)
    }
}
