# Vedika Android AR (`com.github.vedika-io:vedika-sdk-android-ar`)

Native ARCore room capture for the Vedika Android SDK. An app walks a room,
taps floor corners to trace the plot/house outline and each room, faces a
direction it knows is north and holds a button, and gets back a
`vedika.roomCapture/1` — the same body `ar/room-capture`
(`io.vedika.sdk.VastuArRoomCaptureRequest`, in the core `sdks/android` SDK)
takes. This module is the ARCore-native counterpart to
`web/vedika-public/js/vastu/room-capture-webxr.js`: same tap/undo/close-loop
model, same north-hold rule, same plan geometry — see
`RoomCaptureModel.kt`/`RoomCaptureGeometry.kt`'s KDoc for the exact parity
contract, and `RoomCaptureModelTest.kt`/`RoomCaptureGeometryTest.kt` for the
shared-fixture tests that pin it.

## Install

Published via [JitPack](https://jitpack.io/#vedika-io/vedika-sdk-android-ar):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.vedika-io:vedika-sdk-android:1.1.0")
    implementation("com.github.vedika-io:vedika-sdk-android-ar:1.0.2")
}
```

This repository is a published mirror of `sdks/android-ar` in the
`vedika-io/vedika` monorepo, which is the source of truth — the monorepo
builds it against local source through a composite build
(`includeBuild("../android-ar")`, same pattern `sdks/android-acceptance`
uses); this repository exists only so `./gradlew publish`/JitPack has
something to build standalone.

## Permissions

This module's own manifest declares `CAMERA` and an **optional**
`android.hardware.camera.ar` feature plus `com.google.ar.core value="optional"`
— consuming apps install on devices without ARCore, and
[`ArCoreAvailability.status`] gates the feature at runtime instead of the
Play Store filtering the app out entirely. A host app that wants the
stricter, store-filtered behavior overrides both entries via manifest
merging (`tools:node="replace"`; see the comments in
`src/main/AndroidManifest.xml`).

The host app must itself request the runtime `CAMERA` permission (this is a
library module and cannot own that UI — same house style as `VastuArView`).
Check `ArCoreAvailability.hasCameraPermission(context)`, request if needed,
then call `start()` again.

## Usage

```kotlin
class RoomCaptureActivity : AppCompatActivity() {
    private lateinit var captureView: VastuArCoreCaptureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureView = VastuArCoreCaptureView(this)
        setContentView(captureView)
        captureView.listener = object : VastuArCoreCaptureListener {
            override fun onStateChanged(state: RoomCaptureState) {
                when (state) {
                    RoomCaptureState.NEEDS_INSTALL -> captureView.requestArCoreInstall(this@RoomCaptureActivity)
                    RoomCaptureState.NEEDS_CAMERA_PERMISSION -> requestCameraPermission()
                    RoomCaptureState.UNSUPPORTED -> showArUnsupportedMessage()
                    else -> {}
                }
            }
            override fun onCapture(capture: VastuRoomCapture) {
                lifecycleScope.launch {
                    val response = VastuArCoreUploader.upload(vedikaClient, capture)
                    // response.data has the derived zones/audit/scan-quality requests + results
                }
            }
            override fun onError(error: RoomCaptureError) { /* show error.message */ }
        }
    }

    override fun onResume() { super.onResume(); captureView.start() }
    override fun onPause() { captureView.stop(); super.onPause() }
    override fun onDestroy() { captureView.destroy(); super.onDestroy() }
}
```

The view's own bottom bar (Undo / Add room / "This way is north" (hold) /
Finish) drives `RoomCaptureModel` the same way the web reference's HUD does.
Set `captureView.pendingRoomLabel` (e.g. from your own room-name picker —
`RoomCaptureModel.ROOM_LABELS` has the same list the web reference offers)
before tapping "Add room".

### Keep the scan in the account

`VastuArCoreUploader.saveCapture(client, capture, scanId, propertyId, title, retentionDays)`
stores the capture JSON (never a mesh or camera image) through `scans/save`.
It is billed at USD 0.005 per save and needs account scan storage enabled on
the deployment (otherwise `503 SCAN_STORAGE_DISABLED`, before any charge).
Generate `scanId` yourself (16-128 characters of `A-Za-z0-9_-`) and keep it:
after a timeout, retry with the same `scanId` and the same content so you are
not charged twice. No `Idempotency-Key` is sent. Read
`response.data.persistence`: `account-store` means the scan was kept.

## What a capture claims — and does not

- **`device`**: always `{platform: "android", method: "arcore-hit", depth: ...}`.
  `depth` is `"arcore-depth"` only when `Session.isDepthModeSupported(AUTOMATIC)`
  is true on this device AND `Config.DepthMode.AUTOMATIC` was actually
  enabled for the session; otherwise `"none"`. Tap-to-place corners always go
  through ARCore's plane/point hit-test (`method: "arcore-hit"`) — the Depth
  API, when available, only widens what counts as a valid hit surface.
- **North**: this module has no compass of its own and makes no true-north
  claim. `frame.north.referenceFrame` is always `"manual"`: the operator
  faces a direction they already know is north and holds the button for at
  least 3 seconds (`RoomCaptureModel.MIN_NORTH_MS`) and 15 samples
  (`MIN_NORTH_SAMPLES`), exactly like the web reference's calibrate step. If
  a future revision wires in a live compass + verified declination, that
  path may only ever produce `referenceFrame: "true"` — never `"magnetic"`;
  the contract (`web/vedika-public/openapi.json`'s `VastuRoomCapture` schema)
  refuses a magnetic frame outright.
- **Point-cloud density / coverage**: not reported (`null`, basis `"none"`)
  — ARCore's plane/point hit-test API exposes no density figure the way
  LiDAR-backed scanning does. The server accordingly returns
  `acceptForAudit: false` for a plain hit-test capture; zones are still
  computed.
- **Attestation**: `capture.attestation` is always `"caller-reported"`: the
  server does not independently verify device geometry. Separately, an app can
  prove it is a genuine build on a real device with Google Play Integrity. Ask
  for a challenge (`VastuContracts.arAttestationChallenge`), request a Play
  Integrity token whose nonce is the request-binding digest (SHA-256 over
  `vedika-attest-v1`, the operation, the challenge and the canonical request
  body; see the guide), and send it as
  `VastuDeviceAttestation(platform = "android", challenge, integrityToken)` in
  `deviceAttestation` on `ar/room-capture` or `scans/save`. A verified response
  reports `deviceAttestation.status = "verified"`; scores do not change. The app
  identity is yours: register your package, signing-certificate SHA-256 and
  Play Integrity service account with Vedika first (see the Vastu API guide,
  "Device attestation for native apps"). Until you register, responses report
  `deviceAttestation.status = "not_configured"`.

## Testing

- `./gradlew testDebugUnitTest` — `RoomCaptureGeometryTest` and
  `RoomCaptureModelTest` run the pure Kotlin port (`RoomCaptureModel.kt`,
  `RoomCaptureGeometry.kt` — no Android or ARCore import) against
  `sdks/fixtures/vastu-room-capture/*.json`, the same fixtures
  `room-capture-geometry.test.mjs`/`room-capture-webxr.test.mjs` use, with
  every numeric field checked within `1e-6`. These tests self-skip
  (`Assume`) outside a monorepo checkout, matching
  `sdks/android`'s `VastuResponseFixtureTest` pattern — the fixtures are not
  bundled into the published package.
- On-device: `sdks/android-acceptance`'s `ArCoreSessionSmokeTest` (see that
  module) exercises `ArCoreAvailability`, a bare `Session`, and
  `VastuArCoreCaptureView` end to end. It deliberately never asserts
  `TrackingState.TRACKING` — a rack device lying flat with nothing to look at
  may never reach it — and logs everything it measures instead
  (`adb logcat -s ArCoreSessionSmokeTest`).

## What this module deliberately does not do

- No Sceneform, no Filament, no 3D model rendering — the AR overlay is flat
  amber ribbons/rings tracing the floor plan, drawn with plain GLES2
  (`CameraBackgroundRenderer`, `RoomCaptureOverlayRenderer`), matching the
  web reference's own minimal WebGL overlay.
- No automatic room labelling — `labelSource` is always `"user"` (a label was
  typed/picked) or `"none"`; there is no ARCore equivalent of RoomPlan's
  `roomplan-section` source.
- No background upload/retry queue — `VastuArCoreUploader.upload()` is a
  thin, one-shot wrapper over the existing `VedikaClient` call; a host app
  that wants offline queuing owns that itself, the same way it would for any
  other SDK call.
