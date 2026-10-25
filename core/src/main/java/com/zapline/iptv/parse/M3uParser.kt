package com.zapline.iptv.parse

import com.zapline.iptv.model.Channel
import java.io.BufferedReader
import java.net.URLDecoder

/**
 * Tolerant M3U / M3U8 playlist parser.
 *
 * Handles: quoted and unquoted attributes, commas inside quoted values, single quotes,
 * #EXTGRP groups, #EXTVLCOPT headers, Kodi-style "url|User-Agent=..&Referer=.." headers,
 * BOM / CRLF, entries without #EXTINF, and missing names (falls back to tvg-name).
 */
object M3uParser {

    data class Result(val channels: List<Channel>, val epgUrl: String?)

    fun parse(reader: BufferedReader, onProgress: ((Int) -> Unit)? = null): Result {
        val out = ArrayList<Channel>(4096)
        var epgUrl: String? = null

        var info: ExtInf? = null
        var extGroup: String? = null
        var vlcUa: String? = null
        var vlcRef: String? = null
        var vlcOrigin: String? = null
        var lineNo = 0

        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            if (onProgress != null && lineNo % 5000 == 0) onProgress(out.size)
            val line = raw.trim().removePrefix("﻿").trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attrs = parseAttributes(line.substring(7)).first
                        epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: epgUrl
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        info = parseExtInf(line)
                        extGroup = null; vlcUa = null; vlcRef = null; vlcOrigin = null
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        extGroup = line.substring(8).trim().ifEmpty { null }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substring(11)
                        val key = opt.substringBefore('=').trim().lowercase()
                        val value = opt.substringAfter('=', "").trim().ifEmpty { null }
                        when (key) {
                            "http-user-agent" -> vlcUa = value
                            "http-referrer", "http-referer" -> vlcRef = value
                            "http-origin" -> vlcOrigin = value
                        }
                    }
                }
                continue
            }

            // A URL line.
            val (url, pipeHeaders) = splitPipeHeaders(line)
            if (url.isEmpty()) { info = null; continue }
            val attrs = info?.attrs ?: emptyMap()
            val name = cleanName(info?.name)
                ?: cleanName(attrs["tvg-name"])
                ?: url.substringBefore('?').substringAfterLast('/').ifEmpty { url }
            val group = (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: attrs["tvg-group"]?.takeIf { it.isNotBlank() }
                ?: attrs["group"]?.takeIf { it.isNotBlank() } ?: attrs["category"]?.takeIf { it.isNotBlank() } ?: extGroup)
                ?.trim().takeUnless { it.isNullOrEmpty() } ?: "Ungrouped"

            out += Channel(
                name = name,
                url = url,
                group = group,
                logo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.isNotBlank() },
                tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                tvgLanguage = attrs["tvg-language"]?.takeIf { it.isNotBlank() },
                tvgCountry = attrs["tvg-country"]?.takeIf { it.isNotBlank() },
                userAgent = pipeHeaders["user-agent"] ?: vlcUa ?: attrs["user-agent"] ?: attrs["http-user-agent"],
                referrer = pipeHeaders["referer"] ?: pipeHeaders["referrer"] ?: vlcRef
                    ?: attrs["http-referrer"] ?: attrs["referrer"],
                origin = pipeHeaders["origin"] ?: vlcOrigin,
                catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.trim()?.toIntOrNull()
                    ?: if (attrs["catchup"] != null || attrs["catchup-source"] != null) 1 else 0,
                catchupType = attrs["catchup"]?.lowercase()?.takeIf { it.isNotBlank() },
                catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
            )
            info = null
            extGroup = null; vlcUa = null; vlcRef = null; vlcOrigin = null
        }
        onProgress?.invoke(out.size)
        return Result(out, epgUrl)
    }

    class ExtInf(val attrs: Map<String, String>, val name: String?)

    fun parseExtInf(line: String): ExtInf {
        var rest = line.substringAfter(':', "")
        // Skip the duration token ("-1", "0", "-1 " etc.).
        var i = 0
        while (i < rest.length && !rest[i].isWhitespace() && rest[i] != ',') i++
        rest = rest.substring(i)
        val (attrs, name) = parseAttributes(rest)
        return ExtInf(attrs, name)
    }

    /**
     * Parses `key="value" key2=value2,Display Name`. Returns the attributes (lower-case keys)
     * and the display name after the first comma that is outside quotes (null if none).
     */
    fun parseAttributes(s: String): Pair<Map<String, String>, String?> {
        val attrs = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == ',') return attrs to s.substring(i + 1)
            // Read a key.
            val keyStart = i
            while (i < n && s[i] != '=' && s[i] != ',' && !s[i].isWhitespace()) i++
            val key = s.substring(keyStart, i).lowercase()
            if (i < n && s[i] == '=') {
                i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val close = s.indexOf(q, i + 1)
                    if (close < 0) {
                        // Unterminated quote: take the rest, but still try to recover a name.
                        val value = s.substring(i + 1)
                        attrs[key] = value.substringBeforeLast(',').trim()
                        val name = if (value.contains(',')) value.substringAfterLast(',') else null
                        return attrs to name
                    }
                    attrs[key] = s.substring(i + 1, close).trim()
                    i = close + 1
                } else {
                    val vStart = i
                    while (i < n && s[i] != ',' && !s[i].isWhitespace()) i++
                    attrs[key] = s.substring(vStart, i).trim()
                }
            }
            // Bare tokens without '=' are ignored.
        }
        return attrs to null
    }

    private fun cleanName(s: String?): String? =
        s?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

    /** Splits Kodi-style `url|Header=v&Header2=v` into the URL and lower-case header map. */
    fun splitPipeHeaders(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        if (bar < 0) return line to emptyMap()
        val tail = line.substring(bar + 1)
        if (!tail.contains('=')) return line to emptyMap()
        val headers = HashMap<String, String>()
        for (part in tail.split('&')) {
            val k = part.substringBefore('=').trim().lowercase()
            val v = part.substringAfter('=', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                headers[k] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
            }
        }
        return line.substring(0, bar).trim() to headers
    }
}

/** Playlist hygiene shared by every source. */
object PlaylistFilters {
    private val leadingDecor = Regex("^\\s*[#=*_~★☆•●◉■▪▬━─—–\\-|<>✦✪◆♦]{2,}")
    private val decorRun = Regex("[#=*─━▬★]{3,}|-{4,}")

    /** Providers often insert fake "channels" like "##### UK SPORTS #####" as headings. */
    fun isSeparator(name: String): Boolean = leadingDecor.containsMatchIn(name) || decorRun.containsMatchIn(name)

    enum class Kind { LIVE, MOVIE, EPISODE }

    private val episodeRe = Regex("(?i)(?<![A-Z0-9])S(\\d{1,2})\\s*[ ._x-]?\\s*E(\\d{1,3})(?![0-9])")

    fun kindOf(c: Channel): Kind {
        val path = c.url.substringBefore('?').substringBefore('|').lowercase()
        if ("/series/" in path || episodeRe.containsMatchIn(c.name) && isVod(c.url)) return Kind.EPISODE
        if (isVod(c.url)) return Kind.MOVIE
        return Kind.LIVE
    }

    data class EpisodeName(val show: String, val season: Int, val number: Int)

    /** "Breaking Bad S01 E02" -> ("Breaking Bad", 1, 2). Without SxxExx the whole name is the show. */
    fun episodeName(name: String): EpisodeName {
        val m = episodeRe.find(name) ?: return EpisodeName(name.trim(), 0, 0)
        val show = name.substring(0, m.range.first).replace(Regex("[\\s\\-|:._(\\[]+$"), "").trim()
        return EpisodeName(show.ifEmpty { name.trim() }, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** Movies and series bundled into Xtream M3U exports. */
    fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('|').lowercase()
        return "/movie/" in path || "/series/" in path ||
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ||
            path.endsWith(".mov") || path.endsWith(".wmv") || path.endsWith(".m4v")
    }
}
