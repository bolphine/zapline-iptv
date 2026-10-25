package com.zapline.iptv.data

import com.zapline.iptv.model.Channel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Catch-up (timeshift) URLs: watching a programme that has already aired, from the provider's archive.
 *
 * Supports Xtream's /timeshift/ endpoint and the common M3U `catchup` modes (default, append, shift,
 * flussonic). Providers vary; if one doesn't play, the provider may not actually keep that channel.
 */
object Catchup {
    private val xtreamLive = Regex("^(https?://[^/]+(?:/[^/]+)*?)/live/([^/]+)/([^/]+)/(\\d+)\\.(\\w+)$")

    /** Whether [p] (start/end in ms) can be watched again on any of [channels]. */
    fun channelFor(channels: List<Channel>, start: Long, now: Long = System.currentTimeMillis()): Channel? =
        channels.firstOrNull { c ->
            c.catchupDays > 0 && start < now && start > now - c.catchupDays * 86_400_000L &&
                url(c, start, start + 60_000, null, now) != null
        }

    /** Every version of a channel that can replay [p], as one seekable, film-like player entry. */
    fun playEntry(key: String, name: String, variants: List<Channel>, p: Programme, serverTz: String?,
                  now: Long = System.currentTimeMillis()): com.zapline.iptv.player.PlayEntry? {
        val versions = variants.filter { it.catchupDays > 0 && p.start < now && p.start > now - it.catchupDays * 86_400_000L }
            .mapNotNull { c ->
                url(c, p.start, maxOf(p.end, p.start + 60_000), serverTz, now)?.let { u -> c.copy(name = "${p.title} · $name", url = u, catchupDays = 0) }
            }
        if (versions.isEmpty()) return null
        return com.zapline.iptv.player.PlayEntry("vod|catchup|$key|${p.start}", "${p.title} · $name", versions, null)
    }

    fun url(c: Channel, start: Long, end: Long, serverTz: String?, now: Long = System.currentTimeMillis()): String? {
        val type = c.catchupType ?: if (xtreamLive.matches(c.url)) "xc" else null
        val src = c.catchupSource
        return when (type) {
            "xc" -> xtream(c.url, start, end, serverTz) ?: src?.let { fill(it, start, end, now) }
            "append" -> src?.let { c.url + fill(it, start, end, now) }
            "default" -> when {
                src == null -> xtream(c.url, start, end, serverTz)
                src.startsWith("http") -> fill(src, start, end, now)
                else -> c.url + fill(src, start, end, now)
            }
            "shift", "timeshift" -> c.url + (if ('?' in c.url) "&" else "?") + "utc=${start / 1000}&lutc=${now / 1000}"
            "flussonic", "flussonic-hls", "fs" -> flussonic(c.url, start, end)
            else -> src?.let { if (it.startsWith("http")) fill(it, start, end, now) else c.url + fill(it, start, end, now) }
        }
    }

    private fun xtream(url: String, start: Long, end: Long, serverTz: String?): String? {
        val m = xtreamLive.find(url) ?: return null
        val (server, user, pass, id) = m.destructured
        val zone = try { serverTz?.let { ZoneId.of(it) } } catch (_: Exception) { null } ?: ZoneId.systemDefault()
        val startText = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(zone).format(Instant.ofEpochMilli(start))
        val minutes = ((end - start) / 60_000).coerceAtLeast(1)
        return "$server/timeshift/$user/$pass/$minutes/$startText/$id.ts"
    }

    private fun flussonic(url: String, start: Long, end: Long): String? {
        val dur = (end - start) / 1000
        val s = start / 1000
        return when {
            url.contains("/index.m3u8") -> url.replace("/index.m3u8", "/archive-$s-$dur.m3u8")
            url.contains("/mpegts") -> url.replace("/mpegts", "/timeshift_abs-$s.ts")
            url.endsWith(".m3u8") -> url.substringBeforeLast('/') + "/archive-$s-$dur.m3u8"
            else -> null
        }
    }

    /** Fills the placeholders providers use in catchup-source templates. */
    fun fill(t: String, start: Long, end: Long, now: Long): String {
        val z = ZoneId.systemDefault()
        val st = Instant.ofEpochMilli(start).atZone(z)
        val en = Instant.ofEpochMilli(end).atZone(z)
        var r = t
        val sec = mapOf(
            "utc" to start / 1000, "start" to start / 1000, "utcend" to end / 1000, "end" to end / 1000,
            "lutc" to now / 1000, "now" to now / 1000, "timestamp" to now / 1000,
            "duration" to (end - start) / 1000, "offset" to (now - start) / 1000,
        )
        // {duration:60} = duration in minutes etc.
        r = Regex("\\{duration:(\\d+)}").replace(r) { m -> ((end - start) / 1000 / m.groupValues[1].toLong().coerceAtLeast(1)).toString() }
        for ((k, v) in sec) { r = r.replace("{$k}", v.toString()).replace("\${$k}", v.toString()) }
        val parts = mapOf("Y" to "%04d".format(st.year), "m" to "%02d".format(st.monthValue), "d" to "%02d".format(st.dayOfMonth),
            "H" to "%02d".format(st.hour), "M" to "%02d".format(st.minute), "S" to "%02d".format(st.second))
        for ((k, v) in parts) r = r.replace("{$k}", v)
        // {utc:Y-m-d-H-M-S} style
        r = Regex("\\{(utc|start|utcend|end):([^}]+)}").replace(r) { m ->
            val at = if (m.groupValues[1] in setOf("utc", "start")) st else en
            m.groupValues[2].replace("Y", "%04d".format(at.year)).replace("m", "%02d".format(at.monthValue))
                .replace("d", "%02d".format(at.dayOfMonth)).replace("H", "%02d".format(at.hour))
                .replace("M", "%02d".format(at.minute)).replace("S", "%02d".format(at.second))
        }
        return r
    }
}
