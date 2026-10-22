package com.zapline.iptv.data

import android.content.Context
import org.json.JSONObject

/** Small persisted settings: playlist source, filters, favourites, overrides, rules. */
class Prefs(context: Context) {
    internal val p = context.getSharedPreferences("zapline", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()

    /** "xtream", "m3u", "file" or "" when not set up. */
    var sourceType: String get() = str("sourceType"); set(v) = put("sourceType", v)
    var m3uUrl: String get() = str("m3uUrl"); set(v) = put("m3uUrl", v)
    var fileUri: String get() = str("fileUri"); set(v) = put("fileUri", v)
    var fileName: String get() = str("fileName"); set(v) = put("fileName", v)
    var xtServer: String get() = str("xtServer"); set(v) = put("xtServer", v)
    var xtUser: String get() = str("xtUser"); set(v) = put("xtUser", v)
    var xtPass: String get() = str("xtPass"); set(v) = put("xtPass", v)
    /** Xtream server timezone, used to address catch-up recordings. */
    var xtTimezone: String get() = str("xtTimezone"); set(v) = put("xtTimezone", v)
    /** Player identity (user agent) that a provider accepted after refusing the default one, by host. */
    fun userAgentFor(host: String): String? = p.getString("ua:$host", null)
    fun setUserAgentFor(host: String, ua: String?) = p.edit().apply { if (ua == null) remove("ua:$host") else putString("ua:$host", ua) }.apply()

    /** Four-digit PIN guarding the Adult category; empty = no PIN. */
    var adultPin: String get() = str("adultPin"); set(v) = put("adultPin", v)
    /** Saved playlists (JSON array) and the id of the active one. */
    var playlistsJson: String get() = str("playlists", "[]"); set(v) = put("playlists", v)
    var activePlaylist: String get() = str("activePlaylist"); set(v) = put("activePlaylist", v)
    /** "ts" or "m3u8". */
    var xtFormat: String get() = str("xtFormat", "ts"); set(v) = put("xtFormat", v)
    var liveOnly: Boolean
        get() = p.getBoolean("liveOnly", true)
        set(v) = p.edit().putBoolean("liveOnly", v).apply()

    /** Selected languages; empty = all. Defaults to English. */
    var langFilter: Set<String>
        get() = p.getStringSet("langFilter", setOf("en"))!!.toSet()
        set(v) = p.edit().putStringSet("langFilter", HashSet(v)).apply()
    var favourites: Set<String>
        get() = p.getStringSet("favourites", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("favourites", HashSet(v)).apply()
    /** Favourites in the viewer's own order (stream URLs); position + 1 is the channel number. */
    var favOrder: List<String>
        get() = str("favOrder").split('\n').filter { it.isNotEmpty() }
        set(v) = put("favOrder", v.joinToString("\n"))
    var hiddenGroups: Set<String>
        get() = p.getStringSet("hiddenGroups", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("hiddenGroups", HashSet(v)).apply()
    var rules: String get() = str("rules"); set(v) = put("rules", v)

    /** TV guide (XMLTV) URL set by the user; overrides the one from the provider. */
    var epgUrl: String get() = str("epgUrl"); set(v) = put("epgUrl", v)
    /** Guide URL(s) advertised in the M3U header (url-tvg / x-tvg-url), comma separated. */
    var playlistEpgUrl: String get() = str("playlistEpgUrl"); set(v) = put("playlistEpgUrl", v)

    /** Accent colour (ARGB). Defaults to amber. */
    var accent: Int
        get() = p.getInt("accent", 0xFFFFB547.toInt())
        set(v) = p.edit().putInt("accent", v).apply()

    // ---- playback defaults ----

    /** Show subtitles / closed captions when a stream has them. Off by default. */
    var subtitlesOn: Boolean
        get() = p.getBoolean("subtitlesOn", false)
        set(v) = p.edit().putBoolean("subtitlesOn", v).apply()

    /** Subtitle text size multiplier (0.8 small, 1.0 normal, 1.3 large, 1.6 extra large). */
    var subtitleScale: Float
        get() = p.getFloat("subtitleScale", 1f)
        set(v) = p.edit().putFloat("subtitleScale", v).apply()

    /** Preferred audio language for multi-audio streams ("" = the stream's default). */
    var audioLang: String get() = str("audioLang", "en"); set(v) = put("audioLang", v)

    /** Picture fit: 0 fit, 1 zoom (crop to fill), 2 stretch. */
    /** Blurred colours from the picture fill the empty space around it. On by default. */
    var ambient: Boolean
        get() = p.getBoolean("ambient", true)
        set(v) = p.edit().putBoolean("ambient", v).apply()
    /** Series progress (JSON), see SeriesWatch. */
    var seriesJson: String get() = str("series", "{}"); set(v) = put("series", v)
    /** Check channels in the background (Wi-Fi only). */
    var autoCheck: Boolean
        get() = p.getBoolean("autoCheck", true)
        set(v) = p.edit().putBoolean("autoCheck", v).apply()
    /** Player screen rotation: 0 follows the phone, 1 locked landscape, 2 locked portrait. */
    var playerOrientation: Int
        get() = p.getInt("playerOrientation", 0)
        set(v) = p.edit().putInt("playerOrientation", v).apply()
    var resizeMode: Int
        get() = p.getInt("resizeMode", 0)
        set(v) = p.edit().putInt("resizeMode", v).apply()

    /** Data saver: 0 off, 1 on mobile data (default), 2 always. */
    var dataSaver: Int
        get() = p.getInt("dataSaver", 1)
        set(v) = p.edit().putInt("dataSaver", v).apply()

    /** Swipe on the left edge for brightness, right edge for volume. */
    var gestures: Boolean
        get() = p.getBoolean("gestures", true)
        set(v) = p.edit().putBoolean("gestures", v).apply()

    /** Load movies and series into their own sections. */
    var loadVod: Boolean
        get() = p.getBoolean("loadVod", true)
        set(v) = p.edit().putBoolean("loadVod", v).apply()

    /** Resume points for movies/episodes: url -> [positionMs, durationMs, savedAt], newest kept. */
    private fun resumeJson(): JSONObject = try { JSONObject(str("resume", "{}")) } catch (e: Exception) { JSONObject() }

    fun resumePosition(url: String): Long =
        resumeJson().optJSONArray(url)?.optLong(0, 0L) ?: 0L

    /** Saves progress; finished (or barely started) items are dropped so they restart from the beginning. */
    fun saveResume(url: String, positionMs: Long, durationMs: Long) {
        val o = resumeJson()
        if (durationMs <= 0 || positionMs < 30_000 || positionMs > durationMs - 90_000) o.remove(url)
        else o.put(url, org.json.JSONArray().put(positionMs).put(durationMs).put(System.currentTimeMillis()))
        // keep the newest 300
        if (o.length() > 300) {
            val oldest = o.keys().asSequence().sortedBy { o.optJSONArray(it)?.optLong(2) ?: 0 }.take(o.length() - 300).toList()
            oldest.forEach { o.remove(it) }
        }
        put("resume", o.toString())
    }

    /** url -> fraction watched, newest first. */
    fun resumeList(): List<Pair<String, Float>> {
        val o = resumeJson()
        return o.keys().asSequence().mapNotNull { k ->
            val a = o.optJSONArray(k) ?: return@mapNotNull null
            Triple(k, a.optLong(0).toFloat() / a.optLong(1).coerceAtLeast(1), a.optLong(2))
        }.sortedByDescending { it.third }.map { it.first to it.second }.toList()
    }

    /** Recently played entry keys, newest first. */
    var recents: List<String>
        get() = str("recents").split('\n').filter { it.isNotEmpty() }
        set(v) = put("recents", v.joinToString("\n"))

    fun addRecent(key: String) {
        recents = (listOf(key) + recents.filter { it != key }).take(30)
    }

    /** Selected countries; empty = all. */
    var regionFilter: Set<String>
        get() = p.getStringSet("regionFilter", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("regionFilter", HashSet(v)).apply()

    /** Per-group country overrides; value "" forces Unknown. */
    var groupRegion: Map<String, String>
        get() = jsonMap("groupRegion")
        set(v) = put("groupRegion", JSONObject(v).toString())

    private fun jsonMap(key: String): Map<String, String> = try {
        val o = JSONObject(str(key, "{}"))
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    /** Per-group language overrides; value "" forces Unknown. */
    var groupLang: Map<String, String>
        get() = try {
            val o = JSONObject(str("groupLang", "{}"))
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        set(v) = put("groupLang", JSONObject(v).toString())
}
