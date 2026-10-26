package io.vedika.sdk.ar

import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuRoomCaptureDevice
import io.vedika.sdk.VastuRoomCaptureFrame
import io.vedika.sdk.VastuRoomCaptureFrameNorth
import io.vedika.sdk.VastuRoomCaptureOutline
import io.vedika.sdk.VastuRoomCaptureQuality
import io.vedika.sdk.VastuRoomCaptureRoomsItem
import io.vedika.sdk.VedikaClient
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FloorMerge` builds the `ar/capture-merge` request from finished floor
 * scans and refuses a bad link before any request is made. Requests go to a
 * loopback MockWebServer only: no real network, no spend.
 */
class FloorMergeTest {
    private val square = listOf(listOf(0.0, 0.0), listOf(6.0, 0.0), listOf(6.0, 5.0), listOf(0.0, 5.0))

    private fun capture(id: String, floorIndex: Int, referenceFrame: String = "true", closed: Boolean = true) = VastuRoomCapture(
        schema = "vedika.roomCapture/1",
        captureId = id,
        capturedAtEpoch = 1789000000L,
        device = VastuRoomCaptureDevice("android", "arcore-hit", "none"),
        frame = VastuRoomCaptureFrame(
            units = "metres",
            axes = "+X east,+Y true north",
            north = VastuRoomCaptureFrameNorth(referenceFrame, "arcore-compass", "fixture", 17, 0.9),
        ),
        outline = VastuRoomCaptureOutline(square),
        rooms = listOf(VastuRoomCaptureRoomsItem("r-$id", "kitchen", "user", square, 30.0, floorIndex)),
        quality = VastuRoomCaptureQuality(closed, "none", 1, 1),
        attestation = "caller-reported",
    )

    /** Stair points: the same horizontal spot, 1.2 m east / 0.4 m north on the upper plan. */
    private fun stairs(dx: Double = -1.2, dy: Double = -0.4, noise: Double = 0.0) = listOf(
        SharedFloorPoint(FloorPoint(2.0, 1.0), FloorPoint(2.0 + dx, 1.0 + dy)),
        SharedFloorPoint(FloorPoint(2.0, 3.0), FloorPoint(2.0 + dx, 3.0 + dy + noise)),
        SharedFloorPoint(FloorPoint(4.0, 2.0), FloorPoint(4.0 + dx, 2.0 + dy)),
    )

    private fun twoFloors(
        points: List<SharedFloorPoint> = stairs(),
        toleranceM: Double = 0.05,
        kind: FloorLinkKind = FloorLinkKind.STAIR,
    ) = FloorMergeInput(
        scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 1), 3.2)),
        links = listOf(FloorLink("first", kind, points)),
        toleranceM = toleranceM,
    )

    private fun refusal(block: () -> Unit): FloorMergeRefusal =
        assertThrows(FloorMergeRefusedException::class.java) { block() }.reason

    // ---- request shape ----

    @Test fun twoFloorsBecomeExplicitFloorFramesAndNoCrossFloorApiLink() {
        val prepared = FloorMerge.prepare(twoFloors())
        val body = JSONObject(prepared.request.toMap())
        assertEquals(setOf("captures", "links", "floors", "toleranceM"), body.keySet())
        // The API only links captures on one floor, so the stair points become a floor frame.
        assertEquals(0, body.getJSONArray("links").length())
        assertEquals(0.05, body.getDouble("toleranceM"), 0.0)
        val captures = body.getJSONArray("captures")
        assertEquals(2, captures.length())
        assertEquals(setOf("id", "floorIndex", "payload"), captures.getJSONObject(0).keySet())
        assertEquals("ground", captures.getJSONObject(0).getString("id"))
        assertEquals(1, captures.getJSONObject(1).getInt("floorIndex"))
        assertEquals("vedika.roomCapture/1", captures.getJSONObject(1).getJSONObject("payload").getString("schema"))

        val floors = body.getJSONArray("floors")
        assertEquals(setOf("floorIndex", "elevationM", "originXY", "bearingDeg"), floors.getJSONObject(0).keySet())
        assertEquals(0, floors.getJSONObject(0).getInt("floorIndex"))
        assertEquals(0.0, floors.getJSONObject(0).getJSONArray("originXY").getDouble(0), 0.0)
        // originXY = reference point minus the same point on the upper plan.
        val upper = floors.getJSONObject(1)
        assertEquals(1, upper.getInt("floorIndex"))
        assertEquals(3.2, upper.getDouble("elevationM"), 0.0)
        assertEquals(1.2, upper.getJSONArray("originXY").getDouble(0), 1e-9)
        assertEquals(0.4, upper.getJSONArray("originXY").getDouble(1), 1e-9)
        assertEquals(0.0, upper.getDouble("bearingDeg"), 0.0)
        assertNull(body.optString("maxChargeUsd", null))
    }

    @Test fun statedErrorIsTheSharedPointResidual() {
        val exact = FloorMerge.prepare(twoFloors()).registration
        assertEquals(0.0, exact.rmsResidualM, 1e-9)
        assertEquals(0.0, exact.maxResidualM, 1e-9)
        assertEquals(1, exact.pairResiduals.size)
        assertEquals("ground", exact.pairResiduals[0].fromCaptureId)
        assertEquals("first", exact.pairResiduals[0].toCaptureId)
        assertEquals(3, exact.pairResiduals[0].controlCount)

        // One point 3 cm off: mean shift absorbs a third, so the residuals are small and stated.
        val noisy = FloorMerge.prepare(twoFloors(stairs(noise = 0.03))).registration
        assertTrue(noisy.maxResidualM in 0.015..0.03)
        assertTrue(noisy.rmsResidualM > 0.0 && noisy.rmsResidualM <= noisy.maxResidualM)
        assertTrue(noisy.describe().contains("not survey accuracy"))
    }

    @Test fun maxChargeIsForwardedAsGiven() {
        val body = JSONObject(FloorMerge.prepare(twoFloors().copy(maxChargeUsd = "0.50")).request.toMap())
        assertEquals("0.50", body.getString("maxChargeUsd"))
    }

    @Test fun threeFloorsLinkEachUpperFloorToTheReference() {
        val input = FloorMergeInput(
            scans = listOf(FloorScan(capture("g", 0), 0.0), FloorScan(capture("f1", 1), 3.2), FloorScan(capture("f2", 2), 6.4)),
            links = listOf(FloorLink("f2", FloorLinkKind.DOOR, stairs(-2.0, 0.0)), FloorLink("f1", FloorLinkKind.STAIR, stairs())),
        )
        val floors = JSONObject(FloorMerge.prepare(input).request.toMap()).getJSONArray("floors")
        assertEquals(3, floors.length())
        assertEquals(2.0, floors.getJSONObject(2).getJSONArray("originXY").getDouble(0), 1e-9)
        assertEquals(1.2, floors.getJSONObject(1).getJSONArray("originXY").getDouble(0), 1e-9)
    }

    // ---- refusals, all raised before any request ----

    @Test fun refusesFewerThanTwoFloors() {
        val one = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0)), links = emptyList())
        assertEquals(FloorMergeRefusal.TOO_FEW_FLOORS, refusal { FloorMerge.prepare(one) })
    }

    @Test fun refusesDuplicateFloorIndexAndDuplicateCaptureId() {
        val sameFloor = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 0), 3.2)))
        assertEquals(FloorMergeRefusal.DUPLICATE_FLOOR_INDEX, refusal { FloorMerge.prepare(sameFloor) })
        val sameId = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("ground", 1), 3.2)))
        assertEquals(FloorMergeRefusal.DUPLICATE_CAPTURE_ID, refusal { FloorMerge.prepare(sameId) })
        val sameElevation = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 1), 0.0)))
        assertEquals(FloorMergeRefusal.DUPLICATE_ELEVATION, refusal { FloorMerge.prepare(sameElevation) })
    }

    @Test fun refusesALinkToAnUnknownFloor() {
        val unknown = twoFloors().copy(links = listOf(FloorLink("second", FloorLinkKind.STAIR, stairs())))
        assertEquals(FloorMergeRefusal.UNKNOWN_FLOOR_LINK, refusal { FloorMerge.prepare(unknown) })
        // A link naming the reference floor itself, beside a valid one, is also unknown.
        val three = FloorMergeInput(
            scans = listOf(FloorScan(capture("g", 0), 0.0), FloorScan(capture("f1", 1), 3.2), FloorScan(capture("f2", 2), 6.4)),
            links = listOf(FloorLink("f1", FloorLinkKind.STAIR, stairs()), FloorLink("g", FloorLinkKind.STAIR, stairs())),
        )
        assertEquals(FloorMergeRefusal.UNKNOWN_FLOOR_LINK, refusal { FloorMerge.prepare(three) })
        val none = three.copy(links = listOf(FloorLink("f1", FloorLinkKind.STAIR, stairs())))
        assertEquals(FloorMergeRefusal.MISSING_LINK, refusal { FloorMerge.prepare(none) })
        val dup = three.copy(links = listOf(FloorLink("f1", FloorLinkKind.STAIR, stairs()), FloorLink("f1", FloorLinkKind.STAIR, stairs())))
        assertEquals(FloorMergeRefusal.DUPLICATE_LINK, refusal { FloorMerge.prepare(dup) })
    }

    @Test fun refusesTooFewSharedPoints() {
        assertEquals(FloorMergeRefusal.TOO_FEW_POINTS, refusal { FloorMerge.prepare(twoFloors(stairs().take(2))) })
        assertEquals(FloorMergeRefusal.TOO_FEW_POINTS, refusal { FloorMerge.prepare(twoFloors(emptyList())) })
    }

    @Test fun refusesNonFiniteCoordinates() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val points = stairs().toMutableList().also { it[1] = SharedFloorPoint(FloorPoint(bad, 3.0), it[1].next) }
            assertEquals(FloorMergeRefusal.NON_FINITE_POINT, refusal { FloorMerge.prepare(twoFloors(points)) })
            val next = stairs().toMutableList().also { it[2] = SharedFloorPoint(it[2].reference, FloorPoint(1.0, bad)) }
            assertEquals(FloorMergeRefusal.NON_FINITE_POINT, refusal { FloorMerge.prepare(twoFloors(next)) })
        }
    }

    @Test fun refusesPointsOutsideTheirOwnFloorAndCoincidentPoints() {
        val outside = stairs().toMutableList().also { it[0] = SharedFloorPoint(FloorPoint(9.0, 9.0), it[0].next) }
        assertEquals(FloorMergeRefusal.POINT_OUTSIDE_FLOOR, refusal { FloorMerge.prepare(twoFloors(outside)) })
        val p = SharedFloorPoint(FloorPoint(2.0, 2.0), FloorPoint(1.0, 1.0))
        assertEquals(FloorMergeRefusal.NO_BASELINE, refusal { FloorMerge.prepare(twoFloors(listOf(p, p, p))) })
    }

    @Test fun refusesAMisreadLinkAboveTolerance() {
        // One floor's points disagree by 0.5 m: the merge must not be sent.
        val bad = stairs().toMutableList().also { it[1] = SharedFloorPoint(it[1].reference, FloorPoint(it[1].next.x + 0.5, it[1].next.y)) }
        assertEquals(FloorMergeRefusal.ERROR_ABOVE_TOLERANCE, refusal { FloorMerge.prepare(twoFloors(bad)) })
        // The same spread is accepted when the caller states a wide enough tolerance.
        FloorMerge.prepare(twoFloors(bad, toleranceM = 1.0))
    }

    @Test fun refusesAScanThatCannotBeStacked() {
        val magnetic = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 1, "magnetic"), 3.2)))
        assertEquals(FloorMergeRefusal.UNSUPPORTED_CAPTURE, refusal { FloorMerge.prepare(magnetic) })
        val open = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 1, closed = false), 3.2)))
        assertEquals(FloorMergeRefusal.UNSUPPORTED_CAPTURE, refusal { FloorMerge.prepare(open) })
    }

    @Test fun refusesBadToleranceElevationAndMaxCharge() {
        assertEquals(FloorMergeRefusal.BAD_TOLERANCE, refusal { FloorMerge.prepare(twoFloors(toleranceM = 0.0)) })
        assertEquals(FloorMergeRefusal.BAD_TOLERANCE, refusal { FloorMerge.prepare(twoFloors(toleranceM = Double.NaN)) })
        assertEquals(FloorMergeRefusal.BAD_TOLERANCE, refusal { FloorMerge.prepare(twoFloors(toleranceM = 1.5)) })
        val nan = twoFloors().copy(scans = listOf(FloorScan(capture("ground", 0), 0.0), FloorScan(capture("first", 1), Double.NaN)))
        assertEquals(FloorMergeRefusal.BAD_ELEVATION, refusal { FloorMerge.prepare(nan) })
        assertEquals(FloorMergeRefusal.BAD_MAX_CHARGE, refusal { FloorMerge.prepare(twoFloors().copy(maxChargeUsd = "-1")) })
    }

    // ---- the call: key, no request on refusal, response check ----

    private fun mergedBody(rooms: List<Pair<String, Int>> = listOf("ground" to 0, "first" to 1), stack: List<Pair<Int, Double>> = listOf(0 to 0.0, 1 to 3.2)) =
        JSONObject()
            .put("success", true)
            .put(
                "data",
                JSONObject()
                    .put("method", "capture-merge")
                    .put("frame", JSONObject())
                    .put("rooms", JSONArray(rooms.map { JSONObject().put("captureId", it.first).put("floorIndex", it.second) }))
                    .put("outlines", JSONArray())
                    .put("registrations", JSONArray())
                    .put("pairResiduals", JSONArray())
                    .put("unresolvedAlignmentErrors", JSONArray())
                    .put("floorStack", JSONArray(stack.map { JSONObject().put("floorIndex", it.first).put("elevationM", it.second) }))
                    .put("toleranceM", 0.05)
                    .put("alignmentMethod", "anchored-least-squares-rigid-2d"),
            )
            .put("billing", JSONObject().put("charged", 0.01).put("currency", "USD"))
            .toString()

    private fun withServer(block: suspend (MockWebServer, VedikaClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            block(server, VedikaClient(apiKey = "vk_test", baseUrl = server.url("/").toString().trimEnd('/')))
        } finally {
            server.shutdown()
        }
    }

    @Test fun mergePostsTheRequestWithTheCallersIdempotencyKeyAndReturnsTheStatedError() = withServer { server, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(mergedBody()))
        val result = FloorMerge.merge(client, twoFloors(), idempotencyKey = "merge-key-0123")
        val wire = server.takeRequest()
        assertEquals("POST", wire.method)
        assertEquals("/v2/astrology/vastu/ar/capture-merge", wire.path)
        assertEquals("merge-key-0123", wire.getHeader("Idempotency-Key"))
        val body = JSONObject(wire.body.readUtf8())
        assertEquals(setOf("captures", "links", "floors", "toleranceM"), body.keySet())
        assertEquals(2, body.getJSONArray("floors").length())
        assertEquals("capture-merge", result.building.method)
        assertEquals(2, result.building.floorStack.size)
        assertEquals(2, result.building.rooms.size)
        assertEquals(0.0, result.rmsResidualM, 1e-9)
        assertEquals(0.0, result.maxResidualM, 1e-9)
    }

    @Test fun aRefusedLinkMakesNoRequest() = withServer { server, client ->
        val bad = stairs().toMutableList().also { it[1] = SharedFloorPoint(it[1].reference, FloorPoint(it[1].next.x + 0.5, it[1].next.y)) }
        val error = runCatching { FloorMerge.merge(client, twoFloors(bad), idempotencyKey = "merge-key-0123") }.exceptionOrNull()
        assertTrue(error is FloorMergeRefusedException)
        assertEquals(FloorMergeRefusal.ERROR_ABOVE_TOLERANCE, (error as FloorMergeRefusedException).reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun aMissingOrMalformedIdempotencyKeyMakesNoRequest() = withServer { server, client ->
        for (key in listOf("", "has space", "naïve", "k".repeat(257))) {
            val error = runCatching { FloorMerge.merge(client, twoFloors(), idempotencyKey = key) }.exceptionOrNull()
            assertEquals(FloorMergeRefusal.BAD_IDEMPOTENCY_KEY, (error as FloorMergeRefusedException).reason)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun aResultThatDoesNotMatchWhatWasSentIsNotReturned() = withServer { server, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(mergedBody(rooms = listOf("ground" to 0))))
        assertThrows(FloorMergeResponseException::class.java) {
            runBlocking { FloorMerge.merge(client, twoFloors(), idempotencyKey = "merge-key-0123") }
        }
    }
}
