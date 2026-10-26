package io.vedika.sdk.ar

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class PublicApiTest {
    @Test fun captureQueueAndRelocalizerArePublicAndRetainTheirEntryPoints() {
        val expected = mapOf(
            LocalOverlayRelocalizer::class.java to setOf("add", "evaluate", "clear"),
            CaptureOutbox::class.java to setOf("enqueue", "records", "flush", "retry", "removeSynced"),
            AndroidCaptureStore::class.java to setOf("open"),
            CaptureUploadAdapter::class.java to setOf("upload"),
            VastuArCoreUploader::class.java to setOf("upload", "saveCapture"),
            FloorMerge::class.java to setOf("prepare", "merge"),
            FloorMergeSession::class.java to setOf("addFloor", "addPick", "canMerge", "prepare", "merge"),
            VastuArCoreCaptureView::class.java to setOf("showRemedies", "clearRemedies"),
        )
        expected.forEach { (type, names) ->
            assertTrue("${type.name} must be public", Modifier.isPublic(type.modifiers))
            val public = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet()
            assertTrue("${type.name} missing ${names - public}", public.containsAll(names))
        }
    }

    @Test fun publishedTestsContainAllSharedCaptureFixtures() {
        val directory = requireNotNull(javaClass.classLoader).getResource("vastu-room-capture")
        assertNotNull("Mirror tests must run the shared fixtures instead of skipping them", directory)
        val files = java.io.File(directory!!.toURI()).listFiles { f -> f.extension == "json" }!!
        assertEquals(7, files.size)
    }
}
