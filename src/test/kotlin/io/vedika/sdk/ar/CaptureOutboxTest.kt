package io.vedika.sdk.ar

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CaptureOutboxTest {
    private fun key(): Aead { AeadConfig.register(); return KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM).getPrimitive(Aead::class.java) }
    @Test fun offlineRestartBackoffDuplicateAndConflict() = runBlocking {
        val root = Files.createTempDirectory("vedika-outbox-test").toFile()
        try {
            val file = root.resolve("queue"); val key = key(); var queue = CaptureOutbox(file, key)
            val entry = queue.enqueue("{\"capture\":{\"captureId\":\"realistic-room\"}}", "request", "billing", nowMs = 0)
            assertFalse(file.readText().contains("realistic-room"))
            queue.flush({ false }, 0) { fail("Uploaded offline"); CaptureOutbox.Result.Accepted }
            queue = CaptureOutbox(file, key)
            queue.flush({ true }, 0) { assertEquals(entry.idempotencyKey, it.idempotencyKey); CaptureOutbox.Result.Retryable }
            queue = CaptureOutbox(file, key)
            queue.flush({ true }, 1999) { fail("Ignored durable backoff"); CaptureOutbox.Result.Accepted }
            queue.flush({ true }, 2000) { CaptureOutbox.Result.Duplicate(it.requestId, it.sha256) }
            assertEquals(CaptureOutbox.State.SYNCED, queue.records()[0].state)
            queue.enqueue("{}", "conflict", "other", nowMs = 0)
            queue.flush({ true }, 3000) { CaptureOutbox.Result.Duplicate(it.requestId, "wrong-body") }
            assertEquals(CaptureOutbox.State.FAILED, queue.records()[1].state)
            queue.retry("conflict", 4000)
            queue.flush({ true }, 4000) { queue.enqueue("{\"new\":true}", "concurrent", "concurrent", nowMs = 4000); CaptureOutbox.Result.Conflict }
            assertEquals(3, queue.records().size)
            assertEquals(CaptureOutbox.State.FAILED, queue.records()[1].state)
            assertEquals(CaptureOutbox.State.UNSYNCED, CaptureOutbox(file, key).records()[2].state)
        } finally { root.deleteRecursively() }
    }
    @Test fun tamperAndWrongKeyFailClosed() {
        val root = Files.createTempDirectory("vedika-outbox-tamper").toFile()
        try {
            val file = root.resolve("queue"); val key = key(); CaptureOutbox(file, key).enqueue("{}")
            try { CaptureOutbox(file, key()); fail("Wrong key accepted") } catch (_: java.security.GeneralSecurityException) {}
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { CaptureOutbox(file, key); fail("Tampered queue accepted") } catch (_: java.security.GeneralSecurityException) {}
        } finally { root.deleteRecursively() }
    }
}
