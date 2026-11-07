package com.example.meshchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * Sauvegarde chiffrée du compte : identifiant, clés, contacts.
 * Chiffrement : PBKDF2 (mot de passe -> clé) puis AES-GCM.
 * Le jeton APP_TOKEN et la clé Supabase ne sont jamais inclus.
 */
object AccountBackup {
    const val MIN_PASSWORD_LENGTH = 12
    private const val ITERATIONS = 210_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /** Construit le contenu clair de la sauvegarde (JSON) à partir des préférences. */
    private fun contenuClair(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        val contacts = JSONArray()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("peer_") && value is String) {
                val id = key.removePrefix("peer_")
                val nom = prefs.getString("name_$id", "") ?: ""
                contacts.put(JSONObject().put("id", id).put("cle", value).put("nom", nom))
            }
        }
        val json = JSONObject()
            .put("version", 1)
            .put("mon_id", prefs.getString("my_id", "") ?: "")
            .put("ma_cle_publique", prefs.getString("my_pub", "") ?: "")
            .put("ma_cle_privee", prefs.getString("my_priv", "") ?: "")
            .put("mon_nom", prefs.getString("my_name", "") ?: "")
            .put("contacts", contacts)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun derive(motDePasse: CharArray, sel: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(motDePasse, sel, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * Crée la sauvegarde chiffrée, en texte Base64 à copier ou à mettre en QR code.
     * Lève une exception si le mot de passe est trop court.
     */
    fun creer(context: Context, motDePasse: String): String {
        require(motDePasse.length >= MIN_PASSWORD_LENGTH) { "Mot de passe trop court (12 caractères minimum)" }
        val random = SecureRandom()
        val sel = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cle = derive(motDePasse.toCharArray(), sel)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cle, GCMParameterSpec(GCM_TAG_BITS, iv))
        val chiffre = cipher.doFinal(contenuClair(context))
        return Base64.encodeToString(sel + iv + chiffre, Base64.NO_WRAP)
    }

    /**
     * Restaure le compte depuis une sauvegarde. Retourne true si le mot de passe est bon
     * et que le contenu est valide. Ne modifie rien si l'opération échoue.
     */
    fun restaurer(context: Context, sauvegarde: String, motDePasse: String): Boolean {
        return try {
            val brut = Base64.decode(sauvegarde.trim(), Base64.NO_WRAP)
            val sel = brut.copyOfRange(0, SALT_SIZE)
            val iv = brut.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            val chiffre = brut.copyOfRange(SALT_SIZE + IV_SIZE, brut.size)
            val cle = derive(motDePasse.toCharArray(), sel)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(GCM_TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(chiffre), Charsets.UTF_8))

            val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putString("my_id", json.getString("mon_id"))
            edit.putString("my_pub", json.getString("ma_cle_publique"))
            edit.putString("my_priv", json.getString("ma_cle_privee"))
            edit.putString("my_name", json.optString("mon_nom", ""))
            val contacts = json.optJSONArray("contacts") ?: JSONArray()
            for (i in 0 until contacts.length()) {
                val c = contacts.getJSONObject(i)
                edit.putString("peer_" + c.getString("id"), c.getString("cle"))
                edit.putString("name_" + c.getString("id"), c.optString("nom", ""))
            }
            edit.apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
