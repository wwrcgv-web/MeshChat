package com.example.meshchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.service.MeshService

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

/** Une bulle de conversation : reçue (mine = false) ou envoyée par moi (mine = true). */
private data class Bubble(val mine: Boolean, val author: String, val text: String)

@Composable
private fun MeshHome(onStart: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var bubbles by remember { mutableStateOf(listOf<Bubble>()) }
    var journal by remember { mutableStateOf(listOf("MeshChat prêt.")) }
    var peerCount by remember { mutableStateOf("0") }
    val chatState = rememberLazyListState()
    val green = Color(0xFF22C55E)
    val bubbleMine = Color(0xFF166534)
    val bubbleOther = Color(0xFF1F2937)

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
        onDispose { BleMeshManager.listener = null }
    }

    // Fait défiler la conversation vers le dernier message
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) chatState.animateScrollToItem(bubbles.size - 1)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF09090B)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // En-tête
            Text("[ MESHCHAT // HORS-RÉSEAU ]", color = green, fontSize = 17.sp)
            Text("Pairs vus : $peerCount", color = Color(0xFFA3E635), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth()) {
                Text("ACTIVER LE SERVICE", color = Color.Black)
            }
            Spacer(Modifier.height(8.dp))

            // Conversation
            LazyColumn(
                state = chatState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(bubbles) { b ->
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentAlignment = if (b.mine) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(color = if (b.mine) bubbleMine else bubbleOther, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(if (b.mine) "Moi" else "@${b.author}", color = green, fontSize = 11.sp)
                                Text(b.text, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // Journal réduit
            Text("JOURNAL", color = green, fontSize = 12.sp)
            LazyColumn(Modifier.height(90.dp).fillMaxWidth()) {
                items(journal.takeLast(30)) { line ->
                    Text("> $line", color = Color(0xFFA3E635), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Saisie
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
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            val manager = BleMeshManager.instance
                            if (manager == null) {
                                journal = journal + "Service non activé : appuyez sur ACTIVER LE SERVICE"
                            } else {
                                val sent = manager.sendMessage(draft)
                                if (sent > 0) {
                                    bubbles = bubbles + Bubble(true, "moi", draft)
                                    draft = ""
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = bubbleMine)
                ) { Text("ENVOYER") }
            }
        }
    }
}
