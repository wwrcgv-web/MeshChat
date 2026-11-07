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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService

/** Langues proposées. Seul le français et l'anglais sont traduits pour l'instant. */
private val LANGUAGES = listOf(
    "fr" to "Français", "en" to "English", "mos" to "Mooré", "dyu" to "Dioula",
    "bm" to "Bambara", "wo" to "Wolof", "ff" to "Pulaar", "snk" to "Soninké",
    "song" to "Songhaï", "ha" to "Hausa", "mnk" to "Mandinka", "fon" to "Fon",
    "ee" to "Ewe", "yo" to "Yoruba", "ig" to "Igbo", "ak" to "Akan",
    "srr" to "Sérère", "tmh" to "Tamasheq", "ar" to "Arabe", "pt" to "Portugais", "es" to "Espagnol"
)

private val TEXTS: Map<String, Pair<String, String>> = mapOf(
    "settings" to ("Paramètres" to "Settings"),
    "profile" to ("Mon profil" to "My profile"),
    "profile_hint" to ("Photo, nom, identifiant et statut." to "Photo, name, ID and status."),
    "appearance" to ("Apparence" to "Appearance"),
    "appearance_hint" to ("Thème clair, sombre ou automatique." to "Light, dark or automatic theme."),
    "connection" to ("Connexion" to "Connection"),
    "connection_hint" to ("Service, Bluetooth, Wi-Fi et Push-to-Talk." to "Service, Bluetooth, Wi-Fi and Push-to-Talk."),
    "relay" to ("Réseau et relais" to "Network and relay"),
    "relay_hint" to ("Relais maximum, voisins, conservation." to "Max relays, neighbours, retention."),
    "contacts" to ("Contacts" to "Contacts"),
    "contacts_hint" to ("Mon QR code, scanner, liste des contacts." to "My QR code, scan, contact list."),
    "share" to ("Partage" to "Share"),
    "share_hint" to ("Envoyer l'application à un proche." to "Send the app to someone."),
    "help" to ("Langue et assistance" to "Language and help"),
    "help_hint" to ("Langue, aide et informations." to "Language, help and information."),
    "diag" to ("Diagnostic avancé" to "Advanced diagnostics"),
    "diag_hint" to ("Outils de debug et informations techniques." to "Debug tools and technical info."),
    "name" to ("NOM D'UTILISATEUR" to "USERNAME"),
    "save_name" to ("Enregistrer le nom" to "Save name"),
    "photo" to ("Changer la photo" to "Change photo"),
    "status" to ("STATUT (60 caractères)" to "STATUS (60 characters)"),
    "save_status" to ("Enregistrer le statut" to "Save status"),
    "id" to ("IDENTIFIANT" to "ID"),
    "lang" to ("LANGUE" to "LANGUAGE"),
    "lang_note" to (
        "Les autres langues seront traduites une à une. Pour l'instant, cet écran reste en français." to
            "Other languages are translated one by one. For now, this screen stays in French."
        ),
    "help_body" to (
        "Samchat fonctionne sans internet. Gardez le Bluetooth allumé et restez à quelques mètres de vos contacts. " +
            "Pour ajouter un contact, scannez son QR code. Appuyez longtemps sur un message pour le supprimer." to
            "Samchat works without internet. Keep Bluetooth on and stay a few metres from your contacts. " +
            "To add a contact, scan their QR code. Long-press a message to delete it."
        ),
    "about" to ("À PROPOS" to "ABOUT"),
    "scan" to ("Scanner un QR code" to "Scan a QR code"),
    "myqr" to ("Mon QR code" to "My QR code"),
    "share_btn" to ("Partager Samchat" to "Share Samchat"),
    "debug_btn" to ("Ouvrir les outils de debug" to "Open debug tools"),
    "inst" to ("Installée depuis" to "Installed from"),
    "peers" to ("Pairs à portée" to "Peers in range"),
    "bg" to ("Service en arrière-plan" to "Background service"),
    "bg_note" to ("S'arrête à la fermeture de l'app si désactivé." to "Stops when the app closes if turned off."),
    "ble" to ("Bluetooth" to "Bluetooth"),
    "ble_note" to ("Messages par Bluetooth." to "Messages over Bluetooth."),
    "scan_send" to ("Scan pendant les envois" to "Scan during sends"),
    "scan_send_note" to ("À couper si les envois échouent." to "Turn off if sends fail."),
    "live" to ("Parole en direct" to "Live talk"),
    "live_note" to ("Maintenir le micro pour parler. Un appui bref envoie un vocal." to "Hold the mic to talk. A short tap sends a voice note.")
)

private fun tr(lang: String, key: String): String {
    val pair = TEXTS[key] ?: return key
    return if (lang == "en") pair.second else pair.first
}

@Composable
private fun HubIcon(kind: Int) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val c = SamchatColors.Green
        val st = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (kind) {
            0 -> {
                drawCircle(c, w * 0.18f, p(0.5f, 0.32f), style = st)
                drawArc(c, 0f, 180f, false, p(0.2f, 0.55f), Size(w * 0.6f, h * 0.6f), style = st)
            }
            1 -> {
                drawCircle(c, w * 0.4f, p(0.5f, 0.5f), style = st)
                drawArc(c, -90f, 180f, true, p(0.1f, 0.1f), Size(w * 0.8f, h * 0.8f))
            }
            2 -> {
                for (r in listOf(0.2f, 0.35f, 0.5f)) {
                    drawArc(c, 225f, 90f, false, Offset(w * (0.5f - r), h * (0.85f - r)), Size(w * 2 * r, w * 2 * r), style = st)
                }
                drawCircle(c, w * 0.06f, p(0.5f, 0.85f))
            }
            3 -> {
                drawLine(c, p(0.5f, 0.15f), p(0.15f, 0.8f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.15f, 0.8f), p(0.85f, 0.8f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.85f, 0.8f), p(0.5f, 0.15f), strokeWidth = st.width, cap = StrokeCap.Round)
                listOf(p(0.5f, 0.15f), p(0.15f, 0.8f), p(0.85f, 0.8f)).forEach { drawCircle(c, w * 0.1f, it) }
            }
            4 -> {
                drawCircle(c, w * 0.15f, p(0.38f, 0.3f), style = st)
                drawArc(c, 0f, 180f, false, p(0.1f, 0.55f), Size(w * 0.56f, h * 0.56f), style = st)
                drawCircle(c, w * 0.12f, p(0.72f, 0.36f), style = st)
                drawArc(c, 0f, 140f, false, p(0.56f, 0.6f), Size(w * 0.38f, h * 0.38f), style = st)
            }
            5 -> {
                drawLine(c, p(0.5f, 0.68f), p(0.5f, 0.14f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.5f, 0.14f), p(0.3f, 0.34f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.5f, 0.14f), p(0.7f, 0.34f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.2f, 0.5f), p(0.2f, 0.88f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.2f, 0.88f), p(0.8f, 0.88f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawLine(c, p(0.8f, 0.88f), p(0.8f, 0.5f), strokeWidth = st.width, cap = StrokeCap.Round)
            }
            6 -> {
                drawCircle(c, w * 0.42f, p(0.5f, 0.5f), style = st)
                drawLine(c, p(0.08f, 0.5f), p(0.92f, 0.5f), strokeWidth = st.width, cap = StrokeCap.Round)
                drawOval(c, p(0.3f, 0.08f), Size(w * 0.4f, h * 0.84f), style = st)
            }
            else -> {
                listOf(0.25f to 0.3f, 0.5f to 0.7f, 0.75f to 0.45f).forEach { (y, x) ->
                    drawLine(c, p(0.12f, y), p(0.88f, y), strokeWidth = st.width, cap = StrokeCap.Round)
                    drawCircle(SamchatColors.Card, w * 0.12f, p(x, y))
                    drawCircle(c, w * 0.1f, p(x, y), style = st)
                }
            }
        }
    }
}

@Composable
private fun HubChevron() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.12f
        drawLine(SamchatColors.Grey, Offset(w * 0.32f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(SamchatColors.Grey, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.32f, h * 0.85f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun NavRow(kind: Int, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = SamchatColors.Card
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(SamchatColors.Green.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { HubIcon(kind) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = SamchatColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(hint, color = SamchatColors.Grey, fontSize = 14.sp)
            }
            HubChevron()
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
            Text("←", color = SamchatColors.White, fontSize = 26.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = SamchatColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HubLabel(text: String) {
    Text(text, color = SamchatColors.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun HubButton(text: String, selected: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SamchatColors.GreenSoft else SamchatColors.Field,
            contentColor = SamchatColors.White,
            disabledContainerColor = SamchatColors.Field,
            disabledContentColor = SamchatColors.Grey
        )
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun hubFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = SamchatColors.Field,
    unfocusedContainerColor = SamchatColors.Field,
    focusedTextColor = SamchatColors.White,
    unfocusedTextColor = SamchatColors.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = SamchatColors.Green
)

@Composable
private fun HubSwitch(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SamchatColors.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(note, color = SamchatColors.Grey, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = SamchatColors.Green,
                checkedThumbColor = SamchatColors.White,
                uncheckedTrackColor = SamchatColors.Field,
                uncheckedThumbColor = SamchatColors.Grey
            )
        )
    }
}

@Composable
private fun BigAvatar(bitmap: Bitmap?, letter: String, size: Int) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(SamchatColors.Field), contentAlignment = Alignment.Center) {
            Text(letter, color = SamchatColors.White, fontSize = (size * 0.38).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Réglages : liste à icônes et chevrons, puis une page par rubrique.
 * Même paramètres que l'ancien SettingsScreen : MainActivity n'a qu'à appeler SettingsHub à la place.
 */
@Composable
fun SettingsHub(
    context: Context,
    myName: String,
    myAvatar: Bitmap?,
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
    onBack: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    var page by remember { mutableStateOf(0) }
    var lang by remember { mutableStateOf(prefs.getString("app_lang", "fr") ?: "fr") }
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

    BackHandler(enabled = page != 0) { page = 0 }

    val pageTitle = when (page) {
        1 -> tr(lang, "profile")
        2 -> tr(lang, "appearance")
        3 -> tr(lang, "connection")
        4 -> tr(lang, "relay")
        5 -> tr(lang, "contacts")
        6 -> tr(lang, "share")
        7 -> tr(lang, "help")
        8 -> tr(lang, "diag")
        else -> tr(lang, "settings")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(Modifier.fillMaxSize()) {
            PageTitle(pageTitle) { if (page == 0) onBack() else page = 0 }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp)
            ) {
                when (page) {
                    0 -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { page = 1 }.padding(horizontal = 4.dp, vertical = 12.dp)
                        ) {
                            BigAvatar(myAvatar, myName.take(1).uppercase().ifEmpty { "?" }, 64)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(myName.ifBlank { "Ton nom" }, color = SamchatColors.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    ProfileStore.myStatus(context).ifBlank { if (lang == "en") "Add a status" else "Ajoute un statut" },
                                    color = SamchatColors.Grey,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        NavRow(0, tr(lang, "profile"), tr(lang, "profile_hint")) { page = 1 }
                        NavRow(1, tr(lang, "appearance"), tr(lang, "appearance_hint")) { page = 2 }
                        NavRow(2, tr(lang, "connection"), tr(lang, "connection_hint")) { page = 3 }
                        NavRow(3, tr(lang, "relay"), tr(lang, "relay_hint")) { page = 4 }
                        NavRow(4, tr(lang, "contacts"), tr(lang, "contacts_hint")) { page = 5 }
                        NavRow(5, tr(lang, "share"), tr(lang, "share_hint")) { page = 6 }
                        NavRow(6, tr(lang, "help"), tr(lang, "help_hint")) { page = 7 }
                        NavRow(7, tr(lang, "diag"), tr(lang, "diag_hint")) { page = 8 }
                    }

                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BigAvatar(myAvatar, myName.take(1).uppercase().ifEmpty { "?" }, 72)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) { HubButton(tr(lang, "photo"), onClick = onChangePhoto) }
                        }
                        HubLabel(tr(lang, "name"))
                        TextField(
                            value = nameInput,
                            onValueChange = { nameInput = it.take(20) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = hubFieldColors(),
                            textStyle = TextStyle(fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        HubButton(tr(lang, "save_name"), enabled = nameInput.isNotBlank()) { onSaveName(nameInput) }
                        HubLabel(tr(lang, "id"))
                        Text(BleMeshManager.instance?.myIdHex?.take(16) ?: "—", color = SamchatColors.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        HubLabel(tr(lang, "status"))
                        TextField(
                            value = statusInput,
                            onValueChange = { statusInput = it.take(ProfileStore.STATUS_MAX_CHARS) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = hubFieldColors(),
                            textStyle = TextStyle(fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        HubButton(tr(lang, "save_status")) { onSaveStatus(statusInput) }
                    }

                    2 -> {
                        listOf("système" to "Automatique", "clair" to "Clair", "sombre" to "Sombre").forEach { (key, label) ->
                            HubButton(label, selected = theme == key) { onTheme(key) }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    3 -> {
                        HubSwitch(tr(lang, "bg"), tr(lang, "bg_note"), runBg) {
                            runBg = it; AppState.runInBackground = it
                        }
                        HubSwitch(tr(lang, "ble"), tr(lang, "ble_note"), radioBle) { on ->
                            radioBle = on; prefs.edit().putBoolean("radio_ble", on).apply()
                            BleMeshManager.instance?.setBleEnabled(on)
                        }
                        HubSwitch(tr(lang, "scan_send"), tr(lang, "scan_send_note"), scanSend) { on ->
                            scanSend = on; prefs.edit().putBoolean(BleMeshManager.PREF_SCAN_SEND, on).apply()
                        }
                        HubSwitch("Wi-Fi Aware", if (awareSupported) "Gros fichiers entre voisins." else "Non supporté.", radioAware, awareSupported) { on ->
                            radioAware = on; prefs.edit().putBoolean("radio_wifi_aware", on).apply()
                            MeshService.instance?.setWifiAwareEnabled(on)
                        }
                        HubSwitch("Wi-Fi Direct", "Gros fichiers privés.", radioDirect) { on ->
                            radioDirect = on; prefs.edit().putBoolean("radio_wifi_direct", on).apply()
                            MeshService.instance?.setWifiDirectEnabled(on)
                        }
                        HubSwitch("Bluetooth longue portée", if (longSupported) "Plus loin, mais plus lent." else "Non supporté.", radioLong, longSupported) { on ->
                            radioLong = on; prefs.edit().putBoolean("radio_long_range", on).apply()
                        }
                        HubSwitch(tr(lang, "live"), tr(lang, "live_note"), liveOn) { on ->
                            liveOn = on; AppState.liveOn = on; prefs.edit().putBoolean("live_on", on).apply()
                        }
                    }

                    4 -> {
                        RelaySettings(context, SamchatColors.Green, SamchatColors.Grey, SamchatColors.White)
                    }

                    5 -> {
                        HubButton(tr(lang, "myqr"), onClick = onShowQr)
                        Spacer(Modifier.height(10.dp))
                        HubButton(tr(lang, "scan"), onClick = onScan)
                        HubLabel("${if (lang == "en") "MY CONTACTS" else "MES CONTACTS"} (${contacts.size})")
                        if (contacts.isEmpty()) {
                            Text(if (lang == "en") "No contact yet." else "Aucun contact pour l'instant.", color = SamchatColors.Grey, fontSize = 15.sp)
                        } else {
                            contacts.forEach { c ->
                                val online = peers.any { it.id == c.id }
                                Text(
                                    "@${MeshState.nameOf(c.id)}  ${if (online) "● à portée" else "○ hors de portée"}",
                                    color = if (online) SamchatColors.Green else SamchatColors.Grey,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    6 -> {
                        HubButton(tr(lang, "share_btn"), onClick = onShare)
                    }

                    7 -> {
                        HubLabel(tr(lang, "lang"))
                        LANGUAGES.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { (code, name) ->
                                    val selected = lang == code
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 52.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (selected) SamchatColors.GreenSoft else SamchatColors.Field)
                                            .clickable {
                                                lang = code
                                                prefs.edit().putString("app_lang", code).apply()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(name, color = SamchatColors.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(tr(lang, "lang_note"), color = SamchatColors.Grey, fontSize = 13.sp)
                        HubLabel(if (lang == "en") "HELP" else "AIDE")
                        Text(tr(lang, "help_body"), color = SamchatColors.White, fontSize = 15.sp)
                        HubLabel(tr(lang, "about"))
                        Text("Samchat, version $version", color = SamchatColors.White, fontSize = 15.sp)
                    }

                    8 -> {
                        Text("${tr(lang, "inst")} : $installer", color = SamchatColors.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("${tr(lang, "peers")} : ${peers.size}", color = SamchatColors.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(12.dp))
                        HubButton(tr(lang, "debug_btn"), selected = false, onClick = onDebug)
                    }
                }
            }
        }
    }
}
