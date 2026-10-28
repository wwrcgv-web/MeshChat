package com.example.meshchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService
import java.io.ByteArrayOutputStream
import java.io.File

/** Enregistre un vocal court (AAC, mono) dans un fichier temporaire du téléphone. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** Démarre l'enregistrement. Retourne false si le micro n'est pas disponible. */
    fun start(): Boolean {
        val out = File(context.cacheDir, "vocal_envoi.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(12_000)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            file = out
            true
        } catch (e: Exception) {
            rec.release()
            false
        }
    }

    /** Arrête l'enregistrement et retourne le contenu du fichier, ou null si l'enregistrement a échoué. */
    fun stop(): ByteArray? {
        val rec = recorder ?: return null
        recorder = null
        return try {
            rec.stop()
            file?.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            rec.release()
        }
    }

    companion object {
        /** Joue un fichier audio, puis libère le lecteur à la fin. */
        fun play(path: String) {
            try {
                val player = MediaPlayer()
                player.setDataSource(path)
                player.setOnCompletionListener { it.release() }
                player.prepare()
                player.start()
            } catch (e: Exception) {
                // Fichier illisible : on ne bloque pas l'écran
            }
        }
    }
}

/** Réglages partagés, observés par l'écran. */
object AppState {
    var serviceOn by mutableStateOf(false)
    var runInBackground by mutableStateOf(true)
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Le service démarre même si les notifications sont refusées : elles ne servent qu'à l'affichage
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeshHome(
                onStart = { askPermissions() },
                onStop = { stopMeshService() }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Si « Rester actif en arrière-plan » est désactivé, le service s'arrête quand l'app quitte l'écran
        if (!AppState.runInBackground && AppState.serviceOn) stopMeshService()
    }

    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        // Notifications : obligatoires à partir d'Android 13 pour afficher la notification du service
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startMeshService()
        } else {
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun startMeshService() {
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java).setAction(MeshService.ACTION_START))
        AppState.serviceOn = true
    }

    private fun stopMeshService() {
        stopService(Intent(this, MeshService::class.java))
        AppState.serviceOn = false
    }
}

/** Identifiant de la conversation « Tous ». */
private const val PUBLIC = "public"

/** Premier octet d'un contenu envoyé en fragments : indique si c'est une photo ou un vocal. */
private const val KIND_PHOTO: Byte = 1
private const val KIND_AUDIO: Byte = 2

/**
 * Une bulle de conversation.
 * chat = PUBLIC pour la conversation générale, ou l'identifiant complet du téléphone pour une discussion privée.
 * audio = chemin du fichier vocal, si la bulle contient un vocal.
 */
private data class Bubble(
    val mine: Boolean,
    val author: String,
    val text: String,
    val image: Bitmap? = null,
    val chat: String,
    val audio: String? = null
)

/** Titre affiché au-dessus d'une bulle. */
private fun bubbleTitle(b: Bubble): String = when {
    b.mine -> "Moi"
    b.author == "photo" -> "Photo"
    b.author == "vocal" -> "Vocal"
    else -> "@${pseudoFrom(b.author.take(8))}"
}

/** Réduit la photo pour qu'elle tienne dans peu de fragments Bluetooth. */
private fun compressPhoto(source: Bitmap): ByteArray {
    val scale = 240f / maxOf(source.width, source.height)
    val small = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else source
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 40, out)
    return out.toByteArray()
}

/** Pseudo lisible à partir de l'identifiant Bluetooth, par exemple anon1053. */
private fun pseudoFrom(shortId: String): String {
    val number = (shortId.toLongOrNull(16) ?: 0L) % 10000L
    return "anon" + number.toString().padStart(4, '0')
}

/** Icône de debug : un insecte orange, dessiné à la main (comme dans bitchat). */
@Composable
private fun BugIcon(color: Color, iconSize: Dp = 28.dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawOval(color, topLeft = Offset(w * 0.25f, h * 0.30f), size = Size(w * 0.5f, h * 0.64f))
        drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.24f))
        drawLine(color, Offset(w * 0.44f, h * 0.12f), Offset(w * 0.32f, h * 0.01f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.56f, h * 0.12f), Offset(w * 0.68f, h * 0.01f), strokeWidth = stroke)
        for (i in 0..2) {
            val y = h * (0.40f + i * 0.18f)
            drawLine(color, Offset(w * 0.25f, y), Offset(w * 0.03f, y + h * 0.08f), strokeWidth = stroke)
            drawLine(color, Offset(w * 0.75f, y), Offset(w * 0.97f, y + h * 0.08f), strokeWidth = stroke)
        }
    }
}

/** Bouton micro : blanc avec un micro noir, comme sur la capture. Rouge pendant l'enregistrement. */
@Composable
private fun MicButton(recording: Boolean, onClick: () -> Unit) {
    val bg = if (recording) Color(0xFFDC2626) else Color.White
    val fg = if (recording) Color.White else Color.Black
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            // Capsule du micro
            drawRoundRect(
                color = fg,
                topLeft = Offset(w * 0.30f, 0f),
                size = Size(w * 0.40f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.20f)
            )
            // Arc autour de la capsule
            drawArc(
                color = fg,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.30f),
                size = Size(w * 0.70f, h * 0.50f),
                style = Stroke(width = w * 0.08f)
            )
            // Pied et base
            drawLine(fg, Offset(w * 0.5f, h * 0.80f), Offset(w * 0.5f, h * 0.95f), strokeWidth = w * 0.08f)
            drawLine(fg, Offset(w * 0.30f, h * 0.95f), Offset(w * 0.70f, h * 0.95f), strokeWidth = w * 0.08f)
        }
    }
}

@Composable
private fun CircleButton(label: String, background: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontSize = 18.sp)
    }
}

/** Bouton de choix de conversation (Tous, ou un téléphone). */
@Composable
private fun ChatChip(label: String, selected: Boolean, green: Color, inkColor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) green else inkColor.copy(alpha = 0.12f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(label, color = if (selected) Color.White else inkColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MeshHome(onStart: () -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var bubbles by remember { mutableStateOf(listOf<Bubble>()) }
    var journal by remember { mutableStateOf(listOf("MeshChat prêt.")) }
    var peers by remember { mutableStateOf(listOf<MeshPeer>()) }
    var selectedChat by remember { mutableStateOf(PUBLIC) }
    var pseudo by remember { mutableStateOf("anon0000") }
    var counts by remember { mutableStateOf(mapOf<String, Int>()) }
    var recording by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf("système") }
    val chatState = rememberLazyListState()
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }

    // Thème : « système » suit le réglage du téléphone
    val dark = when (theme) {
        "clair" -> false
        "sombre" -> true
        else -> isSystemInDarkTheme()
    }
    val green = if (dark) Color(0xFF6EE7A0) else Color(0xFF3F7D3F)
    val blue = if (dark) Color(0xFF93C5FD) else Color(0xFF2563EB)
    val orange = Color(0xFFF59E0B)
    val grey = Color(0xFF9CA3AF)
    val bgColor = if (dark) Color(0xFF09090B) else Color.White
    val inkColor = if (dark) Color.White else Color(0xFF111827)
    val mineBg = if (dark) Color(0xFF14532D) else Color(0xFFDCFCE7)
    val otherBg = if (dark) Color(0xFF1F2937) else Color(0xFFF1F5F9)
    val inputBg = if (dark) Color(0xFF1F2937) else Color(0xFFF3F4F6)
    val lightGrey = if (dark) Color(0xFF374151) else Color(0xFFE5E7EB)

    // Compte un événement, pour le panneau Debug
    fun count(key: String) {
        counts = counts + (key to ((counts[key] ?: 0) + 1))
    }

    fun c(key: String): Int = counts[key] ?: 0

    // Appareil photo : la photo prise devient l'aperçu, elle n'est pas encore envoyée
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) pendingPhoto = bitmap
    }

    // Galerie : l'image choisie devient l'aperçu
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bitmap = uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
        if (bitmap != null) pendingPhoto = bitmap
    }

    DisposableEffect(Unit) {
        BleMeshManager.listener = { line ->
            when {
                line.startsWith("Mon identifiant : ") -> {
                    pseudo = pseudoFrom(line.removePrefix("Mon identifiant : ").trim())
                    journal = journal + line
                }
                line.startsWith("Reçu privé de ") -> {
                    count("in_text")
                    val rest = line.removePrefix("Reçu privé de ")
                    val sender = rest.substringBefore(" : ")
                    val text = rest.substringAfter(" : ", "")
                    bubbles = bubbles + Bubble(false, sender, text, null, sender)
                }
                line.startsWith("Reçu de ") -> {
                    count("in_text")
                    val rest = line.removePrefix("Reçu de ")
                    val sender = rest.substringBefore(" : ")
                    val text = rest.substringAfter(" : ", "")
                    bubbles = bubbles + Bubble(false, sender, text, null, PUBLIC)
                }
                line.startsWith("Pair trouvé") -> journal = journal + line
                line.startsWith("Fichier reçu") -> {
                    count("in_file")
                    journal = journal + line
                }
                line.startsWith("Envoyé à") -> {
                    count("out_ok")
                    journal = journal + line
                }
                line.startsWith("Envoi du fichier") -> {
                    count("out_file")
                    journal = journal + line
                }
                line.startsWith("Relais vers") -> {
                    count("relay")
                    journal = journal + line
                }
                line.startsWith("Aucun pair") -> {
                    count("no_peer")
                    journal = journal + line
                }
                line.contains("chec") -> {
                    count("fail")
                    journal = journal + line
                }
                else -> journal = journal + line
            }
        }
        // Liste des téléphones identifiés, mise à jour par le Bluetooth
        BleMeshManager.peersListener = { list ->
            peers = list
            // Si la discussion choisie n'est plus à portée, on revient à « Tous »
            if (selectedChat != PUBLIC && list.none { it.id == selectedChat }) selectedChat = PUBLIC
        }
        // Contenu complet reçu : le premier octet indique si c'est une photo ou un vocal
        BleMeshManager.blobListener = { bytes, from ->
            if (bytes.isNotEmpty() && bytes[0] == KIND_AUDIO) {
                val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
                file.writeBytes(bytes.copyOfRange(1, bytes.size))
                val chat = from ?: PUBLIC
                bubbles = bubbles + Bubble(false, from ?: "vocal", "Vocal reçu", null, chat, file.absolutePath)
            } else if (bytes.isNotEmpty() && bytes[0] == KIND_PHOTO) {
                BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1)?.let { photo ->
                    if (from == null) {
                        bubbles = bubbles + Bubble(false, "photo", "Photo reçue", photo, PUBLIC)
                    } else {
                        bubbles = bubbles + Bubble(false, from, "Photo reçue", photo, from)
                    }
                }
            }
        }
        onDispose {
            BleMeshManager.listener = null
            BleMeshManager.peersListener = null
            BleMeshManager.blobListener = null
        }
    }

    // Messages de la conversation choisie
    val visible = bubbles.filter { it.chat == selectedChat }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) chatState.animateScrollToItem(visible.size - 1)
    }

    /** Envoie le texte et/ou la photo (au téléphone choisi, ou à tous). */
    fun send() {
        val manager = BleMeshManager.instance
        if (manager == null) {
            journal = journal + "Service non activé : activez le service"
            return
        }
        val target = if (selectedChat == PUBLIC) null else selectedChat
        if (draft.isNotBlank()) {
            val sent = manager.sendMessage(draft, target)
            if (sent > 0) {
                bubbles = bubbles + Bubble(true, "moi", draft, null, selectedChat)
                draft = ""
            }
        }
        pendingPhoto?.let { photo ->
            val sent = manager.sendBlob(byteArrayOf(KIND_PHOTO) + compressPhoto(photo), target)
            if (sent > 0) {
                bubbles = bubbles + Bubble(true, "moi", "Photo envoyée", photo, selectedChat)
                pendingPhoto = null
            }
        }
    }

    /** Démarre l'enregistrement du vocal. */
    fun startVoice() {
        if (BleMeshManager.instance == null) {
            journal = journal + "Service non activé : activez le service"
            return
        }
        if (recorder.start()) {
            recording = true
            journal = journal + "Enregistrement vocal…"
        } else {
            journal = journal + "Micro indisponible"
        }
    }

    /** Arrête l'enregistrement et envoie le vocal au téléphone choisi (ou à tous). */
    fun stopAndSendVoice() {
        recording = false
        val bytes: ByteArray? = recorder.stop()
        if (bytes == null || bytes.isEmpty()) {
            journal = journal + "Vocal vide, rien n'a été envoyé"
            return
        }
        val manager = BleMeshManager.instance ?: return
        val target = if (selectedChat == PUBLIC) null else selectedChat
        val sent = manager.sendBlob(byteArrayOf(KIND_AUDIO) + bytes, target)
        if (sent > 0) {
            val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
            file.writeBytes(bytes)
            bubbles = bubbles + Bubble(true, "moi", "Vocal envoyé", null, selectedChat, file.absolutePath)
        }
    }

    // Permission du micro : demandée la première fois qu'on appuie sur le micro
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else journal = journal + "Micro refusé dans les réglages du téléphone"
    }

    /** Un appui sur le micro : démarre, ou arrête et envoie. */
    fun onMicTap() {
        if (recording) {
            stopAndSendVoice()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Ajouter une photo") },
            text = { Text("Que voulez-vous faire ?") },
            confirmButton = {
                TextButton(onClick = { showPicker = false; cameraLauncher.launch(null) }) { Text("Prendre une photo") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false; galleryLauncher.launch("image/*") }) { Text("Choisir une image") }
            }
        )
    }

    // Panneau Debug : compteurs des événements
    if (showDebug) {
        AlertDialog(
            onDismissRequest = { showDebug = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BugIcon(orange, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("outils de debug", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Pseudo : /@$pseudo", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Pairs vus : ${peers.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Service : ${if (AppState.serviceOn) "actif" else "arrêté"}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Messages reçus : ${c("in_text")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Fichiers reçus : ${c("in_file")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Envois réussis : ${c("out_ok")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Fichiers envoyés : ${c("out_file")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Relais : ${c("relay")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Sans pair : ${c("no_peer")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Échecs : ${c("fail")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Lecture : « Envois réussis » augmente quand un pair confirme la réception. « Échecs » indique le problème dans le journal.",
                        color = grey,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebug = false }) { Text("Fermer") }
            },
            dismissButton = {
                TextButton(onClick = { counts = emptyMap() }) { Text("Remettre à zéro") }
            }
        )
    }

    // Paramètres : panneau à droite de l'écran
    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Row(Modifier.fillMaxSize().background(Color(0x99000000))) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { showSettings = false })
                Surface(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    color = bgColor
                ) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("PARAMÈTRES", color = green, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showSettings = false }) { Text("✕", fontSize = 18.sp, color = inkColor) }
                        }

                        Spacer(Modifier.height(20.dp))
                        Text("THÈME", color = grey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        listOf("système", "clair", "sombre").forEach { option ->
                            Button(
                                onClick = { theme = option },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (theme == option) green else lightGrey
                                )
                            ) {
                                Text(option, color = if (theme == option) Color.White else inkColor, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("RÉGLAGES", color = grey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Rester actif en arrière-plan", color = inkColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Si désactivé, le service s'arrête quand l'app est quittée", color = grey, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Switch(
                            checked = AppState.runInBackground,
                            onCheckedChange = { AppState.runInBackground = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = green)
                        )

                        Spacer(Modifier.height(16.dp))
                        Text("LANGUE", color = grey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Français", color = inkColor, fontSize = 14.sp)
                        Text("(d'autres langues viendront plus tard)", color = grey, fontSize = 12.sp)

                        // Outils de debug, tout en bas du panneau
                        Spacer(Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showSettings = false
                                    showDebug = true
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            BugIcon(orange, 26.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("outils de debug", color = inkColor, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    val canSend = draft.isNotBlank() || pendingPhoto != null

    Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {

            // En-tête : /@pseudo, mesh, pairs et ⚙ paramètres
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "/@$pseudo",
                    color = green,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text("📡 mesh", color = blue, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text("👥 ${peers.size}", color = grey, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 18.sp) }
            }

            Spacer(Modifier.height(8.dp))

            // Interrupteur du service
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (AppState.serviceOn) "SERVICE ACTIF" else "SERVICE ARRÊTÉ",
                    color = inkColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = AppState.serviceOn,
                    onCheckedChange = { on ->
                        if (on) {
                            journal = journal + "Activation du service demandée"
                            onStart()
                        } else {
                            journal = journal + "Service arrêté"
                            onStop()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = green)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Choix de la conversation : Tous, puis chaque téléphone trouvé
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    ChatChip("Tous", selectedChat == PUBLIC, green, inkColor) { selectedChat = PUBLIC }
                }
                items(peers) { p ->
                    ChatChip("@${pseudoFrom(p.id.take(8))}", selectedChat == p.id, green, inkColor) { selectedChat = p.id }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Titre de la conversation affichée
            Text(
                if (selectedChat == PUBLIC) "Discussion : tous" else "Privé avec @${pseudoFrom(selectedChat.take(8))}",
                color = grey,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            // Conversation (seulement les messages de la discussion choisie)
            LazyColumn(state = chatState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(visible) { b ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(color = if (b.mine) mineBg else otherBg, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(bubbleTitle(b), color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                b.image?.let { img ->
                                    Image(bitmap = img.asImageBitmap(), contentDescription = "Photo", modifier = Modifier.size(200.dp))
                                }
                                b.audio?.let { path ->
                                    Text(
                                        "▶ Écouter le vocal",
                                        color = green,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { VoiceRecorder.play(path) }.padding(vertical = 4.dp)
                                    )
                                }
                                Text(b.text, color = inkColor, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // Journal réduit : les 3 dernières lignes, en petit
            Text(
                journal.takeLast(3).joinToString("\n") { "> $it" },
                color = grey,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3
            )

            Spacer(Modifier.height(6.dp))

            // Aperçu de la photo choisie, avant envoi
            pendingPhoto?.let { photo ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap = photo.asImageBitmap(), contentDescription = "Aperçu", modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Photo prête : appuyez sur ➤", color = inkColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pendingPhoto = null }) { Text("Retirer") }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Barre de saisie arrondie
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = inputBg,
                border = BorderStroke(1.dp, Color(0xFFD1D5DB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("écrire un message…", color = grey) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = inkColor,
                            unfocusedTextColor = inkColor
                        )
                    )
                    CircleButton("📷", lightGrey, inkColor) { showPicker = true }
                    Spacer(Modifier.width(6.dp))
                    if (canSend) {
                        CircleButton("➤", green, Color.White) { send() }
                    } else {
                        MicButton(recording) { onMicTap() }
                    }
                }
            }
        }
    }
}
