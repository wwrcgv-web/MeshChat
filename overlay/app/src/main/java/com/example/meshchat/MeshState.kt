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

    val journal = mutableStateListOf("Samchat prêt.")
    val bubbles = mutableStateListOf<ChatBubble>()
    val counts = mutableStateMapOf<String, Int>()
    var peers by mutableStateOf(listOf<MeshPeer>())
    var pseudo by mutableStateOf("anon0000")
    /** Nombre de pairs trouvés par Wi-Fi Aware (rempli par le service). */
    var wifiPeers by mutableStateOf(0)

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
        bubbles.add(b)
        if (bubbles.size > MAX_BUBBLES) bubbles.removeAt(0)
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
                val newStatus = if (rest.endsWith("lu")) "✓✓ Lu" else "✓✓ Reçu"
                for (i in bubbles.indices) {
                    val b = bubbles[i]
                    if (b.msgId == id && b.status != "✓✓ Lu") bubbles[i] = b.copy(status = newStatus)
                }
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
