package com.example.meshchat.ble

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Réserve de messages privés chiffrés, gardés pour un destinataire absent.
 * Si un fichier est donné, la réserve est sauvegardée et survit à un redémarrage du service.
 */
class MeshStore(private val file: File? = null) {
    class Item(val id: ByteArray, val tag: ByteArray, val cipher: ByteArray, val expiresAt: Long)

    private val items = ArrayList<Item>()

    init {
        load()
    }

    @Synchronized
    fun add(id: ByteArray, tag: ByteArray, cipher: ByteArray) {
        purge()
        if (items.any { it.id.contentEquals(id) }) return
        while (items.size >= MAX_ITEMS) items.removeAt(0)
        items.add(Item(id, tag, cipher, System.currentTimeMillis() + KEEP_MS))
        save()
    }

    @Synchronized
    fun forTag(tag: ByteArray): List<Item> {
        purge()
        return items.filter { it.tag.contentEquals(tag) }
    }

    /** Tous les messages gardés (les relais les transmettent aux nouveaux téléphones). */
    @Synchronized
    fun all(): List<Item> {
        purge()
        return items.toList()
    }

    /** Supprime un message gardé, dès que sa livraison est confirmée. */
    @Synchronized
    fun remove(id: ByteArray) {
        if (items.removeAll { it.id.contentEquals(id) }) save()
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        items.removeAll { it.expiresAt <= now }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun save() {
        val f = file ?: return
        try {
            val array = JSONArray()
            items.forEach {
                array.put(
                    JSONObject()
                        .put("i", b64(it.id))
                        .put("t", b64(it.tag))
                        .put("c", b64(it.cipher))
                        .put("e", it.expiresAt)
                )
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            // Sauvegarde impossible : la réserve reste en mémoire
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val array = JSONArray(f.readText())
            val now = System.currentTimeMillis()
            for (n in 0 until array.length()) {
                val o = array.getJSONObject(n)
                val expires = o.getLong("e")
                if (expires > now) {
                    items.add(Item(unb64(o.getString("i")), unb64(o.getString("t")), unb64(o.getString("c")), expires))
                }
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    companion object {
        /** Nombre maximal de messages gardés pour les autres. */
        const val MAX_ITEMS = 500
        /** Durée de conservation : 24 heures. */
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        const val TAG_SIZE = 8

        /** Ancienne étiquette : empreinte de l'identifiant public du destinataire. */
        fun tagFor(peerIdHex: String): ByteArray {
            val bytes = peerIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("meshchat-tag".toByteArray())
            return digest.digest(bytes).copyOfRange(0, TAG_SIZE)
        }

        /** Jour courant (UTC), compté en jours depuis 1970. */
        fun currentDay(nowMs: Long = System.currentTimeMillis()): Long = nowMs / 86_400_000L

        /** Boîte quotidienne : étiquette calculée à partir du secret partagé et du jour. */
        fun boxTag(sharedSecret: ByteArray, day: Long): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
            mac.update("meshchat-box".toByteArray())
            mac.update(day.toString().toByteArray())
            return mac.doFinal().copyOfRange(0, TAG_SIZE)
        }

        /** Les deux boîtes à tester à la réception : aujourd'hui et hier. */
        fun boxTagsToCheck(sharedSecret: ByteArray, nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
            val today = currentDay(nowMs)
            return listOf(boxTag(sharedSecret, today), boxTag(sharedSecret, today - 1))
        }
    }
}
