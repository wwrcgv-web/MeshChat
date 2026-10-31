package com.example.meshchat

import android.content.Context
import android.graphics.Bitmap
import com.example.meshchat.crypto.CryptoEngine
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * Ajout de contact par QR code. Le QR contient : SAMCHAT|identifiant|nom|clé publique.
 * La clé publique n'est pas secrète : elle sert à chiffrer, la clé privée reste sur le téléphone.
 */
object QrShare {
    private const val PREFIX = "SAMCHAT"

    /** Contenu du QR de ce téléphone, ou null si son identifiant ou sa clé ne sont pas encore prêts. */
    fun myContent(context: Context): String? {
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        val id = prefs.getString("my_id", null) ?: return null
        val pub = prefs.getString("my_pub", null) ?: return null
        val name = (prefs.getString("my_name", "") ?: "").replace("|", " ").trim()
        return "$PREFIX|$id|$name|$pub"
    }

    /** Crée l'image du QR code. */
    fun bitmap(content: String, size: Int = 640): Bitmap =
        BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)

    /**
     * Lit un QR scanné et enregistre le contact. Retourne le nom ajouté, ou null si le QR n'est pas valide
     * ou s'il s'agit de ce téléphone.
     */
    fun addContact(context: Context, raw: String): String? {
        val parts = raw.split("|")
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val id = parts[1]
        if (!Regex("[0-9a-f]{16}").matches(id)) return null
        val name = parts[2].trim().take(20).ifBlank { "contact" }
        val pubHex = parts[3]
        val pubBytes = try {
            pubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return null
        }
        try {
            CryptoEngine.decodePublic(pubBytes)
        } catch (e: Exception) {
            return null
        }
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        if (prefs.getString("my_id", null) == id) return null
        prefs.edit().putString("peer_$id", pubHex).putString("name_$id", name).apply()
        return name
    }
}
