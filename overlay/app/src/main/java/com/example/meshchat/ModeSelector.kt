package com.example.meshchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sélecteur de mode de l'écran d'accueil : « Avec Internet » ou « Sans Internet ».
 * Composant visuel seul : il ne change rien au routage. Le choix est transmis à onChange.
 */
@Composable
fun ModeSelector(
    internetOn: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SamchatColors.Field)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeOption(
            titre = "Avec Internet",
            sousTitre = "Messages partout",
            selected = internetOn,
            modifier = Modifier.weight(1f),
            onClick = { onChange(true) }
        )
        ModeOption(
            titre = "Sans Internet",
            sousTitre = "Amis à côté",
            selected = !internetOn,
            modifier = Modifier.weight(1f),
            onClick = { onChange(false) }
        )
    }
}

@Composable
private fun ModeOption(
    titre: String,
    sousTitre: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SamchatColors.GreenSoft else SamchatColors.Field)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(titre, color = SamchatColors.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sousTitre, color = SamchatColors.Grey, fontSize = 12.sp)
    }
}
