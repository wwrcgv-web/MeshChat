package com.example.meshchat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Texte de confidentialité affiché dans les Réglages. Il ne décrit que ce que fait réellement l'app. */
@Composable
fun NoteConfidentialite() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "CONFIDENTIALITÉ",
            color = SamchatColors.Green,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
        )
        Text(
            "Tes messages privés et tes fichiers privés sont chiffrés de bout en bout : seuls toi et ton contact pouvez les lire. " +
                "Le relais Internet ne voit que des données chiffrées et ne peut pas les déchiffrer. " +
                "Les messages gardés pour un contact absent sont effacés après 24 heures.",
            color = SamchatColors.White,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Les messages publics (« Tous ») ne sont pas chiffrés de bout en bout. " +
                "La sauvegarde du compte est protégée par ton mot de passe.",
            color = SamchatColors.Grey,
            fontSize = 13.sp
        )
    }
}
