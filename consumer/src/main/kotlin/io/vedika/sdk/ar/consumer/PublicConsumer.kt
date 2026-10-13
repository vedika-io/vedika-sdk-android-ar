package io.vedika.sdk.ar.consumer

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.Session
import io.vedika.sdk.VastuArRoomCaptureData
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VedikaClient
import io.vedika.sdk.ar.*

/** Compilation-only consumer. It neither records a scan nor calls the API. */
class PublicConsumer(context: Context, session: Session) {
    val view = VastuArCoreCaptureView(context)
    val relocalizer = LocalOverlayRelocalizer(session)
    val queue: CaptureOutbox = AndroidCaptureStore.open(context)
    fun gate(frame: Frame) = relocalizer.evaluate(frame, 100, 100, emptyMap())
    fun remedies(capture: VastuRoomCapture, data: VastuArRoomCaptureData) = view.showRemedies(capture, data)
    suspend fun flush(client: VedikaClient) {
        val adapter = CaptureUploadAdapter(client.vastu)
        queue.flush(isOnline = { false }, upload = adapter::upload)
    }
    suspend fun save(client: VedikaClient, capture: VastuRoomCapture) = VastuArCoreUploader.saveCapture(
        client, capture, "scan-0123456789abcdef", "property-0123456789ab", "Build check", 1,
    )
}
