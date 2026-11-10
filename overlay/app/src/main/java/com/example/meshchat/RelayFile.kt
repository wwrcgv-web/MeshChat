package com.example.meshchat

import android.content.Context
import com.example.meshchat.crypto.CryptoEngine
import javax.crypto.SecretKey

/**
 * Envoi d'un fichier (photo ou vocal) par le relais Internet, en morceaux.
 * Les morceaux sont chiffrés par RelayChunks, puis déposés un par un.
 * Ne fait rien si Internet est désactivé. Le relais ne voit que du chiffré.
 */
object RelayFile {

    /**
     * Découpe le fichier et dépose chaque morceau sur le relais.
     * Retourne true si tous les morceaux ont été acceptés.
     * En cas de refus d'un morceau, retourne false : l'appelant repassera par le Bluetooth.
     */
    fun envoyer(context: Context, cleSecrete: SecretKey, destinataireId: String, donnees: ByteArray, type: String): Boolean {
        if (!RelayClient.isEnabled(context)) return false
        val morceaux = RelayChunks.decouper(donnees, type, cleSecrete)
        val destinataire = RelayDispatch.hacherDestinataire(destinataireId)
        for (m in morceaux) {
            if (!RelayClient.deposer(context, destinataire, m.contenuChiffre)) return false
        }
        return true
    }
}
