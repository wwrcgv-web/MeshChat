package com.example.meshchat

import android.util.Base64
import com.example.meshchat.crypto.CryptoEngine
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * Découpage des fichiers (photo, vocal, vidéo) pour le relais Internet.
 * Chaque morceau est chiffré avec la clé partagée. Le relais ne voit que du chiffré.
 * En-tête clair du morceau (avant chiffrement) : « CH2:<fileId>|<n>|<total>|<type>| » suivi des
 * octets bruts. L'ancien format « CH: » (données Base64) reste accepté en réception.
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

    /** Nombre de morceaux nécessaires pour ce fichier. */
    fun nombreDeMorceaux(taille: Int): Int = maxOf(1, (taille + TAILLE_MORCEAU - 1) / TAILLE_MORCEAU)

    /** Préfixe du format binaire : l'en-tête est suivi des octets bruts, sans Base64 interne. */
    const val PREFIXE_BINAIRE = "CH2:"

    /**
     * Fabrique un seul morceau (numéro de 1 à total), sans garder les autres en mémoire.
     * Les octets bruts suivent l'en-tête : un seul encodage Base64 au total (le transport JSON
     * en exige un), au lieu de deux.
     */
    fun morceau(donnees: ByteArray, type: String, cle: SecretKey, fileId: String, numero: Int, total: Int): Morceau {
        val from = (numero - 1) * TAILLE_MORCEAU
        val to = minOf(donnees.size, from + TAILLE_MORCEAU)
        val bloc = donnees.copyOfRange(from, to)
        val entete = "$PREFIXE_BINAIRE$fileId|$numero|$total|$type|".toByteArray(Charsets.UTF_8)
        val clair = entete + bloc
        val chiffre = CryptoEngine.encrypt(clair, cle)
        return Morceau(fileId, numero, total, Base64.encodeToString(chiffre, Base64.NO_WRAP))
    }

    /** Découpe un fichier en morceaux chiffrés (pour les petits fichiers). */
    fun decouper(donnees: ByteArray, type: String, cle: SecretKey): List<Morceau> {
        val fileId = nouvelId()
        val total = nombreDeMorceaux(donnees.size)
        return (1..total).map { morceau(donnees, type, cle, fileId, it, total) }
    }

    /** Un morceau relevé et déchiffré. */
    data class MorceauRecu(val fileId: String, val numero: Int, val total: Int, val type: String, val bloc: ByteArray)

}
