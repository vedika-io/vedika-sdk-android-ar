package io.vedika.sdk.ar

/**
 * Plain-value types the pure capture model and geometry work with. No
 * Android or ARCore imports anywhere in this file: [RoomCaptureModel] and
 * [RoomCaptureGeometry] must build and test on a bare JVM (see the module
 * README for why that matters for parity with the web reference).
 */

/** A point in AR world space, metres, Y-up (matches WebXR/ARCore/ARKit convention). */
data class Vec3(val x: Double, val y: Double, val z: Double)

/** One "facing north" sample: the camera's forward vector at time [tMs]. */
data class RoomCaptureHeadingSample(val headingDeg: Double, val forward: Vec3)

data class RoomCaptureOpeningInput(
    val kind: String,
    val centerWorld: Vec3,
    val widthM: Double,
    val heightM: Double?,
    val confidence: String,
)

data class RoomCaptureRoomInput(
    val id: String,
    val label: String?,
    val labelSource: String,
    val floorIndex: Int,
    val heightM: Double?,
    val worldCorners: List<Vec3>,
    val closingTap: Vec3?,
    val openings: List<RoomCaptureOpeningInput> = emptyList(),
)

data class RoomCaptureOutlineInput(
    val source: String,
    val worldCorners: List<Vec3>,
    val closingTap: Vec3?,
)

data class RoomCaptureQualityInput(
    val pointCloudDensity: Double?,
    val pointCloudDensityBasis: String,
    val coveragePercent: Double?,
    val scanDurationSec: Double?,
    val scannedAreaM2: Double?,
    val expectedRoomCount: Int?,
    val gpsConfidence: Double?,
)

/** The `session.north` input before geometry recomputes `referenceFrame`. */
data class RoomCaptureNorthInput(
    val referenceFrame: String,
    val headingSource: String,
    val declinationDeg: Double?,
    val declinationProvenance: String,
)

data class RoomCaptureDeviceInput(
    val platform: String,
    val method: String,
    val depth: String,
)

/**
 * The `session` object [RoomCaptureGeometry.buildRoomCapture] takes — the
 * same shape `room-capture-geometry.js`'s `session` parameter and the shared
 * fixtures' `session` field describe.
 */
data class RoomCaptureSession(
    val captureId: String,
    val capturedAtEpoch: Long,
    val device: RoomCaptureDeviceInput,
    /** 'true' when `headingSamples[].headingDeg` already includes declination; 'magnetic' otherwise. */
    val headingFrame: String,
    val north: RoomCaptureNorthInput,
    val headingSamples: List<RoomCaptureHeadingSample>,
    val outline: RoomCaptureOutlineInput,
    val rooms: List<RoomCaptureRoomInput>,
    val quality: RoomCaptureQualityInput,
)
