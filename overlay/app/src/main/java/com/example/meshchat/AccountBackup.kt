package com.example.meshchat

import android.content.Context
import android.util.Base64
import com.example.meshchat.crypto.SecureKeys
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sauvegarde chiffrée du compte : identifiant, clés, contacts.
 * Chiffrement : PBKDF2 (mot de passe -> clé) puis AES-GCM.
 * Le jeton du relais n'est jamais inclus.
 */
object AccountBackup {
    const val MIN_PASSWORD_LENGTH = 12
    private const val ITERATIONS = 210_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val MAX_CONTACTS = 500
    private const val MAX_NAME_CHARS = 20

    private val HEX_ID = Regex("[0-9a-f]{16}")
    private val HEX_ANY = Regex("[0-9a-f]+")

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && matches(HEX_ANY)) { "hex invalide" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Construit le contenu clair de la sauvegarde (JSON). La clé privée vient de l'identité protégée. */
    private fun contenuClair(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        val pair = LocalIdentity.myKeyPair(context)
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
            .put("mon_id", LocalIdentity.myIdHex(context))
            .put("ma_cle_publique", pair.public.encoded.toHex())
            .put("ma_cle_privee", pair.private.encoded.toHex())
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
     * et que tout le contenu est valide. Rien n'est modifié tant que tout n'a pas été vérifié.
     * Après une restauration réussie, l'application doit être relancée pour utiliser la nouvelle identité.
     */
    fun restaurer(context: Context, sauvegarde: String, motDePasse: String): Boolean {
        return try {
            val brut = Base64.decode(sauvegarde.trim(), Base64.NO_WRAP)
            if (brut.size < SALT_SIZE + IV_SIZE + 16) return false
            val sel = brut.copyOfRange(0, SALT_SIZE)
            val iv = brut.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            val chiffre = brut.copyOfRange(SALT_SIZE + IV_SIZE, brut.size)
            val cle = derive(motDePasse.toCharArray(), sel)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, cle, GCMParameterSpec(GCM_TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(chiffre), Charsets.UTF_8))

            // Vérification complète avant la moindre écriture
            val monId = json.getString("mon_id")
            if (!HEX_ID.matches(monId)) return false
            val pubHex = json.getString("ma_cle_publique")
            val privBytes = json.getString("ma_cle_privee").hexToBytes()
            val factory = KeyFactory.getInstance("EC")
            factory.generatePublic(X509EncodedKeySpec(pubHex.hexToBytes()))
            factory.generatePrivate(PKCS8EncodedKeySpec(privBytes))

            val contacts = json.optJSONArray("contacts") ?: JSONArray()
            if (contacts.length() > MAX_CONTACTS) return false
            val valides = ArrayList<Triple<String, String, String>>()
            for (i in 0 until contacts.length()) {
                val c = contacts.getJSONObject(i)
                val id = c.getString("id")
                val cleHex = c.getString("cle")
                if (!HEX_ID.matches(id)) return false
                factory.generatePublic(X509EncodedKeySpec(cleHex.hexToBytes()))
                valides.add(Triple(id, cleHex, c.optString("nom", "").take(MAX_NAME_CHARS)))
            }

            // Tout est valide : la clé privée est écrite protégée, le reste dans les préférences
            SecureKeys.savePair(context, pubHex, privBytes)
            val edit = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE).edit()
            edit.putString("my_id", monId)
            edit.putString("my_name", json.optString("mon_nom", "").take(MAX_NAME_CHARS))
            for ((id, cleHex, nom) in valides) {
                edit.putString("peer_$id", cleHex)
                edit.putString("name_$id", nom)
            }
            edit.commit()
            LocalIdentity.invalidateCache()
            true
        } catch (e: Exception) {
            false
        }
    }
}
