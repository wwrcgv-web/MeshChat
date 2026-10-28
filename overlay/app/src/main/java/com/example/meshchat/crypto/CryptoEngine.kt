package com.example.meshchat.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des messages privés.
 * - Chaque téléphone crée une paire de clés (courbe P-256).
 * - Deux téléphones combinent leurs clés et obtiennent le même secret, sans l'envoyer.
 * - Le secret sert à chiffrer avec AES-GCM (une modification est détectée).
 */
object CryptoEngine {

    /** Crée une nouvelle paire de clés pour ce téléphone. */
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** Clé publique au format standard, prête à être envoyée (environ 91 octets). */
    fun encodePublic(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** Relit une clé publique reçue d'un autre téléphone. */
    fun decodePublic(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /** Calcule le secret commun entre ma clé privée et la clé publique de l'autre téléphone. */
    fun sharedKey(myPrivate: PrivateKey, theirPublic: PublicKey): SecretKey {
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(myPrivate)
            doPhase(theirPublic, true)
            generateSecret()
        }
        val key = MessageDigest.getInstance("SHA-256").digest(secret)
        return SecretKeySpec(key, "AES")
    }

    /** Chiffre un texte. Le résultat contient le numéro aléatoire (12 octets) suivi des données chiffrées. */
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plain)
    }

    /** Déchiffre. Retourne null si les données sont altérées ou si la clé est fausse. */
    fun decrypt(data: ByteArray, key: SecretKey): ByteArray? {
        if (data.size < 12 + 16) return null
        return try {
            val nonce = data.copyOfRange(0, 12)
            val body = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.doFinal(body)
        } catch (e: Exception) {
            null
        }
    }
}
