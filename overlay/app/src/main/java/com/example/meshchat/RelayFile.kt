package com.example.meshchat

import android.content.Context
import javax.crypto.SecretKey

/**
 * Envoi d'un fichier (photo ou vocal) par le relais Internet, en morceaux.
 * Les morceaux sont chiffrés par RelayChunks, puis déposés un par un.
 * Retourne l'identifiant du fichier si tous les morceaux sont acceptés, sinon null.
 * Ne fait rien si Internet est désactivé. Le relais ne voit que du chiffré.
 */
object RelayFile {

    fun envoyer(context: Context, cleSecrete: SecretKey, destinataireId: String, donnees: ByteArray, type: String): String? {
        if (!RelayClient.isEnabled(context)) return null
        val morceaux = RelayChunks.decouper(donnees, type, cleSecrete)
        if (morceaux.isEmpty()) return null
        val destinataire = RelayDispatch.hacherDestinataire(destinataireId)
        for (m in morceaux) {
            if (!RelayClient.deposer(context, destinataire, m.contenuChiffre)) return null
        }
        return morceaux.first().fileId
    }
}
