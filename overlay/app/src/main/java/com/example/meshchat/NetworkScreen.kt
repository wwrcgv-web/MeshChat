package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * Palette claire, réservée à l'écran « Réseau » : fond très pâle, cartes blanches, texte foncé.
 * Objectif : rester lisible en plein soleil et guider un utilisateur peu habitué aux smartphones.
 */
private object Light {
    val Background = Color(0xFFF4F8F5)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111827)
    val Grey = Color(0xFF5F6B63)
    val Green = Color(0xFF1F8A4F)
    val GreenSoft = Color(0xFFE6F4EC)
}

/**
 * Écran « Réseau » : mon compte (QR code en évidence) puis la liste des contacts.
 * Les deux sections sont nettement séparées par un titre et un fond différent.
 */
@Composable
fun NetworkScreen(
    context: Context,
    contacts: List<MeshPeer>,
    peers: List<MeshPeer>,
    profileTick: Int,
    onOpen: (String) -> Unit,
    onShowQr: () -> Unit,
    onScan: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Light.Background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                AccountCard(onShowQr = onShowQr, onScan = onScan)
            }
            item {
                Spacer(Modifier.height(6.dp))
                SectionTitle("${t("contacts").uppercase()} (${contacts.size})")
            }
            items(contacts, key = { it.id }) { contact ->
                val avatar = remember(contact.id, profileTick) { ProfileStore.avatarBitmapOf(context, contact.id) }
                val online = peers.any { it.id == contact.id }
                ContactRow(
                    name = MeshState.nameOf(contact.id),
                    subtitle = if (online) t("in_range_status") else t("out_of_range"),
                    online = online,
                    avatar = avatar,
                    onClick = { onOpen(contact.id) }
                )
            }
            if (contacts.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(t("no_contact"), color = Light.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(t("go_contacts"), color = Light.Grey, fontSize = 14.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** Carte « Mon compte » : le bouton du QR code est large et en couleur, impossible à manquer. */
@Composable
private fun AccountCard(onShowQr: () -> Unit, onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Light.Card)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Light.GreenSoft),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(id = R.drawable.ic_qr),
                    contentDescription = null,
                    tint = Light.Green,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("my_qr"), color = Light.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("@${MeshState.pseudo}", color = Light.Grey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(t("my_qr_note"), color = Light.Grey, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onShowQr,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Light.Green, contentColor = Color.White)
        ) {
            Text(t("show_qr"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Light.Green)
        ) {
            Text(t("scan_qr"), fontSize = 16.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Light.Grey,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Un contact : carte blanche, avatar, nom, état de portée. */
@Composable
private fun ContactRow(
    name: String,
    subtitle: String,
    online: Boolean,
    avatar: Bitmap?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Light.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box {
            if (avatar != null) {
                Image(
                    bitmap = avatar.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Light.GreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name.take(1).uppercase(),
                        color = Light.Green,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (online) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(Light.Green)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Light.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Light.Grey, fontSize = 13.sp)
        }
        Text("›", color = Light.Grey, fontSize = 24.sp)
    }
}
