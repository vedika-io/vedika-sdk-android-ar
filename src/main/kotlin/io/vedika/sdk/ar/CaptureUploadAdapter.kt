package io.vedika.sdk.ar

import io.vedika.sdk.VastuDeviceAttestation
import io.vedika.sdk.VastuService
import io.vedika.sdk.exceptions.VedikaApiError
import org.json.JSONArray
import org.json.JSONObject

object CaptureUploadAdapter {
    /** Short-lived proof is regenerated around the durable unchanged body.
     * Billing identity persists across attempts. 409 never implies synced. */
    suspend fun upload(entry: CaptureOutbox.Entry, service: VastuService,
                       prepareProof: (suspend (JSONObject) -> VastuDeviceAttestation)? = null): CaptureOutbox.Result {
        val body = JSONObject(entry.body)
        if (body.has("deviceAttestation")) return CaptureOutbox.Result.Rejected
        val captureId = body.optJSONObject("capture")?.optString("captureId") ?: return CaptureOutbox.Result.Rejected
        prepareProof?.let { body.put("deviceAttestation", JSONObject(it(body).toMap())) }
        fun value(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> v.keys().asSequence().associateWith { value(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { value(v.get(it)) }
            else -> v
        }
        val params = body.keys().asSequence().associateWith { value(body.get(it)) }
        return try {
            val response = service.vastu("ar/room-capture", params, entry.idempotencyKey)
            if (response.optBoolean("success") && response.optJSONObject("data")?.optJSONObject("capture")?.optString("captureId") == captureId)
                CaptureOutbox.Result.Accepted else CaptureOutbox.Result.Rejected
        } catch (error: VedikaApiError) {
            when {
                error.statusCode == 409 -> CaptureOutbox.Result.Conflict
                error.statusCode == 429 || (error.statusCode ?: 0) >= 500 || error.statusCode == null -> CaptureOutbox.Result.Retryable
                else -> CaptureOutbox.Result.Rejected
            }
        }
    }
}
