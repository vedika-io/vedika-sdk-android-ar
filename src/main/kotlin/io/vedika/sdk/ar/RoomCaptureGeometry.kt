package io.vedika.sdk.ar

import io.vedika.sdk.VastuArPlanToWorld
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuRoomCaptureDevice
import io.vedika.sdk.VastuRoomCaptureFrame
import io.vedika.sdk.VastuRoomCaptureFrameNorth
import io.vedika.sdk.VastuRoomCaptureOutline
import io.vedika.sdk.VastuRoomCaptureQuality
import io.vedika.sdk.VastuRoomCaptureRoomsItem
import io.vedika.sdk.VastuRoomCaptureRoomsItemOpeningsItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AR room capture -> [VastuRoomCapture], the body `ar/room-capture` takes.
 *
 * A line-for-line Kotlin port of `web/vedika-public/js/vastu/room-capture-geometry.js`
 * (plus the circular-bearing helpers it borrows from `heading-provider.js`).
 * `RoomCaptureGeometryTest` runs the same shared fixtures
 * (`sdks/fixtures/vastu-room-capture`, the `.json` files there) through this port and asserts
 * the same expected capture, within 1e-6 — the two implementations must never
 * silently drift apart. Do not change rounding, ordering, or the sign
 * convention here without re-checking those fixtures.
 *
 * Input is a Y-up AR session (WebXR, ARCore and ARKit all use one): floor
 * corners in world metres and heading samples, each a compass heading paired
 * with the camera's forward vector at that moment. Output is north-up plan
 * geometry: metres, +X east, +Y true north, starting at 0, 0.
 *
 *  1. Floor point to plan point: (u, v) = (x, -z). The wrong sign swaps a
 *     wall silently, so the shared fixtures pin a door's position.
 *  2. theta, the true bearing of plan +v: the stationary median of
 *     trueHeading - atan2(f.x, -f.z) over the samples.
 *  3. Rotate clockwise by theta, move the traced outline's lower-left to 0, 0,
 *     snap to 1 cm, drop corners within 2 cm of a straight run, and start
 *     each ring counter-clockwise at its lowest corner.
 */
object RoomCaptureGeometry {

    const val ROOM_CAPTURE_SCHEMA = "vedika.roomCapture/1"
    const val ROOM_CAPTURE_AXES = "+X east,+Y true north"
    const val SPIKE_THRESHOLD_DEG = 20.0
    const val SPREAD_FOR_ZERO_CONFIDENCE_DEG = 20.0
    const val COLLINEAR_TOLERANCE_M = 0.02
    const val CLOSED_LOOP_TOLERANCE_M = 0.05

    private const val DEG = PI / 180.0

    // ── Circular bearing math (ported from heading-provider.js) ────────────

    fun normalizeBearing(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        return ((deg % 360.0) + 360.0) % 360.0
    }

    /** Shortest signed difference `b - a` in the range `(-180, 180]`. */
    fun circularDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    data class CircularMean(val meanDeg: Double, val resultantLength: Double, val circularVariance: Double)

    fun circularMeanDeg(samples: List<Double>): CircularMean? {
        if (samples.isEmpty()) return null
        var sx = 0.0
        var sy = 0.0
        for (s in samples) {
            val r = s * DEG
            sx += cos(r)
            sy += sin(r)
        }
        val n = samples.size
        sx /= n
        sy /= n
        val meanDeg = normalizeBearing(atan2(sy, sx) / DEG)
        val resultantLength = sqrt(sx * sx + sy * sy)
        return CircularMean(meanDeg, resultantLength, 1.0 - resultantLength)
    }

    /** The sample minimizing the summed circular distance to all other samples. */
    fun circularMedianDeg(samples: List<Double>): Double? {
        if (samples.isEmpty()) return null
        var best = samples[0]
        var bestCost = Double.POSITIVE_INFINITY
        for (cand in samples) {
            var cost = 0.0
            for (s in samples) cost += abs(circularDiff(cand, s))
            if (cost < bestCost) {
                bestCost = cost
                best = cand
            }
        }
        return normalizeBearing(best)
    }

    data class StationarySummary(
        val headingDeg: Double?,
        val sampleCount: Int,
        val keptCount: Int,
        val rejectedCount: Int,
        val circularVariance: Double?,
        val insufficientSamples: Boolean,
    )

    fun summarizeStationarySamples(
        samples: List<Double>,
        spikeThresholdDeg: Double = 20.0,
        minSamples: Int = 3,
    ): StationarySummary? {
        if (samples.isEmpty()) return null
        val roughMedian = circularMedianDeg(samples)!!
        val kept = samples.filter { abs(circularDiff(roughMedian, it)) <= spikeThresholdDeg }
        val useSet = if (kept.size >= max(3, ceil(samples.size * 0.4).toInt())) kept else samples
        val median = circularMedianDeg(useSet)
        val insufficientSamples = samples.size < minSamples
        val stats = if (insufficientSamples) null else circularMeanDeg(useSet)
        return StationarySummary(
            headingDeg = median,
            sampleCount = samples.size,
            keptCount = useSet.size,
            rejectedCount = samples.size - useSet.size,
            circularVariance = stats?.circularVariance,
            insufficientSamples = insufficientSamples,
        )
    }

    // ── Rounding ─────────────────────────────────────────────────────────

    /** Round half toward +infinity at [places] decimals; -0 becomes 0. */
    fun roundHalfUp(value: Double, places: Int): Double {
        val f = Math.pow(10.0, places.toDouble())
        return floor(value * f + 0.5) / f + 0.0
    }

    fun snapCm(value: Double): Double = roundHalfUp(value, 2)

    // ── Floor <-> plan mapping ───────────────────────────────────────────

    /** World (x, y, z) -> plan (u, v). */
    fun floorToPlan(point: Vec3): Pair<Double, Double> = point.x to -point.z

    /** Clockwise bearing of a camera forward vector within the plan, in degrees. */
    fun forwardBearingDeg(forward: Vec3): Double = atan2(forward.x, -forward.z) / DEG

    data class NorthRotation(
        val thetaDeg: Double,
        val yawSamples: Int,
        val yawSpreadDeg: Double,
        val compassConfidence: Double,
        val trueFrame: Boolean,
    )

    /**
     * theta and its spread from heading samples. [headingFrame] is `"true"`
     * when headings already include declination.
     */
    fun northRotation(
        samples: List<RoomCaptureHeadingSample>,
        headingFrame: String,
        declinationDeg: Double?,
    ): NorthRotation {
        require(samples.isNotEmpty()) { "Take heading samples before building a capture" }
        val offsets = samples.map { (headingDeg, forward) ->
            val heading = if (headingFrame == "magnetic" && declinationDeg != null) headingDeg + declinationDeg else headingDeg
            normalizeBearing(heading - forwardBearingDeg(forward))
        }
        val summary = summarizeStationarySamples(offsets, SPIKE_THRESHOLD_DEG)!!
        val rough = circularMedianDeg(offsets)!!
        val kept = offsets.filter { abs(circularDiff(rough, it)) <= SPIKE_THRESHOLD_DEG }
        val used = if (kept.size >= max(3, ceil(offsets.size * 0.4).toInt())) kept else offsets
        var sx = 0.0
        var sy = 0.0
        for (d in used) {
            sx += cos(d * DEG)
            sy += sin(d * DEG)
        }
        val mx = sx / used.size
        val my = sy / used.size
        val r = min(1.0, sqrt(mx * mx + my * my))
        val spread = if (r < 1.0) sqrt(-2.0 * ln(r)) / DEG else 0.0
        val confidence = max(0.0, min(1.0, 1.0 - spread / SPREAD_FOR_ZERO_CONFIDENCE_DEG))
        return NorthRotation(
            thetaDeg = summary.headingDeg!!,
            yawSamples = offsets.size,
            yawSpreadDeg = roundHalfUp(spread, 2),
            compassConfidence = roundHalfUp(confidence, 3),
            trueFrame = headingFrame == "true" || declinationDeg != null,
        )
    }

    /** Plan (u, v) -> north-up (X, Y) for a plan whose +v has true bearing theta. */
    fun rotateToNorth(point: Pair<Double, Double>, thetaDeg: Double): Pair<Double, Double> {
        val (u, v) = point
        val t = thetaDeg * DEG
        return (u * cos(t) + v * sin(t)) to (-u * sin(t) + v * cos(t))
    }

    fun signedArea(ring: List<Pair<Double, Double>>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val (x1, y1) = ring[i]
            val (x2, y2) = ring[(i + 1) % ring.size]
            sum += x1 * y2 - x2 * y1
        }
        return sum / 2.0
    }

    /** Drop repeats and corners that sit within 2 cm of a straight run. */
    fun simplifyRing(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val pts = mutableListOf<Pair<Double, Double>>()
        for (p in points) {
            val last = pts.lastOrNull()
            if (last == null || last.first != p.first || last.second != p.second) pts.add(p)
        }
        while (pts.size > 1 && pts.first() == pts.last()) pts.removeAt(pts.size - 1)
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            val n = pts.size
            for (i in 0 until n) {
                val a = pts[(i - 1 + n) % n]
                val b = pts[i]
                val c = pts[(i + 1) % n]
                val ab = (b.first - a.first) to (b.second - a.second)
                val ac = (c.first - a.first) to (c.second - a.second)
                val span = sqrt(ac.first * ac.first + ac.second * ac.second)
                if (span == 0.0) continue
                val off = abs(ab.first * ac.second - ab.second * ac.first) / span
                val along = (ab.first * ac.first + ab.second * ac.second) / (span * span)
                if (off <= COLLINEAR_TOLERANCE_M && along > 0.0 && along < 1.0) {
                    pts.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    /** Counter-clockwise, starting at the lowest-Y (then lowest-X) corner. */
    fun canonicalRing(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val ring = if (signedArea(points) < 0.0) points.reversed() else points
        var start = 0
        for (i in 1 until ring.size) {
            if (ring[i].second < ring[start].second ||
                (ring[i].second == ring[start].second && ring[i].first < ring[start].first)
            ) {
                start = i
            }
        }
        return ring.subList(start, ring.size) + ring.subList(0, start)
    }

    private fun finish(points: List<Pair<Double, Double>>, shift: Pair<Double, Double>): List<Pair<Double, Double>> =
        canonicalRing(simplifyRing(points.map { (x, y) -> snapCm(x - shift.first) to snapCm(y - shift.second) }))

    private fun Pair<Double, Double>.toList(): List<Double> = listOf(first, second)

    /**
     * Build the capture body from an AR session. See the shared fixtures'
     * `session` objects for the input shape.
     */
    fun buildRoomCapture(session: RoomCaptureSession): VastuRoomCapture {
        val northInput = session.north
        val quality = session.quality
        val rotation = northRotation(session.headingSamples, session.headingFrame, northInput.declinationDeg)
        val toPlan: (Vec3) -> Pair<Double, Double> = { p -> rotateToNorth(floorToPlan(p), rotation.thetaDeg) }

        require(session.outline.source == "traced" && session.outline.worldCorners.isNotEmpty()) {
            "Trace the plot or house outline first; a room is zoned by where it sits in it"
        }
        val outlineWorldCorners = session.outline.worldCorners
        val closingTap = requireNotNull(session.outline.closingTap) {
            "Trace the plot or house outline first; a room is zoned by where it sits in it"
        }

        val roomsPlan = session.rooms.map { room -> room.worldCorners.map(toPlan) }
        val outlinePlan = outlineWorldCorners.map(toPlan)
        val loopA = outlineWorldCorners[0]
        val loopB = closingTap
        val shift = outlinePlan.minOf { it.first } to outlinePlan.minOf { it.second }
        val a = toPlan(loopA)
        val b = toPlan(loopB)
        val dx = a.first - b.first
        val dy = a.second - b.second
        val gap = roundHalfUp(sqrt(dx * dx + dy * dy), 2)

        val rooms = session.rooms.mapIndexed { index, room ->
            val ring = finish(roomsPlan[index], shift)
            VastuRoomCaptureRoomsItem(
                id = room.id,
                label = room.label,
                labelSource = room.labelSource,
                polygon = ring.map { it.toList() },
                areaM2 = roundHalfUp(abs(signedArea(ring)), 2),
                floorIndex = room.floorIndex,
                heightM = room.heightM,
                openings = room.openings.map { o ->
                    val (cx, cy) = toPlan(o.centerWorld)
                    VastuRoomCaptureRoomsItemOpeningsItem(
                        kind = o.kind,
                        centerXY = listOf(snapCm(cx - shift.first), snapCm(cy - shift.second)),
                        widthM = o.widthM,
                        heightM = o.heightM,
                        confidence = o.confidence,
                    )
                },
            )
        }

        val t = rotation.thetaDeg * DEG
        val c = cos(t)
        val s = sin(t)
        val u0 = c * shift.first - s * shift.second
        val v0 = s * shift.first + c * shift.second

        return VastuRoomCapture(
            schema = ROOM_CAPTURE_SCHEMA,
            captureId = session.captureId,
            capturedAtEpoch = session.capturedAtEpoch,
            device = VastuRoomCaptureDevice(
                platform = session.device.platform,
                method = session.device.method,
                depth = session.device.depth,
            ),
            frame = VastuRoomCaptureFrame(
                units = "metres",
                axes = ROOM_CAPTURE_AXES,
                north = VastuRoomCaptureFrameNorth(
                    referenceFrame = if (rotation.trueFrame) northInput.referenceFrame else "magnetic",
                    headingSource = northInput.headingSource,
                    declinationDeg = northInput.declinationDeg,
                    declinationProvenance = northInput.declinationProvenance,
                    yawSamples = rotation.yawSamples,
                    yawSpreadDeg = rotation.yawSpreadDeg,
                    compassConfidence = rotation.compassConfidence,
                ),
                planToWorld = VastuArPlanToWorld(
                    units = "metres",
                    origin = listOf(roundHalfUp(u0, 3), roundHalfUp(loopA.y, 3), roundHalfUp(-v0, 3)),
                    xAxis = listOf(roundHalfUp(c, 9), 0.0, roundHalfUp(-s, 9)),
                    yAxis = listOf(roundHalfUp(-s, 9), 0.0, roundHalfUp(-c, 9)),
                ),
            ),
            outline = VastuRoomCaptureOutline(
                polygon = finish(outlinePlan, shift).map { it.toList() },
                source = session.outline.source,
            ),
            rooms = rooms,
            quality = VastuRoomCaptureQuality(
                polygonClosure = gap <= CLOSED_LOOP_TOLERANCE_M,
                closureGapM = gap,
                pointCloudDensity = quality.pointCloudDensity,
                pointCloudDensityBasis = quality.pointCloudDensityBasis,
                coveragePercent = quality.coveragePercent,
                scanDurationSec = quality.scanDurationSec,
                scannedAreaM2 = quality.scannedAreaM2,
                roomCount = rooms.size,
                expectedRoomCount = quality.expectedRoomCount,
                roomsTagged = rooms.count { it.label != null },
                gpsConfidence = quality.gpsConfidence,
            ),
            attestation = "caller-reported",
        )
    }
}
