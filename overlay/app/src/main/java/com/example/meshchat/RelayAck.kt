package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.crypto.CryptoEngine
import javax.crypto.SecretKey

/**
 * Accusés de réception par relais Internet.
 * Un accusé est un petit message chiffré « REÇU:<id du message> », déposé pour l'expéditeur.
 * Le relais ne voit que du chiffré. Ne fait rien si Internet est désactivé.
 */
object RelayAck {
    private const val PREFIXE = "REÇU:"

    /** Dépose un accusé « reçu » pour un message relayé, à destination de son expéditeur. */
    fun deposerAccuse(context: Context, expediteurId: String, messageId: String, cle: SecretKey): Boolean {
        if (!RelayClient.isEnabled(context)) return false
        val chiffre = CryptoEngine.encrypt((PREFIXE + messageId).toByteArray(Charsets.UTF_8), cle)
        val contenu = Base64.encodeToString(chiffre, Base64.NO_WRAP)
        return RelayClient.deposer(context, RelayDispatch.hacherDestinataire(expediteurId), contenu)
    }

    /**
     * Relève mes accusés, les déchiffre et retourne les identifiants des messages confirmés reçus.
     */
    fun releverAccuses(context: Context): List<String> {
        if (!RelayClient.isEnabled(context)) return emptyList()
        val manager = BleMeshManager.instance ?: return emptyList()
        val monHache = RelayDispatch.hacherDestinataire(manager.myIdHex)
        val confirmes = mutableListOf<String>()
        for (contenu in RelayClient.relever(context, monHache)) {
            val chiffre = try {
                Base64.decode(contenu, Base64.NO_WRAP)
            } catch (e: Exception) {
                continue
            }
            for (contact in manager.knownContacts()) {
                val cle = manager.sharedKeyWith(contact.id) ?: continue
                val clair = CryptoEngine.decrypt(chiffre, cle) ?: continue
                val texte = String(clair, Charsets.UTF_8)
                if (texte.startsWith(PREFIXE)) confirmes.add(texte.removePrefix(PREFIXE))
                break
            }
        }
        return confirmes
    }
}
