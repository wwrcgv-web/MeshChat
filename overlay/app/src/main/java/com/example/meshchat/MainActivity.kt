package com.example.meshchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
private fun MeshHome(onStart: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf("MeshChat prêt.", "Appuyez sur ACTIVER LE SERVICE sur chaque téléphone.")) }
    val green = Color(0xFF22C55E)

    DisposableEffect(Unit) {
        BleMeshManager.listener = { line -> log = log + line }
        onDispose { BleMeshManager.listener = null }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF09090B)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("[ MESHCHAT // HORS-RÉSEAU ]", color = green, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text("MESSAGERIE P2P • BLUETOOTH LE", color = Color(0xFFA3E635), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = green), modifier = Modifier.fillMaxWidth()) { Text("ACTIVER LE SERVICE", color = Color.Black) }
            Spacer(Modifier.height(12.dp))
            Text("JOURNAL", color = green)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                items(log) { line ->
                    Text("> $line", color = Color(0xFFA3E635), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message") }, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = green, unfocusedBorderColor = green.copy(alpha = .5f), focusedLabelColor = green, unfocusedLabelColor = green))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        val manager = BleMeshManager.instance
                        if (manager == null) {
                            log = log + "Service non activé : appuyez d'abord sur ACTIVER LE SERVICE"
                        } else {
                            val n = manager.sendMessage(draft)
                            if (n > 0) { log = log + "Moi : $draft"; draft = "" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534))
            ) { Text("ENVOYER") }
            Spacer(Modifier.height(6.dp))
            Text("Test : service activé sur les deux téléphones, à quelques mètres l'un de l'autre.", color = Color.LightGray, fontSize = 11.sp)
        }
    }
}
