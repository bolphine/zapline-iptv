package com.zapline.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Sports
import com.zapline.iptv.player.PlayEntry
import kotlinx.coroutines.delay

/** Today's football: live, later and finished, each with the channels showing it from your TV guide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportScreen(vm: MainViewModel, onBack: () -> Unit, onPlay: (List<PlayEntry>, Int) -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { vm.watchSport(); onDispose { vm.stopSport() } }
    val st by vm.sport.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val now by vm.now.collectAsState()
    var league by rememberSaveable { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Sports.Match?>(null) }

    val leagues = st.matches.map { it.league }.distinct()
    val shown = st.matches.filter { league == null || it.league == league }
    // Games on your channels first within each section.
    fun section(state: String) = shown.filter { it.state == state }
        .sortedWith(compareBy<Sports.Match> { if (st.listings[it.id].isNullOrEmpty()) 1 else 0 }.thenBy { it.start })

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sport", onBack = onBack) {
            IconButton(onClick = { vm.stopSport(); vm.watchSport() }) { Icon(Icons.Default.Refresh, "Refresh scores", tint = Palette.Text) }
        }
        if (st.loading && st.matches.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Tally, trackColor = Palette.Dusk)
        if (leagues.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterPill("All", league == null, { league = null }, dropdown = false) }
                items(leagues) { l -> FilterPill(l, league == l, { league = if (league == l) null else l }, dropdown = false) }
            }
        }
        when {
            st.failed -> Message("Scores couldn't be reached", "They come from ESPN's public feed, which may be down.", "Try again", { vm.stopSport(); vm.watchSport() })
            !st.loading && st.matches.isEmpty() -> Message("No games today", "Nothing in the covered competitions today.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                listOf("in" to "Live now", "pre" to "Later today", "post" to "Finished").forEach { (state, title) ->
                    val list = section(state)
                    if (list.isNotEmpty()) {
                        item(key = "h$state") { SectionLabel(title) }
                        items(list, key = { it.id }) { m ->
                            MatchCard(m, onClick = { open = m }) {
                                Listings(vm, m, st.listings[m.id].orEmpty(), reminders, now, onPlay)
                            }
                        }
                    }
                }
                item { Text("Scores from ESPN. Channels found from your TV guide.", Modifier.padding(20.dp),
                    style = MaterialTheme.typography.labelSmall, color = Palette.Faint) }
            }
        }
    }

    open?.let { m0 ->
        val m = st.matches.firstOrNull { it.id == m0.id } ?: m0
        var detail by remember(m.id) { mutableStateOf<Sports.Detail?>(null) }
        var loading by remember(m.id) { mutableStateOf(true) }
        LaunchedEffect(m.id) {
            while (true) {
                Sports.detail(m)?.let { detail = it }
                loading = false
                if (m.state == "post") break
                delay(45_000)
            }
        }
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Night) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                MatchHeader(m)
                Listings(vm, m, st.listings[m.id].orEmpty(), reminders, now, onPlay, Modifier.padding(horizontal = 14.dp))
                MatchDetailView(m, detail, loading)
            }
        }
    }
}

/** Channels showing a game: tap to watch, bell to be reminded at kick-off. */
@Composable
private fun Listings(
    vm: MainViewModel, m: Sports.Match, listings: List<Pair<Entry, Programme>>, reminders: List<com.zapline.iptv.data.Reminder>,
    now: Long, onPlay: (List<PlayEntry>, Int) -> Unit, modifier: Modifier = Modifier,
) {
    if (listings.isEmpty()) {
        if (m.state != "post") Text("Not found in your TV guide", modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        return
    }
    Column(modifier.padding(top = 8.dp)) {
        listings.forEach { (e, p) ->
            val airing = now in p.start until p.end
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(if (airing) Palette.TallyDim else Palette.Dusk)
                    .clickable { onPlay(listOf(e.toPlay(vm.epgIdFor(e))), 0) }.padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = if (airing) Palette.Tally else Palette.Muted, modifier = Modifier.size(18.dp))
                ChannelLogo(e.logo, e.name, 26.dp, Modifier.padding(start = 6.dp))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(e.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (airing) "On now" else "${hm(p.start)}${dayWord(p.start, now)}", style = MaterialTheme.typography.labelSmall,
                        color = if (airing) Palette.Tally else Palette.Muted)
                }
                e.info?.resolution?.let { Text(it, Modifier.padding(end = 6.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Muted) }
                if (p.start > now) {
                    val set = vm.hasReminder(reminders, e, p)
                    IconButton(onClick = { if (set) vm.removeReminder(e, p) else vm.addReminder(e, p) }) {
                        Icon(if (set) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                            if (set) "Remove reminder" else "Remind me", tint = if (set) Palette.Tally else Palette.Muted)
                    }
                }
            }
        }
    }
}
