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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    val msgId: String? = null,
    val imagePath: String? = null
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
 * Lecteur unique de l'app : un seul son à la fois (vocal reçu, vocal envoyé ou live).
 * Lancer un son coupe celui qui joue, comme WhatsApp.
 */
object Playback {
    private var player: MediaPlayer? = null
    private var owner: Any? = null
    private var onDone: (() -> Unit)? = null

    /** Chemin du son en cours de lecture. Sert à afficher « Arrêter » sur le bon vocal. */
    var currentPath by mutableStateOf<String?>(null)
        private set

    /** Joue un fichier. [owner] identifie qui le joue, [onFinished] est appelé à la fin normale. */
    fun play(path: String, owner: Any, onFinished: (() -> Unit)? = null) {
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.setOnCompletionListener { finish(it) }
            p.setOnErrorListener { mp, _, _ -> finish(mp); true }
            p.prepare()
            p.start()
            player = p
            this.owner = owner
            this.onDone = onFinished
            currentPath = path
        } catch (e: Exception) {
            try { p.release() } catch (e2: Exception) { }
            onFinished?.invoke()
        }
    }

    /** Arrête le son en cours, sans appeler le « fin de lecture » de son propriétaire. */
    fun stop() {
        val p = player ?: return
        player = null
        owner = null
        onDone = null
        currentPath = null
        try { p.stop() } catch (e: Exception) { }
        try { p.release() } catch (e: Exception) { }
    }

    /** Vrai si le son en cours appartient à [who]. */
    fun isOwnedBy(who: Any): Boolean = owner === who

    private fun finish(mp: MediaPlayer) {
        if (mp !== player) {
            try { mp.release() } catch (e: Exception) { }
            return
        }
        val callback = onDone
        player = null
        owner = null
        onDone = null
        currentPath = null
        try { mp.release() } catch (e: Exception) { }
        callback?.invoke()
    }
}

/**
 * File du live : un morceau joue, le plus récent en attente remplace les autres.
 * Le live passe avant un vocal en cours, car il est en direct.
 */
private object LivePlayer {
    private val owner = Any()
    private var pending: File? = null

    fun offer(file: File) {
        if (Playback.isOwnedBy(owner)) {
            pending?.delete()
            pending = file
        } else {
            pending?.delete()
            pending = null
            playNow(file)
        }
    }

    private fun playNow(file: File) {
        Playback.play(file.absolutePath, owner) {
            file.delete()
            val next = pending
            pending = null
            if (next != null) playNow(next)
        }
    }
}

/**
 * Mémoire de l'application : journal, conversations, pairs, contacts.
 * Elle vit tant que le service tourne. Les conversations sont aussi sauvegardées sur le téléphone,
 * pour être retrouvées après un redémarrage de l'app.
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
    private const val HISTORY_FILE = "historique.json"
    private const val MEDIA_DIR = "media"

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

    private var liveActiveUntil = 0L

    private var appContext: Context? = null

    /** Sauvegarde de l'historique, faite en arrière-plan pour ne pas bloquer l'écran. */
    private val saver = Executors.newSingleThreadExecutor()
    private var historyFile: File? = null
    private var mediaDir: File? = null
    private var historyLoaded = false

    /** Nombre d'écrans de l'app visibles. Zéro : l'app est en arrière-plan ou fermée. */
    @Volatile private var startedActivities = 0
    private var callbacksRegistered = false

    /** Branché une seule fois, par le service. Les messages arrivent ensuite, écran fermé ou non. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        loadHistory(context.applicationContext)
        BleMeshManager.listener = { line -> onLine(line) }
        BleMeshManager.peersListener = { list ->
            peers = list
            refresh()
        }
        BleMeshManager.blobListener = { bytes, from -> onBlob(bytes, from) }
        BleMeshManager.liveListener = { sender, audio -> onLive(sender, audio) }
        registerVisibilityTracking(context.applicationContext)
    }

    /** Recharge les conversations sauvegardées. Ne se fait qu'une fois par lancement de l'app. */
    private fun loadHistory(context: Context) {
        if (historyLoaded) return
        historyLoaded = true
        val dir = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
        mediaDir = dir
        val file = File(context.filesDir, HISTORY_FILE)
        historyFile = file
        if (!file.exists()) return
        try {
            val array = JSONArray(file.readText())
            val loaded = ArrayList<ChatBubble>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val imagePath = o.optString("imagePath").takeIf { it.isNotEmpty() && File(it).exists() }
                val image = imagePath?.let {
                    BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
                val audio = o.optString("audio").takeIf { it.isNotEmpty() && File(it).exists() }
                loaded.add(
                    ChatBubble(
                        mine = o.optBoolean("mine"),
                        author = o.optString("author"),
                        text = o.optString("text"),
                        image = image,
                        chat = o.optString("chat", PUBLIC),
                        audio = audio,
                        time = o.optString("time", timeNow()),
                        status = o.optString("status").takeIf { it.isNotEmpty() },
                        msgId = o.optString("msgId").takeIf { it.isNotEmpty() },
                        imagePath = imagePath
                    )
                )
            }
            bubbles.addAll(loaded.takeLast(MAX_BUBBLES))
        } catch (e: Exception) {
            // Fichier illisible : on repart d'un historique vide, sans bloquer l'app
        }
    }

    /** Écrit les conversations sur le téléphone, en arrière-plan. */
    private fun saveHistory() {
        val file = historyFile ?: return
        val array = JSONArray()
        for (b in bubbles) {
            array.put(
                JSONObject().apply {
                    put("mine", b.mine)
                    put("author", b.author)
                    put("text", b.text)
                    put("chat", b.chat)
                    put("time", b.time)
                    put("status", b.status ?: "")
                    put("msgId", b.msgId ?: "")
                    put("audio", b.audio ?: "")
                    put("imagePath", b.imagePath ?: "")
                }
            )
        }
        val content = array.toString()
        saver.execute {
            try {
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(content)
                tmp.renameTo(file)
            } catch (e: Exception) {
                // Échec d'écriture : l'historique reste en mémoire, on réessaiera au prochain message
            }
        }
    }

    /** Copie un vocal dans le stockage permanent de l'app, pour qu'Android ne le supprime pas avec le cache. */
    private fun keepAudio(path: String?): String? {
        if (path == null) return null
        val dir = mediaDir ?: return path
        val src = File(path)
        if (!src.exists() || src.parentFile == dir) return path
        val dst = File(dir, "${System.currentTimeMillis()}_${src.name}")
        return try {
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (e: Exception) {
            path
        }
    }

    /** Enregistre une photo sur le téléphone, si elle ne l'est pas déjà. Retourne son chemin. */
    private fun keepImage(bitmap: Bitmap?, existing: String?): String? {
        if (existing != null) return existing
        if (bitmap == null) return null
        val dir = mediaDir ?: return null
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Retire le message le plus ancien, et supprime sa photo et son vocal du téléphone. */
    private fun removeOldest() {
        val old = bubbles.removeAt(0)
        old.audio?.let { File(it).delete() }
        old.imagePath?.let { File(it).delete() }
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
        val withStatus = if (known != null && rank(known) > rank(b.status)) b.copy(status = known) else b
        val shown = withStatus.copy(
            audio = keepAudio(withStatus.audio),
            imagePath = keepImage(withStatus.image, withStatus.imagePath)
        )
        bubbles.add(shown)
        if (bubbles.size > MAX_BUBBLES) removeOldest()
        saveHistory()
    }

    /** Met à jour le statut d'un message privé, sans jamais revenir à un statut moins avancé. */
    private fun setStatus(id: String, status: String) {
        if (rank(status) < rank(statusById[id])) return
        if (statusById.size > MAX_STATUSES) statusById.clear()
        statusById[id] = status
        var changed = false
        for (i in bubbles.indices) {
            val b = bubbles[i]
            if (b.msgId == id && rank(status) >= rank(b.status)) {
                bubbles[i] = b.copy(status = status)
                changed = true
            }
        }
        if (changed) saveHistory()
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
        LivePlayer.offer(file)
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
