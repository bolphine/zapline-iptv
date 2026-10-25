package com.zapline.iptv.lang

import java.text.Normalizer

/** What a stream's name (or its group) says about picture quality, before it's been checked. */
object Quality {
    private val fourK = Regex("(?i)(?<![A-Za-z0-9])(4K|UHD|2160[PI]?|ULTRA[ -]?HD|UHDTV)(?![A-Za-z0-9])")
    private val fhd = Regex("(?i)(?<![A-Za-z0-9])(FHD|1080[PI]?|FULL[ -]?HD)(?![A-Za-z0-9])")
    private val hd = Regex("(?i)(?<![A-Za-z0-9])(HD|720[PI]?)(?![A-Za-z0-9])")
    private val sd = Regex("(?i)(?<![A-Za-z0-9])(SD|576[PI]?|480[PI]?|LQ)(?![A-Za-z0-9])")
    private val hdr = Regex("(?i)(?<![A-Za-z0-9])(HDR10\\+?|HDR|DOLBY[ -]?VISION|DV)(?![A-Za-z0-9])")

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    /** "4K", "UHD", "2160p" or "Ultra HD" in the name or group. */
    fun labelled4k(name: String, group: String = ""): Boolean = fourK.containsMatchIn(norm(name)) || fourK.containsMatchIn(norm(group))

    fun labelledHdr(name: String, group: String = ""): Boolean = hdr.containsMatchIn(norm(name)) || hdr.containsMatchIn(norm(group))

    /** Picture height the label suggests (2160, 1080, 720, 576), or 0 when it doesn't say. */
    fun labelledHeight(name: String, group: String = ""): Int {
        val n = norm(name)
        return when {
            labelled4k(n, group) -> 2160
            fhd.containsMatchIn(n) -> 1080
            hd.containsMatchIn(n) -> 720
            sd.containsMatchIn(n) -> 576
            else -> 0
        }
    }
}
