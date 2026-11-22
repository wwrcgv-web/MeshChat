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

    /**
     * Convertit une signature ECDSA au format DER (ce que produit Android) en 64 octets
     * r||s, format attendu par WebCrypto côté Worker. Null si la signature est mal formée.
     */
    fun derToRaw(der: ByteArray): ByteArray? {
        return try {
            var i = 0
            if (der[i++] != 0x30.toByte()) return null
            val len = der[i++].toInt() and 0xFF
            if (len and 0x80 != 0) i += len and 0x7F
            if (der[i++] != 0x02.toByte()) return null
            val rLen = der[i++].toInt() and 0xFF
            val r = der.copyOfRange(i, i + rLen)
            i += rLen
            if (der[i++] != 0x02.toByte()) return null
            val sLen = der[i++].toInt() and 0xFF
            val s = der.copyOfRange(i, i + sLen)
            val out = ByteArray(64)
            if (!poserEntier(r, out, 0)) return null
            if (!poserEntier(s, out, 32)) return null
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Écrit un entier DER dans 32 octets, en retirant les zéros de tête. */
    private fun poserEntier(src: ByteArray, dst: ByteArray, offset: Int): Boolean {
        var s = src
        while (s.size > 32 && s[0] == 0.toByte()) s = s.copyOfRange(1, s.size)
        if (s.size > 32) return false
        System.arraycopy(s, 0, dst, offset + (32 - s.size), s.size)
        return true
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
