package io.vedika.sdk.ar

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure point-cloud density / coverage math for a native ARCore capture.
 *
 * `Frame.acquirePointCloud()` returns ARCore's sparse, ID-tracked feature-point
 * cloud. That cloud exists regardless of `Config.DepthMode` — it is NOT the
 * Depth API's dense depth image — so a density computed from it is always
 * `pointCloudDensityBasis: "feature-points"`, never `"lidar-depth"`; the
 * server's `ar/scan-quality` validator rejects `pointCloudDensityBasis: "none"`
 * paired with a non-null reading, so callers must set the basis together with
 * the value (see [VastuArCoreCaptureView]'s README section on what a capture
 * claims). Kept free of every Android/ARCore import, same house style as
 * [RoomCaptureGeometry], so it runs as an ordinary bare-JVM unit test.
 */
object RoomCaptureQuality {

    /** Grid cell size (metres) for the coverage estimate. */
    const val COVERAGE_CELL_M = 0.5

    /**
     * Points-per-m^2 of an accumulated, de-duplicated (by ARCore point id)
     * feature-point set over the outline's plan area. `null` when either
     * input is missing or non-positive — never a fabricated reading, matching
     * every other "not reported" field in `vedika.roomCapture/1`.
     */
    fun pointCloudDensityPerM2(distinctPointCount: Int, scannedAreaM2: Double?): Double? {
        if (scannedAreaM2 == null || scannedAreaM2 <= 0.0) return null
        if (distinctPointCount <= 0) return null
        return distinctPointCount / scannedAreaM2
    }

    /**
     * Percent of the outline polygon's plan-space area (gridded into
     * [COVERAGE_CELL_M] cells, counting only cells whose center falls inside
     * the polygon) that contains at least one accumulated plan-space point.
     * `null` for a degenerate outline (fewer than 3 corners, or a zero-area
     * bounding box) or an empty point set — never a fabricated 0.
     */
    fun coveragePercent(outlinePlan: List<Pair<Double, Double>>, planPoints: List<Pair<Double, Double>>): Double? {
        if (outlinePlan.size < 3 || planPoints.isEmpty()) return null
        val minX = outlinePlan.minOf { it.first }
        val maxX = outlinePlan.maxOf { it.first }
        val minY = outlinePlan.minOf { it.second }
        val maxY = outlinePlan.maxOf { it.second }
        if (maxX <= minX || maxY <= minY) return null

        val cols = ceil((maxX - minX) / COVERAGE_CELL_M).toInt().coerceAtLeast(1)
        val rows = ceil((maxY - minY) / COVERAGE_CELL_M).toInt().coerceAtLeast(1)

        var totalInside = 0
        val insideCell = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = minX + (c + 0.5) * COVERAGE_CELL_M
                val cy = minY + (r + 0.5) * COVERAGE_CELL_M
                if (pointInPolygon(cx, cy, outlinePlan)) {
                    insideCell[r][c] = true
                    totalInside++
                }
            }
        }
        if (totalInside == 0) return null

        val hit = HashSet<Long>()
        for ((px, py) in planPoints) {
            if (!pointInPolygon(px, py, outlinePlan)) continue
            val c = floor((px - minX) / COVERAGE_CELL_M).toInt()
            val r = floor((py - minY) / COVERAGE_CELL_M).toInt()
            if (c < 0 || c >= cols || r < 0 || r >= rows) continue
            if (!insideCell[r][c]) continue
            hit.add(r.toLong() * cols + c)
        }
        val pct = 100.0 * hit.size / totalInside
        return RoomCaptureGeometry.roundHalfUp(pct.coerceIn(0.0, 100.0), 1)
    }

    /** Even-odd point-in-polygon test on a simple ring. */
    private fun pointInPolygon(x: Double, y: Double, ring: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            if ((yi > y) != (yj > y)) {
                val xIntersect = xi + (y - yi) / (yj - yi) * (xj - xi)
                if (x < xIntersect) inside = !inside
            }
            j = i
        }
        return inside
    }
}
