package com.zapline.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zapline.iptv.data.Sports

private val YellowCard = Color(0xFFFFD43B)
private val RedCard = Color(0xFFE5484D)

/** Scoreboard for one game: crests, names, score or kick-off time, status. */
@Composable
fun MatchHeader(m: Sports.Match, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(m.league, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Text(statusText(m), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelLarge,
            color = if (m.isLive) Palette.Tally else Palette.Muted)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Crest(m.home, Modifier.weight(1f))
            Text(if (m.state == "pre") hm(m.start.takeIf { it > 0 } ?: System.currentTimeMillis()) else "${m.home.score}  ${m.away.score}",
                Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.displaySmall)
            Crest(m.away, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Crest(t: Sports.Team, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(t, 44)
        Text(t.short, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TeamLogo(t: Sports.Team, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        if (t.logo != null) AsyncImage(model = t.logo, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
        else Text(t.abbr.take(3), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
    }
}

fun statusText(m: Sports.Match): String = when (m.state) {
    "in" -> "LIVE ${m.clock}".trim()
    "pre" -> if (m.start > 0) "Kick-off ${hm(m.start)}" else m.detail
    else -> m.detail.ifEmpty { "Full time" }
}

/**
 * The full picture of a game: stats, line-ups, key moments, live text and the table.
 * [detail] null while loading (or if the feed has nothing).
 */
@Composable
fun MatchDetailView(m: Sports.Match, detail: Sports.Detail?, loading: Boolean) {
    var tab by rememberSaveable(m.id) { mutableStateOf(if (m.state == "pre") 3 else 0) }
    val tabs = listOf("Stats", "Line-ups", "Moments", "Table", "Live text")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, t -> FilterPill(t, tab == i, { tab = i }, dropdown = false) }
    }
    if (detail == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(color = Palette.Tally, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            else Text("No details for this game yet.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    when (tab) {
        0 -> StatsTab(m, detail)
        1 -> LineupsTab(detail)
        2 -> MomentsTab(detail)
        3 -> TableTab(m, detail)
        else -> LiveTextTab(detail)
    }
    if (detail.venue != null) {
        Text(listOfNotNull(detail.venue, detail.attendance.takeIf { it > 0 }?.let { "attendance ${"%,d".format(it)}" }).joinToString(" · "),
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
    }
}

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun StatsTab(m: Sports.Match, d: Sports.Detail) {
    if (d.stats.isEmpty()) return Empty(if (m.state == "pre") "Stats appear once the game kicks off." else "No stats for this game yet.")
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(m.home.abbr, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
        Text(m.away.abbr, style = MaterialTheme.typography.labelMedium, color = Palette.Lilac)
    }
    d.stats.forEach { s -> StatRow(s) }
}

@Composable
private fun StatRow(s: Sports.Stat) {
    val h = s.home.trimEnd('%').toFloatOrNull() ?: 0f
    val a = s.away.trimEnd('%').toFloatOrNull() ?: 0f
    val total = (h + a).takeIf { it > 0 } ?: 1f
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.home, Modifier.width(56.dp), style = MaterialTheme.typography.titleSmall, fontWeight = if (h > a) FontWeight.Bold else FontWeight.Normal)
            Text(s.label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Text(s.away, Modifier.width(56.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.titleSmall, fontWeight = if (a > h) FontWeight.Bold else FontWeight.Normal)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Dusk), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth(h / total).fillMaxHeight().background(Palette.Tally))
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Dusk)) {
                Box(Modifier.fillMaxWidth(a / total).fillMaxHeight().background(Palette.Lilac))
            }
        }
    }
}

@Composable
private fun LineupsTab(d: Sports.Detail) {
    val h = d.home; val a = d.away
    if (h == null && a == null || (h?.starters.isNullOrEmpty() && a?.starters.isNullOrEmpty())) return Empty("Line-ups are usually out about an hour before kick-off.")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(h, a).forEach { l ->
            Column(Modifier.weight(1f)) {
                if (l == null) return@Column
                Text(l.team, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.formation?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Tally) }
                Spacer(Modifier.height(6.dp))
                l.starters.forEach { PlayerLine(it) }
                if (l.subs.isNotEmpty()) {
                    Text("Bench", Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
                    l.subs.forEach { PlayerLine(it) }
                }
            }
        }
    }
}

@Composable
private fun PlayerLine(p: Sports.Player) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.number, Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
        Text(p.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (p.subbedOut) Palette.Muted else Palette.Text)
        if (p.subbedIn) Text(" ↑", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CC38A))
        if (p.subbedOut) Text(" ↓", style = MaterialTheme.typography.labelSmall, color = RedCard)
    }
}

@Composable
private fun MomentsTab(d: Sports.Detail) {
    if (d.events.isEmpty()) return Empty("Goals, cards and substitutions appear here.")
    d.events.forEach { e ->
        val right = e.home == false
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.minute, Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Row(Modifier.weight(1f), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                if (!right) MomentIcon(e.kind)
                Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
                    Text(
                        when (e.kind) {
                            "goal" -> e.players.firstOrNull() ?: "Goal"
                            "sub" -> e.players.getOrNull(0)?.let { "$it on" } ?: "Substitution"
                            "ht" -> "Half time"; "ft" -> "Full time"
                            else -> e.players.firstOrNull() ?: e.text
                        },
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (e.kind == "goal") FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val sub = when (e.kind) {
                        "goal" -> e.players.getOrNull(1)?.let { "Assist: $it" }
                        "sub" -> e.players.getOrNull(1)?.let { "$it off" }
                        else -> null
                    }
                    sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted) }
                }
                if (right) MomentIcon(e.kind)
            }
        }
    }
}

@Composable
private fun MomentIcon(kind: String) {
    when (kind) {
        "goal" -> Text("⚽", style = MaterialTheme.typography.bodyMedium)
        "yellow" -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(YellowCard))
        "red" -> Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(RedCard))
        "sub" -> Text("⇄", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CC38A))
        else -> Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Faint))
    }
}

@Composable
private fun TableTab(m: Sports.Match, d: Sports.Detail) {
    if (d.table.isEmpty()) return Empty("No league table for this competition.")
    d.tableName?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("#", Modifier.width(26.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Text("Team", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        listOf("P", "W", "D", "L", "GD", "Pts").forEach { Text(it, Modifier.width(30.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = Palette.Faint) }
    }
    d.table.forEach { r ->
        val mine = r.teamId == m.home.id || r.teamId == m.away.id
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
                .background(if (mine) Palette.TallyDim else Color.Transparent).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${r.position}", Modifier.width(26.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Text(r.team, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
            listOf(r.played, r.won, r.drawn, r.lost, r.goalDiff).forEach {
                Text(it, Modifier.width(30.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
            Text(r.points, Modifier.width(30.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LiveTextTab(d: Sports.Detail) {
    if (d.commentary.isEmpty()) return Empty("Live text starts at kick-off.")
    d.commentary.take(60).forEach { (t, text) ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Text(t, Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One game in a list: crests, names, score/time, status, plus optional extra content (channels). */
@Composable
fun MatchCard(m: Sports.Match, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Night)
            .border(1.dp, if (m.isLive) Palette.Tally.copy(alpha = 0.45f) else Palette.LineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.league, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Palette.Faint, maxLines = 1)
            Text(statusText(m), style = MaterialTheme.typography.labelMedium, color = if (m.isLive) Palette.Tally else Palette.Muted)
        }
        listOf(m.home, m.away).forEach { t ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(t, 22)
                Text(t.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.state != "pre") Text(t.score, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        extra()
    }
}
