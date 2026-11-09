package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.crypto.CryptoEngine

/**
 * Réception des messages déposés sur le relais Internet.
 * Relève les messages chiffrés, les déchiffre avec la clé du contact, puis dépose
 * un accusé « REÇU » à l'expéditeur. Ne fait rien si Internet est désactivé.
 */
object RelayReceive {

    /** Un message relevé : expéditeur, identifiant du message d'origine et texte. */
    data class Recu(val expediteurId: String, val msgId: String, val texte: String)

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
            for (contact in manager.knownContacts()) {
                val cle = manager.sharedKeyWith(contact.id) ?: continue
                val clair = CryptoEngine.decrypt(chiffre, cle) ?: continue
                val texte = String(clair, Charsets.UTF_8)
                // Format attendu : ID:<msgId>|<texte>
                if (!texte.startsWith("ID:") || !texte.contains("|")) break
                val msgId = texte.substring(3, texte.indexOf("|"))
                val corps = texte.substring(texte.indexOf("|") + 1)
                resultat.add(Recu(contact.id, msgId, corps))
                // Accusé « reçu » renvoyé à l'expéditeur par le relais
                RelayAck.deposerAccuse(context, contact.id, msgId, cle)
                break
            }
        }
        return resultat
    }
}
