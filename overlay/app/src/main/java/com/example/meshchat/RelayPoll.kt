package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.crypto.CryptoEngine
import kotlinx.coroutines.*
import javax.crypto.SecretKey

object RelayPoll {

    private const val PERIOD_MS = 5_000L
    private const val MAX_PROCESSED_IDS = 2_000

    private const val PREFIX_TEXT = "ID:"
    private const val PREFIX_FILE = "CH:"
    private const val PREFIX_ACK = "REÇU:"

    sealed class RelayResult {
        data class Text(val senderId: String, val msgId: String, val text: String) : RelayResult()
        data class File(val senderId: String, val type: String, val data: ByteArray) : RelayResult()
        data class Ack(val msgId: String) : RelayResult()
    }

    private val pendingFileChunks = HashMap<String, MutableList<RelayChunks.MorceauRecu>>()
    private val processedIds = LinkedHashSet<String>()
    private var pollJob: Job? = null

    fun start(scope: CoroutineScope, context: Context, onResult: (RelayResult) -> Unit) {
        stop()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollOnce(context, onResult)
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
            val manager = BleMeshManager.instance ?: return@withContext
            val myHash = RelayDispatch.hacherDestinataire(manager.myIdHex)
            val messages = RelayClient.relever(context, myHash)
            for (content in messages) {
                val encrypted = try {
                    Base64.decode(content, Base64.NO_WRAP)
                } catch (e: Exception) {
                    continue
                }
                var decrypted = false
                for (contact in manager.knownContacts()) {
                    val key = manager.sharedKeyWith(contact.id) ?: continue
                    val clear = CryptoEngine.decrypt(encrypted, key) ?: continue
                    decrypted = true
                    processClear(context, contact.id, clear, key, onResult)
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
        if (!markProcessed("text:$msgId")) return
        withContext(Dispatchers.IO) { depositAck(context, senderId, msgId, key) }
        withContext(Dispatchers.Main) { onResult(RelayResult.Text(senderId, msgId, body)) }
    }

    private suspend fun processFile(context: Context, senderId: String, text: String, key: SecretKey, onResult: (RelayResult) -> Unit) {
        val chunk = parseChunk(text) ?: return
        val dedupKey = "file:${chunk.fileId}:${chunk.numero}"
        if (!markProcessed(dedupKey)) return
        val list: MutableList<RelayChunks.MorceauRecu> = pendingFileChunks.getOrPut(chunk.fileId) {
            mutableListOf<RelayChunks.MorceauRecu>()
        }
        if (list.none { it.numero == chunk.numero }) {
            list.add(chunk)
        }
        val data = RelayChunks.reassembler(list)
        if (data == null) return
        pendingFileChunks.remove(chunk.fileId)
        markProcessed("filecomplete:${chunk.fileId}")
        withContext(Dispatchers.IO) { depositAck(context, senderId, chunk.fileId, key) }
        withContext(Dispatchers.Main) { onResult(RelayResult.File(senderId, chunk.type, data)) }
    }

    private fun parseChunk(text: String): RelayChunks.MorceauRecu? {
        if (!text.startsWith(PREFIX_FILE)) return null
        return try {
            val parts = text.substring(PREFIX_FILE.length).split("|", limit = 5)
            if (parts.size != 5) return null
            RelayChunks.MorceauRecu(
                fileId = parts[0],
                numero = parts[1].toInt(),
                total = parts[2].toInt(),
                type = parts[3],
                bloc = Base64.decode(parts[4], Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun processAck(text: String, onResult: (RelayResult) -> Unit) {
        val msgId = text.removePrefix(PREFIX_ACK)
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
