package com.zapline.iptv.desktop

import com.zapline.iptv.desktop.player.Mpv
import com.zapline.iptv.desktop.player.MpvPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The libmpv binding against the real library (skipped where libmpv isn't installed). */
class MpvPlayerTest {
    private val media = File("build/testmedia")

    private fun waitFor(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(50) }
    }

    /** Test clips: 1080p H.264 with 5.1 sound, and 4K HEVC. Made with ffmpeg the first time. */
    private fun ensureMedia() {
        val hd = File(media, "hd51.ts"); val uhd = File(media, "uhd.ts")
        if (hd.exists() && uhd.exists()) return
        media.mkdirs()
        fun ff(vararg a: String) = try {
            ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + a).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
        } catch (_: Exception) { false }
        ff("-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-ac", "6", hd.path)
        ff("-f", "lavfi", "-i", "testsrc2=size=3840x2160:rate=25", "-t", "4", "-c:v", "libx265", "-preset", "ultrafast", uhd.path)
        assumeTrue("test clips need ffmpeg", hd.exists() && uhd.exists())
    }

    private fun player(): MpvPlayer {
        assumeTrue("libmpv not available: ${Mpv.loadError}", Mpv.available)
        ensureMedia()
        val p = MpvPlayer("test")
        assertTrue(p.init(wid = null, inputConf = MpvPlayer.inputConf, headless = true))
        return p
    }

    @Test fun readsStreamDetailsAndTracks() {
        val p = player()
        try {
            p.load(File(media, "hd51.ts").absolutePath, "test-agent")
            waitFor("playing 1080p") { p.state.ready && p.state.height == 1080 && p.state.audioChannels > 0 }
            val s = p.state
            assertEquals(1920, s.width); assertEquals("1080p", s.resolution)
            assertEquals("h264", s.videoCodec?.lowercase()?.let { if ("264" in it) "h264" else it })
            assertEquals(6, s.audioChannels)
            waitFor("tracks") { p.state.tracks.any { it.type == "audio" } && p.state.tracks.any { it.type == "video" } }
            val audio = p.state.tracks.first { it.type == "audio" }
            assertEquals(6, audio.channels); assertTrue(audio.label, audio.label.contains("5.1"))

            p.load(File(media, "uhd.ts").absolutePath, "test-agent")
            waitFor("playing 4K") { p.state.ready && p.state.height == 2160 }
            assertEquals("4K", p.state.resolution)
            assertTrue(p.state.videoCodec, p.state.videoCodec!!.lowercase().contains("hevc") || p.state.videoCodec!!.lowercase().contains("265"))
        } finally { p.destroy() }
    }

    @Test fun reportsEndAndErrors() {
        val p = player()
        val ends = CopyOnWriteArrayList<String?>()
        p.onEnded = { ends += it }
        try {
            p.load(File(media, "uhd.ts").absolutePath, "test-agent")
            waitFor("end of file", 20_000) { ends.isNotEmpty() }
            assertNull("normal end", ends[0])
            ends.clear()
            p.load("http://127.0.0.1:9/missing.ts", "test-agent")
            waitFor("error") { ends.isNotEmpty() }
            assertNotNull(ends[0]); assertTrue(ends[0]!!, ends[0]!!.contains("couldn't be opened"))
            assertNotNull(p.state.error)
        } finally { p.destroy() }
    }

    @Test fun keyBindingsComeBackAsMessages() {
        val p = player()
        val msgs = CopyOnWriteArrayList<List<String>>()
        p.onMessage = { msgs += it }
        try {
            p.load(File(media, "hd51.ts").absolutePath, "test-agent")
            waitFor("ready") { p.state.ready }
            // Same path a key press takes: the binding runs "script-message zapline …".
            p.set("pause", "yes")
            p.javaClass.getDeclaredMethod("cmd", Array<String>::class.java).apply { isAccessible = true }
                .invoke(p, arrayOf("keypress", "UP"))
            waitFor("message") { msgs.any { it == listOf("zapline", "channel-up") } }
            p.javaClass.getDeclaredMethod("cmd", Array<String>::class.java).apply { isAccessible = true }
                .invoke(p, arrayOf("keypress", "7"))
            waitFor("digit") { msgs.any { it == listOf("zapline", "digit", "7") } }
            waitFor("paused") { p.state.paused }
        } finally { p.destroy() }
    }

    @Test fun survivesBeingRecreated() {
        val p = player()
        try {
            repeat(3) { round ->
                p.load(File(media, "hd51.ts").absolutePath, "test-agent")
                waitFor("ready round $round") { p.state.ready && p.state.height == 1080 }
                p.destroy()
                assertTrue(p.init(wid = null, inputConf = MpvPlayer.inputConf, headless = true))
            }
            p.load(File(media, "uhd.ts").absolutePath, "test-agent")
            waitFor("ready after re-creation") { p.state.ready && p.state.height == 2160 }
        } finally { p.destroy() }
    }

    @Test fun fillZoomForUltrawide() {
        // 16:9 in the G9's 5120x1440: fit = no zoom, 21:9 = 1.3125x, full width = 2x.
        assertEquals(0.0, MpvPlayer.fitZoom("fit", 5120, 1440, 1920, 1080), 1e-9)
        assertEquals(Math.log(1.3125) / Math.log(2.0), MpvPlayer.fitZoom("fill219", 5120, 1440, 1920, 1080), 1e-6)
        assertEquals(1.0, MpvPlayer.fitZoom("fillwidth", 5120, 1440, 3840, 2160), 1e-9)
        // A picture already wider than the area is never zoomed.
        assertEquals(0.0, MpvPlayer.fitZoom("fillwidth", 1920, 1080, 2560, 1080), 1e-9)
    }
}
