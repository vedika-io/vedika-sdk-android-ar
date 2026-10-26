package io.vedika.sdk.ar

import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuRoomCaptureDevice
import io.vedika.sdk.VastuRoomCaptureFrame
import io.vedika.sdk.VastuRoomCaptureFrameNorth
import io.vedika.sdk.VastuRoomCaptureOutline
import io.vedika.sdk.VastuRoomCaptureQuality
import io.vedika.sdk.VastuRoomCaptureRoomsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FloorMergeSession` collects floors and picked shared points for a "merge floors"
 * entry and hands them to `FloorMerge`. No network is used.
 */
class FloorMergeSessionTest {
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

    // ---- the entry flow ----

    private fun session(): FloorMergeSession = FloorMergeSession().apply {
        addFloor(capture("ground", 0), 0.0)
        addFloor(capture("first", 1), 3.2)
    }

    @Test fun oneFloorCannotMergeAndTheScreenSaysWhatToDo() {
        val s = FloorMergeSession()
        s.addFloor(capture("ground", 0), 0.0)
        assertTrue(!s.canMerge())
        assertTrue(s.status().startsWith("Scan a second floor"))
        assertEquals(FloorMergeRefusal.TOO_FEW_FLOORS, refusal { s.prepare() })
    }

    @Test fun threePairsOnTheSecondFloorUnlockTheMerge() {
        val s = session()
        assertTrue(!s.canMerge())
        assertEquals(3, s.pairsStillNeeded("first"))
        stairs().take(2).forEach { s.addPick("first", it.reference, it.next) }
        assertTrue(!s.canMerge())
        assertEquals(1, s.pairsStillNeeded("first"))
        assertTrue(s.status().contains("pick 1 more"))
        stairs().drop(2).forEach { s.addPick("first", it.reference, it.next) }
        assertTrue(s.canMerge())
        assertTrue(s.status().startsWith("Ready: 2 floors linked"))
    }

    @Test fun theSessionBuildsTheSameRequestAsTheDirectCall() {
        val s = session()
        stairs().forEach { s.addPick("first", it.reference, it.next) }
        val viaSession = s.prepare("0.50")
        val direct = FloorMerge.prepare(twoFloors().copy(maxChargeUsd = "0.50"))
        assertEquals(direct.request.toMap(), viaSession.request.toMap())
        assertEquals(direct.registration.rmsResidualM, viaSession.registration.rmsResidualM, 1e-12)
    }

    @Test fun aBadLinkIsRefusedByTheSharedValidatorBeforeAnyRequest() {
        val s = session()
        stairs(noise = 0.9).forEach { s.addPick("first", it.reference, it.next) }
        assertTrue(s.canMerge())
        assertEquals(FloorMergeRefusal.ERROR_ABOVE_TOLERANCE, refusal { s.prepare() })
    }

    @Test fun undoRemovesTheLastPickAndRemovingAFloorDropsItsPicks() {
        val s = session()
        stairs().forEach { s.addPick("first", it.reference, it.next) }
        s.undoPick("first")
        assertEquals(2, s.pickCount("first"))
        s.removeLastFloor()
        assertEquals(1, s.floorCount)
        assertEquals(0, s.pickCount("first"))
        assertThrows(IllegalArgumentException::class.java) { s.addPick("first", FloorPoint(1.0, 1.0), FloorPoint(1.0, 1.0)) }
    }

    @Test fun theSameScanTwiceAndAPickOnTheReferenceFloorAreRejected() {
        val s = session()
        assertThrows(IllegalArgumentException::class.java) { s.addFloor(capture("first", 1), 6.4) }
        assertThrows(IllegalArgumentException::class.java) { s.addPick("ground", FloorPoint(1.0, 1.0), FloorPoint(1.0, 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { s.addFloor(capture("second", 2), Double.NaN) }
    }

    @Test fun doorKindIsCarriedToTheLink() {
        val s = session()
        stairs().forEach { s.addPick("first", it.reference, it.next) }
        s.setKind("first", FloorLinkKind.DOOR)
        assertEquals(FloorLinkKind.DOOR, s.input().links.single().kind)
    }
}
