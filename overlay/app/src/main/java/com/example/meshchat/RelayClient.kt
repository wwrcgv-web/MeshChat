package com.example.meshchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RelayClient {
    const val BASE_URL = "https://samchat-relais.wwrcgv.workers.dev"
    private const val TIMEOUT_MS = 10_000
    private const val PREFS = "meshchat"
    /** Drapeau du mode « Avec Internet ». Vrai par défaut, comme avant. */
    private const val KEY_ENABLED = "relay_on"

    /** Raison du dernier échec d'envoi, affichée dans le journal pour le diagnostic. */
    @Volatile var lastError: String = ""

    /** Code HTTP de la dernière relève : 0 = pas encore de réponse, -1 = connexion impossible. */
    @Volatile var lastReleveCode: Int = 0

    /** Interrupteur « Avec Internet » : persisté, et respecté par l'envoi comme par la relève. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on)
            .apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveToken(context: Context, token: String) {
    }

    @Suppress("UNUSED_PARAMETER")
    private fun token(context: Context): String = BuildConfig.RELAY_TOKEN

    fun deposer(context: Context, destinataireHache: String, contenuChiffre: String): Boolean {
        if (token(context).isEmpty()) {
            lastError = "jeton absent dans l'app (secret RELAY_TOKEN)"
            return false
        }
        val corps = JSONObject()
            .put("destinataire", destinataireHache)
            .put("contenu_chiffre", contenuChiffre)
            .toString()
        val code = requete(context, "POST", "/deposer", corps)
        if (code != 201) {
            if (code > 0) lastError = "le serveur a répondu $code"
            return false
        }
        return true
    }

    fun relever(context: Context, destinataireHache: String): List<String> {
        if (token(context).isEmpty()) {
            lastError = "jeton absent dans l'app (secret RELAY_TOKEN)"
            return emptyList()
        }
        val query = "?destinataire=" + URLEncoder.encode(destinataireHache, "UTF-8")
        val reponse = lireReponse(context, "GET", "/relever$query", null)
        if (reponse == null) {
            lastError = when {
                lastReleveCode > 0 -> "relève refusée par le relais (HTTP $lastReleveCode)"
                lastReleveCode < 0 -> "relève impossible (réseau)"
                else -> "relève impossible (pas de réponse)"
            }
            return emptyList()
        }
        return try {
            val tableau: JSONArray = JSONObject(reponse).getJSONArray("messages")
            (0 until tableau.length()).map { tableau.getJSONObject(it).getString("contenu_chiffre") }
        } catch (e: Exception) {
            lastError = "réponse du relais illisible"
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
            lastError = "réseau : ${e.javaClass.simpleName}"
            -1
        } finally {
            connexion.disconnect()
        }
    }

    private fun lireReponse(context: Context, methode: String, chemin: String, corps: String?): String? {
        val connexion = ouvrir(context, methode, chemin) ?: run {
            lastReleveCode = -1
            return null
        }
        return try {
            if (corps != null) {
                connexion.doOutput = true
                connexion.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            val code = connexion.responseCode
            lastReleveCode = code
            if (code != 200) null
            else connexion.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            lastReleveCode = -1
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
            lastError = "connexion impossible"
            null
        }
    }
}
