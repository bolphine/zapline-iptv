package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The whole window. */
@Composable
fun AppShell(app: AppState) {
    val hasSource by app.vm.hasSource.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ink)) {
        if (!hasSource || app.setup) { SetupScreen(app); return@BoxWithConstraints }
        val main = app.playback.main
        val playing = main.entry != null
        val g = Geometry.compute(app.mode, app.section, playing, app.fullscreenPanels, maxWidth, maxHeight)

        when (app.mode) {
            Mode.FULLSCREEN -> {
                g.fsLeft?.let { r -> Box(r.mod().background(Palette.Ink)) { FullscreenSideList(app) } }
                g.fsRight?.let { r -> Box(r.mod().background(Palette.Ink)) { FullscreenSideInfo(app) } }
            }
            Mode.MULTIVIEW -> MultiviewChrome(app, maxWidth, maxHeight)
            else -> {
                g.rail?.let { r -> NavRail(app, r) }
                g.left?.let { r -> Box(r.mod()) { LeftPanel(app) } }
                g.header?.let { r -> Box(r.mod()) { PlayerHeader(app, main) } }
                g.controls?.let { r -> Box(r.mod()) { PlayerControls(app, main) } }
                g.side?.let { r -> Box(r.mod()) { SidePanel(app) } }
                g.content?.let { r -> Box(r.mod()) { ContentPage(app) } }
                g.miniBar?.let { r -> Box(r.mod()) { MiniPlayerBar(app) } }
                // Nothing playing yet: an inviting empty picture area.
                if (Geometry.playerSection(app.section) && app.mode == Mode.BROWSE && !playing && g.header != null) {
                    val v = g.video
                    Box(v.mod().clip(RoundedCornerShape(16.dp)).background(Palette.Night), contentAlignment = Alignment.Center) {
                        EmptyState("Pick a channel", "Double-click the picture or press F for fullscreen, T for theatre, V for multiview.")
                    }
                }
            }
        }

        // The main picture: always this one call, so changing layout only moves it (no restart).
        val mv = if (app.mode == Mode.MULTIVIEW) Geometry.multiview(app.playback.tiles.size, maxWidth, maxHeight).first().second else g.video
        val visible = playing || app.mode == Mode.MULTIVIEW
        VideoSurface(main, app.fit, (if (visible) mv else Rect.NONE).mod(), app.playerOptions()) { app.onVideoMessage(0, it) }

        if (app.mode == Mode.MULTIVIEW) {
            val rects = Geometry.multiview(app.playback.tiles.size, maxWidth, maxHeight)
            app.playback.extra.forEachIndexed { i, t ->
                key(t.id) {
                    // Only once it has a channel, so the empty tile's "choose a channel" box isn't covered.
                    if (t.entry != null) rects.getOrNull(i + 1)?.let { (_, v, _) -> VideoSurface(t, Fit.FIT, v.mod(), app.playerOptions()) { app.onVideoMessage(i + 1, it) } }
                }
            }
        }

        // Toasts and the reminder prompt sit along the bottom edge, clear of the picture where possible.
        Overlays(app, maxWidth, maxHeight, g)
        app.programme?.let { ProgrammeDialog(app, it.first, it.second) }
    }
}

private val sections = listOf(
    Section.LIVE to Icons.Default.LiveTv, Section.FOURK to Icons.Default.Hd, Section.GUIDE to Icons.Default.DateRange,
    Section.FILMS to Icons.Default.Movie, Section.SERIES to Icons.Default.VideoLibrary, Section.SPORT to Icons.Default.SportsSoccer,
    Section.SEARCH to Icons.Default.Search,
)

@Composable
private fun NavRail(app: AppState, r: Rect) {
    Column(r.mod().background(Palette.Ink).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Z", style = MaterialTheme.typography.headlineMedium, color = Palette.Tally, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        sections.forEach { (s, icon) -> RailItem(icon, s.title, app.section == s) { app.section = s; app.show = null } }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Default.Settings, "Settings", app.section == Section.SETTINGS) { app.section = Section.SETTINGS }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Palette.TallyDim else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(24.dp), tint = if (on) Palette.Tally else Palette.Muted)
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (on) Palette.TallyText else Palette.Muted, maxLines = 1)
    }
    Spacer(Modifier.height(4.dp))
}

/** Left column next to the picture: depends on the section. */
@Composable
private fun LeftPanel(app: AppState) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).background(Palette.Ink)) {
            when (app.section) {
                Section.FOURK -> FourKPanel(app)
                Section.SEARCH -> SearchPanel(app)
                else -> ChannelPanel(app)
            }
        }
        VDivider()
    }
}

/** Full-width pages. */
@Composable
private fun ContentPage(app: AppState) {
    when (app.section) {
        Section.GUIDE -> GuidePage(app)
        Section.FILMS -> VodPage(app, series = false)
        Section.SERIES -> VodPage(app, series = true)
        Section.SPORT -> SportPage(app)
        Section.SETTINGS -> SettingsPage(app)
        else -> {}
    }
}

/** Above the mini player: what's on, and buttons to go back to it or stop. */
@Composable
private fun MiniPlayerBar(app: AppState) {
    val t = app.playback.main
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(Palette.Dusk).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.entry?.name ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ToolButton(Icons.Default.OpenInFull, "Back to the player") { app.section = if (t.isVod) Section.LIVE else Section.LIVE }
        ToolButton(Icons.Default.Fullscreen, "Fullscreen (F)") { app.mode = Mode.FULLSCREEN }
        ToolButton(Icons.Default.Close, "Stop") { app.playback.stopAll() }
    }
}

@Composable
private fun Overlays(app: AppState, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, g: Geometry) {
    // In fullscreen the picture covers the window, so messages go to mpv's on-screen text instead.
    if (app.mode == Mode.FULLSCREEN && app.fullscreenPanels.not()) {
        app.toast?.let { androidx.compose.runtime.LaunchedEffect(it) { app.playback.main.player.showText(it) } }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.Start) {
        app.dueReminder?.let { r ->
            Row(
                Modifier.padding(start = Geometry.RAIL).clip(RoundedCornerShape(14.dp)).background(Palette.Dusk)
                    .border(1.dp, Palette.Tally.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(360.dp)) {
                    Text("Starting now", style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
                    Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("on ${r.channelName}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                Pill("Watch", accent = true) {
                    app.dueReminder = null
                    app.scope.launchSafe { app.vm.entryForKey(r.channelKey)?.let { app.watchLive(it) } }
                }
                Spacer(Modifier.width(8.dp))
                Pill("Dismiss") { app.dueReminder = null }
            }
            Spacer(Modifier.height(10.dp))
        }
        app.toast?.let {
            Text(it, Modifier.padding(start = Geometry.RAIL).clip(RoundedCornerShape(12.dp)).background(Palette.Haze).padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall)
        }
    }
}

fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) = launch { try { block() } catch (_: Exception) {} }
