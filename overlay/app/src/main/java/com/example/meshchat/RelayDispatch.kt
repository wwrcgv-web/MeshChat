package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * Envoi d'un message texte par le relais Internet, quand le destinataire n'est pas à portée.
 * Le contenu chiffré porte un identifiant de message : « ID:<msgId>|<texte> ».
 * Le relais ne voit que du chiffré. Ne fait rien si Internet est désactivé.
 */
object RelayDispatch {

    /** Identifiant haché du destinataire : le relais ne voit jamais l'identifiant réel. */
    fun hacherDestinataire(destinataireId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("samchat-relais:" + destinataireId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Nouvel identifiant de message (16 caractères hexadécimaux). */
    fun nouvelId(): String {
        val octets = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return octets.joinToString("") { "%02x".format(it) }
    }

    /**
     * Chiffre « ID:<msgId>|<texte> » avec la clé partagée puis le dépose sur le relais.
     * Retourne l'identifiant du message si le relais l'a accepté, sinon null.
     */
    fun envoyerParRelais(context: Context, cleSecrete: SecretKey, destinataireId: String, texte: String): String? {
        if (!RelayClient.isEnabled(context)) return null
        val msgId = nouvelId()
        val chiffre = CryptoEngine.encrypt("ID:$msgId|$texte".toByteArray(Charsets.UTF_8), cleSecrete)
        val contenu = Base64.encodeToString(chiffre, Base64.NO_WRAP)
        return if (RelayClient.deposer(context, hacherDestinataire(destinataireId), contenu)) msgId else null
    }
}
