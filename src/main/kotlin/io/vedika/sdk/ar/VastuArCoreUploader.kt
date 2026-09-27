package io.vedika.sdk.ar

import io.vedika.sdk.VastuArRoomCaptureData
import io.vedika.sdk.VastuArRoomCaptureRequest
import io.vedika.sdk.VastuContracts
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuTypedResponse
import io.vedika.sdk.VedikaClient

/**
 * Sends a finished [VastuRoomCapture] (from [VastuArCoreCaptureView.listener]'s
 * `onCapture`) to `ar/room-capture` using the existing SDK call — see
 * `VastuArRoomCaptureRequest`/`VastuArRoomCaptureData` in
 * `sdks/android`'s `VastuService.kt`. This is a thin convenience wrapper, not
 * a new transport: it runs the SAME contract every other typed Vastu
 * operation on [VedikaClient] does.
 */
object VastuArCoreUploader {

    /**
     * Uploads [capture]. [zoneResolution] mirrors the request field
     * (`8`, `16`, or `32`; the server defaults to `16` when omitted).
     * Call from a coroutine scope (e.g. `lifecycleScope.launch { ... }`) —
     * this suspends off the calling dispatcher exactly like every other
     * [VedikaClient] call (see `VedikaClient.post`).
     */
    suspend fun upload(
        client: VedikaClient,
        capture: VastuRoomCapture,
        zoneResolution: Int? = null,
        idempotencyKey: String? = null,
    ): VastuTypedResponse<VastuArRoomCaptureData> =
        client.vastu.vastuOperation(
            VastuContracts.arRoomCapture,
            VastuArRoomCaptureRequest(capture = capture, zoneResolution = zoneResolution),
            idempotencyKey = idempotencyKey,
        )
}
