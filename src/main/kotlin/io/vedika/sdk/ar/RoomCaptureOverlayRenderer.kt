package io.vedika.sdk.ar

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the traced outline/room ribbons and the hit-test reticle over the
 * camera background, using [RoomCaptureRenderGeometry]'s flat triangles and
 * ARCore's own view/projection matrices. A minimal GLES2 line-of-triangles
 * renderer — no lighting, no depth test against the scene (drawn on top,
 * like the web reference's WebXR overlay) — visual polish is intentionally
 * out of scope for a first native cut; see the module README.
 */
class RoomCaptureOverlayRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        /** Amber outline, matching the web reference's `ribbon`/`ring` overlay color. */
        val OUTLINE_COLOR = floatArrayOf(1f, 0.85f, 0.2f, 0.95f)

        /** White reticle at the current floor hit-test point. */
        val RETICLE_COLOR = floatArrayOf(1f, 1f, 1f, 0.95f)
    }

    /** Call once from `onSurfaceCreated`. */
    fun createOnGlThread() {
        val vertexShader = ShaderUtil.compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderUtil.compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        positionAttrib = GLES20.glGetAttribLocation(program, "aPosition")
        mvpUniform = GLES20.glGetUniformLocation(program, "uMvp")
        colorUniform = GLES20.glGetUniformLocation(program, "uColor")
    }

    /**
     * Draws the current outline (or active room) plus a reticle, if any.
     * [viewMatrix]/[projectionMatrix] come straight from `Camera.getViewMatrix`
     * / `Camera.getProjectionMatrix`.
     */
    fun draw(
        outlineCorners: List<Vec3>,
        outlineClosed: Boolean,
        reticle: Vec3?,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
    ) {
        if (outlineCorners.isEmpty() && reticle == null) return
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, vp, 0)

        if (outlineCorners.isNotEmpty()) {
            drawTriangles(RoomCaptureRenderGeometry.loopVertices(outlineCorners, outlineClosed), OUTLINE_COLOR)
        }
        if (reticle != null) {
            drawTriangles(RoomCaptureRenderGeometry.ring(reticle), RETICLE_COLOR)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawTriangles(vertices: FloatArray, color: FloatArray) {
        if (vertices.isEmpty()) return
        if (buffer.capacity() < vertices.size) {
            buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        buffer.clear()
        buffer.put(vertices)
        buffer.position(0)
        GLES20.glUniform4fv(colorUniform, 1, color, 0)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / 3)
        GLES20.glDisableVertexAttribArray(positionAttrib)
    }
}
