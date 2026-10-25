package com.zapline.iptv.data

/** Sent with every request to providers; many IPTV panels only accept known players. */
const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

/** Marks a "show" built from an M3U playlist's episode entries (there's no series API for M3U). */
const val M3U_SERIES_SCHEME = "m3useries:"
