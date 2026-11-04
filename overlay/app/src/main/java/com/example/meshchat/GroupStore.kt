package com.example.meshchat

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Groupes de discussion. Chaque personne peut créer les siens et en est alors l'administrateur.
 * Seul l'administrateur change le nom ou la liste des membres. Une version qui ne fait que monter
 * permet d'ignorer les anciennes invitations arrivées en retard.
 * Ce fichier ne fait que stocker : l'envoi et la réception sont dans BleMeshManager.
 */
object GroupStore {
    const val NAME_MAX_CHARS = 24
    const val MAX_MEMBERS = 20
    const val AVATAR_MAX_BYTES = 8_000

    private const val PREFS = "meshchat"
    private const val KEY_GROUPS = "groups_json"
    private const val PREFIX_AVATAR = "group_avatar_"

    /** [members] contient tous les membres, administrateur compris. */
    data class Group(
        val id: String,
        val name: String,
        val admin: String,
        val members: List<String>,
        val version: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Group> {
        val raw = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { toGroup(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): Group? = all(context).firstOrNull { it.id == id }

    /** Les membres à qui écrire : tous sauf moi. */
    fun recipients(group: Group, myId: String): List<String> = group.members.filter { it != myId }

    /** Crée un groupe dont je suis l'administrateur. Retourne null si le nom est vide ou s'il y a trop de membres. */
    @Synchronized
    fun create(context: Context, name: String, myId: String, others: List<String>): Group? {
        val clean = name.trim().take(NAME_MAX_CHARS)
        val members = (listOf(myId) + others).distinct()
        if (clean.isBlank() || members.size > MAX_MEMBERS) return null
        val group = Group(newId(), clean, myId, members, 1)
        save(context, all(context) + group)
        return group
    }

    /** Change le nom et les membres. Réservé à l'administrateur. Retourne le groupe mis à jour, ou null. */
    @Synchronized
    fun edit(context: Context, id: String, myId: String, name: String, others: List<String>): Group? {
        val current = get(context, id) ?: return null
        if (current.admin != myId) return null
        val clean = name.trim().take(NAME_MAX_CHARS)
        val members = (listOf(myId) + others).distinct()
        if (clean.isBlank() || members.size > MAX_MEMBERS) return null
        val updated = current.copy(name = clean, members = members, version = current.version + 1)
        save(context, all(context).map { if (it.id == id) updated else it })
        return updated
    }

    /**
     * Accepte un groupe reçu de son administrateur, s'il est plus récent que celui qu'on a.
     * Si je ne suis plus dans la liste, le groupe est retiré de ce téléphone.
     * Retourne true si quelque chose a changé.
     */
    @Synchronized
    fun accept(context: Context, group: Group, myId: String): Boolean {
        val current = get(context, group.id)
        if (current != null && group.version <= current.version) return false
        if (current != null && current.admin != group.admin) return false
        val rest = all(context).filter { it.id != group.id }
        if (myId !in group.members) {
            save(context, rest)
            return current != null
        }
        save(context, rest + group)
        return true
    }

    /** Quitte ou supprime le groupe sur ce téléphone seulement. */
    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
        prefs(context).edit().remove(PREFIX_AVATAR + id).apply()
    }

    // ---------- Photo du groupe ----------

    fun avatarOf(context: Context, id: String): ByteArray? =
        prefs(context).getString(PREFIX_AVATAR + id, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun setAvatar(context: Context, id: String, jpeg: ByteArray) {
        if (jpeg.isEmpty() || jpeg.size > AVATAR_MAX_BYTES) return
        prefs(context).edit().putString(PREFIX_AVATAR + id, Base64.encodeToString(jpeg, Base64.NO_WRAP)).apply()
    }

    // ---------- Invitation envoyée aux membres (avant chiffrement) ----------

    fun encodeInvite(group: Group): ByteArray = toJson(group).toString().toByteArray(Charsets.UTF_8)

    fun decodeInvite(bytes: ByteArray): Group? = try {
        val group = toGroup(JSONObject(String(bytes, Charsets.UTF_8)))
        if (group.id.length != 16 || group.name.isBlank() || group.admin !in group.members ||
            group.members.size > MAX_MEMBERS
        ) null else group
    } catch (e: Exception) {
        null
    }

    // ---------- Interne ----------

    private fun newId(): String =
        ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun save(context: Context, groups: List<Group>) {
        val array = JSONArray()
        groups.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_GROUPS, array.toString()).apply()
    }

    private fun toJson(g: Group) = JSONObject().apply {
        put("id", g.id)
        put("name", g.name)
        put("admin", g.admin)
        put("members", JSONArray(g.members))
        put("v", g.version)
    }

    private fun toGroup(o: JSONObject): Group {
        val array = o.getJSONArray("members")
        return Group(
            id = o.getString("id"),
            name = o.getString("name").take(NAME_MAX_CHARS),
            admin = o.getString("admin"),
            members = (0 until array.length()).map { array.getString(it) },
            version = o.getInt("v")
        )
    }
}
