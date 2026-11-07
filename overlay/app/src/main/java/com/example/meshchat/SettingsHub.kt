package com.example.meshchat

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService

private val LANGUAGES = listOf(
    "fr" to "Français", "en" to "English", "es" to "Español", "pt" to "Português",
    "ar" to "العربية", "zh" to "中文",
    "bm" to "Bambara", "dyu" to "Dioula", "wo" to "Wolof", "ha" to "Hausa",
    "mos" to "Mooré", "ff" to "Pulaar", "snk" to "Soninké", "song" to "Songhaï",
    "mnk" to "Mandinka", "fon" to "Fon", "ee" to "Ewe", "yo" to "Yoruba",
    "ig" to "Igbo", "ak" to "Akan", "srr" to "Sérère", "tmh" to "Tamasheq"
)

private val TEXTS: Map<String, List<String>> = mapOf(
    "settings" to listOf("Paramètres", "Settings", "Ajustes", "Definições", "الإعدادات", "设置"),
    "profile" to listOf("Mon profil", "My profile", "Mi perfil", "Meu perfil", "ملفي الشخصي", "我的资料"),
    "profile_hint" to listOf("Photo, nom, identifiant et statut.", "Photo, name, ID and status.", "Foto, nombre, ID y estado.", "Foto, nome, ID e estado.", "الصورة والاسم والمعرّف والحالة.", "头像、名称、ID 和状态。"),
    "appearance" to listOf("Apparence", "Appearance", "Apariencia", "Aparência", "المظهر", "外观"),
    "appearance_hint" to listOf("Thème clair, sombre ou automatique.", "Light, dark or automatic theme.", "Tema claro, oscuro o automático.", "Tema claro, escuro ou automático.", "سمة فاتحة أو داكنة أو تلقائية.", "浅色、深色或自动主题。"),
    "connection" to listOf("Connexion", "Connection", "Conexión", "Ligação", "الاتصال", "连接"),
    "connection_hint" to listOf("Service, Bluetooth, Wi-Fi et Push-to-Talk.", "Service, Bluetooth, Wi-Fi and Push-to-Talk.", "Servicio, Bluetooth, Wi-Fi y Push-to-Talk.", "Serviço, Bluetooth, Wi-Fi e Push-to-Talk.", "الخدمة والبلوتوث والواي فاي والتحدث المباشر.", "服务、蓝牙、Wi-Fi 和按键通话。"),
    "relay" to listOf("Réseau et relais", "Network and relay", "Red y relevo", "Rede e retransmissão", "الشبكة والترحيل", "网络与中继"),
    "relay_hint" to listOf("Relais maximum, voisins, conservation.", "Max relays, neighbours, retention.", "Máximo de relevos, vecinos, conservación.", "Máximo de retransmissões, vizinhos, retenção.", "أقصى عدد من الترحيلات والجيران والاحتفاظ.", "最多中继、邻居、保留时间。"),
    "c
