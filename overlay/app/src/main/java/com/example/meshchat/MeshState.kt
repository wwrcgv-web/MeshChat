package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un message affiché dans une conversation. */
data class ChatBubble(
    val mine: Boolean,
    val author: String,
    val text: String,
    val image: Bitmap? = null,
    val chat: String,
    val audio: String? = null,
    val time: String = timeNow(),
    val status: String? = null,
    val msgId: String? = null
)

/** Ordre d'avancement d'un statut : un message ne revient jamais à un statut moins avancé. */
private fun rank(status: String?): Int = when (status) {
    "✓✓ Lu" -> 5
    "✓✓ Reçu" -> 4
    "✓ Parti" -> 3
    "⏳ En attente" -> 2
    "✗ Non envoyé" -> 1
    else -> 0
}

/**
 * Mémoire de l'application : journal, conversations, pairs.
 * Elle vit tant que le service tourne, donc les messages reçus écran fermé sont gardés.
 */
object MeshState {
    const val PUBLIC = "public"
    private const val KIND_PHOTO: Byte = 1
    private const val KIND_AUDIO: Byte = 2
    private const val MAX_JOURNAL = 200
    private const val MAX_BUBBLES = 500
    private const val MAX_STATUSES = 1000

    val journal = mutableStateListOf("Samchat prêt.")
    val bubbles = mutableStateListOf<ChatBubble>()
    val counts = mutableStateMapOf<String, Int>()
    var peers by mutableStateOf(listOf<MeshPeer>())
    var pseudo by mutableStateOf("anon0000")
    /** Nombre de pairs trouvés par Wi-Fi Aware (rempli par le service). */
    var wifiPeers by mutableStateOf(0)

    /** Dernier statut connu de chaque message privé, pour les bulles créées après l'arrivée du statut. */
    private val statusById = HashMap<String, String>()

    private var appContext: Context? = null

    /** Branché une seule fois, par le service. Les messages arrivent ensuite, écran fermé ou non. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        BleMeshManager.listener = { line -> onLine(line) }
        BleMeshManager.peersListener = { list -> peers = list }
        BleMeshManager.blobListener = { bytes, from -> onBlob(bytes, from) }
    }

    fun addJournal(line: String) {
        journal.add(line)
        if (journal.size > MAX_JOURNAL) journal.removeAt(0)
    }

    fun addBubble(b: ChatBubble) {
        // Si le statut est arrivé avant la bulle (envoi sans pair à portée), on l'applique tout de suite
        val known = b.msgId?.let { statusById[it] }
        val shown = if (known != null && rank(known) > rank(b.status)) b.copy(status = known) else b
        bubbles.add(shown)
        if (bubbles.size > MAX_BUBBLES) bubbles.removeAt(0)
    }

    /** Met à jour le statut d'un message privé, sans jamais revenir à un statut moins avancé. */
    private fun setStatus(id: String, status: String) {
        if (rank(status) < rank(statusById[id])) return
        if (statusById.size > MAX_STATUSES) statusById.clear()
        statusById[id] = status
        for (i in bubbles.indices) {
            val b = bubbles[i]
            if (b.msgId == id && rank(status) >= rank(b.status)) bubbles[i] = b.copy(status = status)
        }
    }

    private fun count(key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private fun onLine(line: String) {
        when {
            line.startsWith("Mon identifiant : ") -> {
                pseudo = pseudoFrom(line.removePrefix("Mon identifiant : ").trim())
                addJournal(line)
            }
            line.startsWith("Statut ") -> {
                // « Statut <identifiant> reçu » ou « Statut <identifiant> lu »
                val rest = line.removePrefix("Statut ")
                val id = rest.substringBefore(" ")
                setStatus(id, if (rest.endsWith("lu")) "✓✓ Lu" else "✓✓ Reçu")
            }
            line.startsWith("Parti ") -> {
                // Au moins un téléphone a reçu tous les morceaux
                setStatus(line.removePrefix("Parti ").trim(), "✓ Parti")
                addJournal(line)
            }
            line.startsWith("Non envoyé ") -> {
                count("fail")
                setStatus(line.removePrefix("Non envoyé ").trim(), "✗ Non envoyé")
                addJournal(line)
            }
            line.startsWith("En attente ") -> {
                setStatus(line.removePrefix("En attente ").trim(), "⏳ En attente")
                addJournal(line)
            }
            line.startsWith("Reçu privé de ") -> {
                count("in_text")
                val rest = line.removePrefix("Reçu privé de ")
                val sender = rest.substringBefore(" : ")
                val text = rest.substringAfter(" : ", "")
                addBubble(ChatBubble(false, sender, text, null, sender))
            }
            line.startsWith("Reçu de ") -> {
                count("in_text")
                val rest = line.removePrefix("Reçu de ")
                val sender = rest.substringBefore(" : ")
                val text = rest.substringAfter(" : ", "")
                addBubble(ChatBubble(false, sender, text, null, PUBLIC))
            }
            line.startsWith("Pair trouvé") -> addJournal(line)
            line.startsWith("Fichier reçu") -> { count("in_file"); addJournal(line) }
            line.startsWith("Envoyé à") -> { count("out_ok"); addJournal(line) }
            line.startsWith("Envoi du fichier") -> { count("out_file"); addJournal(line) }
            line.startsWith("Relais vers") -> { count("relay"); addJournal(line) }
            line.startsWith("Aucun pair") -> { count("no_peer"); addJournal(line) }
            line.contains("chec") -> { count("fail"); addJournal(line) }
            else -> addJournal(line)
        }
    }

    /** Photo ou vocal complet reçu. Le premier octet indique le type. */
    private fun onBlob(bytes: ByteArray, from: String?) {
        val context = appContext ?: return
        if (bytes.isNotEmpty() && bytes[0] == KIND_AUDIO) {
            val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
            file.writeBytes(bytes.copyOfRange(1, bytes.size))
            addBubble(ChatBubble(false, from ?: "vocal", "Vocal reçu", null, from ?: PUBLIC, file.absolutePath))
        } else if (bytes.isNotEmpty() && bytes[0] == KIND_PHOTO) {
            BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1)?.let { photo ->
                if (from == null) {
                    addBubble(ChatBubble(false, "photo", "Photo reçue", photo, PUBLIC))
                } else {
                    addBubble(ChatBubble(false, from, "Photo reçue", photo, from))
                }
            }
        }
    }
}

private fun timeNow(): String = SimpleDateFormat("dd MMM HH:mm", Locale.FRANCE).format(Date())

/** Pseudo lisible à partir de l'identifiant Bluetooth, par exemple anon1053. */
private fun pseudoFrom(shortId: String): String {
    val number = (shortId.toLongOrNull(16) ?: 0L) % 10000L
    return "anon" + number.toString().padStart(4, '0')
}
