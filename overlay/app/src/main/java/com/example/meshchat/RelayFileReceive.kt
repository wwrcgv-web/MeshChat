package com.example.meshchat

import android.content.Context
import com.example.meshchat.ble.BleMeshManager
import javax.crypto.SecretKey

/**
 * Réception des fichiers (photo, vocal) envoyés par le relais Internet, en morceaux.
 * Relève les morceaux, les range par identifiant de fichier, et renvoie les fichiers complets.
 * Les morceaux incomplets restent en attente et expirent avec le relais (24 heures).
 */
object RelayFileReceive {

    /** Un fichier complet reçu par Internet. */
    data class FichierRecu(val expediteurId: String, val type: String, val donnees: ByteArray)

    // Morceaux en attente, rangés par identifiant de fichier (mémoire seulement)
    private val enAttente = HashMap<String, MutableList<RelayChunks.MorceauRecu>>()

    fun releverEtReconstituer(context: Context): List<FichierRecu> {
        if (!RelayClient.isEnabled(context)) return emptyList()
        val manager = BleMeshManager.instance ?: return emptyList()
        val monHache = RelayDispatch.hacherDestinataire(manager.myIdHex)
        val complets = mutableListOf<FichierRecu>()

        for (contenu in RelayClient.relever(context, monHache)) {
            for (contact in manager.knownContacts()) {
                val cle: SecretKey = manager.sharedKeyWith(contact.id) ?: continue
                val morceau = RelayChunks.lireMorceau(contenu, cle) ?: continue
                val liste = enAttente.getOrPut(morceau.fileId) { mutableListOf() }
                if (liste.none { it.numero == morceau.numero }) liste.add(morceau)
                val donnees = RelayChunks.reassembler(liste)
                if (donnees != null) {
                    complets.add(FichierRecu(contact.id, morceau.type, donnees))
                    enAttente.remove(morceau.fileId)
                }
                break
            }
        }
        return complets
    }
}
