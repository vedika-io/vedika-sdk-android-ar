package io.vedika.sdk.ar

import io.vedika.sdk.VastuRoomCapture

/**
 * State [VastuArCoreCaptureView] reports as the operator walks a room.
 * Mirrors the `onState` values `mountRoomCapture` reports in the web
 * reference (`unsupported|idle|requesting|active|ended|failed|captured`),
 * plus states that only make sense once an install/permission flow is
 * involved on a native platform.
 */
enum class RoomCaptureState {
    /** [ArCoreAvailability.status] has not resolved yet. */
    CHECKING,
    /** This device cannot run ARCore. The capture flow must not be offered. */
    UNSUPPORTED,
    /** ARCore needs installing or updating; [VastuArCoreCaptureView.requestArCoreInstall] was/should be called. */
    NEEDS_INSTALL,
    /** The CAMERA runtime permission is not granted; the host app must request it and call [VastuArCoreCaptureView.start] again. */
    NEEDS_CAMERA_PERMISSION,
    /** Ready to start; [VastuArCoreCaptureView.start] has not been called yet (or the session ended). */
    IDLE,
    /** A [com.google.ar.core.Session] is running and the operator is tracing the outline or a room. */
    ACTIVE,
    /** ARCore lost tracking (see [RoomCaptureError]); the overlay is stale until tracking resumes. */
    TRACKING_LOST,
    /** The session ended before [finish] was called — nothing usable was produced. */
    ENDED,
    /** Session setup failed outright (see [RoomCaptureError]). */
    FAILED,
    /** [VastuArCoreCaptureView.finish] built a capture and handed it to [VastuArCoreCaptureListener.onCapture]. */
    CAPTURED,
}

/** Why a capture attempt could not continue. */
data class RoomCaptureError(val message: String, val cause: Throwable? = null)

/** Receives capture progress and the finished result. All callbacks run on the main thread. */
interface VastuArCoreCaptureListener {
    fun onStateChanged(state: RoomCaptureState) {}

    /** A tap placed or closed a corner, or the phase/room list otherwise changed. */
    fun onProgress(phase: RoomCaptureModel.Phase, outlineCorners: Int, rooms: Int) {}

    fun onError(error: RoomCaptureError) {}

    /** The finished `vedika.roomCapture/1`. Upload it with [VastuArCoreUploader], or send it elsewhere. */
    fun onCapture(capture: VastuRoomCapture) {}
}
