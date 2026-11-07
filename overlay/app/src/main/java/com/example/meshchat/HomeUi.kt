package com.example.meshchat

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.MeshService

/** Couleurs de Samchat, en un seul endroit. Sombre, vert et blanc. */
object SamchatColors {
    val Background = Color(0xFF0A0C0B)
    val Card = Color(0xFF151916)
    val Field = Color(0xFF1F2521)
    val Green = Color(0xFF2FBF71)
    val GreenSoft = Color(0xFF1F8A4F)
    val White = Color.White
    val Grey = Color(0xFFA5ADA8)
    val Danger = Color(0xFFDC2626)
}

private const val ALL_CHAT = "public"

@Composable
private fun RowAvatar(bitmap: Bitmap?, letter: String, size: Int) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(SamchatColors.Field),
            contentAlignment = Alignment.Center
        ) {
            Text(letter, color = SamchatColors.White, fontSize = (size * 0.4).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChatRow(
    title: String,
    preview: String,
    time: String,
    online: Boolean,
    avatar: Bitmap?,
    letter: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box {
            RowAvatar(avatar, letter, 56)
            if (online) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(SamchatColors.Green)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SamchatColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(preview, color = SamchatColors.Grey, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(time, color = SamchatColors.Grey, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) SamchatColors.Green.copy(alpha = 0.22f) else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) { Text(icon, fontSize = 24.sp) }
        Text(
            label,
            color = if (selected) SamchatColors.White else SamchatColors.Grey,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SettingsCard(
    icon: String,
    title: String,
    hint: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = SamchatColors.Card
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(SamchatColors.Green.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Text(icon, fontSize = 20.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = SamchatColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(hint, color = SamchatColors.Grey, fontSize = 13.sp)
                }
                Text(if (open) "▲" else "▼", color = SamchatColors.Grey, fontSize = 14.sp)
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SamchatColors.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
private fun BigButton(text: String, selected: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun Label(text: String) {
    Text(text, color = SamchatColors.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = SamchatColors.Field,
    unfocusedContainerColor = SamchatColors.Field,
    focusedTextColor = SamchatColors.White,
    unfocusedTextColor = SamchatColors.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = SamchatColors.Green
)

/** Écran Réglages : 8 rubriques repliables, ouvert par le ⋮ en haut à droite de l'accueil. */
@Composable
fun SettingsScreen(
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

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)
            ) {
                TextButton(onClick = onBack, modifier = Modifier.height(52.dp)) {
                    Text("←", color = SamchatColors.White, fontSize = 26.sp)
                }
                Text("Réglages", color = SamchatColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 28.dp)
            ) {
                SettingsCard("👤", "Mon profil", "Photo, nom, identifiant et statut.", open == 1, { toggle(1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = myAvatar
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape))
                        } else {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(SamchatColors.Field), contentAlignment = Alignment.Center) {
                                Text(myName.take(1).uppercase().ifEmpty { "?" }, color = SamchatColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { BigButton("Changer la photo", onClick = onChangePhoto) }
                    }
                    Label("NOM")
                    TextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(20) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        textStyle = TextStyle(fontSize = 16.sp),
                        placeholder = { Text("ton nom", color = SamchatColors.Grey) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    BigButton("Enregistrer le nom", enabled = nameInput.isNotBlank()) { onSaveName(nameInput) }
                    Label("IDENTIFIANT")
                    Text(BleMeshManager.instance?.myIdHex?.take(16) ?: "—", color = SamchatColors.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Label("STATUT (60 caractères)")
                    TextField(
                        value = statusInput,
                        onValueChange = { statusInput = it.take(ProfileStore.STATUS_MAX_CHARS) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        textStyle = TextStyle(fontSize = 16.sp),
                        placeholder = { Text("ton statut", color = SamchatColors.Grey) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    BigButton("Enregistrer le statut") { onSaveStatus(statusInput) }
                }

                SettingsCard("🎨", "Apparence", "Clair, sombre ou automatique.", open == 2, { toggle(2) }) {
                    listOf("système" to "Automatique", "clair" to "Clair", "sombre" to "Sombre").forEach { (key, label) ->
                        BigButton(label, selected = theme == key) { onTheme(key) }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                SettingsCard("📡", "Connexion", "Service, Bluetooth, Wi-Fi, parole en direct.", open == 3, { toggle(3) }) {
                    SettingSwitch("Service en arrière-plan", "S'arrête à la fermeture de l'app si désactivé.", runBg) {
                        runBg = it; AppState.runInBackground = it
                    }
                    SettingSwitch("Bluetooth", "Messages par Bluetooth.", radioBle) { on ->
                        radioBle = on; prefs.edit().putBoolean("radio_ble", on).apply()
                        BleMeshManager.instance?.setBleEnabled(on)
                    }
                    SettingSwitch("Scan pendant les envois", "À couper si les envois échouent.", scanSend) { on ->
                        scanSend = on; prefs.edit().putBoolean(BleMeshManager.PREF_SCAN_SEND, on).apply()
                    }
                    SettingSwitch("Wi-Fi Aware", if (awareSupported) "Gros fichiers entre voisins." else "Non supporté.", radioAware, awareSupported) { on ->
                        radioAware = on; prefs.edit().putBoolean("radio_wifi_aware", on).apply()
                        MeshService.instance?.setWifiAwareEnabled(on)
                    }
                    SettingSwitch("Wi-Fi Direct", "Gros fichiers privés.", radioDirect) { on ->
                        radioDirect = on; prefs.edit().putBoolean("radio_wifi_direct", on).apply()
                        MeshService.instance?.setWifiDirectEnabled(on)
                    }
                    SettingSwitch("Bluetooth longue portée", if (longSupported) "Plus loin, mais plus lent." else "Non supporté.", radioLong, longSupported) { on ->
                        radioLong = on; prefs.edit().putBoolean("radio_long_range", on).apply()
                    }
                    SettingSwitch("Parole en direct", "Maintenir le micro pour parler. Un appui bref envoie un vocal.", liveOn) { on ->
                        liveOn = on; AppState.liveOn = on; prefs.edit().putBoolean("live_on", on).apply()
                    }
                }

                SettingsCard("🔁", "Réseau et relais", "Relais, voisins, conservation.", open == 4, { toggle(4) }) {
                    RelaySettings(context, SamchatColors.Green, SamchatColors.Grey, SamchatColors.White)
                }

                SettingsCard("👥", "Contacts", "Mon QR code, scanner, liste.", open == 5, { toggle(5) }) {
                    BigButton("Mon QR code", onClick = onShowQr)
                    Spacer(Modifier.height(8.dp))
                    BigButton("Scanner un QR code", onClick = onScan)
                    Label("MES CONTACTS (${contacts.size})")
                    if (contacts.isEmpty()) {
                        Text("Aucun contact pour l'instant.", color = SamchatColors.Grey, fontSize = 14.sp)
                    } else {
                        contacts.forEach { c ->
                            val online = peers.any { it.id == c.id }
                            Text(
                                "@${MeshState.nameOf(c.id)}  ${if (online) "● à portée" else "○ hors de portée"}",
                                color = if (online) SamchatColors.Green else SamchatColors.Grey,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                SettingsCard("📤", "Partage", "Envoyer l'application à un proche.", open == 6, { toggle(6) }) {
                    BigButton("Partager Samchat", onClick = onShare)
                }

                SettingsCard("🌍", "Langue et aide", "Langue, aide, informations.", open == 7, { toggle(7) }) {
                    Label("LANGUE")
                    Text("Français", color = SamchatColors.White, fontSize = 16.sp)
                    Label("AIDE")
                    Text(
                        "Samchat fonctionne sans internet. Gardez le Bluetooth allumé et restez à quelques mètres de vos contacts. " +
                            "Pour ajouter un contact, scannez son QR code. Appuyez longtemps sur un message pour le supprimer.",
                        color = SamchatColors.White,
                        fontSize = 14.sp
                    )
                    Label("À PROPOS")
                    Text("Samchat, version $version", color = SamchatColors.White, fontSize = 14.sp)
                }

                SettingsCard("🛠", "Diagnostic avancé", "Outils de debug et informations techniques.", open == 8, { toggle(8) }) {
                    Text("Installée depuis : $installer", color = SamchatColors.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text("Pairs à portée : ${peers.size}", color = SamchatColors.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    BigButton("Ouvrir les outils de debug", selected = false, onClick = onDebug)
                }
            }
        }
    }
}

/** Écran d'accueil : logo, ⋮ (Réglages) en haut à droite, recherche, filtres, liste, barre du bas à deux entrées. */
@Composable
fun ChatListScreen(
    context: Context,
    contacts: List<MeshPeer>,
    peers: List<MeshPeer>,
    bubbles: List<ChatBubble>,
    profileTick: Int,
    serviceOn: Boolean,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onShowQr: () -> Unit,
    onScan: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    fun previewOf(b: ChatBubble?): String = when {
        b == null -> "Aucun message"
        b.audio != null -> (if (b.mine) "Moi : " else "") + "🎤 Vocal"
        b.image != null || b.imagePath != null -> (if (b.mine) "Moi : " else "") + "📷 Photo"
        else -> (if (b.mine) "Moi : " else "") + b.text
    }

    val q = query.trim().lowercase()
    val ordered = contacts.sortedByDescending { c -> bubbles.indexOfLast { it.chat == c.id } }
    val shown = ordered.filter { c ->
        val name = MeshState.nameOf(c.id)
        (q.isEmpty() || name.lowercase().contains(q)) && (filter == 0 || peers.any { it.id == c.id })
    }
    val showAll = (q.isEmpty() || "tous".contains(q)) && filter == 0

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(Modifier.fillMaxSize()) {
            // Barre du haut : logo, nom, état, et ⋮ (Réglages) à droite
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "Logo Samchat",
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Samchat", color = SamchatColors.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (serviceOn) "● actif" else "○ arrêté",
                    color = if (serviceOn) SamchatColors.Green else SamchatColors.Grey,
                    fontSize = 13.sp
                )
                TextButton(onClick = onSettings, modifier = Modifier.size(52.dp)) {
                    Text("⋮", color = SamchatColors.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (tab == 0) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Rechercher", color = SamchatColors.Grey) },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Toutes", "À portée").forEachIndexed { i, label ->
                        val on = filter == i
                        Box(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (on) SamchatColors.GreenSoft else SamchatColors.Field)
                                .clickable { filter = i }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = SamchatColors.White, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (showAll) {
                        item {
                            val last = bubbles.lastOrNull { it.chat == ALL_CHAT }
                            ChatRow("Tous", previewOf(last), last?.time?.takeLast(5) ?: "",
                                online = peers.isNotEmpty(), avatar = null, letter = "👥") { onOpen(ALL_CHAT) }
                        }
                    }
                    items(shown, key = { it.id }) { c ->
                        val last = bubbles.lastOrNull { it.chat == c.id }
                        val avatar = remember(c.id, profileTick) { ProfileStore.avatarBitmapOf(context, c.id) }
                        val name = MeshState.nameOf(c.id)
                        ChatRow("@$name", previewOf(last), last?.time?.takeLast(5) ?: "",
                            online = peers.any { it.id == c.id }, avatar = avatar,
                            letter = name.take(1).uppercase()) { onOpen(c.id) }
                    }
                    if (contacts.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Aucun contact pour l'instant", color = SamchatColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("Va dans Contacts pour scanner un ami.", color = SamchatColors.Grey, fontSize = 15.sp)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    BigButton("Mon QR code", onClick = onShowQr)
                    Spacer(Modifier.height(10.dp))
                    BigButton("Scanner un QR code", onClick = onScan)
                    Spacer(Modifier.height(14.dp))
                    Text("MES CONTACTS (${contacts.size})", color = SamchatColors.Grey, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(contacts, key = { it.id }) { c ->
                            val avatar = remember(c.id, profileTick) { ProfileStore.avatarBitmapOf(context, c.id) }
                            val online = peers.any { it.id == c.id }
                            val name = MeshState.nameOf(c.id)
                            ChatRow("@$name", if (online) "À portée" else "Hors de portée", "",
                                online = online, avatar = avatar, letter = name.take(1).uppercase()) { onOpen(c.id) }
                        }
                    }
                }
            }

            // Barre du bas : deux entrées seulement
            Row(
                Modifier.fillMaxWidth().background(SamchatColors.Card).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavItem("💬", "Discussions", tab == 0) { tab = 0 }
                NavItem("👥", "Contacts", tab == 1) { tab = 1 }
            }
        }
    }
}
