package com.example.meshchat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protège la clé privée de ce téléphone avec une clé AES stockée dans le Keystore Android.
 * La clé AES ne quitte jamais le matériel de sécurité. Les préférences ne contiennent que du chiffré.
 */
object SecureKeys {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "samchat_wrap_key"
    private const val PREFS = "meshchat"
    private const val KEY_PUB = "my_pub"
    private const val KEY_PRIV_WRAPPED = "my_priv_wrapped"
    private const val KEY_PRIV_OLD = "my_priv"

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(data: ByteArray): ByteArray? {
        if (data.size < 12 + 16) return null
        return try {
            val iv = data.copyOfRange(0, 12)
            val body = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(body)
        } catch (e: Exception) {
            null
        }
    }

    /** Enregistre la paire de clés en une seule opération : clé publique en clair, clé privée protégée. */
    fun savePair(context: Context, publicHex: String, privateBytes: ByteArray) {
        val wrapped = wrap(privateBytes).toHex()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PUB, publicHex)
            .putString(KEY_PRIV_WRAPPED, wrapped)
            .remove(KEY_PRIV_OLD)
            .commit()
    }

    /**
     * Relit la clé privée. Une clé en clair trouvée dans les préférences (ancienne version
     * ou restauration de compte) est reprise et protégée tout de suite.
     */
    fun loadPrivate(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldHex = prefs.getString(KEY_PRIV_OLD, null)
        val pubHex = prefs.getString(KEY_PUB, null)
        if (oldHex != null && pubHex != null) {
            val plain = try { oldHex.hexToBytes() } catch (e: Exception) { return null }
            savePair(context, pubHex, plain)
            return plain
        }
        return prefs.getString(KEY_PRIV_WRAPPED, null)?.let { unwrap(it.hexToBytes()) }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
