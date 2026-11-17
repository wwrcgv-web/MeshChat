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

    /** Chiffre les octets avec la clé du Keystore. Retourne IV (12 octets) + données chiffrées. */
    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Déchiffre. Retourne null si la clé du Keystore a disparu (ex. réinitialisation du téléphone). */
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

    /** Enregistre la clé privée (octets PKCS#8) sous forme protégée, et supprime l'ancienne version en clair. */
    fun savePrivate(context: Context, privateBytes: ByteArray) {
        val wrapped = wrap(privateBytes).toHex()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRIV_WRAPPED, wrapped)
            .remove(KEY_PRIV_OLD)
            .apply()
    }

    /**
     * Relit la clé privée. Si un ancien téléphone a encore la clé en clair, elle est protégée
     * au passage (migration automatique).
     */
    fun loadPrivate(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PRIV_WRAPPED, null)?.let { return unwrap(it.hexToBytes()) }
        val oldHex = prefs.getString(KEY_PRIV_OLD, null) ?: return null
        val plain = oldHex.hexToBytes()
        savePrivate(context, plain)
        return plain
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
