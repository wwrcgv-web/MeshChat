package com.example.meshchat

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
 * Lecteur du live : joue les morceaux un à la fois, dans l'ordre.
 * Si un morceau arrive pendant qu'un autre joue, il attend. Si un autre arrive avant la fin, le plus ancien
 * en attente est abandonné : on joue toujours le plus récent, jamais un son en retard.
 */
private class LivePlayer {
    private var player: MediaPlayer? = null
    private var pending: File? = null

    /** Propose un morceau au lecteur : il joue tout de suite, ou prend la place du morceau en attente. */
    fun offer(file: File) {
        if (player == null) {
            startNow(file)
        } else {
            pending?.delete()
            pending = file
        }
    }

    private fun startNow(file: File) {
        val p = MediaPlayer()
        player = p
        var ended = false
        // Fin du morceau, normale ou en erreur : on libère le lecteur une seule fois, puis on enchaîne
        val end = {
            if (!ended) {
                ended = true
                try { p.release() } catch (e: Exception) { }
                file.delete()
                if (player === p) player = null
                val next = pending
                pending = null
                if (next != null) startNow(next)
            }
        }
        try {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { end() }
            p.setOnErrorListener { _, _, _ -> end(); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            end()
        }
    }
}

/**
 * Mémoire de l'application : journal, conversations, pairs, contacts.
 * Elle vit tant que le service tourne, donc les messages reçus écran fermé sont gardés.
 */
object MeshState {
    const val PUBLIC = "public"
    private const val KIND_PHOTO: Byte = 1
    private const val KIND_AUDIO: Byte = 2
    private const val MAX_JOURNAL = 200
    private const val MAX_BUBBLES = 500
    private const val MAX_STATUSES = 1000
    /** Délai sans morceau de voix au-delà duquel une nouvelle prise de parole est annoncée dans le journal. */
    private const val LIVE_GAP_MS = 3_000L
    private const val CHANNEL_MESSAGES = "meshchat_messages"
    private const val MAX_NOTIF_TEXT = 120

    val journal = mutableStateListOf("Samchat prêt.")
    val bubbles = mutableStateListOf<ChatBubble>()
    val counts = mutableStateMapOf<String, Int>()

    /** Pairs à portée, en ce moment. */
    var peers by mutableStateOf(listOf<MeshPeer>())
    /** Tous les contacts déjà rencontrés, y compris ceux qui sont hors de portée. */
    var contacts by mutableStateOf(listOf<MeshPeer>())
    /** Pseudo de ce téléphone : le nom choisi, ou anonXXXX tant qu'il n'est pas choisi. */
    var pseudo by mutableStateOf("anon0000")
    /** Nombre de pairs trouvés par Wi-Fi Aware (rempli par le service). */
    var wifiPeers by mutableStateOf(0)

    /** Dernier statut connu de chaque message privé, pour les bulles créées après l'arrivée du statut. */
    private val statusById = HashMap<String, String>()

    private val livePlayer = LivePlayer()
    private var liveActiveUntil = 0L

    private var appContext: Context? = null

    /** Nombre d'écrans de l'app visibles. Zéro : l'app est en arrière-plan ou fermée. */
    @Volatile private var startedActivities = 0
    private var callbacksRegistered = false

    /** Branché une seule fois, par le service. Les messages arrivent ensuite, écran fermé ou non. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        BleMeshManager.listener = { line -> onLine(line) }
        BleMeshManager.peersListener = { list ->
            peers = list
            refresh()
        }
        BleMeshManager.blobListener = { bytes, from -> onBlob(bytes, from) }
        BleMeshManager.liveListener = { sender, audio -> onLive(sender, audio) }
        registerVisibilityTracking(context.applicationContext)
    }

    /** Suit les écrans ouverts, pour savoir si l'app est à l'écran. Enregistré une seule fois. */
    private fun registerVisibilityTracking(app: Context) {
        if (callbacksRegistered) return
        val application = app as? Application ?: return
        application.registerActivityLifecycleCallbacks(visibilityCallbacks)
        callbacksRegistered = true
    }

    private val visibilityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { startedActivities++ }
        override fun onActivityStopped(activity: Activity) { if (startedActivities > 0) startedActivities-- }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Recalcule le pseudo et la liste des contacts à partir du gestionnaire Bluetooth. */
    fun refresh() {
        val m = BleMeshManager.instance ?: return
        val name = m.myName
        pseudo = if (name.isNotBlank()) name else pseudoFrom(m.myId.joinToString("") { "%02x".format(it) }.take(8))
        contacts = m.knownContacts()
    }

    /** Nom à afficher pour un identifiant : le nom reçu, sinon anonXXXX. */
    fun nameOf(id: String): String {
        val known = contacts.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
            ?: peers.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        return known ?: pseudoFrom(id.take(8))
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

    /**
     * Affiche une notification de message, seulement si Samchat n'est pas à l'écran.
     * Une notification par conversation : les messages suivants la mettent à jour.
     */
    private fun notifyMessage(key: String, title: String, text: String) {
        if (startedActivities > 0) return
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val id = key.hashCode()
        val pending = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_MESSAGES)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text.take(MAX_NOTIF_TEXT))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission retirée entre-temps : on ne bloque pas la réception
        }
    }

    /** Notification d'un vocal ou d'une photo : privée si l'expéditeur est connu, sinon sur « Tous ». */
    private fun notifyBlob(from: String?, text: String) {
        if (from == null) notifyMessage(PUBLIC, "Tous", text)
        else notifyMessage("p$from", "@${nameOf(from)}", text)
    }

    private fun onLine(line: String) {
        when {
            line.startsWith("Mon identifiant : ") -> {
                addJournal(line)
                refresh()
            }
            line.startsWith("Clés mémorisées : ") -> {
                // Au démarrage, la liste des contacts déjà rencontrés est chargée ici
                addJournal(line)
                refresh()
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
                notifyMessage("p$sender", "@${nameOf(sender)}", text)
            }
            line.startsWith("Reçu de ") -> {
                count("in_text")
                val rest = line.removePrefix("Reçu de ")
                val sender = rest.substringBefore(" : ")
                val text = rest.substringAfter(" : ", "")
                addBubble(ChatBubble(false, sender, text, null, PUBLIC))
                notifyMessage(PUBLIC, "Tous · @${nameOf(sender)}", text)
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

    /**
     * Morceau de voix en direct reçu. Il est enregistré dans un fichier temporaire, puis joué.
     * Le journal ne reçoit qu'une ligne au début de chaque prise de parole, pas une par morceau.
     */
    private fun onLive(sender: String, audio: ByteArray) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        if (now > liveActiveUntil) addJournal("Live reçu de ${nameOf(sender)}")
        liveActiveUntil = now + LIVE_GAP_MS
        val file = File(context.cacheDir, "live_${now}.m4a")
        file.writeBytes(audio)
        livePlayer.offer(file)
    }

    /** Photo ou vocal complet reçu. Le premier octet indique le type. */
    private fun onBlob(bytes: ByteArray, from: String?) {
        val context = appContext ?: return
        if (bytes.isNotEmpty() && bytes[0] == KIND_AUDIO) {
            val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
            file.writeBytes(bytes.copyOfRange(1, bytes.size))
            addBubble(ChatBubble(false, from ?: "vocal", "Vocal reçu", null, from ?: PUBLIC, file.absolutePath))
            notifyBlob(from, "Vocal reçu")
        } else if (bytes.isNotEmpty() && bytes[0] == KIND_PHOTO) {
            BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1)?.let { photo ->
                if (from == null) {
                    addBubble(ChatBubble(false, "photo", "Photo reçue", photo, PUBLIC))
                } else {
                    addBubble(ChatBubble(false, from, "Photo reçue", photo, from))
                }
                notifyBlob(from, "Photo reçue")
            }
        }
    }
}

private fun timeNow(): String = SimpleDateFormat("dd MMM HH:mm", Locale.FRANCE).format(Date())

/** Pseudo de repli à partir de l'identifiant Bluetooth, par exemple anon1053. */
private fun pseudoFrom(shortId: String): String {
    val number = (shortId.toLongOrNull(16) ?: 0L) % 10000L
    return "anon" + number.toString().padStart(4, '0')
}
