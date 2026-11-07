package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.crypto.CryptoEngine

/**
 * Réception des messages déposés sur le relais Internet.
 * Relève les messages chiffrés pour ce téléphone, les déchiffre avec la clé du contact,
 * et renvoie les textes lisibles. Ne fait rien si Internet est désactivé.
 */
object RelayReceive {

    /** Un message relevé : expéditeur identifié par la clé qui le déchiffre, et texte. */
    data class Recu(val expediteurId: String, val texte: String)

    /**
     * Relève les messages pour mon identifiant haché, puis tente de les déchiffrer
     * avec chacun de mes contacts. Retourne les messages déchiffrés.
     */
    fun releverEtDechiffrer(context: Context): List<Recu> {
        if (!RelayClient.isEnabled(context)) return emptyList()
        val manager = BleMeshManager.instance ?: return emptyList()
        val monHache = RelayDispatch.hacherDestinataire(manager.myIdHex)
        val contenus = RelayClient.relever(context, monHache)
        val resultat = mutableListOf<Recu>()
        for (contenu in contenus) {
            val chiffre = try {
                Base64.decode(contenu, Base64.NO_WRAP)
            } catch (e: Exception) {
                continue
            }
            // Le message vient de l'un de mes contacts : on essaie chaque clé partagée
            for (contact in manager.knownContacts()) {
                val cle = manager.sharedKeyWith(contact.id) ?: continue
                val clair = CryptoEngine.decrypt(chiffre, cle) ?: continue
                resultat.add(Recu(contact.id, String(clair, Charsets.UTF_8)))
                break
            }
        }
        return resultat
    }
}
