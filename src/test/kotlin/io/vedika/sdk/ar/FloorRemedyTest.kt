package io.vedika.sdk.ar

import io.vedika.sdk.VastuArPlanToWorld
import io.vedika.sdk.VastuArRoomCaptureData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FloorRemedyTest {
    private val transform = VastuArPlanToWorld("metres", listOf(10.0, 1.0, 20.0), listOf(0.0, 0.0, -1.0), listOf(-1.0, 0.0, 0.0))
    private val marker = FloorRemedyMarker("room-1", 2.0 to 3.0, "Returned advice", "Returned source", "convention")
    private val scene = FloorRemedyScene("capture-1", transform, listOf(marker))

    @Test fun metreTransformUsesCapturedNorthAxesAndFloorHeight() {
        assertEquals(Vec3(7.0, 1.0, 18.0), FloorRemedyGeometry.planToFloor(marker.point, transform))
        for (bad in listOf(
            transform.copy(units = "feet"), transform.copy(origin = listOf(Double.NaN, 0.0, 0.0)),
            transform.copy(xAxis = listOf(0.0, 0.0, -2.0)), transform.copy(yAxis = transform.xAxis),
            transform.copy(xAxis = listOf(0.0, 1.0, 0.0)), transform.copy(origin = listOf(0.0)),
        )) assertThrows(IllegalArgumentException::class.java) { FloorRemedyGeometry.planToFloor(marker.point, bad) }
        assertThrows(IllegalArgumentException::class.java) { FloorRemedyGeometry.planToFloor(Double.NaN to 0.0, transform) }
    }

    @Test fun concaveRoomsKeepPinsInsideAndRejectInvalidPolygons() {
        val polygon = listOf(listOf(0.0, 0.0), listOf(6.0, 0.0), listOf(6.0, 6.0), listOf(4.0, 6.0),
            listOf(4.0, 2.0), listOf(2.0, 2.0), listOf(2.0, 6.0), listOf(0.0, 6.0))
        assertFalse(FloorRemedyGeometry.inside(3.0 to 3.0, polygon))
        val point = FloorRemedyGeometry.interiorPoint(polygon)
        assertTrue(FloorRemedyGeometry.inside(point, polygon))
        assertEquals(point, FloorRemedyGeometry.interiorPoint(polygon.reversed()))
        for (bad in listOf(emptyList(), listOf(listOf(0.0, 0.0), listOf(1.0, 1.0), listOf(2.0, 2.0)),
            polygon.map { listOf(it[0], Double.NaN) })) {
            assertThrows(IllegalArgumentException::class.java) { FloorRemedyGeometry.interiorPoint(bad) }
        }
    }

    @Test fun consumesTheWebRemedyFieldsWithoutInventingAdviceOrSources() {
        val root = requireNotNull(RoomCaptureFixtures.monorepoRoot())
        val fixture = RoomCaptureFixtures.load(root).first()
        val capture = RoomCaptureGeometry.buildRoomCapture(RoomCaptureFixtures.parseSession(fixture.getJSONObject("session")))
        val room = capture.rooms.first()
        val defect = JSONObject().put("roomId", room.id).put("zone", "SW")
            .put("remedy", "Verbatim returned advice").put("remedySource", "modern practice (not a classical verse)")
            .put("remedyClassification", "convention")
        fun analysis(d: JSONObject) = VastuArRoomCaptureData(JSONObject()
            .put("capture", JSONObject().put("captureId", capture.captureId))
            .put("rooms", JSONArray().put(JSONObject().put("id", room.id).put("zone", "SW")))
            .put("audit", JSONObject().put("defects", JSONArray().put(d)))
            .put("planAnalysis", JSONObject().put("defects", JSONArray().put(d))))
        val built = FloorRemedyScene.from(capture, analysis(defect))
        assertEquals(1, built.markers.size) // audit + planAnalysis describe the same remedy
        val pin = built.markers.single()
        assertEquals(room.id, pin.roomId)
        assertEquals("Verbatim returned advice", pin.remedy)
        assertEquals("modern practice (not a classical verse)", pin.source)
        assertEquals("convention", pin.classification)
        assertTrue(FloorRemedyGeometry.inside(pin.point, room.polygon))
        defect.remove("roomId"); defect.put("room", room.label)
        assertEquals(room.id, FloorRemedyScene.from(capture, analysis(defect)).markers.single().roomId)
        defect.put("room", "Unknown room"); defect.remove("remedySource")
        val global = FloorRemedyScene.from(capture, analysis(defect)).markers.single()
        assertNull(global.roomId); assertNull(global.source)
        assertTrue(FloorRemedyGeometry.inside(global.point, capture.outline.polygon))
        defect.remove("remedy")
        assertTrue(FloorRemedyScene.from(capture, analysis(defect)).markers.isEmpty())
        val wrong = analysis(defect).raw
        wrong.getJSONObject("capture").put("captureId", "unrelated-capture")
        assertThrows(IllegalArgumentException::class.java) { FloorRemedyScene.from(capture, VastuArRoomCaptureData(wrong)) }
    }

    private class Anchor : RemedyFloorAnchor {
        override var tracking = true
        var pose = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 10f, 1f, 20f, 1f)
        var detached = 0
        override fun matrix() = pose.copyOf()
        override fun detach() { detached++; tracking = false }
    }

    @Test fun missingFloorTrackingLossAndClearHidePinsAndDetachExactlyOnce() {
        val controller = FloorRemedyController()
        assertFalse(controller.setScene(scene))
        assertNull(controller.draw(true))
        val anchor = Anchor()
        controller.attach(anchor, Vec3(10.0, 1.0, 20.0))
        assertTrue(controller.setScene(scene))
        repeat(30) {
            assertNotNull(controller.draw(true))
            assertNull(controller.draw(false))
            anchor.tracking = false; assertNull(controller.draw(true))
            anchor.tracking = true; assertNotNull(controller.draw(true))
        }
        anchor.pose[12] = Float.NaN; assertNull(controller.draw(true))
        controller.clear(); controller.clear()
        assertEquals(1, anchor.detached)
        assertNull(controller.draw(true))
        assertFalse(controller.setScene(scene))
    }

    @Test fun anchorCorrectionMovesTheEntireFloorMeshAndNewSessionStartsEmpty() {
        val controller = FloorRemedyController(); val anchor = Anchor()
        controller.attach(anchor, Vec3(10.0, 1.0, 20.0)); controller.setScene(scene)
        val first = controller.draw(true)!!
        assertTrue(first.vertices.isNotEmpty())
        assertTrue((1 until first.vertices.size step 3).all { abs(first.vertices[it] - .003f) < 1e-6f })
        anchor.pose[12] = 13f; anchor.pose[14] = 15f
        val moved = controller.draw(true)!!
        assertArrayEquals(first.vertices, moved.vertices, 0f)
        assertEquals(3f, moved.modelMatrix[12] - first.modelMatrix[12], 0f)
        assertEquals(-5f, moved.modelMatrix[14] - first.modelMatrix[14], 0f)
        val replacement = Anchor()
        controller.attach(replacement, Vec3(0.0, 0.0, 0.0))
        assertEquals(1, anchor.detached)
        assertNull(controller.draw(true)) // old positions cannot survive the session identity
        controller.clear(); assertEquals(1, replacement.detached)
    }
}
