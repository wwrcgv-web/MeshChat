package com.example.meshchat.ble

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Un message MeshChat tel qu'il voyage entre les téléphones.
 *
 * Format binaire :
 * [id 16 octets][expéditeur 8 octets][destinataire 8 octets][TTL 1 octet][longueur 2 octets][texte UTF-8]
 *
 * Destinataire = 8 octets à zéro : message pour tout le monde.
 */
data class MeshPacket(
    val id: UUID,
    val sender: ByteArray,
    val recipient: ByteArray,
    val ttl: Int,
    val text: String
) {
    fun encode(): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(16 + 8 + 8 + 1 + 2 + body.size)
        buf.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        buf.put(sender)
        buf.put(recipient)
        buf.put(ttl.toByte())
        buf.putShort(body.size.toShort())
        buf.put(body)
        return buf.array()
    }

    /** Un message n'est pas pour moi, mais il peut encore être relayé si son TTL est > 0. */
    fun canRelay(): Boolean = ttl > 0

    fun withDecrementedTtl(): MeshPacket = copy(ttl = ttl - 1)

    override fun equals(other: Any?): Boolean =
        other is MeshPacket && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val DEFAULT_TTL = 5
        const val ID_SIZE = 8

        /** Identifiant court et fixe de ce téléphone, généré une seule fois. */
        fun newPeerId(): ByteArray = ByteArray(ID_SIZE).also { SecureRandom().nextBytes(it) }

        fun newMessage(sender: ByteArray, text: String): MeshPacket =
            MeshPacket(UUID.randomUUID(), sender, ByteArray(ID_SIZE), DEFAULT_TTL, text)

        fun decode(data: ByteArray): MeshPacket? {
            return try {
                val buf = ByteBuffer.wrap(data)
                val id = UUID(buf.long, buf.long)
                val sender = ByteArray(ID_SIZE).also { buf.get(it) }
                val recipient = ByteArray(ID_SIZE).also { buf.get(it) }
                val ttl = buf.get().toInt() and 0xFF
                val len = buf.short.toInt() and 0xFFFF
                val body = ByteArray(len).also { buf.get(it) }
                MeshPacket(id, sender, recipient, ttl, String(body, Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }
}
