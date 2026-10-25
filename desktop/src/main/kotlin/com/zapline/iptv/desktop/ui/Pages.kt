package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.lang.Quality
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.Xtream
import com.zapline.iptv.ui.episodeCode
import com.zapline.iptv.ui.episodeQueue

// ------------------------------------------------------------------------------------------------ TV guide

/**
 * The guide, laid out for a very wide screen: about ten hours across at once on a G9.
 * Shift + wheel (or the arrows) moves through time.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuidePage(app: AppState) {
    val vm = app.vm
    val view by vm.view.collectAsState()
    val now by vm.now.collectAsState()
    val epg by vm.epg.collectAsState()
    val status by vm.epgStatus.collectAsState()
    val q by vm.query.collectAsState()
    val half = 30 * 60_000L
    var start by remember { mutableLongStateOf(now / half * half - half) }
    val perMin = 7.dp
    val nameW = 300.dp
    val rowH = 66.dp
    val withGuide = remember(view, epg) { view.entries.filter { vm.epgIdFor(it) != null } }
    val list = if (withGuide.isEmpty()) view.entries else withGuide
    val reminders by vm.reminders.collectAsState()

    Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 18.dp)) {
        Row(Modifier.padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV guide", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            SearchBox(q, { vm.query.value = it }, "Filter channels", Modifier.width(380.dp))
            Spacer(Modifier.weight(1f))
            if (status.isNotBlank()) Text(status, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier (Shift + wheel)") { start -= 2 * 3600_000L }
            Pill("Now") { start = now / half * half - half }
            Spacer(Modifier.width(6.dp))
            Pill("Tonight") { start = tonight(now) }
            Spacer(Modifier.width(6.dp))
            Pill("Tomorrow") { start = tonight(now) - 13 * 3600_000L + 24 * 3600_000L }
            ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later (Shift + wheel)") { start += 2 * 3600_000L }
        }
        if (epg.byIdCount() == 0) {
            EmptyState("No TV guide yet", if (status.isNotBlank()) status else "Your provider didn't send one. You can add a guide link (XMLTV) in Settings.", "Open settings") { app.section = Section.SETTINGS }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).padding(top = 12.dp)
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val c = ev.changes.firstOrNull() ?: return@onPointerEvent
                val dx = c.scrollDelta.x.takeIf { it != 0f } ?: if (ev.keyboardModifiers.isShiftPressed) c.scrollDelta.y else 0f
                if (dx != 0f) { start += (dx * 15 * 60_000L).toLong(); c.consume() }
            }) {
            val gridW = maxWidth - nameW - 20.dp
            val minutes = (gridW / perMin).toInt()
            val end = start + minutes * 60_000L
            Column {
                // Time ruler
                Row(Modifier.height(30.dp)) {
                    Text(dayWord(start, now).trim().ifEmpty { "Today" }, Modifier.width(nameW), style = MaterialTheme.typography.labelLarge, color = Palette.Tally)
                    Box(Modifier.width(gridW).fillMaxHeight()) {
                        var t = (start + half - 1) / half * half
                        while (t < end) {
                            Text(hm(t), Modifier.offset(perMin * ((t - start) / 60_000f)), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                            t += half
                        }
                    }
                }
                val state = rememberLazyListState()
                LaunchedEffect(app.playback.main.entry?.key) {
                    val i = list.indexOfFirst { it.key == app.playback.main.entry?.key }
                    if (i > 3) state.scrollToItem(i - 3)
                }
                Box {
                    LazyColumn(state = state) {
                        itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                            val progs = EpgStore.data.programmes(vm.epgIdFor(e))
                            val playing = e.key == app.playback.main.entry?.key
                            Row(Modifier.height(rowH).padding(bottom = 4.dp)) {
                                Row(
                                    Modifier.width(nameW).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                        .background(if (playing) Palette.TallyDim else Palette.Night).clickable { app.playEntries(list, i) }.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${i + 1}", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
                                    Logo(e.logo, e.name, 40.dp)
                                    Text(e.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    QualityBadges(e.info, labelled4k = e.labelled4k(), compact = true)
                                }
                                Box(Modifier.width(gridW).fillMaxHeight().padding(start = 4.dp)) {
                                    progs.filter { it.end > start && it.start < end }.forEach { p ->
                                        val x0 = maxOf(p.start, start); val x1 = minOf(p.end, end)
                                        val live = p.start <= now && p.end > now
                                        val past = p.end <= now
                                        val reminded = reminders.any { it.channelKey == e.key && it.start == p.start }
                                        Column(
                                            Modifier.offset(perMin * ((x0 - start) / 60_000f)).width(perMin * ((x1 - x0) / 60_000f) - 3.dp).fillMaxHeight()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (live) Palette.Haze else if (past) Palette.Ink else Palette.Night)
                                                .border(1.dp, if (reminded) Palette.Tally else if (live) Palette.Tally.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    if (live) app.watchLive(e) else app.programme = e to p
                                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                color = if (past) Palette.Faint else Palette.Text, fontWeight = if (live) FontWeight.Bold else FontWeight.Medium)
                                            Text("${hm(p.start)}–${hm(p.end)}${if (Quality.labelled4k(p.title, "")) "  4K" else ""}", style = MaterialTheme.typography.labelSmall,
                                                color = if (live) Palette.TallyText else Palette.Faint, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // The "now" line.
                    if (now in start until end) Box(Modifier.offset(nameW + 4.dp + perMin * ((now - start) / 60_000f)).width(2.dp).fillMaxHeight().background(Palette.Tally))
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
                }
            }
        }
    }
}

private fun com.zapline.iptv.data.EpgData.byIdCount(): Int = byId.size

/** 18:00 today, or now if it's later. */
private fun tonight(now: Long): Long {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 18); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
    return maxOf(c.timeInMillis, now / 1_800_000L * 1_800_000L)
}

// ------------------------------------------------------------------------------------------------ films & series

@Composable
fun VodPage(app: AppState, series: Boolean) {
    val vm = app.vm
    val show = app.show
    if (series && show != null) { ShowPage(app, show); return }
    val v by (if (series) vm.seriesView else vm.movieView).collectAsState()
    val q by (if (series) vm.seriesQuery else vm.movieQuery).collectAsState()
    var only4k by rememberSaveable(series) { mutableStateOf(false) }
    val items = remember(v, only4k) {
        val l = if (only4k) v.items.filter { Quality.labelled4k(it.name, it.group) } else v.items
        // 4K versions first: that's what this screen is for.
        l.sortedByDescending { Quality.labelled4k(it.name, it.group) }
    }
    val fourKCount = remember(v) { v.items.count { Quality.labelled4k(it.name, it.group) } }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 18.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (series) "Series" else "Films", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            SearchBox(q, { if (series) vm.seriesQuery.value = it else vm.movieQuery.value = it }, "Search ${v.total} ${if (series) "series" else "films"}", Modifier.width(420.dp))
            Spacer(Modifier.width(12.dp))
            if (fourKCount > 0) Pill("4K only  $fourKCount", active = only4k) { only4k = !only4k }
            Spacer(Modifier.width(8.dp))
            if (v.groups.size > 1) ChoicePill(
                v.selectedGroup ?: "All groups",
                listOf<Pair<String?, String>>(null to "All groups") + v.groups.map { (g, n) -> g to "$g  ($n)" },
                v.selectedGroup, leading = Icons.Default.Folder,
            ) { if (series) vm.seriesGroup.value = it else vm.movieGroup.value = it }
        }
        if (v.total == 0) {
            EmptyState(if (series) "No series" else "No films", "Your playlist doesn't include any, or loading them is turned off in Settings.")
            return@Column
        }
        val grid = rememberLazyGridState()
        Box(Modifier.weight(1f).padding(top = 16.dp)) {
            LazyVerticalGrid(GridCells.Adaptive(190.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(end = 12.dp)) {
                if (!series && v.continueWatching.isNotEmpty() && q.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel("Continue watching", Modifier.padding(start = 0.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(v.continueWatching, key = { "c" + it.first.url }) { (c, f) -> Poster(c, f, Modifier.width(190.dp)) { app.playMovie(c) } }
                            }
                        }
                    }
                }
                if (series && v.seriesContinue.isNotEmpty() && q.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionLabel("Carry on watching")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(v.seriesContinue, key = { "c" + it.first.url }) { (c, p) ->
                                    Poster(c, null, Modifier.width(190.dp), subtitle = p.code) { vm.continueSeries(c, p) { l, i -> app.play(l, i) } }
                                }
                            }
                        }
                    }
                }
                items(items, key = { it.url }) { c ->
                    Poster(c, if (series) null else vm.resumeFraction(c.url).takeIf { it > 0f }) {
                        if (series) app.show = c else app.playMovie(c)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
        }
    }
}

@Composable
private fun Poster(c: Channel, progress: Float?, modifier: Modifier = Modifier, subtitle: String? = null, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Palette.Dusk)) {
            Text(c.name, Modifier.align(Alignment.Center).padding(12.dp), style = MaterialTheme.typography.titleSmall, color = Palette.Muted, maxLines = 4)
            Art(c.logo, Modifier.fillMaxSize())
            if (Quality.labelled4k(c.name, c.group)) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Badge("4K", Palette.Tally, filled = true) }
            progress?.let {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(it).fillMaxHeight().background(Palette.Tally))
                }
            }
        }
        Text(c.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle ?: c.group, style = MaterialTheme.typography.bodySmall, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One series: seasons along the top, episodes as wide rows. */
@Composable
private fun ShowPage(app: AppState, show: Channel) {
    val vm = app.vm
    val info by produceState<Result<Xtream.SeriesInfo>?>(null, show.url) { value = runCatching { vm.episodesFor(show) } }
    var season by remember(show.url) { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(520.dp).fillMaxHeight()) {
            Art(show.logo, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Ink))))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to series") { app.show = null }
                Text(show.name, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val r = info
            when {
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Tally) }
                r.isFailure -> EmptyState("Couldn't load episodes", r.exceptionOrNull()?.message)
                else -> {
                    val si = r.getOrThrow()
                    si.plot?.let { Text(it, Modifier.padding(vertical = 12.dp).width(1100.dp), style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    val seasons = si.episodes.map { it.season }.distinct().sorted()
                    val sel = season ?: seasons.firstOrNull()
                    if (seasons.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        items(seasons) { s -> Pill(if (s > 0) "Season $s" else "Extras", active = s == sel) { season = s } }
                    }
                    val eps = si.episodes.filter { it.season == sel }
                    val all = si.episodes
                    LazyVerticalGrid(GridCells.Adaptive(560.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(eps, key = { it.url }) { ep ->
                            val f = vm.resumeFraction(ep.url)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Night)
                                    .clickable { app.play(episodeQueue(show, all), all.indexOf(ep)) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.Dusk), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, null, tint = Palette.Muted)
                                    Art(ep.image ?: show.logo, Modifier.fillMaxSize())
                                    if (f > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(Palette.Tally))
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(episodeCode(ep), style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
                                    Text(ep.title.removePrefix(show.name).trim(' ', '-', '|', ':').ifBlank { "Episode ${ep.number}" },
                                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    ep.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
