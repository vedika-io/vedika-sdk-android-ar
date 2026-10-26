package io.vedika.sdk.ar

import io.vedika.sdk.VedikaClient
import io.vedika.sdk.VastuRoomCapture

/**
 * The state behind a "merge floors" entry in a scanner flow: floors scanned one
 * at a time, the stair or door points the person picked on the reference floor
 * and on each later floor, and the measured elevations. A host screen only has
 * to feed it taps and show [status]; the validation, the request and the
 * refusal-before-billing rules stay in [FloorMerge].
 *
 * The first floor added is the reference floor. Every later floor is tied to it
 * with at least [FloorMerge.MIN_SHARED_POINTS] picked pairs. Pure JVM: no
 * Android type, no network until [merge].
 */
class FloorMergeSession(var toleranceM: Double = FloorMerge.DEFAULT_TOLERANCE_M) {
    private val scans = ArrayList<FloorScan>()
    private val picks = LinkedHashMap<String, ArrayList<SharedFloorPoint>>()
    private val kinds = HashMap<String, FloorLinkKind>()

    val floorCount: Int get() = scans.size

    /** Captures in the order they were added; the first is the reference floor. */
    val floors: List<FloorScan> get() = scans.toList()

    /** Adds a finished floor scan with its measured elevation (metres above the building datum). */
    fun addFloor(capture: VastuRoomCapture, elevationM: Double) {
        require(elevationM.isFinite()) { "elevationM must be a finite number of metres." }
        require(scans.none { it.capture.captureId == capture.captureId }) { "That scan is already part of this merge." }
        require(scans.size < FloorMerge.MAX_FLOORS) { "Merge accepts at most ${FloorMerge.MAX_FLOORS} floors." }
        scans += FloorScan(capture, elevationM)
        if (scans.size > 1) picks[capture.captureId] = ArrayList()
    }

    /** Drops the most recently added floor and its picks. The reference floor is removed last. */
    fun removeLastFloor() {
        val last = scans.removeLastOrNull() ?: return
        picks.remove(last.capture.captureId)
        kinds.remove(last.capture.captureId)
    }

    /** One stair or door spot, as picked on the reference floor and on the floor [captureId]. */
    fun addPick(captureId: String, reference: FloorPoint, next: FloorPoint) {
        val list = requireNotNull(picks[captureId]) { "$captureId is not a floor after the reference floor." }
        list += SharedFloorPoint(reference, next)
    }

    fun undoPick(captureId: String) {
        picks[captureId]?.removeLastOrNull()
    }

    fun setKind(captureId: String, kind: FloorLinkKind) {
        require(picks.containsKey(captureId)) { "$captureId is not a floor after the reference floor." }
        kinds[captureId] = kind
    }

    fun pickCount(captureId: String): Int = picks[captureId]?.size ?: 0

    /** Pairs still missing before [captureId] can be tied to the reference floor. */
    fun pairsStillNeeded(captureId: String): Int = (FloorMerge.MIN_SHARED_POINTS - pickCount(captureId)).coerceAtLeast(0)

    /** True once there are two floors and every later floor has its three pairs. Validation proper is [FloorMerge.prepare]. */
    fun canMerge(): Boolean = scans.size >= 2 && picks.keys.all { pairsStillNeeded(it) == 0 }

    /** One sentence for the screen: what to do next, or that the merge can be requested. */
    fun status(): String {
        if (scans.size < 2) return "Scan a second floor, then pick shared stair or door points on both plans."
        val missing = picks.keys.firstOrNull { pairsStillNeeded(it) > 0 }
        val index = missing?.let { id -> scans.indexOfFirst { it.capture.captureId == id } }
        return if (missing == null) "Ready: ${scans.size} floors linked. Merging is billed once and shows the shared-point error."
        else "Floor ${index!! + 1}: pick ${pairsStillNeeded(missing)} more stair or door point(s) on the reference plan and on this plan."
    }

    fun input(maxChargeUsd: String? = null): FloorMergeInput = FloorMergeInput(
        scans = scans.toList(),
        links = picks.map { (id, points) -> FloorLink(id, kinds[id] ?: FloorLinkKind.STAIR, points.toList()) },
        toleranceM = toleranceM,
        maxChargeUsd = maxChargeUsd,
    )

    /** Validates and builds the request without sending it. Throws [FloorMergeRefusedException] for a bad link. */
    fun prepare(maxChargeUsd: String? = null): PreparedFloorMerge = FloorMerge.prepare(input(maxChargeUsd))

    /**
     * Posts the merge. [idempotencyKey] must be kept by the caller and reused to retry
     * the same merge without a second charge.
     */
    suspend fun merge(client: VedikaClient, idempotencyKey: String, maxChargeUsd: String? = null): FloorMergeResult =
        FloorMerge.merge(client, input(maxChargeUsd), idempotencyKey)
}
