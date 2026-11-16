package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.crypto.CryptoEngine
import kotlinx.coroutines.*
import javax.crypto.SecretKey

/**
 * Relève unique et périodique des messages du relais Internet.
 * Corrige :
 * - Erreur 3 : une seule relève au lieu de trois.
 * - Erreur 4 : polling toutes les 5 secondes quand l'app est ouverte.
 */
object RelayPoll {

    private const val PERIOD_MS = 5_000L
    private const val MAX_PROCESSED_IDS = 2_000

    private const val PREFIX_TEXT = "ID:"
    private const val PREFIX_FILE = "CH:"
    private const val PREFIX_ACK = "REÇU:"

    /** Résultat d'une relève. */
    sealed class RelayResult {
        data class Text(val expediteurId: String, val msgId: String, val texte: String) : RelayResult()
        data class File(val expediteurId: String, val type: String, val donnees: ByteArray) : RelayResult()
        data class Ack(val msgId: String) : RelayResult()
    }

    private val pendingFileChunks = HashMap<String, MutableList<RelayChunks.MorceauRecu>>()
    private val processedIds = LinkedHashSet<String>()
    private var pollJob: Job? = null

    /** Démarre le polling régulier. */
    fun start(scope: CoroutineScope, context: Context, onResult: (RelayResult) -> Unit) {
        stop()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollOnce(context, onResult)
                delay(PERIOD_MS)
            }
        }
    }

    /** Arrête le polling. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Relève et traite une fois les messages du relais.
     * Peut aussi être appelée manuellement.
     */
    suspend fun pollOnce(context: Context, onResult: (RelayResult) -> Unit) {
        withContext(Dispatchers.IO) {
            if (!RelayClient.isEnabled(context)) return@withContext
            val manager = BleMeshManager.instance ?: return@withContext
            val monHache = RelayDispatch.hacherDestinataire(manager.myIdHex)
            val messages = RelayClient.relever(context, monHache)

            for (contenu in messages) {
                val chiffre = try {
                    Base64.decode(contenu, Base64.NO_WRAP)
                } catch (e: Exception) {
                    continue
                }

                var dechiffre = false
                for (contact in manager.knownContacts()) {
                    val cle = manager.sharedKeyWith(contact.id) ?: continue
                    val clair = CryptoEngine.decrypt(chiffre, cle) ?: continue

                    dechiffre = true
                    traiterClair(context, contact.id, clair, cle, onResult)
                    break
                }
            }
        }
    }

    private suspend fun traiterClair(
        context: Context,
        expediteurId: String,
        clair: ByteArray,
        cle: SecretKey,
        onResult: (RelayResult) -> Unit
    ) {
        val texte = String(clair, Charsets.UTF_8)

        when {
            texte.startsWith(PREFIX_TEXT) -> traiterTexte(context, expediteurId, texte, cle, onResult)
            texte.startsWith(PREFIX_FILE) -> traiterFichier(context, expediteurId, texte, cle, onResult)
            texte.startsWith(PREFIX_ACK) -> traiterAccuse(texte, onResult)
        }
    }

    private suspend fun traiterTexte(
        context: Context,
        expediteurId: String,
        texte: String,
        cle: SecretKey,
        onResult: (RelayResult) -> Unit
    ) {
        val sep = texte.indexOf('|')
        if (sep == -1) return
        val msgId = texte.substring(PREFIX_TEXT.length, sep)
        val corps = texte.substring(sep + 1)

        if (!marquerTraite("text:$msgId")) return

        // Envoie l'accusé en arrière-plan
        withContext(Dispatchers.IO) {
            RelayAck.deposerAccuse(context, expediteurId, msgId, cle)
        }

        withContext(Dispatchers.Main) {
            onResult(RelayResult.Text(expediteurId, msgId, corps))
        }
    }

    private suspend fun traiterFichier(
        context: Context,
        expediteurId: String,
        texte: String,
        cle: SecretKey,
        onResult: (RelayResult) -> Unit
    ) {
        val morceau = parseMorceau(texte) ?: return
        val dedupKey = "file:${morceau.fileId}:${morceau.numero}"
        if (!marquerTraite(dedupKey)) return

        val liste = pendingFileChunks.getOrPut(morceau.fileId) { mutableListOf() }
        if (liste.none { it.numero == morceau.numero }) {
            liste.add(morceau)
        }

        val donnees = RelayChunks.reassembler(liste)
        if (donnees == null) return // fichier encore incomplet

        pendingFileChunks.remove(morceau.fileId)
        marquerTraite("filecomplete:${morceau.fileId}")

        // Envoie l'accusé en arrière-plan
        withContext(Dispatchers.IO) {
            RelayAck.deposerAccuse(context, expediteurId, morceau.fileId, cle)
        }

        withContext(Dispatchers.Main) {
            onResult(RelayResult.File(expediteurId, morceau.type, donnees))
        }
    }

    private fun parseMorceau(texte: String): RelayChunks.MorceauRecu? {
        if (!texte.startsWith(PREFIX_FILE)) return null
        return try {
            val parties = texte.substring(PREFIX_FILE.length).split("|", limit = 5)
            if (parties.size != 5) return null
            RelayChunks.MorceauRecu(
                fileId = parties[0],
                numero = parties[1].toInt(),
                total = parties[2].toInt(),
                type = parties[3],
                bloc = Base64.decode(parties[4], Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun traiterAccuse(texte: String, onResult: (RelayResult) -> Unit) {
        val msgId = texte.removePrefix(PREFIX_ACK)
        if (!marquerTraite("ack:$msgId")) return
        withContext(Dispatchers.Main) {
            onResult(RelayResult.Ack(msgId))
        }
    }

    @Synchronized
    private fun marquerTraite(id: String): Boolean {
        if (id in processedIds) return false
        processedIds.add(id)
        // Évite que la mémoire ne grossisse indéfiniment
        while (processedIds.size > MAX_PROCESSED_IDS) {
            processedIds.remove(processedIds.first())
        }
        return true
    }
}
