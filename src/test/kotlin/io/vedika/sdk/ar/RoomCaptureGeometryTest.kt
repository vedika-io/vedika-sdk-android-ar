package io.vedika.sdk.ar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Kotlin parity for `web/vedika-public/js/vastu/__tests__/room-capture-geometry.test.mjs`.
 * Same shared fixtures, same scenarios, same expected outcomes (numeric
 * fields within 1e-6 — see [RoomCaptureFixtures]).
 */
class RoomCaptureGeometryTest {

    private fun withFixtures(block: (List<JSONObject>) -> Unit) {
        val root = RoomCaptureFixtures.monorepoRoot()
        Assume.assumeTrue(
            "skipped: no monorepo checkout above ${System.getProperty("user.dir")}, " +
                "so sdks/fixtures/vastu-room-capture is not reachable",
            root != null,
        )
        block(RoomCaptureFixtures.load(root!!))
    }

    @Test fun everySharedFixtureSessionBecomesItsExpectedCaptureExactly() = withFixtures { fixtures ->
        assertEquals(7, fixtures.size)
        for (fixture in fixtures) {
            val session = RoomCaptureFixtures.parseSession(fixture.getJSONObject("session"))
            val capture = RoomCaptureGeometry.buildRoomCapture(session)
            RoomCaptureFixtures.assertMatches(capture.toMap(), fixture.getJSONObject("expected").getJSONObject("capture"), fixture.getString("fixture"))
        }
    }

    @Test fun theEastDoorLandsOnTheEastWallAtEveryDeviceYaw() = withFixtures { fixtures ->
        for (name in listOf("room-3x4-yaw0", "room-3x4-yaw90", "room-3x4-yaw233")) {
            val fixture = fixtures.first { it.getString("fixture") == name }
            val session = RoomCaptureFixtures.parseSession(fixture.getJSONObject("session"))
            val capture = RoomCaptureGeometry.buildRoomCapture(session)
            assertEquals(name, listOf(listOf(0.0, 0.0), listOf(9.0, 0.0), listOf(9.0, 12.0), listOf(0.0, 12.0)), capture.outline.polygon)
            assertEquals(name, listOf(listOf(6.0, 0.0), listOf(9.0, 0.0), listOf(9.0, 4.0), listOf(6.0, 4.0)), capture.rooms[0].polygon)
            assertEquals(name, listOf(9.0, 1.0), capture.rooms[0].openings!![0].centerXY)
        }
    }

    @Test fun aMirroredFloorMappingCannotPassTheDoorCheck() = withFixtures { fixtures ->
        val fixture = fixtures.first { it.getString("fixture") == "room-3x4-yaw90" }
        val session = RoomCaptureFixtures.parseSession(fixture.getJSONObject("session"))
        fun mirror(p: Vec3) = Vec3(p.x, p.y, -p.z)
        val mirrored = session.copy(
            outline = session.outline.copy(
                worldCorners = session.outline.worldCorners.map(::mirror),
                closingTap = session.outline.closingTap?.let(::mirror),
            ),
            rooms = session.rooms.map { room ->
                room.copy(
                    worldCorners = room.worldCorners.map(::mirror),
                    closingTap = room.closingTap?.let(::mirror),
                    openings = room.openings.map { it.copy(centerWorld = mirror(it.centerWorld)) },
                )
            },
            headingSamples = session.headingSamples.map { it.copy(forward = mirror(it.forward)) },
        )
        val capture = RoomCaptureGeometry.buildRoomCapture(mirrored)
        assertNotEquals(listOf(9.0, 1.0), capture.rooms[0].openings!![0].centerXY)
    }

    @Test fun northNeedsSamplesAndReportsAMagneticFrameWhenDeclinationIsUnknown() = withFixtures { fixtures ->
        assertThrows(IllegalArgumentException::class.java) {
            RoomCaptureGeometry.northRotation(emptyList(), "magnetic", 1.0)
        }
        val magnetic = fixtures.first { it.getString("fixture") == "magnetic-frame" }
        val magneticSession = RoomCaptureFixtures.parseSession(magnetic.getJSONObject("session"))
        assertEquals("magnetic", RoomCaptureGeometry.buildRoomCapture(magneticSession).frame.north.referenceFrame)

        // All six samples give a median of 2; dropping the two spikes gives 1.
        val samples = listOf(0.0, 1.0, 2.0, 3.0, 100.0, 101.0).map { RoomCaptureHeadingSample(it, Vec3(0.0, 0.0, -1.0)) }
        val r = RoomCaptureGeometry.northRotation(samples, "true", null)
        assertEquals(1.0, r.thetaDeg, 1e-9)
        assertTrue(r.compassConfidence > 0.9)

        val lShape = fixtures.first { it.getString("fixture") == "l-shape-traced" }
        val lShapeSession = RoomCaptureFixtures.parseSession(lShape.getJSONObject("session"))
        assertEquals(19, RoomCaptureGeometry.buildRoomCapture(lShapeSession).frame.north.yawSamples)
    }

    @Test fun floorMappingRoundingAndRingHelpers() {
        assertEquals(2.0 to -3.0, RoomCaptureGeometry.floorToPlan(Vec3(2.0, -1.4, 3.0)))
        assertEquals(0.13, RoomCaptureGeometry.roundHalfUp(0.125, 2), 0.0)
        assertEquals(0.0, RoomCaptureGeometry.roundHalfUp(-0.001, 2), 0.0)
        assertTrue("-0.0 must become +0.0, not stay a negative zero", 1.0 / RoomCaptureGeometry.roundHalfUp(-0.001, 2) > 0)
        assertEquals(
            listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0),
            RoomCaptureGeometry.simplifyRing(listOf(0.0 to 0.0, 1.0 to 0.01, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0, 0.0 to 0.0)),
        )
        assertEquals(
            listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0),
            RoomCaptureGeometry.canonicalRing(listOf(0.0 to 2.0, 2.0 to 2.0, 2.0 to 0.0, 0.0 to 0.0)),
        )
    }

    @Test fun aCaptureNeedsATracedOutline() = withFixtures { fixtures ->
        val fixture = fixtures.first { it.getString("fixture") == "room-3x4-yaw0" }
        val session = RoomCaptureFixtures.parseSession(fixture.getJSONObject("session")).copy(
            outline = RoomCaptureOutlineInput(source = "union-of-rooms", worldCorners = emptyList(), closingTap = null),
        )
        val error = assertThrows(IllegalArgumentException::class.java) { RoomCaptureGeometry.buildRoomCapture(session) }
        assertTrue(error.message.orEmpty().contains("Trace the plot or house outline"))
    }
}
