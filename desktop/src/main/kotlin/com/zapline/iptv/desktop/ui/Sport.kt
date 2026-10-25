package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.Sports
import kotlinx.coroutines.delay

private fun Sports.Match.status(): String = when (state) {
    "in" -> clock.ifBlank { detail }
    "post" -> "FT"
    else -> if (start > 0) hm(start) else detail
}

/** The whole Sport page: today's games, with where to watch each. */
@Composable
fun SportPage(app: AppState) {
    val vm = app.vm
    val s by vm.sport.collectAsState()
    DisposableEffect(Unit) { vm.watchSport(); onDispose { if (app.sideTab != SideTab.MATCH) vm.stopSport() } }
    var open by remember { mutableStateOf<Sports.Match?>(null) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sport today", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (s.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Tally, strokeWidth = 2.dp)
                else if (s.updatedAt > 0) Text("Updated ${hm(s.updatedAt)}", style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
            }
            Spacer(Modifier.height(16.dp))
            when {
                s.matches.isEmpty() && s.loading -> EmptyState("Finding today's games…")
                s.matches.isEmpty() && s.failed -> EmptyState("Couldn't load scores", "Check your internet connection.")
                s.matches.isEmpty() -> EmptyState("No games today", "Football from the big leagues shows here on match days.")
                else -> {
                    val byLeague = s.matches.groupBy { it.league }
                    LazyVerticalGrid(GridCells.Adaptive(440.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        byLeague.forEach { (league, ms) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "l$league") { SectionLabel(league, Modifier.padding(top = 8.dp)) }
                            items(ms, key = { it.id }) { m ->
                                MatchCard(m, s.listings[m.id].orEmpty().size, selected = open?.id == m.id) { open = m }
                            }
                        }
                    }
                }
            }
        }
        open?.let { m ->
            VDivider()
            Column(Modifier.width(720.dp).fillMaxHeight().background(Palette.Ink)) {
                val live = s.matches.firstOrNull { it.id == m.id } ?: m
                val where = s.listings[m.id].orEmpty()
                if (where.isNotEmpty()) {
                    SectionLabel("Watch on", Modifier.padding(top = 16.dp))
                    where.take(6).forEach { (e, p) ->
                        CompactChannelRow(app, e, vm.now.value, e.key == app.playback.main.entry?.key) { app.watchLive(e) }
                    }
                } else Text("Not found on your channels' TV guide.", Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                MatchDetail(live, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MatchCard(m: Sports.Match, channels: Int, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Night)
            .border(1.dp, if (selected) Palette.Tally else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.status(), style = MaterialTheme.typography.labelLarge, color = if (m.isLive) Palette.Tally else Palette.Muted)
            if (m.isLive) { Spacer(Modifier.width(8.dp)); Badge("LIVE", Palette.Tally, filled = true) }
            Spacer(Modifier.weight(1f))
            if (channels > 0) Text("On $channels channel${if (channels == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = Palette.TallyText)
        }
        Spacer(Modifier.height(10.dp))
        TeamLine(m.home, m.state != "pre")
        Spacer(Modifier.height(6.dp))
        TeamLine(m.away, m.state != "pre")
    }
}

@Composable
private fun TeamLine(t: Sports.Team, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(t.logo, t.name, 30.dp)
        Text(t.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showScore) Text(t.score, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Score, events, stats and line-ups for one game; refreshes while it's live. */
@Composable
fun MatchDetail(m: Sports.Match, modifier: Modifier = Modifier) {
    var d by remember(m.id) { mutableStateOf<Sports.Detail?>(null) }
    LaunchedEffect(m.id, m.isLive) {
        while (true) {
            d = Sports.detail(m) ?: d
            if (!m.isLive) break
            delay(60_000)
        }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text(m.league, style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(m.home.logo, m.home.name, 56.dp)
                Text(m.home.short.ifBlank { m.home.name }, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (m.state == "pre") "v" else "${m.home.score} – ${m.away.score}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(m.status(), style = MaterialTheme.typography.labelLarge, color = if (m.isLive) Palette.Tally else Palette.Muted)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(m.away.logo, m.away.name, 56.dp)
                Text(m.away.short.ifBlank { m.away.name }, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
        val detail = d
        val events = detail?.events ?: m.events.map { Sports.KeyEvent(it.minute, it.kind.name.lowercase(), it.home, it.text, listOfNotNull(it.player)) }
        if (events.isNotEmpty()) {
            SectionLabel("Key moments")
            events.forEach { ev ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = when {
                        ev.kind.contains("goal", true) -> "⚽"
                        ev.kind.contains("red", true) -> "🟥"
                        ev.kind.contains("yellow", true) -> "🟨"
                        ev.kind.contains("sub", true) -> "⇄"
                        else -> "•"
                    }
                    val txt = ev.players.joinToString(", ").ifBlank { ev.text }
                    if (ev.home == false) Spacer(Modifier.weight(1f))
                    Text("${ev.minute}  $icon  $txt", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (ev.home == false) TextAlign.End else TextAlign.Start)
                }
            }
        }
        val stats = detail?.stats ?: m.stats
        if (stats.isNotEmpty()) {
            SectionLabel("Stats")
            stats.forEach { st ->
                val h = st.home.trimEnd('%').toFloatOrNull() ?: 0f; val a = st.away.trimEnd('%').toFloatOrNull() ?: 0f
                val f = if (h + a > 0) h / (h + a) else 0.5f
                Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
                    Row { Text(st.home, style = MaterialTheme.typography.labelLarge); Text(st.label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = Palette.Muted); Text(st.away, style = MaterialTheme.typography.labelLarge) }
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).clip(RoundedCornerShape(2.dp))) {
                        Box(Modifier.weight(f.coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Palette.Tally))
                        Box(Modifier.weight((1 - f).coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Palette.Lilac.copy(alpha = 0.5f)))
                    }
                }
            }
        }
        if (detail?.home != null || detail?.away != null) {
            SectionLabel("Line-ups")
            Row(Modifier.padding(horizontal = 18.dp)) {
                listOf(detail.home, detail.away).forEach { l ->
                    Column(Modifier.weight(1f)) {
                        l?.let {
                            Text(listOfNotNull(it.team, it.formation).joinToString("  "), style = MaterialTheme.typography.labelLarge, color = Palette.TallyText)
                            it.starters.forEach { p -> Text("${p.number.padStart(2)}  ${p.name}${if (p.subbedOut) "  ↓" else ""}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (!detail?.table.isNullOrEmpty()) {
            SectionLabel(detail!!.tableName ?: "Table")
            detail.table.forEach { r ->
                val inGame = r.teamId == m.home.id || r.teamId == m.away.id
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
                    val c = if (inGame) Palette.Tally else Palette.Text
                    Text("${r.position}", Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, color = c)
                    Text(r.team, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c, maxLines = 1)
                    listOf(r.played, r.goalDiff, r.points).forEach { Text(it, Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = c, textAlign = TextAlign.End) }
                }
            }
        }
    }
}
