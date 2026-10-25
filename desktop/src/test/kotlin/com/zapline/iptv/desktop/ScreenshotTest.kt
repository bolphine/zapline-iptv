package com.zapline.iptv.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.StreamInfo
import com.zapline.iptv.desktop.data.DesktopPlatform
import com.zapline.iptv.desktop.ui.AppShell
import com.zapline.iptv.desktop.ui.AppState
import com.zapline.iptv.desktop.ui.Mode
import com.zapline.iptv.desktop.ui.Section
import com.zapline.iptv.desktop.ui.ZaplineDesktopTheme
import com.zapline.iptv.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Renders the whole window at Samsung G9 size (5120×1440) and at an ordinary 2560×1440, in each layout,
 * to build/screens/ for checking by eye. The picture is a placeholder here (no native window in a test).
 */
class ScreenshotTest {
    private fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(50) }
    }

    @Test fun screens() {
        val root = Files.createTempDirectory("zapline-shots").toFile()
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val names = listOf(
            "Sky Sports Main Event" to "UK| Sports", "Sky Sports Premier League" to "UK| Sports", "TNT Sports 1" to "UK| Sports",
            "BBC One" to "UK| General", "BBC Two" to "UK| General", "ITV1" to "UK| General", "Channel 4" to "UK| General",
            "Sky Cinema Premiere" to "UK| Movies", "Sky Cinema Action" to "UK| Movies", "Discovery" to "UK| Documentary",
            "National Geographic" to "UK| Documentary", "Sky News" to "UK| News", "BBC News" to "UK| News", "CNN International" to "UK| News",
            "Nature Ultra HD" to "UK| 4K", "Fashion 4K" to "UK| 4K", "Travel UHD" to "UK| 4K", "Eurosport 1" to "UK| Sports",
            "Sky Atlantic" to "UK| Entertainment", "Comedy Central" to "UK| Entertainment", "Dave" to "UK| Entertainment",
            "E4" to "UK| Entertainment", "More4" to "UK| Entertainment", "Film4" to "UK| Movies", "Sky Arts" to "UK| Entertainment",
        )
        val shows = listOf("Live Premier League", "Match of the Day", "The News at Six", "Planet Earth III", "Gogglebox", "Top Gear",
            "Grand Designs", "The Chase", "Pointless", "Countryfile", "Film: Dune Part Two", "Snooker: Masters", "Champions League Live",
            "Late News", "Taskmaster", "Would I Lie To You?")
        val sb = StringBuilder("#EXTM3U\n")
        val gx = StringBuilder("""<?xml version="1.0"?><tv>""")
        names.forEachIndexed { i, (n, g) ->
            val id = "c$i.uk"
            sb.append("#EXTINF:-1 tvg-id=\"$id\" group-title=\"$g\",$n HD\nhttp://h/$i.ts\n")
            if (i < 3) sb.append("#EXTINF:-1 tvg-id=\"$id\" group-title=\"$g\",$n 4K\nhttp://h/${i}uhd.ts\n")
            gx.append("<channel id=\"$id\"><display-name>$n</display-name></channel>")
            var t = now - (20 + i * 7) * 60_000L
            var k = i
            while (t < now + 14 * 3600_000L) {
                val len = listOf(30, 60, 45, 90, 120, 60)[k % 6] * 60_000L
                gx.append("<programme start=\"${fmt.format(java.util.Date(t))}\" stop=\"${fmt.format(java.util.Date(t + len))}\" channel=\"$id\"><title>${shows[k % shows.size]}</title><desc>A description of this programme, long enough to wrap over a couple of lines in the panel beside the picture.</desc></programme>")
                t += len; k++
            }
        }
        gx.append("</tv>")
        val playlist = File(root, "list.m3u").apply { writeText(sb.toString()) }
        val guide = File(root, "guide.xml").apply { writeText(gx.toString()) }

        val platform = DesktopPlatform(File(root, "app"))
        platform.prefs.apply { sourceType = "file"; fileUri = playlist.path; fileName = "list.m3u"; epgUrl = guide.toURI().toString(); langFilter = emptySet(); regionFilter = emptySet(); autoCheck = false; loadVod = false }
        Health.resetForTests()
        Health.load(platform.filesDir)
        Health.put("http://h/14.ts", StreamInfo(StreamInfo.OK, 3840, 2160, fps = 50f, videoCodec = "HEVC", audioChannels = 6, checkedAt = now))
        Health.put("http://h/0uhd.ts", StreamInfo(StreamInfo.OK, 3840, 2160, fps = 50f, videoCodec = "HEVC", checkedAt = now))
        Health.put("http://h/15.ts", StreamInfo(StreamInfo.OK, 1920, 1080, checkedAt = now))
        Health.put("http://h/3.ts", StreamInfo(StreamInfo.OK, 1920, 1080, fps = 50f, videoCodec = "H264", audioChannels = 2, checkedAt = now))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = MainViewModel(platform, scope)
            vm.refresh()
            waitFor("channels") { vm.view.value.ready && vm.view.value.totalChannels > 20 }
            waitFor("guide") { vm.epgIds.value.size > 10 }
            val app = AppState(vm, platform, scope)
            val out = File("build/screens").apply { mkdirs() }
            val holder = mutableStateOf(app)

            fun shot(name: String, w: Int, h: Int, setup: () -> Unit) {
                setup()
                val scene = ImageComposeScene(w, h, Density(1f)) { ZaplineDesktopTheme { AppShell(holder.value) } }
                var t = 0L
                repeat(6) { scene.render(t); t += 200_000_000L; Thread.sleep(80) }
                File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }

            for ((tag, w) in listOf("g9" to 5120, "qhd" to 2560)) {
                shot("$tag-01-empty", w, 1440) { app.mode = Mode.BROWSE; app.section = Section.LIVE }
                shot("$tag-02-live", w, 1440) { app.watchLive(vm.view.value.entries.first { it.name.startsWith("Sky Sports Main") }) }
                shot("$tag-03-fourk", w, 1440) { app.section = Section.FOURK }
                shot("$tag-04-guide", w, 1440) { app.section = Section.GUIDE }
                shot("$tag-05-theatre", w, 1440) { app.section = Section.LIVE; app.mode = Mode.THEATRE }
                shot("$tag-06-fullscreen", w, 1440) { app.mode = Mode.FULLSCREEN }
                shot("$tag-07-multiview", w, 1440) { app.mode = Mode.BROWSE; app.enterMultiview() }
                shot("$tag-08-settings", w, 1440) { app.exitMultiview(); app.section = Section.SETTINGS }
                shot("$tag-09-search", w, 1440) { vm.searchQuery.value = "news"; app.section = Section.SEARCH; Thread.sleep(600) }
                app.section = Section.LIVE
            }
            app.setup = true
            shot("setup", 2560, 1440) {}
        } finally { scope.cancel() }
    }
}
