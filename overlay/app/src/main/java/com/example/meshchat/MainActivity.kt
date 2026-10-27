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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.service.MeshService
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshHome(onStart = { askPermissions() }) }
    }

    private fun askPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMeshService()
        else permissions.launch(needed)
    }

    private fun startMeshService() {
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java).setAction(MeshService.ACTION_START))
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

@Composable
private fun MeshHome(onStart: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var bubbles by remember { mutableStateOf(listOf<Bubble>()) }
    var journal by remember { mutableStateOf(listOf("MeshChat prêt.")) }
    var peerCount by remember { mutableStateOf("0") }
    var showPicker by remember { mutableStateOf(false) }
    val chatState = rememberLazyListState()
    val context = LocalContext.current
    val green = Color(0xFF22C55E)
    val bubbleMine = Color(0xFF166534)
    val bubbleOther = Color(0xFF1F2937)

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
                line.startsWith("Reçu de ") -> {
                    val rest = line.removePrefix("Reçu de ")
                    val author = rest.substringBefore(" : ")
                    val text = rest.substringAfter(" : ", "")
                    bubbles = bubbles + Bubble(false, author, text)
                }
                line.startsWith("Pair trouvé") -> {
                    Regex("""\((\d+) au total\)""").find(line)?.groupValues?.get(1)?.let { peerCount = it }
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF09090B)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("[ MESHCHAT // HORS-RÉSEAU ]", color = green, fontSize = 17.sp)
            Text("Pairs vus : $peerCount", color = Color(0xFFA3E635), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth()) {
                Text("ACTIVER LE SERVICE", color = Color.Black)
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(state = chatState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(bubbles) { b ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(color = if (b.mine) bubbleMine else bubbleOther, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(if (b.mine) "Moi" else if (b.author == "photo") "Photo" else "@${b.author}", color = green, fontSize = 11.sp)
                                b.image?.let { img ->
                                    Image(bitmap = img.asImageBitmap(), contentDescription = "Photo", modifier = Modifier.size(180.dp))
                                }
                                Text(b.text, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            Text("JOURNAL", color = green, fontSize = 12.sp)
            LazyColumn(Modifier.height(90.dp).fillMaxWidth()) {
                items(journal.takeLast(30)) { line ->
                    Text("> $line", color = Color(0xFFA3E635), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Aperçu de la photo choisie, avant envoi
            pendingPhoto?.let { photo ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap = photo.asImageBitmap(), contentDescription = "Aperçu", modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Photo prête : appuyez sur ENVOYER", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pendingPhoto = null }) { Text("Retirer") }
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Écrire un message…") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = green,
                        unfocusedBorderColor = green.copy(alpha = .5f)
                    )
                )
                Spacer(Modifier.width(6.dp))
                Button(onClick = { showPicker = true }, colors = ButtonDefaults.buttonColors(containerColor = bubbleOther)) { Text("📷") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { journal = journal + "Vocal : pas encore branché" }, colors = ButtonDefaults.buttonColors(containerColor = bubbleOther)) { Text("🎤") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        val manager = BleMeshManager.instance
                        if (manager == null) {
                            journal = journal + "Service non activé : appuyez sur ACTIVER LE SERVICE"
                            return@Button
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
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = bubbleMine)
                ) { Text("ENVOYER") }
            }
        }
    }
}
