package io.vedika.sdk.ar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Kotlin parity for the model-only scenarios in
 * `web/vedika-public/js/vastu/__tests__/room-capture-webxr.test.mjs` (the
 * WebGL/DOM/XR-session driver half of that suite has no Android analogue —
 * `VastuArCoreCaptureView` is exercised on-device, see
 * `sdks/android-acceptance`'s instrumentation test instead).
 */
class RoomCaptureModelTest {

    private class Clock(start: Long = 1_000_000L) {
        var t = start
            private set
        fun now(): Long = t
        fun advance(ms: Long) { t += ms }
    }

    private fun holdNorth(model: RoomCaptureModel, forward: Vec3, clock: Clock, samples: Int = 20, stepMs: Long = 200) {
        repeat(samples) {
            model.addNorthSample(forward, clock.now())
            clock.advance(stepMs)
        }
    }

    private fun withFixtures(block: (List<JSONObject>) -> Unit) {
        val root = RoomCaptureFixtures.monorepoRoot()
        Assume.assumeTrue(
            "skipped: no monorepo checkout above ${System.getProperty("user.dir")}, " +
                "so sdks/fixtures/vastu-room-capture is not reachable",
            root != null,
        )
        block(RoomCaptureFixtures.load(root!!))
    }

    @Test fun tapsReproduceEveryTracedFixturesOutlineAndRoomCornersExactly() = withFixtures { fixtures ->
        var checked = 0
        for (fx in fixtures) {
            val session = fx.getJSONObject("session")
            val outline = session.getJSONObject("outline")
            if (outline.getString("source") != "traced") continue
            val parsed = RoomCaptureFixtures.parseSession(session)
            val clock = Clock()
            val model = RoomCaptureModel("x", now = clock::now)
            for (c in parsed.outline.worldCorners) assertEquals(fx.getString("fixture"), RoomCaptureModel.TapResult.ADDED, model.tap(c))
            val closingTap = parsed.outline.closingTap!!
            val first = parsed.outline.worldCorners[0]
            val snaps = kotlin.math.hypot(closingTap.x - first.x, closingTap.z - first.z) <= RoomCaptureModel.CLOSE_SNAP_M
            if (snaps) assertEquals(fx.getString("fixture"), RoomCaptureModel.TapResult.CLOSED, model.tap(closingTap))
            else model.closeAt(closingTap)
            for (room in parsed.rooms) {
                model.startRoom(room.label)
                for (c in room.worldCorners) model.tap(c)
                model.closeAt(room.closingTap!!)
            }
            val northClock = Clock()
            holdNorth(model, Vec3(0.0, 0.0, -1.0), northClock) // north is not what this test pins
            val built = model.toSession(endedAt = northClock.now())
            assertEquals(fx.getString("fixture"), parsed.outline.worldCorners, built.outline.worldCorners)
            assertEquals(fx.getString("fixture"), parsed.outline.closingTap, built.outline.closingTap)
            assertEquals(fx.getString("fixture"), parsed.rooms.size, built.rooms.size)
            built.rooms.forEachIndexed { i, r ->
                assertEquals("${fx.getString("fixture")} room $i", parsed.rooms[i].worldCorners, r.worldCorners)
                assertEquals("${fx.getString("fixture")} room $i", parsed.rooms[i].closingTap, r.closingTap)
                assertEquals("${fx.getString("fixture")} room $i", parsed.rooms[i].label, r.label)
            }
            checked += 1
        }
        assertTrue("only $checked traced fixtures", checked >= 5)
    }

    /** A 4 m x 6 m house traced in AR world coordinates, with a kitchen in one corner. */
    private fun traceHouse(model: RoomCaptureModel, toWorld: (Double, Double) -> Vec3) {
        for ((x, y) in listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 6.0, 0.0 to 6.0)) model.tap(toWorld(x, y))
        assertEquals(RoomCaptureModel.TapResult.CLOSED, model.tap(toWorld(0.1, 0.1)))
        model.startRoom("kitchen")
        for ((x, y) in listOf(3.0 to 5.0, 4.0 to 5.0, 4.0 to 6.0, 3.0 to 6.0)) model.tap(toWorld(x, y))
        assertEquals(RoomCaptureModel.TapResult.CLOSED, model.tap(toWorld(3.05, 5.05)))
    }

    @Test fun northSetByFacingItThatDirectionBecomesPlusYFrameIsManual() {
        // World where "north" is -Z: plan (x, y) sits at world (x, 0, -y).
        val clock = Clock()
        val model = RoomCaptureModel("n1", now = clock::now)
        traceHouse(model) { x, y -> Vec3(x, -1.4, -y) }
        holdNorth(model, Vec3(0.0, -0.3, -1.0), clock)
        val capture = model.build()
        assertEquals("manual", capture.frame.north.referenceFrame)
        assertEquals("manual", capture.frame.north.declinationProvenance)
        assertEquals(listOf(listOf(0.0, 0.0), listOf(4.0, 0.0), listOf(4.0, 6.0), listOf(0.0, 6.0)), capture.outline.polygon)
        assertEquals(listOf(listOf(3.0, 5.0), listOf(4.0, 5.0), listOf(4.0, 6.0), listOf(3.0, 6.0)), capture.rooms[0].polygon)
        assertEquals("kitchen", capture.rooms[0].label)
        assertEquals(null, capture.quality.pointCloudDensity)
        assertEquals("arcore-hit", capture.device.method)
    }

    @Test fun theSameHouseWithTheArWorldTurned90DegreesGivesTheSamePlan() {
        // Now north is world +X: plan (x, y) sits at world (y, 0, x).
        val clock = Clock()
        val model = RoomCaptureModel("n2", now = clock::now)
        traceHouse(model) { x, y -> Vec3(y, -1.4, x) }
        holdNorth(model, Vec3(1.0, -0.2, 0.0), clock)
        val capture = model.build()
        assertEquals(listOf(listOf(0.0, 0.0), listOf(4.0, 0.0), listOf(4.0, 6.0), listOf(0.0, 6.0)), capture.outline.polygon)
        assertEquals(listOf(listOf(3.0, 5.0), listOf(4.0, 5.0), listOf(4.0, 6.0), listOf(3.0, 6.0)), capture.rooms[0].polygon)
    }

    @Test fun northNeedsEnoughSamplesOverEnoughTimeAndAClosedOutlineFirst() {
        val clock = Clock()
        val model = RoomCaptureModel("n3", now = clock::now)
        assertThrows(IllegalStateException::class.java) { model.toSession() }
        traceHouse(model) { x, y -> Vec3(x, 0.0, -y) }
        holdNorth(model, Vec3(0.0, 0.0, -1.0), clock, RoomCaptureModel.MIN_NORTH_SAMPLES - 1, 400)
        assertFalse(model.northReady)
        assertThrows(IllegalStateException::class.java) { model.toSession() }
        model.resetNorth()
        holdNorth(model, Vec3(0.0, 0.0, -1.0), clock, RoomCaptureModel.MIN_NORTH_SAMPLES + 5, RoomCaptureModel.MIN_NORTH_MS / 40)
        assertFalse(model.northReady)
        model.resetNorth()
        holdNorth(model, Vec3(0.0, 0.0, -1.0), clock)
        assertTrue(model.northReady)
    }

    @Test fun aRoomStillBeingTracedBlocksFinishAndBlocksStartingAnother() {
        val clock = Clock()
        val model = RoomCaptureModel("n4", now = clock::now)
        traceHouse(model) { x, y -> Vec3(x, 0.0, -y) }
        holdNorth(model, Vec3(0.0, 0.0, -1.0), clock)
        model.startRoom("pooja")
        model.tap(Vec3(0.0, 0.0, 0.0))
        assertThrows(IllegalStateException::class.java) { model.toSession() }
        assertTrue(model.undo())
        assertEquals(0, model.currentRoom!!.corners)
        assertThrows(IllegalStateException::class.java) { model.startRoom("toilet") }
    }

    @Test fun closingNeedsThreeCornersAFarTapAddsACornerInstead() {
        val model = RoomCaptureModel("n5")
        model.tap(Vec3(0.0, 0.0, 0.0))
        model.tap(Vec3(1.0, 0.0, 0.0))
        assertEquals(RoomCaptureModel.TapResult.ADDED, model.tap(Vec3(0.05, 0.0, 0.0))) // only two corners before it, so no snap
        assertThrows(IllegalStateException::class.java) { RoomCaptureModel("n6").closeAt(Vec3(0.0, 0.0, 0.0)) }
    }

    @Test fun everyOfferedRoomNameIsOneTheServerRecognises() {
        // Mirrors normalize_room_type in ported/vastu.rs.
        val known = Regex(
            "kitchen|cooking|master|pooja|puja|mandir|prayer|toilet|bath|living|hall|drawing|dining|" +
                "treasury|granary|locker|vault|storage|store|study|stair|entrance|door|entry|guest|children|kid|bed",
        )
        for (label in RoomCaptureModel.ROOM_LABELS) {
            assertTrue(label, known.containsMatchIn(label.replace(Regex("[^a-zA-Z]"), "").lowercase()))
        }
    }
}
