package com.zapline.iptv.player

import com.zapline.iptv.model.Channel

/** One channel as the player sees it: every version of it, best first. */
data class PlayEntry(val key: String, val name: String, val variants: List<Channel>, val epgId: String? = null)

/** The filtered list the user picked a channel from, so the player can step through it. */
object PlayQueue {
    @Volatile var items: List<PlayEntry> = emptyList()
    @Volatile var index: Int = 0

    fun set(list: List<PlayEntry>, i: Int) {
        items = list
        index = i.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }
}
