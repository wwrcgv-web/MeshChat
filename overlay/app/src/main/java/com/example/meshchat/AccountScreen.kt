package com.example.meshchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshchat.service.MeshService

@Composable
private fun accountFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = SamchatColors.Field,
    unfocusedContainerColor = SamchatColors.Field,
    focusedTextColor = SamchatColors.White,
    unfocusedTextColor = SamchatColors.White,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = SamchatColors.Green
)

@Composable
private fun accountButtonColors() = ButtonDefaults.buttonColors(
    containerColor = SamchatColors.GreenSoft,
    contentColor = SamchatColors.White,
    disabledContainerColor = SamchatColors.Field,
    disabledContentColor = SamchatColors.Grey
)

/** Copie un texte sensible : Android 13 et plus le masque dans l'aperçu du presse-papiers. */
private fun copySensitive(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("sauvegarde", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}

/** Relance le service pour qu'il utilise l'identité restaurée. */
private fun relancerService(context: Context) {
    val app = context.applicationContext
    val handler = Handler(Looper.getMainLooper())
    app.stopService(Intent(app, MeshService::class.java))
    handler.postDelayed({
        ContextCompat.startForegroundService(
            app,
            Intent(app, MeshService::class.java).setAction(MeshService.ACTION_START)
        )
    }, 1000L)
    handler.postDelayed({ MeshState.refresh() }, 2500L)
}

/** Sauvegarde et restauration du compte. Aucune clé ici : tout passe par AccountBackup. */
@Composable
fun AccountSection(context: Context) {
    var motDePasse by remember { mutableStateOf("") }
    var sauvegardeCreee by remember { mutableStateOf("") }
    var aRestaurer by remember { mutableStateOf("") }
    var motDePasseRestauration by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var confirmer by remember { mutableStateOf(false) }

    if (confirmer) {
        AlertDialog(
            onDismissRequest = { confirmer = false },
            title = { Text("Remplacer ce compte ?") },
            text = { Text("Ton identité et tes contacts actuels seront remplacés par ceux de la sauvegarde. Cette action ne peut pas être annulée.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmer = false
                    occupe = true
                    message = "Restauration en cours…"
                    val sauvegarde = aRestaurer
                    val mdp = motDePasseRestauration
                    Thread {
                        val ok = try {
                            AccountBackup.restaurer(context, sauvegarde, mdp)
                        } catch (e: Exception) {
                            false
                        }
                        Handler(Looper.getMainLooper()).post {
                            occupe = false
                            if (ok) {
                                aRestaurer = ""
                                motDePasseRestauration = ""
                                message = "Compte restauré. Le service redémarre : ferme puis rouvre l'app."
                                relancerService(context)
                            } else {
                                message = "Échec : mot de passe ou sauvegarde incorrect"
                            }
                        }
                    }.start()
                }) { Text("Remplacer", fontSize = 16.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmer = false }) { Text("Annuler", fontSize = 16.sp) }
            }
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Sauvegarde du compte", color = SamchatColors.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = motDePasse,
            onValueChange = { motDePasse = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("Mot de passe (12 caractères min.)", color = SamchatColors.Grey) },
            textStyle = TextStyle(fontSize = 16.sp),
            colors = accountFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !occupe,
            colors = accountButtonColors(),
            onClick = {
                if (motDePasse.length < AccountBackup.MIN_PASSWORD_LENGTH) {
                    message = "Mot de passe trop court"
                } else {
                    occupe = true
                    message = "Création en cours…"
                    val mdp = motDePasse
                    Thread {
                        val resultat = try {
                            AccountBackup.creer(context, mdp)
                        } catch (e: Exception) {
                            null
                        }
                        Handler(Looper.getMainLooper()).post {
                            occupe = false
                            if (resultat != null) {
                                sauvegardeCreee = resultat
                                motDePasse = ""
                                message = "Sauvegarde créée : copie-la et garde-la hors du téléphone"
                            } else {
                                message = "Échec de la sauvegarde"
                            }
                        }
                    }.start()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Sauvegarder le compte", fontSize = 16.sp) }

        if (sauvegardeCreee.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(sauvegardeCreee, color = SamchatColors.Grey, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                colors = accountButtonColors(),
                onClick = {
                    copySensitive(context, sauvegardeCreee)
                    message = "Sauvegarde copiée"
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Copier la sauvegarde", fontSize = 16.sp) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Restaurer un compte", color = SamchatColors.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = aRestaurer,
            onValueChange = { aRestaurer = it },
            placeholder = { Text("Colle la sauvegarde ici", color = SamchatColors.Grey) },
            textStyle = TextStyle(fontSize = 12.sp),
            colors = accountFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = motDePasseRestauration,
            onValueChange = { motDePasseRestauration = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("Mot de passe de la sauvegarde", color = SamchatColors.Grey) },
            textStyle = TextStyle(fontSize = 16.sp),
            colors = accountFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !occupe && aRestaurer.isNotBlank() && motDePasseRestauration.isNotEmpty(),
            colors = accountButtonColors(),
            onClick = { confirmer = true },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Restaurer le compte", fontSize = 16.sp) }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = SamchatColors.White, fontSize = 14.sp)
        }
    }
}
