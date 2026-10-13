package io.vedika.sdk.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bare-JVM replay-provenance, depth-scoring and density-scoring fixtures
 * for `sdks/android-ar`. Touches no Android or ARCore type — every type
 * under test lives in this module's pure-Kotlin half
 * ([RoomCaptureQuality], [RoomCaptureModel], [RoomCaptureGeometry]) so
 * the test runs under `./gradlew testDebugUnitTest` with no Android SDK.
 *
 * The four invariants pinned here all reflect claims made by `ar/room-capture`
 * on the wire (see `vedika.roomCapture/1` in
 * `web/vedika-public/openapi.json`): any change that breaks one of them is
 * either a regression in the model or a change to the wire contract, both of
 * which require touching the shared cross-platform fixtures too.
 *
 *  1. **Replay provenance is deterministic.** [RoomCaptureQuality.pointCloudDensityPerM2]
 *     and [RoomCaptureQuality.coveragePercent] produce the same value twice
 *     for the same input; nothing is read from system clock, system I/O or a
 *     fixed table. A replay harness can therefore pin a captured score and
 *     compare bit-for-bit on a later run.
 *  2. **An insufficient-depth recording scores cleanly, not as a number.**
 *     A capture built without `recordFeaturePoints()` keeps
 *     `pointCloudDensity = null` and `coveragePercent = null` — never a
 *     fabricated density and never a 0.0 the server would have to retract.
 *  3. **Depth availability does NOT change density basis.** Point-cloud density
 *     from `Frame.acquirePointCloud()` is always `feature-points`, independent
 *     of whether `Session.isDepthModeSupported(AUTOMATIC)` is true. A native
 *     capture never reports the LiDAR-style `lidar-depth` basis.
 *  4. **The model's `build()` is a replay identical to `RoomCaptureGeometry`.** No
 *     hidden reordering, no off-by-one in the room list — `model.build()` and
 *     `RoomCaptureGeometry.buildRoomCapture(model.toSession(...))` produce
 *     matching `north.referenceFrame`, room count and polygon lists.
 */
class RoomCaptureReplayProvenanceTest {

    /** Identical input → identical density. Pinning determinism for replay. */
    @Test fun pointCloudDensityPerM2IsDeterministic() {
        val first = RoomCaptureQuality.pointCloudDensityPerM2(4800, 12.0)!!
        val second = RoomCaptureQuality.pointCloudDensityPerM2(4800, 12.0)!!
        assertEquals(first, second, 0.0)
        assertEquals(400.0, first, 1e-9)
    }

    /** Identical input → identical coverage. Pinning determinism for replay. */
    @Test fun coveragePercentIsDeterministic() {
        val outline = listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0)
        val points = listOf(0.25 to 0.25, 0.75 to 0.75, 1.25 to 1.25, 1.75 to 1.75)
        val first = RoomCaptureQuality.coveragePercent(outline, points)!!
        val second = RoomCaptureQuality.coveragePercent(outline, points)!!
        assertEquals(first, second, 0.0)
    }

    /** Two distinct inputs produce two distinct scores — pinning non-trivial. */
    @Test fun identicalCoverageWithDifferentPointSetsActuallyDiffers() {
        val outline = listOf(0.0 to 0.0, 2.0 to 0.0, 2.0 to 2.0, 0.0 to 2.0)
        val sparse = RoomCaptureQuality.coveragePercent(outline, listOf(0.25 to 0.25))!!
        val dense = RoomCaptureQuality.coveragePercent(outline, listOf(0.25 to 0.25, 1.75 to 1.75))!!
        assertNotEquals(sparse, dense)
        assertTrue("dense should not be smaller than sparse", dense > sparse)
    }

    /**
     * A capture built without ever calling [RoomCaptureModel.recordFeaturePoints]
     * — every non-ARCore platform, every shared fixture, and any operator
     * who skipped the depth-unaware scan — has `pointCloudDensity = null` and
     * `coveragePercent = null`, never a fabricated density and never a 0.0
     * the server would have to retract. The `ar/scan-quality` validator
     * rejects the wire shape with a 400 if either field is the wrong kind.
     */
    @Test fun captureWithoutFeaturePointsScoresNullNotZero() {
        val model = traceAndHoldNorth("recplay-1")
        val session = model.toSession(
            device = RoomCaptureDeviceInput("android", "arcore-hit", "none"),
        )
        assertNull("no point cloud -> density is null, not 0.0",
            session.quality.pointCloudDensity)
        assertNull("no point cloud -> coverage is null, not 0.0",
            session.quality.coveragePercent)
        assertEquals("basis must be \"none\" when density is null",
            "none", session.quality.pointCloudDensityBasis)
    }

    /**
     * A capture that DID record feature points gets a non-null density with
     * the `feature-points` basis, regardless of whether ARCore's Depth API
     * was available on the device — feature points are not the Depth API's
     * dense depth image, so they must NEVER carry the `lidar-depth` basis.
     */
    @Test fun captureWithFeaturePointsKeepsFeaturePointsBasisUnderEveryDepthClaim() {
        for (depth in listOf("none", "arcore-depth")) {
            val model = traceAndHoldNorth("recplay-2-$depth")
            val ids = intArrayOf(1, 2, 3, 4, 5)
            val pts = listOf(
                Vec3(0.5, 0.0, -0.5),
                Vec3(1.5, 0.0, -0.5),
                Vec3(2.5, 0.0, -0.5),
                Vec3(0.5, 0.0, -1.5),
                Vec3(2.5, 0.0, -1.5),
            )
            model.recordFeaturePoints(ids, pts)
            val session = model.toSession(
                device = RoomCaptureDeviceInput("android", "arcore-hit", depth),
            )
            assertNotNull("feature points present -> density is a real number", session.quality.pointCloudDensity)
            assertTrue(
                "depth=$depth still uses the feature-points basis (it is ARCore's sparse cloud, not the Depth API)",
                session.quality.pointCloudDensityBasis == "feature-points",
            )
        }
    }

    /**
     * `RoomCaptureModel.build()` and `RoomCaptureGeometry.buildRoomCapture(
     * RoomCaptureModel.toSession(...))` are equivalent — `build()` is just
     * the convenience wrapper, so a replay test that drives `build()`
     * directly must produce the same `referenceFrame`, room count and
     * polygon lists as the split form.
     */
    @Test fun modelBuildEqualsGeometryBuildOfModelToSession() {
        val model = traceAndHoldNorth("recplay-3")
        val directSession = model.toSession(
            device = RoomCaptureDeviceInput("android", "arcore-hit", "arcore-depth"),
        )
        val directCapture = RoomCaptureGeometry.buildRoomCapture(directSession)
        val modelCapture = model.build(
            device = RoomCaptureDeviceInput("android", "arcore-hit", "arcore-depth"),
        )
        assertEquals(
            "RoomCaptureModel.build sets the same referenceFrame as the split path",
            "manual",
            directCapture.frame.north.referenceFrame,
        )
        assertEquals(modelCapture.frame.north.referenceFrame, directCapture.frame.north.referenceFrame)
        assertEquals(modelCapture.rooms.size, directCapture.rooms.size)
        for (i in directCapture.rooms.indices) {
            assertEquals(
                "room $i label",
                directCapture.rooms[i].label,
                modelCapture.rooms[i].label,
            )
            assertEquals(
                "room $i polygon order",
                directCapture.rooms[i].polygon,
                modelCapture.rooms[i].polygon,
            )
        }
        assertEquals(
            "quality.density basis must be the same string (the wrapper does not rewrap)",
            directCapture.quality.pointCloudDensityBasis,
            modelCapture.quality.pointCloudDensityBasis,
        )
    }

    /**
     * Recording a session then asking for the session back before north is
     * ready throws the same exception the API contract documents, rather
     * than silently emitting an invalid `referenceFrame`. The
     * `ar/room-capture` validator rejects `null` compass confidence with a
     * 400 — the Kotlin side keeps that contract by failing fast.
     */
    @Test fun finishingBeforeNorthHoldsRejectedWithIllegalState() {
        val model = RoomCaptureModel("recplay-4")
        for ((x, y) in listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 6.0, 0.0 to 6.0)) {
            model.tap(Vec3(x, -1.4, -y))
        }
        // Outline is closed: 4 outline taps form the rectangle and a 5th
        // close-snap tap closes the loop. The model still throws on
        // toSession() because no north samples were ever added — the
        // API contract requires a confirmed north before build.
        val closed = model.tap(Vec3(0.05, -1.4, -0.05)) == RoomCaptureModel.TapResult.CLOSED
        assertTrue("outline must be closed by the snap-tap", closed)
        assertThrows(IllegalStateException::class.java) { model.toSession() }
    }

    // ── helpers (private — fixture context, not part of the public test API) ──

    private fun traceAndHoldNorth(id: String): RoomCaptureModel {
        val clock = MutableMs(2_000_000L)
        val model = RoomCaptureModel(id, now = clock::now)
        for ((x, y) in listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 6.0, 0.0 to 6.0)) {
            model.tap(Vec3(x, -1.4, -y))
        }
        assertEquals(
            "outline closes on a snap-tap",
            RoomCaptureModel.TapResult.CLOSED,
            model.tap(Vec3(0.1, -1.4, -0.1)),
        )
        model.startRoom("kitchen")
        for ((x, y) in listOf(3.0 to 5.0, 4.0 to 5.0, 4.0 to 6.0, 3.0 to 6.0)) {
            model.tap(Vec3(x, -1.4, -y))
        }
        assertEquals(
            "kitchen closes on a snap-tap",
            RoomCaptureModel.TapResult.CLOSED,
            model.tap(Vec3(3.05, -1.4, -5.05)),
        )
        repeat(20) {
            model.addNorthSample(Vec3(0.0, -0.3, -1.0), clock.now())
            clock.advance(200L)
        }
        return model
    }

    private class MutableMs(start: Long) {
        private var t = start
        fun now(): Long = t
        fun advance(delta: Long) { t += delta }
    }
}
