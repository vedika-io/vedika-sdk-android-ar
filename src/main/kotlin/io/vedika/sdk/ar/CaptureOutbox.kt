package io.vedika.sdk.ar

import com.google.crypto.tink.Aead
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** One process owner per file. All snapshots are encrypted and atomic. The
 * uploader runs outside the lock, so enqueue during upload is preserved. */
class CaptureOutbox(private val file: File, private val aead: Aead, private val capacity: Int = 100) {
    enum class State { UNSYNCED, SYNCED, FAILED }
    data class Entry(val requestId: String, val idempotencyKey: String, val body: String,
                     val sha256: String, val metadata: String?, val state: State = State.UNSYNCED,
                     val attempts: Int = 0, val nextAttemptAtMs: Long = 0, val failure: String? = null)
    sealed class Result {
        data object Accepted : Result()
        data class Duplicate(val requestId: String, val sha256: String) : Result()
        data object Retryable : Result()
        data object Conflict : Result()
        data object Rejected : Result()
    }
    private val aad = "vedika.capture-outbox/1".toByteArray(Charsets.UTF_8)
    private var flushing = false
    private var entries = if (file.exists()) decode(String(aead.decrypt(file.readBytes(), aad), Charsets.UTF_8)) else emptyList()
    @Synchronized fun records(): List<Entry> = entries.toList()
    @Synchronized fun enqueue(body: String, requestId: String = UUID.randomUUID().toString(),
                              idempotencyKey: String = UUID.randomUUID().toString(), metadata: String? = null,
                              nowMs: Long = System.currentTimeMillis()): Entry {
        require(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024 && requestId.isNotBlank() && idempotencyKey.isNotBlank())
        JSONObject(body); metadata?.let { JSONObject(it) }
        val hash = hash(body)
        entries.firstOrNull { it.requestId == requestId || it.idempotencyKey == idempotencyKey }?.let {
            require(it.requestId == requestId && it.idempotencyKey == idempotencyKey && it.body == body && it.metadata == metadata) { "Identity conflict" }; return it
        }
        require(entries.size < capacity) { "Outbox full; remove acknowledged entries" }
        val entry = Entry(requestId, idempotencyKey, body, hash, metadata, nextAttemptAtMs = nowMs)
        commit(entries + entry); return entry
    }
    suspend fun flush(isOnline: () -> Boolean, nowMs: Long = System.currentTimeMillis(), upload: suspend (Entry) -> Result) {
        val ids = synchronized(this) {
            if (flushing || !isOnline()) return
            flushing = true; entries.filter { it.state == State.UNSYNCED && it.nextAttemptAtMs <= nowMs }.map { it.requestId }
        }
        try {
            for (id in ids) {
                if (!isOnline()) break
                val checkpoint = synchronized(this) {
                    val old = entries.first { it.requestId == id }
                    val item = old.copy(attempts = old.attempts + 1,
                        nextAttemptAtMs = nowMs + minOf(3_600_000L, 2000L shl minOf(old.attempts, 11)))
                    commit(entries.map { if (it.requestId == id) item else it }); item
                }
                val result = try { upload(checkpoint) }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { Result.Retryable }
                synchronized(this) {
                    commit(entries.map { current -> if (current.requestId != id) current else when (result) {
                        Result.Accepted -> current.copy(state = State.SYNCED)
                        is Result.Duplicate -> if (result.requestId == id && result.sha256 == current.sha256) current.copy(state = State.SYNCED)
                            else current.copy(state = State.FAILED, failure = "identity-conflict")
                        Result.Conflict -> current.copy(state = State.FAILED, failure = "identity-conflict")
                        Result.Rejected -> current.copy(state = State.FAILED, failure = "server-rejected")
                        Result.Retryable -> if (current.attempts >= 8) current.copy(state = State.FAILED, failure = "retry-limit") else current
                    } })
                }
            }
        } finally { synchronized(this) { flushing = false } }
    }
    @Synchronized fun retry(requestId: String, nowMs: Long = System.currentTimeMillis()) {
        commit(entries.map { if (it.requestId == requestId && it.state == State.FAILED)
            it.copy(state = State.UNSYNCED, attempts = 0, nextAttemptAtMs = nowMs, failure = null) else it })
    }
    @Synchronized fun removeSynced() { commit(entries.filter { it.state != State.SYNCED }) }
    private fun commit(snapshot: List<Entry>) {
        val json = JSONArray()
        snapshot.forEach { e -> json.put(JSONObject().put("requestId", e.requestId).put("idempotencyKey", e.idempotencyKey)
            .put("body", e.body).put("sha256", e.sha256).put("metadata", e.metadata ?: JSONObject.NULL)
            .put("state", e.state.name).put("attempts", e.attempts).put("nextAttemptAtMs", e.nextAttemptAtMs).put("failure", e.failure ?: JSONObject.NULL)) }
        val encrypted = aead.encrypt(json.toString().toByteArray(Charsets.UTF_8), aad)
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temp = File.createTempFile("capture-outbox-", ".pending", parent)
        try {
            FileOutputStream(temp).use { it.write(encrypted); it.fd.sync() }
            check(temp.renameTo(file)) { "Atomic queue replacement failed" }
            entries = snapshot
        } finally { if (temp.exists()) temp.delete() }
    }
    private fun decode(text: String): List<Entry> {
        val list = JSONArray(text); require(list.length() <= capacity)
        return (0 until list.length()).map { i -> val e = list.getJSONObject(i)
            Entry(e.getString("requestId"), e.getString("idempotencyKey"), e.getString("body"), e.getString("sha256"),
                if (e.isNull("metadata")) null else e.getString("metadata"), State.valueOf(e.getString("state")),
                e.getInt("attempts"), e.getLong("nextAttemptAtMs"), if (e.isNull("failure")) null else e.getString("failure"))
                .also { require(it.sha256 == hash(it.body)) { "Corrupt queue body" } }
        }
    }
    companion object { fun hash(body: String): String = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
}
