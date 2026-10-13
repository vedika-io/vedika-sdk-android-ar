package io.vedika.sdk.ar
import org.junit.Assert.*
import org.junit.Test
class OverlayRelocalizationTest {
    @Test fun missingLandmarksTrackingAndReprojectionRefuseThenRevoke() {
        val gate = OverlayRelocalizationGate(); val ids = setOf("door", "corner")
        assertFalse(gate.observe(true, ids, ids, listOf(0.0)))
        assertFalse(gate.observe(true, ids, setOf("door"), listOf(0.0, 0.0)))
        assertFalse(gate.observe(false, ids, ids, listOf(0.0, 0.0)))
        for (bad in listOf(Double.NaN, -1.0, 9.0, Double.POSITIVE_INFINITY)) assertFalse(gate.observe(true, ids, ids, listOf(bad, 0.0)))
        assertFalse(gate.observe(true, ids, ids, listOf(2.0, 3.0)))
        assertFalse(gate.observe(true, ids, ids, listOf(2.0, 3.0)))
        assertTrue(gate.observe(true, ids, ids, listOf(2.0, 3.0)))
        assertFalse(gate.observe(false, ids, ids, listOf(2.0, 3.0)))
        assertFalse(gate.overlaysAllowed)
    }

    @Test fun trackingLossAndNorthResetRequireThreeNewMatchingFrames() {
        val gate = OverlayRelocalizationGate()
        val ids = setOf("floor-corner")
        fun frame(tracking: Boolean = true, error: Double = 8.0) = gate.observe(tracking, ids, ids, listOf(error))
        repeat(60) {
            assertFalse(frame()); assertFalse(frame()); assertTrue(frame())
            assertFalse(frame(false)); assertFalse(gate.overlaysAllowed)
        }
        assertFalse(frame()); assertFalse(frame()); assertTrue(frame())
        gate.reset() // host north/session reset must revoke cached qualification
        assertFalse(gate.overlaysAllowed)
        assertFalse(frame()); assertFalse(frame()); assertTrue(frame())
        assertFalse(frame(error = 8.000001)); assertFalse(gate.overlaysAllowed)
        assertFalse(frame()); assertFalse(frame()); assertTrue(frame())
    }

    @Test fun invalidPolicyAndMissingTrackingNeverRender() {
        val ids = setOf("floor-corner")
        for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            val gate = OverlayRelocalizationGate(maximumErrorPixels = threshold)
            repeat(10) { assertFalse(gate.observe(true, ids, ids, listOf(0.0))) }
        }
        val gate = OverlayRelocalizationGate()
        repeat(10) {
            assertFalse(gate.observe(false, ids, ids, listOf(0.0)))
            assertFalse(gate.observe(true, emptySet(), emptySet(), emptyList()))
            assertFalse(gate.observe(true, ids, emptySet(), listOf(0.0)))
        }
    }
}
