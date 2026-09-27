package io.vedika.sdk.ar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure floor-drawing geometry: flat triangle strips ("ribbons") along traced
 * walls and small rings at each corner/reticle, so the outline stays visible
 * on a phone screen from any AR viewing angle. A Kotlin port of the same
 * helpers in `web/vedika-public/js/vastu/room-capture-webxr.js`
 * (`ribbon`/`ring`), used by [RoomCaptureOverlayRenderer]. Kept dependency-free
 * (no GLES types) so it is unit-testable on a bare JVM like the rest of this
 * package.
 */
object RoomCaptureRenderGeometry {

    /** Triangles (as a flat x,y,z,... array) for a 2 cm wide strip along the floor from a to b. */
    fun ribbon(a: Vec3, b: Vec3, width: Double = 0.02): FloatArray {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dz * dz).let { if (it == 0.0) 1.0 else it }
        val ox = (-dz / len) * (width / 2.0)
        val oz = (dx / len) * (width / 2.0)
        val y1 = a.y + 0.003
        val y2 = b.y + 0.003
        val p1 = doubleArrayOf(a.x + ox, y1, a.z + oz)
        val p2 = doubleArrayOf(a.x - ox, y1, a.z - oz)
        val p3 = doubleArrayOf(b.x + ox, y2, b.z + oz)
        val p4 = doubleArrayOf(b.x - ox, y2, b.z - oz)
        return floatArrayOfAll(p1, p2, p3, p2, p4, p3)
    }

    /** Triangles for a flat ring around [c]. */
    fun ring(c: Vec3, radius: Double = 0.08, width: Double = 0.015, segments: Int = 24): FloatArray {
        val out = mutableListOf<Float>()
        val y = c.y + 0.003
        fun pt(r: Double, a: Double) = doubleArrayOf(c.x + r * cos(a), y, c.z + r * sin(a))
        for (i in 0 until segments) {
            val a0 = (i.toDouble() / segments) * PI * 2
            val a1 = ((i + 1).toDouble() / segments) * PI * 2
            val inner = radius - width / 2.0
            val outer = radius + width / 2.0
            val q0 = pt(inner, a0)
            val q1 = pt(outer, a0)
            val q2 = pt(inner, a1)
            val q3 = pt(outer, a1)
            out.addAll(floatArrayOfAll(q0, q1, q2, q1, q3, q2).toList())
        }
        return out.toFloatArray()
    }

    private fun floatArrayOfAll(vararg points: DoubleArray): FloatArray {
        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            out[i++] = p[0].toFloat()
            out[i++] = p[1].toFloat()
            out[i++] = p[2].toFloat()
        }
        return out
    }

    /** Triangles for every ribbon + corner ring in a traced loop. */
    fun loopVertices(corners: List<Vec3>, closed: Boolean): FloatArray {
        val out = mutableListOf<Float>()
        for (i in 1 until corners.size) out.addAll(ribbon(corners[i - 1], corners[i]).toList())
        if (closed && corners.size > 2) out.addAll(ribbon(corners.last(), corners.first()).toList())
        for (c in corners) out.addAll(ring(c, 0.03, 0.02, 12).toList())
        return out.toFloatArray()
    }

    /**
     * The camera's forward vector from ARCore's `Pose.getZAxis(float[])`: the
     * pose's local +Z axis expressed in world space, which points OUT of the
     * screen toward the viewer — forward is its negation. Same convention as
     * `forwardFromMatrix` in the web reference (there, columns 8..10 of a
     * column-major view matrix are the pose's Z axis; both read out the
     * identical quantity from their platform's own pose representation).
     */
    fun forwardFromZAxis(zAxis: FloatArray): Vec3 = Vec3(-zAxis[0].toDouble(), -zAxis[1].toDouble(), -zAxis[2].toDouble())
}
