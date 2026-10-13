package io.vedika.sdk.ar

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/** API 24+ (module floor). Never silently replaces a missing/invalidated key.
 * Store in noBackupFilesDir and exclude the keyset preferences from Auto Backup
 * and device transfer; ciphertext must not migrate without its Keystore key. */
object AndroidCaptureStore {
    @Synchronized fun open(context: Context): CaptureOutbox {
        AeadConfig.register()
        val app = context.applicationContext
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(app, "capture-aead-v1", "vedika_capture_keyset")
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri("android-keystore://vedika-capture-master-v1")
            .build()
        check(manager.isUsingKeystore) { "Encrypted outbox requires Android Keystore" }
        val aead = manager.keysetHandle.getPrimitive(Aead::class.java)
        return CaptureOutbox(File(app.noBackupFilesDir, "vedika-capture-outbox.aead"), aead)
    }
}
