package com.example.meshchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Écran affiché une seule fois, au tout premier lancement : l'utilisateur doit accepter les
 * Conditions d'Utilisation et la Politique de Confidentialité. Tant qu'il n'a pas accepté,
 * aucun service ne démarre et aucune permission n'est demandée.
 */
@Composable
fun TermsScreen(onAccept: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = SamchatColors.Background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                t("terms_title"),
                color = SamchatColors.Green,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t("terms_sub"),
                color = SamchatColors.Grey,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(SamchatColors.Card, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Clause(
                    t("terms_c1_t"),
                    t("terms_c1_x")
                )
                Clause(
                    t("terms_c2_t"),
                    t("terms_c2_x")
                )
                Clause(
                    t("terms_c3_t"),
                    t("terms_c3_x")
                )
                Clause(
                    t("terms_c4_t"),
                    t("terms_c4_x")
                )
                Clause(
                    t("terms_c5_t"),
                    t("terms_c5_x")
                )
                Clause(
                    t("terms_c6_t"),
                    t("terms_c6_x")
                )
                Text(
                    t("terms_footer"),
                    color = SamchatColors.Grey,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { accepted = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SamchatColors.Green,
                        uncheckedColor = SamchatColors.Grey,
                        checkmarkColor = Color.Black
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    t("terms_check"),
                    color = SamchatColors.White,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onAccept,
                enabled = accepted,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SamchatColors.Green,
                    contentColor = Color.Black,
                    disabledContainerColor = SamchatColors.Field,
                    disabledContentColor = SamchatColors.Grey
                )
            ) {
                Text(t("terms_start"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Une clause : un titre et son explication. */
@Composable
private fun Clause(titre: String, texte: String) {
    Text(
        titre,
        color = SamchatColors.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(3.dp))
    Text(texte, color = SamchatColors.Grey, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(14.dp))
}
