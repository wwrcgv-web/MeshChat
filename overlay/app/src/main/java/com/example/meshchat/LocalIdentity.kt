package com.example.meshchat

import android.content.Context
import android.content.SharedPreferences
import com.example.meshchat.ble.MeshPacket
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.crypto.CryptoEngine
import com.example.meshchat.crypto.SecureKeys
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.SecretKey

/**
 * Identité locale : identifiant, clés et contacts. Source unique de la paire de clés de ce téléphone.
 * Ne dépend pas de BleMeshManager, donc fonctionne même si le service BLE est arrêté.
 */
object LocalIdentity {

    private const val PREFS = "meshchat"
    private const val KEY_MY_ID = "my_id"
    private const val KEY_MY_PUB = "my_pub"
    private const val PREFIX_PEER_KEY = "peer_"
    private const val PREFIX_PEER_NAME = "name_"

    /** Paire gardée en mémoire : évite de rouvrir le Keystore à chaque message. */
    @Volatile private var cached: KeyPair? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Identifiant hexadécimal de ce téléphone. */
    fun myIdHex(context: Context): String {
        val saved = prefs(context).getString(KEY_MY_ID, null)
        if (saved != null) return saved
        val id = MeshPacket.newPeerId()
        val hex = id.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_MY_ID, hex).apply()
        return hex
    }

    /** Paire de clés EC P-256 de ce téléphone. Un seul thread à la fois peut la créer. */
    @Synchronized
    fun myKeyPair(context: Context): KeyPair {
        cached?.let { return it }
        val pair = loadKeyPair(context) ?: generateAndSaveKeyPair(context)
        cached = pair
        return pair
    }

    /** À appeler après une restauration de compte : force la relecture des clés. */
    @Synchronized
    fun invalidateCache() {
        cached = null
    }

    private fun loadKeyPair(context: Context): KeyPair? {
        val pubHex = prefs(context).getString(KEY_MY_PUB, null) ?: return null
        val privBytes = SecureKeys.loadPrivate(context) ?: return null
        return try {
            val factory = KeyFactory.getInstance("EC")
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(pubHex.hexToBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun generateAndSaveKeyPair(context: Context): KeyPair {
        val pair = CryptoEngine.newKeyPair()
        SecureKeys.savePair(context, pair.public.encoded.toHex(), pair.private.encoded)
        return pair
    }

    /** Clé publique d'identité de ce téléphone, en hexadécimal (SPKI DER). */
    fun myPublicHex(context: Context): String = myKeyPair(context).public.encoded.toHex()

    /** Clé publique d'un contact en hexadécimal, ou null s'il est inconnu. */
    fun peerPublicHex(context: Context, peerId: String): String? =
        prefs(context).getString(PREFIX_PEER_KEY + peerId, null)

    /**
     * Signe des données avec la clé d'identité et renvoie 64 octets r||s (format WebCrypto).
     * Sert à prouver au relais que cette boîte appartient bien à cette identité.
     */
    fun signerAvecIdentite(context: Context, data: ByteArray): ByteArray? {
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(myKeyPair(context).private)
            signature.update(data)
            CryptoEngine.derToRaw(signature.sign())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Auto-test du provider : la clé d'identité signe-t-elle vraiment ? Certains téléphones
     * pourraient refuser l'ECDSA sur une clé née pour l'ECDH ; on préfère le savoir au démarrage.
     */
    fun autoTestSignature(context: Context): Boolean {
        return try {
            val paire = myKeyPair(context)
            val donnees = "samchat-selftest".toByteArray(Charsets.UTF_8)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(paire.private)
            signature.update(donnees)
            val sig = signature.sign()
            signature.initVerify(paire.public)
            signature.update(donnees)
            signature.verify(sig)
        } catch (e: Exception) {
            false
        }
    }

    /** Liste des contacts connus (clés publiques enregistrées). */
    fun knownContacts(context: Context): List<MeshPeer> {
        val p = prefs(context)
        return p.all.keys
            .filter { it.startsWith(PREFIX_PEER_KEY) }
            .map { key ->
                val id = key.removePrefix(PREFIX_PEER_KEY)
                val name = p.getString(PREFIX_PEER_NAME + id, "") ?: ""
                MeshPeer(id, name)
            }
            .sortedBy { it.id }
    }

    /** Clé partagée avec un contact, calculée à partir des préférences. */
    fun sharedKeyWith(context: Context, peerId: String): SecretKey? {
        val pubHex = prefs(context).getString(PREFIX_PEER_KEY + peerId, null) ?: return null
        return try {
            val factory = KeyFactory.getInstance("EC")
            val publicKey = factory.generatePublic(X509EncodedKeySpec(pubHex.hexToBytes()))
            CryptoEngine.sharedKey(myKeyPair(context).private, publicKey)
        } catch (e: Exception) {
            null
        }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
