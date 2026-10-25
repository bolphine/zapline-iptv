package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.desktop.player.PlayerState
import com.zapline.iptv.desktop.player.Tile
import com.zapline.iptv.lang.ChannelNames

/** What's playing, above the picture. */
@Composable
fun PlayerHeader(app: AppState, t: Tile) {
    val now by app.vm.now.collectAsState()
    val favs by app.vm.favourites.collectAsState()
    val e = t.entry
    val st = t.player.state
    val (cur, next) = EpgStore.data.nowNext(e?.epgId, now)
    Row(Modifier.fillMaxSize().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e == null) return@Row
        Logo(e.variants.firstNotNullOfOrNull { it.logo }, e.name, 56.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!t.isVod && t.queue.size > 1) Text("${t.index + 1}", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.titleLarge, color = Palette.Tally)
                Text(e.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!t.isVod) {
                    val entry = app.vm.view.value.entries.firstOrNull { it.key == e.key }
                    if (entry != null) {
                        val fav = app.vm.isFav(entry, favs)
                        ToolButton(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Remove favourite" else "Add favourite", active = fav, size = 34.dp) { app.vm.toggleFav(entry) }
                    }
                }
            }
            if (cur != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(cur.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ${hm(cur.start)}–${hm(cur.end)}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    next?.let { Text("   Next ${hm(it.start)} ${it.title}", style = MaterialTheme.typography.bodySmall, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                ProgressLine(cur, now, Modifier.padding(top = 6.dp).width(420.dp))
            } else t.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1) }
        }
        StreamBadges(st)
        val n = e.variants.size
        if (n > 1) {
            Spacer(Modifier.width(10.dp))
            Pill("${ChannelNames.versionLabel(t.channel?.name ?: "")}  ${t.variant + 1}/$n") { t.nextVersion() }
        }
    }
}

/** What the stream really is, read from the player. */
@Composable
fun StreamBadges(st: PlayerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        st.resolution?.let { Badge(it, if (it == "4K") Palette.Tally else Palette.TallyText, filled = it == "4K") }
        if (st.hdr) Badge(if (st.gamma == "hlg") "HLG" else "HDR", Palette.Tally)
        st.videoCodec?.let { Badge(com.zapline.iptv.desktop.player.Tile.codecLabel(it) ?: it, Palette.Muted) }
        if (st.fps >= 48) Badge("${Math.round(st.fps)} fps", Palette.Muted)
        when {
            st.audioChannels >= 8 -> Badge("7.1", Palette.Lilac)
            st.audioChannels >= 6 -> Badge("5.1", Palette.Lilac)
        }
        st.hwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { Badge("GPU", Palette.Faint) }
    }
}

/** Under the picture. */
@Composable
fun PlayerControls(app: AppState, t: Tile) {
    val st = t.player.state
    Row(Modifier.fillMaxSize().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.isVod) {
            ToolButton(Icons.Default.Replay10, "Back 10 s (←)") { t.player.seekRelative(-10.0) }
            ToolButton(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (st.paused) "Play (Space)" else "Pause (Space)", size = 48.dp) { t.player.togglePause() }
            ToolButton(Icons.Default.Forward10, "Forward 10 s (→)") { t.player.seekRelative(10.0) }
            Text(com.zapline.iptv.desktop.player.Tile.formatTime(st.position), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
            var drag by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = drag ?: if (st.duration > 0) (st.position / st.duration).toFloat() else 0f,
                onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { t.player.seekTo(it * st.duration) }; drag = null },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(thumbColor = Palette.Tally, activeTrackColor = Palette.Tally, inactiveTrackColor = Palette.LineSoft),
            )
            Text(if (st.duration > 0) com.zapline.iptv.desktop.player.Tile.formatTime(st.duration) else "--:--", style = MaterialTheme.typography.labelMedium)
        } else {
            ToolButton(Icons.Default.SkipPrevious, "Previous channel (↑)") { t.step(-1) }
            ToolButton(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (st.paused) "Play (Space)" else "Pause (Space)", size = 48.dp) { t.player.togglePause() }
            ToolButton(Icons.Default.SkipNext, "Next channel (↓)") { t.step(1) }
            StartOverButton(app, t)
            Text(
                t.status ?: when {
                    st.error != null -> st.error
                    st.buffering -> "Buffering…"
                    st.cacheSeconds > 0.5 -> "Live · ${Math.round(st.cacheSeconds)} s buffered"
                    else -> ""
                },
                Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = if (st.error != null) Palette.Alert else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Sound
        ToolButton(if (st.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (st.muted) "Unmute (M)" else "Mute (M)") { t.player.setMuted(!st.muted) }
        Slider(
            value = st.volume / 100f, onValueChange = { t.player.setVolume((it * 100).toInt()) },
            modifier = Modifier.width(120.dp),
            colors = SliderDefaults.colors(thumbColor = Palette.Text, activeTrackColor = Palette.Text, inactiveTrackColor = Palette.LineSoft),
        )
        TrackMenu(t, "audio", Icons.Default.Audiotrack, "Audio track (A)")
        TrackMenu(t, "sub", Icons.Default.ClosedCaption, "Subtitles (C)")
        FitMenu(app)
        Spacer(Modifier.width(8.dp))
        ToolButton(Icons.Default.Theaters, "Theatre (T)", active = app.mode == Mode.THEATRE) { app.toggleTheatre() }
        ToolButton(Icons.Default.GridView, "Multiview (V)", active = app.mode == Mode.MULTIVIEW) { app.enterMultiview() }
        ToolButton(Icons.Default.Fullscreen, "Fullscreen (F)") { app.toggleFullscreen() }
    }
}

@Composable
private fun StartOverButton(app: AppState, t: Tile) {
    val e = t.entry ?: return
    val entry = app.vm.view.value.entries.firstOrNull { it.key == e.key } ?: return
    val now by app.vm.now.collectAsState()
    val cur = EpgStore.data.nowNext(e.epgId, now).first ?: return
    if (!app.vm.canCatchUp(entry, cur, now)) return
    ToolButton(Icons.Default.Replay, "Start over: watch \"${cur.title}\" from the beginning") {
        app.vm.catchupEntry(entry, cur)?.let { app.play(listOf(it), 0) }
    }
}

@Composable
private fun TrackMenu(t: Tile, type: String, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    var open by remember { mutableStateOf(false) }
    val tracks = t.player.state.tracks.filter { it.type == type }
    Box {
        ToolButton(icon, label, active = type == "sub" && tracks.any { it.selected }, enabled = tracks.isNotEmpty()) { open = true }
        DropdownMenu(open, { open = false }, containerColor = Palette.Night) {
            if (type == "sub") DropdownMenuItem(text = { Text("Off", color = if (tracks.none { it.selected }) Palette.Tally else Palette.Text) },
                onClick = { open = false; t.player.selectSubtitle(null) })
            tracks.forEach { tr ->
                DropdownMenuItem(text = { Text(tr.label, color = if (tr.selected) Palette.Tally else Palette.Text) },
                    onClick = { open = false; if (type == "sub") t.player.selectSubtitle(tr.id) else t.player.selectAudio(tr.id) })
            }
        }
    }
}

@Composable
private fun FitMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Default.AspectRatio, "Picture: ${app.fit.title} (Z)", active = app.fit != Fit.FIT) { open = true }
        DropdownMenu(open, { open = false }, containerColor = Palette.Night) {
            Fit.entries.forEach { f ->
                DropdownMenuItem(text = {
                    Column {
                        Text(f.title, color = if (f == app.fit) Palette.Tally else Palette.Text)
                        Text(when (f) {
                            Fit.FIT -> "Whole picture, bars at the sides"
                            Fit.FILL219 -> "Zoom to 21:9: less bar, crops a little top and bottom"
                            Fit.FILLWIDTH -> "Fill the whole width: crops the most"
                            Fit.STRETCH -> "Fill everything by stretching"
                        }, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    }
                }, onClick = { open = false; app.chooseFit(f) })
            }
        }
    }
}

/** A channel row used in lists beside fullscreen and in pickers. */
@Composable
fun CompactChannelRow(app: AppState, e: com.zapline.iptv.ui.Entry, now: Long, playing: Boolean, number: Int? = null, onClick: () -> Unit) {
    val cur = EpgStore.data.nowNext(app.vm.epgIdFor(e), now).first
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (playing) Palette.TallyDim else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        number?.let { Text("$it", Modifier.width(34.dp), style = MaterialTheme.typography.labelLarge, color = Palette.Tally) }
        Logo(e.logo, e.name, 36.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold)
            cur?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        QualityBadges(e.info, compact = true)
    }
}
