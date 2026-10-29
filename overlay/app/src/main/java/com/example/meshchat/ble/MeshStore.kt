package com.example.meshchat.ble

import java.security.MessageDigest

/** Réserve de messages privés chiffrés gardés pour un destinataire absent. */
class MeshStore {
    class Item(val id: ByteArray, val tag: ByteArray, val cipher: ByteArray, val expiresAt: Long)

    private val items = ArrayList<Item>()

    @Synchronized
    fun add(id: ByteArray, tag: ByteArray, cipher: ByteArray) {
        purge()
        if (items.any { it.id.contentEquals(id) }) return
        while (items.size >= MAX_ITEMS) items.removeAt(0)
        items.add(Item(id, tag, cipher, System.currentTimeMillis() + KEEP_MS))
    }

    @Synchronized
    fun forTag(tag: ByteArray): List<Item> {
        purge()
        return items.filter { it.tag.contentEquals(tag) }
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        items.removeAll { it.expiresAt <= now }
    }

    companion object {
        const val MAX_ITEMS = 50
        const val KEEP_MS = 10 * 60 * 1000L
        const val TAG_SIZE = 8

        /** Étiquette = empreinte de l'identifiant du destinataire (on ne peut pas remonter à l'identifiant). */
        fun tagFor(peerIdHex: String): ByteArray {
            val bytes = peerIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("meshchat-tag".toByteArray())
            return digest.digest(bytes).copyOfRange(0, TAG_SIZE)
        }
    }
}
