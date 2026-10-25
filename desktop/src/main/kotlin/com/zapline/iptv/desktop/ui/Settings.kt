package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zapline.iptv.data.Programme
import com.zapline.iptv.desktop.APP_VERSION
import com.zapline.iptv.desktop.player.Mpv
import com.zapline.iptv.lang.Languages
import com.zapline.iptv.ui.Entry
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// ------------------------------------------------------------------------------------------------ small controls

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", secret: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Box(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Night)
                .border(1.dp, Palette.Line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = Palette.Faint, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Tally), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SettingSwitch(title: String, body: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!on) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted) }
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Tally, checkedThumbColor = Palette.OnTally))
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Night).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ------------------------------------------------------------------------------------------------ settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPage(app: AppState) {
    val vm = app.vm
    val prefs = app.prefs
    val playlists by vm.playlists.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()
    val epgStatus by vm.epgStatus.collectAsState()
    val epgErr by vm.epgError.collectAsState()
    val highest by vm.preferHighest.collectAsState()
    val scan by vm.healthScan.collectAsState()
    var epgUrl by remember { mutableStateOf(prefs.epgUrl) }
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        // Two columns of cards: there's plenty of width on an ultrawide.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), maxItemsInEachRow = 3) {
            val w = Modifier.width(760.dp)
            Box(w) {
                Card("Playlist") {
                    val src = when (prefs.sourceType) {
                        "xtream" -> "Xtream: ${prefs.xtUser} at ${prefs.xtServer}"
                        "m3u" -> "M3U link: ${prefs.m3uUrl}"
                        "file" -> "File: ${prefs.fileName.ifBlank { prefs.fileUri }}"
                        else -> "None"
                    }
                    Text(src, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    err?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Alert) }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (loading) "Loading…" else "Reload channels", leading = Icons.Default.Replay) { vm.refresh() }
                        Pill("Change playlist") { app.setup = true }
                        Pill("Add another") { vm.startNewPlaylist(); app.setup = true }
                    }
                    if (playlists.size > 1) {
                        SectionLabel("Saved playlists", Modifier.padding(top = 8.dp))
                        playlists.forEach { p ->
                            val active = p.id == prefs.activePlaylist
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) Palette.TallyDim else Color.Transparent)
                                .clickable(enabled = !active) { app.playback.stopAll(); vm.switchPlaylist(p) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name.ifBlank { p.server.ifBlank { p.m3uUrl.ifBlank { p.fileName } } }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (active) Text("In use", style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
                                else Pill("Remove") { vm.deletePlaylist(p) }
                            }
                        }
                    }
                }
            }
            Box(w) {
                Card("TV guide") {
                    Text("Your provider's guide is used automatically. Add an XMLTV link (or a file path) to use another.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.height(10.dp))
                    Field("Guide link", epgUrl, { epgUrl = it }, placeholder = "https://… .xml or .xml.gz")
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Save and load guide", accent = true) { prefs.epgUrl = epgUrl.trim(); vm.refreshEpg() }
                        Pill("Choose file…", leading = Icons.Default.FolderOpen) { pickFile("Choose a TV guide file")?.let { epgUrl = it.absolutePath } }
                    }
                    (epgErr ?: epgStatus.ifBlank { null })?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (epgErr != null) Palette.Alert else Palette.Muted) }
                }
            }
            Box(w) {
                Card("Picture & sound") {
                    Text("Picture fit (Z)", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Fit.entries.forEach { f -> Pill(f.title, active = app.fit == f) { app.chooseFit(f) } }
                    }
                    SettingSwitch("Channel list and info beside fullscreen", "On a super-ultrawide the picture sits in the middle, with channels on the left and what's on at the right.",
                        app.fullscreenPanels) { app.chooseFullscreenPanels(it) }
                    SettingSwitch("Best quality first", "When a channel has several versions, start with 4K/UHD and work down.", highest) { vm.setPreferHighest(it) }
                    SettingSwitch("Graphics card decoding", "Uses your GPU for 4K and HEVC. Turn off only if the picture is black or glitchy.",
                        prefs.p.getBoolean("desktopHwdec", true)) { prefs.p.edit().putBoolean("desktopHwdec", it).apply(); tick++; app.say("Applies to the next channel you play") }
                    SettingSwitch("HDR passthrough", "Send HDR to the screen as HDR (turn on HDR in Windows display settings first).",
                        prefs.p.getBoolean("desktopHdr", false)) { prefs.p.edit().putBoolean("desktopHdr", it).apply(); tick++; app.say("Applies to the next channel you play") }
                    SettingSwitch("Subtitles on by default", null, prefs.subtitlesOn) { prefs.subtitlesOn = it; tick++ }
                    Text("Preferred audio language", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en", "es", "fr", "de", "it", "pt", "ar").forEach { l -> Pill(Languages.label(l), active = prefs.audioLang == l) { prefs.audioLang = l; tick++ } }
                    }
                }
            }
            Box(w) {
                Card("Checking channels") {
                    Text("Zapline tests channels in the background to find which work and what quality they really are. It pauses while you watch, because most accounts allow one stream at a time.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    SettingSwitch("Check automatically", "Every day or so, while nothing is playing.", prefs.autoCheck) { prefs.autoCheck = it; tick++ }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scan.running) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Tally, strokeWidth = 2.dp)
                            Text(scan.paused ?: "${scan.done} of ${scan.total} · ${scan.working} working · ${scan.dead} not", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                            Pill("Stop") { vm.stopChecking() }
                        } else {
                            Pill("Check all now", accent = true) { vm.checkChannels(force = true) }
                            Pill("Check 4K only") { vm.check4k(force = true) }
                            Pill("Forget results") { vm.forgetHealth() }
                        }
                    }
                }
            }
            Box(w) {
                Card("Colour") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accents.forEach { a ->
                            Box(Modifier.size(40.dp).clip(CircleShape).background(a.color)
                                .border(3.dp, if (a.color == Palette.Tally) Palette.Text else Color.Transparent, CircleShape)
                                .clickable { Palette.setAccent(a.color); prefs.accent = a.color.toArgb() })
                        }
                    }
                }
            }
            Box(w) {
                Card("Keyboard") {
                    listOf(
                        "Space" to "Play / pause", "F or double-click" to "Fullscreen", "Esc" to "Back / leave fullscreen", "T" to "Theatre",
                        "V" to "Multiview", "Z" to "Picture fit (Fit, 21:9, width, stretch)", "↑ ↓ / Page Up Down" to "Channel up / down",
                        "← →" to "Back / forward 10 s (films)", "0–9" to "Channel number", "Backspace" to "Last channel",
                        "M" to "Mute", "A / C" to "Audio track / subtitles", "G / L / I" to "Guide / channel list / stream info", "Ctrl+F" to "Search",
                    ).forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(k, Modifier.width(200.dp), style = MaterialTheme.typography.labelLarge, color = Palette.TallyText)
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Box(w) {
                Card("About") {
                    Text("Zapline IPTV for Windows $APP_VERSION", style = MaterialTheme.typography.titleSmall)
                    Text(if (Mpv.available) "Player: libmpv, ready" else "Player: libmpv not found (libmpv-2.dll should be next to Zapline.exe)",
                        style = MaterialTheme.typography.bodySmall, color = if (Mpv.available) Palette.Muted else Palette.Alert)
                    Text("Free software under the GNU GPL v3. Source: github.com/bolphine/zapline-iptv", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Text("Settings are kept in ${app.platform.root.absolutePath}", style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                }
            }
        }
    }
}

fun pickFile(title: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, f)
}

// ------------------------------------------------------------------------------------------------ setup

/** First run, or changing playlist. */
@Composable
fun SetupScreen(app: AppState) {
    val vm = app.vm
    val prefs = app.prefs
    val loading by vm.loading.collectAsState()
    val progress by vm.progress.collectAsState()
    val err by vm.error.collectAsState()
    val blank by vm.blankSetup.collectAsState()
    var tab by remember { mutableStateOf(when (prefs.sourceType.takeIf { !blank }) { "m3u" -> 1; "file" -> 2; else -> 0 }) }
    var server by remember { mutableStateOf(if (blank) "" else prefs.xtServer) }
    var user by remember { mutableStateOf(if (blank) "" else prefs.xtUser) }
    var pass by remember { mutableStateOf(if (blank) "" else prefs.xtPass) }
    var url by remember { mutableStateOf(if (blank) "" else prefs.m3uUrl) }
    var file by remember { mutableStateOf(if (blank) "" else prefs.fileUri) }
    var epg by remember { mutableStateOf(if (blank) "" else prefs.epgUrl) }
    var vod by remember { mutableStateOf(prefs.loadVod) }
    val valid = when (tab) { 0 -> server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(); 1 -> url.isNotBlank(); else -> file.isNotBlank() }
    val hasSource by vm.hasSource.collectAsState()

    Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Night).padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zapline", style = MaterialTheme.typography.displaySmall, color = Palette.Tally, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                if (hasSource) Pill("Cancel") { vm.blankSetup.value = false; app.setup = false }
            }
            Text("Add your IPTV playlist", Modifier.padding(top = 4.dp, bottom = 20.dp), style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Xtream login", "M3U link", "M3U file").forEachIndexed { i, l -> Pill(l, active = tab == i) { tab = i } }
            }
            Spacer(Modifier.height(18.dp))
            when (tab) {
                0 -> {
                    Field("Server", server, { server = it }, placeholder = "http://example.com:8080")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field("Username", user, { user = it }, Modifier.weight(1f))
                        Field("Password", pass, { pass = it }, Modifier.weight(1f), secret = true)
                    }
                }
                1 -> Field("Playlist link", url, { url = it }, placeholder = "http://… .m3u or get.php?…")
                else -> Row(verticalAlignment = Alignment.Bottom) {
                    Field("Playlist file", file, { file = it }, Modifier.weight(1f), placeholder = "C:\\Users\\you\\Downloads\\playlist.m3u")
                    Spacer(Modifier.width(10.dp))
                    Pill("Browse…", leading = Icons.Default.FolderOpen) { pickFile("Choose a playlist")?.let { file = it.absolutePath } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Field("TV guide link (optional)", epg, { epg = it }, placeholder = "Leave empty to use your provider's")
            SettingSwitch("Load films and series", null, vod) { vod = it }
            err?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Alert) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (loading) "Loading…" else "Load channels", accent = valid && !loading, leading = Icons.Default.PlayArrow) {
                    if (!valid || loading) return@Pill
                    vm.saveSource(listOf("xtream", "m3u", "file")[tab], url, server, user, pass, "ts", file, File(file).name.takeIf { tab == 2 } ?: "", vod, epg)
                    vm.refresh(onSuccess = { vm.blankSetup.value = false; app.setup = false })
                }
                if (loading) Text(progress, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ programme dialog

@Composable
fun ProgrammeDialog(app: AppState, e: Entry, p: Programme) {
    val vm = app.vm
    val now = vm.now.value
    val reminders by vm.reminders.collectAsState()
    val reminded = vm.hasReminder(reminders, e, p)
    Dialog(onDismissRequest = { app.programme = null }) {
        Column(Modifier.width(620.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Dusk).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(e.logo, e.name, 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall, color = Palette.Muted)
                    Text("${hm(p.start)}–${hm(p.end)}${dayWord(p.start, now)}", style = MaterialTheme.typography.labelLarge, color = Palette.TallyText)
                }
            }
            Text(p.title, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.headlineSmall)
            p.desc?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val live = p.start <= now && p.end > now
                if (live) Pill("Watch now", accent = true, leading = Icons.Default.PlayArrow) { app.programme = null; app.watchLive(e) }
                if (p.end <= now && vm.canCatchUp(e, p, now)) Pill("Watch from the start", accent = true, leading = Icons.Default.Replay) {
                    app.programme = null; vm.catchupEntry(e, p)?.let { app.play(listOf(it), 0) }
                }
                if (live && vm.canCatchUp(e, p, now)) Pill("Start over", leading = Icons.Default.Replay) { app.programme = null; vm.catchupEntry(e, p)?.let { app.play(listOf(it), 0) } }
                if (p.start > now) Pill(if (reminded) "Reminder set" else "Remind me", active = reminded,
                    leading = if (reminded) Icons.Default.NotificationsActive else Icons.Default.Notifications) {
                    if (reminded) vm.removeReminder(e, p) else { vm.addReminder(e, p); app.say("I'll remind you when ${p.title} starts") }
                }
                if (!live) Pill("Watch channel") { app.programme = null; app.watchLive(e) }
                Spacer(Modifier.weight(1f))
                Pill("Close") { app.programme = null }
            }
        }
    }
}
