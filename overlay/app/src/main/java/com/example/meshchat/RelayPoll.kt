package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKey

object RelayPoll {

    private const val PERIOD_MS = 5_000L
    private const val MAX_PROCESSED_IDS = 5_000
    private const val MAX_PENDING_FILES = 20
    private const val MAX_CHUNKS = 250
    private const val PENDING_EXPIRY_MS = 30 * 60 * 1000L
    private const val MAX_TEXT_CHARS = 10_000
    private const val MAX_ID_CHARS = 32
    private const val MAX_SMALL_BYTES = 10 * 1024 * 1024

    private const val PREFIX_TEXT = "ID:"
    private const val PREFIX_FILE = "CH:"
    private const val PREFIX_ACK = "REÇU:"

    private val ALLOWED_TYPES = setOf("photo", "audio", "video")
    private val ID_PATTERN = Regex("[0-9a-f]{1,$MAX_ID_CHARS}")

    sealed class RelayResult {
        data class Text(val senderId: String, val msgId: String, val text: String) : RelayResult()
        data class File(val senderId: String, val type: String, val data: ByteArray) : RelayResult()
        data class Ack(val msgId: String) : RelayResult()
    }

    /** Un fichier en cours de réception : ses morceaux arrivés, sa taille, son âge. */
    private class PendingFile(val total: Int, val type: String, val createdAt: Long) {
        val chunks = HashMap<Int, ByteArray>()
        var bytes = 0L
    }

    private val pending = LinkedHashMap<String, PendingFile>()
    private val processedIds = LinkedHashSet<String>()
    private var pollJob: Job? = null

    /** Démarre la relève toutes les 5 secondes. Remplace une relève déjà en cours. */
    fun start(scope: CoroutineScope, context: Context, onResult: (RelayResult) -> Unit) {
        stop()
        val app = context.applicationContext
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    pollOnce(app, onResult)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Une erreur ne doit jamais arrêter la relève : on réessaie au prochain tour
                }
                delay(PERIOD_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun pollOnce(context: Context, onResult: (RelayResult) -> Unit) {
        withContext(Dispatchers.IO) {
            if (!RelayClient.isEnabled(context)) return@withContext
            val myHash = RelayDispatch.hacherDestinataire(LocalIdentity.myIdHex(context))
            val messages = RelayClient.relever(context, myHash)
            if (messages.isEmpty()) return@withContext

            // Les clés partagées sont calculées une seule fois par relève
            val keys = LocalIdentity.knownContacts(context).mapNotNull { contact ->
                LocalIdentity.sharedKeyWith(context, contact.id)?.let { contact.id to it }
            }
            if (keys.isEmpty()) return@withContext

            for (content in messages) {
                val encrypted = try {
                    Base64.decode(content, Base64.NO_WRAP)
                } catch (e: Exception) {
                    continue
                }
                for ((contactId, key) in keys) {
                    val clear = CryptoEngine.decrypt(encrypted, key) ?: continue
                    processClear(context, contactId, clear, key, onResult)
                    break
                }
            }
        }
    }

    private suspend fun processClear(context: Context, senderId: String, clear: ByteArray, key: SecretKey, onResult: (RelayResult) -> Unit) {
        val text = String(clear, Charsets.UTF_8)
        when {
            text.startsWith(PREFIX_TEXT) -> processText(context, senderId, text, key, onResult)
            text.startsWith(PREFIX_FILE) -> processFile(context, senderId, text, key, onResult)
            text.startsWith(PREFIX_ACK) -> processAck(text, onResult)
        }
    }

    private suspend fun processText(context: Context, senderId: String, text: String, key: SecretKey, onResult: (RelayResult) -> Unit) {
        val sep = text.indexOf('|')
        if (sep == -1) return
        val msgId = text.substring(PREFIX_TEXT.length, sep)
        val body = text.substring(sep + 1)
        if (!ID_PATTERN.matches(msgId) || body.length > MAX_TEXT_CHARS) return
        if (!markProcessed("text:$msgId")) return
        withContext(Dispatchers.IO) { depositAck(context, senderId, msgId, key) }
        withContext(Dispatchers.Main) { onResult(RelayResult.Text(senderId, msgId, body)) }
    }

    private suspend fun processFile(context: Context, senderId: String, text: String, key: SecretKey, onResult: (RelayResult) -> Unit) {
        val chunk = parseChunk(text) ?: return
        val data = storeChunk(chunk) ?: return
        withContext(Dispatchers.IO) { depositAck(context, senderId, chunk.fileId, key) }
        withContext(Dispatchers.Main) { onResult(RelayResult.File(senderId, chunk.type, data)) }
    }

    /**
     * Range un morceau. Retourne le fichier complet quand tous les morceaux sont là, sinon null.
     * Refuse les morceaux incohérents, trop nombreux ou trop gros.
     */
    @Synchronized
    private fun storeChunk(c: RelayChunks.MorceauRecu): ByteArray? {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAt > PENDING_EXPIRY_MS }
        if ("filecomplete:${c.fileId}" in processedIds) return null
        if (!markProcessed("file:${c.fileId}:${c.numero}")) return null

        var file = pending[c.fileId]
        if (file == null) {
            if (pending.size >= MAX_PENDING_FILES) {
                pending.entries.minByOrNull { it.value.createdAt }?.let { pending.remove(it.key) }
            }
            file = PendingFile(c.total, c.type, now)
            pending[c.fileId] = file
        }
        if (file.total != c.total || file.type != c.type) return null

        val limit = if (c.type == "video") RelayFile.MAX_VIDEO_BYTES.toLong() else MAX_SMALL_BYTES.toLong()
        if (file.bytes + c.bloc.size > limit) {
            pending.remove(c.fileId)
            return null
        }
        if (file.chunks.containsKey(c.numero)) return null
        file.chunks[c.numero] = c.bloc
        file.bytes += c.bloc.size
        if (file.chunks.size < file.total) return null

        pending.remove(c.fileId)
        markProcessed("filecomplete:${c.fileId}")
        val out = ByteArrayOutputStream(file.bytes.toInt())
        for (i in 1..file.total) {
            val bloc = file.chunks[i] ?: return null
            out.write(bloc)
        }
        return out.toByteArray()
    }

    private fun parseChunk(text: String): RelayChunks.MorceauRecu? {
        if (!text.startsWith(PREFIX_FILE)) return null
        return try {
            val parts = text.substring(PREFIX_FILE.length).split("|", limit = 5)
            if (parts.size != 5) return null
            val fileId = parts[0]
            val numero = parts[1].toInt()
            val total = parts[2].toInt()
            val type = parts[3]
            if (!ID_PATTERN.matches(fileId)) return null
            if (total < 1 || total > MAX_CHUNKS || numero < 1 || numero > total) return null
            if (type !in ALLOWED_TYPES) return null
            RelayChunks.MorceauRecu(
                fileId = fileId,
                numero = numero,
                total = total,
                type = type,
                bloc = Base64.decode(parts[4], Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun processAck(text: String, onResult: (RelayResult) -> Unit) {
        val msgId = text.removePrefix(PREFIX_ACK)
        if (!ID_PATTERN.matches(msgId)) return
        if (!markProcessed("ack:$msgId")) return
        withContext(Dispatchers.Main) { onResult(RelayResult.Ack(msgId)) }
    }

    private fun depositAck(context: Context, recipientId: String, messageId: String, key: SecretKey): Boolean {
        if (!RelayClient.isEnabled(context)) return false
        return try {
            val encrypted = CryptoEngine.encrypt((PREFIX_ACK + messageId).toByteArray(Charsets.UTF_8), key)
            val content = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            RelayClient.deposer(context, RelayDispatch.hacherDestinataire(recipientId), content)
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    private fun markProcessed(id: String): Boolean {
        if (id in processedIds) return false
        processedIds.add(id)
        while (processedIds.size > MAX_PROCESSED_IDS) {
            processedIds.remove(processedIds.first())
        }
        return true
    }
}
