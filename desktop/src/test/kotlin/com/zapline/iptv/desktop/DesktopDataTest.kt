package com.zapline.iptv.desktop

import com.zapline.iptv.data.Health
import com.zapline.iptv.data.StreamInfo
import com.zapline.iptv.desktop.data.DesktopPlatform
import com.zapline.iptv.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The Windows app's data path: settings file, playlist file, XMLTV guide, shared view model, 4K view. */
class DesktopDataTest {
    private fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(50) }
    }

    @Test fun loadsPlaylistGuideAndFindsFourK() {
        val root = Files.createTempDirectory("zapline").toFile()
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val playlist = File(root, "list.m3u").apply { writeText("""
            #EXTM3U
            #EXTINF:-1 tvg-id="SkyMain.uk" group-title="UK| Sports",Sky Sports Main Event HD
            http://h/1.ts
            #EXTINF:-1 tvg-id="SkyMain.uk" group-title="UK| Sports",Sky Sports Main Event 4K
            http://h/2.ts
            #EXTINF:-1 group-title="UK| 4K",Nature Ultra HD
            http://h/3.ts
            #EXTINF:-1 group-title="UK| 4K",Fake Cinema 4K
            http://h/4.ts
            #EXTINF:-1 group-title="UK| General",BBC One FHD
            http://h/5.ts
        """.trimIndent()) }
        val guide = File(root, "guide.xml").apply { writeText("""<?xml version="1.0"?><tv><channel id="SkyMain.uk"><display-name>Sky Sports Main Event</display-name></channel>
            <programme start="${fmt.format(java.util.Date(now - 600_000))}" stop="${fmt.format(java.util.Date(now + 3_000_000))}" channel="SkyMain.uk"><title>Live Premier League</title></programme></tv>""") }

        val platform = DesktopPlatform(File(root, "app"))
        platform.prefs.apply { sourceType = "file"; fileUri = playlist.path; fileName = "list.m3u"; epgUrl = guide.toURI().toString(); langFilter = emptySet(); regionFilter = emptySet(); autoCheck = false }
        Health.resetForTests()
        Health.load(platform.filesDir)
        // "Fake Cinema 4K" was checked and is really 1080p.
        Health.put("http://h/4.ts", StreamInfo(StreamInfo.OK, 1920, 1080, checkedAt = now))
        Health.put("http://h/3.ts", StreamInfo(StreamInfo.OK, 3840, 2160, checkedAt = now))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = MainViewModel(platform, scope)
            vm.refresh()
            waitFor("channels") { vm.view.value.ready && vm.view.value.totalChannels == 5 }
            val entries = vm.view.value.entries
            assertEquals("HD and 4K Sky versions merged", 4, entries.size)
            val sky = entries.first { it.name.startsWith("Sky Sports Main Event") }
            assertTrue("PC prefers the 4K version first: ${sky.variants.map { it.ch.name }}", sky.variants[0].ch.name.endsWith("4K"))

            waitFor("4K view") { vm.fourK.value.total == 3 }
            val fk = vm.fourK.value
            assertEquals(listOf("Nature Ultra HD"), fk.verified.map { it.variant.ch.name })
            assertEquals(listOf("Sky Sports Main Event 4K"), fk.labelled.map { it.variant.ch.name })
            assertEquals(listOf("Fake Cinema 4K"), fk.notReally.map { it.variant.ch.name })
            assertEquals(1080, fk.notReally[0].info!!.height)

            // XMLTV over file:// with kxml2 on the desktop
            waitFor("guide") { vm.epg.value.channelCount > 0 && vm.epgIds.value.isNotEmpty() }
            val id = vm.epgIds.value[sky.key]
            assertEquals("SkyMain.uk", id)
            assertEquals("Live Premier League", vm.epg.value.nowNext(id, now).first?.title)

            // Settings persisted to %APPDATA%\Zapline\settings.json equivalent
            assertTrue(File(root, "app/settings.json").readText().contains("sourceType"))
        } finally { scope.cancel() }
    }
}
