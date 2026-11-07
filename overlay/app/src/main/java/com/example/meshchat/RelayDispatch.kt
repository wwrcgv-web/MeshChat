package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import java.security.MessageDigest
import javax.crypto.SecretKey

/**
 * Envoi d'un message texte par le relais Internet, quand le destinataire n'est pas à portée.
 * Le message est chiffré ici avec le secret partagé : le relais ne voit que du chiffré.
 * Ne fait rien si Internet est désactivé ou si le jeton n'est pas enregistré.
 */
object RelayDispatch {

    /** Identifiant haché du destinataire : le relais ne voit jamais l'identifiant réel. */
    fun hacherDestinataire(destinataireId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("samchat-relais:" + destinataireId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Chiffre le texte avec la clé partagée puis le dépose sur le relais.
     * Retourne true si le relais a accepté le message.
     */
    fun envoyerParRelais(context: Context, cleSecrete: SecretKey, destinataireId: String, texte: String): Boolean {
        if (!RelayClient.isEnabled(context)) return false
        val chiffre = CryptoEngine.encrypt(texte.toByteArray(Charsets.UTF_8), cleSecrete)
        val contenu = Base64.encodeToString(chiffre, Base64.NO_WRAP)
        return RelayClient.deposer(context, hacherDestinataire(destinataireId), contenu)
    }
}
