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
    "✓✓ " -> 4
    "✓ Parti" -> 3
    "⏳ En attente" -> 2
    "✗ Non envoyé" -> 1
    else -> 0
}

/**
 * Lecteur unique de l'app : un seul son à la fois (vocal reçu, vocal envoyé ou live).
 * Lancer un son coupe
