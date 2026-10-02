package io.vedika.sdk.ar

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Walk a room with ARCore, tap floor corners, and produce a
 * `vedika.roomCapture/1` — the native equivalent of
 * `mountRoomCapture`/`createCaptureModel` in
 * `web/vedika-public/js/vastu/room-capture-webxr.js`. See that file's header
 * and the module README for what a caller-reported capture does and does not
 * claim; this class adds nothing to that contract, it only collects the same
 * inputs from ARCore instead of WebXR.
 *
 * ## Usage
 * ```kotlin
 * val captureView = VastuArCoreCaptureView(context)
 * container.addView(captureView)
 * captureView.listener = object : VastuArCoreCaptureListener {
 *     override fun onCapture(capture: VastuRoomCapture) { /* upload it, see VastuArCoreUploader */ }
 * }
 * // in the hosting Activity/Fragment lifecycle:
 * override fun onResume() { captureView.start() }
 * override fun onPause() { captureView.stop() }
 * override fun onDestroy() { captureView.destroy() }
 * ```
 * [start] checks [ArCoreAvailability] and the CAMERA permission itself and
 * reports [RoomCaptureState.NEEDS_INSTALL] / [RoomCaptureState.NEEDS_CAMERA_PERMISSION]
 * rather than throwing — the host app owns the install/permission UI (see
 * [ArCoreAvailability]'s class doc for why this module never launches either
 * itself).
 *
 * ## Record/replay seam (the AR test lab)
 * [sessionHook] runs once, after `Session.configure()` and before the first
 * `resume()` — the one moment ARCore lets a caller attach a recording
 * (`Session.startRecording`) or a playback dataset
 * (`Session.setPlaybackDatasetUri`). [recordingTrackId] / [playbackTrackId]
 * turn on this view's own event track (every tap, north-hold and
 * add-room/finish), written with `Frame.recordTrackData` and read back with
 * `Frame.getUpdatedTrackData` — camera + IMU + depth alone cannot replay this
 * flow because nothing on played-back video taps anything; Vedika's
 * acceptance suite records and replays it on device.
 */
class VastuArCoreCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /**
     * Receives state, progress, errors and the finished capture. A listener
     * attached after [start] immediately receives the current state, so a host
     * that wires it late does not miss NEEDS_CAMERA_PERMISSION or ACTIVE.
     */
    var listener: VastuArCoreCaptureListener? = null
        set(value) {
            field = value
            val current = currentState ?: return
            mainHandler.post { if (listener === value) value?.onStateChanged(current) }
        }

    @Volatile private var currentState: RoomCaptureState? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glSurfaceView = GLSurfaceView(context)
    private val statusText = TextView(context).apply { setPadding(24, 16, 24, 16) }
    private val undoButton = Button(context).apply { text = "Undo" }
    private val addRoomButton = Button(context).apply { text = "Add room" }
    private val northButton = Button(context).apply { text = "This way is north" }
    private val finishButton = Button(context).apply { text = "Finish" }

    /** Set by the host BEFORE tapping [addRoomButton] (e.g. from a spinner) — see the README for a wiring example. */
    var pendingRoomLabel: String? = null

    /**
     * Runs once with the freshly configured [Session], after `configure()`
     * and before the first `resume()`. Wire ARCore recording or playback
     * here (see class doc's "Record/replay seam"). Must be set before
     * calling [start].
     */
    var sessionHook: ((Session) -> Unit)? = null

    /** Non-null makes [start] write every tap/north/room event to this ARCore track for a recording. */
    @Volatile var recordingTrackId: UUID? = null

    /** Non-null makes [start] read and re-apply every recorded event from this track during ARCore playback. */
    @Volatile var playbackTrackId: UUID? = null

    private val pendingTrackEvents = ConcurrentLinkedQueue<String>()

    @Volatile private var session: Session? = null
    @Volatile private var model: RoomCaptureModel? = null
    @Volatile private var reticle: Vec3? = null
    @Volatile private var holdingNorth = false
    @Volatile private var depthAvailable = false
    @Volatile private var reportedTrackingLostOnce = false
    @Volatile private var lastFrame: Frame? = null
    private var installRequested = false
    private var pointCloudFrameCounter = 0

    private val backgroundRenderer = CameraBackgroundRenderer()
    private val overlayRenderer = RoomCaptureOverlayRenderer()
    private var viewportWidth = 1
    private var viewportHeight = 1

    init {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(InternalRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        addView(glSurfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        for (b in listOf(undoButton, addRoomButton, northButton, finishButton)) controls.addView(b)
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(controls)
        }
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.BOTTOM },
        )

        undoButton.setOnClickListener { runOnGl { recordEvent("UNDO"); model?.undo(); reportProgress() } }
        addRoomButton.setOnClickListener {
            runOnGl {
                try {
                    recordEvent("ADDROOM ${pendingRoomLabel.orEmpty()}")
                    model?.startRoom(pendingRoomLabel)
                    reportProgress()
                } catch (e: IllegalStateException) {
                    postError(e.message ?: "Could not start a room")
                }
            }
        }
        finishButton.setOnClickListener { runOnGl { recordEvent("FINISH"); finishOnGlThread() } }
        northButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> runOnGl { recordEvent("NORTH_DOWN"); model?.resetNorth(); holdingNorth = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { recordEvent("NORTH_UP"); holdingNorth = false }
            }
            true
        }

        @SuppressLint("ClickableViewAccessibility")
        val tapListener = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) runOnGl { recordEvent("TAP"); onSelect() }
            true
        }
        glSurfaceView.setOnTouchListener(tapListener)
        setState(RoomCaptureState.CHECKING)
    }

    private fun runOnGl(block: () -> Unit) = glSurfaceView.queueEvent(block)

    private fun setState(state: RoomCaptureState) {
        currentState = state
        mainHandler.post { listener?.onStateChanged(state) }
    }

    private fun postError(message: String, cause: Throwable? = null) =
        mainHandler.post { listener?.onError(RoomCaptureError(message, cause)) }

    private fun reportProgress() {
        val m = model ?: return
        val phase = m.currentPhase
        val outlineCount = m.outlineCorners.size
        val roomCount = m.roomSummaries.size
        mainHandler.post { listener?.onProgress(phase, outlineCount, roomCount) }
    }

    /** Queues one line for [recordingTrackId]'s ARCore track; drained onto the next frame in [InternalRenderer.onDrawFrame]. No-op unless recording. */
    private fun recordEvent(line: String) {
        if (recordingTrackId != null) pendingTrackEvents.add(line)
    }

    /**
     * Checks ARCore availability and camera permission, then creates and
     * resumes a [Session]. Call from `onResume()`; safe to call again after
     * the host app resolves an install or a permission request (see class
     * doc). No-op if a session is already running.
     */
    fun start() {
        if (session != null) return
        when (val status = ArCoreAvailability.status(context)) {
            ArCoreAvailability.Status.UNSUPPORTED -> { setState(RoomCaptureState.UNSUPPORTED); return }
            ArCoreAvailability.Status.UNKNOWN -> { setState(RoomCaptureState.CHECKING); return }
            ArCoreAvailability.Status.SUPPORTED_NEEDS_INSTALL -> { setState(RoomCaptureState.NEEDS_INSTALL); return }
            ArCoreAvailability.Status.SUPPORTED_INSTALLED -> Unit
        }
        if (!ArCoreAvailability.hasCameraPermission(context)) {
            setState(RoomCaptureState.NEEDS_CAMERA_PERMISSION)
            return
        }
        try {
            val newSession = Session(context)
            val config = Config(newSession)
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            depthAvailable = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            config.depthMode = if (depthAvailable) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            newSession.configure(config)
            sessionHook?.invoke(newSession)
            session = newSession
            model = RoomCaptureModel(captureId = "android-${System.currentTimeMillis()}")
            glSurfaceView.onResume()
            newSession.resume()
            setState(RoomCaptureState.ACTIVE)
        } catch (e: UnavailableException) {
            postError("Could not start the AR session: ${e.message}", e)
            setState(RoomCaptureState.FAILED)
        } catch (e: CameraNotAvailableException) {
            postError("The camera is in use by another app.", e)
            setState(RoomCaptureState.FAILED)
        }
    }

    /**
     * Requests the ARCore install/update flow from an `Activity.onResume()`
     * when [start] reports [RoomCaptureState.NEEDS_INSTALL]. Call [start]
     * again after the Activity is recreated (or immediately if this returns
     * [ArCoreAvailability.InstallOutcome.Installed]).
     */
    fun requestArCoreInstall(activity: android.app.Activity): ArCoreAvailability.InstallOutcome {
        val outcome = ArCoreAvailability.requestInstall(activity, !installRequested)
        installRequested = true
        if (outcome is ArCoreAvailability.InstallOutcome.UserDeclined) {
            postError("The AR view did not open. Nothing else on this page changed.")
            setState(RoomCaptureState.UNSUPPORTED)
        } else if (outcome is ArCoreAvailability.InstallOutcome.Unsupported) {
            postError(outcome.reason)
            setState(RoomCaptureState.UNSUPPORTED)
        }
        return outcome
    }

    /** Pauses the [Session]. Call from `onPause()` — every pause path, no exceptions. */
    fun stop() {
        session?.pause()
        glSurfaceView.onPause()
    }

    /** Stops an active ARCore recording (see [recordingTrackId]) without pausing the session. Safe to call when not recording. */
    fun stopRecording() {
        try {
            session?.stopRecording()
        } catch (_: Exception) {
            // Not recording, or the session already tore down — nothing to do.
        }
    }

    /**
     * Releases the [Session]. Call from `onDestroy()` — and also from the AR
     * test lab's replay test between datasets: this resets every bit of
     * per-session state ([reticle], [holdingNorth], [reportedTrackingLostOnce],
     * [lastFrame]) so a fresh [start] after [destroy] is a clean session, not
     * one carrying a stray flag from the dataset before it.
     */
    fun destroy() {
        stopRecording()
        session?.close()
        session = null
        model = null
        reticle = null
        holdingNorth = false
        reportedTrackingLostOnce = false
        lastFrame = null
    }

    private fun onSelect() {
        val m = model ?: return
        if (!m.tracing || holdingNorth) return
        val point = reticle ?: return
        try {
            m.tap(point)
        } catch (e: IllegalStateException) {
            postError(e.message ?: "Could not place that corner")
            return
        }
        reportProgress()
    }

    private fun finishOnGlThread() {
        val m = model ?: return
        try {
            val device = RoomCaptureDeviceInput(
                platform = "android",
                method = "arcore-hit",
                depth = if (depthAvailable) "arcore-depth" else "none",
            )
            val capture = m.build(device = device)
            mainHandler.post { listener?.onCapture(capture) }
            setState(RoomCaptureState.CAPTURED)
        } catch (e: IllegalStateException) {
            postError(e.message ?: "Could not build the capture")
        }
    }

    /**
     * Applies one recorded event line (see [recordEvent]'s format) exactly as
     * the real button/touch handlers would, for ARCore playback replaying
     * [playbackTrackId]. Called on the GL thread from [InternalRenderer.onDrawFrame].
     */
    private fun applyRecordedEvent(line: String) {
        val space = line.indexOf(' ')
        val head = if (space < 0) line else line.substring(0, space)
        val rest = if (space < 0) "" else line.substring(space + 1)
        when (head) {
            "TAP" -> onSelect()
            "NORTH_DOWN" -> { model?.resetNorth(); holdingNorth = true }
            "NORTH_UP" -> holdingNorth = false
            "ADDROOM" -> {
                try {
                    model?.startRoom(rest.ifEmpty { null })
                    reportProgress()
                } catch (e: IllegalStateException) {
                    postError(e.message ?: "Could not start a room (replay)")
                }
            }
            "FINISH" -> finishOnGlThread()
            "UNDO" -> { model?.undo(); reportProgress() }
        }
    }

    /** Ray-casts [x], [y] on [frame], accepting the same trackable types a real tap does. */
    private fun hitTestAt(frame: Frame, x: Float, y: Float): Vec3? {
        val hits = frame.hitTest(x, y)
        for (hit in hits) {
            val trackable = hit.trackable
            val usable = when (trackable) {
                is Plane -> trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING && trackable.isPoseInPolygon(hit.hitPose)
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                // Only ever returned when depth is enabled, but check explicitly so intent reads at the call site.
                is DepthPoint -> depthAvailable
                else -> false
            }
            if (usable) {
                val pose = hit.hitPose
                return Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
            }
        }
        return null
    }

    /** Ray-casts the screen center each frame, like the web reference's viewer-space hit-test source. */
    private fun updateReticle(frame: Frame) {
        reticle = hitTestAt(frame, viewportWidth / 2f, viewportHeight / 2f)
    }

    /**
     * Accumulates ARCore's sparse feature-point cloud into [model] so a good
     * capture reports a real `quality.pointCloudDensity` (see
     * [RoomCaptureQuality]) instead of always `null`. Independent of
     * [depthAvailable] — `Frame.acquirePointCloud()` is not the Depth API.
     * Throttled to every third frame; the point cloud barely changes frame
     * to frame and this is easily the most expensive per-frame call here.
     */
    private fun accumulateFeaturePoints(frame: Frame) {
        pointCloudFrameCounter++
        if (pointCloudFrameCounter % 3 != 0) return
        try {
            frame.acquirePointCloud().use { cloud ->
                val ids = cloud.ids
                val points = cloud.points
                val count = ids.remaining()
                if (count == 0) return@use
                val idArray = IntArray(count)
                val pts = ArrayList<Vec3>(count)
                for (i in 0 until count) {
                    idArray[i] = ids.get(i)
                    val base = i * 4
                    pts.add(Vec3(points.get(base).toDouble(), points.get(base + 1).toDouble(), points.get(base + 2).toDouble()))
                }
                model?.recordFeaturePoints(idArray, pts)
            }
        } catch (_: NotYetAvailableException) {
            // No point cloud for this frame yet — try again on the next one.
        }
    }

    private fun trackingLostReason(camera: com.google.ar.core.Camera): String = when (camera.trackingFailureReason) {
        TrackingFailureReason.BAD_STATE -> "Tracking lost: internal AR error."
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Tracking lost: not enough light."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Tracking lost: the phone moved too fast."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Tracking lost: point the camera at a more textured surface."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Tracking lost: camera unavailable."
        else -> "Tracking lost."
    }

    private inner class InternalRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            backgroundRenderer.createOnGlThread()
            overlayRenderer.createOnGlThread()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            GLES20.glViewport(0, 0, width, height)
            session?.setDisplayGeometry(display?.rotation ?: 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val activeSession = session ?: return
            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = try {
                activeSession.update()
            } catch (e: CameraNotAvailableException) {
                postError("Camera became unavailable mid-session.", e)
                return
            }
            lastFrame = frame
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                if (camera.trackingState == TrackingState.PAUSED && !reportedTrackingLostOnce) {
                    reportedTrackingLostOnce = true
                    setState(RoomCaptureState.TRACKING_LOST)
                    postError(trackingLostReason(camera))
                }
                return
            }
            if (reportedTrackingLostOnce) {
                reportedTrackingLostOnce = false
                setState(RoomCaptureState.ACTIVE)
            }
            updateReticle(frame)
            accumulateFeaturePoints(frame)

            recordingTrackId?.let { trackId ->
                var line = pendingTrackEvents.poll()
                while (line != null) {
                    try {
                        frame.recordTrackData(trackId, ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
                    } catch (_: Exception) {
                        // Not actually recording (e.g. host set the id but never started it) — drop it.
                    }
                    line = pendingTrackEvents.poll()
                }
            }
            playbackTrackId?.let { trackId ->
                for (trackData in frame.getUpdatedTrackData(trackId)) {
                    try {
                        val buffer = trackData.data
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        applyRecordedEvent(String(bytes, Charsets.UTF_8))
                    } finally {
                        trackData.close()
                    }
                }
            }

            if (holdingNorth) {
                val zAxis = camera.pose.zAxis
                model?.addNorthSample(RoomCaptureRenderGeometry.forwardFromZAxis(zAxis), System.currentTimeMillis())
            }

            val viewMatrix = FloatArray(16)
            val projectionMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 50f)
            val m = model
            overlayRenderer.draw(
                outlineCorners = m?.outlineCorners.orEmpty(),
                outlineClosed = m?.currentPhase == RoomCaptureModel.Phase.ROOMS,
                reticle = reticle,
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix,
            )
        }
    }
}
