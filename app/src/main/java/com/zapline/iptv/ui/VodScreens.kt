package com.zapline.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zapline.iptv.lang.Languages
import com.zapline.iptv.lang.RegionTagger
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.Xtream
import com.zapline.iptv.player.PlayEntry
import kotlinx.coroutines.flow.MutableStateFlow

fun movieEntry(c: Channel) = PlayEntry("vod|${c.url}", c.name, listOf(c))

/** Movies (isSeries = false) or the list of shows (isSeries = true). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VodScreen(
    vm: MainViewModel,
    isSeries: Boolean,
    onPlay: (List<PlayEntry>, Int) -> Unit,
    onOpenShow: (Channel) -> Unit,
    onSettings: () -> Unit,
) {
    val view by (if (isSeries) vm.seriesView else vm.movieView).collectAsState()
    val queryFlow: MutableStateFlow<String> = if (isSeries) vm.seriesQuery else vm.movieQuery
    val groupFlow: MutableStateFlow<String?> = if (isSeries) vm.seriesGroup else vm.movieGroup
    val query by queryFlow.collectAsState()
    val langFilter by vm.langFilter.collectAsState()
    val regionFilter by vm.regionFilter.collectAsState()
    val loading by vm.loading.collectAsState()
    val starting by vm.starting.collectAsState()
    val loaded by (if (isSeries) vm.series else vm.movies).collectAsState()
    var groupSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val noun = if (isSeries) "shows" else "movies"

    LaunchedEffect(view.selectedGroup, langFilter, regionFilter) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (isSeries) "Series" else "Movies") {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Palette.Text) }
        }
        SearchField(query, { queryFlow.value = it }, "Search $noun", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill(
                view.selectedGroup ?: "All categories", view.selectedGroup != null, { groupSheet = true },
                modifier = Modifier.widthIn(max = 230.dp),
                onClear = if (view.selectedGroup != null) ({ groupFlow.value = null }) else null,
            )
            if (langFilter.isNotEmpty() || regionFilter.isNotEmpty()) {
                val parts = listOfNotNull(
                    langFilter.takeIf { it.isNotEmpty() }?.joinToString { Languages.label(it) },
                    regionFilter.takeIf { it.isNotEmpty() }?.joinToString { RegionTagger.label(it) },
                )
                // Same filters as Live TV; untagged titles stay so nothing quietly vanishes.
                Text("${parts.joinToString(", ")} + untagged", Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.Tally, trackColor = Palette.Ink)

        if ((starting || loading) && view.total == 0 && (loaded.isEmpty() || starting)) {
            SkeletonPosters()
            return@Column
        }
        if (view.total == 0 && !loading) {
            Message(
                if (isSeries) "No series loaded" else "No movies loaded",
                "If your provider has them, turn on \"Movies & series\" under Change playlist in Settings, then load again.",
                "Open Settings", onSettings,
            )
            return@Column
        }
        if (view.items.isEmpty() && query.isNotBlank()) {
            Message("Nothing matches \"$query\"", "Try a shorter part of the title.", "Clear search", { queryFlow.value = "" })
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isSeries && query.isBlank() && view.selectedGroup == null && view.seriesContinue.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "sc-h") { GridLabel("Continue watching") }
                item(span = { GridItemSpan(maxLineSpan) }, key = "sc") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(view.seriesContinue, key = { it.first.url }) { (show, prog) ->
                            SeriesContinueCard(show, prog, vm.resumeFraction(prog.episodeUrl)) {
                                vm.continueSeries(show, prog) { list, i -> if (list.isEmpty()) onOpenShow(show) else onPlay(list, i) }
                            }
                        }
                    }
                }
            }
            val showContinue = !isSeries && query.isBlank() && view.selectedGroup == null && view.continueWatching.isNotEmpty()
            if (showContinue) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "cw-h") { GridLabel("Continue watching") }
                item(span = { GridItemSpan(maxLineSpan) }, key = "cw") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(view.continueWatching, key = { it.first.url }) { (c, f) ->
                            ContinueCard(c, f) { onPlay(listOf(movieEntry(c)), 0) }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                GridLabel("${"%,d".format(view.items.size)} ${if (view.items.size == 1) noun.dropLast(1) else noun}")
            }
            items(view.items, key = { it.url }) { c ->
                Poster(c) { if (isSeries) onOpenShow(c) else onPlay(listOf(movieEntry(c)), 0) }
            }
        }
    }

    if (groupSheet) {
        ModalBottomSheet(onDismissRequest = { groupSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Night) {
            CategoryPicker(view, isSeries) { g -> groupFlow.value = g; groupSheet = false }
        }
    }
}

/** Placeholder posters while the library is still being read. */
@Composable
private fun SkeletonPosters() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(List(12) { it }) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium).background(Palette.Night))
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.7f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Night))
            }
        }
    }
}

@Composable
private fun GridLabel(text: String) {
    Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = Palette.Muted)
}

@Composable
private fun CategoryPicker(view: VodView, isSeries: Boolean, onPick: (String?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(view.groups, q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) view.groups else view.groups.filter { it.first.lowercase().contains(t) }
    }
    Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 8.dp)) {
        Text(if (isSeries) "Series categories" else "Movie categories", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 22.dp))
        SearchField(q, { q = it }, "Find a category", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            if (q.isBlank()) item(key = "__all") { PickRow("All categories", view.total, view.selectedGroup == null) { onPick(null) } }
            items(shown, key = { it.first }) { (g, n) -> PickRow(g, n, view.selectedGroup == g) { onPick(g) } }
        }
    }
}

/** 2:3 poster. Without artwork the title is set large in the display face so the grid still reads well. */
@Composable
internal fun Poster(c: Channel, onClick: () -> Unit) {
    Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)
                .background(Brush.verticalGradient(listOf(Palette.Dusk, Palette.Night))),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(c.name, Modifier.padding(10.dp), style = MaterialTheme.typography.titleMedium, color = Palette.Muted,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (c.logo != null) {
                AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(c.name, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge)
    }
}

/** A show you're part-way through: next episode code, how far into it. */
@Composable
private fun SeriesContinueCard(show: Channel, p: com.zapline.iptv.data.SeriesProgress, progress: Float, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(Palette.Dusk)) {
            (show.logo ?: p.logo)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC15131F)))))
            Text(if (p.finished) "Next after ${p.code}" else p.code, Modifier.align(Alignment.TopStart).padding(8.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xCC15131F)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0xCC15131F)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Continue ${show.name}", tint = Palette.Tally)
            }
            if (!p.finished && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Tally))
            }
        }
        Text(show.name, Modifier.padding(top = 6.dp, start = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge)
    }
}

/** Landscape card with an amber bar showing how much is left. */
@Composable
private fun ContinueCard(c: Channel, progress: Float, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(Palette.Dusk)) {
            if (c.logo != null) AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC15131F)))))
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0xCC15131F)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Resume", tint = Palette.Tally)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Tally))
            }
        }
        Text(c.name, Modifier.padding(top = 6.dp, start = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------ show page

@Composable
fun ShowScreen(vm: MainViewModel, show: Channel, onBack: () -> Unit, onPlay: (List<PlayEntry>, Int) -> Unit) {
    BackHandler(onBack = onBack)
    var info by remember(show.url) { mutableStateOf<Xtream.SeriesInfo?>(null) }
    var error by remember(show.url) { mutableStateOf<String?>(null) }
    var season by remember(show.url) { mutableStateOf<Int?>(null) }
    var plotOpen by remember(show.url) { mutableStateOf(false) }

    LaunchedEffect(show.url) {
        try {
            val i = vm.episodesFor(show)
            info = i
            season = i.episodes.firstOrNull()?.season
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load the episodes"
        }
    }

    fun playEpisodes(list: List<Xtream.Episode>, idx: Int) = onPlay(episodeQueue(show, list), idx)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        // Hero: artwork fading into the page, title set over it.
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                if (show.logo != null) {
                    AsyncImage(model = show.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x6615131F), 0.55f to Color(0xAA15131F), 1f to Palette.Ink)))
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(8.dp).size(44.dp).clip(CircleShape).background(Color(0x9915131F)),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(show.name, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    info?.let { i ->
                        val seasons = i.episodes.map { it.season }.distinct().size
                        Text("$seasons season${if (seasons == 1) "" else "s"}, ${i.episodes.size} episode${if (i.episodes.size == 1) "" else "s"}",
                            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                    }
                }
            }
        }
        val i = info
        when {
            error != null -> item { Message("Couldn't load episodes", error, "Try again", { error = null; info = null }) }
            i == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = Palette.Tally, trackColor = Palette.Dusk) }
            i.episodes.isEmpty() -> item { Message("No episodes yet", "The provider lists this show but hasn't added episodes.") }
            else -> {
                // Continue where you left off, or start from the top.
                val resumeIdx = i.episodes.indexOfFirst { vm.resumePosition(it.url) > 0 }
                val startIdx = if (resumeIdx >= 0) resumeIdx else 0
                val startEp = i.episodes[startIdx]
                item(key = "play") {
                    PrimaryButton(
                        (if (resumeIdx >= 0) "Continue " else "Play ") + episodeCode(startEp).ifEmpty { "episode 1" },
                        onClick = {
                            val inS = i.episodes.filter { it.season == startEp.season }
                            playEpisodes(inS, inS.indexOf(startEp).coerceAtLeast(0))
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                i.plot?.let { plot ->
                    item(key = "plot") {
                        Text(plot, Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable { plotOpen = !plotOpen },
                            style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
                            maxLines = if (plotOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                val seasons = i.episodes.map { it.season }.distinct()
                if (seasons.size > 1) item(key = "seasons") {
                    TextTabs(
                        tabs = seasons.map { s -> Triple<String?, String, Int?>(s.toString(), if (s == 0) "Other" else "Season $s", null) },
                        selected = season?.toString(),
                        onSelect = { season = it?.toIntOrNull() },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val inSeason = i.episodes.filter { it.season == season }
                itemsIndexed(inSeason, key = { _, e -> e.url }) { idx, e ->
                    EpisodeRow(e, vm.resumePosition(e.url) > 0) { playEpisodes(inSeason, idx) }
                }
            }
        }
    }
}

/** Episodes as a player queue; each is registered so the player can note progress through the show. */
fun episodeQueue(show: Channel, list: List<Xtream.Episode>): List<PlayEntry> = list.map { ep ->
    com.zapline.iptv.data.SeriesWatch.register(ep.url, com.zapline.iptv.data.SeriesWatch.Ref(show.url, show.name, show.logo, episodeCode(ep)))
    PlayEntry("vod|${ep.url}", episodeTitle(show, ep), listOf(Channel(episodeTitle(show, ep), ep.url, show.name, logo = ep.image ?: show.logo)))
}

private fun episodeCode(e: Xtream.Episode): String =
    if (e.season > 0 || e.number > 0) "S${e.season} E${e.number}" else ""

private fun episodeTitle(show: Channel, e: Xtream.Episode): String {
    val code = episodeCode(e)
    val t = e.title.removePrefix(show.name).trim(' ', '-', '|', ':')
    return listOfNotNull(show.name, code.ifEmpty { null }, t.takeIf { it.isNotEmpty() && !it.equals(code, true) }).joinToString("  ")
}

@Composable
private fun EpisodeRow(e: Xtream.Episode, started: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Palette.Dusk),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Palette.Faint)
            if (e.image != null) AsyncImage(model = e.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (started) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.4f).height(3.dp).background(Palette.Tally))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(if (e.number > 0) "Episode ${e.number}" else "Episode", style = MaterialTheme.typography.labelMedium,
                color = if (started) Palette.Tally else Palette.Muted)
            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (started) Text("In progress", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        }
    }
}
