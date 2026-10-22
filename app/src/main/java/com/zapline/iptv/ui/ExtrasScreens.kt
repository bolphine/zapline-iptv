package com.zapline.iptv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Reminder
import com.zapline.iptv.data.SavedPlaylist
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry

// ================================================================== programme dialog

/**
 * What can be done with one programme: watch live, watch it from the start (catch-up), or be reminded.
 * Shared by the guide and search.
 */
@Composable
fun ProgrammeDialog(
    vm: MainViewModel,
    e: Entry,
    p: Programme,
    onDismiss: () -> Unit,
    onWatchLive: () -> Unit,
    onPlay: (List<PlayEntry>, Int) -> Unit,
) {
    val now by vm.now.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val airing = now in p.start until p.end
    val future = p.start > now
    val catchup = !future && vm.canCatchUp(e, p, now)
    val reminded = vm.hasReminder(reminders, e, p)
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(ctx, "Reminders need notifications turned on for Zapline", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Night,
        title = { Text(p.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "${e.name} · ${hm(p.start)} – ${hm(p.end)}${dayWord(p.start, now)}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (airing) ProgressLine(p, now, Modifier.padding(top = 8.dp).fillMaxWidth())
                p.desc?.let { Text(it, Modifier.padding(top = 8.dp), maxLines = 8, overflow = TextOverflow.Ellipsis) }
                if (!future && !catchup && !airing) {
                    Text("This channel doesn't keep catch-up for this programme.", Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = Palette.Faint)
                }
                Spacer(Modifier.height(14.dp))
                if (catchup) {
                    DialogAction(Icons.Default.Replay, if (airing) "Watch from the start" else "Watch catch-up") {
                        val entry = vm.catchupEntry(e, p)
                        onDismiss()
                        if (entry != null) onPlay(listOf(entry), 0)
                        else Toast.makeText(ctx, "Catch-up isn't available for this one", Toast.LENGTH_SHORT).show()
                    }
                }
                if (future) {
                    DialogAction(
                        if (reminded) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                        if (reminded) "Remove reminder" else "Remind me",
                    ) {
                        if (reminded) vm.removeReminder(e, p) else {
                            Haptics.confirm(view)
                            vm.addReminder(e, p)
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else Toast.makeText(ctx, "We'll remind you a minute before", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onWatchLive() }) { Text(if (airing) "Watch live" else "Watch channel", color = Palette.Tally) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.Lilac) } },
    )
}

@Composable
private fun DialogAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Dusk).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Tally, modifier = Modifier.size(20.dp))
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
    }
    Spacer(Modifier.height(8.dp))
}

fun dayWord(t: Long, now: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = t
    return when (cal.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> ""
        1 -> " tomorrow"
        -1 -> " yesterday"
        else -> " " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(java.util.Date(t))
    }
}

// ================================================================== search

/** One box for everything: channels, what's on now, later, catch-up, films and series. */
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onPlay: (List<PlayEntry>, Int) -> Unit,
    onOpenShow: (Channel) -> Unit,
) {
    BackHandler(onBack = onBack)
    val q by vm.searchQuery.collectAsState()
    val r by vm.search.collectAsState()
    val now by vm.now.collectAsState()
    val epg by vm.epg.collectAsState()
    var selected by remember { mutableStateOf<Pair<Entry, Programme>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }

    fun live(e: Entry) = onPlay(listOf(e.toPlay(vm.epgIdFor(e))), 0)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Search", onBack = onBack)
        SearchField(q, { vm.searchQuery.value = it }, "Channels, programmes, films, series",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
        val searching = q.trim().length >= 2
        when {
            !searching -> Message("Search everything", "Type at least two letters. Results follow your language and country filters.")
            r.query != q -> SkeletonRows(6)
            r.isEmpty -> Message("Nothing found for “${q.trim()}”",
                if (epg.channelCount == 0) "The TV guide isn't loaded, so programmes can't be searched yet." else "Try fewer words, or check your filters on Live TV.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (r.onNow.isNotEmpty()) {
                    item { SectionLabel("On now") }
                    items(r.onNow, key = { "now" + it.first.key }) { (e, p) ->
                        ProgrammeRow(e, p, now, trailing = "Live") { live(e) }
                    }
                }
                if (r.channels.isNotEmpty()) {
                    item { SectionLabel("Channels") }
                    items(r.channels, key = { "ch" + it.key }) { e ->
                        val (cur, _) = epg.nowNext(vm.epgIdFor(e), now)
                        Row(
                            Modifier.fillMaxWidth().clickable { live(e) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelLogo(e.logo, e.name, 40.dp)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(cur?.title ?: e.groups.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (r.upcoming.isNotEmpty()) {
                    item { SectionLabel("Coming up") }
                    items(r.upcoming, key = { "up" + it.first.key + it.second.start }) { (e, p) ->
                        ProgrammeRow(e, p, now, trailing = hm(p.start) + dayWord(p.start, now)) { selected = e to p }
                    }
                }
                if (r.catchup.isNotEmpty()) {
                    item { SectionLabel("Catch-up") }
                    items(r.catchup, key = { "cu" + it.first.key + it.second.start }) { (e, p) ->
                        ProgrammeRow(e, p, now, trailing = hm(p.start) + dayWord(p.start, now)) { selected = e to p }
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item { SectionLabel("Movies") }
                    item { PosterRow(r.movies) { c -> onPlay(listOf(movieEntry(c)), 0) } }
                }
                if (r.series.isNotEmpty()) {
                    item { SectionLabel("Series") }
                    item { PosterRow(r.series, onOpenShow) }
                }
            }
        }
    }

    selected?.let { (e, p) ->
        ProgrammeDialog(vm, e, p, onDismiss = { selected = null }, onWatchLive = { live(e) }, onPlay = onPlay)
    }
}

@Composable
private fun ProgrammeRow(e: Entry, p: Programme, now: Long, trailing: String, onClick: () -> Unit) {
    val airing = now in p.start until p.end
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(e.logo, e.name, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.name, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (airing) ProgressLine(p, now, Modifier.padding(top = 4.dp).fillMaxWidth())
        }
        Text(trailing, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium,
            color = if (airing) Palette.Tally else Palette.Muted, fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PosterRow(items: List<Channel>, onClick: (Channel) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.url }) { c -> Box(Modifier.width(110.dp)) { Poster(c) { onClick(c) } } }
    }
}

// ================================================================== reminders

@Composable
fun RemindersScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val list by vm.reminders.collectAsState()
    val now by vm.now.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Reminders", onBack = onBack)
        if (list.isEmpty()) {
            Message("No reminders", "Tap a programme that hasn't started in the TV guide or search, then “Remind me”.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { r: Reminder ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.channelName} · ${hm(r.start)}${dayWord(r.start, now)}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    IconButton(onClick = { vm.removeReminder(r) }) { Icon(Icons.Default.Delete, "Remove reminder for ${r.title}", tint = Palette.Muted) }
                }
            }
        }
    }
}

// ================================================================== playlists

@Composable
fun PlaylistsScreen(vm: MainViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    BackHandler(onBack = onBack)
    val list by vm.playlists.collectAsState()
    val active = remember(list) { vm.prefs.activePlaylist }
    var renaming by remember { mutableStateOf<SavedPlaylist?>(null) }
    var confirm by remember { mutableStateOf<SavedPlaylist?>(null) }
    LaunchedEffect(Unit) { vm.rememberCurrentPlaylist() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Playlists", onBack = onBack) {
            TextButton(onClick = onAdd) { Text("Add", color = Palette.Tally) }
        }
        Text("Switching reloads channels from that provider. Favourites, line-up and group fixes are shared.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { pl ->
                val on = pl.id == active
                Row(
                    Modifier.fillMaxWidth().clickable { if (!on) confirm = pl }.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Palette.Tally else Palette.LineSoft))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(pl.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        Text(if (on) "In use · ${pl.summary}" else pl.summary, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    TextButton(onClick = { renaming = pl }) { Text("Rename", color = Palette.Lilac) }
                    if (!on) IconButton(onClick = { vm.deletePlaylist(pl) }) { Icon(Icons.Default.Delete, "Delete ${pl.name}", tint = Palette.Muted) }
                }
            }
        }
    }
    confirm?.let { pl ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = Palette.Night,
            title = { Text("Switch to ${pl.name}?") },
            text = { Text("Channels, films and the TV guide will reload from this provider.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.switchPlaylist(pl); onBack() }) { Text("Switch", color = Palette.Tally) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Palette.Lilac) } },
        )
    }
    renaming?.let { pl ->
        var name by remember(pl.id) { mutableStateOf(pl.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Palette.Night,
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    vm.renamePlaylist(pl, name); renaming = null
                }) { Text("Save", color = Palette.Tally) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Palette.Lilac) } },
        )
    }
}

// ================================================================== PIN

/** Four-digit PIN entry on an on-screen keypad (no keyboard), like a TV's parental lock. */
@Composable
fun PinDialog(title: String, message: String?, onDismiss: () -> Unit, onDone: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun press(d: String) {
        if (pin.length >= 4) return
        wrong = false
        pin += d
        if (pin.length == 4) { if (onDone(pin)) onDismiss() else { wrong = true; pin = "" } }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.Night,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                message?.let { Text(it, Modifier.padding(bottom = 12.dp), color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${pin.length} of 4 digits entered"
                }) {
                    repeat(4) { i ->
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (i < pin.length) Palette.Tally else Palette.LineSoft))
                    }
                }
                Text(if (wrong) "That PIN isn't right" else " ", Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "\u232B")
                keys.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.size(60.dp, 48.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (k.isEmpty()) androidx.compose.ui.graphics.Color.Transparent else Palette.Dusk)
                                    .then(if (k.isEmpty()) Modifier else Modifier.clickable {
                                        if (k == "\u232B") { pin = pin.dropLast(1); wrong = false } else press(k)
                                    })
                                    .semantics { if (k == "\u232B") contentDescription = "Delete digit" },
                                contentAlignment = Alignment.Center,
                            ) { Text(k, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Lilac) } },
    )
}
