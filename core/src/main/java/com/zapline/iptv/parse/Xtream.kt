package com.zapline.iptv.parse

import com.zapline.iptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder

/** Xtream Codes helpers: server URL handling and mapping the player_api JSON to channels. */
object Xtream {

    data class Creds(val server: String, val user: String, val pass: String, val format: String = "ts")

    /** Accepts "host:port", "http://host:port/", ".../player_api.php" etc. */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (!s.contains("://")) s = "http://$s"
        s = s.substringBefore('?').substringBefore('#').trimEnd('/')
        for (suffix in listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php")) {
            if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
        }
        return s.trimEnd('/')
    }

    /** Recognises an Xtream "get.php?username=..&password=.." M3U link so we can use the API instead. */
    fun fromGetPhp(url: String): Creds? {
        val u = try { URI(url.trim()) } catch (e: Exception) { return null }
        val path = u.rawPath ?: return null
        if (!path.endsWith("get.php", ignoreCase = true)) return null
        val q = (u.rawQuery ?: return null).split('&').associate {
            val k = it.substringBefore('=').lowercase()
            val v = try { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") } catch (e: Exception) { it.substringAfter('=', "") }
            k to v
        }
        val user = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val format = when (q["output"]?.lowercase()) { "m3u8", "hls" -> "m3u8"; else -> "ts" }
        val base = "${u.scheme}://${u.rawAuthority}${path.dropLast("get.php".length)}".trimEnd('/')
        return Creds(base, user, pass, format)
    }

    fun apiUrl(c: Creds, action: String?): String {
        val base = "${normalizeServer(c.server)}/player_api.php?username=${enc(c.user)}&password=${enc(c.pass)}"
        return if (action == null) base else "$base&action=$action"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** The server's timezone from player_api.php (catch-up start times are given in it). */
    fun serverTimezone(json: String): String? = try {
        JSONObject(json.trim()).optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** Throws an IOException with a readable message if the server rejected the login. */
    fun checkCategoriesResponse(body: String) {
        val t = body.trimStart()
        if (t.startsWith("[")) return
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            val info = o.optJSONObject("user_info")
            if (info != null && info.optInt("auth", 1) == 0) throw IOException("Login rejected by the server - check username and password")
            if (info != null && info.optString("status").equals("Expired", true)) throw IOException("This account has expired")
        }
        if (t.isEmpty()) throw IOException("Login rejected by the server - check username and password")
        throw IOException("The server didn't return channel data (is this an Xtream server?)")
    }

    // ---- streaming list readers ----
    // Xtream catalogues (especially movies) can be tens of MB. Reading them into one String and a JSON tree
    // exhausts a phone's memory, so lists are read one object at a time and only the needed fields are kept.

    /** Calls [block] with the flat fields of each object in a top-level JSON array. Non-array bodies yield nothing. */
    internal fun forEachObject(reader: Reader, block: (Map<String, String>) -> Unit) {
        val jr = JsonReader(reader)
        jr.isLenient = true
        val first = try { jr.peek() } catch (e: java.io.EOFException) { return }   // empty body
        if (first != JsonToken.BEGIN_ARRAY) return   // {} / null / error object: treat as empty
        jr.beginArray()
        val fields = HashMap<String, String>(24)
        while (jr.hasNext()) {
            if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
            fields.clear()
            jr.beginObject()
            while (jr.hasNext()) {
                val key = jr.nextName()
                when (jr.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> fields[key] = jr.nextString()
                    JsonToken.BOOLEAN -> fields[key] = jr.nextBoolean().toString()
                    JsonToken.NULL -> jr.nextNull()
                    JsonToken.BEGIN_ARRAY -> if (key == "category_ids") {
                        val ids = ArrayList<String>(2)
                        jr.beginArray()
                        while (jr.hasNext()) {
                            when (jr.peek()) {
                                JsonToken.STRING, JsonToken.NUMBER -> ids += jr.nextString()
                                else -> jr.skipValue()
                            }
                        }
                        jr.endArray()
                        fields[key] = ids.joinToString(",")
                    } else jr.skipValue()
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            block(fields)
        }
        jr.endArray()
    }

    private fun Map<String, String>.text(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** Ids may arrive as 12, "12" or 12.0. */
    private fun Map<String, String>.id(key: String): String? =
        text(key)?.removeSuffix(".0")

    private fun Map<String, String>.category(cats: Map<String, String>): String {
        id("category_id")?.let { cats[it]?.let { c -> return c } }
        text("category_ids")?.split(',')?.forEach { cats[it.trim().removeSuffix(".0")]?.let { c -> return c } }
        return "Ungrouped"
    }

    private val spaces = Regex("\\s+")

    fun categories(reader: Reader): Map<String, String> {
        val cats = HashMap<String, String>()
        forEachObject(reader) { o -> o.id("category_id")?.let { cats[it] = o.text("category_name") ?: "Ungrouped" } }
        return cats
    }

    fun mapStreams(cats: Map<String, String>, streams: Reader, c: Creds): List<Channel> {
        val server = normalizeServer(c.server)
        val out = ArrayList<Channel>(4096)
        forEachObject(streams) { o ->
            val id = o.id("stream_id") ?: return@forEachObject
            val name = o.text("name")?.replace(spaces, " ") ?: return@forEachObject
            out += Channel(
                name = name,
                url = "$server/live/${c.user}/${c.pass}/$id.${c.format}",
                group = o.category(cats),
                logo = o.text("stream_icon"),
                tvgId = o.text("epg_channel_id"),
                catchupDays = if (o.text("tv_archive") == "1") o.text("tv_archive_duration")?.toIntOrNull() ?: 1 else 0,
                catchupType = if (o.text("tv_archive") == "1") "xc" else null,
            )
        }
        return out
    }

    /** Movies: get_vod_categories + get_vod_streams. */
    fun mapMovies(cats: Map<String, String>, streams: Reader, c: Creds): List<Channel> {
        val server = normalizeServer(c.server)
        val out = ArrayList<Channel>(4096)
        forEachObject(streams) { o ->
            val id = o.id("stream_id") ?: return@forEachObject
            val name = o.text("name")?.replace(spaces, " ") ?: return@forEachObject
            val ext = o.text("container_extension") ?: "mp4"
            out += Channel(
                name = name,
                url = "$server/movie/${c.user}/${c.pass}/$id.$ext",
                group = o.category(cats),
                logo = o.text("stream_icon") ?: o.text("cover"),
            )
        }
        return out
    }

    /** Series list: get_series_categories + get_series. Episodes are fetched per series with get_series_info. */
    fun mapSeries(cats: Map<String, String>, series: Reader): List<Channel> {
        val out = ArrayList<Channel>(2048)
        forEachObject(series) { o ->
            val id = o.id("series_id") ?: return@forEachObject
            val name = o.text("name")?.replace(spaces, " ") ?: return@forEachObject
            out += Channel(name = name, url = "$SERIES_SCHEME$id", group = o.category(cats), logo = o.text("cover"))
        }
        return out
    }

    // String conveniences (tests, small responses).
    fun mapStreams(categoriesJson: String, streamsJson: String, c: Creds) =
        mapStreams(categories(StringReader(categoriesJson)), StringReader(streamsJson), c)
    fun mapMovies(categoriesJson: String, streamsJson: String, c: Creds) =
        mapMovies(categories(StringReader(categoriesJson)), StringReader(streamsJson), c)
    fun mapSeries(categoriesJson: String, seriesJson: String) =
        mapSeries(categories(StringReader(categoriesJson)), StringReader(seriesJson))

    const val SERIES_SCHEME = "xtseries:"

    data class Episode(val season: Int, val number: Int, val title: String, val url: String, val image: String?, val plot: String?)
    data class SeriesInfo(val plot: String?, val episodes: List<Episode>)

    /** get_series_info: "episodes" is usually {"1":[...],"2":[...]}, on some panels a list of lists. */
    fun parseSeriesInfo(json: String, c: Creds): SeriesInfo {
        val server = normalizeServer(c.server)
        val root = JSONObject(json.trim())
        val plot = root.optJSONObject("info")?.str("plot")
        val raw = ArrayList<JSONObject>()
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(raw::add) } }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val x = eps.opt(i)) {
                    is JSONArray -> for (j in 0 until x.length()) x.optJSONObject(j)?.let(raw::add)
                    is JSONObject -> raw += x
                }
            }
        }
        val out = raw.mapNotNull { o ->
            val id = o.idString("id") ?: return@mapNotNull null
            val ext = o.str("container_extension") ?: "mp4"
            val info = o.optJSONObject("info")
            Episode(
                season = o.optInt("season", 0),
                number = o.optInt("episode_num", 0),
                title = o.str("title") ?: "Episode ${o.optInt("episode_num", 0)}",
                url = "$server/series/${c.user}/${c.pass}/$id.$ext",
                image = info?.str("movie_image"),
                plot = info?.str("plot"),
            )
        }.sortedWith(compareBy({ it.season }, { it.number }))
        return SeriesInfo(plot, out)
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.idString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong().toString()
            else -> v?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
