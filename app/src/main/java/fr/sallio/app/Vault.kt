package fr.sallio.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted files in the app's private storage. The AES key lives in
 * the Android Keystore (hardware backed on most phones) and never leaves it,
 * so a copied file, a backup or a rooted file explorer only sees noise.
 */
class Vault(context: Context) {

    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun write(name: String, text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        // Write then rename, so a crash never leaves half a token behind.
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            tmp.renameTo(File(dir, name))
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            }
            cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // Key wiped (app data cleared, device reset): the file is useless.
            file.delete()
            null
        }
    }

    @Synchronized
    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private companion object {
        const val ALIAS = "sallio-vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
