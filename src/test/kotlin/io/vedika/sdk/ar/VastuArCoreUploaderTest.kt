package io.vedika.sdk.ar

import java.net.InetAddress
import io.vedika.sdk.VastuDeviceAttestation
import io.vedika.sdk.VastuRoomCapture
import io.vedika.sdk.VedikaClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * `VastuArCoreUploader.saveCapture` sends exactly the `scans/save` body the
 * API documents (`snapshot.capture` with `inputSource: device-reported`), with
 * no retry header, and refuses malformed identifiers before any request.
 */
class VastuArCoreUploaderTest {
    private val scanId = "scan-0123456789abcdef"
    private val propertyId = "property-0123456789ab"

    private fun fixtureCapture(): VastuRoomCapture {
        val root = RoomCaptureFixtures.monorepoRoot()
        Assume.assumeTrue("skipped: no monorepo checkout reachable, so the shared fixtures are not available", root != null)
        val fixture = RoomCaptureFixtures.load(root!!).first()
        return RoomCaptureGeometry.buildRoomCapture(RoomCaptureFixtures.parseSession(fixture.getJSONObject("session")))
    }

    private fun savedBody(persistence: String = "account-store") =
        """{"success":true,"data":{"scan":{"scanId":"$scanId"},"replayed":false,"persistence":"$persistence"},""" +
            """"billing":{"charged":0.005,"currency":"USD","balanceBefore":1.0,"balanceAfter":0.995,"endpoint":"/v2/vastu/scans/save","category":"vastu"}}"""

    private fun withServer(block: suspend (MockWebServer, VedikaClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            block(server, VedikaClient(apiKey = "vk_test", baseUrl = server.url("/").toString().trimEnd('/')))
        } finally {
            server.shutdown()
        }
    }

    @Test fun saveCapturePostsTheCaptureAsADeviceReportedSnapshotWithoutARetryHeader() = withServer { server, client ->
        val capture = fixtureCapture()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(savedBody()))
        val response = VastuArCoreUploader.saveCapture(client, capture, scanId, propertyId, "Flat 4B living room", 14)
        assertTrue(response.success)
        assertEquals("account-store", response.data.persistence)
        assertFalse(response.data.replayed)
        val wire = server.takeRequest()
        assertEquals("POST", wire.method)
        assertEquals("/v2/astrology/vastu/scans/save", wire.path)
        assertNull(wire.getHeader("Idempotency-Key"))
        val body = JSONObject(wire.body.readUtf8())
        assertEquals(setOf("scanId", "propertyId", "title", "retentionDays", "snapshot"), body.keySet())
        assertEquals(scanId, body.getString("scanId"))
        assertEquals(propertyId, body.getString("propertyId"))
        assertEquals("Flat 4B living room", body.getString("title"))
        assertEquals(14, body.getInt("retentionDays"))
        val snapshot = body.getJSONObject("snapshot")
        // The server refuses rooms, plotPolygon, bearingDeg or telemetry beside a capture.
        assertEquals(setOf("inputSource", "capture"), snapshot.keySet())
        assertEquals("device-reported", snapshot.getString("inputSource"))
        val sent = snapshot.getJSONObject("capture")
        assertEquals(capture.schema, sent.getString("schema"))
        assertEquals(capture.captureId, sent.getString("captureId"))
        assertEquals(capture.rooms.size, sent.getJSONArray("rooms").length())
    }

    @Test fun saveCaptureCarriesDeviceAttestationOnlyWhenGiven() = withServer { server, client ->
        val capture = fixtureCapture()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(savedBody()))
        VastuArCoreUploader.saveCapture(
            client, capture, scanId, propertyId, "Kitchen", 1,
            deviceAttestation = VastuDeviceAttestation(platform = "android", challenge = "challenge-1", integrityToken = "token-1"),
        )
        val attestation = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("deviceAttestation")
        assertEquals("android", attestation.getString("platform"))
        assertEquals("challenge-1", attestation.getString("challenge"))
        assertEquals("token-1", attestation.getString("integrityToken"))
    }

    @Test fun aPreviewOnlyAnswerIsReportedAsNotStored() = withServer { server, client ->
        val capture = fixtureCapture()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(savedBody("preview-only")))
        val response = VastuArCoreUploader.saveCapture(client, capture, scanId, propertyId, "Kitchen", 7)
        assertEquals("preview-only", response.data.persistence)
    }

    @Test fun malformedIdentifiersTitleAndRetentionAreRefusedBeforeAnyRequest() = withServer { server, client ->
        val capture = fixtureCapture()
        fun refuse(scan: String = scanId, property: String = propertyId, title: String = "Kitchen", days: Int = 7) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { VastuArCoreUploader.saveCapture(client, capture, scan, property, title, days) }
            }
        }
        refuse(scan = "short")
        refuse(scan = "scan 0123456789abcdef")
        refuse(property = "p".repeat(129))
        refuse(title = "   ")
        refuse(title = "x".repeat(161))
        refuse(days = 0)
        refuse(days = 31)
        assertEquals(0, server.requestCount)
    }
}
