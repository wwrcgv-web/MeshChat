package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Profil de ce téléphone (statut et photo) et profils reçus des contacts.
 * Chaque profil porte une version qui ne fait que monter : un profil plus ancien est toujours ignoré.
 *
 * Les anciennes signatures (encode/decode à deux valeurs, acceptFromPeer sans photo) sont conservées
 * pour que BleMeshManager continue de compiler. Le format avec photo est disponible à côté.
 */
object ProfileStore {
    const val STATUS_MAX_CHARS = 60
    const val AVATAR_SIZE = 96
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

    fun myStatus(context: Context): String =
        prefs(context).getString(KEY_MY_STATUS, "") ?: ""

    fun myVersion(context: Context): Int =
        prefs(context).getInt(KEY_MY_VERSION, 0)

    fun myAvatar(context: Context): ByteArray? =
        prefs(context).getString(KEY_MY_AVATAR, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Enregistre le nouveau statut et incrémente la version. La photo actuelle est conservée. */
    fun setMyStatus(context: Context, raw: String): Int {
        val clean = raw.trim().take(STATUS_MAX_CHARS)
        val next = myVersion(context) + 1
        prefs(context).edit()
            .putString(KEY_MY_STATUS, clean)
            .putInt(KEY_MY_VERSION, next)
            .apply()
        return next
    }

    /** Enregistre une nouvelle photo de profil et incrémente la version. Retourne null si l'image est illisible. */
    fun setMyAvatar(context: Context, source: Bitmap): Int? {
        val bytes = compressAvatar(source) ?: return null
        val next = myVersion(context) + 1
        prefs(context).edit()
            .putString(KEY_MY_AVATAR, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putInt(KEY_MY_VERSION, next)
            .apply()
        return next
    }

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

    fun statusOf(context: Context, peerId: String): String =
        prefs(context).getString(PREFIX_STATUS + peerId, "") ?: ""

    fun versionOf(context: Context, peerId: String): Int =
        prefs(context).getInt(PREFIX_VERSION + peerId, 0)

    fun avatarOf(context: Context, peerId: String): ByteArray? =
        prefs(context).getString(PREFIX_AVATAR + peerId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun avatarBitmapOf(context: Context, peerId: String): Bitmap? =
        avatarOf(context, peerId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** Version ancienne, sans photo : utilisée par BleMeshManager tant qu'il n'envoie que le statut. */
    fun acceptFromPeer(context: Context, peerId: String, version: Int, status: String): Boolean =
        acceptFromPeer(context, peerId, version, status, null)

    /**
     * Accepte un profil reçu d'un contact, seulement s'il est plus récent que celui qu'on a.
     * Si [avatar] est null, la photo déjà gardée est conservée.
     */
    fun acceptFromPeer(context: Context, peerId: String, version: Int, status: String, avatar: ByteArray?): Boolean {
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

    /** Ancien format, sans photo : version (4 octets) + statut UTF-8. Utilisé par BleMeshManager actuel. */
    fun encode(version: Int, status: String): ByteArray {
        val statusBytes = status.take(STATUS_MAX_CHARS).toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(
            (version shr 24).toByte(),
            (version shr 16).toByte(),
            (version shr 8).toByte(),
            version.toByte()
        ) + statusBytes
    }

    /** Ancien décodage : renvoie (version, statut), ou null si mal formé. */
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

    /** Nouveau format avec photo : version (4) + longueur du statut (1) + statut + photo JPEG (optionnelle). */
    fun encodeWithAvatar(version: Int, status: String, avatar: ByteArray?): ByteArray {
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

    /** Nouveau décodage : renvoie (version, statut, photo), photo null si absente, ou null si mal formé. */
    fun decodeWithAvatar(bytes: ByteArray): Triple<Int, String, ByteArray?>? {
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
