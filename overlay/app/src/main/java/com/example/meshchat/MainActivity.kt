package com.example.meshchat

import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.service.MeshService
import com.example.meshchat.service.RelayService
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

    fun start(): Boolean {
        val out = File(context.cacheDir, "vocal_envoi.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
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
        private val owner = Any()

        fun play(path: String) {
            if (Playback.currentPath == path) {
                Playback.stop()
            } else {
                Playback.play(path, owner)
            }
        }
    }
}

/** Enregistre le micro par segments d'une seconde, pour la parole en direct. */
private class LiveStreamer(
    private val context: Context,
    private val onSegment: (ByteArray) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var running = false
    private var counter = 0

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

/** État partagé de l'écran (l'état du service vient de BleMeshManager). */
object AppState {
    var serviceOn by mutableStateOf(false)
    var runInBackground by mutableStateOf(true)
    var liveOn by mutableStateOf(false)
    var openChat: String? = null
}

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startMeshService()
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.liveOn = getSharedPreferences("meshchat", Context.MODE_PRIVATE).getBoolean("live_on", false)
        Lang.load(this)
        val accepted = termsAccepted()
        setContent {
            var acc by remember { mutableStateOf(accepted) }
            if (!acc) {
                // Premier lancement : aucune permission n'est demandée et aucun service ne démarre
                // tant que les Conditions d'Utilisation ne sont pas acceptées.
                TermsScreen(
                    onAccept = {
                        acceptTerms()
                        acc = true
                        startAfterTerms()
                    }
                )
            } else {
                MeshHome(
                    onStart = { askPermissions() },
                    onStop = { stopMeshService() },
                    onRestart = { restartMeshService() },
                    onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                )
            }
        }
        if (accepted) startAfterTerms()
    }

    /** Vrai si les Conditions d'Utilisation ont déjà été acceptées sur ce téléphone. */
    private fun termsAccepted(): Boolean =
        getSharedPreferences("meshchat", Context.MODE_PRIVATE).getBoolean(KEY_CGU_ACCEPTED, false)

    /** Enregistre l'acceptation, avec sa date, pour ne plus réafficher l'écran. */
    private fun acceptTerms() {
        getSharedPreferences("meshchat", Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CGU_ACCEPTED, true)
            .putLong(KEY_CGU_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Ce qui ne démarre qu'après acceptation : mémoire de l'app (annuaire et historique),
     * relais Internet, puis demandes de permission et service Bluetooth.
     */
    private fun startAfterTerms() {
        MeshState.attach(this)
        RelayClient.attach(this)
        // Inscription de l'appareil auprès du relais, hors du fil principal (réseau)
        val appContext = applicationContext
        Thread {
            // Null si l'appareil est déjà inscrit : on n'écrit pas de ligne inutile au journal
            val ligne = RelayClient.assurerEnregistrement(appContext)
            if (ligne != null) {
                Handler(Looper.getMainLooper()).post { MeshState.addJournal(ligne) }
            }
        }.start()
        // Sans signature ECDSA, l'enregistrement d'appareil serait voué à l'échec : on le dit tôt
        MeshState.addJournal(
            if (LocalIdentity.autoTestSignature(this)) "Signature d'identité : OK"
            else "Signature d'identité indisponible : repli sur le jeton"
        )
        if (RelayClient.isEnabled(this)) RelayService.start(this)
        if (BleMeshManager.instance == null) askPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Écran visible : relève rapide (5 s) et tout de suite, sans attendre le tour suivant
        RelayPoll.setAppVisible(true)
        RelayPoll.requestNow()
        // Scan BLE rapide le temps de retrouver les voisins, puis mode économe
        BleMeshManager.instance?.setAppVisible(true)
    }

       override fun onResume() {
        super.onResume()
        Lang.load(this)
       } 

        override fun onPause() {
        super.onPause()
        }

    override fun onStop() {
        super.onStop()
        // Écran fermé : la relève se calme (30 s) et le scan BLE passe en mode économe
        RelayPoll.setAppVisible(false)
        BleMeshManager.instance?.setAppVisible(false)
        BleMeshManager.instance?.openChatPeer = null
        if (!AppState.runInBackground && AppState.serviceOn) stopMeshService()
    }

    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
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

    private fun restartMeshService() {
        stopMeshService()
        Handler(Looper.getMainLooper()).postDelayed({ startMeshService() }, 1000L)
    }
}

private enum class Etat { ATTENTE, ENVOYE, RECU, LU, ECHEC }

/** Traduit le statut stocké (texte, hérité) en état affichable. Aucune écriture. */
private fun etatDe(statut: String?): Etat? = when {
    statut.isNullOrBlank() -> null
    statut.contains("Lu") || statut.contains("Read") -> Etat.LU
    statut.contains("Reçu") || statut.contains("Received") -> Etat.RECU
    statut.contains("Parti") || statut.contains("Envoyé") || statut.contains("Sent") -> Etat.ENVOYE
    statut.contains("En attente") || statut.contains("Waiting") -> Etat.ATTENTE
    statut.contains("Non envoyé") || statut.contains("Not sent") -> Etat.ECHEC
    else -> null
}

/** Statut d'une bulle : icône seule, le libellé n'existe que pour l'accessibilité. */
@Composable
private fun EtatMessage(statut: String?, fond: Color) {
    val etat = etatDe(statut) ?: return
    val clair = fond.luminance() > 0.45f
    val description = when (etat) {
        Etat.ATTENTE -> t("status_pending")
        Etat.ENVOYE -> t("status_sent")
        Etat.RECU -> t("status_received")
        Etat.LU -> t("status_read")
        Etat.ECHEC -> t("status_failed")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = description }
    ) {
        when (etat) {
            Etat.ATTENTE -> Text("⏳", fontSize = 12.sp)
            Etat.ECHEC -> Text("✗", color = SamchatColors.Danger, fontSize = 12.sp)
            Etat.ENVOYE -> Coche("✓", Color.White, if (clair) Color(0xE61F2937) else null)
            Etat.RECU -> Coche("✓✓", Color.White, if (clair) Color(0xE61F2937) else null)
            Etat.LU -> Coche("✓✓", Color(0xFF15803D), if (clair) null else Color(0xB3FFFFFF))
        }
    }
}

/**
 * Une ou deux coches. Sur fond clair, un contour sombre de 4 copies décalées garde le blanc
 * lisible ; sur fond sombre, le blanc se suffit. Aucun texte n'est affiché.
 */
@Composable
private fun Coche(texte: String, couleur: Color, contour: Color?) {
    if (contour == null) {
        Text(texte, color = couleur, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        return
    }
    Box(contentAlignment = Alignment.Center) {
        listOf(-0.6f to 0f, 0.6f to 0f, 0f to -0.6f, 0f to 0.6f).forEach { (dx, dy) ->
            Text(
                texte, color = contour, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(dx.dp, dy.dp)
            )
        }
        Text(texte, color = couleur, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun ouiNon(valeur: Boolean): String = if (valeur) "oui" else "non"

private fun codeDepot(): String {
    val code = RelayClient.lastDepotCode
    return if (code < 0) "réseau" else code.toString()
}

private const val KEY_CGU_ACCEPTED = "cgv_accepted"
private const val KEY_CGU_ACCEPTED_AT = "cgv_accepted_at"
private const val PUBLIC = "public"
private const val KIND_PHOTO: Byte = 1
private const val KIND_AUDIO: Byte = 2
private const val HOLD_MS = 350L

private fun initialStatus(target: String?): String = if (target == null) "✓ ${t("sent")}" else "⏳ ${t("waiting")}"

private fun bubbleTitle(b: ChatBubble): String = when {
    b.mine -> t("you")
    b.author == "photo" -> t("photo")
    b.author == "vocal" -> t("voice")
    else -> "@${MeshState.nameOf(b.author)}"
}

private fun compressPhoto(source: Bitmap): ByteArray {
    val scale = 1600f / maxOf(source.width, source.height)
    val small = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else source
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}

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

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun VoiceBubble(path: String, green: Color, grey: Color) {
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
            modifier = Modifier.size(40.dp).clip(CircleShape).background(green).clickable { VoiceRecorder.play(path) },
            contentAlignment = Alignment.Center
        ) {
            Text(if (playing) "■" else "▶", color = Color.White, fontSize = 15.sp)
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
                Text(if (playing) formatMs(elapsed) else "0:00", color = grey, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(formatMs(total), color = grey, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Cercle de chargement affiché à la place du bouton tant que le message est en attente. */
@Composable
private fun PendingCircle(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(40.dp),
        color = color,
        strokeWidth = 3.dp
    )
}

@Composable
private fun AvatarDot(bitmap: Bitmap?, label: String, size: Dp, bg: Color, ink: Color) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(label, color = ink, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

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

private class DistributionInfo(val version: String, val installer: String, val fingerprint: String)

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

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("empreinte", text))
}

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

@Composable
private fun MicButton(recording: Boolean, live: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val bg = if (recording) SamchatColors.Danger else Color.White
    val fg = if (recording) Color.White else Color.Black
    Box(
        modifier = Modifier
            .size(52.dp)
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
        Canvas(modifier = Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(color = fg, topLeft = Offset(w * 0.30f, 0f), size = Size(w * 0.40f, h * 0.56f), cornerRadius = CornerRadius(w * 0.20f))
            drawArc(color = fg, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.30f), size = Size(w * 0.70f, h * 0.50f), style = Stroke(width = w * 0.08f))
            drawLine(fg, Offset(w * 0.5f, h * 0.80f), Offset(w * 0.5f, h * 0.95f), strokeWidth = w * 0.08f)
            drawLine(fg, Offset(w * 0.30f, h * 0.95f), Offset(w * 0.70f, h * 0.95f), strokeWidth = w * 0.08f)
        }
    }
}

@Composable
private fun CircleButton(label: String, background: Color, textColor: Color, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontSize = 20.sp)
    }
}
@Composable
private fun MeshHome(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var selectedChat by remember { mutableStateOf(PUBLIC) }
    var inChat by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    var showBluetoothAsk by remember { mutableStateOf(false) }
    var bubbleToDelete by remember { mutableStateOf<ChatBubble?>(null) }
    var fullscreenPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var theme by remember { mutableStateOf("système") }
    var recording by remember { mutableStateOf(false) }
    var liveHolding by remember { mutableStateOf(false) }
    var liveStreamer by remember { mutableStateOf<LiveStreamer?>(null) }

    val chatState = rememberLazyListState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    var myName by remember { mutableStateOf(prefs.getString("my_name", "") ?: "") }
    var myAvatar by remember {
        mutableStateOf(ProfileStore.myAvatar(context)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
    }
    val recorder = remember { VoiceRecorder(context) }
    val dist = remember { readDistribution(context) }

    val peers = MeshState.peers
    val contacts = MeshState.contacts
    val pseudo = MeshState.pseudo
    val journal = MeshState.journal
    val bubbles = MeshState.bubbles
    val counts = MeshState.counts
    val displayName = if (myName.isNotBlank()) myName else pseudo
    val profileTick = MeshState.profileTick

    fun log(line: String) = MeshState.addJournal(line)

    LaunchedEffect(Unit) {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt != null && !bt.isEnabled) showBluetoothAsk = true
    }

    fun saveName(raw: String) {
        val clean = raw.trim().take(20)
        if (clean.isBlank()) return
        prefs.edit().putString("my_name", clean).apply()
        myName = clean
        MeshState.refresh()
    }

    fun saveStatus(text: String) {
        ProfileStore.setMyStatus(context, text)
        BleMeshManager.instance?.broadcastProfile()
        log(t("saved_status"))
    }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bitmap = uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }
        if (bitmap == null) {
            log(t("reading_error"))
        } else if (ProfileStore.setMyAvatar(context, bitmap) == null) {
            log(t("send_fail"))
        } else {
            myAvatar = ProfileStore.myAvatar(context)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            BleMeshManager.instance?.broadcastProfile()
            log(t("saved_status"))
        }
    }

    val dark = when (theme) {
        "clair" -> false
        "sombre" -> true
        else -> isSystemInDarkTheme()
    }
    val green = SamchatColors.Green
    val readGreen = Color(0xFF4ADE80)
    val blue = Color(0xFF93C5FD)
    val grey = SamchatColors.Grey
    val bgColor = if (dark) SamchatColors.Background else Color(0xFFF6F7F6)
    val inkColor = if (dark) SamchatColors.White else Color(0xFF111827)
    val mineBg = SamchatColors.GreenSoft.copy(alpha = 0.35f)
    val otherBg = SamchatColors.Card
    val inputBg = SamchatColors.Field
    val lightGrey = SamchatColors.Field

    LaunchedEffect(selectedChat, AppState.serviceOn, inChat) {
        val peer = if (inChat && selectedChat != PUBLIC) selectedChat else null
        AppState.openChat = peer
        val manager = BleMeshManager.instance
        manager?.openChatPeer = peer
        if (peer != null) manager?.markRead(peer)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) pendingPhoto = bitmap
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bitmap = uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }
        if (bitmap != null) pendingPhoto = bitmap
    }

    val visible = bubbles.filter { it.chat == selectedChat }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) chatState.animateScrollToItem(visible.size - 1)
    }

            fun send() {
        val manager = BleMeshManager.instance
        val target = if (selectedChat == PUBLIC) null else selectedChat

        if (draft.isNotBlank()) {
            val text = draft
            val key = if (target != null) LocalIdentity.sharedKeyWith(context, target) else null

            val peerPub = if (target != null) LocalIdentity.peerPublicHex(context, target) else null
            log("Envoi : privé=${ouiNon(target != null)} clé=${ouiNon(key != null)} cléPub=${ouiNon(peerPub != null)} internet=${ouiNon(RelayClient.isEnabled(context))}")
            if (target != null && key != null && peerPub != null && RelayClient.isEnabled(context)) {
                // Envoi par Internet : la requête part dans un autre fil, l'écran reste fluide
                draft = ""
                Thread {
                    val relayId = RelayDispatch.envoyerParRelais(context, key, peerPub, text)
                    Handler(Looper.getMainLooper()).post {
                        log("Internet : HTTP ${codeDepot()}")
                        if (relayId == null) log("Internet : ${RelayClient.lastError}")
                        if (relayId != null) {
                            MeshState.addBubble(
                                ChatBubble(true, "moi", text, null, selectedChat, status = "🌐 ${t("sent")}", msgId = relayId)
                            )
                                                } else {
                            // Échec Internet : repli sur le Bluetooth
                            val m = BleMeshManager.instance
                            val sent = m?.sendMessage(text, target) ?: 0
                            if (sent > 0) {
                                MeshState.addBubble(
                                    ChatBubble(true, "moi", text, null, selectedChat, status = initialStatus(target), msgId = m?.lastSentId)
                                )
                            } else {
                                if (draft.isEmpty()) draft = text
                                log(t("send_fail"))
                            }
                        }
                    }
                }.start()
            } else if (manager != null) {
                val sent = manager.sendMessage(text, target)
                if (sent > 0) {
                    MeshState.addBubble(
                        ChatBubble(true, "moi", text, null, selectedChat, status = initialStatus(target), msgId = manager.lastSentId)
                    )
                    draft = ""
                } else {
                    log(t("send_fail"))
                }
            } else {
                log(t("service_off"))
            }
        }

        pendingPhoto?.let { photo ->
            val key = if (target != null) LocalIdentity.sharedKeyWith(context, target) else null
            val peerPub = if (target != null) LocalIdentity.peerPublicHex(context, target) else null
            log("Envoi photo : privé=${ouiNon(target != null)} clé=${ouiNon(key != null)} cléPub=${ouiNon(peerPub != null)} internet=${ouiNon(RelayClient.isEnabled(context))}")
            if (target != null && key != null && peerPub != null && RelayClient.isEnabled(context)) {
                // Envoi par Internet : compression et dépôt dans un autre fil, l'écran reste fluide
                pendingPhoto = null
                Thread {
                    val fileId = RelayFile.envoyer(context, key, peerPub, compressPhoto(photo), "photo")
                    Handler(Looper.getMainLooper()).post {
                        log("Internet : HTTP ${codeDepot()}")
                        if (fileId == null) log("Internet : ${RelayClient.lastError}")
                        if (fileId != null) {
                            MeshState.addBubble(
                                ChatBubble(true, "moi", t("photo_sent"), photo, selectedChat, status = "🌐 ${t("sent")}", msgId = fileId)
                            )
                        } else {
                            // Échec : la photo reste en attente, rien n'est perdu
                            pendingPhoto = photo
                            log(t("send_fail"))
                        }
                    }
                }.start()
            } else if (manager != null) {
                val sent = manager.sendBlob(byteArrayOf(KIND_PHOTO) + compressPhoto(photo), target)
                if (sent > 0) {
                    MeshState.addBubble(
                        ChatBubble(true, "moi", t("photo_sent"), photo, selectedChat, status = initialStatus(target), msgId = manager.lastSentId)
                    )
                    pendingPhoto = null
                } else {
                    log(t("send_fail"))
                }
            } else {
                log(t("service_off"))
            }
        }
            }

    fun startVoice() {
        if (BleMeshManager.instance == null) {
            log(t("service_off"))
            return
        }
        if (recorder.start()) {
            recording = true
            log(t("voice") + "…")
        } else {
            log("Micro indisponible")
        }
    }

        fun stopAndSendVoice() {
        recording = false
        val bytes: ByteArray? = recorder.stop()
        if (bytes == null || bytes.isEmpty()) {
            log("Vocal vide, rien n'a été envoyé")
            return
        }
        val manager = BleMeshManager.instance
        val target = if (selectedChat == PUBLIC) null else selectedChat
        val key = if (target != null) LocalIdentity.sharedKeyWith(context, target) else null
        val peerPub = if (target != null) LocalIdentity.peerPublicHex(context, target) else null
        log("Envoi vocal : privé=${ouiNon(target != null)} clé=${ouiNon(key != null)} cléPub=${ouiNon(peerPub != null)} internet=${ouiNon(RelayClient.isEnabled(context))}")

        if (target != null && key != null && peerPub != null && RelayClient.isEnabled(context)) {
            // Envoi par Internet : hors du fil principal, l'écran reste fluide
            Thread {
                val fileId = RelayFile.envoyer(context, key, peerPub, bytes, "audio")
                Handler(Looper.getMainLooper()).post {
                    log("Internet : HTTP ${codeDepot()}")
                    if (fileId == null) log("Internet : ${RelayClient.lastError}")
                    if (fileId != null) {
                        val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
                        file.writeBytes(bytes)
                        MeshState.addBubble(
                            ChatBubble(true, "moi", t("voice_sent"), null, selectedChat, file.absolutePath, status = "🌐 ${t("sent")}", msgId = fileId)
                        )
                    } else {
                        log(t("send_fail"))
                    }
                }
            }.start()
        } else if (manager != null) {
            val sent = manager.sendBlob(byteArrayOf(KIND_AUDIO) + bytes, target)
            if (sent > 0) {
                val file = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
                file.writeBytes(bytes)
                MeshState.addBubble(
                    ChatBubble(true, "moi", t("voice_sent"), null, selectedChat, file.absolutePath, status = initialStatus(target), msgId = manager.lastSentId)
                )
            } else {
                log(t("send_fail"))
            }
        } else {
            log(t("service_off"))
        }
        }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            log("Micro refusé dans les réglages du téléphone")
        } else if (AppState.liveOn) {
            log("Micro autorisé : maintenez le bouton pour parler")
        } else {
            startVoice()
        }
    }

    fun onMicTap() {
        if (recording) {
            stopAndSendVoice()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun startLive() {
        if (BleMeshManager.instance == null) {
            log(t("service_off"))
            return
        }
        val target = selectedChat
        if (target == PUBLIC) {
            log("Live : choisissez un contact")
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
        val streamer = LiveStreamer(context) { bytes -> BleMeshManager.instance?.sendLive(bytes, target) }
        if (streamer.start()) {
            liveStreamer = streamer
            liveHolding = true
            log("Live : envoi à ${MeshState.nameOf(target)}")
        } else {
            log("Micro indisponible")
        }
    }

    fun stopLive() {
        val streamer = liveStreamer ?: return
        liveStreamer = null
        liveHolding = false
        streamer.stop()
        log("Live : fin")
    }

    /** Traite un QR code lu sur l'écran de partage. */
    fun traiterScan(text: String?) {
        if (text == null) {
            log("Scan annulé")
            return
        }
        val added = QrShare.addContact(context, text)
        if (added != null) {
            log("Contact ajouté : $added. Redémarrage du service…")
            MeshState.refresh()
            onRestart()
        } else {
            log("QR code non reconnu")
        }
    }

    if (myName.isBlank()) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text(t("welcome")) },
            text = {
                Column {
                    Text(t("choose_name"), fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    TextField(value = nameInput, onValueChange = { nameInput = it.take(20) }, singleLine = true, placeholder = { Text(t("your_name")) })
                }
            },
            confirmButton = {
                TextButton(onClick = { saveName(nameInput) }, enabled = nameInput.isNotBlank()) { Text(t("continue"), fontSize = 16.sp) }
            }
        )
    }

    if (showBluetoothAsk && myName.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showBluetoothAsk = false },
            title = { Text(t("bt_title")) },
            text = { Text(t("bt_note")) },
            confirmButton = {
                TextButton(onClick = { showBluetoothAsk = false; onEnableBluetooth() }) { Text(t("bt_enable"), fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothAsk = false }) { Text(t("bt_later"), fontSize = 16.sp) }
            }
        )
    }

    bubbleToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { bubbleToDelete = null },
            title = { Text(t("delete_q")) },
            text = { Text(t("delete_note")) },
            confirmButton = {
                TextButton(onClick = {
                    MeshState.deleteBubble(target)
                    bubbleToDelete = null
                    log(t("delete"))
                }) { Text(t("delete"), color = SamchatColors.Danger, fontSize = 16.sp) }
            },
            dismissButton = { TextButton(onClick = { bubbleToDelete = null }) { Text(t("cancel"), fontSize = 16.sp) } }
        )
    }

    fullscreenPhoto?.let { photo ->
        Dialog(
            onDismissRequest = { fullscreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEE000000))
                    .clickable { fullscreenPhoto = null },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = t("photo"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showMyQr) {
        // Fenêtre plein écran : l'écran QR doit passer au-dessus de tout le reste
        Dialog(
            onDismissRequest = { showMyQr = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            QrScreen(
                context = context,
                onClose = { showMyQr = false },
                onScanned = { texte ->
                    showMyQr = false
                    traiterScan(texte)
                }
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(t("photo_choice")) },
            text = { Text(t("what_do")) },
            confirmButton = {
                TextButton(onClick = { showPicker = false; cameraLauncher.launch(null) }) { Text(t("take_photo"), fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false; galleryLauncher.launch("image/*") }) { Text(t("pick_image"), fontSize = 16.sp) }
            }
        )
    }

    if (showDebug) {
        AlertDialog(
            onDismissRequest = { showDebug = false },
            title = { Text(t("dbg_title"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${t("dbg_peers")} : ${peers.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${t("dbg_contacts")} : ${contacts.size}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${t("dbg_service")} : ${if (AppState.serviceOn) t("active") else t("stopped")}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${t("dbg_received")} : ${counts["in_text"] ?: 0}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${t("dbg_sent")} : ${counts["out_ok"] ?: 0}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("${t("dbg_fail")} : ${counts["fail"] ?: 0}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("${t("dbg_version")} : ${dist.version}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(dist.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    TextButton(onClick = { copyToClipboard(context, dist.fingerprint) }) { Text(t("dbg_copy"), fontSize = 14.sp) }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${t("dbg_log")} (${journal.size})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    journal.takeLast(50).forEach { ligne ->
                        Text("> $ligne", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    TextButton(onClick = { copyToClipboard(context, journal.joinToString("\n")) }) {
                        Text(t("dbg_copy_log"), fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDebug = false }) { Text(t("close"), fontSize = 16.sp) } },
            dismissButton = { TextButton(onClick = { MeshState.counts.clear() }) { Text(t("dbg_reset"), fontSize = 16.sp) } }
        )
    }

    if (showSettings) {
        SettingsHub(
            context = context,
            myName = myName,
            myAvatar = myAvatar,
            peers = peers,
            theme = theme,
            version = dist.version,
            installer = dist.installer,
            onTheme = { theme = it },
            onSaveName = { saveName(it) },
            onSaveStatus = { saveStatus(it) },
            onChangePhoto = { avatarLauncher.launch("image/*") },
            onShare = { if (!ShareApp.share(context)) log(t("send_fail")) },
            onDebug = { showSettings = false; showDebug = true },
            onBack = { showSettings = false }
        )
        return
    }

    BackHandler(enabled = inChat) { inChat = false }

    if (!inChat) {
        ChatListScreen(
            context = context,
            contacts = contacts,
            peers = peers,
            bubbles = bubbles,
            profileTick = profileTick,
            serviceOn = AppState.serviceOn,
            onOpen = { id -> selectedChat = id; inChat = true },
            onSettings = { showSettings = true },
            onShowQr = { showMyQr = true }
        )
        return
    }

    val canSend = draft.isNotBlank() || pendingPhoto != null

    Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { inChat = false }, modifier = Modifier.size(52.dp)) {
                    Text("←", color = inkColor, fontSize = 26.sp)
                }
                Spacer(Modifier.width(6.dp))
                val contactAvatar = if (selectedChat != PUBLIC) {
                    remember(selectedChat, profileTick) { ProfileStore.avatarBitmapOf(context, selectedChat) }
                } else null
                if (contactAvatar != null) {
                    Image(
                        bitmap = contactAvatar.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectedChat == PUBLIC) t("all_group") else "@${MeshState.nameOf(selectedChat)}",
                        color = inkColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    val status = if (selectedChat != PUBLIC) remember(selectedChat, profileTick) {
                        ProfileStore.statusOf(context, selectedChat)
                    } else ""
                    Text(
                        if (selectedChat == PUBLIC) "${peers.size} ${t("all_range")}" else status,
                        color = grey,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(t("no_message"), color = inkColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(t("message_for"), color = grey, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(state = chatState, modifier = Modifier.fillMaxSize()) {
                        items(visible) { b ->
                            BubbleRow(
                                b = b,
                                myAvatar = myAvatar,
                                displayName = displayName,
                                profileTick = profileTick,
                                green = green,
                                grey = grey,
                                ink = inkColor,
                                blue = blue,
                                readGreen = readGreen,
                                mineBg = mineBg,
                                otherBg = otherBg,
                                lightGrey = lightGrey,
                                onLongPress = { bubbleToDelete = it },
                                onPhotoTap = { fullscreenPhoto = it },
                                onLog = { log(it) }
                            )
                        }
                    }
                }
            }

            Text(
                journal.takeLast(2).joinToString("\n") { "> $it" },
                color = grey,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )

            Spacer(Modifier.height(6.dp))

            pendingPhoto?.let { photo ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap = photo.asImageBitmap(), contentDescription = t("photo"), modifier = Modifier.size(60.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("photo_ready_short"), color = inkColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pendingPhoto = null }) { Text(t("remove"), fontSize = 15.sp) }
                }
                Spacer(Modifier.height(4.dp))
            }

            Surface(
                shape = RoundedCornerShape(30.dp),
                color = inputBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    CircleButton("📎", SamchatColors.Card, inkColor, 48.dp) { showPicker = true }
                    Spacer(Modifier.width(6.dp))
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(t("write"), color = grey, fontSize = 16.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = inkColor,
                            unfocusedTextColor = inkColor,
                            cursorColor = green
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    if (canSend) {
                        CircleButton("➤", green, Color.White, 52.dp) { send() }
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

/** Une bulle de message : avatar, contenu (photo, vocal, texte), heure et statut. */
@Composable
private fun BubbleRow(
    b: ChatBubble,
    myAvatar: Bitmap?,
    displayName: String,
    profileTick: Int,
    green: Color,
    grey: Color,
    ink: Color,
    blue: Color,
    readGreen: Color,
    mineBg: Color,
    otherBg: Color,
    lightGrey: Color,
    onLongPress: (ChatBubble) -> Unit,
    onPhotoTap: (Bitmap) -> Unit,
    onLog: (String) -> Unit
) {
    val context = LocalContext.current
    val unknownAuthor = !b.mine && (b.author == "photo" || b.author == "vocal")
    val avatarBmp = remember(b.mine, b.author, profileTick, myAvatar) {
        if (b.mine) myAvatar
        else if (unknownAuthor) null
        else ProfileStore.avatarBitmapOf(context, b.author)
    }
    val pending = b.status?.let { etatDe(it) == Etat.ATTENTE } == true
    val initial = when {
        b.mine -> displayName.take(1).uppercase()
        unknownAuthor -> "?"
        else -> MeshState.nameOf(b.author).take(1).uppercase()
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!b.mine) {
                AvatarDot(avatarBmp, initial, 34.dp, lightGrey, ink)
                Spacer(Modifier.width(6.dp))
            }
            Surface(
                color = if (b.mine) mineBg else otherBg,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.pointerInput(b) {
                    detectTapGestures(onLongPress = { onLongPress(b) })
                }
            ) {
                Column(Modifier.padding(12.dp)) {
                    b.image?.let { img ->
                        Image(
                            bitmap = img.asImageBitmap(),
                            contentDescription = t("photo"),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(240.dp)
                                .clickable { onPhotoTap(img) }
                        )
                    }
                    if (pending && (b.audio != null || b.image != null)) {
                        PendingCircle(green)
                    }
                    if (pending && b.audio == null && b.image == null) {
                        Text(
                            "✕ Annuler",
                            color = SamchatColors.Danger,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onLongPress(b) }
                                .padding(vertical = 4.dp)
                        )
                    }
                    b.audio?.let { path ->
                        if (pending) {
                            Text("⏳ ${t("voice")}", color = grey, fontSize = 14.sp)
                        } else {
                            VoiceBubble(path, green, grey)
                            Text(
                                "⬇",
                                color = ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .semantics { contentDescription = t("saved_voice") }
                                    .clickable {
                                    val name = saveToDownloads(context, path, "audio/mp4", "vocal")
                                    onLog(if (name != null) "Vocal enregistré : Téléchargements/Samchat/$name" else t("send_fail"))
                                }.padding(vertical = 6.dp)
                            )
                        }
                    }
                    b.imagePath?.let { path ->
                        Text(
                            "⬇ ${t("saved_photo")}",
                            color = blue,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                val name = saveToDownloads(context, path, "image/jpeg", "photo")
                                onLog(if (name != null) "Photo enregistrée : Téléchargements/Samchat/$name" else t("send_fail"))
                            }.padding(vertical = 6.dp)
                        )
                    }
                    if (b.text.isNotEmpty() && b.audio == null && b.image == null) {
                        Text(b.text, color = ink, fontSize = 17.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.time, color = grey, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        EtatMessage(b.status, mineBg)
                    }
                }
            }
            if (b.mine) {
                Spacer(Modifier.width(6.dp))
                AvatarDot(avatarBmp, initial, 34.dp, lightGrey, ink)
            }
        }
    }
}
