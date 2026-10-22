package com.zapline.iptv.model

/** One playable live channel, as parsed from a playlist or the Xtream API. */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    val tvgLanguage: String? = null,
    val tvgCountry: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val origin: String? = null,
    /** Language detected from the playlist data; "" when unknown. */
    val detectedLang: String = "",
    /** Country detected from the playlist data ("UK", "US", ...); "" when unknown. */
    val detectedRegion: String = "",
    /** Days of catch-up the provider keeps for this channel (0 = none). */
    val catchupDays: Int = 0,
    /** How to build a catch-up URL: "xc" (Xtream), "default", "append", "shift", "flussonic"; null = guess. */
    val catchupType: String? = null,
    /** URL template for catch-up ({utc}, {utcend}, {duration}, {Y}{m}{d}{H}{M}{S}...). */
    val catchupSource: String? = null,
)
