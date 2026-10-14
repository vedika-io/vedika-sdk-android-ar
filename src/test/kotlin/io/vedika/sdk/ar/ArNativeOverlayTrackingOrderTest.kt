package io.vedika.sdk.ar

import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [dispatchRelocalizedOverlay], the boundary `onDrawFrame` calls right
 * after the camera background is drawn, with the real ARCore [TrackingState]
 * and recorded gate/render callbacks. A frame whose camera is PAUSED or
 * STOPPED must never reach the renderer, even when the gate says yes, and the
 * gate must still see every frame so it can reset itself on tracking loss.
 */
class ArNativeOverlayTrackingOrderTest {
    private fun run(state: TrackingState, gateResult: Boolean?): List<String> {
        val log = mutableListOf<String>()
        val gate: ((String) -> Boolean)? = gateResult?.let { result -> { frame -> log += "gate:$frame"; result } }
        dispatchRelocalizedOverlay("f1", state, gate, { frame -> log += "render:$frame" })
        return log
    }

    @Test fun pausedFrameWithOpenGateNeverRenders() =
        assertEquals(listOf("gate:f1"), run(TrackingState.PAUSED, true))

    @Test fun stoppedFrameWithOpenGateNeverRenders() =
        assertEquals(listOf("gate:f1"), run(TrackingState.STOPPED, true))

    @Test fun trackingFrameWithOpenGateRendersExactlyOnceAfterGate() =
        assertEquals(listOf("gate:f1", "render:f1"), run(TrackingState.TRACKING, true))

    @Test fun trackingFrameWithClosedGateNeverRenders() =
        assertEquals(listOf("gate:f1"), run(TrackingState.TRACKING, false))

    @Test fun missingGateNeverRenders() =
        assertEquals(emptyList<String>(), run(TrackingState.TRACKING, null))

    @Test fun lossThenRecoveryRendersOnlyOnTrackingFrames() {
        val log = mutableListOf<String>()
        val states = listOf(TrackingState.TRACKING, TrackingState.PAUSED, TrackingState.STOPPED, TrackingState.TRACKING)
        states.forEachIndexed { i, state ->
            dispatchRelocalizedOverlay("f$i", state, { true }, { frame -> log += frame })
        }
        assertEquals(listOf("f0", "f3"), log)
    }

    /** Binds the tests above to the real callback: onDrawFrame must reach the overlay only through the boundary. */
    @Test fun onDrawFrameReachesOverlayOnlyThroughDispatchBoundaryAfterBackgroundDraw() {
        val src = java.io.File("src/main/kotlin/io/vedika/sdk/ar/VastuArCoreCaptureView.kt").readText()
        val draw = src.indexOf("override fun onDrawFrame")
        assertTrue("onDrawFrame not found", draw >= 0)
        val body = src.substring(draw)
        val background = body.indexOf("backgroundRenderer.draw(frame)")
        val dispatch = body.indexOf("dispatchRelocalizedOverlay(frame, trackingState, overlayGate, renderRelocalizedOverlay)")
        val lostBranch = body.indexOf("if (trackingState != TrackingState.TRACKING)")
        assertTrue("background draw must come first", background in 0 until dispatch)
        assertTrue("dispatch must come before the lost-tracking branch", dispatch in 0 until lostBranch)
        assertEquals("overlay callbacks must not be invoked directly", 0, Regex("(overlayGate|renderRelocalizedOverlay)\\?\\.invoke").findAll(src).count())
    }
}
