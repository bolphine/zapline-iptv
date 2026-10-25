package com.zapline.iptv.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Reminder
import com.zapline.iptv.desktop.data.DesktopPlatform
import com.zapline.iptv.desktop.player.PlaybackController
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry
import com.zapline.iptv.ui.Entry
import com.zapline.iptv.ui.MainViewModel
import com.zapline.iptv.ui.movieEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Section(val title: String) {
    LIVE("Live TV"), FOURK("4K"), GUIDE("TV guide"), FILMS("Films"), SERIES("Series"), SPORT("Sport"), SEARCH("Search"), SETTINGS("Settings")
}

/** How the window is laid out. */
enum class Mode { BROWSE, THEATRE, FULLSCREEN, MULTIVIEW }

enum class SideTab(val title: String) { NOW("Now & next"), GUIDE("Guide"), MATCH("Match"), STREAM("Stream") }

/** How the picture fills its area (see MpvPlayer.setFit). */
enum class Fit(val key: String, val title: String) {
    FIT("fit", "Fit"), FILL219("fill219", "Fill 21:9"), FILLWIDTH("fillwidth", "Fill width"), STRETCH("stretch", "Stretch")
}

/** Everything the Windows UI shows and does, on top of the shared view model. */
class AppState(val vm: MainViewModel, val platform: DesktopPlatform, val scope: CoroutineScope) {
    val prefs get() = platform.prefs
    val playback = PlaybackController(platform.prefs, scope)

    var section by mutableStateOf(Section.LIVE)
    var mode by mutableStateOf(Mode.BROWSE)
    var sideTab by mutableStateOf(SideTab.NOW)
    var fit by mutableStateOf(Fit.entries.firstOrNull { it.key == prefs.p.getString("desktopFit", "fit") } ?: Fit.FIT)
        private set
    /** Fullscreen on a very wide screen: picture in the middle, channel list and info in the side bars. */
    var fullscreenPanels by mutableStateOf(prefs.p.getBoolean("desktopFsPanels", true))
        private set
    /** Show the setup screen (first run, or changing playlist). */
    var setup by mutableStateOf(false)
    /** A programme picked in the guide or search, for the watch / remind / catch-up dialog. */
    var programme by mutableStateOf<Pair<Entry, Programme>?>(null)
    /** The show opened on the Series page. */
    var show by mutableStateOf<Channel?>(null)
    /** Short message at the bottom of the window ("Reminder: …", "Channel 12"). */
    var toast by mutableStateOf<String?>(null)
    /** Digits typed for a channel number. */
    var digits by mutableStateOf("")
    /** Which multiview tile a channel picked from the list goes to (null = main). */
    var pickingForTile by mutableStateOf<Int?>(null)
    /** A reminder that's due, offering to switch to it. */
    var dueReminder by mutableStateOf<Reminder?>(null)

    private var toastJob: Job? = null
    private var digitJob: Job? = null

    fun chooseFit(f: Fit) { fit = f; prefs.p.edit().putString("desktopFit", f.key).apply(); say("Picture: ${f.title}") }
    fun cycleFit() = chooseFit(Fit.entries[(fit.ordinal + 1) % Fit.entries.size])
    fun chooseFullscreenPanels(on: Boolean) { fullscreenPanels = on; prefs.p.edit().putBoolean("desktopFsPanels", on).apply() }

    /** mpv options from the settings, applied when a picture is created. */
    fun playerOptions(): Map<String, String> = buildMap {
        put("hwdec", if (prefs.p.getBoolean("desktopHwdec", true)) "auto-safe" else "no")
        put("alang", listOf(prefs.audioLang, "en", "eng").distinct().joinToString(","))
        put("slang", listOf(prefs.audioLang, "en", "eng").distinct().joinToString(","))
        put("sid", if (prefs.subtitlesOn) "auto" else "no")
        if (prefs.p.getBoolean("desktopHdr", false)) put("target-colorspace-hint", "yes")
    }

    fun say(msg: String, ms: Long = 2_500) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(ms); toast = null }
    }

    // ---------------------------------------------------------------- playing things

    /** Plays [list] from [i] in the main picture (or the multiview tile being picked for). */
    fun play(list: List<PlayEntry>, i: Int) {
        val tile = pickingForTile?.let { playback.tiles.getOrNull(it) }
        pickingForTile = null
        if (tile != null && tile !== playback.main) { tile.play(list, i); playback.applyAudio(); return }
        playback.play(list, i)
        if (mode == Mode.BROWSE && section !in setOf(Section.LIVE, Section.FOURK, Section.SEARCH)) { /* keep browsing; mini player shows it */ }
    }

    fun playEntries(list: List<Entry>, i: Int) = play(list.map { it.toPlay(vm.epgIdFor(it)) }, i)
    fun playMovie(c: Channel) = play(listOf(movieEntry(c)), 0)

    fun watchLive(e: Entry) {
        val list = vm.view.value.entries
        val i = list.indexOfFirst { it.key == e.key }
        if (i >= 0) playEntries(list, i) else playEntries(listOf(e), 0)
    }

    // ---------------------------------------------------------------- modes

    fun toggleFullscreen() { mode = if (mode == Mode.FULLSCREEN) Mode.BROWSE else Mode.FULLSCREEN }
    fun toggleTheatre() { mode = if (mode == Mode.THEATRE) Mode.BROWSE else Mode.THEATRE }

    fun enterMultiview() {
        if (com.zapline.iptv.data.XtreamAuth.singleConnection) { say("Your account allows one stream at a time, so multiview can't open a second channel."); return }
        if (playback.extra.isEmpty()) playback.addTile()
        mode = Mode.MULTIVIEW
    }

    fun exitMultiview() { playback.clearMultiview(); mode = Mode.BROWSE }

    fun escape() {
        when {
            programme != null -> programme = null
            mode == Mode.FULLSCREEN || mode == Mode.THEATRE -> mode = Mode.BROWSE
            mode == Mode.MULTIVIEW -> exitMultiview()
        }
    }

    /** Typed digits jump to that position in the current list (your line-up numbers when it's favourites). */
    fun digit(d: Int) {
        digits = (digits + d).takeLast(4)
        say("Channel $digits", 1_600)
        digitJob?.cancel()
        digitJob = scope.launch {
            delay(1_300)
            val n = digits.toIntOrNull(); digits = ""
            val t = playback.main
            if (n != null && n in 1..t.queue.size) t.jump(n - 1) else say("No channel $n")
        }
    }

    /** Keys pressed while the video has focus (mpv sends them back as "zapline <action> …"). */
    fun onVideoMessage(tileIndex: Int, args: List<String>) {
        if (args.firstOrNull() != "zapline") return
        val t = playback.tiles.getOrNull(tileIndex) ?: return
        when (args.getOrNull(1)) {
            "fullscreen" -> toggleFullscreen()
            "escape" -> escape()
            "theatre" -> toggleTheatre()
            "multiview" -> if (mode == Mode.MULTIVIEW) exitMultiview() else enterMultiview()
            "channel-up" -> t.step(-1)
            "channel-down" -> t.step(1)
            "last-channel" -> t.lastChannel()
            "digit" -> args.getOrNull(2)?.toIntOrNull()?.let(::digit)
            "fit" -> cycleFit()
            "guide" -> { mode = Mode.BROWSE; section = Section.GUIDE }
            "list" -> { if (mode != Mode.BROWSE) mode = Mode.BROWSE; section = Section.LIVE }
            "info" -> { sideTab = SideTab.STREAM; if (mode == Mode.FULLSCREEN) mode = Mode.THEATRE }
            "click", "menu" -> if (mode == Mode.MULTIVIEW) playback.focusAudio(tileIndex)
            "audio" -> cycleTrack(t, "audio")
            "subtitles" -> cycleTrack(t, "sub")
        }
    }

    private fun cycleTrack(t: com.zapline.iptv.desktop.player.Tile, type: String) {
        val tracks = t.player.state.tracks.filter { it.type == type }
        if (type == "sub") {
            val cur = tracks.indexOfFirst { it.selected }
            val next = if (cur + 1 >= tracks.size) null else tracks[cur + 1]
            t.player.selectSubtitle(next?.id); say(next?.let { "Subtitles: ${it.label}" } ?: "Subtitles off")
        } else if (tracks.size > 1) {
            val cur = tracks.indexOfFirst { it.selected }
            val next = tracks[(cur + 1) % tracks.size]
            t.player.selectAudio(next.id); say("Audio: ${next.label}")
        } else say("This stream has one audio track")
    }

    /** Watches for reminders while Zapline is open. */
    fun startReminderWatch() = scope.launch {
        while (true) {
            platform.reminders.due().firstOrNull()?.let { r ->
                dueReminder = r
                vm.reminders.value = platform.reminders.all()
                Notifier.notify("Starting now: ${r.title}", "on ${r.channelName}")
            }
            delay(20_000)
        }
    }
}
