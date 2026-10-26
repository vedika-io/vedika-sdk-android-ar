package io.vedika.sdk.ar

import io.vedika.sdk.VastuArCaptureMergeData
import io.vedika.sdk.VastuArCaptureMergeRequest
import io.vedika.sdk.VastuArCaptureMergeRequestCapturesItem
import io.vedika.sdk.VastuArCaptureMergeRequestFloorsItem
import io.vedika.sdk.VastuContracts
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuTypedResponse
import io.vedika.sdk.VedikaClient
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scan the floors of one building one at a time, then merge them through
 * `POST /v2/astrology/vastu/ar/capture-merge`.
 *
 * This is a port of the web reference (`prepareFloorMerge` in
 * `web/vedika-public/js/vastu/room-capture-geometry.js`) so the web guide and
 * the native module send the same request for the same input.
 *
 * How the floors are tied together: the API only accepts `links` between two
 * captures on the SAME floor, so a stair or door correspondence between two
 * floors can never be sent as an API link. Instead the shared points are
 * resolved here, on the device, into one explicit floor frame per floor
 * (`floors[].originXY`) and the request carries `links = []`. The first scan
 * is the reference floor and sits at origin 0, 0. Every other floor gets a
 * translation only: its recorded true north is never rotated, and there is no
 * scale change (`bearingDeg` stays 0).
 *
 * Everything in [FloorMerge.prepare] runs before any network call. A bad link
 * raises [FloorMergeRefusedException] and nothing is sent, so nothing is
 * billed. The error it reports is the disagreement between the picked points
 * (a correspondence residual). It is not a claim of survey or house accuracy.
 *
 * Pure JVM: no Android or ARCore type is used here.
 */

/** A point on one floor plan, metres: +X east, +Y true north (the capture's own frame). */
data class FloorPoint(val x: Double, val y: Double)

/** The same physical stair or door location, picked on the reference floor and on the next floor. */
data class SharedFloorPoint(val reference: FloorPoint, val next: FloorPoint)

enum class FloorLinkKind { STAIR, DOOR }

/** One finished room scan of one floor, with that floor's measured elevation above the building datum. */
data class FloorScan(val capture: VastuRoomCapture, val elevationM: Double)

/**
 * Ties the scan whose `captureId` is [toCaptureId] to the reference (first)
 * scan with [points]. Needs at least [FloorMerge.MIN_SHARED_POINTS] points.
 * A door link must use measured, vertically corresponding points: unrelated
 * doors cannot locate a floor.
 */
data class FloorLink(
    val toCaptureId: String,
    val kind: FloorLinkKind,
    val points: List<SharedFloorPoint>,
)

data class FloorMergeInput(
    /** The first scan is the reference floor. */
    val scans: List<FloorScan>,
    /** One link for every scan after the first. */
    val links: List<FloorLink>,
    /** Largest accepted shared-point error, metres: 0.0001 to 1. */
    val toleranceM: Double = FloorMerge.DEFAULT_TOLERANCE_M,
    /** Optional wallet cap sent as `maxChargeUsd`, an exact decimal string such as "0.50". */
    val maxChargeUsd: String? = null,
)

enum class FloorMergeRefusal {
    TOO_FEW_FLOORS,
    TOO_MANY_FLOORS,
    BAD_TOLERANCE,
    BAD_MAX_CHARGE,
    BAD_IDEMPOTENCY_KEY,
    UNSUPPORTED_CAPTURE,
    DUPLICATE_CAPTURE_ID,
    DUPLICATE_FLOOR_INDEX,
    DUPLICATE_ELEVATION,
    BAD_ELEVATION,
    TOO_MANY_ROOMS,
    MISSING_LINK,
    UNKNOWN_FLOOR_LINK,
    DUPLICATE_LINK,
    TOO_FEW_POINTS,
    NON_FINITE_POINT,
    POINT_OUTSIDE_FLOOR,
    NO_BASELINE,
    ERROR_ABOVE_TOLERANCE,
    REQUEST_TOO_LARGE,
}

/** Raised BEFORE any request when the floors or their shared points cannot be merged as given. */
class FloorMergeRefusedException(val reason: FloorMergeRefusal, message: String) : IllegalArgumentException(message)

/** Raised when the API answered but the merged building does not match what was sent. Nothing is hidden: keep the original scans. */
class FloorMergeResponseException(message: String) : IllegalStateException(message)

/** Agreement of the picked points for one floor against the reference floor, metres. */
data class FloorPairResidual(
    val fromCaptureId: String,
    val toCaptureId: String,
    val kind: FloorLinkKind,
    val controlCount: Int,
    val rmsResidualM: Double,
    val maxResidualM: Double,
)

data class FloorMergeRegistration(
    val pairResiduals: List<FloorPairResidual>,
    /** Point-weighted RMS over every link. */
    val rmsResidualM: Double,
    val maxResidualM: Double,
) {
    /** Plain-language statement of the error, safe to show to a person. */
    fun describe(): String =
        "Shared-point error: RMS ${fmt4(rmsResidualM)} m; maximum ${fmt4(maxResidualM)} m. " +
            "This is correspondence agreement, not survey accuracy. Original north and floor identities are preserved."

    private fun fmt4(v: Double) = String.format(java.util.Locale.ROOT, "%.4f", v)
}

/** A validated request plus the error it was built with. Nothing has been sent. */
data class PreparedFloorMerge(val request: VastuArCaptureMergeRequest, val registration: FloorMergeRegistration)

/** The merged building and the stated error of the floor links that produced it. */
data class FloorMergeResult(
    val response: VastuTypedResponse<VastuArCaptureMergeData>,
    val registration: FloorMergeRegistration,
) {
    val building: VastuArCaptureMergeData get() = response.data
    val rmsResidualM: Double get() = registration.rmsResidualM
    val maxResidualM: Double get() = registration.maxResidualM
}

object FloorMerge {
    const val DEFAULT_TOLERANCE_M = 0.05
    const val MIN_SHARED_POINTS = 3
    const val MAX_FLOORS = 16
    const val MIN_BASELINE_M = 0.1
    private const val MAX_ROOMS = 512
    private const val MAX_BODY_BYTES = 1024 * 1024

    private val KEY = Regex("^[\\x21-\\x7e]{1,256}$")
    private val MAX_CHARGE = Regex("^(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$")

    private fun refuse(reason: FloorMergeRefusal, message: String): Nothing =
        throw FloorMergeRefusedException(reason, message)

    /**
     * Validates [input] and builds the `ar/capture-merge` request. Makes no
     * network call. Throws [FloorMergeRefusedException] with a [FloorMergeRefusal]
     * reason for a bad link.
     */
    fun prepare(input: FloorMergeInput): PreparedFloorMerge {
        val scans = input.scans
        if (scans.size < 2) refuse(FloorMergeRefusal.TOO_FEW_FLOORS, "Scan at least 2 floors to merge; one floor needs no merge.")
        if (scans.size > MAX_FLOORS) refuse(FloorMergeRefusal.TOO_MANY_FLOORS, "Merge accepts at most $MAX_FLOORS floors.")
        if (!input.toleranceM.isFinite() || input.toleranceM < 0.0001 || input.toleranceM > 1.0) {
            refuse(FloorMergeRefusal.BAD_TOLERANCE, "toleranceM must be 0.0001 to 1 metre.")
        }
        input.maxChargeUsd?.let {
            if (!MAX_CHARGE.matches(it)) refuse(FloorMergeRefusal.BAD_MAX_CHARGE, "maxChargeUsd must be an exact non-negative decimal string such as \"0.50\".")
        }

        val ids = HashSet<String>()
        val indices = HashSet<Int>()
        val elevations = HashSet<Double>()
        val floorIndexOf = ArrayList<Int>(scans.size)
        for (scan in scans) {
            val capture = scan.capture
            val floorIndex = readFloorIndex(capture)
            if (!ids.add(capture.captureId)) {
                refuse(FloorMergeRefusal.DUPLICATE_CAPTURE_ID, "Two scans share the capture id ${capture.captureId}. Use a fresh scan for each floor.")
            }
            if (!indices.add(floorIndex)) {
                refuse(FloorMergeRefusal.DUPLICATE_FLOOR_INDEX, "Two scans record floor index $floorIndex. Each floor needs its own index.")
            }
            if (!scan.elevationM.isFinite() || abs(scan.elevationM) > 1000.0) {
                refuse(FloorMergeRefusal.BAD_ELEVATION, "Floor $floorIndex needs a finite measured elevation within 1000 metres.")
            }
            if (!elevations.add(scan.elevationM)) {
                refuse(FloorMergeRefusal.DUPLICATE_ELEVATION, "Two floors share the elevation ${scan.elevationM} m. Floors need distinct measured elevations.")
            }
            floorIndexOf += floorIndex
        }
        if (scans.sumOf { it.capture.rooms.size } > MAX_ROOMS) {
            refuse(FloorMergeRefusal.TOO_MANY_ROOMS, "Merge accepts at most $MAX_ROOMS rooms across all floors.")
        }

        val links = input.links
        val linkable = scans.drop(1).map { it.capture.captureId }.toSet()
        val linked = HashSet<String>()
        for (link in links) {
            if (link.toCaptureId !in linkable) {
                refuse(FloorMergeRefusal.UNKNOWN_FLOOR_LINK, "A link names ${link.toCaptureId}, which is not a scanned floor after the reference floor.")
            }
            if (!linked.add(link.toCaptureId)) {
                refuse(FloorMergeRefusal.DUPLICATE_LINK, "Floor ${link.toCaptureId} has more than one link.")
            }
        }
        val reference = scans[0]
        val residuals = ArrayList<FloorPairResidual>()
        val floors = ArrayList<VastuArCaptureMergeRequestFloorsItem>(scans.size)
        floors += VastuArCaptureMergeRequestFloorsItem(floorIndexOf[0], reference.elevationM, listOf(0.0, 0.0), 0.0)
        for (index in 1 until scans.size) {
            val scan = scans[index]
            val id = scan.capture.captureId
            val link = links.firstOrNull { it.toCaptureId == id }
                ?: refuse(FloorMergeRefusal.MISSING_LINK, "Floor ${floorIndexOf[index]} ($id) has no link to the reference floor.")
            val points = link.points
            if (points.size < MIN_SHARED_POINTS || points.size > 256) {
                refuse(
                    FloorMergeRefusal.TOO_FEW_POINTS,
                    "Floor ${floorIndexOf[index]} needs $MIN_SHARED_POINTS to 256 shared stair or door points; got ${points.size}. " +
                        "Three points give a check beyond a two-point baseline.",
                )
            }
            for (p in points) {
                if (!p.reference.finite() || !p.next.finite()) {
                    refuse(FloorMergeRefusal.NON_FINITE_POINT, "Shared points must be finite metre coordinates.")
                }
                if (!onFloor(p.reference, reference.capture.outline.polygon) || !onFloor(p.next, scan.capture.outline.polygon)) {
                    refuse(FloorMergeRefusal.POINT_OUTSIDE_FLOOR, "Shared points must lie within the recorded outline of their own floor.")
                }
            }
            if (!hasBaseline(points.map { it.reference }) || !hasBaseline(points.map { it.next })) {
                refuse(FloorMergeRefusal.NO_BASELINE, "Shared points need a baseline of at least $MIN_BASELINE_M metre on both floors.")
            }
            val n = points.size.toDouble()
            val originX = points.sumOf { it.reference.x - it.next.x } / n
            val originY = points.sumOf { it.reference.y - it.next.y } / n
            val distances = points.map { hypot(it.next.x + originX - it.reference.x, it.next.y + originY - it.reference.y) }
            val rms = sqrt(distances.sumOf { it * it } / n)
            val worst = distances.max()
            if (worst > input.toleranceM || rms > input.toleranceM) {
                refuse(
                    FloorMergeRefusal.ERROR_ABOVE_TOLERANCE,
                    "Shared-link error ${String.format(java.util.Locale.ROOT, "%.4f", worst)} m exceeds ${input.toleranceM} m. " +
                        "Check the points and north; no merge request was sent.",
                )
            }
            residuals += FloorPairResidual(reference.capture.captureId, id, link.kind, points.size, rms, worst)
            floors += VastuArCaptureMergeRequestFloorsItem(floorIndexOf[index], scan.elevationM, listOf(originX, originY), 0.0)
        }

        val request = VastuArCaptureMergeRequest(
            captures = scans.mapIndexed { i, s -> VastuArCaptureMergeRequestCapturesItem(s.capture.captureId, floorIndexOf[i], s.capture) },
            links = emptyList(),
            floors = floors,
            toleranceM = input.toleranceM,
            maxChargeUsd = input.maxChargeUsd,
        )
        if (org.json.JSONObject(request.toMap()).toString().toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            refuse(FloorMergeRefusal.REQUEST_TOO_LARGE, "Merge request exceeds 1 MiB.")
        }
        val totalPoints = residuals.sumOf { it.controlCount }
        val registration = FloorMergeRegistration(
            pairResiduals = residuals,
            rmsResidualM = sqrt(residuals.sumOf { it.rmsResidualM * it.rmsResidualM * it.controlCount } / totalPoints),
            maxResidualM = residuals.maxOf { it.maxResidualM },
        )
        return PreparedFloorMerge(request, registration)
    }

    /**
     * Validates, then posts `ar/capture-merge`. The call is billed, so a
     * caller-retained [idempotencyKey] (1-256 visible ASCII characters) is
     * required: retry a timeout with the SAME key and the same [input] and the
     * first result is replayed with no second charge. The key is checked
     * before anything else, and a bad link throws before any request.
     *
     * The returned [FloorMergeResult.registration] holds the stated shared-point
     * error. The response is cross-checked against what was sent; a mismatch
     * throws [FloorMergeResponseException].
     */
    suspend fun merge(client: VedikaClient, input: FloorMergeInput, idempotencyKey: String): FloorMergeResult {
        if (!KEY.matches(idempotencyKey)) {
            refuse(FloorMergeRefusal.BAD_IDEMPOTENCY_KEY, "A caller-retained idempotencyKey (1-256 visible ASCII characters) is required to merge floors.")
        }
        val prepared = prepare(input)
        val response = client.vastu.vastuOperation(VastuContracts.arCaptureMerge, prepared.request, idempotencyKey = idempotencyKey)
        try {
            checkResponse(prepared.request, response)
        } catch (e: org.json.JSONException) {
            // The typed accessors throw on a missing field: that is also a result that does not match what was sent.
            throw FloorMergeResponseException("Invalid or empty merge result; keep the original scans and check the response before retrying.")
        }
        return FloorMergeResult(response, prepared.registration)
    }

    private fun checkResponse(request: VastuArCaptureMergeRequest, response: VastuTypedResponse<VastuArCaptureMergeData>) {
        val bad = FloorMergeResponseException("Invalid or empty merge result; keep the original scans and check the response before retrying.")
        if (!response.success) throw bad
        val data = response.data
        if (data.method != "capture-merge" || data.unresolvedAlignmentErrors.isNotEmpty()) throw bad
        val sentRooms = request.captures.sumOf { it.payload.rooms.size }
        if (data.rooms.size != sentRooms || data.floorStack.size != request.floors.size) throw bad
        if (request.floors.any { f ->
                data.floorStack.none { it.optInt("floorIndex", Int.MIN_VALUE) == f.floorIndex && it.optDouble("elevationM", Double.NaN) == f.elevationM }
            }
        ) throw bad
        if (request.captures.any { c ->
                data.rooms.count { it.optString("captureId") == c.id && it.optInt("floorIndex", Int.MIN_VALUE) == c.floorIndex } != c.payload.rooms.size
            }
        ) throw bad
    }

    // ---- validation helpers (mirror readFloorCapture / onFloor in the web reference) ----

    private fun FloorPoint.finite() = x.isFinite() && y.isFinite()

    private fun signedArea(ring: List<List<Double>>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a[0] * b[1] - b[0] * a[1]
        }
        return sum / 2.0
    }

    private fun validRing(ring: List<List<Double>>) =
        ring.size in 3..256 && ring.all { it.size == 2 && it[0].isFinite() && it[1].isFinite() } && abs(signedArea(ring)) > 0.001

    /** The floor index a scan recorded. Never relabelled: refuses a scan that cannot be stacked as measured. */
    private fun readFloorIndex(capture: VastuRoomCapture): Int {
        if (capture.schema != RoomCaptureGeometry.ROOM_CAPTURE_SCHEMA || capture.captureId.isBlank() || capture.captureId.length > 128 ||
            capture.frame.units != "metres" || capture.frame.axes != RoomCaptureGeometry.ROOM_CAPTURE_AXES ||
            capture.frame.north.referenceFrame !in setOf("true", "manual")
        ) {
            refuse(
                FloorMergeRefusal.UNSUPPORTED_CAPTURE,
                "Use a metre-based room scan with recorded true or explicitly entered north; magnetic north cannot be stacked as true north.",
            )
        }
        if (!validRing(capture.outline.polygon) || capture.rooms.size !in 1..64 || !capture.quality.polygonClosure ||
            capture.rooms.any { !validRing(it.polygon) }
        ) {
            refuse(FloorMergeRefusal.UNSUPPORTED_CAPTURE, "Use a closed outline with 1 to 64 measured room polygons.")
        }
        val floorIndex = capture.rooms[0].floorIndex
        if (floorIndex !in -3..9 || capture.rooms.any { it.floorIndex != floorIndex }) {
            refuse(FloorMergeRefusal.UNSUPPORTED_CAPTURE, "A scan must retain one recorded integer floor index from -3 to 9.")
        }
        return floorIndex
    }

    /** Inside or exactly on the ring: door controls often sit on a wall. */
    private fun onFloor(p: FloorPoint, ring: List<List<Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[j]
            val b = ring[i]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val along = (p.x - a[0]) * dx + (p.y - a[1]) * dy
            if ((dx != 0.0 || dy != 0.0) && abs((p.x - a[0]) * dy - (p.y - a[1]) * dx) <= 1e-8 && along >= 0 && along <= dx * dx + dy * dy) return true
            if ((a[1] > p.y) != (b[1] > p.y) && p.x < (b[0] - a[0]) * (p.y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
            j = i
        }
        return inside
    }

    private fun hasBaseline(points: List<FloorPoint>): Boolean {
        var widest = 0.0
        for (i in points.indices) for (k in i + 1 until points.size) {
            widest = max(widest, hypot(points[i].x - points[k].x, points[i].y - points[k].y))
        }
        return widest >= MIN_BASELINE_M
    }
}
