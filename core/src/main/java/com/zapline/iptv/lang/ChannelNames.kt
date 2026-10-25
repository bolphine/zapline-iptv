package com.zapline.iptv.lang

import java.text.Normalizer

/**
 * Turns provider channel names into something comparable and readable.
 *
 * "UK | BBC One FHD", "UK: BBC ONE HD ᴿᴬᵂ", "BBC One (UK) SD backup" all share the key "BBC ONE",
 * so they collapse into one entry with three versions.
 */
object ChannelNames {

    /** Quality / technical tags that don't make a different channel. "+1" (timeshift) is deliberately not here. */
    private val qualityTokens = setOf(
        "HD", "FHD", "UHD", "SD", "LQ", "HQ", "4K", "8K", "HEVC", "H265", "H264", "X265", "X264",
        "1080P", "1080I", "720P", "576P", "2160P", "50FPS", "60FPS", "25FPS", "FPS",
        "RAW", "BACKUP", "BAK", "ALT", "MULTI", "MULTIAUDIO", "DOLBY", "AAC", "AC3", "VIP", "HDR", "SDR",
        "BK", "B", "BKP", "LIVE", "ONLY", "DIRECT", "OPT", "OPTION",
    )
    /** Region / language tags that are noise once channels are already split by country. */
    private val regionTokens = setOf("UK", "GB", "US", "USA", "EN", "ENG", "IE", "CA", "AU")

    private val leadingTag = Regex("^\\W*[A-Za-z]{2,5}\\s*[|:\\]\\-»]\\s*")

    private fun nfkc(s: String) =
        Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKC), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun stripLeadingTags(s: String): String {
        var t = s
        repeat(2) { t = t.replace(leadingTag, "") }
        return t
    }

    private fun tokens(name: String): List<String> =
        stripLeadingTags(nfkc(name)).uppercase()
            .replace("+", " +")
            .split(Regex("[^A-Z0-9+&]+"))
            .filter { it.isNotEmpty() && it != "+" }

    /** Comparison key; channels with the same key (and country) are versions of one channel. */
    fun key(name: String): String {
        val t = tokens(name)
        val core = t.filter { it !in qualityTokens && it !in regionTokens }
        return (core.ifEmpty { t }).joinToString(" ")
    }

    private val qualityWordRe = Regex(
        "(?i)(?<![A-Za-z0-9])(" + qualityTokens.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z0-9])"
    )
    private val regionBracketRe = Regex("(?i)[\\[(]\\s*(UK|GB|US|USA|EN|ENG)\\s*[\\])]")

    /** Readable name: "UK | BBC One FHD ᴿᴬᵂ" -> "BBC One". Keeps the provider's casing. */
    fun display(name: String): String {
        var t = stripLeadingTags(Normalizer.normalize(name, Normalizer.Form.NFKC))
        t = regionBracketRe.replace(t, " ")
        t = qualityWordRe.replace(t, " ")
        t = t.replace(Regex("[\\[(]\\s*[\\])]"), " ")          // brackets left empty
            .replace(Regex("[\\s|:\\-_•]+$"), "")               // trailing separators
            .replace(Regex("\\s+"), " ").trim()
        return t.ifEmpty { name.trim() }
    }

    /** Lower is better: plays first when an entry has several versions. */
    fun qualityRank(name: String): Int {
        val t = tokens(name).toSet()
        var r = when {
            "FHD" in t || "1080P" in t || "1080I" in t -> 0
            "HD" in t || "720P" in t -> 1
            "UHD" in t || "4K" in t || "2160P" in t -> 3   // heavy on mobile data, not first choice
            "SD" in t || "LQ" in t || "576P" in t -> 4
            else -> 2
        }
        if (t.any { it in setOf("BACKUP", "BAK", "BK", "BKP", "ALT", "B", "OPT", "OPTION") }) r += 10
        return r
    }

    /** Short label for a version in pickers: "FHD", "HD", "4K", "SD", "Backup"... */
    fun versionLabel(name: String): String {
        val t = tokens(name)
        val q = t.filter { it in qualityTokens && it !in setOf("B", "BK", "ONLY", "LIVE", "DIRECT") }
        return if (q.isEmpty()) "Standard" else q.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase).let { w -> if (w.length <= 4) w.uppercase() else w } }
    }
}
