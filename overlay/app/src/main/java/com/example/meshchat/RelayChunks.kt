package com.example.meshchat

import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * Découpage et réassemblage des fichiers (photo, vocal) pour le relais Internet.
 * Chaque morceau est chiffré avec la clé partagée. Le relais ne voit que du chiffré.
 * En-tête clair du morceau (avant chiffrement) : « CH:<fileId>|<n>|<total>|<type>|<données Base64> ».
 */
object RelayChunks {
    /** Taille d'un morceau de données brutes : reste sous la limite de 200 Ko du relais après chiffrement et Base64. */
    const val TAILLE_MORCEAU = 100_000

    /** Un morceau prêt à être déposé. */
    data class Morceau(val fileId: String, val numero: Int, val total: Int, val contenuChiffre: String)

    /** Identifiant d'un fichier (16 caractères hexadécimaux). */
    fun nouvelId(): String {
        val octets = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return octets.joinToString("") { "%02x".format(it) }
    }

    /** Découpe un fichier en morceaux chiffrés, prêts à être déposés un par un. */
    fun decouper(donnees: ByteArray, type: String, cle: SecretKey): List<Morceau> {
        val fileId = nouvelId()
        val parts = donnees.toList().chunked(TAILLE_MORCEAU).map { it.toByteArray() }
        val total = parts.size
        return parts.mapIndexed { index, bloc ->
            val entete = "CH:$fileId|${index + 1}|$total|$type|"
            val clair = entete.toByteArray(Charsets.UTF_8) + Base64.encode(bloc, Base64.NO_WRAP)
            val chiffre = CryptoEngine.encrypt(clair, cle)
            Morceau(fileId, index + 1, total, Base64.encodeToString(chiffre, Base64.NO_WRAP))
        }
    }

    /** Un morceau relevé et déchiffré. */
    data class MorceauRecu(val fileId: String, val numero: Int, val total: Int, val type: String, val bloc: ByteArray)

    /** Lit un morceau reçu (contenu chiffré en Base64). Retourne null s'il est invalide. */
    fun lireMorceau(contenuChiffre: String, cle: SecretKey): MorceauRecu? {
        return try {
            val chiffre = Base64.decode(contenuChiffre, Base64.NO_WRAP)
            val clair = CryptoEngine.decrypt(chiffre, cle) ?: return null
            val texte = String(clair, Charsets.UTF_8)
            if (!texte.startsWith("CH:")) return null
            val parties = texte.substring(3).split("|", limit = 5)
            if (parties.size != 5) return null
            MorceauRecu(
                fileId = parties[0],
                numero = parties[1].toInt(),
                total = parties[2].toInt(),
                type = parties[3],
                bloc = Base64.decode(parties[4], Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Reconstitue le fichier si tous les morceaux sont présents, sinon retourne null. */
    fun reassembler(morceaux: List<MorceauRecu>): ByteArray? {
        if (morceaux.isEmpty()) return null
        val total = morceaux.first().total
        val par = morceaux.associateBy { it.numero }
        if (par.size != total) return null
        val sortie = java.io.ByteArrayOutputStream()
        for (i in 1..total) {
            val m = par[i] ?: return null
            sortie.write(m.bloc)
        }
        return sortie.toByteArray()
    }
}
