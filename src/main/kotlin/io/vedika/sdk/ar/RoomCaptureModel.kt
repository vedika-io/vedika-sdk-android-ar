package io.vedika.sdk.ar

import io.vedika.sdk.VastuRoomCapture
import kotlin.math.sqrt

/**
 * Native room capture for ARCore (and, by the same shape, ARKit): tap/closeAt/
 * undo/startRoom/addNorthSample/toSession/build. A line-for-line Kotlin port
 * of `createCaptureModel` in `web/vedika-public/js/vastu/room-capture-webxr.js`
 * — see that file's header for what this pure model does and does not claim.
 * `RoomCaptureModelTest` runs it against the same shared fixtures and the
 * same scenarios the JS test suite pins, so a native capture and a WebXR
 * capture of the same walk produce the same `vedika.roomCapture/1`.
 *
 * This class touches no Android or ARCore type — [VastuArCoreCaptureView]
 * feeds it ARCore hit-test poses and camera-forward vectors as plain [Vec3]
 * values, and (see [recordFeaturePoints]) the sparse feature-point cloud as
 * plain ids and [Vec3] positions. That keeps it testable on a bare JVM,
 * exactly like its JS sibling runs under plain Node.
 *
 * North is set by the operator, never inferred: they face a direction they
 * already know is north and hold "This way is north" for [MIN_NORTH_MS] while
 * at least [MIN_NORTH_SAMPLES] camera-forward samples arrive. The result's
 * `frame.north.referenceFrame` is always `"manual"` — this model has no
 * compass and makes no true-north claim of its own.
 */
class RoomCaptureModel(
    private val captureId: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** A tap this close to a loop's first corner closes the loop. */
        const val CLOSE_SNAP_M = 0.3
        const val MIN_CORNERS = 3
        /** North needs this many samples over at least this long, like the web calibrate step. */
        const val MIN_NORTH_SAMPLES = 15
        const val MIN_NORTH_MS = 3000L
        const val MAX_ROOMS = 64
        const val MAX_CORNERS = 256

        /** Room names the server's `normalize_room_type` recognises. Mirrors `ROOM_LABELS` in room-capture-webxr.js. */
        val ROOM_LABELS: List<String> = listOf(
            "kitchen", "master bedroom", "bedroom", "children bedroom", "guest bedroom", "pooja",
            "living room", "dining room", "toilet", "bathroom", "study", "store room", "staircase", "entrance",
        )
    }

    enum class Phase { OUTLINE, ROOMS }

    enum class TapResult { ADDED, CLOSED }

    data class RoomSummary(val label: String?, val corners: Int)

    private class Loop {
        val worldCorners: MutableList<Vec3> = mutableListOf()
        var closingTap: Vec3? = null
    }

    private class Room(
        val id: String,
        val label: String?,
        val labelSource: String,
    ) {
        var closingTap: Vec3? = null
        val worldCorners: MutableList<Vec3> = mutableListOf()
        val openings: MutableList<RoomCaptureOpeningInput> = mutableListOf()
    }

    private val startedAt = now()
    private var phase = Phase.OUTLINE
    private val outline = Loop()
    private val rooms = mutableListOf<Room>()
    private var current: Room? = null
    private val north = mutableListOf<Pair<Vec3, Long>>()
    private var northStart: Long? = null

    // ── ARCore feature-point accumulation (native-only; see RoomCaptureQuality) ──
    private val distinctPointIds = mutableSetOf<Int>()
    private val featurePlanPoints = mutableListOf<Pair<Double, Double>>()

    val currentPhase: Phase get() = phase
    val tracing: Boolean get() = phase == Phase.OUTLINE || current != null
    val outlineCorners: List<Vec3> get() = outline.worldCorners.toList()
    val roomSummaries: List<RoomSummary> get() = rooms.map { RoomSummary(it.label, it.worldCorners.size) }
    val currentRoom: RoomSummary? get() = current?.let { RoomSummary(it.label, it.worldCorners.size) }
    val northSamples: Int get() = north.size
    val northReady: Boolean
        get() = north.size >= MIN_NORTH_SAMPLES && (north.lastOrNull()?.second ?: 0L) - (northStart ?: 0L) >= MIN_NORTH_MS
    /** Distinct ARCore feature points accumulated so far — see [recordFeaturePoints]. */
    val distinctFeaturePointCount: Int get() = distinctPointIds.size

    private fun loop(): Loop? = if (phase == Phase.OUTLINE) outline else null
    private fun activeCorners(): MutableList<Vec3>? = if (phase == Phase.OUTLINE) outline.worldCorners else current?.worldCorners
    private fun firstCorner(): Vec3? = if (phase == Phase.OUTLINE) outline.worldCorners.firstOrNull() else current?.worldCorners?.firstOrNull()

    private fun dist(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun closeLoop(tap: Vec3) {
        val corners = activeCorners() ?: throw IllegalStateException("Add a room before tapping corners")
        if (corners.size < MIN_CORNERS) throw IllegalStateException("Tap at least $MIN_CORNERS corners before closing")
        if (phase == Phase.OUTLINE) {
            outline.closingTap = tap
            phase = Phase.ROOMS
        } else {
            val room = current ?: throw IllegalStateException("Nothing is being traced")
            room.closingTap = tap
            rooms.add(room)
            current = null
        }
    }

    /** Returns [TapResult.ADDED] or [TapResult.CLOSED]. A tap near the loop's first corner closes it. */
    fun tap(point: Vec3): TapResult {
        if (phase == Phase.ROOMS && current == null) throw IllegalStateException("Add a room before tapping corners")
        val corners = activeCorners()!!
        val first = firstCorner()
        if (corners.size >= MIN_CORNERS && first != null && dist(point, first) <= CLOSE_SNAP_M) {
            closeLoop(point)
            return TapResult.CLOSED
        }
        if (corners.size >= MAX_CORNERS) throw IllegalStateException("A loop can have at most $MAX_CORNERS corners")
        corners.add(point)
        return TapResult.ADDED
    }

    /** Close the current loop with this tap even if it is not near the first corner. */
    fun closeAt(point: Vec3) {
        if (phase == Phase.ROOMS && current == null) throw IllegalStateException("Nothing is being traced")
        closeLoop(point)
    }

    fun undo(): Boolean {
        val corners = activeCorners()
        if (corners != null && corners.isNotEmpty()) {
            corners.removeAt(corners.size - 1)
            return true
        }
        if (phase == Phase.ROOMS && current == null && rooms.isNotEmpty()) {
            val restored = rooms.removeAt(rooms.size - 1)
            restored.closingTap = null
            current = restored
            return true
        }
        return false
    }

    fun startRoom(label: String?) {
        if (phase != Phase.ROOMS) throw IllegalStateException("Close the outline first")
        if (current != null) throw IllegalStateException("Finish the room you are tracing first")
        if (rooms.size >= MAX_ROOMS) throw IllegalStateException("At most $MAX_ROOMS rooms")
        val clean = label?.trim()?.takeIf { it.isNotEmpty() }
        current = Room(
            id = "room-${rooms.size + 1}",
            label = clean,
            labelSource = if (clean != null) "user" else "none",
        )
    }

    /** One "facing north" sample: the camera's forward vector at time [t]. */
    fun addNorthSample(forward: Vec3, t: Long = now()) {
        if (northStart == null) northStart = t
        north.add(forward to t)
    }

    fun resetNorth() {
        north.clear()
        northStart = null
    }

    /**
     * Accumulates ARCore's sparse feature-point cloud, de-duplicated by
     * ARCore's own persistent point [ids] (unique across frames within a
     * session; see `Frame.acquirePointCloud()`/`PointCloud.getIds()`).
     * [points] are world-space, same convention as every corner tap. Feeds
     * [RoomCaptureQuality] at [toSession] time — see that object's doc for
     * why this is always basis `"feature-points"`, independent of the Depth
     * API. A capture built without ever calling this (every fixture, and
     * every non-native platform) keeps `pointCloudDensity: null`, unchanged
     * from before this method existed.
     */
    fun recordFeaturePoints(ids: IntArray, points: List<Vec3>) {
        require(ids.size == points.size) { "ids and points must be the same length" }
        for (i in ids.indices) {
            if (distinctPointIds.add(ids[i])) {
                featurePlanPoints.add(RoomCaptureGeometry.floorToPlan(points[i]))
            }
        }
    }

    /** The [RoomCaptureSession] [RoomCaptureGeometry.buildRoomCapture] takes. */
    fun toSession(device: RoomCaptureDeviceInput? = null, endedAt: Long = now()): RoomCaptureSession {
        if (phase == Phase.OUTLINE) throw IllegalStateException("Trace and close the outline first")
        current?.let { throw IllegalStateException("Finish the room \"${it.label ?: "unnamed"}\" first") }
        if (!northReady) throw IllegalStateException("Set north first")
        val outlinePlan = outline.worldCorners.map { it.x to -it.z }
        val area = kotlin.math.abs(RoomCaptureGeometry.signedArea(outlinePlan))
        val density = RoomCaptureQuality.pointCloudDensityPerM2(distinctPointIds.size, area)
        val coverage = RoomCaptureQuality.coveragePercent(outlinePlan, featurePlanPoints)
        return RoomCaptureSession(
            captureId = captureId,
            capturedAtEpoch = startedAt / 1000,
            device = device ?: RoomCaptureDeviceInput(platform = "android", method = "arcore-hit", depth = "none"),
            headingFrame = "true",
            north = RoomCaptureNorthInput(
                referenceFrame = "manual",
                headingSource = "viewer-facing-north",
                declinationDeg = null,
                declinationProvenance = "manual",
            ),
            // Facing north means a heading of 0 along the camera's forward vector.
            headingSamples = north.map { (forward, _) -> RoomCaptureHeadingSample(headingDeg = 0.0, forward = forward) },
            outline = RoomCaptureOutlineInput(
                source = "traced",
                worldCorners = outline.worldCorners.toList(),
                closingTap = outline.closingTap,
            ),
            rooms = rooms.map { room ->
                RoomCaptureRoomInput(
                    id = room.id,
                    label = room.label,
                    labelSource = room.labelSource,
                    floorIndex = 0,
                    heightM = null,
                    worldCorners = room.worldCorners.toList(),
                    closingTap = room.closingTap,
                    openings = room.openings.toList(),
                )
            },
            quality = RoomCaptureQualityInput(
                pointCloudDensity = density,
                pointCloudDensityBasis = if (density != null) "feature-points" else "none",
                coveragePercent = coverage,
                scanDurationSec = maxOf(0L, Math.round((endedAt - startedAt) / 1000.0)).toDouble(),
                scannedAreaM2 = RoomCaptureGeometry.roundHalfUp(area, 2),
                expectedRoomCount = rooms.size,
                gpsConfidence = null,
            ),
        )
    }

    fun build(device: RoomCaptureDeviceInput? = null, endedAt: Long = now()): VastuRoomCapture =
        RoomCaptureGeometry.buildRoomCapture(toSession(device, endedAt))
}
