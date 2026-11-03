package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Profil de ce téléphone (statut et photo) et profils reçus des contacts.
 * Chaque profil porte une version qui ne fait que monter : un profil plus ancien est toujours ignoré,
 * même s'il arrive après un plus récent (cas fréquent avec les relais).
 *
 * Ce fichier ne touche ni au Bluetooth ni au chiffrement : il ne fait que stocker et lire.
 */
object ProfileStore {
    const val STATUS_MAX_CHARS = 60
    /** Côté de la photo de profil, en pixels (carré). */
    const val AVATAR_SIZE = 96
    /** Taille maximale de la photo de profil, en octets, après compression. */
    const val AVATAR_MAX_BYTES = 8_000

    private const val PREFS = "meshchat"
    private const val KEY_MY_STATUS = "profile_my_status"
    private const val KEY_MY_VERSION = "profile_my_version"
    private const val KEY_MY_AVATAR = "profile_my_avatar"
    private const val PREFIX_STATUS = "profile_status_"
    private const val PREFIX_VERSION = "profile_version_"
    private const val PREFIX_AVATAR = "profile_avatar_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Mon profil ----------

    /** Statut de ce téléphone. Vide si l'utilisateur n'en a pas mis. */
    fun myStatus(context: Context): String =
        prefs(context).getString(KEY_MY_STATUS, "") ?: ""

    /** Version du profil de ce téléphone. Monte à chaque enregistrement. */
    fun myVersion(context: Context): Int =
        prefs(context).getInt(KEY_MY_VERSION, 0)

    /** Photo de profil de ce téléphone, en JPEG, ou null si aucune n'est choisie. */
    fun myAvatar(context: Context): ByteArray? =
        prefs(context).getString(KEY_MY_AVATAR, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /**
     * Enregistre le nouveau statut et incrémente la version. Appelé seulement sur « Enregistrer ».
     * La photo actuelle est conservée. Retourne la nouvelle version.
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
     * Enregistre une nouvelle photo de profil et incrémente la version.
     * L'image est recadrée au carré, réduite à [AVATAR_SIZE] pixels, puis compressée en JPEG.
     * Retourne la nouvelle version, ou null si l'image ne peut pas être lue ou est trop lourde.
     */
    fun setMyAvatar(context: Context, source: Bitmap): Int? {
        val bytes = compressAvatar(source) ?: return null
        val next = myVersion(context) + 1
        prefs(context).edit()
            .putString(KEY_MY_AVATAR, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putInt(KEY_MY_VERSION, next)
            .apply()
        return next
    }

    /** Recadrage carré au centre, réduction, puis compression JPEG par paliers jusqu'à rester sous la limite. */
    private fun compressAvatar(source: Bitmap): ByteArray? {
        val side = minOf(source.width, source.height)
        if (side <= 0) return null
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, x, y, side, side)
        val small = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
        for (quality in intArrayOf(60, 50, 40, 30)) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= AVATAR_MAX_BYTES) return out.toByteArray()
        }
        return null
    }

    // ---------- Profils reçus des contacts ----------

    /** Statut reçu d'un contact, ou vide si on ne le connaît pas encore. */
    fun statusOf(context: Context, peerId: String): String =
        prefs(context).getString(PREFIX_STATUS + peerId, "") ?: ""

    /** Version reçue d'un contact, ou 0 si on n'a encore rien reçu de lui. */
    fun versionOf(context: Context, peerId: String): Int =
        prefs(context).getInt(PREFIX_VERSION + peerId, 0)

    /** Photo de profil reçue d'un contact, en JPEG, ou null si aucune. */
    fun avatarOf(context: Context, peerId: String): ByteArray? =
        prefs(context).getString(PREFIX_AVATAR + peerId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Photo de profil reçue d'un contact, décodée pour l'affichage, ou null. */
    fun avatarBitmapOf(context: Context, peerId: String): Bitmap? =
        avatarOf(context, peerId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /**
     * Accepte un profil reçu d'un contact, seulement s'il est plus récent que celui qu'on a.
     * [avatar] peut être null si le contact n'a pas de photo ou si elle n'a pas encore été reçue :
     * dans ce cas, la photo déjà gardée est conservée.
     * Retourne true si le profil a été enregistré, false s'il a été ignoré (ancien ou identique).
     */
    fun acceptFromPeer(context: Context, peerId: String, version: Int, status: String, avatar: ByteArray? = null): Boolean {
        if (version <= versionOf(context, peerId)) return false
        val editor = prefs(context).edit()
            .putInt(PREFIX_VERSION + peerId, version)
            .putString(PREFIX_STATUS + peerId, status.take(STATUS_MAX_CHARS))
        if (avatar != null && avatar.isNotEmpty() && avatar.size <= AVATAR_MAX_BYTES) {
            editor.putString(PREFIX_AVATAR + peerId, Base64.encodeToString(avatar, Base64.NO_WRAP))
        }
        editor.apply()
        return true
    }

    // ---------- Encodage ----------

    /**
     * Contenu clair d'un profil, prêt à être chiffré : version (4 octets, big-endian), longueur du statut
     * (1 octet), statut en UTF-8, puis photo en JPEG (le reste du contenu, peut être vide).
     */
    fun encode(version: Int, status: String, avatar: ByteArray?): ByteArray {
        val statusBytes = status.take(STATUS_MAX_CHARS).toByteArray(StandardCharsets.UTF_8)
        val header = byteArrayOf(
            (version shr 24).toByte(),
            (version shr 16).toByte(),
            (version shr 8).toByte(),
            version.toByte(),
            statusBytes.size.toByte()
        )
        return header + statusBytes + (avatar ?: ByteArray(0))
    }

    /**
     * Lit un contenu clair produit par [encode]. Retourne (version, statut, photo), photo vide si absente,
     * ou null si le contenu est mal formé.
     */
    fun decode(bytes: ByteArray): Triple<Int, String, ByteArray?>? {
        if (bytes.size < 5) return null
        val version = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val statusLen = bytes[4].toInt() and 0xFF
        if (statusLen > STATUS_MAX_CHARS * 4 || bytes.size < 5 + statusLen) return null
        val status = String(bytes, 5, statusLen, StandardCharsets.UTF_8)
        val rest = bytes.copyOfRange(5 + statusLen, bytes.size)
        return Triple(version, status, if (rest.isEmpty()) null else rest)
    }
}
