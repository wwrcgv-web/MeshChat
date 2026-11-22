package com.example.meshchat

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Client du relais Internet.
 *
 * Deux façons de s'authentifier auprès du Worker :
 * - la signature d'appareil (clé du Keystore) pour relire sa boîte et confirmer les messages lus ;
 * - le jeton de compilation, encore obligatoire pour déposer, et utilisé en secours si la
 *   signature n'est pas disponible (téléphone sans ECDSA, appareil non encore inscrit).
 */
object RelayClient {
    const val BASE_URL = "https://samchat-relais.wwrcgv.workers.dev"
    private const val TIMEOUT_MS = 10_000
    private const val PREFS = "meshchat"
    /** Drapeau du mode « Avec Internet ». Vrai par défaut. */
    private const val KEY_ENABLED = "relay_on"
    private const val KEY_DEVICE_ID = "relay_device_id"
    private const val KEY_BOITE = "relay_boite"

    /** Raison du dernier échec, affichée dans le journal pour le diagnostic. */
    @Volatile var lastError: String = ""

    /** Code HTTP de la dernière relève : 0 = pas encore de réponse, -1 = connexion impossible. */
    @Volatile var lastReleveCode: Int = 0

    /** Un message relevé : son identifiant en base et son contenu chiffré. */
    data class Message(val id: String, val contenu: String)

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

    private fun token(context: Context): String = BuildConfig.RELAY_TOKEN

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    /** Boîte de ce téléphone, dérivée de la clé d'identité (même formule que le Worker). */
    fun maBoite(context: Context): String =
        RelayDispatch.boiteDe(LocalIdentity.myPublicHex(context))

    /**
     * Inscrit cet appareil auprès du relais, une seule fois. Retourne vrai si l'inscription
     * est faite (ou déjà faite). En cas d'échec, l'app continue avec le jeton.
     */
    fun assurerEnregistrement(context: Context): Boolean {
        if (deviceId(context) != null) return true

        val pubIdentite = LocalIdentity.myPublicHex(context)
        val pubAppareil = DeviceKey.publicKeyHex() ?: run {
            lastError = "clé d'appareil indisponible"
            return false
        }
        val boite = maBoite(context)
        val horodatage = System.currentTimeMillis() / 1000
        val charge = "$boite\n$pubAppareil\n$horodatage"
        val signature = LocalIdentity.signerAvecIdentite(context, charge.toByteArray(Charsets.UTF_8))
            ?: run {
                lastError = "signature d'identité impossible"
                return false
            }

        val corps = JSONObject()
            .put("cle_identite", pubIdentite)
            .put("cle_publique", pubAppareil)
            .put("boite", boite)
            .put("horodatage", horodatage)
            .put("signature_identite", Base64.encodeToString(signature, Base64.NO_WRAP))
            .toString()

        val reponse = envoyer("POST", "/enregistrer", corps, emptyMap()) ?: return false
        if (reponse.first != 201) {
            lastError = "inscription refusée (HTTP ${reponse.first})"
            return false
        }
        val id = try {
            JSONObject(reponse.second).optString("device_id", "")
        } catch (e: Exception) {
            ""
        }
        if (id.isEmpty()) {
            lastError = "inscription sans identifiant d'appareil"
            return false
        }
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, id)
            .putString(KEY_BOITE, boite)
            .apply()
        return true
    }

    /** Dépose un contenu chiffré. Le jeton reste requis pendant la transition. */
    fun deposer(context: Context, destinataireHache: String, contenuChiffre: String): Boolean {
        if (token(context).isEmpty()) {
            lastError = "jeton absent dans l'app (secret RELAY_TOKEN)"
            return false
        }
        val corps = JSONObject()
            .put("destinataire", destinataireHache)
            .put("contenu_chiffre", contenuChiffre)
            .toString()
        // La signature est ajoutée quand elle existe : le Worker compte alors par appareil
        val entetes = entetesSignes("POST", "/deposer", corps)
        val reponse = envoyer("POST", "/deposer", corps, entetes) ?: return false
        return if (reponse.first == 201) {
            true
        } else {
            if (reponse.first > 0) lastError = "le serveur a répondu ${reponse.first}"
            false
        }
    }

    /**
     * Relève la boîte de ce téléphone. Signature d'appareil d'abord ; si elle échoue ou que
     * l'appareil n'est pas inscrit, repli sur le jeton (le Worker marque alors lu à la lecture).
     */
    fun relever(context: Context, boite: String): List<Message> {
        val entetes = entetesSignes("GET", "/relever", null)
        if (entetes.isNotEmpty()) {
            val r = envoyer("GET", "/relever", null, entetes)
            if (r != null && r.first == 200) {
                return lireMessages(r.second)
            }
            if (r != null && r.first != 401) {
                lastReleveCode = r.first
                lastError = when {
                    r.first > 0 -> "relève refusée (HTTP ${r.first})"
                    else -> "relève impossible (réseau)"
                }
                return emptyList()
            }
            // 401 : appareil inconnu du relais, on retombe sur le jeton
        }
        return releverAvecJeton(context, boite)
    }

    private fun releverAvecJeton(context: Context, boite: String): List<Message> {
        if (token(context).isEmpty()) {
            lastError = "jeton absent dans l'app (secret RELAY_TOKEN)"
            return emptyList()
        }
        val chemin = "/relever?destinataire=" + URLEncoder.encode(boite, "UTF-8")
        val r = envoyer("GET", chemin, null, emptyMap())
        if (r == null) {
            lastReleveCode = -1
            lastError = "relève impossible (réseau)"
            return emptyList()
        }
        lastReleveCode = r.first
        if (r.first != 200) {
            lastError = "relève refusée (HTTP ${r.first})"
            return emptyList()
        }
        return lireMessages(r.second)
    }

    private fun lireMessages(texte: String): List<Message> {
        return try {
            val tableau = JSONObject(texte).getJSONArray("messages")
            (0 until tableau.length()).mapNotNull { i ->
                val o = tableau.getJSONObject(i)
                val id = o.optString("id", "")
                val contenu = o.optString("contenu_chiffre", "")
                if (id.isEmpty() || contenu.isEmpty()) null else Message(id, contenu)
            }
        } catch (e: Exception) {
            lastError = "réponse du relais illisible"
            emptyList()
        }
    }

    /** Confirme la réception des messages traités : ils ne seront plus relivrés. */
    fun confirmer(context: Context, ids: List<String>): Boolean {
        if (ids.isEmpty()) return true
        val corps = JSONObject().put("ids", JSONArray(ids)).toString()
        val entetes = entetesSignes("POST", "/confirmer", corps)
        if (entetes.isEmpty()) return false
        val r = envoyer("POST", "/confirmer", corps, entetes) ?: return false
        if (r.first != 200) {
            lastError = "confirmation refusée (HTTP ${r.first})"
            return false
        }
        return true
    }

    /** En-têtes de signature d'appareil. Vide si l'appareil n'est pas inscrit. */
    private fun entetesSignes(methode: String, chemin: String, corps: String?): Map<String, String> {
        val context = appContext ?: return emptyMap()
        val id = deviceId(context) ?: return emptyMap()
        val horodatage = System.currentTimeMillis() / 1000
        val empreinte = sha256Hex(corps ?: "")
        val message = "$methode\n$chemin\n$empreinte\n$horodatage"
        val signature = DeviceKey.signRaw(message.toByteArray(Charsets.UTF_8)) ?: return emptyMap()
        return mapOf(
            "X-Device-Id" to id,
            "X-Timestamp" to horodatage.toString(),
            "X-Signature" to Base64.encodeToString(signature, Base64.NO_WRAP)
        )
    }

    /** Contexte d'application, nécessaire à la signature des requêtes. */
    @Volatile
    private var appContext: Context? = null

    /** À appeler une fois au démarrage (service ou écran). */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun sha256Hex(texte: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(texte.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun envoyer(
        methode: String,
        chemin: String,
        corps: String?,
        entetes: Map<String, String>
    ): Pair<Int, String>? {
        val connexion = ouvrir(methode, chemin, entetes) ?: return null
        return try {
            if (corps != null) {
                connexion.doOutput = true
                connexion.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            val code = connexion.responseCode
            val texte = if (code in 200..299) {
                connexion.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connexion.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            Pair(code, texte)
        } catch (e: Exception) {
            lastError = "réseau : ${e.javaClass.simpleName}"
            null
        } finally {
            connexion.disconnect()
        }
    }

    private fun ouvrir(methode: String, chemin: String, entetes: Map<String, String>): HttpURLConnection? {
        val context = appContext ?: return null
        return try {
            val connexion = URL(BASE_URL + chemin).openConnection() as HttpURLConnection
            connexion.requestMethod = methode
            connexion.connectTimeout = TIMEOUT_MS
            connexion.readTimeout = TIMEOUT_MS
            connexion.setRequestProperty("Content-Type", "application/json")
            if (token(context).isNotEmpty()) {
                connexion.setRequestProperty("X-App-Token", token(context))
            }
            entetes.forEach { (cle, valeur) -> connexion.setRequestProperty(cle, valeur) }
            connexion
        } catch (e: Exception) {
            lastError = "connexion impossible"
            null
        }
    }
}
