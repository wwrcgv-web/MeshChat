package com.example.meshchat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sauvegarde et restauration du compte. Aucune clé ici : tout passe par AccountBackup. */
@Composable
fun AccountSection(context: Context) {
    var motDePasse by remember { mutableStateOf("") }
    var sauvegarde by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text("Sauvegarde du compte", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = motDePasse,
            onValueChange = { motDePasse = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("Mot de passe (12 caractères min.)") },
            textStyle = TextStyle(fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (motDePasse.length < AccountBackup.MIN_PASSWORD_LENGTH) {
                    message = "Mot de passe trop court"
                } else {
                    sauvegarde = AccountBackup.creer(context, motDePasse)
                    message = "Sauvegarde créée : copie-la et garde-la hors du téléphone"
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Sauvegarder le compte", fontSize = 16.sp) }

        if (sauvegarde.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(sauvegarde, fontSize = 11.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("Restaurer un compte", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = sauvegarde,
            onValueChange = { sauvegarde = it },
            placeholder = { Text("Colle la sauvegarde ici") },
            textStyle = TextStyle(fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val ok = AccountBackup.restaurer(context, sauvegarde, motDePasse)
                message = if (ok) "Compte restauré : relance l'app" else "Échec : mot de passe ou sauvegarde incorrect"
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Restaurer le compte", fontSize = 16.sp) }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 14.sp)
        }
    }
}
