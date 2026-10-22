package com.zapline.iptv.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zapline.iptv.BuildConfig
import com.zapline.iptv.data.Programme
import com.zapline.iptv.lang.Categories
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.lang.LangRules
import com.zapline.iptv.lang.Languages
import com.zapline.iptv.lang.RegionTagger
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry

// ================================================================== shell

@Composable
fun AppRoot(vm: MainViewModel, onPlay: (List<PlayEntry>, Int) -> Unit) {
    val hasSource by vm.hasSource.collectAsState()
    var screen by rememberSaveable { mutableStateOf("channels") }
    /** The live / movies / series section, remembered while visiting other screens. */
    var section by rememberSaveable { mutableStateOf("channels") }
    var show by remember { mutableStateOf<Channel?>(null) }
    val current = if (!hasSource) "setup" else screen
    val home = { screen = section }
    Surface(Modifier.fillMaxSize(), color = Palette.Ink) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.weight(1f)) {
                // Screens cross-fade with a slight settle, instead of cutting.
                androidx.compose.animation.AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 40)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(260), initialScale = 0.985f))
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                    },
                    label = "screen",
                ) { current ->
                when (current) {
                    "setup" -> SetupScreen(vm, canCancel = hasSource, onDone = { vm.blankSetup.value = false; home() },
                        onCancel = { val wasNew = vm.blankSetup.value; vm.blankSetup.value = false; screen = if (wasNew) "playlists" else "settings" })
                    "settings" -> SettingsScreen(vm, onBack = home, onNavigate = { screen = it })
                    "groups" -> GroupsScreen(vm, onBack = { screen = "settings" })
                    "rules" -> RulesScreen(vm, onBack = { screen = "settings" })
                    "lineup" -> LineupScreen(vm, onBack = home)
                    "guide" -> GuideScreen(vm, onBack = home, onPlay = onPlay)
                    "sport" -> SportScreen(vm, onBack = home, onPlay = onPlay)
                    "search" -> SearchScreen(vm, onBack = home, onPlay = onPlay, onOpenShow = { show = it; section = "series"; screen = "series" })
                    "reminders" -> RemindersScreen(vm, onBack = { screen = "settings" })
                    "playlists" -> PlaylistsScreen(vm, onBack = { screen = "settings" }, onAdd = { vm.startNewPlaylist(); screen = "setup" })
                    "movies" -> VodScreen(vm, isSeries = false, onPlay = onPlay, onOpenShow = {}, onSettings = { screen = "settings" })
                    "series" -> {
                        val s = show
                        if (s != null) ShowScreen(vm, s, onBack = { show = null }, onPlay = onPlay)
                        else VodScreen(vm, isSeries = true, onPlay = onPlay, onOpenShow = { show = it }, onSettings = { screen = "settings" })
                    }
                    else -> ChannelsScreen(vm, onPlay, onNavigate = { screen = it })
                }
                }
            }
            if (current in setOf("channels", "movies", "series")) {
                BottomBar(current) { key ->
                    if (key == "series" && current == "series") show = null
                    section = key; screen = key
                }
            }
        }
    }
}

/** Three sections. The current one is lit with a short amber bar, like a selected input on a TV. */
@Composable
private fun BottomBar(current: String, onSelect: (String) -> Unit) {
    Column {
        HorizontalDivider(color = Palette.LineSoft)
        Row(Modifier.fillMaxWidth().background(Palette.Ink).padding(horizontal = 12.dp, vertical = 6.dp)) {
            listOf(
                Triple("channels", "Live TV", Icons.Default.Home),
                Triple("movies", "Movies", Icons.Default.PlayArrow),
                Triple("series", "Series", Icons.AutoMirrored.Filled.List),
            ).forEach { (key, label, icon) ->
                val on = current == key
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(key) }
                        .semantics { role = Role.Tab; selected = on }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Palette.Tally else Color.Transparent))
                    Spacer(Modifier.height(6.dp))
                    Icon(icon, null, tint = if (on) Palette.Text else Palette.Faint, modifier = Modifier.size(22.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Palette.Text else Palette.Faint,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// ================================================================== setup

@Composable
fun SetupScreen(vm: MainViewModel, canCancel: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val p = vm.prefs
    val ctx = LocalContext.current
    // Adding another playlist starts from an empty form; editing starts from the current one.
    val blank = remember { vm.blankSetup.value }
    fun cur(v: String) = if (blank) "" else v
    var tab by rememberSaveable { mutableIntStateOf(if (blank) 0 else when (p.sourceType) { "m3u" -> 1; "file" -> 2; else -> 0 }) }
    var server by rememberSaveable { mutableStateOf(cur(p.xtServer)) }
    var user by rememberSaveable { mutableStateOf(cur(p.xtUser)) }
    var pass by rememberSaveable { mutableStateOf(cur(p.xtPass)) }
    var format by rememberSaveable { mutableStateOf(if (blank) "ts" else p.xtFormat) }
    var url by rememberSaveable { mutableStateOf(cur(p.m3uUrl)) }
    var fileUri by rememberSaveable { mutableStateOf(cur(p.fileUri)) }
    var fileName by rememberSaveable { mutableStateOf(cur(p.fileName)) }
    var loadVod by rememberSaveable { mutableStateOf(p.loadVod) }
    var epgUrl by rememberSaveable { mutableStateOf(cur(p.epgUrl)) }
    var showAdvanced by rememberSaveable { mutableStateOf(p.epgUrl.isNotBlank()) }
    val loading by vm.loading.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            fileUri = uri.toString()
            fileName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "playlist"
        }
    }

    if (canCancel) BackHandler(onBack = onCancel)

    val valid = when (tab) {
        0 -> server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
        1 -> url.isNotBlank()
        else -> fileUri.isNotBlank()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(start = 0.dp)) { Text("Cancel", color = Palette.Muted) }
        } else {
            Spacer(Modifier.height(28.dp))
        }
        Text("Zapline", style = MaterialTheme.typography.displaySmall)
        Text(
            "Point it at your playlist. Channels get sorted by language and country, duplicates are merged, and the TV guide is pulled in.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
        )
        Spacer(Modifier.height(4.dp))
        Text("Where's your playlist?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "Xtream login" to "Server, username, password",
                "M3U link" to "A playlist URL",
                "File" to "An .m3u on this phone",
            ).forEachIndexed { i, (t, d) ->
                SourceCard(t, d, tab == i, Modifier.weight(1f)) { tab = i }
            }
        }
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Tally, unfocusedBorderColor = Palette.Line, cursorColor = Palette.Tally,
            focusedLabelColor = Palette.Tally, focusedContainerColor = Palette.Night, unfocusedContainerColor = Palette.Night,
        )
        when (tab) {
            0 -> {
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server") },
                    placeholder = { Text("http://example.com:8080") }, singleLine = true, colors = fieldColors,
                    shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true,
                    colors = fieldColors, shape = MaterialTheme.shapes.medium)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    colors = fieldColors, shape = MaterialTheme.shapes.medium,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stream type", style = MaterialTheme.typography.labelLarge, color = Palette.Muted, modifier = Modifier.padding(end = 4.dp))
                    FilterPill("MPEG-TS", format == "ts", { format = "ts" }, dropdown = false)
                    FilterPill("HLS", format == "m3u8", { format = "m3u8" }, dropdown = false)
                }
            }
            1 -> {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Playlist URL") },
                    placeholder = { Text("http://…/playlist.m3u") }, minLines = 2, colors = fieldColors, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text("Xtream get.php links are spotted and loaded through the faster Xtream API.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Palette.Night)
                        .border(1.dp, Palette.Line, MaterialTheme.shapes.medium)
                        .clickable { picker.launch(arrayOf("*/*")) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (fileUri.isBlank()) "Choose .m3u file" else fileName, style = MaterialTheme.typography.titleSmall)
                        Text(if (fileUri.isBlank()) "From Downloads, Drive or anywhere on the phone" else "Tap to choose a different file",
                            style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = Palette.Muted)
                }
            }
        }
        ToggleRow("Movies & series", "Load on-demand titles into their own sections. Big libraries take longer.", loadVod) { loadVod = it }
        if (!showAdvanced) {
            TextButton(onClick = { showAdvanced = true }) { Text("Use a different TV guide", color = Palette.Lilac) }
        } else {
            OutlinedTextField(epgUrl, { epgUrl = it }, Modifier.fillMaxWidth(), label = { Text("TV guide URL (optional)") },
                placeholder = { Text("Leave blank to use your provider's guide") }, singleLine = true, colors = fieldColors,
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("XMLTV link, .xml or .xml.gz") })
        }
        PrimaryButton(
            if (loading) "Loading…" else "Load channels",
            onClick = {
                vm.saveSource(listOf("xtream", "m3u", "file")[tab], url, server, user, pass, format, fileUri, fileName, loadVod, epgUrl)
                vm.refresh(onSuccess = onDone)
            },
            enabled = valid && !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = Palette.Tally, trackColor = Palette.Dusk)
            Text(progress, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SourceCard(title: String, desc: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier.heightIn(min = 96.dp).clip(shape)
            .background(if (selected) Palette.TallyDim else Palette.Night)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Tally else Palette.LineSoft, shape)
            .clickable(onClick = onClick).semantics { role = Role.Tab; this.selected = selected }
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) Palette.TallyText else Palette.Text)
        Text(desc, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
}

@Composable
fun ToggleRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.OnTally, checkedTrackColor = Palette.Tally,
            uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Dusk, uncheckedBorderColor = Palette.Line,
        ))
    }
}

// ================================================================== live TV

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: MainViewModel, onPlay: (List<PlayEntry>, Int) -> Unit, onNavigate: (String) -> Unit) {
    var askPin by remember { mutableStateOf(false) }
    val view by vm.view.collectAsState()
    val query by vm.query.collectAsState()
    val langFilter by vm.langFilter.collectAsState()
    val regionFilter by vm.regionFilter.collectAsState()
    val favOnly by vm.favOnly.collectAsState()
    val favs by vm.favourites.collectAsState()
    val loading by vm.loading.collectAsState()
    val starting by vm.starting.collectAsState()
    val progress by vm.progress.collectAsState()
    val error by vm.error.collectAsState()
    val epg by vm.epg.collectAsState()
    val epgIds by vm.epgIds.collectAsState()
    val now by vm.now.collectAsState()
    val epgStatus by vm.epgStatus.collectAsState()
    val epgError by vm.epgError.collectAsState()
    var langSheet by remember { mutableStateOf(false) }
    var regionSheet by remember { mutableStateOf(false) }
    var groupSheet by remember { mutableStateOf(false) }
    var versionsOf by remember { mutableStateOf<Entry?>(null) }
    var detailsOf by remember { mutableStateOf<Entry?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(view.selectedGroup, view.selectedCategory, langFilter, regionFilter, favOnly) { listState.scrollToItem(0) }

    val playFromList: (Int) -> Unit = { i -> onPlay(view.entries.map { it.toPlay(epgIds[it.key]) }, i) }
    val showRecents = query.isBlank() && view.selectedGroup == null && view.selectedCategory == null &&
        !favOnly && view.recents.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Live TV") {
            IconButton(onClick = { onNavigate("search") }) { Icon(Icons.Default.Search, "Search everything", tint = Palette.Text) }
            IconButton(onClick = { onNavigate("sport") }) { Icon(Icons.Default.SportsSoccer, "Sport", tint = Palette.Text) }
            IconButton(onClick = { onNavigate("guide") }) { Icon(Icons.Default.DateRange, "TV guide", tint = Palette.Text) }
            IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Default.Settings, "Settings", tint = Palette.Text) }
        }
        SearchField(query, { vm.query.value = it }, "Search channels", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterPill(filterLabel(langFilter, "All languages", "en", Languages::label), langFilter.isNotEmpty(), { langSheet = true }) }
            item { FilterPill(filterLabel(regionFilter, "All countries", "UK", RegionTagger::label), regionFilter.isNotEmpty(), { regionSheet = true }) }
            item {
                FilterPill(
                    view.selectedGroup ?: "All groups", view.selectedGroup != null, { groupSheet = true },
                    modifier = Modifier.widthIn(max = 220.dp),
                    onClear = if (view.selectedGroup != null) ({ vm.selectedGroup.value = null }) else null,
                )
            }
            item {
                FilterPill("Favourites", favOnly, { vm.favOnly.value = !favOnly },
                    leading = if (favOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder, dropdown = false)
            }
            item { HealthPill(view) { vm.healthFilter.value = it } }
        }
        HealthScanLine(vm)

        TextTabs(
            tabs = listOf(Triple<String?, String, Int?>(null, "Everything", null)) +
                view.categories.filter { it.first.isNotEmpty() }.map { (c, n) -> Triple(c, Categories.label(c), n) },
            selected = view.selectedCategory,
            onSelect = { if (vm.pinRequired(it)) askPin = true else vm.selectedCategory.value = it },
        )
        if (askPin) {
            PinDialog("Adult channels", "Enter your PIN to show this category.", onDismiss = { askPin = false }) { pin ->
                vm.unlock(pin).also { if (it) vm.selectedCategory.value = Categories.ADULT }
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.Tally, trackColor = Palette.Ink)
            Text(progress, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        error?.let { Notice(it, isError = true) { vm.error.value = null } }
        if (epgStatus.isNotEmpty()) Notice(epgStatus) {}
        epgError?.let { Notice(it) { vm.epgError.value = null } }

        when {
            (view.totalChannels == 0 && (starting || loading)) || (view.totalChannels > 0 && !view.ready) -> SkeletonRows()
            view.entries.isEmpty() && !loading -> EmptyChannels(view, (langFilter + regionFilter).isNotEmpty() || view.selectedCategory != null, favOnly, query,
                onReset = {
                    vm.query.value = ""; vm.selectedCategory.value = null; vm.selectedGroup.value = null; vm.favOnly.value = false
                }, onOrganise = { onNavigate("groups") })
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (showRecents) {
                    item(key = "recents-h") { SectionLabel("Recently watched") }
                    item(key = "recents") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(view.recents, key = { _, e -> e.key }) { i, e ->
                                RecentCard(e, epg.nowNext(epgIds[e.key], now).first) { onPlay(view.recents.map { it.toPlay(epgIds[it.key]) }, i) }
                            }
                        }
                    }
                }
                item(key = "count") {
                    SectionLabel(remember(view.entries) { countLine(view) }) {
                        if (favOnly && view.entries.size > 1) {
                            TextButton(onClick = { onNavigate("lineup") }) { Text("Edit line-up", color = Palette.TallyText) }
                        }
                    }
                }
                itemsIndexed(view.entries, key = { _, e -> e.key }, contentType = { _, _ -> "entry" }) { i, e ->
                    EntryRow(
                        e = e,
                        number = if (favOnly && query.isBlank()) i + 1 else null,
                        guide = epg.nowNext(epgIds[e.key], now),
                        now = now,
                        fav = vm.isFav(e, favs),
                        onClick = { playFromList(i) },
                        onFav = { vm.toggleFav(e) },
                        onVersions = { versionsOf = e },
                        onDetails = { detailsOf = e },
                    )
                }
            }
        }
    }

    if (langSheet) {
        ModalBottomSheet(onDismissRequest = { langSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Night) {
            FacetSheet(
                title = "Languages",
                hint = "Worked out from channel names, groups and playlist tags. If one's wrong, fix the whole group in Settings.",
                rows = view.langs, selected = langFilter, label = Languages::label,
                quick = "English only" to setOf("en"),
                onChange = { vm.setLangFilter(it) },
            )
        }
    }
    if (regionSheet) {
        ModalBottomSheet(onDismissRequest = { regionSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Night) {
            FacetSheet(
                title = "Countries",
                hint = "Separate from language, so UK and US channels can be told apart. Counts follow your language choice.",
                rows = view.regions, selected = regionFilter, label = RegionTagger::label,
                quick = "UK only" to setOf("UK"),
                onChange = { vm.setRegionFilter(it) },
            )
        }
    }
    if (groupSheet) {
        ModalBottomSheet(onDismissRequest = { groupSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Night) {
            GroupSheet(view) { g -> vm.selectedGroup.value = g; groupSheet = false }
        }
    }
    detailsOf?.let { e -> DetailsDialog(e) { detailsOf = null } }
    versionsOf?.let { e ->
        ModalBottomSheet(onDismissRequest = { versionsOf = null }, containerColor = Palette.Night) {
            VersionsSheet(e) { chosen ->
                versionsOf = null
                val reordered = e.variants.sortedBy { if (it === chosen) 0 else 1 }
                onPlay(listOf(PlayEntry(e.key, e.name, reordered.map { it.ch }, epgIds[e.key])), 0)
            }
        }
    }
}

/** One-line status under the filters; tap to dismiss. */
@Composable
private fun Notice(text: String, isError: Boolean = false, onDismiss: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else Palette.Muted,
    )
}

/** Picture and sound of the version that plays first, e.g. "1080p" "5.1"; or "Not working". */
@Composable
private fun QualityBadges(e: Entry) {
    val h = e.health
    val info = e.info
    if (h == 2) {
        Row(Modifier.padding(top = 3.dp)) { Badge("Not working", MaterialTheme.colorScheme.error) }
        return
    }
    if (info == null || !info.ok) return
    val res = info.resolution; val audio = info.audio?.takeIf { it != "Stereo" && it != "Mono" }
    if (res == null && audio == null) return
    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        res?.let { Badge(it, if (info.height >= 1000 || info.width >= 1800) Palette.Tally else Palette.Muted) }
        audio?.let { Badge(it, Palette.Lilac) }
        if (info.videoCodec == "HEVC") Badge("HEVC", Palette.Muted)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color,
    )
}

/** Working / not working filter, with counts from the background check. */
@Composable
private fun HealthPill(view: ChannelView, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = view.healthCounts
    val label = when (view.healthFilter) { 1 -> "Working"; 2 -> "Not working"; 0 -> "Not checked"; else -> "Status" }
    Box {
        FilterPill(label, view.healthFilter != null, { open = true },
            onClear = if (view.healthFilter != null) ({ onPick(null) }) else null)
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Palette.Night) {
            listOf(null to "All channels", 1 to "Working", 2 to "Not working", 0 to "Not checked yet").forEach { (k, l) ->
                val n = if (k == null) c.values.sum() else c[k] ?: 0
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("$l  ${"%,d".format(n)}", color = if (k == view.healthFilter) Palette.Tally else Palette.Text) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}

/** "Checking channels 120 of 800 · 98 working", while the background check runs. */
@Composable
private fun HealthScanLine(vm: MainViewModel) {
    val s by vm.healthScan.collectAsState()
    if (!s.running && s.paused == null) return
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                s.paused ?: "Checking channels ${"%,d".format(s.done)} of ${"%,d".format(s.total)} · ${"%,d".format(s.working)} working",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (s.total > 0) LinearProgressIndicator(
                progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp),
                color = Palette.Tally, trackColor = Palette.Night,
            )
        }
        TextButton(onClick = { vm.stopChecking() }) { Text(if (s.running) "Stop" else "OK", color = Palette.Lilac) }
    }
}

internal fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

private fun countLine(view: ChannelView): String {
    val n = "%,d".format(view.entries.size)
    val merged = view.entries.sumOf { it.variants.size - 1 }
    return if (merged > 0) "$n channels (${"%,d".format(merged)} duplicate${if (merged == 1) "" else "s"} merged)" else "$n channels"
}

@Composable
private fun RecentCard(e: Entry, now: Programme?, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        ChannelLogo(e.logo, e.name, 96.dp)
        Text(e.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Text(now?.title ?: " ", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Palette.Tally)
    }
}

@Composable
private fun EmptyChannels(view: ChannelView, filtered: Boolean, favOnly: Boolean, query: String, onReset: () -> Unit, onOrganise: () -> Unit) {
    when {
        view.totalChannels == 0 -> Message("No channels yet", "Refresh the playlist from Settings, or check the playlist link.")
        query.isNotBlank() -> Message("Nothing matches \"$query\"", "Try part of the name, like \"sky sp\".", "Clear search", onReset)
        favOnly -> Message("No favourites here", "Tap the heart on any channel to keep it here.", "Show all channels", onReset)
        filtered -> Message("No channels match these filters", "If a group is tagged with the wrong language or country, correct it once and every channel in it follows.",
            "Fix groups", onOrganise)
        else -> Message("Nothing to show")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: Entry,
    number: Int?,
    guide: Pair<Programme?, Programme?>,
    now: Long,
    fav: Boolean,
    onClick: () -> Unit,
    onFav: () -> Unit,
    onVersions: () -> Unit,
    onDetails: () -> Unit,
) {
    val (cur, next) = guide
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onDetails)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("$number", Modifier.width(30.dp), style = MaterialTheme.typography.titleMedium, color = Palette.Tally, textAlign = TextAlign.Center)
        }
        ChannelLogo(e.logo, e.name, 52.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    color = if (e.health == 2) Palette.Faint else Palette.Text,
                    modifier = Modifier.weight(1f, fill = false))
                if (e.variants.size > 1) {
                    Text(
                        "${e.variants.size}×",
                        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onVersions)
                            .background(Palette.Dusk).padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
                    )
                }
            }
            QualityBadges(e)
            if (cur != null) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Text, modifier = Modifier.weight(1f))
                    Text("until ${hm(cur.end)}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
                }
                ProgressLine(cur, now, Modifier.padding(top = 5.dp, bottom = 4.dp))
                next?.let {
                    Text("Then ${hm(it.start)}  ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
            } else {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodeTag(e.lang, emphasised = true)
                    if (e.region.isNotEmpty()) { Spacer(Modifier.width(4.dp)); CodeTag(e.region) }
                    Text(next?.let { "Next ${hm(it.start)}  ${it.title}" } ?: e.main.ch.group,
                        Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
        }
        val view = androidx.compose.ui.platform.LocalView.current
        IconButton(onClick = { if (!fav) Haptics.confirm(view) else Haptics.tick(view); onFav() }) {
            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (fav) "Remove favourite" else "Add favourite",
                tint = if (fav) Palette.Tally else Palette.Faint)
        }
    }
}

private fun filterLabel(sel: Set<String>, all: String, preferred: String, label: (String) -> String): String = when {
    sel.isEmpty() -> all
    sel.size == 1 -> label(sel.first())
    else -> "${label(sel.sortedBy { it != preferred }.first())} +${sel.size - 1}"
}

// ---------------------------------------------------------------- sheets

@Composable
private fun SheetHeader(title: String, hint: String?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        actions()
    }
    if (hint != null) {
        Text(hint, Modifier.padding(horizontal = 22.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun FacetSheet(
    title: String,
    hint: String,
    rows: List<Pair<String, Int>>,
    selected: Set<String>,
    label: (String) -> String,
    quick: Pair<String, Set<String>>,
    onChange: (Set<String>) -> Unit,
) {
    val counts = rows.toMap()
    val codes = rows.map { it.first } + selected.filter { it !in counts }
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetHeader(title, hint) {
            TextButton(onClick = { onChange(quick.second) }) { Text(quick.first, color = Palette.Tally) }
            TextButton(onClick = { onChange(emptySet()) }) { Text("All", color = Palette.Lilac) }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(codes, key = { it }) { code ->
                val checked = code in selected
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(if (checked) selected - code else selected + code) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, { onChange(if (it) selected + code else selected - code) },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.Tally, checkmarkColor = Palette.OnTally, uncheckedColor = Palette.Line))
                    Text(label(code), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("%,d".format(counts[code] ?: 0), Modifier.padding(end = 12.dp),
                        style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
        }
    }
}

@Composable
private fun GroupSheet(view: ChannelView, onPick: (String?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(view.groups, q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) view.groups else view.groups.filter { it.first.lowercase().contains(t) }
    }
    Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 8.dp)) {
        SheetHeader("Groups", "Only groups with channels matching your other filters are listed.")
        SearchField(q, { q = it }, "Find a group", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            if (q.isBlank()) item(key = "__all") { PickRow("All groups", view.groupTotal, view.selectedGroup == null) { onPick(null) } }
            items(shown, key = { it.first }) { (g, n) -> PickRow(g, n, view.selectedGroup == g) { onPick(g) } }
        }
    }
}

@Composable
fun PickRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) Palette.TallyDim else Color.Transparent)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Palette.TallyText else Palette.Text)
        Text("%,d".format(count), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@Composable
private fun VersionsSheet(e: Entry, onPick: (Tagged) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        SheetHeader(e.name, "${e.variants.size} versions of this channel, best working one first. If one fails, the next takes over.")
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(e.variants) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(v) }.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ChannelNames.versionLabel(v.ch.name), Modifier.width(96.dp), style = MaterialTheme.typography.titleSmall, color = Palette.Tally)
                    Column(Modifier.weight(1f)) {
                        Text(v.ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        val info = com.zapline.iptv.data.Health[v.ch.url]
                        Text(
                            when {
                                info == null || info.state == com.zapline.iptv.data.StreamInfo.BUSY -> "Not checked yet · ${v.ch.group}"
                                info.suspect -> "Failed once${info.note?.let { " ($it)" } ?: ""}, will check again · ${v.ch.group}"
                                info.dead -> "Not working${info.note?.let { " ($it)" } ?: ""} · failed ${info.failures} checks, last ${ago(info.checkedAt)}"
                                else -> (info.summary().ifEmpty { "Works" }) + " · ${if (info.watched) "watched" else "checked"} ${ago(info.checkedAt)}"
                            },
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (info?.dead == true) MaterialTheme.colorScheme.error else Palette.Muted,
                        )
                    }
                }
            }
        }
    }
}

/** Long-press: the raw playlist metadata next to what was worked out from it. */
@Composable
private fun DetailsDialog(e: Entry, onDismiss: () -> Unit) {
    val c = e.main.ch
    val host = try { java.net.URI(c.url).host ?: "" } catch (_: Exception) { "" }
    val rows = listOf(
        "Name in playlist" to c.name,
        "Group" to c.group,
        "tvg-id" to (c.tvgId ?: "none"),
        "tvg-country" to (c.tvgCountry ?: "none"),
        "tvg-language" to (c.tvgLanguage ?: "none"),
        "Language" to "${Languages.label(e.lang)}${if (e.lang != c.detectedLang) " (set by you)" else ""}",
        "Country" to "${RegionTagger.label(e.region)}${if (e.region != c.detectedRegion) " (set by you)" else ""}",
        "Category" to Categories.label(e.category),
        "Versions" to e.variants.size.toString(),
        "Server" to host.ifEmpty { "unknown" },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Night,
        title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Wrong language or country? Correct the group in Settings and every channel in it follows.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.Tally) } },
    )
}

// ================================================================== line-up

/** Favourites in your own order; the position is the channel number used by the player's number pad. */
@Composable
fun LineupScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val order by vm.favOrder.collectAsState()
    val favs by vm.favourites.collectAsState()
    val lineup = remember(order, favs) { vm.lineup() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Your line-up", onBack = onBack, large = true)
        Text("Numbers here are what you type on the player's number pad. Favourites you add later join the end.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        if (lineup.isEmpty()) {
            Message("No favourites yet", "Tap the heart on any channel to add it to your line-up.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(lineup, key = { _, e -> e.key }) { i, e ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.titleMedium, color = Palette.Tally, textAlign = TextAlign.Center)
                    ChannelLogo(e.logo, e.name, 40.dp)
                    Text(e.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.moveToTop(e) }, enabled = i > 0) {
                        Icon(Icons.Default.VerticalAlignTop, "Move ${e.name} to the top", tint = if (i > 0) Palette.Muted else Palette.LineSoft)
                    }
                    IconButton(onClick = { vm.moveInLineup(e, -1) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move ${e.name} up", tint = if (i > 0) Palette.Text else Palette.LineSoft)
                    }
                    IconButton(onClick = { vm.moveInLineup(e, 1) }, enabled = i < lineup.size - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move ${e.name} down", tint = if (i < lineup.size - 1) Palette.Text else Palette.LineSoft)
                    }
                }
            }
        }
    }
}

// ================================================================== settings

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val p = vm.prefs
    val loading by vm.loading.collectAsState()
    val progress by vm.progress.collectAsState()
    val epg by vm.epg.collectAsState()
    val epgStatus by vm.epgStatus.collectAsState()
    val epgError by vm.epgError.collectAsState()
    val view by vm.view.collectAsState()
    var subtitles by remember { mutableStateOf(p.subtitlesOn) }
    var subScale by remember { mutableStateOf(p.subtitleScale) }
    var resize by remember { mutableIntStateOf(p.resizeMode) }
    var gestures by remember { mutableStateOf(p.gestures) }
    var audioLang by remember { mutableStateOf(p.audioLang) }
    var pickAudio by remember { mutableStateOf(false) }
    val reminders by vm.reminders.collectAsState()
    val playlists by vm.playlists.collectAsState()
    var pinSet by remember { mutableStateOf(p.adultPin.isNotEmpty()) }
    /** 0 none, 1 check old PIN before change, 2 enter new PIN, 3 check PIN before removing. */
    var pinStep by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(com.zapline.iptv.data.Backup.export(ctx).toByteArray()) }
            android.widget.Toast.makeText(ctx, "Backup saved. It includes your playlist logins, so keep it private.", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Couldn't save the backup: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val n = com.zapline.iptv.data.Backup.import(ctx, text)
            vm.reloadFromPrefs()
            pinSet = p.adultPin.isNotEmpty()
            Palette.setAccent(androidx.compose.ui.graphics.Color(p.accent))
            android.widget.Toast.makeText(ctx, "Restored $n settings", android.widget.Toast.LENGTH_SHORT).show()
            vm.refresh()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, e.message ?: "Couldn't read that file", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val source = when (p.sourceType) {
        "xtream" -> "Xtream login, ${try { java.net.URI(com.zapline.iptv.parse.Xtream.normalizeServer(p.xtServer)).host } catch (_: Exception) { p.xtServer }}"
        "m3u" -> "M3U link, ${try { java.net.URI(p.m3uUrl.trim()).host ?: p.m3uUrl } catch (_: Exception) { p.m3uUrl }}"
        "file" -> "File, ${p.fileName}"
        else -> "Not set up"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack = onBack, large = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup("Appearance") {
                AccentPicker(p)
            }
            SettingsGroup("Playlist") {
                NavRow("Playlists", "${playlists.size.coerceAtLeast(1)} saved · in use: $source") { onNavigate("playlists") }
                NavRow("Edit this playlist", source) { onNavigate("setup") }
                NavRow("Refresh playlist", if (loading) progress.ifEmpty { "Refreshing…" } else "${"%,d".format(view.totalChannels)} channels loaded") { vm.refresh() }
            }
            SettingsGroup("TV guide") {
                NavRow(
                    "Refresh TV guide",
                    when {
                        epgStatus.isNotEmpty() -> epgStatus
                        epgError != null -> epgError!!
                        epg.channelCount > 0 -> "${"%,d".format(epg.channelCount)} channels covered, updated ${hm(epg.loadedAt)}"
                        else -> "Not loaded"
                    },
                ) { vm.refreshEpg() }
            }
            SettingsGroup("Channel check") {
                val scan by vm.healthScan.collectAsState()
                var auto by remember { mutableStateOf(p.autoCheck) }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    ToggleRow("Check channels automatically", "On Wi-Fi, one at a time, never while you're watching. Only channels inside your language and country filters.", auto) { auto = it; p.autoCheck = it }
                }
                NavRow(
                    if (scan.running) "Stop checking" else "Check channels now",
                    when {
                        scan.running -> "${scan.done} of ${scan.total} checked · ${scan.working} working · ${scan.dead} not working"
                        scan.paused != null -> scan.paused
                        scan.finishedAt > 0 -> "Last finished ${ago(scan.finishedAt)}"
                        else -> "Finds dead channels and each version's resolution and sound"
                    },
                ) { if (scan.running) vm.stopChecking() else vm.checkChannels(force = true) }
                NavRow("Forget results", "Clear what's known about every channel") { vm.forgetHealth() }
            }
            SettingsGroup("Reminders") {
                NavRow("Programme reminders", if (reminders.isEmpty()) "None set. Tap an upcoming programme in the guide" else "${reminders.size} upcoming") { onNavigate("reminders") }
            }
            SettingsGroup("Organise channels") {
                NavRow("Groups & languages", "Hide groups, correct language or country") { onNavigate("groups") }
                NavRow("Language rules", "Pattern-based tagging for tricky playlists") { onNavigate("rules") }
                NavRow(
                    if (pinSet) "Change adult PIN" else "Set adult PIN",
                    if (pinSet) "The Adult category asks for a PIN" else "Lock the Adult category behind a 4-digit PIN",
                ) { pinStep = if (pinSet) 1 else 2 }
                if (pinSet) NavRow("Remove adult PIN", null) { pinStep = 3 }
            }
            SettingsGroup("Backup") {
                NavRow("Back up settings", "Favourites, line-up, group fixes, rules, playlists and logins, to a file") {
                    exporter.launch("zapline-backup.json")
                }
                NavRow("Restore from backup", "Replaces your current settings") { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
            SettingsGroup("Playback") {
                Column(Modifier.padding(horizontal = 22.dp)) {
                    ToggleRow("Subtitles", "Show captions when a channel or film has them", subtitles) { subtitles = it; p.subtitlesOn = it }
                }
                ChoiceRow("Subtitle size", listOf(0.8f to "Small", 1f to "Normal", 1.3f to "Large", 1.6f to "Huge"), subScale) { subScale = it; p.subtitleScale = it }
                ChoiceRow("Picture", listOf(0 to "Fit", 1 to "Zoom", 2 to "Stretch"), resize) { resize = it; p.resizeMode = it }
                var rotation by remember { mutableIntStateOf(p.playerOrientation) }
                ChoiceRow("Player rotation", listOf(0 to "Auto", 1 to "Landscape", 2 to "Portrait"), rotation) { rotation = it; p.playerOrientation = it }
                var saver by remember { mutableIntStateOf(p.dataSaver) }
                ChoiceRow("Data saver", listOf(0 to "Off", 1 to "On mobile data", 2 to "Always"), saver) { saver = it; p.dataSaver = it }
                Text("Caps quality at 720p and picks lighter versions of channels.", Modifier.padding(horizontal = 22.dp),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                NavRow("Preferred audio", if (audioLang.isEmpty()) "Stream default" else Languages.label(audioLang)) { pickAudio = true }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    ToggleRow("Swipe gestures", "Left edge for brightness, right edge for volume", gestures) { gestures = it; p.gestures = it }
                }
            }
            Text("Zapline IPTV ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
        }
    }

    when (pinStep) {
        1, 3 -> PinDialog("Current PIN", null, onDismiss = { if (pinStep != 2) pinStep = 0 }) { pin ->
            (pin == p.adultPin).also { ok ->
                if (ok) { if (pinStep == 3) { p.adultPin = ""; pinSet = false; pinStep = 0 } else pinStep = 2 }
            }
        }
        2 -> PinDialog("New PIN", "Four digits. You'll need it to open the Adult category.", onDismiss = { pinStep = 0 }) { pin ->
            p.adultPin = pin; pinSet = true; vm.adultUnlocked.value = false; true
        }
    }

    if (pickAudio) {
        PickerDialog(
            title = "Preferred audio",
            options = Languages.labels.entries.map { it.key to it.value },
            current = audioLang,
            onPick = { code -> audioLang = code ?: ""; p.audioLang = code ?: ""; pickAudio = false },
            onDismiss = { pickAudio = false },
            automaticLabel = "Stream default",
            showUnknown = false,
        )
    }
}

/** Accent swatches; the whole app recolours as soon as one is tapped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(p: com.zapline.iptv.data.Prefs) {
    val current = Palette.Tally
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Accent colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(Accents.firstOrNull { it.color == current }?.name ?: "Custom", style = MaterialTheme.typography.bodySmall, color = Palette.TallyText)
        }
        Text("Used for what's live now, progress bars and main buttons.", Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        // Every colour visible at once, wrapping onto a second row.
        FlowRow(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Accents.forEach { a ->
                val on = a.color == current
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .border(if (on) 3.dp else 0.dp, if (on) Palette.Text else Color.Transparent, CircleShape)
                        .padding(if (on) 5.dp else 0.dp).clip(CircleShape).background(a.color)
                        .clickable { Palette.setAccent(a.color); p.accent = a.color.toArgb() }
                        .semantics { role = Role.RadioButton; selected = on; contentDescription = "${a.name} accent" },
                )
            }
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = Palette.Tally)
    content()
}

@Composable
internal fun NavRow(title: String, desc: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = Palette.Faint)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> FilterPill(label, v == current, { onPick(v) }, dropdown = false) }
        }
    }
}

// ================================================================== groups

@Composable
fun GroupsScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val groups by vm.groupInfo.collectAsState()
    val hidden by vm.hiddenGroups.collectAsState()
    val langOverrides by vm.groupLang.collectAsState()
    val regionOverrides by vm.groupRegion.collectAsState()
    var q by remember { mutableStateOf("") }
    /** group name to true when editing its country, false when editing its language. */
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val shown = remember(groups, q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) groups else groups.filter {
            it.name.lowercase().contains(t) || Languages.label(it.lang).lowercase().contains(t) ||
                RegionTagger.label(it.region).lowercase().contains(t)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Groups & languages", onBack = onBack) {
            if (hidden.isNotEmpty()) TextButton(onClick = { vm.unhideAll() }) { Text("Show all", color = Palette.Lilac) }
        }
        Text(
            "Untick a group to hide it everywhere. Tap its language or country to correct it, and every channel in the group follows.",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
        )
        SearchField(q, { q = it }, "Find a group", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.name }) { g ->
                val off = g.name in hidden
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)) {
                    Checkbox(!off, { vm.setGroupHidden(g.name, !it) },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.Tally, checkmarkColor = Palette.OnTally, uncheckedColor = Palette.Line))
                    Column(Modifier.weight(1f)) {
                        Text(g.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                            color = if (off) Palette.Faint else Palette.Text, modifier = Modifier.padding(top = 12.dp))
                        Text("${"%,d".format(g.count)} channels", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill(Languages.label(g.lang), g.overridden, { editing = g.name to false })
                            FilterPill(RegionTagger.label(g.region), g.regionOverridden, { editing = g.name to true })
                        }
                    }
                }
                HorizontalDivider(color = Palette.LineSoft, modifier = Modifier.padding(start = 56.dp))
            }
        }
    }

    editing?.let { (g, isRegion) ->
        if (isRegion) {
            PickerDialog(g, RegionTagger.labels.entries.map { it.key to it.value }, regionOverrides[g],
                onPick = { code -> vm.setGroupRegion(g, code); editing = null }, onDismiss = { editing = null })
        } else {
            PickerDialog(g, Languages.labels.entries.map { it.key to it.value }, langOverrides[g],
                onPick = { code -> vm.setGroupLang(g, code); editing = null }, onDismiss = { editing = null })
        }
    }
}

@Composable
fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    automaticLabel: String = "Automatic",
    showUnknown: Boolean = true,
) {
    val all: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to automaticLabel) + (if (showUnknown) listOf<Pair<String?, String>>("" to "Unknown") else emptyList()) + options
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Night,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(all) { (code, label) ->
                    val selected = code == current || (code == null && current.isNullOrEmpty() && !showUnknown)
                    Text(
                        label,
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(if (selected) Palette.TallyDim else Color.Transparent)
                            .clickable { onPick(code) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) Palette.TallyText else Palette.Text,
                        fontWeight = if (code == null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Lilac) } },
    )
}

// ================================================================== rules

private const val RULES_HELP = """One rule per line: pattern => language

Patterns are regular expressions, matched without caring about case against the group and channel name. Start a line with group: or name: to match only one of them. Use hide to drop matches. Rules beat automatic detection; per-group choices beat rules.

SKY SPORTS => en
group:^VIP => hide
name:\bMBC\b => arabic"""

@Composable
fun RulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val saved by vm.rulesText.collectAsState()
    var text by rememberSaveable { mutableStateOf(saved) }
    val parsed = remember(text) { LangRules.parse(text) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Language rules", onBack = onBack) {
            TextButton(onClick = { vm.saveRules(text); onBack() }, enabled = parsed.errors.isEmpty()) {
                Text("Save rules", color = if (parsed.errors.isEmpty()) Palette.Tally else Palette.Faint)
            }
        }
        Text(RULES_HELP, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        OutlinedTextField(
            text, { text = it },
            Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("SKY SPORTS => en", fontFamily = FontFamily.Monospace) },
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Tally, unfocusedBorderColor = Palette.Line,
                cursorColor = Palette.Tally, focusedContainerColor = Palette.Night, unfocusedContainerColor = Palette.Night),
        )
        Text(
            "${parsed.rules.size} rule${if (parsed.rules.size == 1) "" else "s"}",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
        )
        parsed.errors.forEach {
            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ================================================================== shared helpers

private val hmFormat = ThreadLocal.withInitial { java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK) }

/** 24-hour local time, e.g. "21:30". */
fun hm(ms: Long): String = hmFormat.get()!!.format(java.util.Date(ms))
