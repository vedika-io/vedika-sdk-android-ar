# Vedika Android AR 1.2.0

Native ARCore room capture, a durable encrypted capture queue, local overlay
relocalization and floor-pinned remedy markers.

ARCore 1.56.0 has aligned 64-bit LOAD segments but fails the current
documented RELRO-end check, so 16 KB page support is not claimed. A newer
compatible official artifact is still required.
Real-phone accuracy not yet measured.

## Changes

- **1.2.0:** `FloorMerge` and `FloorMergeSession` post a multi-floor
  merge to `ar/capture-merge`.
- **1.1.1:** remedy markers no longer draw on frames where the camera has
  lost tracking. In 1.1.0 they could be drawn before the tracking check.
- **1.1.0:** capture queue, local overlay relocalization and floor-pinned
  remedy markers.

## Install

Use the public JitPack coordinate:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
// app/build.gradle.kts
dependencies {
    implementation("com.github.vedika-io:vedika-sdk-android-ar:1.2.0")
}
```

The AR package exports the core client and its public ARCore/Tink types as
transitive API dependencies. A separate core dependency is optional. For a
source checkout, build and stage the candidate locally with the commands
below. Android API 24 or later and Java 17 are required.

## Public API

All types below are in `io.vedika.sdk.ar`.

| API | Supported entry points |
| --- | --- |
| `LocalOverlayRelocalizer(session)` | `add(pose)`, `evaluate(frame, width, height, observedPixels)`, `clear()` |
| `CaptureOutbox(file, aead)` | `enqueue`, `records`, suspending `flush`, `retry`, `removeSynced` |
| `AndroidCaptureStore.open(context)` | Open the encrypted, device-local outbox |
| `CaptureUploadAdapter` | Suspending `upload(entry, client.vastu, prepareProof)` for outbox flushing |
| `VastuArCoreUploader` | Suspending `upload(...)` and `saveCapture(...)` |
| `FloorMerge` | `prepare(input)`, suspending `merge(client, input, idempotencyKey)` |
| `FloorMergeSession` | The state behind a "merge floors" screen: `addFloor`, `addPick`, `undoPick`, `canMerge`, `status`, `prepare`, suspending `merge` |
| `VastuArCoreCaptureView` | `start`, `stop`, `destroy`, `showRemedies(capture, analysis)`, `clearRemedies` |
| `FloorRemedyScene.from(capture, analysis)` | Remedy positions, verbatim text, source and classification |
| `FloorRemedyGeometry` | JVM-testable plan-to-floor transforms and interior positions |

`saveCapture` ships since 1.0.2. The four queue and relocalizer APIs above
ship since 1.1.0.

## Capture and display remedies

The library declares an optional AR capability and camera permission. The
host must request the runtime camera permission and handle the install state.
Check `ArCoreAvailability.hasCameraPermission(context)` and
`ArCoreAvailability.status(context)` before opening the camera.

```kotlin
val captureView = VastuArCoreCaptureView(this)
setContentView(captureView)
captureView.listener = object : VastuArCoreCaptureListener {
    override fun onCapture(capture: VastuRoomCapture) {
        lifecycleScope.launch {
            val response = VastuArCoreUploader.upload(client, capture)
            captureView.showRemedies(capture, response.data)
            val scene = FloorRemedyScene.from(capture, response.data)
            // Display scene.markers' full remedy/source/classification
            // in the host's accessible advice list.
        }
    }
    override fun onError(error: RoomCaptureError) {
        // Show the error to the user.
    }
}
// Activity/Fragment lifecycle:
override fun onResume() { super.onResume(); captureView.start() }
override fun onPause() { captureView.stop(); super.onPause() }
override fun onDestroy() { captureView.destroy(); super.onDestroy() }
```

The host supplies an authorized `VedikaClient`. Keep production credentials
on your backend and use your existing request or token exchange.

Trace the outline and named rooms by tapping floor corners. Face a direction
you know is north and hold the north button for at least three seconds and
15 samples. The capture uses `vedika.roomCapture/1`; north is user-entered,
not independently measured. Set `pendingRoomLabel` before adding a room.

The first floor corner creates one local ARCore anchor. Purple rings show
returned room remedies. Positions use the capture's `frame.planToWorld`
origin/east/north axes and remain within each room, including concave rooms.
Text comes only from `audit.defects` and `planAnalysis.defects`, using
`roomId`/`room`, `zone`, `remedy`, `remedySource` and
`remedyClassification`. Repeated advice is deduplicated. A note without an
identified room is placed inside the plot outline. Missing sources remain
missing; the host should identify them for review.

Camera tracking loss, floor-anchor tracking loss or a missing floor anchor
hides all remedy rings. No previous pose is used as a fallback. Editing the
capture clears its remedy display; delayed analysis for another capture is
refused. `destroy()` detaches the anchor and clears callbacks. A new session
requires a fresh scan; an imported scan cannot be attached to this session.
Markers locate room-level suggestions, not detected furniture. This is a
floor sketch, not a survey or a promise of remedy efficacy.

## Queue captures offline

```kotlin
val outbox = AndroidCaptureStore.open(context) // keep one process owner
val request = VastuArRoomCaptureRequest(capture = capture)
val entry = outbox.enqueue(JSONObject(request.toMap()).toString())
outbox.flush(isOnline = { connectivity.isOnline }) { entry ->
    CaptureUploadAdapter.upload(entry, client.vastu, prepareProof = null)
}
val states = outbox.records()
outbox.removeSynced() // explicit acknowledgement cleanup
```

`AndroidCaptureStore` uses device-key encryption and stores ciphertext in
`noBackupFilesDir`. Merge `backup-rules-native.xml` and
`data-extraction-rules-native.xml` into the app's backup policy to exclude
`vedika_capture_keyset.xml` from backup and device transfer. Decryption/key
failures throw; they never silently replace a queue with an empty one.

The queue preserves request identity, idempotency key, state, attempts and
backoff across process restarts. Conflicts remain failed for review. It
does not schedule background work: the host decides when to flush. Store an
unsigned request, without `deviceAttestation`. If attestation is used,
`prepareProof` creates a fresh request-bound proof at each upload. Never store
short-lived attestation tokens in the outbox.

## Save to an account

```kotlin
val response = VastuArCoreUploader.saveCapture(
    client, capture,
    scanId = scanId, propertyId = propertyId,
    title = "Room scan", retentionDays = 14,
)
```

This uses the billed `scans/save` contract and saves capture JSON, not camera
images or meshes. Generate and retain 16–128 character `A-Za-z0-9_-`
identifiers. Retry the same `scanId` with exactly the same body after a timeout;
changed content for a used identity is a conflict. No `Idempotency-Key` header
is used on this route. Check `response.data.persistence`: `account-store`
confirms storage; `preview-only` does not. Retention is 1–30 days.

## Scan two floors and merge

Scan each floor on its own with `VastuArCoreCaptureView`, set that floor's
`floorIndex` before scanning, and measure its elevation above the building
datum. Then pick the same stair (or measured door) points on both plans and call
`FloorMerge.merge`, which posts `ar/capture-merge`. Available from 1.2.0.

A "merge floors" screen can drive `FloorMergeSession`: `addFloor(capture, elevationM)`,
`addPick(captureId, reference, next)`, `canMerge()`, `status()`, `prepare()` and suspending
`merge(client, key)`. It only collects the floors and picked points; `FloorMerge` still validates them and
refuses a bad link before anything is sent. The acceptance app's debug build has a working screen
(`MergeFloorsActivity`, source SDK only).

```kotlin
val input = FloorMergeInput(
    scans = listOf(FloorScan(groundCapture, elevationM = 0.0), FloorScan(firstCapture, elevationM = 3.2)),
    links = listOf(
        FloorLink(
            toCaptureId = firstCapture.captureId, kind = FloorLinkKind.STAIR,
            points = listOf( // the same spot: reference floor plan, then first floor plan, metres
                SharedFloorPoint(FloorPoint(2.0, 1.0), FloorPoint(0.8, 0.6)),
                SharedFloorPoint(FloorPoint(2.0, 3.0), FloorPoint(0.8, 2.6)),
                SharedFloorPoint(FloorPoint(4.0, 2.0), FloorPoint(2.8, 1.6)),
            ),
        ),
    ),
    toleranceM = 0.05,
)
val result = FloorMerge.merge(client, input, idempotencyKey = retainedKey)
result.building.floorStack   // both floors, one building
result.registration.describe() // the stated shared-point error
```

- The first scan is the reference floor. Every other floor needs one link of at
  least 3 shared points (the wire minimum is 2; 3 gives a check beyond a
  baseline). Points must lie inside their own floor's outline and be at least
  0.1 m apart on both floors.
- `FloorMerge.prepare(input)` builds and validates the request with no network
  call. A bad link throws `FloorMergeRefusedException` (`reason` is a
  `FloorMergeRefusal`) before anything is sent, so nothing is billed: fewer than
  two floors, an unknown or duplicate link, a duplicate floor index, capture id
  or elevation, too few or non-finite points, points outside the floor, or an
  error above `toleranceM`.
- The API links only captures on the same floor, so the shared points become
  one floor frame per floor (`floors[].originXY`) and `links` is empty. The
  merge is translation only: each scan's recorded true north is kept and there is
  no scale change.
- The call is billed. Keep `idempotencyKey` (1-256 visible ASCII characters)
  and reuse it, with the same input, to retry a timeout without a second
  charge. `maxChargeUsd` caps the debit; the API refuses a higher charge before
  any debit.
- The stated error is the agreement of your picked points (RMS and maximum, in
  metres), not survey or house accuracy. A response that does not match what
  was sent throws `FloorMergeResponseException`; keep the original scans.

## Relocalize a custom overlay

`LocalOverlayRelocalizer` owns local anchors in one ARCore session. Add
poses from actual landmarks with `add(pose)` and retain the returned IDs.
On every render frame, pass current independently observed landmark pixels
to `evaluate(frame, width, height, observedPixels)`. Wire its result to
`captureView.overlayGate` and draw through `renderRelocalizedOverlay`.
A missing or false gate does not draw the custom overlay. Cached projected
pixels are not valid physical observations.

The policy requires three matching frames within eight pixels; that is a
rendering threshold, not measured accuracy. Missing landmarks, tracking loss
or invalid reprojection immediately revoke it. Call `clear()` and clear the
host callbacks on teardown. Restart means rescan.

## Build and verify

With an Android SDK platform installed:

```bash
./gradlew test assembleRelease publishReleasePublicationToConsumerRepository
./gradlew -p consumer assembleRelease
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/verify_artifacts.py --aar build/outputs/aar/vedika-android-ar-release.aar --report build/public-api.json
python3 scripts/verify_artifacts.py --native consumer/build/outputs/apk/release/consumer-release-unsigned.apk --zipalign "$ANDROID_HOME/build-tools/35.0.0/zipalign" --report build/native-pages.json
```

Shared capture fixtures are available to standalone mirror tests. Tests cover
queue identity/retries, API exports, save serialization, reprojection refusal,
remedy sources, concave rooms, rigid anchor corrections and tracking loss.
The consumer compiles against the locally staged AAR and Maven metadata with
only the AR dependency and packages the complete native dependency graph.

JitPack reads `jitpack.yml` at the **public mirror repository root**. The
candidate exporter places it there. Its build uses Java 17, installs platform
35, runs unit tests and assembles the release AAR before local Maven staging.

The strict native checker inventories every `.so`, checks every 64-bit LOAD
segment and RELRO end, and verifies uncompressed APK library offsets. It
also runs `zipalign -c -P 16 4`. The candidate fails that gate while ARCore's
RELRO ends remain unaligned. See [Google's current 16 KB instructions](https://developer.android.com/guide/practices/page-sizes).

Unit tests, builds and static native checks do not prove physical tracking,
heading accuracy or operation on a 16 KB device.

## Known issue: 16 KB page-size devices

ARCore 1.56.0, the latest published release, ships 64-bit libraries that fail
the 16 KB RELRO alignment check. ARCore may fail to load on devices using
16 KB pages. Versions 1.0.2 and 1.1.0 have the same dependency. We will publish an update
when a compliant ARCore release is available. Native checks retain an exact
checksum record of these four upstream failures and reject new failures.
