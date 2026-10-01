package io.vedika.sdk.ar

import io.vedika.sdk.VastuArRoomCaptureData
import io.vedika.sdk.VastuArRoomCaptureRequest
import io.vedika.sdk.VastuContracts
import io.vedika.sdk.VastuDeviceAttestation
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VastuScanResponse
import io.vedika.sdk.VastuScanSnapshot
import io.vedika.sdk.VastuScansSaveData
import io.vedika.sdk.VastuScansSaveRequest
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

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,128}$")

    /**
     * Keeps a finished [VastuRoomCapture] in the customer's account through
     * `scans/save` (`snapshot.capture`). Only the capture JSON is stored; no
     * mesh or camera image is ever uploaded.
     *
     * This call is billed (USD 0.005 per save) and requires account scan
     * storage to be enabled on the deployment; otherwise the API answers
     * `503 SCAN_STORAGE_DISABLED` before any charge.
     *
     * The caller supplies and retains [scanId] (16-128 characters of
     * `A-Za-z0-9_-`, for example a UUID with dashes). It is the retry
     * identity: after a timeout or a `503`, call again with the SAME [scanId]
     * and the SAME content so the save finishes without a second charge. A
     * changed body for a used [scanId] returns `409`. No `Idempotency-Key` is
     * sent or accepted on scan routes, so this method takes none.
     *
     * [retentionDays] is 1-30. [propertyId] groups scans of one property and
     * follows the same pattern as [scanId]. Check
     * `response.data.persistence`: `account-store` means the scan was kept;
     * `preview-only` (keyless sandbox) means nothing was stored.
     */
    suspend fun saveCapture(
        client: VedikaClient,
        capture: VastuRoomCapture,
        scanId: String,
        propertyId: String,
        title: String,
        retentionDays: Int,
        deviceAttestation: VastuDeviceAttestation? = null,
    ): VastuScanResponse<VastuScansSaveData> {
        require(ID_PATTERN.matches(scanId)) { "scanId must be 16-128 characters of A-Z a-z 0-9 _ -" }
        require(ID_PATTERN.matches(propertyId)) { "propertyId must be 16-128 characters of A-Z a-z 0-9 _ -" }
        require(title.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= 160) {
            "title must be nonblank and at most 160 UTF-8 bytes"
        }
        require(retentionDays in 1..30) { "retentionDays must be 1-30" }
        return client.vastu.vastuScansSave(
            VastuScansSaveRequest(
                scanId = scanId,
                propertyId = propertyId,
                title = title,
                retentionDays = retentionDays,
                snapshot = VastuScanSnapshot(inputSource = "device-reported", capture = capture),
                deviceAttestation = deviceAttestation,
            ),
        )
    }
}
