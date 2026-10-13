package io.vedika.sdk.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.UUID
import kotlin.math.hypot

/** Pure gate shared by live rendering and JVM refusal tests. Thresholds are
 * policy, not measured accuracy. Every lost/missing/outlying frame revokes. */
class OverlayRelocalizationGate(private val maximumErrorPixels: Double = 8.0, private val requiredFrames: Int = 3) {
    var overlaysAllowed = false; private set
    private var passing = 0
    fun reset() { passing = 0; overlaysAllowed = false }
    fun observe(tracking: Boolean, expected: Set<String>, restored: Set<String>, errors: List<Double>): Boolean {
        if (!maximumErrorPixels.isFinite() || maximumErrorPixels <= 0 || requiredFrames < 1 || !tracking || expected.isEmpty() || !restored.containsAll(expected) ||
            errors.size != expected.size || errors.any { !it.isFinite() || it < 0 || it > maximumErrorPixels }) { reset(); return false }
        passing++; overlaysAllowed = passing >= requiredFrames; return overlaysAllowed
    }
}

/** Local ARCore anchors only. This identity is valid for ONE Session; a process
 * restart has no stored map and must rescan. No claim of cross-session restore. */
class LocalOverlayRelocalizer(private val session: Session) {
    private val anchors = linkedMapOf<String, Anchor>()
    private val gate = OverlayRelocalizationGate()
    val sessionId: String = UUID.randomUUID().toString()
    init {
        val config = session.config
        config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        config.geospatialMode = Config.GeospatialMode.DISABLED
        session.configure(config)
    }
    fun add(pose: Pose): String {
        gate.reset(); val id = UUID.randomUUID().toString(); anchors[id] = session.createAnchor(pose); return id
    }
    fun clear() { anchors.values.forEach { it.detach() }; anchors.clear(); gate.reset() }
    /** Observations must be current physical landmark taps/detector positions,
     * not cached screen coordinates. Call on each GL frame before rendering. */
    fun evaluate(frame: Frame, width: Int, height: Int, observedPixels: Map<String, Pair<Float, Float>>): Boolean {
        if (width <= 0 || height <= 0 || observedPixels.keys != anchors.keys) { gate.reset(); return false }
        val camera = frame.camera; val view = FloatArray(16); val projection = FloatArray(16)
        camera.getViewMatrix(view, 0); camera.getProjectionMatrix(projection, 0, .05f, 100f)
        fun multiply(matrix: FloatArray, v: FloatArray) = FloatArray(4) { row -> (0..3).sumOf { column -> (matrix[column * 4 + row] * v[column]).toDouble() }.toFloat() }
        val errors = anchors.map { (id, anchor) ->
            val p = anchor.pose.translation; val clip = multiply(projection, multiply(view, floatArrayOf(p[0], p[1], p[2], 1f)))
            if (clip.any { !it.isFinite() } || clip[3] <= 0 || kotlin.math.abs(clip[0]) > clip[3] || kotlin.math.abs(clip[1]) > clip[3] || kotlin.math.abs(clip[2]) > clip[3]) Double.POSITIVE_INFINITY
            else {
                val x = (clip[0] / clip[3] + 1) * width / 2; val y = (1 - clip[1] / clip[3]) * height / 2
                val observed = observedPixels.getValue(id)
                if (!observed.first.isFinite() || !observed.second.isFinite() || observed.first !in 0f..width.toFloat() || observed.second !in 0f..height.toFloat()) Double.POSITIVE_INFINITY
                else hypot((x - observed.first).toDouble(), (y - observed.second).toDouble())
            }
        }
        val tracked = anchors.filterValues { it.trackingState == TrackingState.TRACKING }.keys
        return gate.observe(camera.trackingState == TrackingState.TRACKING, anchors.keys, tracked, errors)
    }
}
