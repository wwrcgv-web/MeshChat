package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshchat.ble.MeshPeer
import com.example.meshchat.service.RelayService

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

/** Écran d'accueil : logo, ⋮ (Réglages) en haut à droite, sélecteur de mode, liste, barre du bas à deux entrées. */
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
    var internetOn by remember { mutableStateOf(RelayClient.isEnabled(context)) }

    fun previewOf(b: ChatBubble?): String = when {
        b == null -> t("no_message")
        b.audio != null -> (if (b.mine) "${t("you")} : " else "") + "🎤 ${t("voice")}"
        b.image != null || b.imagePath != null -> (if (b.mine) "${t("you")} : " else "") + "📷 ${t("photo")}"
        else -> (if (b.mine) "${t("you")} : " else "") + b.text
    }

    val ordered = contacts.sortedByDescending { c -> bubbles.indexOfLast { it.chat == c.id } }

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "Samchat",
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Samchat", color = SamchatColors.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (serviceOn) "● ${t("active")}" else "○ ${t("stopped")}",
                    color = if (serviceOn) SamchatColors.Green else SamchatColors.Grey,
                    fontSize = 13.sp
                )
                if (tab == 1) {
                    IconButton(onClick = onShowQr, modifier = Modifier.size(52.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_qr),
                            contentDescription = t("my_qr"),
                            tint = SamchatColors.Green,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                TextButton(onClick = onSettings, modifier = Modifier.size(52.dp)) {
                    Text("⋮", color = SamchatColors.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (tab == 0) {
                ModeSelector(internetOn = internetOn) { on ->
                    internetOn = on
                    RelayClient.setEnabled(context, on)
                    // Le service de relève suit le mode : inutile de sonder quand Internet est coupé
                    if (on) RelayService.start(context) else RelayService.stop(context)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        val last = bubbles.lastOrNull { it.chat == ALL_CHAT }
                        ChatRow(t("all_group"), previewOf(last), last?.time?.takeLast(5) ?: "",
                            online = peers.isNotEmpty(), avatar = null, letter = "👥") { onOpen(ALL_CHAT) }
                    }
                    items(ordered, key = { it.id }) { c ->
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
                                Text(t("no_contact"), color = SamchatColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(t("go_contacts"), color = SamchatColors.Grey, fontSize = 15.sp)
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    NetworkScreen(
                        context = context,
                        contacts = contacts,
                        peers = peers,
                        profileTick = profileTick,
                        onOpen = onOpen,
                        onShowQr = onShowQr,
                        onScan = onScan
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().background(SamchatColors.Card).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavItem("💬", t("discussions"), tab == 0) { tab = 0 }
                NavItem("👥", t("contacts"), tab == 1) { tab = 1 }
            }
        }
    }
}
