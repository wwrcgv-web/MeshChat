package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Contenu qu'aucune clé ne déchiffre : on le note pour ne pas le réessayer à chaque relève. */
    private const val OPAQUE_EXPIRY_MS = 30 * 60 * 1000L
    private const val MAX_OPAQUE = 500
    /** Identifiants déjà traités, sauvegardés pour survivre au redémarrage du service. */
    private const val PROCESSED_FILE = "relay_processed.json"

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
    /** Empreintes des contenus indéchiffrables, avec leur date d'arrivée. */
    private val opaqueSeen = LinkedHashMap<String, Long>()
    /** Fichier de sauvegarde des identifiants traités (préférences locales de l'app). */
    private var processedFile: File? = null
    private var processedLoaded = false
    @Volatile private var processedDirty = false
    private var pollJob: Job? = null

    /** Portée et callback du service, gardés pour pouvoir déclencher une relève immédiate. */
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    @Volatile private var resultHandler: ((RelayResult) -> Unit)? = null
    /** Vrai pendant une relève : deux relèves ne tournent jamais en même temps. */
    private val polling = AtomicBoolean(false)

    /** Démarre la relève toutes les 5 secondes. Remplace une relève déjà en cours. */
    fun start(scope: CoroutineScope, context: Context, onResult: (RelayResult) -> Unit) {
        stop()
        val app = context.applicationContext
        this.scope = scope
        appContext = app
        resultHandler = onResult
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
        scope = null
        appContext = null
        resultHandler = null
    }

    /**
     * Demande une relève tout de suite, sans attendre le tour de 5 secondes.
     * Appelé quand l'écran revient au premier plan. Sans effet si le service ne fait pas tourner
     * la relève ; une relève déjà en cours n'est jamais doublée (voir [pollOnce]).
     */
    fun requestNow() {
        val s = scope ?: return
        val context = appContext ?: return
        val handler = resultHandler ?: return
        s.launch(Dispatchers.IO) {
            try {
                pollOnce(context, handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Relève à la demande : un échec ne casse rien, la boucle réessaiera
            }
        }
    }

    suspend fun pollOnce(context: Context, onResult: (RelayResult) -> Unit) {
        // Deux relèves ne doivent jamais s'exécuter en même temps sur la même boîte
        if (!polling.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                if (!RelayClient.isEnabled(context)) return@withContext
                ensureLoaded(context)
                try {
                    releverEtTraiter(context, onResult)
                } finally {
                    persistProcessed()
                }
            }
        } finally {
            polling.set(false)
        }
    }

    /**
     * Une relève : un seul appel au relais, puis tri et traitement de chaque contenu.
     * Rien n'est redemandé deux fois : les identifiants traités sont mémorisés, et un contenu
     * qu'aucune clé ne déchiffre est écarté pour un moment au lieu d'être repris à chaque tour.
     */
    private suspend fun releverEtTraiter(context: Context, onResult: (RelayResult) -> Unit) {
        val myHash = RelayDispatch.hacherDestinataire(LocalIdentity.myIdHex(context))
        val messages = RelayClient.relever(context, myHash)
        if (messages.isEmpty()) return

        // Les clés partagées sont calculées une seule fois par relève
        val keys = LocalIdentity.knownContacts(context).mapNotNull { contact ->
            LocalIdentity.sharedKeyWith(context, contact.id)?.let { contact.id to it }
        }
        if (keys.isEmpty()) return

        for (content in messages) {
            val fp = empreinte(content)
            if (opaqueRecent(fp)) continue
            val encrypted = try {
                Base64.decode(content, Base64.NO_WRAP)
            } catch (e: Exception) {
                noteOpaque(fp)
                continue
            }
            var open = false
            for ((contactId, key) in keys) {
                val clear = CryptoEngine.decrypt(encrypted, key) ?: continue
                open = true
                processClear(context, contactId, clear, key, onResult)
                break
            }
            if (!open) noteOpaque(fp)
        }
    }

    private suspend fun processClear(context: Context, senderId: String, clear: ByteArray, key: SecretKey, onResult: (RelayResult) -> Unit) {
        val text = String(clear, Charsets.UTF_8)
        when (classer(text)) {
            Kind.TEXTE -> processText(context, senderId, text, key, onResult)
            Kind.FICHIER -> processFile(context, senderId, text, key, onResult)
            Kind.ACCUSE -> processAck(text, onResult)
            Kind.INCONNU -> Unit
        }
    }

    /** Type d'un contenu déchiffré, d'après son préfixe. Fonction pure, vérifiable seule. */
    enum class Kind { TEXTE, FICHIER, ACCUSE, INCONNU }

    fun classer(texte: String): Kind = when {
        texte.startsWith(PREFIX_TEXT) -> Kind.TEXTE
        texte.startsWith(PREFIX_FILE) -> Kind.FICHIER
        texte.startsWith(PREFIX_ACK) -> Kind.ACCUSE
        else -> Kind.INCONNU
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
        processedDirty = true
        return true
    }

    /** Empreinte stable d'un contenu chiffré reçu : on ne retente pas indéfiniment le même. */
    private fun empreinte(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Vrai si ce contenu a déjà échoué au déchiffrement récemment. */
    private fun opaqueRecent(fp: String): Boolean = synchronized(this) {
        purgeOpaque()
        opaqueSeen.containsKey(fp)
    }

    private fun noteOpaque(fp: String) {
        synchronized(this) {
            purgeOpaque()
            if (opaqueSeen.size >= MAX_OPAQUE) {
                opaqueSeen.remove(opaqueSeen.keys.first())
            }
            opaqueSeen[fp] = System.currentTimeMillis()
        }
    }

    private fun purgeOpaque() {
        val limit = System.currentTimeMillis() - OPAQUE_EXPIRY_MS
        opaqueSeen.entries.removeAll { it.value < limit }
    }

    /** Charge les identifiants déjà traités, une seule fois par lancement du service. */
    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (processedLoaded) return
        processedLoaded = true
        val f = File(context.filesDir, PROCESSED_FILE)
        processedFile = f
        if (!f.exists()) return
        try {
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val id = array.optString(i)
                if (id.isNotEmpty()) processedIds.add(id)
            }
            while (processedIds.size > MAX_PROCESSED_IDS) processedIds.remove(processedIds.first())
        } catch (e: Exception) {
            // Fichier illisible : on repart d'un ensemble vide, sans bloquer la relève
        }
    }

    /** Écrit les identifiants traités sur le téléphone. Appelé depuis la relève, hors du fil écran. */
    private fun persistProcessed() {
        val f = processedFile ?: return
        val snapshot = snapshotIfDirty() ?: return
        try {
            val array = JSONArray()
            snapshot.forEach { array.put(it) }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            // Sauvegarde impossible : on réessaiera au prochain tour plutôt que de perdre la liste
            processedDirty = true
        }
    }

    @Synchronized
    private fun snapshotIfDirty(): List<String>? {
        if (!processedDirty) return null
        processedDirty = false
        return processedIds.toList()
    }
}
