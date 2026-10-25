package com.zapline.iptv.ui

import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.Xtream
import com.zapline.iptv.player.PlayEntry

/** A film as a one-item player queue. */
fun movieEntry(c: Channel) = PlayEntry("vod|${c.url}", c.name, listOf(c))

/** Episodes as a player queue; each is registered so the player can note progress through the show. */
fun episodeQueue(show: Channel, list: List<Xtream.Episode>): List<PlayEntry> = list.map { ep ->
    com.zapline.iptv.data.SeriesWatch.register(ep.url, com.zapline.iptv.data.SeriesWatch.Ref(show.url, show.name, show.logo, episodeCode(ep)))
    PlayEntry("vod|${ep.url}", episodeTitle(show, ep), listOf(Channel(episodeTitle(show, ep), ep.url, show.name, logo = ep.image ?: show.logo)))
}

fun episodeCode(e: Xtream.Episode): String =
    if (e.season > 0 || e.number > 0) "S${e.season} E${e.number}" else ""

fun episodeTitle(show: Channel, e: Xtream.Episode): String {
    val code = episodeCode(e)
    val t = e.title.removePrefix(show.name).trim(' ', '-', '|', ':')
    return listOfNotNull(show.name, code.ifEmpty { null }, t.takeIf { it.isNotEmpty() && !it.equals(code, true) }).joinToString("  ")
}
