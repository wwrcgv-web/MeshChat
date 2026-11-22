package com.example.meshchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Clé d'appareil du relais Internet : elle remplacera à terme le jeton saisi à la main.
 *
 * La clé privée naît dans le Keystore Android et n'en sort jamais : la signature se fait par le
 * Keystore lui-même (matériel quand le téléphone le permet). Seule la clé publique est publiée,
 * pour l'enregistrement auprès du Worker puis la vérification de chaque requête.
 *
 * Rien n'est encore envoyé au réseau : le Worker ne sait pas encore vérifier ces signatures.
 * Ces fonctions sont prêtes pour le jour où il saura.
 */
object DeviceKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "samchat_device_key"

    /** Paire de signature de l'appareil, créée au premier usage. Null si le Keystore refuse. */
    @Synchronized
    private fun keyPair(): KeyPair? {
        return try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val private = store.getKey(ALIAS, null) as? PrivateKey
            if (private != null) {
                val public = store.getCertificate(ALIAS)?.publicKey ?: return null
                KeyPair(public, private)
            } else {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generator.generateKeyPair()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Clé publique au format standard, en hexadécimal : c'est ce que le Worker enregistrerait. */
    fun publicKeyHex(): String? =
        keyPair()?.public?.encoded?.joinToString("") { "%02x".format(it) }

    /** Identifiant d'appareil stable : empreinte de la clé publique, 32 caractères hexadécimaux. */
    fun deviceId(): String? {
        val public = keyPair()?.public?.encoded ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(public)
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Signe des données (SHA-256 + ECDSA) sans que la clé privée quitte le Keystore. */
    fun sign(data: ByteArray): ByteArray? {
        val private = keyPair()?.private ?: return null
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(private)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            null
        }
    }
}
