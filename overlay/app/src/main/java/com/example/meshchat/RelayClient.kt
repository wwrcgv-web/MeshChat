package com.example.meshchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client du relais Internet (Cloudflare Worker + Supabase).
 * Ne fait que déposer et relire des messages déjà chiffrés.
 * Désactivé par défaut : il ne sert que si l'utilisateur a activé « Internet » dans les Réglages.
 */
object RelayClient {
    const val BASE_URL = "https://samchat-relais.wwrcgv.workers.dev"
    private const val PREF_INTERNET = "internet_on"
    private const val PREF_TOKEN = "relay_token"
    private const val TIMEOUT_MS = 10_000

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("meshchat", Context.MODE_PRIVATE).getBoolean(PREF_INTERNET, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("meshchat", Context.MODE_PRIVATE).edit().putBoolean(PREF_INTERNET, on).apply()
    }

    /** Jeton saisi par l'utilisateur dans les Réglages. Jamais écrit dans le code. */
    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences("meshchat", Context.MODE_PRIVATE).edit().putString(PREF_TOKEN, token.trim()).apply()
    }

    private fun token(context: Context): String =
        context.getSharedPreferences("meshchat", Context.MODE_PRIVATE).getString(PREF_TOKEN, "") ?: ""

    /**
     * Dépose un message chiffré pour un destinataire (identifiant haché).
     * Retourne true si le relais a accepté le message.
     */
    fun deposer(context: Context, destinataireHache: String, contenuChiffre: String): Boolean {
        if (!isEnabled(context) || token(context).isEmpty()) return false
        val corps = JSONObject()
            .put("destinataire", destinataireHache)
            .put("contenu_chiffre", contenuChiffre)
            .toString()
        val code = requete(context, "POST", "/deposer", corps)
        return code == 201
    }

    /**
     * Relit les messages chiffrés déposés pour ce destinataire.
     * Le relais les supprime après lecture. Retourne une liste de contenus chiffrés.
     */
    fun relever(context: Context, destinataireHache: String): List<String> {
        if (!isEnabled(context) || token(context).isEmpty()) return emptyList()
        val query = "?destinataire=" + URLEncoder.encode(destinataireHache, "UTF-8")
        val reponse = lireReponse(context, "GET", "/relever$query", null) ?: return emptyList()
        return try {
            val tableau: JSONArray = JSONObject(reponse).getJSONArray("messages")
            (0 until tableau.length()).map { tableau.getJSONObject(it).getString("contenu_chiffre") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun requete(context: Context, methode: String, chemin: String, corps: String?): Int {
        val connexion = ouvrir(context, methode, chemin) ?: return -1
        return try {
            if (corps != null) {
                connexion.doOutput = true
                connexion.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            connexion.responseCode
        } catch (e: Exception) {
            -1
        } finally {
            connexion.disconnect()
        }
    }

    private fun lireReponse(context: Context, methode: String, chemin: String, corps: String?): String? {
        val connexion = ouvrir(context, methode, chemin) ?: return null
        return try {
            if (corps != null) {
                connexion.doOutput = true
                connexion.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            if (connexion.responseCode != 200) null
            else connexion.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connexion.disconnect()
        }
    }

    private fun ouvrir(context: Context, methode: String, chemin: String): HttpURLConnection? {
        return try {
            val connexion = URL(BASE_URL + chemin).openConnection() as HttpURLConnection
            connexion.requestMethod = methode
            connexion.connectTimeout = TIMEOUT_MS
            connexion.readTimeout = TIMEOUT_MS
            connexion.setRequestProperty("Content-Type", "application/json")
            connexion.setRequestProperty("X-App-Token", token(context))
            connexion
        } catch (e: Exception) {
            null
        }
    }
}
