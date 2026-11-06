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

private const val ALL_CHAT = "public"

@Composable
private fun RowAvatar(bitmap: Bitmap?, letter: String, bg: Color, ink: Color) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(letter, color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
    green: Color,
    ink: Color,
    grey: Color,
    field: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box {
            RowAvatar(avatar, letter, field, ink)
            if (online) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(green)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(preview, color = grey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(time, color = grey, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, green: Color, ink: Color, grey: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) green.copy(alpha = 0.22f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) { Text(icon, fontSize = 20.sp) }
        Text(label, color = if (selected) ink else grey, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * Écran d'accueil façon WhatsApp : recherche, filtres, liste des discussions, barre du bas.
 * Il ne contient aucune logique de messagerie : il lit les mêmes listes et appelle les fonctions qu'on lui donne.
 */
@Composable
fun ChatListScreen(
    context: Context,
    contacts: List<MeshPeer>,
    peers: List<MeshPeer>,
    bubbles: List<ChatBubble>,
    profileTick: Int,
    serviceOn: Boolean,
    bg: Color,
    field: Color,
    ink: Color,
    grey: Color,
    green: Color,
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

    Surface(modifier = Modifier.fillMaxSize(), color = bg) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Barre du haut
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = "Logo Samchat",
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Samchat", color = green, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        if (serviceOn) "● actif" else "○ arrêté",
                        color = if (serviceOn) green else grey,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = onSettings, modifier = Modifier.height(48.dp)) {
                        Text("⋮", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (tab == 0) {
                    // Recherche
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        placeholder = { Text("Rechercher une discussion", color = grey) },
                        leadingIcon = { Text("🔍", fontSize = 16.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = field,
                            unfocusedContainerColor = field,
                            focusedTextColor = ink,
                            unfocusedTextColor = ink,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = green
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    // Filtres
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Toutes", "À portée").forEachIndexed { i, label ->
                            val on = filter == i
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (on) green.copy(alpha = 0.25f) else field)
                                    .clickable { filter = i }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(label, color = if (on) ink else grey, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (showAll) {
                            item {
                                val last = bubbles.lastOrNull { it.chat == ALL_CHAT }
                                ChatRow(
                                    "Tous", previewOf(last), last?.time?.takeLast(5) ?: "",
                                    online = peers.isNotEmpty(), avatar = null, letter = "👥",
                                    green = green, ink = ink, grey = grey, field = field
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
                                letter = name.take(1).uppercase(),
                                green = green, ink = ink, grey = grey, field = field
                            ) { onOpen(c.id) }
                        }
                        if (contacts.isEmpty()) {
                            item {
                                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Aucun contact pour l'instant", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("Appuie sur le bouton vert + et scanne le QR code d'un ami.", color = grey, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Onglet Contacts
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        Button(
                            onClick = onShowQr,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White)
                        ) { Text("Mon QR code", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onScan,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = field, contentColor = ink)
                        ) { Text("Scanner un QR code", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        Text("MES CONTACTS (${contacts.size})", color = grey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(contacts, key = { it.id }) { c ->
                                val avatar = remember(c.id, profileTick) { ProfileStore.avatarBitmapOf(context, c.id) }
                                val online = peers.any { it.id == c.id }
                                val name = MeshState.nameOf(c.id)
                                ChatRow(
                                    "@$name", if (online) "À portée" else "Hors de portée", "",
                                    online = online, avatar = avatar, letter = name.take(1).uppercase(),
                                    green = green, ink = ink, grey = grey, field = field
                                ) { onOpen(c.id) }
                            }
                        }
                    }
                }

                // Barre du bas
                Row(
                    Modifier.fillMaxWidth().background(field).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavItem("💬", "Discussions", tab == 0, green, ink, grey) { tab = 0 }
                    NavItem("👥", "Contacts", tab == 1, green, ink, grey) { tab = 1 }
                    NavItem("⚙", "Réglages", false, green, ink, grey) { onSettings() }
                }
            }

            // Bouton vert « + » : ajouter un contact
            if (tab == 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 92.dp)
                        .size(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(green)
                        .clickable(onClick = onScan),
                    contentAlignment = Alignment.Center
                ) { Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
