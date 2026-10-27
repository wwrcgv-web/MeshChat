package com.example.meshchat.ble

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Découpe un gros contenu (image, vocal) en fragments, et les reconstitue à la réception.
 *
 * Format d'un fragment :
 * [id du contenu 8 octets][numéro 2 octets][total 2 octets][données]
 *
 * Taille maximale d'un fragment : 212 octets, pour passer dans un envoi Bluetooth (244 octets utiles).
 */
object MeshChunker {
    const val CHUNK_SIZE = 200
    const val HEADER_SIZE = 12
    const val MAX_TOTAL_BYTES = 1024 * 1024
    const val ASSEMBLY_TIMEOUT_MS = 30_000L

    fun newContentId(): ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }

    /** Découpe les données en fragments prêts à être envoyés. */
    fun split(contentId: ByteArray, data: ByteArray): List<ByteArray> {
        require(data.size <= MAX_TOTAL_BYTES) { "Contenu trop grand (1 Mo maximum)" }
        val total = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        return (0 until total).map { index ->
            val start = index * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.size)
            val buf = ByteBuffer.allocate(HEADER_SIZE + (end - start))
            buf.put(contentId)
            buf.putShort(index.toShort())
            buf.putShort(total.toShort())
            buf.put(data, start, end - start)
            buf.array()
        }
    }

    /** Un contenu en cours de réception. */
    class Assembly(val total: Int) {
        private val parts = arrayOfNulls<ByteArray>(total)
        val createdAt: Long = System.currentTimeMillis()

        fun add(index: Int, data: ByteArray) {
            if (index in 0 until total) parts[index] = data
        }

        fun isComplete(): Boolean = parts.all { it != null }

        fun assemble(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            parts.forEach { out.write(it!!) }
            return out.toByteArray()
        }

        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > ASSEMBLY_TIMEOUT_MS
    }

    /** Lit l'en-tête d'un fragment reçu. Retourne null si le fragment est mal formé. */
    fun parse(fragment: ByteArray): Triple<String, Int, Pair<Int, ByteArray>>? {
        if (fragment.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(fragment)
        val id = ByteArray(8).also { buf.get(it) }
        val index = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        if (total == 0 || index >= total || total * CHUNK_SIZE > MAX_TOTAL_BYTES * 2) return null
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        val key = id.joinToString("") { "%02x".format(it) }
        return Triple(key, total, Pair(index, data))
    }
}
