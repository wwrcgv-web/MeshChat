package com.example.meshchat.ble

import java.util.UUID

/**
 * File d'attente des messages qui n'ont trouvé aucun pair.
 * Chaque message reste au maximum [MAX_AGE_MS] millisecondes, pour ne pas remplir la mémoire.
 */
class MeshQueue {
    private class Pending(val packet: MeshPacket, val createdAt: Long)

    private val items = mutableListOf<Pending>()

    @Synchronized
    fun add(packet: MeshPacket) {
        purge()
        items.add(Pending(packet, System.currentTimeMillis()))
        if (items.size > MAX_ITEMS) items.removeAt(0)
    }

    /** Retourne et retire tous les messages encore valides. */
    @Synchronized
    fun drain(): List<MeshPacket> {
        purge()
        val result = items.map { it.packet }
        items.clear()
        return result
    }

    @Synchronized
    fun size(): Int {
        purge()
        return items.size
    }

    private fun purge() {
        val limit = System.currentTimeMillis() - MAX_AGE_MS
        items.removeAll { it.createdAt < limit }
    }

    companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L
        const val MAX_ITEMS = 50
    }
}
