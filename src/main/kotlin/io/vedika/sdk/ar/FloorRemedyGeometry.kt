package io.vedika.sdk.ar

import io.vedika.sdk.VastuArPlanToWorld
import io.vedika.sdk.VastuArRoomCaptureData
import io.vedika.sdk.VastuRoomCapture
import org.json.JSONObject
import kotlin.math.abs

/** An API remedy and its source, positioned inside the associated room. */
data class FloorRemedyMarker(
    val roomId: String?,
    val point: Pair<Double, Double>,
    val remedy: String,
    val source: String?,
    val classification: String?,
)

/** Same room/zone/remedy fields as the web floor plan. No advice is generated. */
data class FloorRemedyScene(
    val captureId: String,
    val transform: VastuArPlanToWorld,
    val markers: List<FloorRemedyMarker>,
) {
    companion object {
        fun from(capture: VastuRoomCapture, analysis: VastuArRoomCaptureData): FloorRemedyScene {
            require(analysis.capture.captureId == capture.captureId) { "Analysis belongs to another capture" }
            val transform = requireNotNull(capture.frame.planToWorld) { "Capture has no floor transform" }
            FloorRemedyGeometry.planToFloor(0.0 to 0.0, transform)
            FloorRemedyGeometry.interiorPoint(capture.outline.polygon)
            val data = analysis.raw
            val returned = data.optJSONArray("rooms")
            val analyzedRooms = (0 until (returned?.length() ?: 0)).associate { i ->
                val room = returned!!.getJSONObject(i)
                room.getString("id") to room
            }
            val markers = linkedSetOf<FloorRemedyMarker>()
            for (section in listOf("audit", "planAnalysis")) {
                val defects = data.optJSONObject(section)?.optJSONArray("defects") ?: continue
                require(defects.length() <= 1024) { "Too many placement notes" }
                for (i in 0 until defects.length()) {
                    val defect = defects.getJSONObject(i)
                    val remedy = defect.text("remedy") ?: continue
                    val roomId = defect.text("roomId") ?: defect.text("room_id")
                    val roomName = defect.text("room") ?: defect.text("roomType") ?: defect.text("roomName")
                    val zone = defect.text("zone")
                    val rooms = capture.rooms.filter { room ->
                        if (roomId != null) room.id == roomId
                        else roomName != null && normalized(roomName) in listOf(
                            normalized(room.label.orEmpty()), normalized(analyzedRooms[room.id]?.optString("roomType").orEmpty()),
                        ) && (zone == null || analyzedRooms[room.id]?.optString("zone") == zone)
                    }
                    fun marker(id: String?, polygon: List<List<Double>>) = FloorRemedyMarker(
                        id, FloorRemedyGeometry.interiorPoint(polygon), remedy,
                        defect.text("remedySource"), defect.text("remedyClassification"),
                    )
                    if (rooms.isEmpty()) markers += marker(null, capture.outline.polygon)
                    else rooms.forEach { markers += marker(it.id, it.polygon) }
                }
            }
            return FloorRemedyScene(capture.captureId, transform, markers.toList())
        }

        private fun normalized(text: String) = text.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
        private fun JSONObject.text(key: String): String? =
            (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Metre-based floor geometry; intentionally independent of Android and ARCore. */
object FloorRemedyGeometry {
    fun planToFloor(point: Pair<Double, Double>, transform: VastuArPlanToWorld): Vec3 {
        require(point.first.isFinite() && point.second.isFinite()) { "Invalid plan point" }
        val axes = listOf(transform.origin, transform.xAxis, transform.yAxis)
        require(transform.units == "metres" && axes.all { it.size == 3 && it.all(Double::isFinite) }) { "Invalid floor transform" }
        fun dot(a: List<Double>, b: List<Double>) = a.indices.sumOf { a[it] * b[it] }
        require(abs(dot(transform.xAxis, transform.xAxis) - 1) < 1e-5 &&
            abs(dot(transform.yAxis, transform.yAxis) - 1) < 1e-5 &&
            abs(dot(transform.xAxis, transform.yAxis)) < 1e-5 &&
            abs(transform.xAxis[1]) < 1e-5 && abs(transform.yAxis[1]) < 1e-5) { "Floor axes must be horizontal and orthonormal" }
        val world = (0..2).map { transform.origin[it] + point.first * transform.xAxis[it] + point.second * transform.yAxis[it] }
        require(world.all { it.isFinite() && it.toFloat().isFinite() }) { "Invalid floor position" }
        return Vec3(world[0], world[1], world[2])
    }

    fun inside(point: Pair<Double, Double>, polygon: List<List<Double>>): Boolean {
        var inside = false
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val cross = (b[0] - a[0]) * (point.second - a[1]) - (b[1] - a[1]) * (point.first - a[0])
            if (abs(cross) < 1e-7 && point.first >= minOf(a[0], b[0]) - 1e-7 &&
                point.first <= maxOf(a[0], b[0]) + 1e-7 && point.second >= minOf(a[1], b[1]) - 1e-7 &&
                point.second <= maxOf(a[1], b[1]) + 1e-7) return true
            if ((a[1] > point.second) != (b[1] > point.second) &&
                point.first < (b[0] - a[0]) * (point.second - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
        }
        return inside
    }

    /** Area centroid, falling back to an interior horizontal span for concave rooms. */
    fun interiorPoint(polygon: List<List<Double>>): Pair<Double, Double> {
        require(polygon.size in 3..256 && polygon.all { it.size == 2 && it.all(Double::isFinite) }) { "Invalid room polygon" }
        var area = 0.0; var x = 0.0; var y = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val cross = a[0] * b[1] - b[0] * a[1]
            area += cross; x += (a[0] + b[0]) * cross; y += (a[1] + b[1]) * cross
        }
        require(area.isFinite() && abs(area) >= 1e-8) { "Room has no measurable area" }
        val centroid = x / (3 * area) to y / (3 * area)
        if (inside(centroid, polygon)) return centroid
        val minY = polygon.minOf { it[1] }; val maxY = polygon.maxOf { it[1] }
        for (scanY in listOf(centroid.second) + (1..19).map { minY + it * (maxY - minY) / 20 }) {
            val cuts = polygon.indices.mapNotNull { i ->
                val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                if ((a[1] > scanY) == (b[1] > scanY)) null
                else a[0] + (scanY - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
            }.sorted()
            val span = cuts.chunked(2).filter { it.size == 2 && it[1] - it[0] > 1e-8 }.maxByOrNull { it[1] - it[0] }
            if (span != null) return (span[0] + span[1]) / 2 to scanY
        }
        error("No interior remedy position")
    }

    /** Floor pins relative to the original translation-only ARCore anchor. */
    fun anchorVertices(scene: FloorRemedyScene, origin: Vec3): FloatArray = scene.markers.flatMap { marker ->
        val world = planToFloor(marker.point, scene.transform)
        val center = Vec3(world.x - origin.x, world.y - origin.y, world.z - origin.z)
        RoomCaptureRenderGeometry.ring(center, radius = 0.14, width = 0.035).toList()
    }.toFloatArray().also { require(it.all(Float::isFinite)) { "Invalid remedy vertices" } }
}
