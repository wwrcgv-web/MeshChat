package com.example.meshchat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.service.MeshService
import com.example.meshchat.wifi.WifiAwareSupport
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

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
        /** Identifie les vocaux joués par l'écran, dans le lecteur commun. */
        private val owner = Any()

        /** Lance un vocal, ou l'arrête si c'est celui qui joue déjà. */
        fun play(path: String) {
            if (Playback.currentPath == path) {
                Playback.stop()
            } else {
                Playback.play(path, owner)
            }
        }
    }
}

/**
 * Enregistre le micro par segments d'une seconde. Chaque segment est un fichier audio complet,
 * remis à [onSegment] dès qu'il est fini, puis un nouveau segment démarre aussitôt.
 */
private class LiveStreamer(
    private val context: Context,
    private val onSegment: (ByteArray) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var running = false
    private var counter = 0

    /** Démarre le premier segment. Retourne false si le micro n'est pas disponible. */
    fun start(): Boolean {
        if (!startSegment()) return false
        running = true
        handler.postDelayed(rotate, SEGMENT_MS)
        return true
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            stopSegment()?.takeIf { it.isNotEmpty() }?.let { onSegment(it) }
            if (startSegment()) {
                handler.postDelayed(this, SEGMENT_MS)
            } else {
                running = false
            }
        }
    }

    /** Arrête le live et envoie le dernier segment. */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(rotate)
        stopSegment()?.takeIf { it.isNotEmpty() }?.let { onSegment(it) }
    }

    private fun startSegment(): Boolean {
        counter++
        val out = File(context.cacheDir, "live_envoi_$counter.m4a")
        currentFile = out
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
            true
        } catch (e: Exception) {
            rec.release()
            false
        }
    }

    /** Termine le segment en cours, lit son contenu, puis supprime le fichier. */
    private fun stopSegment(): ByteArray? {
        val rec = recorder ?: return null
        recorder = null
        val file = currentFile
        return try {
            rec.stop()
            file?.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            rec.release()
            file?.delete()
        }
    }

    companion object {
        private const val SEGMENT_MS = 1_000L
    }
}

/** Réglages de l'écran (l'état du service vient de BleMeshManager). */
object AppState {
    var serviceOn by mutableStateOf(false)
    var runInBackground by mutableStateOf(true)
    /** Live push-to-talk : maintenir le micro pour parler en direct. Gardé entre les lancements. */
    var liveOn by mutableStateOf(false)
    /** Contact dont la discussion est ouverte (null : « Tous » ou aucune). Sert à signaler « Lu ». */
    var openChat: String? = null
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Le service démarre même si les notifications sont refusées : elles ne servent qu'à l'affichage
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.liveOn = getSharedPreferences("meshchat", Context.MODE_PRIVATE).getBoolean("live_on", false)
        setContent {
            MeshHome(
                onStart = { askPermissions() },
                onStop = { stopMeshService() },
                onRestart = { restartMeshService() }
            )
        }
        // Le service démarre tout seul à l'ouverture, s'il ne tourne pas déjà
        if (BleMeshManager.instance == null) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Si le service tourne déjà (écran rouvert), l'interrupteur doit le montrer
        val manager = BleMeshManager.instance
        if (manager != null) {
            AppState.serviceOn = true
            // La discussion ouverte est de nouveau visible : les messages en attente sont signalés « Lus »
            manager.openChatPeer = AppState.openChat
            AppState.openChat?.let { manager.markRead(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        // L'écran n'est plus visible : on ne signale plus « Lu » automatiquement
        BleMeshManager.instance?.openChatPeer = null
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
        // Appareils à proximité : nécessaire pour le Wi-Fi Aware sur Android 13 et plus
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES

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

    /** Arrête puis relance le service, pour qu'il relise les contacts sauvegardés (ex. après un scan de QR). */
    private fun restartMeshService() {
        stopMeshService()
        Handler(Looper.getMainLooper()).postDelayed({ startMeshService() }, 1000L)
    }
}

/** Identifiant de la conversation « Tous ». */
private const val PUBLIC = "public"

/** Premier octet d'un contenu envoyé en fragments : indique si c'est une photo ou un vocal. */
private const val KIND_PHOTO: Byte = 1
private const val KIND_AUDIO: Byte = 2

/** Durée d'appui à partir de laquelle un appui devient du live. En dessous, c'est un appui bref (vocal classique). */
private const val HOLD_MS = 350L

/** Statut d'un envoi qui vient d'être lancé : privé = en attente de « Parti » puis des accusés, public = envoyé. */
private fun initialStatus(target: String?): String = if (target == null) "✓ Envoyé" else "⏳ En attente"

/** Titre affiché au-dessus d'une bulle : le nom du contact, ou anonXXXX tant qu'on ne le connaît pas. */
private fun bubbleTitle(b: ChatBubble): String = when {
    b.mine -> "Moi"
    b.author == "photo" -> "Photo"
    b.author == "vocal" -> "Vocal"
    else -> "@${MeshState.nameOf(b.author)}"
}

/**
 * Réduit la photo pour l'envoi : 800 px au plus, JPEG qualité 75.
 * Une photo plus lourde que 250 Ko ne peut pas partir par Bluetooth : elle passe alors par Wi-Fi.
 */
private fun compressPhoto(source: Bitmap): ByteArray {
    val scale = 800f / maxOf(source.width, source.height)
    val small = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else source
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 75, out)
    return out.toByteArray()
}

/**
 * Forme d'onde estimée à partir du contenu du fichier audio : [bars] valeurs entre 0.2 et 1.
 * Ce n'est pas le volume réel de la voix, mais un aperçu visuel stable pour chaque vocal.
 */
private fun waveformOf(path: String, bars: Int = 40): List<Float> = try {
    val bytes = File(path).readBytes()
    if (bytes.isEmpty()) {
        List(bars) { 0.3f }
    } else {
        val chunk = maxOf(1, bytes.size / bars)
        val raw = List(bars) { i ->
            val from = i * chunk
            val to = minOf(bytes.size, from + chunk)
            if (to <= from) 0f else {
                var sum = 0L
                for (j in from until to) sum += abs((bytes[j].toInt() and 0xFF) - 128)
                sum.toFloat() / (to - from)
            }
        }
        val max = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
        raw.map { (it / max).coerceIn(0.2f, 1f) }
    }
} catch (e: Exception) {
    List(bars) { 0.3f }
}

/** Durée d'un fichier audio, en millisecondes. 0 si elle ne peut pas être lue. */
private fun durationMsOf(path: String): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

/** Affiche une durée au format m:ss. */
private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Bulle de vocal, façon WhatsApp : bouton lecture, barres de forme d'onde qui se colorent pendant la lecture,
 * temps écoulé et durée totale.
 */
@Composable
private fun VoiceBubble(path: String, green: Color, grey: Color, inkColor: Color) {
    val bars = remember(path) { waveformOf(path) }
    val total = remember(path) { durationMsOf(path) }
    val playing = Playback.currentPath == path
    var elapsed by remember(path) { mutableStateOf(0L) }

    LaunchedEffect(playing) {
        if (playing) {
            val started = System.currentTimeMillis()
            while (Playback.currentPath == path) {
                elapsed = System.currentTimeMillis() - started
                if (total > 0 && elapsed > total) elapsed = total
                delay(100)
            }
        } else {
            elapsed = 0L
        }
    }

    val fraction = if (total > 0 && playing) (elapsed.toFloat() / total).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(220.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(green)
                .clickable { VoiceRecorder.play(path) },
            contentAlignment = Alignment.Center
        ) {
            Text(if (playing) "■" else "▶", color = Color.White, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
                bars.forEachIndexed { i, h ->
                    val played = (i + 0.5f) / bars.size <= fraction
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((6 + 22 * h).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (played) green else grey.copy(alpha = 0.5f))
                    )
                    if (i < bars.size - 1) Spacer(Modifier.width(2.dp))
                }
            }
            Row {
                Text(
                    if (playing) formatMs(elapsed) else "0:00",
                    color = grey,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(formatMs(total), color = grey, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Photo de profil ronde. Sans photo : un rond gris avec une lettre (ou « ? » si l'auteur est inconnu).
 */
@Composable
private fun AvatarDot(bitmap: Bitmap?, label: String, size: Dp, bg: Color, ink: Color) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Photo de profil",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = ink, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Enregistre un fichier de l'app dans Téléchargements/Samchat, pour qu'il reste visible.
 * Retourne le nom du fichier enregistré, ou null en cas d'échec.
 */
private fun saveToDownloads(context: Context, source: String, mime: String, prefix: String): String? {
    val src = File(source)
    if (!src.exists()) return null
    val ext = src.extension.ifEmpty { "bin" }
    val name = "${prefix}_${System.currentTimeMillis()}.$ext"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Samchat")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: return null
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Samchat")
            dir.mkdirs()
            src.copyTo(File(dir, name), overwrite = true)
        }
        name
    } catch (e: Exception) {
        null
    }
}

/** Informations de distribution de cette copie, affichées dans les outils de debug. */
private class DistributionInfo(val version: String, val installer: String, val fingerprint: String)

/** Lit la version, l'installateur et l'empreinte SHA-256 du certificat qui a signé cette copie. */
private fun readDistribution(context: Context): DistributionInfo {
    val pm = context.packageManager
    val pkg = context.packageName

    val version = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName ?: "?"} ($code)"
    } catch (e: Exception) {
        "indisponible"
    }

    val installer = try {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
        name ?: "inconnue (installation manuelle)"
    } catch (e: Exception) {
        "inconnue"
    }

    val fingerprint = signingBytes(context)?.let { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    } ?: "indisponible"

    return DistributionInfo(version, installer, fingerprint)
}

/** Certificat qui a signé l'application, en octets. Null si Android ne le fournit pas. */
@Suppress("DEPRECATION")
private fun signingBytes(context: Context): ByteArray? = try {
    val pm = context.packageManager
    val pkg = context.packageName
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    } else {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        info.signatures?.firstOrNull()?.toByteArray()
    }
} catch (e: Exception) {
    null
}

/** Copie un texte dans le presse-papiers du téléphone. */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("empreinte", text))
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

/** Icône « trois traits horizontaux » (menu), dessinée à la main. */
@Composable
private fun MenuIcon(color: Color, iconSize: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        for (i in 0..2) {
            val y = h * (0.2f + i * 0.3f)
            drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = stroke)
        }
    }
}

/**
 * Bouton micro. Appui bref : vocal classique (démarrer, puis arrêter et envoyer).
 * Avec le live activé : appui maintenu = parler en direct, le relâcher arrête.
 */
@Composable
private fun MicButton(
    recording: Boolean,
    live: Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    val bg = if (recording) Color(0xFFDC2626) else Color.White
    val fg = if (recording) Color.White else Color.Black
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(live) {
                detectTapGestures(
                    onPress = {
                        if (!live) {
                            if (tryAwaitRelease()) onTap()
                        } else {
                            val released = withTimeoutOrNull(HOLD_MS) { tryAwaitRelease() }
                            if (released == null) {
                                onHoldStart()
                                tryAwaitRelease()
                                onHoldEnd()
                            } else if (released) {
                                onTap()
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = fg,
                topLeft = Offset(w * 0.30f, 0f),
                size = Size(w * 0.40f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.20f)
            )
            drawArc(
                color = fg,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.30f),
                size = Size(w * 0.70f, h * 0.50f),
                style = Stroke(width = w * 0.08f)
            )
            drawLine(fg, Offset(w * 0.5f, h * 0.80f), Offset(w * 0.5f, h * 0.95f), strokeWidth = w * 0.08f)
            drawLine(fg, Offset(w * 0.30f, h * 0.95f), Offset(w * 0.70f, h * 0.95f), strokeWidth = w * 0.08f)
        }
    }
}

@Composable
private fun CircleButton(label: String, background: Color, textColor: Color, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontSize = 18.sp)
    }
}

/**
 * Bouton de choix de conversation : photo du contact (si connue) à gauche du nom.
 * Un contact hors de portée apparaît en grisé.
 */
@Composable
private fun ChatChip(
    label: String,
    selected: Boolean,
    online: Boolean,
    green: Color,
    inkColor: Color,
    avatar: Bitmap?,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) green else inkColor.copy(alpha = 0.12f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val textColor = when {
            selected -> Color.White
            online -> inkColor
            else -> inkColor.copy(alpha = 0.4f)
        }
        if (avatar != null) {
            Image(
                bitmap = avatar.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(18.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = textColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MeshHome(onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var selectedChat by remember { mutableStateOf(PUBLIC) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    /** Message dont la suppression est demandée (appui long sur la bulle). */
    var bubbleToDelete by remember { mutableStateOf<ChatBubble?>(null) }
    var theme by remember { mutableStateOf("système") }
    var recording by remember { mutableStateOf(false) }
    var liveHolding by remember { mutableStateOf(false) }
    var liveStreamer by remember { mutableStateOf<LiveStreamer?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var statusInput by remember { mutableStateOf("") }

    val chatState = rememberLazyListState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    var myName by remember { mutableStateOf(prefs.getString("my_name", "") ?: "") }
    var myAvatar by remember {
        mutableStateOf(ProfileStore.myAvatar(context)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
    }
    val recorder = remember { VoiceRecorder(context) }
    val wifiStatus = remember { WifiAwareSupport.status(context) }
    val dist = remember { readDistribution(context) }

    // Données partagées avec le service : elles restent là quand l'écran se ferme
    val peers = MeshState.peers
    val contacts = MeshState.contacts
    val pseudo = MeshState.pseudo
    val journal = MeshState.journal
    val bubbles = MeshState.bubbles
    val counts = MeshState.counts
    val displayName = if (myName.isNotBlank()) myName else pseudo

    // Rafraîchissement : ce compteur change dès qu'un profil de contact est accepté
    val profileTick = MeshState.profileTick

    fun log(line: String) = MeshState.addJournal(line)

    fun c(key: String): Int = counts[key] ?: 0

    /** Enregistre le nom choisi. Il sera envoyé aux téléphones rencontrés. */
    fun saveName(raw: String) {
        val clean = raw.trim().take(20)
        if (clean.isBlank()) return
        prefs.edit().putString("my_name", clean).apply()
        myName = clean
        MeshState.refresh()
    }

    /** Enregistre le statut, puis l'envoie à chaque contact. Seulement à l'appui sur le bouton. */
    fun saveStatus() {
        ProfileStore.setMyStatus(context, statusInput)
        BleMeshManager.instance?.broadcastProfile()
        log("Statut enregistré")
    }

    // Photo de profil : choisie dans la galerie, recadrée et réduite, puis envoyée aux contacts
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bitmap = uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }
        if (bitmap == null) {
            log("Photo de profil illisible")
        } else if (ProfileStore.setMyAvatar(context, bitmap) == null) {
            log("Photo de profil impossible à enregistrer")
        } else {
            myAvatar = ProfileStore.myAvatar(context)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            BleMeshManager.instance?.broadcastProfile()
            log("Photo de profil enregistrée")
        }
    }

    // Thème : « système » suit le réglage du téléphone
    val dark = when (theme) {
        "clair" -> false
        "sombre" -> true
        else -> isSystemInDarkTheme()
    }
    val green = if (dark) Color(0xFF6EE7A0) else Color(0xFF3F7D3F)
    val readGreen = if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    val blue = if (dark) Color(0xFF93C5FD) else Color(0xFF2563EB)
    val orange = Color(0xFFF59E0B)
    val grey = Color(0xFF9CA3AF)
    val bgColor = if (dark) Color(0xFF09090B) else Color(0xFFF6F7F6)
    val inkColor = if (dark) Color.White else Color(0xFF111827)
    val mineBg = if (dark) Color(0xFF14532D) else Color(0xFFDCFCE7)
    val otherBg = if (dark) Color(0xFF1F2937) else Color(0xFFF1F5F9)
    val inputBg = if (dark) Color(0xFF1F2937) else Color(0xFFF3F4F6)
    val lightGrey = if (dark) Color(0xFF374151) else Color(0xFFE5E7EB)

    // Discussion privée ouverte : le moteur sait qu'on la lit, donc les messages reçus sont signalés « Lus » tout de suite.
    // Les messages déjà reçus et pas encore lus sont signalés à l'ouverture.
    LaunchedEffect(selectedChat, AppState.serviceOn) {
        val peer = if (selectedChat != PUBLIC) selectedChat else null
        AppState.openChat = peer
        val manager = BleMeshManager.instance
        manager?.openChatPeer = peer
        if (peer != null) manager?.markRead(peer)
    }

    // Appareil photo : la photo prise devient l'aperçu, elle n'est pas encore envoyée
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) pendingPhoto = bitmap
    }

    // Galerie : l'image choisie devient l'aperçu
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bitmap = uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }
        if (bitmap != null) pendingPhoto = bitmap
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
            log("Service non activé : activez le service")
            return
        }
        val target = if (selectedChat == PUBLIC) null else selectedChat
        if (draft.isNotBlank()) {
            val sent = manager.sendMessage(draft, target)
            if (sent > 0) {
                // Pour un message privé, on garde son identifiant pour recevoir ses accusés
                MeshState.addBubble(
                    ChatBubble(true, "moi", draft, null, selectedChat, status = initialStatus(target), msgId = manager.lastSentId)
                )
                draft = ""
            }
        }
        pendingPhoto?.let { photo ->
            val sent = manager.sendBlob(byteArrayOf(KIND_PHOTO) + compressPhoto(photo), target)
            if (sent > 0) {
                MeshState.addBubble(
                    ChatBubble(true, "moi", "Photo envoyée", photo, selectedChat, status = initialStatus(target), msgId = manager.lastSentId)
                )
                pendingPhoto = null
            }
        }
    }

    /** Démarre l'enregistrement du vocal. */
    fun startVoice() {
        if (BleMeshManager.instance == null) {
            log("Service non activé : activez le service")
            return
        }
        if (recorder.start()) {
            recording = true
            log("Enregistrement vocal…")
        } else {
            log("Micro indisponible")
        }
    }

    /** Arrête l'enregistrement et envoie le vocal au téléphone choisi (ou à tous). */
    fun stopAndSendVoice() {
        recording = false
        val bytes: ByteArray? = recorder.stop()
        if (bytes == null || bytes.isEmpty()) {
            log("Vocal vide, rien n'a été envoyé")
            return
        }
        val manager = BleMeshManager.instance ?: return
        val target = if (selectedChat == PUBLIC) null else selectedChat
        val sent = manager.sendBlob(byteArrayOf(KIND_AUDIO) + bytes, target)
        if (sent > 0) {
            val sentId = manager.lastSentId
            val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
            file.writeBytes(bytes)
            MeshState.addBubble(
                ChatBubble(true, "moi", "Vocal envoyé", null, selectedChat, file.absolutePath, status = initialStatus(target), msgId = sentId)
            )
        }
    }

    // Permission du micro : demandée la première fois qu'on appuie sur le micro
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            log("Micro refusé dans les réglages du téléphone")
        } else if (AppState.liveOn) {
            log("Micro autorisé : maintenez le bouton pour parler")
        } else {
            startVoice()
        }
    }

    /** Un appui bref sur le micro : démarre le vocal classique, ou l'arrête et l'envoie. */
    fun onMicTap() {
        if (recording) {
            stopAndSendVoice()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Démarre le live : le micro est envoyé par segments d'une seconde au contact choisi. */
    fun startLive() {
        if (BleMeshManager.instance == null) {
            log("Service non activé : activez le service")
            return
        }
        val target = selectedChat
        if (target == PUBLIC) {
            log("Live : choisissez un contact dans la liste")
            return
        }
        if (MeshState.peers.none { it.id == target }) {
            log("Live : ${MeshState.nameOf(target)} n'est pas à portée")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val streamer = LiveStreamer(context) { bytes ->
            BleMeshManager.instance?.sendLive(bytes, target)
        }
        if (streamer.start()) {
            liveStreamer = streamer
            liveHolding = true
            log("Live : envoi à ${MeshState.nameOf(target)}")
        } else {
            log("Micro indisponible")
        }
    }

    /** Arrête le live quand le bouton est relâché. */
    fun stopLive() {
        val streamer = liveStreamer ?: return
        liveStreamer = null
        liveHolding = false
        streamer.stop()
        log("Live : fin")
    }

    // Scan d'un QR code : le contact est enregistré, puis le service redémarre pour le prendre en compte.
    // L'orientation n'est pas verrouillée : sinon certains téléphones affichent la caméra à l'envers.
    val scanOptions = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Visez le QR code de l'autre téléphone")
        .setBeepEnabled(false)
        .setOrientationLocked(false)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text == null) {
            log("Scan annulé")
        } else {
            val added = QrShare.addContact(context, text)
            if (added != null) {
                log("Contact ajouté : $added. Redémarrage du service…")
                MeshState.refresh()
                onRestart()
            } else {
                log("QR code non reconnu")
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(scanOptions) else log("Caméra refusée dans les réglages du téléphone")
    }

    /** Ouvre la caméra pour scanner un QR code, après avoir demandé la permission si besoin. */
    fun startScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(scanOptions)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // Premier lancement : le nom est demandé, et il est obligatoire
    if (myName.isBlank()) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Bienvenue sur Samchat") },
            text = {
                Column {
                    Text("Choisis le nom que verront les autres téléphones.", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(20) },
                        singleLine = true,
                        placeholder = { Text("ton nom") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveName(nameInput) }, enabled = nameInput.isNotBlank()) {
                    Text("Continuer")
                }
            }
        )
    }

    // Suppression d'un message, après un appui long (comme WhatsApp)
    bubbleToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { bubbleToDelete = null },
            title = { Text("Supprimer ce message ?") },
            text = { Text("Le message sera retiré de ce téléphone. Il ne sera pas supprimé chez les autres.") },
            confirmButton = {
                TextButton(onClick = {
                    MeshState.deleteBubble(target)
                    bubbleToDelete = null
                    log("Message supprimé")
                }) { Text("Supprimer", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { bubbleToDelete = null }) { Text("Annuler") }
            }
        )
    }

    // Mon QR code : l'autre personne le scanne pour t'ajouter
    if (showMyQr) {
        AlertDialog(
            onDismissRequest = { showMyQr = false },
            title = { Text("Mon QR code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val content = QrShare.myContent(context)
                    if (content != null) {
                        val qr = remember(content) { QrShare.bitmap(content) }
                        Image(bitmap = qr.asImageBitmap(), contentDescription = "Mon QR code", modifier = Modifier.size(240.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("L'autre personne le scanne pour t'ajouter.", fontSize = 12.sp, color = grey)
                    } else {
                        Text("Le QR code sera disponible après le premier démarrage du service.", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMyQr = false }) { Text("Fermer") }
            }
        )
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

    // Outils de debug : compteurs et distribution de cette copie
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pseudo : /@$displayName", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Pairs vus : ${peers.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Contacts : ${contacts.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Service : ${if (AppState.serviceOn) "actif" else "arrêté"}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(wifiStatus, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Pairs Wi-Fi Aware : ${MeshState.wifiPeers}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Messages reçus : ${c("in_text")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Fichiers reçus : ${c("in_file")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Envois réussis : ${c("out_ok")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Fichiers envoyés : ${c("out_file")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Relais : ${c("relay")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Sans pair : ${c("no_peer")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Échecs : ${c("fail")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)

                    Spacer(Modifier.height(14.dp))
                    Text("DISTRIBUTION", color = orange, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Version : ${dist.version}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Installée depuis : ${dist.installer}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Empreinte du certificat (SHA-256) :", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(dist.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    TextButton(onClick = { copyToClipboard(context, dist.fingerprint) }) {
                        Text("Copier l'empreinte", fontSize = 13.sp)
                    }

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
                TextButton(onClick = { MeshState.counts.clear() }) { Text("Remettre à zéro") }
            }
        )
    }

    // Paramètres : nouvel écran plein écran (voir SettingsUi.kt)
    if (showSettings) {
        SettingsPanel(
            context = context,
            myName = myName,
            myAvatar = myAvatar,
            myIdText = BleMeshManager.instance?.myId?.joinToString("") { "%02x".format(it) }?.take(16) ?: "—",
            peers = peers,
            contacts = contacts,
            theme = theme,
            version = dist.version,
            installer = dist.installer,
            onTheme = { theme = it },
            onSaveName = { saveName(it) },
            onSaveStatus = { statusInput = it; saveStatus() },
            onChangePhoto = { avatarLauncher.launch("image/*") },
            onShowQr = { showSettings = false; showMyQr = true },
            onScan = { showSettings = false; startScan() },
            onShare = { if (!ShareApp.share(context)) log("Partage impossible") },
            onDebug = { showSettings = false; showDebug = true },
            onClose = { showSettings = false }
        )
    }

    val canSend = draft.isNotBlank() || pendingPhoto != null

    Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {

            // En-tête : logo, /@nom, mesh, pairs et bouton menu (trois traits)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "Logo Samchat",
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "/@$displayName",
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
                TextButton(onClick = {
                    nameInput = myName
                    statusInput = ProfileStore.myStatus(context)
                    showSettings = true
                }) {
                    MenuIcon(inkColor, 20.dp)
                }
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
                            log("Activation du service demandée")
                            onStart()
                        } else {
                            log("Service arrêté")
                            onStop()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = green)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Choix de la conversation : Tous, puis chaque contact avec sa photo (grisé s'il est hors de portée)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    ChatChip("Tous", selectedChat == PUBLIC, true, green, inkColor, null) { selectedChat = PUBLIC }
                }
                items(contacts) { contact ->
                    // Lit profileTick pour se recomposer quand une photo ou un statut arrive
                    val avatar = remember(contact.id, profileTick) {
                        ProfileStore.avatarBitmapOf(context, contact.id)
                    }
                    ChatChip(
                        "@${MeshState.nameOf(contact.id)}",
                        selectedChat == contact.id,
                        peers.any { it.id == contact.id },
                        green,
                        inkColor,
                        avatar
                    ) { selectedChat = contact.id }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Titre de la conversation affichée
            Text(
                if (selectedChat == PUBLIC) "Discussion : tous" else "Privé avec @${MeshState.nameOf(selectedChat)}",
                color = grey,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            if (selectedChat != PUBLIC) {
                val contactAvatar = remember(selectedChat, profileTick) {
                    ProfileStore.avatarBitmapOf(context, selectedChat)
                }
                val status = remember(selectedChat, profileTick) {
                    ProfileStore.statusOf(context, selectedChat)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    contactAvatar?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Photo de profil",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (status.isNotBlank()) {
                        Text(status, color = inkColor, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                    }
                }
            }

            // Conversation : message « Aucun appareil détecté » s'il n'y a ni contact ni message, sinon les bulles
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty() && contacts.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("👥", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Aucun appareil détecté", color = inkColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Activez Samchat sur un autre téléphone à proximité.", color = grey, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(state = chatState, modifier = Modifier.fillMaxSize()) {
                        items(visible) { b ->
                            // Photo de profil de l'auteur : la mienne pour mes messages, sinon celle de l'expéditeur
                            val unknownAuthor = !b.mine && (b.author == "photo" || b.author == "vocal")
                            val avatarBmp = remember(b.mine, b.author, profileTick, myAvatar) {
                                if (b.mine) myAvatar
                                else if (unknownAuthor) null
                                else ProfileStore.avatarBitmapOf(context, b.author)
                            }
                            val initial = when {
                                b.mine -> displayName.take(1).uppercase()
                                unknownAuthor -> "?"
                                else -> MeshState.nameOf(b.author).take(1).uppercase()
                            }
                            val bubble: @Composable () -> Unit = {
                                Surface(
                                    color = if (b.mine) mineBg else otherBg,
                                    shape = RoundedCornerShape(16.dp),
                                    // Appui long : proposer la suppression du message
                                    modifier = Modifier.pointerInput(b) {
                                        detectTapGestures(onLongPress = { bubbleToDelete = b })
                                    }
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(bubbleTitle(b), color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        b.image?.let { img ->
                                            Image(
                                                bitmap = img.asImageBitmap(),
                                                contentDescription = "Photo",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(240.dp)
                                            )
                                        }
                                        b.audio?.let { path ->
                                            Spacer(Modifier.height(4.dp))
                                            VoiceBubble(path, green, grey, inkColor)
                                            Text(
                                                "⬇ Enregistrer le vocal",
                                                color = blue,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    val name = saveToDownloads(context, path, "audio/mp4", "vocal")
                                                    log(if (name != null) "Vocal enregistré : Téléchargements/Samchat/$name" else "Enregistrement impossible")
                                                }.padding(vertical = 4.dp)
                                            )
                                        }
                                        // Bouton de téléchargement : copie la photo dans Téléchargements/Samchat
                                        b.imagePath?.let { path ->
                                            Text(
                                                "⬇ Enregistrer la photo",
                                                color = blue,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    val name = saveToDownloads(context, path, "image/jpeg", "photo")
                                                    log(if (name != null) "Photo enregistrée : Téléchargements/Samchat/$name" else "Enregistrement impossible")
                                                }.padding(vertical = 4.dp)
                                            )
                                        }
                                        Text(b.text, color = inkColor, fontSize = 15.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                b.time,
                                                color = grey,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            // Le statut devient vert et en gras quand le message est lu
                                            b.status?.let { s ->
                                                val read = s.contains("Lu")
                                                Text(
                                                    "  $s",
                                                    color = if (read) readGreen else grey,
                                                    fontSize = if (read) 11.sp else 10.sp,
                                                    fontWeight = if (read) FontWeight.Bold else FontWeight.Normal,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    if (!b.mine) {
                                        AvatarDot(avatarBmp, initial, 30.dp, lightGrey, inkColor)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    bubble()
                                    if (b.mine) {
                                        Spacer(Modifier.width(6.dp))
                                        AvatarDot(avatarBmp, initial, 30.dp, lightGrey, inkColor)
                                    }
                                }
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
                        CircleButton("➤", green, Color.White, size = 50.dp) { send() }
                    } else {
                        MicButton(
                            recording = recording || liveHolding,
                            live = AppState.liveOn,
                            onTap = { onMicTap() },
                            onHoldStart = { startLive() },
                            onHoldEnd = { stopLive() }
                        )
                    }
                }
            }
        }
    }
}
