package com.example.meshchat

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Profil de ce téléphone (statut pour l'instant) et profils reçus des contacts.
 * Chaque profil porte une version qui ne fait que monter : un profil plus ancien est toujours ignoré,
 * même s'il arrive après un plus récent (cas fréquent avec les relais).
 *
 * Ce fichier ne touche ni au Bluetooth ni au chiffrement : il ne fait que stocker et lire.
 * Le chiffrement et l'envoi seront branchés dans BleMeshManager.
 */
object ProfileStore {
    const val STATUS_MAX_CHARS = 60

    private const val PREFS = "meshchat"
    private const val KEY_MY_STATUS = "profile_my_status"
    private const val KEY_MY_VERSION = "profile_my_version"
    private const val PREFIX_STATUS = "profile_status_"
    private const val PREFIX_VERSION = "profile_version_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Statut de ce téléphone. Vide si l'utilisateur n'en a pas mis. */
    fun myStatus(context: Context): String =
        prefs(context).getString(KEY_MY_STATUS, "") ?: ""

    /** Version du profil de ce téléphone. Monte à chaque enregistrement. */
    fun myVersion(context: Context): Int =
        prefs(context).getInt(KEY_MY_VERSION, 0)

    /**
     * Enregistre le nouveau statut, en le coupant à 60 caractères, et incrémente la version.
     * Appelé seulement quand l'utilisateur appuie sur « Enregistrer », pas à chaque lettre.
     * Retourne la nouvelle version.
     */
    fun setMyStatus(context: Context, raw: String): Int {
        val clean = raw.trim().take(STATUS_MAX_CHARS)
        val next = myVersion(context) + 1
        prefs(context).edit()
            .putString(KEY_MY_STATUS, clean)
            .putInt(KEY_MY_VERSION, next)
            .apply()
        return next
    }

    /** Statut reçu d'un contact, ou vide si on ne le connaît pas encore. */
    fun statusOf(context: Context, peerId: String): String =
        prefs(context).getString(PREFIX_STATUS + peerId, "") ?: ""

    /** Version reçue d'un contact, ou 0 si on n'a encore rien reçu de lui. */
    fun versionOf(context: Context, peerId: String): Int =
        prefs(context).getInt(PREFIX_VERSION + peerId, 0)

    /**
     * Accepte un profil reçu d'un contact, seulement s'il est plus récent que celui qu'on a.
     * Retourne true si le profil a été enregistré, false s'il a été ignoré (ancien ou identique).
     */
    fun acceptFromPeer(context: Context, peerId: String, version: Int, status: String): Boolean {
        if (version <= versionOf(context, peerId)) return false
        prefs(context).edit()
            .putInt(PREFIX_VERSION + peerId, version)
            .putString(PREFIX_STATUS + peerId, status.take(STATUS_MAX_CHARS))
            .apply()
        return true
    }

    /**
     * Contenu clair d'un profil, prêt à être chiffré : version (4 octets, big-endian) + statut en UTF-8.
     * Le chiffrement avec le secret partagé se fait ailleurs, pas ici.
     */
    fun encode(version: Int, status: String): ByteArray {
        val statusBytes = status.take(STATUS_MAX_CHARS).toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(
            (version shr 24).toByte(),
            (version shr 16).toByte(),
            (version shr 8).toByte(),
            version.toByte()
        ) + statusBytes
    }

    /**
     * Lit un contenu clair produit par [encode]. Retourne (version, statut), ou null si le contenu est mal formé.
     */
    fun decode(bytes: ByteArray): Pair<Int, String>? {
        if (bytes.size < 4) return null
        val version = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val status = String(bytes, 4, bytes.size - 4, StandardCharsets.UTF_8)
        if (status.length > STATUS_MAX_CHARS) return null
        return Pair(version, status)
    }
}
