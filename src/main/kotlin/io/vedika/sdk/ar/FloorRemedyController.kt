package io.vedika.sdk.ar

/** A small native seam: production wraps one local ARCore Anchor; JVM tests
 * supply tracked poses. Nothing is restored into a different Session. */
internal interface RemedyFloorAnchor {
    val tracking: Boolean
    fun matrix(): FloatArray
    fun detach()
}

/** Render-thread owner. Missing/lost floor or camera tracking means no draw. */
internal class FloorRemedyController {
    data class Draw(val vertices: FloatArray, val modelMatrix: FloatArray)
    private var anchor: RemedyFloorAnchor? = null
    private var origin: Vec3? = null
    private var vertices = FloatArray(0)

    fun attach(floor: RemedyFloorAnchor, initialOrigin: Vec3) {
        clear()
        anchor = floor
        origin = initialOrigin
    }

    fun setScene(scene: FloorRemedyScene): Boolean {
        hide()
        val initial = origin ?: return false
        if (anchor == null) return false
        vertices = FloorRemedyGeometry.anchorVertices(scene, initial)
        return true
    }

    fun draw(cameraTracking: Boolean): Draw? {
        val floor = anchor ?: return null
        if (!cameraTracking || !floor.tracking || vertices.isEmpty()) return null
        val matrix = floor.matrix()
        if (matrix.size != 16 || matrix.any { !it.isFinite() }) return null
        return Draw(vertices, matrix)
    }

    fun hide() { vertices = FloatArray(0) }
    fun clear() {
        hide()
        anchor?.detach()
        anchor = null
        origin = null
    }
}
