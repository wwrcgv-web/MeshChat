package com.example.meshchat.ble

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Réserve de messages privés chiffrés, gardés pour un destinataire absent.
 * Chaque téléphone garde les messages des autres : ainsi, un déplacement peut transporter un message
 * jusqu'à son destinataire.
 */
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
        /** Nombre maximal de messages gardés pour les autres. */
        const val MAX_ITEMS = 500
        /** Durée de conservation : 24 heures, pour que les déplacements transportent les messages. */
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        const val TAG_SIZE = 8

        /**
         * Ancienne étiquette : empreinte de l'identifiant public du destinataire.
         * Toujours utilisée par BleMeshManager tant que la boîte quotidienne n'est pas branchée.
         */
        fun tagFor(peerIdHex: String): ByteArray {
            val bytes = peerIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("meshchat-tag".toByteArray())
            return digest.digest(bytes).copyOfRange(0, TAG_SIZE)
        }

        /** Jour courant (UTC), compté en jours depuis 1970. Les deux téléphones calculent donc le même jour. */
        fun currentDay(nowMs: Long = System.currentTimeMillis()): Long = nowMs / 86_400_000L

        /**
         * Boîte quotidienne : étiquette calculée à partir du secret partagé entre deux personnes et du jour.
         * Seuls l'expéditeur et le destinataire peuvent la calculer. Elle change chaque jour.
         */
        fun boxTag(sharedSecret: ByteArray, day: Long): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
            mac.update("meshchat-box".toByteArray())
            mac.update(day.toString().toByteArray())
            return mac.doFinal().copyOfRange(0, TAG_SIZE)
        }

        /**
         * Les deux boîtes à tester à la réception : celle d'aujourd'hui et celle d'hier.
         * Cela couvre le passage de minuit entre deux téléphones qui ne sont pas à la même seconde.
         */
        fun boxTagsToCheck(sharedSecret: ByteArray, nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
            val today = currentDay(nowMs)
            return listOf(boxTag(sharedSecret, today), boxTag(sharedSecret, today - 1))
        }
    }
}
