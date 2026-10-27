package com.example.meshchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.service.MeshService
import java.io.ByteArrayOutputStream

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

/** Une bulle de conversation : texte ou image, reçue (mine = false) ou envoyée par moi (mine = true). */
private data class Bubble(val mine: Boolean, val author: String, val text: String, val image: Bitmap? = null)

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

@Composable
private fun CircleButton(label: String, background: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontSize = 18.sp)
    }
}

@Composable
private fun MeshHome(onStart: () -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var bubbles by remember { mutableStateOf(listOf<Bubble>()) }
    var journal by remember { mutableStateOf(listOf("MeshChat prêt.")) }
    var peerCount by remember { mutableStateOf("0") }
    var pseudo by remember { mutableStateOf("anon0000") }
    var counts by remember { mutableStateOf(mapOf<String, Int>()) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf("système") }
    val chatState = rememberLazyListState()
    val context = LocalContext.current

    // Thème : « système » suit le réglage du téléphone
    val dark = when (theme) {
        "clair" -> false
        "sombre" -> true
        else -> isSystemInDarkTheme()
    }
    val green = if (dark) Color(0xFF6EE7A0) else Color(0xFF3F7D3F)
    val blue = if (dark) Color(0xFF93C5FD) else Color(0xFF2563EB)
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
                line.startsWith("Reçu de ") -> {
                    count("in_text")
                    val rest = line.removePrefix("Reçu de ")
                    val author = rest.substringBefore(" : ")
                    val text = rest.substringAfter(" : ", "")
                    bubbles = bubbles + Bubble(false, author, text)
                }
                line.startsWith("Pair trouvé") -> {
                    Regex("""\((\d+) au total\)""").find(line)?.groupValues?.get(1)?.let { peerCount = it }
                    journal = journal + line
                }
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
        // Fichier complet reçu : on le décode et on l'affiche comme une photo reçue
        BleMeshManager.blobListener = { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { photo ->
                bubbles = bubbles + Bubble(false, "photo", "Photo reçue", photo)
            }
        }
        onDispose {
            BleMeshManager.listener = null
            BleMeshManager.blobListener = null
        }
    }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) chatState.animateScrollToItem(bubbles.size - 1)
    }

    /** Envoie le texte et/ou la photo en aperçu. */
    fun send() {
        val manager = BleMeshManager.instance
        if (manager == null) {
            journal = journal + "Service non activé : activez le service"
            return
        }
        if (draft.isNotBlank()) {
            val sent = manager.sendMessage(draft)
            if (sent > 0) {
                bubbles = bubbles + Bubble(true, "moi", draft)
                draft = ""
            }
        }
        pendingPhoto?.let { photo ->
            val sent = manager.sendBlob(compressPhoto(photo))
            if (sent > 0) {
                bubbles = bubbles + Bubble(true, "moi", "Photo envoyée", photo)
                pendingPhoto = null
            }
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
            title = { Text("🐞 Outils de debug", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Pseudo : /@$pseudo", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Pairs vus : $peerCount", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
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
                    }
                }
            }
        }
    }

    val canSend = draft.isNotBlank() || pendingPhoto != null

    Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {

            // En-tête : /@pseudo, mesh, pairs, 🐞 debug et ⚙ paramètres
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "/@$pseudo",
                    color = green,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text("📡 mesh", color = blue, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text("👥 $peerCount", color = grey, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { showDebug = true }) { Text("🐞", fontSize = 16.sp) }
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

            // Conversation
            LazyColumn(state = chatState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(bubbles) { b ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(color = if (b.mine) mineBg else otherBg, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    if (b.mine) "Moi" else if (b.author == "photo") "Photo" else "@${b.author}",
                                    color = green,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                b.image?.let { img ->
                                    Image(bitmap = img.asImageBitmap(), contentDescription = "Photo", modifier = Modifier.size(200.dp))
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
                        CircleButton("🎤", lightGrey, inkColor) { journal = journal + "Vocal : pas encore branché" }
                    }
                }
            }
        }
    }
}
