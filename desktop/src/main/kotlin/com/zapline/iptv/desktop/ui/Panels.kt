package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.Programme
import com.zapline.iptv.lang.Categories
import com.zapline.iptv.lang.Languages
import com.zapline.iptv.lang.Quality
import com.zapline.iptv.lang.RegionTagger
import com.zapline.iptv.ui.Entry
import com.zapline.iptv.ui.FourKItem

/** True when a channel is (or says it is) 4K. */
fun Entry.is4k(): Boolean = variants.any { v ->
    val s = Health[v.ch.url]
    (s != null && s.ok && s.height >= 2000) || (Quality.labelled4k(v.ch.name, v.ch.group) && s?.let { !it.ok || it.height >= 2000 } != false)
}

fun Entry.labelled4k(): Boolean = variants.any { Quality.labelled4k(it.ch.name, it.ch.group) }

// ------------------------------------------------------------------------------------------------ channels

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelPanel(app: AppState) {
    val vm = app.vm
    val view by vm.view.collectAsState()
    val q by vm.query.collectAsState()
    val favOnly by vm.favOnly.collectAsState()
    val langs by vm.langFilter.collectAsState()
    val regions by vm.regionFilter.collectAsState()
    val loading by vm.loading.collectAsState()
    val progress by vm.progress.collectAsState()
    val now by vm.now.collectAsState()
    val favs by vm.favourites.collectAsState()
    val epg by vm.epg.collectAsState()
    var only4k by rememberSaveable { mutableStateOf(app.prefs.p.getBoolean("desktopOnly4k", false)) }

    val list = remember(view, only4k, Health.version.collectAsState().value) { if (only4k) view.entries.filter { it.is4k() } else view.entries }
    val playingKey = app.playback.main.entry?.key

    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (loading) {
                Text(progress, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1)
                CircularProgressIndicator(Modifier.padding(start = 8.dp).width(18.dp).height(18.dp), color = Palette.Tally, strokeWidth = 2.dp)
            } else ToolButton(Icons.Default.Refresh, "Reload playlist") { vm.refresh() }
        }
        SearchBox(q, { vm.query.value = it }, "Search ${view.totalChannels} channels", Modifier.fillMaxWidth().padding(16.dp, 12.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Favourites", active = favOnly, leading = Icons.Default.Favorite) { vm.favOnly.value = !favOnly }
            Pill("4K only", active = only4k) { only4k = !only4k; app.prefs.p.edit().putBoolean("desktopOnly4k", only4k).apply() }
            ChoicePill(
                if (langs.isEmpty()) "Language" else langs.joinToString { Languages.label(it) },
                listOf("*" to "All languages") + view.langs.map { (c, n) -> c to "${Languages.label(c)}  ($n)" },
                langs.singleOrNull(), active = langs.isNotEmpty(), leading = Icons.Default.Language,
            ) { vm.setLangFilter(if (it == "*") emptySet() else setOf(it)) }
            ChoicePill(
                if (regions.isEmpty()) "Country" else regions.joinToString { RegionTagger.label(it) },
                listOf("*" to "All countries") + view.regions.map { (c, n) -> c to "${RegionTagger.label(c)}  ($n)" },
                regions.singleOrNull(), active = regions.isNotEmpty(), leading = Icons.Default.Public,
            ) { vm.setRegionFilter(if (it == "*") emptySet() else setOf(it)) }
            if (view.categories.size > 1) ChoicePill(
                view.selectedCategory?.let { Categories.label(it) } ?: "Category",
                listOf<Pair<String?, String>>(null to "All categories") + view.categories.map { (c, n) -> c to "${Categories.label(c)}  ($n)" },
                view.selectedCategory, leading = Icons.Default.Category,
            ) { vm.selectedCategory.value = it; vm.selectedGroup.value = null }
            if (view.groups.size > 1) ChoicePill(
                view.selectedGroup ?: "Group",
                listOf<Pair<String?, String>>(null to "All groups (${view.groupTotal})") + view.groups.map { (g, n) -> g to "$g  ($n)" },
                view.selectedGroup, leading = Icons.Default.Folder,
            ) { vm.selectedGroup.value = it }
        }
        Text(
            when {
                !view.ready -> "Loading channels…"
                only4k -> "${list.size} 4K channels"
                else -> "${list.size} channels"
            },
            Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint,
        )

        if (view.ready && list.isEmpty()) {
            EmptyState(
                if (only4k) "No 4K channels here" else "Nothing matches",
                if (only4k) "Channels show here when their name says 4K or UHD, or checking found a 4K picture. Try the 4K section to check them." else "Try clearing the search or filters.",
            )
            return@Column
        }

        val state = rememberLazyListState()
        LaunchedEffect(playingKey, list) {
            val i = list.indexOfFirst { it.key == playingKey }
            if (i >= 0 && (i < state.firstVisibleItemIndex || i > state.firstVisibleItemIndex + 12)) state.scrollToItem((i - 3).coerceAtLeast(0))
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = state) {
                itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                    ChannelRow(app, e, i + 1, now, epg.let { EpgStore.data.nowNext(vm.epgIdFor(e), now).first }, playing = e.key == playingKey,
                        fav = vm.isFav(e, favs), onFav = { vm.toggleFav(e) }) { app.playEntries(list, i) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxSize().width(8.dp).padding(vertical = 4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(app: AppState, e: Entry, number: Int, now: Long, cur: Programme?, playing: Boolean, fav: Boolean, onFav: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (playing) Palette.TallyDim else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = { onClick(); app.mode = Mode.FULLSCREEN })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", Modifier.width(44.dp), style = MaterialTheme.typography.labelLarge, color = if (playing) Palette.Tally else Palette.Faint)
        Logo(e.logo, e.name, 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold)
                if (e.variants.size > 1) Text("  ×${e.variants.size}", style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
            }
            if (cur != null) {
                Text(cur.title, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressLine(cur, now, Modifier.padding(top = 4.dp).fillMaxWidth(0.6f), height = 2.dp)
            } else Text(e.groups.firstOrNull() ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        QualityBadges(e.info, labelled4k = e.labelled4k(), compact = true)
        ToolButton(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Remove favourite" else "Add favourite", active = fav, size = 32.dp, onClick = onFav)
    }
}

// ------------------------------------------------------------------------------------------------ 4K

/** Everything that might be 4K, sorted by what checking found. */
@Composable
fun FourKPanel(app: AppState) {
    val vm = app.vm
    val fk by vm.fourK.collectAsState()
    val scan by vm.healthScan.collectAsState()
    val movies by vm.fourKMovies.collectAsState()
    val series by vm.fourKSeries.collectAsState()
    val now by vm.now.collectAsState()
    val playingUrl = app.playback.main.channel?.url
    LaunchedEffect(Unit) { if (fk.labelled.isNotEmpty() && !scan.running) vm.check4k(force = false) }

    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Text("4K", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "Channels whose names say 4K or UHD, checked to see whether the picture really is 2160p.",
            Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
        )
        Row(Modifier.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (scan.running) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), color = Palette.Tally, strokeWidth = 2.dp)
                Text(scan.paused ?: "Checking ${scan.done} of ${scan.total}…", Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Pill("Stop", leading = Icons.Default.Stop) { vm.stopChecking() }
            } else {
                Text("${fk.verified.size} real · ${fk.labelled.size} not checked · ${fk.notReally.size} not really 4K",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Pill("Check 4K now", accent = true) { vm.check4k(force = true) }
            }
        }
        if (fk.total == 0 && movies.isEmpty() && series.isEmpty()) {
            EmptyState("No 4K found", "Nothing in your playlist (with your language and country filters) has 4K or UHD in its name.")
            return@Column
        }
        val state = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = state) {
                fun section(title: String, items: List<FourKItem>, note: String?) {
                    if (items.isEmpty()) return
                    item(key = "h$title") { SectionLabel("$title  ${items.size}") }
                    note?.let { item(key = "n$title") { Text(it, Modifier.padding(18.dp, 0.dp, 18.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Faint) } }
                    items(items, key = { "$title|" + it.variant.ch.url }) { it2 ->
                        FourKRow(app, it2, now, it2.variant.ch.url == playingUrl) {
                            // Play exactly this version first.
                            val e = it2.entry
                            val vs = listOf(it2.variant.ch) + e.variants.map { v -> v.ch }.filter { c -> c.url != it2.variant.ch.url }
                            val all = items.map { x -> x.entry.toPlay(vm.epgIdFor(x.entry)) }
                            val i = items.indexOf(it2)
                            app.play(all.toMutableList().also { l -> l[i] = l[i].copy(variants = vs) }, i)
                        }
                    }
                }
                section("Real 4K", fk.verified, null)
                section("Says 4K, not checked yet", fk.labelled, "Playing or checking these confirms them.")
                section("Says 4K but isn't", fk.notReally, "Checking found a smaller picture (often upscaled HD), or it isn't working.")
                if (movies.isNotEmpty()) {
                    item(key = "hm") { SectionLabel("4K films  ${movies.size}") }
                    items(movies.take(200), key = { "m" + it.url }) { c -> VodRow(app, c) { app.playMovie(c) } }
                }
                if (series.isNotEmpty()) {
                    item(key = "hs") { SectionLabel("4K series  ${series.size}") }
                    items(series.take(200), key = { "s" + it.url }) { c -> VodRow(app, c) { app.show = c; app.section = Section.SERIES } }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxSize().width(8.dp).padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun FourKRow(app: AppState, it: FourKItem, now: Long, playing: Boolean, onClick: () -> Unit) {
    val cur = EpgStore.data.nowNext(app.vm.epgIdFor(it.entry), now).first
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (playing) Palette.TallyDim else Color.Transparent).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(it.entry.logo, it.entry.name, 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(it.variant.ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cur?.title ?: it.variant.ch.group, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val info = it.info
        when {
            info == null || (!info.ok && !info.dead) -> Badge("4K?", Palette.Tally)
            info.dead -> Badge("Not working", Palette.Alert)
            else -> {
                Badge(info.resolution ?: "?", if (info.height >= 2000) Palette.Tally else Palette.Muted, filled = info.height >= 2000)
                if (info.videoCodec == "HEVC") { Box(Modifier.width(5.dp)); Badge("HEVC", Palette.Muted) }
                if (info.fps >= 48) { Box(Modifier.width(5.dp)); Badge("${Math.round(info.fps)} fps", Palette.Muted) }
            }
        }
    }
}

@Composable
fun VodRow(app: AppState, c: com.zapline.iptv.model.Channel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Dusk)) { Art(c.logo, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.group, style = MaterialTheme.typography.bodySmall, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (Quality.labelled4k(c.name, c.group)) Badge("4K", Palette.Tally)
    }
}

// ------------------------------------------------------------------------------------------------ search

@Composable
fun SearchPanel(app: AppState) {
    val vm = app.vm
    val q by vm.searchQuery.collectAsState()
    val r by vm.search.collectAsState()
    val now by vm.now.collectAsState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Text("Search", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
        SearchBox(q, { vm.searchQuery.value = it }, "Channels, programmes, films, series", Modifier.fillMaxWidth().padding(16.dp, 12.dp), focus)
        if (q.isBlank()) { EmptyState("Search everything", "Type a channel, a programme on the guide, a film or a series."); return@Column }
        if (r.query == q && r.isEmpty) { EmptyState("Nothing found", "Nothing matches \"$q\"."); return@Column }
        val state = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = state) {
                fun progs(title: String, l: List<Pair<Entry, Programme>>) {
                    if (l.isEmpty()) return
                    item(key = "h$title") { SectionLabel(title) }
                    items(l, key = { "$title|${it.first.key}|${it.second.start}" }) { (e, p) ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { if (p.start <= now && p.end > now) app.watchLive(e) else app.programme = e to p }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(e.logo, e.name, 40.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${e.name} · ${hm(p.start)}${dayWord(p.start, now)}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
                            }
                        }
                    }
                }
                progs("On now", r.onNow)
                if (r.channels.isNotEmpty()) {
                    item(key = "hc") { SectionLabel("Channels") }
                    itemsIndexed(r.channels, key = { _, e -> "c" + e.key }) { i, e ->
                        CompactChannelRow(app, e, now, e.key == app.playback.main.entry?.key) { app.playEntries(r.channels, i) }
                    }
                }
                progs("Coming up", r.upcoming)
                progs("Catch up", r.catchup)
                if (r.movies.isNotEmpty()) {
                    item(key = "hm") { SectionLabel("Films") }
                    items(r.movies, key = { "m" + it.url }) { c -> VodRow(app, c) { app.playMovie(c) } }
                }
                if (r.series.isNotEmpty()) {
                    item(key = "hs") { SectionLabel("Series") }
                    items(r.series, key = { "s" + it.url }) { c -> VodRow(app, c) { app.show = c; app.section = Section.SERIES } }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxSize().width(8.dp).padding(vertical = 4.dp))
        }
    }
}
