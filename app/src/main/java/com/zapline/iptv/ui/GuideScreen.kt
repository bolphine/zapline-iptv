package com.zapline.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zapline.iptv.data.Programme
import com.zapline.iptv.player.PlayEntry
import kotlinx.coroutines.launch

private val CHANNEL_COL = 92.dp
private val ROW_H = 60.dp
private const val DP_PER_MIN = 4f          // 30 minutes = 120dp
private const val HALF_HOUR = 30 * 60_000L

/**
 * Classic TV guide: channels down the left, time across the top, a red "now" line.
 * Uses the same filters as the Live TV list. One horizontal scroll state is shared by the header and every row,
 * so the whole grid moves together.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(vm: MainViewModel, onBack: () -> Unit, onPlay: (List<PlayEntry>, Int) -> Unit) {
    BackHandler(onBack = onBack)
    val view by vm.view.collectAsState()
    val epg by vm.epg.collectAsState()
    val epgIds by vm.epgIds.collectAsState()
    val now by vm.now.collectAsState()
    val epgStatus by vm.epgStatus.collectAsState()
    val epgError by vm.epgError.collectAsState()
    var onlyWithGuide by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Pair<Entry, Programme>?>(null) }

    // Timeline: 24 hours ahead. When any channel keeps catch-up, the guide also scrolls back a day.
    val hasCatchup = remember(view.entries) { view.entries.any { e -> e.variants.any { it.ch.catchupDays > 0 } } }
    val back = if (hasCatchup) 24 * 3600_000L else HALF_HOUR
    val start = remember(now / HALF_HOUR, back) { (now / HALF_HOUR) * HALF_HOUR - back }
    val end = (now / HALF_HOUR) * HALF_HOUR + 24 * 3600_000L
    val totalWidth = (((end - start) / 60_000f) * DP_PER_MIN).dp
    fun xOf(t: Long) = (((t - start) / 60_000f) * DP_PER_MIN).dp

    val rows = remember(view.entries, epgIds, onlyWithGuide) {
        if (onlyWithGuide && epgIds.isNotEmpty()) view.entries.filter { epgIds.containsKey(it.key) } else view.entries
    }
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val nowPx = with(density) { (xOf(now) - 40.dp).toPx() }.toInt().coerceAtLeast(0)
    LaunchedEffect(start) { hScroll.scrollTo(nowPx) }

    fun playRow(i: Int) = onPlay(rows.map { it.toPlay(epgIds[it.key]) }, i)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("TV guide", onBack = onBack, large = true) {
            TextButton(onClick = { scope.launch { hScroll.animateScrollTo(nowPx) } }) { Text("Now", color = Palette.Tally) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill("Only channels with guide", onlyWithGuide, { onlyWithGuide = !onlyWithGuide }, dropdown = false)
            Text(
                "${rows.size} channels, filtered like Live TV",
                Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint,
            )
        }
        if (epgStatus.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(epgStatus, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (epg.channelCount == 0 && epgStatus.isEmpty()) {
            Message(
                if (epgError != null) "TV guide didn't load" else "No TV guide yet",
                epgError ?: "It normally loads in the background after the playlist. You can load it now.",
                "Load TV guide", { vm.refreshEpg() },
            )
            return@Column
        }

        // time header
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.width(CHANNEL_COL))
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Box(Modifier.width(totalWidth).fillMaxHeight()) {
                    var t = start
                    while (t < end) {
                        Text(hm(t), Modifier.offset(x = xOf(t) + 4.dp).padding(top = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        t += HALF_HOUR
                    }
                    Box(Modifier.offset(x = xOf(now)).width(2.dp).fillMaxHeight().background(NowRed))
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(rows, key = { _, e -> e.key }) { i, e ->
                Row(Modifier.fillMaxWidth().height(ROW_H)) {
                    // channel cell: tap to watch
                    Column(
                        Modifier.width(CHANNEL_COL).fillMaxHeight().clickable { playRow(i) }.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ChannelLogo(e.logo, e.name, 34.dp)
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                        Box(Modifier.width(totalWidth).fillMaxHeight()) {
                            val progs = epg.programmes(epgIds[e.key]).filter { it.end > start && it.start < end }
                            if (progs.isEmpty()) {
                                Text("No guide information", Modifier.offset(x = xOf(now)).padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            for (p in progs) {
                                val x0 = xOf(maxOf(p.start, start)); val x1 = xOf(minOf(p.end, end))
                                val airing = now in p.start until p.end
                                val past = p.end <= now
                                val canCatch = past && hasCatchup && vm.canCatchUp(e, p, now)
                                Box(
                                    Modifier.offset(x = x0).width(x1 - x0).fillMaxHeight().padding(2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (airing) Palette.TallyDim else if (past && !canCatch) Palette.Ink else Palette.Night)
                                        .border(1.dp, if (airing) Palette.Tally.copy(alpha = 0.45f) else Palette.LineSoft, RoundedCornerShape(10.dp))
                                        .combinedClickable(
                                            onClick = { if (airing) playRow(i) else selected = e to p },
                                            onLongClick = { selected = e to p },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                ) {
                                    Column {
                                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (past && !canCatch) Palette.Faint else Palette.Text,
                                            fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal)
                                        Text((if (canCatch) "\u21BA " else "") + "${hm(p.start)} – ${hm(p.end)}", maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                            color = if (airing) Palette.Tally else Palette.Muted)
                                    }
                                }
                            }
                            Box(Modifier.offset(x = xOf(now)).width(2.dp).fillMaxHeight().background(NowRed))
                        }
                    }
                }
            }
        }
    }

    selected?.let { (e, p) ->
        ProgrammeDialog(vm, e, p, onDismiss = { selected = null },
            onWatchLive = { playRow(rows.indexOf(e).coerceAtLeast(0)) }, onPlay = onPlay)
    }
}

/** The now-line uses the on-air amber, like every other "live right now" mark in the app. */
private val NowRed: Color get() = Palette.Tally
