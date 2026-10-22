package com.zapline.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A playlist the viewer has used, kept so they can switch between providers without retyping logins. */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val type: String,
    val m3uUrl: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val format: String = "ts",
    val fileUri: String = "",
    val fileName: String = "",
    val epgUrl: String = "",
    val loadVod: Boolean = true,
) {
    val summary: String get() = when (type) {
        "xtream" -> "Xtream · ${host(server)} · $user"
        "m3u" -> "M3U · ${host(m3uUrl)}"
        "file" -> "File · $fileName"
        else -> type
    }

    private fun host(u: String) = try { java.net.URI(if ("://" in u) u.trim() else "http://${u.trim()}").host ?: u } catch (_: Exception) { u }

    fun sameSource(o: SavedPlaylist) = type == o.type && m3uUrl == o.m3uUrl && server == o.server && user == o.user && fileUri == o.fileUri

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("m3uUrl", m3uUrl)
        .put("server", server).put("user", user).put("pass", pass).put("format", format)
        .put("fileUri", fileUri).put("fileName", fileName).put("epgUrl", epgUrl).put("loadVod", loadVod)

    companion object {
        fun fromJson(o: JSONObject) = SavedPlaylist(
            o.optString("id"), o.optString("name"), o.optString("type"), o.optString("m3uUrl"), o.optString("server"),
            o.optString("user"), o.optString("pass"), o.optString("format", "ts"), o.optString("fileUri"),
            o.optString("fileName"), o.optString("epgUrl"), o.optBoolean("loadVod", true),
        )
    }
}

object Playlists {
    fun all(p: Prefs): List<SavedPlaylist> = try {
        val a = JSONArray(p.playlistsJson)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SavedPlaylist::fromJson) }
    } catch (_: Exception) { emptyList() }

    private fun store(p: Prefs, list: List<SavedPlaylist>) {
        p.playlistsJson = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }

    fun current(p: Prefs, name: String? = null): SavedPlaylist? {
        if (p.sourceType.isEmpty()) return null
        val base = SavedPlaylist(
            id = p.activePlaylist.ifEmpty { java.util.UUID.randomUUID().toString().take(8) },
            name = "", type = p.sourceType, m3uUrl = p.m3uUrl, server = p.xtServer, user = p.xtUser, pass = p.xtPass,
            format = p.xtFormat, fileUri = p.fileUri, fileName = p.fileName, epgUrl = p.epgUrl, loadVod = p.loadVod,
        )
        val existing = all(p).firstOrNull { it.id == base.id } ?: all(p).firstOrNull { it.sameSource(base) }
        return base.copy(id = existing?.id ?: base.id, name = name?.takeIf { it.isNotBlank() } ?: existing?.name ?: base.summary.substringAfter(" · "))
    }

    /** Adds or updates the active source in the saved list. */
    fun saveCurrent(p: Prefs, name: String? = null) {
        val cur = current(p, name) ?: return
        val list = all(p)
        store(p, if (list.any { it.id == cur.id }) list.map { if (it.id == cur.id) cur else it } else list + cur)
        p.activePlaylist = cur.id
    }

    fun apply(p: Prefs, s: SavedPlaylist) {
        p.sourceType = s.type; p.m3uUrl = s.m3uUrl; p.xtServer = s.server; p.xtUser = s.user; p.xtPass = s.pass
        p.xtFormat = s.format; p.fileUri = s.fileUri; p.fileName = s.fileName; p.epgUrl = s.epgUrl; p.loadVod = s.loadVod
        p.playlistEpgUrl = ""; p.xtTimezone = ""
        p.activePlaylist = s.id
    }

    fun rename(p: Prefs, s: SavedPlaylist, name: String) =
        store(p, all(p).map { if (it.id == s.id) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    fun delete(p: Prefs, s: SavedPlaylist) = store(p, all(p).filterNot { it.id == s.id })
}

/** Everything the viewer has set up, as one JSON file they can keep and restore (includes logins). */
object Backup {
    private const val FORMAT = "zapline-backup"
    /** Backups from Clearview, this app's earlier name, restore too. */
    private val READABLE = setOf(FORMAT, "clearview-backup")
    /** Device-specific or bulky keys that shouldn't travel between phones. */
    private val skip = setOf("fileUri")

    fun export(ctx: Context): String {
        val prefs = Prefs(ctx).p.all
        val o = JSONObject()
        for ((k, v) in prefs) {
            if (k in skip) continue
            o.put(k, when (v) {
                is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                is Int -> JSONObject().put("int", v)
                is Long -> JSONObject().put("long", v)
                is Float -> JSONObject().put("float", v.toDouble())
                is Boolean -> JSONObject().put("bool", v)
                else -> JSONObject().put("str", v.toString())
            })
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("prefs", o).toString(2)
    }

    /** Returns the number of settings restored; throws IllegalArgumentException for a file that isn't a backup. */
    fun import(ctx: Context, text: String): Int {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("That file isn't a Zapline backup") }
        if (root.optString("format") !in READABLE) throw IllegalArgumentException("That file isn't a Zapline backup")
        val o = root.optJSONObject("prefs") ?: throw IllegalArgumentException("The backup is empty")
        val ed = Prefs(ctx).p.edit()
        var n = 0
        for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            when {
                v.has("set") -> { val a = v.getJSONArray("set"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                v.has("int") -> ed.putInt(k, v.getInt("int"))
                v.has("long") -> ed.putLong(k, v.getLong("long"))
                v.has("float") -> ed.putFloat(k, v.getDouble("float").toFloat())
                v.has("bool") -> ed.putBoolean(k, v.getBoolean("bool"))
                v.has("str") -> ed.putString(k, v.getString("str"))
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }
}
