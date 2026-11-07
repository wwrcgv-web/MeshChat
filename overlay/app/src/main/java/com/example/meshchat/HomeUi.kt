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

/** Avatar rond : photo si connue, sinon une lettre sur fond gris. */
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

/** Une discussion dans la liste : grand avatar, nom, aperçu du dernier message, heure, point vert si à portée. */
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
            Text(
                title,
                color = SamchatColors.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                preview,
                color = SamchatColors.Grey,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(time, color = SamchatColors.Grey, fontSize = 13.sp)
        }
    }
}

/** Bouton de la barre du bas : grande icône et libellé court. */
@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp)
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
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Écran d'accueil : recherche, filtres, liste des discussions, barre du bas.
 * Il ne contient aucune logique de messagerie : il lit les listes et appelle les fonctions reçues.
 */
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
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Barre du haut : logo, nom de l'app, état du service
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = "Logo Samchat",
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Samchat",
                        color = SamchatColors.Green,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (serviceOn) "● actif" else "○ arrêté",
                        color = if (serviceOn) SamchatColors.Green else SamchatColors.Grey,
                        fontSize = 13.sp
                    )
                }

                if (tab == 0) {
                    // Recherche
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        placeholder = { Text("Rechercher", color = SamchatColors.Grey) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SamchatColors.Field,
                            unfocusedContainerColor = SamchatColors.Field,
                            focusedTextColor = SamchatColors.White,
                            unfocusedTextColor = SamchatColors.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = SamchatColors.Green
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    // Filtres : Toutes / À portée
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
                                Text(
                                    label,
                                    color = SamchatColors.White,
                                    fontSize = 15.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (showAll) {
                            item {
                                val last = bubbles.lastOrNull { it.chat == ALL_CHAT }
                                ChatRow(
                                    "Tous", previewOf(last), last?.time?.takeLast(5) ?: "",
                                    online = peers.isNotEmpty(), avatar = null, letter = "👥"
                                ) { onOpen(ALL_CHAT) }
                            }
                        }
                        items(shown, key = { it.id }) { c ->
                            val last = bubbles.lastOrNull { it.chat == c.id }
                            val avatar = remember(c.id, profileTick) { ProfileStore.avatarBitmapOf(context, c.id) }
                            val name = MeshState.nameOf(c.id)
                            ChatRow(
                                "@$name", previewOf(last), last?.time?.takeLast(5) ?: "",
                                online = peers.any { it.id == c.id }, avatar = avatar,
                                letter = name.take(1).uppercase()
                            ) { onOpen(c.id) }
                        }
                        if (contacts.isEmpty()) {
                            item {
                                Column(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Aucun contact pour l'instant", color = SamchatColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("Appuie sur le bouton vert + pour ajouter un ami.", color = SamchatColors.Grey, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Onglet Contacts : QR et scan, grands boutons
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        Button(
                            onClick = onShowQr,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SamchatColors.GreenSoft, contentColor = SamchatColors.White)
                        ) { Text("Mon QR code", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onScan,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SamchatColors.Field, contentColor = SamchatColors.White)
                        ) { Text("Scanner un QR code", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(14.dp))
                        Text("MES CONTACTS (${contacts.size})", color = SamchatColors.Grey, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(contacts, key = { it.id }) { c ->
                                val avatar = remember(c.id, profileTick) { ProfileStore.avatarBitmapOf(context, c.id) }
                                val online = peers.any { it.id == c.id }
                                val name = MeshState.nameOf(c.id)
                                ChatRow(
                                    "@$name",
                                    if (online) "À portée" else "Hors de portée",
                                    "",
                                    online = online,
                                    avatar = avatar,
                                    letter = name.take(1).uppercase()
                                ) { onOpen(c.id) }
                            }
                        }
                    }
                }

                // Barre du bas : trois grandes entrées
                Row(
                    Modifier.fillMaxWidth().background(SamchatColors.Card).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavItem("💬", "Discussions", tab == 0) { tab = 0 }
                    NavItem("👥", "Contacts", tab == 1) { tab = 1 }
                    NavItem("⚙", "Réglages", false) { onSettings() }
                }
            }

            // Bouton vert « + » : ajouter un contact (grand, facile à toucher)
            if (tab == 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 100.dp)
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SamchatColors.Green)
                        .clickable(onClick = onScan),
                    contentAlignment = Alignment.Center
                ) { Text("+", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
