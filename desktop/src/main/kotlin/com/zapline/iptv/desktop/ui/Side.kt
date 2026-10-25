package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Sports
import com.zapline.iptv.desktop.player.Tile
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.ui.Entry

/** The entry in the channel list for what a tile is playing (null for films and episodes). */
fun AppState.liveEntry(t: Tile): Entry? {
    val k = t.entry?.key ?: return null
    return vm.view.value.entries.firstOrNull { it.key == k }
}

/** The sports match on the programme now showing, if there is one. */
fun AppState.matchFor(t: Tile, now: Long): Sports.Match? {
    val cur = EpgStore.data.nowNext(t.entry?.epgId, now).first
    val ms = vm.sport.value.matches
    if (ms.isEmpty()) return null
    return Sports.find(ms, cur?.title, cur?.desc, t.entry?.name)
}

// ------------------------------------------------------------------------------------------------ beside the picture

@Composable
fun SidePanel(app: AppState) {
    val t = app.playback.main
    val now by app.vm.now.collectAsState()
    val sport by app.vm.sport.collectAsState()
    DisposableEffect(Unit) { app.vm.watchSport(); onDispose { } }
    val match = remember2(sport, t.entry, now) { app.matchFor(t, now) }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.Night)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideTab.entries.forEach { tab ->
                if (tab == SideTab.MATCH && match == null) return@forEach
                Pill(if (tab == SideTab.MATCH && match?.isLive == true) "Match · live" else tab.title, active = app.sideTab == tab) { app.sideTab = tab }
            }
        }
        val tab = if (app.sideTab == SideTab.MATCH && match == null) SideTab.NOW else app.sideTab
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            // A G9 leaves ~2000 px here: use the second half for what's on everywhere else.
            val wide = maxWidth >= 1200.dp
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when {
                        t.entry == null -> EmptyState("Nothing playing", "Pick a channel from the list.")
                        tab == SideTab.NOW -> NowNext(app, t, now)
                        tab == SideTab.GUIDE -> ChannelDay(app, t, now)
                        tab == SideTab.MATCH -> MatchDetail(match!!)
                        tab == SideTab.STREAM -> StreamInfoView(app, t)
                    }
                }
                if (wide) {
                    VDivider()
                    Box(Modifier.weight(1f)) { OnNowElsewhere(app, t, now) }
                }
            }
        }
    }
}

/** Every channel in the current list with what's on it now, to hop across without leaving the picture. */
@Composable
private fun OnNowElsewhere(app: AppState, t: Tile, now: Long) {
    val q = t.queue
    val byKey = androidx.compose.runtime.remember(app.vm.view.value) { app.vm.view.value.entries.associateBy { it.key } }
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Text("On now", Modifier.padding(start = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = state) {
                itemsIndexed(q, key = { i, p -> "$i|${p.key}" }) { i, p ->
                    val e = byKey[p.key] ?: return@itemsIndexed
                    CompactChannelRow(app, e, now, i == t.index, number = i + 1) { t.jump(i) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxSize().width(8.dp))
        }
    }
}

@Composable
private fun <T> remember2(vararg keys: Any?, calc: () -> T): T = androidx.compose.runtime.remember(*keys) { calc() }

@Composable
private fun NowNext(app: AppState, t: Tile, now: Long) {
    val progs = EpgStore.data.programmes(t.entry?.epgId)
    val curI = progs.indexOfFirst { it.start <= now && it.end > now }
    val cur = progs.getOrNull(curI)
    val reminders by app.vm.reminders.collectAsState()
    val e = app.liveEntry(t)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (t.isVod) {
            Text(t.entry?.name ?: "", style = MaterialTheme.typography.headlineSmall)
            Text(t.channel?.group ?: "", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            return@Column
        }
        if (cur == null) {
            Text("No guide for this channel", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text("Add a TV guide link in Settings if your provider doesn't send one.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            return@Column
        }
        Text("NOW · ${hm(cur.start)}–${hm(cur.end)} · ${((cur.end - now) / 60_000).coerceAtLeast(0)} min left", style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
        Text(cur.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineSmall)
        ProgressLine(cur, now, Modifier.padding(vertical = 10.dp).fillMaxWidth(), height = 4.dp)
        cur.desc?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted) }
        Spacer(Modifier.height(20.dp))
        progs.drop(curI + 1).take(8).forEachIndexed { i, p ->
            ProgrammeLine(app, e, p, now, reminders.any { it.channelKey == e?.key && it.start == p.start }, big = i == 0)
        }
    }
}

@Composable
private fun ProgrammeLine(app: AppState, e: Entry?, p: Programme, now: Long, reminded: Boolean, big: Boolean = false, onClick: (() -> Unit)? = null) {
    val past = p.end <= now
    val live = p.start <= now && p.end > now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (live) Palette.TallyDim else Color.Transparent)
            .clickable { if (onClick != null) onClick() else if (e != null) app.programme = e to p }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(hm(p.start), Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge, color = if (live) Palette.Tally else if (past) Palette.Faint else Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(p.title, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = if (past) Palette.Muted else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (big || live) p.desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        if (e != null && p.start > now) ToolButton(if (reminded) Icons.Default.NotificationsActive else Icons.Default.Notifications,
            if (reminded) "Cancel reminder" else "Remind me", active = reminded, size = 32.dp) {
            if (reminded) app.vm.removeReminder(e, p) else { app.vm.addReminder(e, p); app.say("I'll remind you when ${p.title} starts") }
        }
        if (e != null && past && app.vm.canCatchUp(e, p, now)) Badge("Catch up", Palette.TallyText)
    }
}

/** Today's programmes on this channel, scrolled to now. */
@Composable
private fun ChannelDay(app: AppState, t: Tile, now: Long) {
    val progs = EpgStore.data.programmes(t.entry?.epgId).filter { it.end > now - 12 * 3600_000L && it.start < now + 36 * 3600_000L }
    val e = app.liveEntry(t)
    val reminders by app.vm.reminders.collectAsState()
    if (progs.isEmpty()) { EmptyState("No guide for this channel"); return }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (progs.indexOfFirst { it.end > now } - 2).coerceAtLeast(0))
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = state) {
            itemsIndexed(progs, key = { _, p -> p.start }) { i, p ->
                if (i == 0 || dayWord(p.start, now) != dayWord(progs[i - 1].start, now))
                    SectionLabel(dayWord(p.start, now).trim().ifEmpty { "Today" })
                ProgrammeLine(app, e, p, now, reminders.any { it.channelKey == e?.key && it.start == p.start }, onClick = {
                    if (e == null) return@ProgrammeLine
                    if (p.start <= now && p.end > now) app.watchLive(e)
                    else if (p.end <= now && app.vm.canCatchUp(e, p, now)) app.vm.catchupEntry(e, p)?.let { app.play(listOf(it), 0) }
                    else app.programme = e to p
                })
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxSize().width(8.dp))
    }
}

/** What the stream actually is, and the other versions of this channel. */
@Composable
private fun StreamInfoView(app: AppState, t: Tile) {
    val st = t.player.state
    val e = t.entry ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Playing now", style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
        Spacer(Modifier.height(6.dp))
        StreamBadges(st)
        Spacer(Modifier.height(12.dp))
        fun line(k: String, v: String?) = v?.takeIf { it.isNotBlank() }?.let { k to it }
        listOfNotNull(
            line("Picture", if (st.width > 0) "${st.width} × ${st.height}${if (st.fps > 0) " at ${"%.2f".format(st.fps).trimEnd('0').trimEnd('.')} fps" else ""}" else null),
            line("Video", st.videoCodec?.let { (Tile.codecLabel(it) ?: it) + (st.gamma?.let { g -> " · $g" } ?: "") }),
            line("Bitrate", st.videoBitrate.takeIf { it > 0 }?.let { "%.1f Mb/s".format(it / 1_000_000f) }),
            line("Sound", st.audioCodec?.let { (Tile.codecLabel(it) ?: it) + if (st.audioChannels > 0) " · ${st.audioChannels} channels" else "" }),
            line("Decoding", st.hwdec?.let { if (it == "no" || it.isBlank()) "Processor (software)" else "Graphics card ($it)" } ?: "Processor (software)"),
            line("Buffered", if (st.cacheSeconds > 0) "${"%.1f".format(st.cacheSeconds)} s" else null),
            line("Version", t.channel?.name),
        ).forEach { (k, v) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (e.variants.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text("Versions of this channel", style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
            Text("Best first. Click one to switch.", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
            e.variants.forEachIndexed { i, c ->
                val info = Health[c.url]
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (i == t.variant) Palette.TallyDim else Palette.Dusk).clickable { t.pickVersion(i) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ChannelNames.versionLabel(c.name).ifBlank { c.name }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (i == t.variant) FontWeight.Bold else FontWeight.Normal)
                    QualityBadges(info, labelled4k = com.zapline.iptv.lang.Quality.labelled4k(c.name, c.group))
                }
            }
        }
        t.channel?.url?.let {
            Spacer(Modifier.height(16.dp))
            Text("Address", style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
            Text(it.replace(Regex("(/(live|movie|series)/)[^/]+/[^/]+/"), "$1•••/•••/"), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------ fullscreen side bars

/** Left of a fullscreen picture on an ultrawide: the channel list, to flick through without leaving fullscreen. */
@Composable
fun FullscreenSideList(app: AppState) {
    val t = app.playback.main
    val now by app.vm.now.collectAsState()
    val q = t.queue
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (t.index - 4).coerceAtLeast(0))
    LaunchedEffect(t.index) { if (t.index < state.firstVisibleItemIndex || t.index > state.firstVisibleItemIndex + 14) state.animateScrollToItem((t.index - 4).coerceAtLeast(0)) }
    val byKey = androidx.compose.runtime.remember(app.vm.view.value) { app.vm.view.value.entries.associateBy { it.key } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.isVod) "Up next" else "Channels", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            ToolButton(Icons.Default.FullscreenExit, "Leave fullscreen (Esc)") { app.mode = Mode.BROWSE }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(state = state) {
            itemsIndexed(q, key = { i, p -> "$i|${p.key}" }) { i, p ->
                val e = byKey[p.key]
                if (e != null) CompactChannelRow(app, e, now, i == t.index, number = i + 1) { t.jump(i) }
                else Text(p.name, Modifier.fillMaxWidth().clickable { t.jump(i) }.padding(10.dp), color = if (i == t.index) Palette.Tally else Palette.Text)
            }
        }
    }
}

/** Right of a fullscreen picture: what's on, what's next, and the match if it's a game. */
@Composable
fun FullscreenSideInfo(app: AppState) {
    val t = app.playback.main
    val now by app.vm.now.collectAsState()
    val sport by app.vm.sport.collectAsState()
    DisposableEffect(Unit) { app.vm.watchSport(); onDispose { } }
    val match = remember2(sport, t.entry, now) { app.matchFor(t, now) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.entry?.name ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hm(now), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            }
            StreamBadges(t.player.state)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            if (match != null) MatchDetail(match) else NowNext(app, t, now)
        }
        Text("Z changes the picture fit · Esc leaves fullscreen", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
    }
}

// ------------------------------------------------------------------------------------------------ multiview

/** Everything around the multiview pictures: a top bar, a strip above each tile and info below. */
@Composable
fun MultiviewChrome(app: AppState, w: Dp, h: Dp) {
    val tiles = app.playback.tiles
    val rects = Geometry.multiview(tiles.size, w, h)
    val now by app.vm.now.collectAsState()
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Multiview", style = MaterialTheme.typography.titleLarge)
        Text("  ${tiles.size} pictures · click a picture's speaker to hear it", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        if (tiles.size < 4) Pill("Add picture", leading = Icons.Default.Add) { app.playback.addTile() }
        Spacer(Modifier.width(8.dp))
        Pill("Done", accent = true) { app.exitMultiview() }
    }
    tiles.forEachIndexed { i, t ->
        val (head, video, info) = rects.getOrNull(i) ?: return@forEachIndexed
        val hasAudio = app.playback.audioTile == i
        Row(
            head.mod().clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(if (hasAudio) Palette.TallyDim else Palette.Night).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(if (hasAudio) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, if (hasAudio) "You're hearing this one" else "Hear this one",
                active = hasAudio, size = 32.dp) { app.playback.focusAudio(i) }
            Text(t.entry?.name ?: "Empty", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.player.state.resolution?.let { Badge(it, if (it == "4K") Palette.Tally else Palette.Muted, filled = it == "4K") }
            ToolButton(Icons.Default.SwapHoriz, "Choose the channel for this picture", active = app.pickingForTile == i, size = 32.dp) {
                app.pickingForTile = if (app.pickingForTile == i) null else i
            }
            if (i > 0) ToolButton(Icons.Default.Close, "Remove this picture", size = 32.dp) { app.playback.removeTile(t) }
        }
        if (t.entry == null && app.pickingForTile != i) {
            Box(video.mod().background(Palette.Night).clickable { app.pickingForTile = i }, contentAlignment = Alignment.Center) {
                EmptyState("Empty picture", "Click to choose a channel")
            }
        }
        if (!info.empty) Box(info.mod()) {
            if (app.pickingForTile == i) MultiviewPicker(app, i)
            else {
                val cur = EpgStore.data.nowNext(t.entry?.epgId, now).first
                Column(Modifier.padding(horizontal = 4.dp)) {
                    cur?.let {
                        Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ProgressLine(it, now, Modifier.padding(top = 4.dp).fillMaxWidth(0.5f))
                    }
                    t.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1) }
                }
            }
        }
    }
    // Picking a channel for a tile whose info strip is too small (grid on ordinary screens): a side list.
    val picking = app.pickingForTile
    if (picking != null && (rects.getOrNull(picking)?.third?.h ?: 0.dp) < 200.dp) {
        Box(Modifier.padding(top = 60.dp).width(460.dp).fillMaxSize().background(Palette.Ink).border(1.dp, Palette.Line)) { MultiviewPicker(app, picking) }
    }
}

@Composable
private fun MultiviewPicker(app: AppState, tile: Int) {
    val vm = app.vm
    val view by vm.view.collectAsState()
    val q by vm.query.collectAsState()
    val now by vm.now.collectAsState()
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Palette.Night).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchBox(q, { vm.query.value = it }, "Channel for picture ${tile + 1}", Modifier.weight(1f))
            ToolButton(Icons.Default.Close, "Cancel") { app.pickingForTile = null }
        }
        LazyColumn(Modifier.padding(top = 6.dp)) {
            itemsIndexed(view.entries.take(500), key = { _, e -> e.key }) { i, e ->
                CompactChannelRow(app, e, now, app.playback.tiles.getOrNull(tile)?.entry?.key == e.key) { app.pickingForTile = tile; app.playEntries(view.entries, i) }
            }
        }
    }
}
