package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Profil de ce téléphone (statut et photo de profil) et profils reçus des contacts.
 * Chaque profil porte une version qui ne fait que monter : un profil plus ancien est toujours ignoré,
 * même s'il arrive après un plus récent (cas fréquent avec les relais).
 *
 * Ce fichier ne touche ni au Bluetooth ni au chiffrement : il ne fait que stocker et lire.
 * Le chiffrement et l'envoi sont faits dans BleMeshManager.
 */
object ProfileStore {
    const val STATUS_MAX_CHARS = 60

    /** Côté de la photo de profil, en pixels (carrée). */
    const val PHOTO_SIDE = 96

    /** Taille maximale acceptée pour une photo de profil reçue, en octets. */
    const val PHOTO_MAX_BYTES = 12_000

    private const val PREFS = "meshchat"
    private const val KEY_MY_STATUS = "profile_my_status"
    private const val KEY_MY_VERSION = "profile_my_version"
    private const val KEY_MY_PHOTO = "profile_my_photo"
    private const val PREFIX_STATUS = "profile_status_"
    private const val PREFIX_VERSION = "profile_version_"
    private const val PREFIX_PHOTO = "profile_photo_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Statut de ce téléphone. Vide si l'utilisateur n'en a pas mis. */
    fun myStatus(context: Context): String =
        prefs(context).getString(KEY_MY_STATUS, "") ?: ""

    /** Photo de profil de ce téléphone, en JPEG. Vide si l'utilisateur n'en a pas mis. */
    fun myPhoto(context: Context): ByteArray {
        val hex = prefs(context).getString(KEY_MY_PHOTO, "") ?: ""
        return if (hex.isEmpty()) ByteArray(0) else Base64.decode(hex, Base64.NO_WRAP)
    }

    /** Version du profil de ce téléphone. Monte à chaque enregistrement. */
    fun myVersion(context: Context): Int =
        prefs(context).getInt(KEY_MY_VERSION, 0)

    /**
     * Enregistre le nouveau statut, en le coupant à 60 caractères, et incrémente la version.
     * La photo de profil déjà enregistrée est conservée. Retourne la nouvelle version.
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

    /**
     * Enregistre la photo de profil : recadrée au carré, réduite à 96 px, en JPEG qualité 60.
     * Incrémente la version. Retourne la nouvelle version, ou null si la photo est trop lourde.
     */
    fun setMyPhoto(context: Context, source: Bitmap): Int? {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, left, top, side, side)
        val small = Bitmap.createScaledBitmap(square, PHOTO_SIDE, PHOTO_SIDE, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val bytes = out.toByteArray()
        if (bytes.size > PHOTO_MAX_BYTES) return null
        val next = myVersion(context) + 1
        prefs(context).edit()
            .putString(KEY_MY_PHOTO, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putInt(KEY_MY_VERSION, next)
            .apply()
        return next
    }

    /** Statut reçu d'un contact, ou vide si on ne le connaît pas encore. */
    fun statusOf(context: Context, peerId: String): String =
        prefs(context).getString(PREFIX_STATUS + peerId, "") ?: ""

    /** Photo de profil reçue d'un contact, en JPEG. Vide si on n'en a pas encore reçu. */
    fun photoOf(context: Context, peerId: String): ByteArray {
        val hex = prefs(context).getString(PREFIX_PHOTO + peerId, "") ?: ""
        return if (hex.isEmpty()) ByteArray(0) else Base64.decode(hex, Base64.NO_WRAP)
    }

    /** Version reçue d'un contact, ou 0 si on n'a encore rien reçu de lui. */
    fun versionOf(context: Context, peerId: String): Int =
        prefs(context).getInt(PREFIX_VERSION + peerId, 0)

    /**
     * Accepte un profil reçu d'un contact, seulement s'il est plus récent que celui qu'on a.
     * [photo] peut être vide si le contact n'a pas de photo. Retourne true si le profil a été enregistré.
     */
    fun acceptFromPeer(context: Context, peerId: String, version: Int, status: String, photo: ByteArray): Boolean {
        if (version <= versionOf(context, peerId)) return false
        prefs(context).edit()
            .putInt(PREFIX_VERSION + peerId, version)
            .putString(PREFIX_STATUS + peerId, status.take(STATUS_MAX_CHARS))
            .putString(PREFIX_PHOTO + peerId, if (photo.isEmpty()) "" else Base64.encodeToString(photo, Base64.NO_WRAP))
            .apply()
        return true
    }

    /**
     * Contenu clair d'un profil, prêt à être chiffré : version (4 octets, big-endian)
     * + longueur du statut (1 octet) + statut en UTF-8 + photo en JPEG (le reste).
     */
    fun encode(version: Int, status: String, photo: ByteArray): ByteArray {
        val statusBytes = status.take(STATUS_MAX_CHARS).toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(
            (version shr 24).toByte(),
            (version shr 16).toByte(),
            (version shr 8).toByte(),
            version.toByte(),
            statusBytes.size.toByte()
        ) + statusBytes + photo
    }

    /**
     * Lit un contenu clair produit par [encode]. Retourne (version, statut, photo), ou null si mal formé.
     */
    fun decode(bytes: ByteArray): Triple<Int, String, ByteArray>? {
        if (bytes.size < 5) return null
        val version = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val statusLen = bytes[4].toInt() and 0xFF
        if (statusLen > STATUS_MAX_CHARS * 4 || bytes.size < 5 + statusLen) return null
        val status = String(bytes, 5, statusLen, StandardCharsets.UTF_8)
        val photo = bytes.copyOfRange(5 + statusLen, bytes.size)
        if (photo.size > PHOTO_MAX_BYTES) return null
        return Triple(version, status, photo)
    }
}
