package com.example.meshchat

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService

private val SBg = Color(0xFF0A0C0B)
private val SCard = Color(0xFF151916)
private val SField = Color(0xFF1F2521)
private val SGreen = Color(0xFF2FBF71)
private val SGreenDark = Color(0xFF1F8A4F)
private val SWhite = Color.White
private val SGrey = Color(0xFFA5ADA8)

/** Icônes simples, toutes dessinées avec le même trait. 1 profil, 2 apparence, 3 connexion, 4 réseau, 5 contacts, 6 partage, 7 langue, 8 diagnostic. */
@Composable
private fun CategoryIcon(kind: Int, tint: Color, iconSize: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(w * x, h * y)
        when (kind) {
            1 -> {
                drawCircle(tint, radius = w * 0.2f, center = o(0.5f, 0.3f), style = stroke)
                drawArc(tint, 180f, 180f, false, topLeft = o(0.15f, 0.55f), size = Size(w * 0.7f, h * 0.7f), style = stroke)
            }
            2 -> {
                drawCircle(tint, radius = w * 0.38f, center = o(0.5f, 0.5f), style = stroke)
                drawArc(tint, 90f, 180f, true, topLeft = o(0.12f, 0.12f), size = Size(w * 0.76f, h * 0.76f))
            }
            3 -> {
                for (r in listOf(0.18f, 0.32f, 0.46f)) {
                    drawArc(tint, 225f, 90f, false, topLeft = o(0.5f - r, 0.8f - r), size = Size(w * 2 * r, w * 2 * r), style = stroke)
                }
                drawCircle(tint, radius = w * 0.06f, center = o(0.5f, 0.8f))
            }
            4 -> {
                val a = o(0.5f, 0.2f); val b = o(0.2f, 0.78f); val c = o(0.8f, 0.78f)
                drawLine(tint, a, b, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, a, c, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, b, c, strokeWidth = stroke.width, cap = StrokeCap.Round)
                listOf(a, b, c).forEach { drawCircle(tint, radius = w * 0.11f, center = it) }
            }
            5 -> {
                drawCircle(tint, radius = w * 0.15f, center = o(0.38f, 0.3f), style = stroke)
                drawArc(tint, 180f, 180f, false, topLeft = o(0.08f, 0.55f), size = Size(w * 0.6f, h * 0.6f), style = stroke)
                drawCircle(tint, radius = w * 0.11f, center = o(0.72f, 0.36f), style = stroke)
                drawArc(tint, 200f, 140f, false, topLeft = o(0.52f, 0.58f), size = Size(w * 0.44f, h * 0.44f), style = stroke)
            }
            6 -> {
                drawLine(tint, o(0.5f, 0.68f), o(0.5f, 0.14f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, o(0.5f, 0.14f), o(0.3f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, o(0.5f, 0.14f), o(0.7f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, o(0.2f, 0.5f), o(0.2f, 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, o(0.2f, 0.88f), o(0.8f, 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, o(0.8f, 0.88f), o(0.8f, 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            7 -> {
                drawCircle(tint, radius = w * 0.4f, center = o(0.5f, 0.5f), style = stroke)
                drawLine(tint, o(0.1f, 0.5f), o(0.9f, 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawOval(tint, topLeft = o(0.3f, 0.1f), size = Size(w * 0.4f, h * 0.8f), style = stroke)
            }
            else -> {
                listOf(0.25f to 0.3f, 0.5f to 0.7f, 0.75f to 0.45f).forEach { (y, x) ->
                    drawLine(tint, o(0.12f, y), o(0.88f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawCircle(SCard, radius = w * 0.12f, center = o(x, y))
                    drawCircle(tint, radius = w * 0.1f, center = o(x, y), style = stroke)
                }
            }
        }
    }
}

/** Flèche à droite d'une catégorie ; elle pivote à l'ouverture. */
@Composable
private fun Chevron(open: Boolean) {
    val angle by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "chevron")
    Canvas(modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = angle }) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawLine(SGrey, Offset(w * 0.32f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(SGrey, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.32f, h * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun CategoryCard(
    kind: Int,
    title: String,
    hint: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = SCard
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(SGreen.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { CategoryIcon(kind, SGreen, 22.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = SWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(hint, color = SGrey, fontSize = 12.sp)
                }
                Chevron(open)
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(note, color = SGrey, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = SGreen,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = SField,
                uncheckedThumbColor = SGrey,
                uncheckedBorderColor = SGrey
            )
        )
    }
}

@Composable
private fun BigButton(text: String, selected: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SGreenDark else SField,
            contentColor = SWhite,
            disabledContainerColor = SField,
            disabledContentColor = SGrey
        )
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Label(text: String) {
    Text(text, color = SGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = SField,
    unfocusedContainerColor = SField,
    focusedTextColor = SWhite,
    unfocusedTextColor = SWhite,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = SGreen
)

@Composable
private fun Avatar(bitmap: Bitmap?, letter: String, size: Dp) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Photo de profil",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(SField), contentAlignment = Alignment.Center) {
            Text(letter, color = SWhite, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Écran Paramètres, plein écran, fond sombre. Il ne contient aucune logique de messagerie :
 * les réglages appellent les mêmes fonctions et écrivent les mêmes préférences qu'avant.
 */
@Composable
fun SettingsPanel(
    context: Context,
    myName: String,
    myAvatar: Bitmap?,
    myIdText: String,
    peers: List<MeshPeer>,
    contacts: List<MeshPeer>,
    theme: String,
    version: String,
    installer: String,
    onTheme: (String) -> Unit,
    onSaveName: (String) -> Unit,
    onSaveStatus: (String) -> Unit,
    onChangePhoto: () -> Unit,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onShare: () -> Unit,
    onDebug: () -> Unit,
    onClose: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(0) }
    var nameInput by remember(myName) { mutableStateOf(myName) }
    var statusInput by remember { mutableStateOf(ProfileStore.myStatus(context)) }

    var runBg by remember { mutableStateOf(AppState.runInBackground) }
    var radioBle by remember { mutableStateOf(prefs.getBoolean("radio_ble", true)) }
    var scanSend by remember { mutableStateOf(prefs.getBoolean(BleMeshManager.PREF_SCAN_SEND, true)) }
    var radioAware by remember { mutableStateOf(prefs.getBoolean("radio_wifi_aware", true)) }
    var radioDirect by remember { mutableStateOf(prefs.getBoolean("radio_wifi_direct", false)) }
    var radioLong by remember { mutableStateOf(prefs.getBoolean("radio_long_range", true)) }
    var liveOn by remember { mutableStateOf(AppState.liveOn) }
    val awareSupported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) }
    val longSupported = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isLeCodedPhySupported == true
    }

    fun toggle(n: Int) { open = if (open == n) 0 else n }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = SBg) {
            Column(Modifier.fillMaxSize()) {
                // Barre du haut
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)
                ) {
                    TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                        Text("←", color = SWhite, fontSize = 24.sp)
                    }
                    Text("Paramètres", color = SWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 28.dp)
                ) {
                    // Résumé du profil
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { open = 1 }
                            .padding(horizontal = 6.dp, vertical = 12.dp)
                    ) {
                        Avatar(myAvatar, myName.take(1).uppercase().ifEmpty { "?" }, 64.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(myName.ifBlank { "Ton nom" }, color = SWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            val s = ProfileStore.myStatus(context)
                            Text(s.ifBlank { "Ajoute un statut" }, color = SGrey, fontSize = 13.sp)
                        }
                    }

                    // 1. Mon profil
                    CategoryCard(1, "Mon profil", "Photo, nom, identifiant et statut.", open == 1, { toggle(1) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(myAvatar, myName.take(1).uppercase().ifEmpty { "?" }, 64.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                BigButton("Changer la photo", onClick = onChangePhoto)
                            }
                        }
                        Text("Recadrée en carré, envoyée à chaque contact.", color = SGrey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

                        Label("NOM D'UTILISATEUR")
                        TextField(
                            value = nameInput,
                            onValueChange = { nameInput = it.take(20) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors(),
                            textStyle = TextStyle(fontSize = 15.sp),
                            placeholder = { Text("ton nom", color = SGrey) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        BigButton("Enregistrer le nom", enabled = nameInput.isNotBlank()) { onSaveName(nameInput) }

                        Label("IDENTIFIANT")
                        Text(myIdText, color = SWhite, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text("Un code technique qui identifie ton téléphone.", color = SGrey, fontSize = 12.sp)

                        Label("STATUT (60 caractères)")
                        TextField(
                            value = statusInput,
                            onValueChange = { statusInput = it.take(ProfileStore.STATUS_MAX_CHARS) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors(),
                            textStyle = TextStyle(fontSize = 15.sp),
                            placeholder = { Text("ton statut", color = SGrey) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        BigButton("Enregistrer le statut") { onSaveStatus(statusInput) }
                        Text("Envoyé à chaque contact.", color = SGrey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }

                    // 2. Apparence
                    CategoryCard(2, "Apparence", "Thème clair, sombre ou automatique.", open == 2, { toggle(2) }) {
                        listOf("système" to "Automatique (système)", "clair" to "Clair", "sombre" to "Sombre").forEach { (key, label) ->
                            BigButton(label, selected = theme == key) { onTheme(key) }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // 3. Connexion
                    CategoryCard(3, "Connexion", "Service, Bluetooth, Wi-Fi et Push-to-Talk.", open == 3, { toggle(3) }) {
                        SettingSwitch("Service en arrière-plan", "Si désactivé, le service s'arrête quand l'app est quittée.", runBg) {
                            runBg = it
                            AppState.runInBackground = it
                        }
                        SettingSwitch("Bluetooth BLE", "Messages par Bluetooth. Coupé : les contacts et les clés restent.", radioBle) { on ->
                            radioBle = on
                            prefs.edit().putBoolean("radio_ble", on).apply()
                            BleMeshManager.instance?.setBleEnabled(on)
                        }
                        SettingSwitch("Scan pendant les envois", "À couper si les envois échouent sur ce téléphone.", scanSend) { on ->
                            scanSend = on
                            prefs.edit().putBoolean(BleMeshManager.PREF_SCAN_SEND, on).apply()
                        }
                        SettingSwitch(
                            "Wi-Fi Aware",
                            if (awareSupported) "Gros fichiers entre voisins directs." else "Non supporté par ce téléphone.",
                            radioAware,
                            enabled = awareSupported
                        ) { on ->
                            radioAware = on
                            prefs.edit().putBoolean("radio_wifi_aware", on).apply()
                            MeshService.instance?.setWifiAwareEnabled(on)
                        }
                        SettingSwitch("Wi-Fi Direct", "Gros fichiers privés, avec une invitation à accepter sur l'autre téléphone.", radioDirect) { on ->
                            radioDirect = on
                            prefs.edit().putBoolean("radio_wifi_direct", on).apply()
                            MeshService.instance?.setWifiDirectEnabled(on)
                        }
                        SettingSwitch(
                            "Bluetooth Longue Portée",
                            if (longSupported) "Plus loin, mais plus lent." else "Non supporté par ce téléphone.",
                            radioLong,
                            enabled = longSupported
                        ) { on ->
                            radioLong = on
                            prefs.edit().putBoolean("radio_long_range", on).apply()
                        }
                        SettingSwitch("Live Push-to-Talk", "Maintenir le micro pour parler en direct. Un appui bref envoie un vocal.", liveOn) { on ->
                            liveOn = on
                            AppState.liveOn = on
                            prefs.edit().putBoolean("live_on", on).apply()
                        }
                    }

                    // 4. Réseau et relais
                    CategoryCard(4, "Réseau et relais", "Relais maximum, voisins, conservation.", open == 4, { toggle(4) }) {
                        RelaySettings(context, SGreen, SGrey, SWhite)
                    }

                    // 5. Contacts
                    CategoryCard(5, "Contacts", "Mon QR code, scanner, liste des contacts.", open == 5, { toggle(5) }) {
                        BigButton("Mon QR code", onClick = onShowQr)
                        Spacer(Modifier.height(8.dp))
                        BigButton("Scanner un QR code", onClick = onScan)
                        Text("Scanne le QR de quelqu'un pour l'ajouter, même s'il est loin.", color = SGrey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Label("MES CONTACTS (${contacts.size})")
                        if (contacts.isEmpty()) {
                            Text("Aucun contact pour l'instant.", color = SGrey, fontSize = 13.sp)
                        } else {
                            contacts.forEach { c ->
                                val online = peers.any { it.id == c.id }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) SGreen else SGrey.copy(alpha = 0.5f)))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("@${MeshState.nameOf(c.id)}", color = SWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(if (online) "À portée" else "Hors de portée", color = SGrey, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 6. Partage
                    CategoryCard(6, "Partage", "Envoyer l'application à un proche.", open == 6, { toggle(6) }) {
                        BigButton("Partager Samchat", onClick = onShare)
                        Text("Envoie l'application par Bluetooth ou Nearby Share, sans internet.", color = SGrey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }

                    // 7. Langue et assistance
                    CategoryCard(7, "Langue et assistance", "Langue, aide et informations.", open == 7, { toggle(7) }) {
                        Label("LANGUE")
                        Text("Français", color = SWhite, fontSize = 15.sp)
                        Text("D'autres langues viendront plus tard.", color = SGrey, fontSize = 12.sp)
                        Label("AIDE")
                        Text(
                            "Samchat fonctionne sans internet. Gardez le Bluetooth allumé et restez à quelques mètres de vos contacts. " +
                                "Pour ajouter un contact, scannez son QR code. Appuyez longtemps sur un message pour le supprimer. " +
                                "Un message devient « ✓✓ Lu » en vert quand l'autre personne l'a ouvert.",
                            color = SWhite,
                            fontSize = 13.sp
                        )
                        Label("À PROPOS")
                        Text("Samchat, version $version", color = SWhite, fontSize = 13.sp)
                    }

                    // 8. Diagnostic avancé
                    CategoryCard(8, "Diagnostic avancé", "Outils de debug et informations techniques.", open == 8, { toggle(8) }) {
                        Text("Version : $version", color = SWhite, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Installée depuis : $installer", color = SWhite, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Pairs à portée : ${peers.size}", color = SWhite, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(12.dp))
                        BigButton("Ouvrir les outils de debug", onClick = onDebug)
                    }
                }
            }
        }
    }
}
