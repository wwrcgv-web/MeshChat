package com.example.meshchat

import android.content.Context
import javax.crypto.SecretKey

/**
 * Envoi d'un fichier (photo, vocal ou vidéo) par le relais Internet, en morceaux.
 * Les morceaux sont chiffrés un par un, puis déposés. Le relais ne voit que du chiffré.
 * Retourne l'identifiant du fichier si tous les morceaux sont acceptés, sinon null.
 * À appeler hors du fil principal : chaque dépôt est une requête réseau.
 */
object RelayFile {

    /** Taille maximale d'une vidéo envoyée par Internet : 20 Mo. */
    const val MAX_VIDEO_BYTES = 20 * 1024 * 1024

    private const val ESSAIS_PAR_MORCEAU = 2

    fun envoyer(context: Context, cleSecrete: SecretKey, destinataireId: String, donnees: ByteArray, type: String): String? {
        if (!RelayClient.isEnabled(context)) return null
        if (donnees.isEmpty()) return null
        val destinataire = RelayDispatch.hacherDestinataire(destinataireId)
        val fileId = RelayChunks.nouvelId()
        val total = RelayChunks.nombreDeMorceaux(donnees.size)
        for (numero in 1..total) {
            val m = RelayChunks.morceau(donnees, type, cleSecrete, fileId, numero, total)
            var ok = false
            for (essai in 1..ESSAIS_PAR_MORCEAU) {
                if (RelayClient.deposer(context, destinataire, m.contenuChiffre)) {
                    ok = true
                    break
                }
            }
            if (!ok) return null
        }
        return fileId
    }
}
