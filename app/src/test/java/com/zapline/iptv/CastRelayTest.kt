package com.zapline.iptv

import com.zapline.iptv.player.CastProxy
import com.zapline.iptv.player.TsSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/** Relay behaviour that caused buffering and replays on the TV. */
class CastRelayTest {
    private fun packet(pid: Int, pusi: Boolean, cc: Int, payload: ByteArray, rai: Boolean = false): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8 and 0x1F)).toByte(); p[2] = (pid and 0xFF).toByte()
        if (rai) { p[3] = (0x30 or (cc and 0xF)).toByte(); p[4] = 1; p[5] = 0x40; System.arraycopy(payload, 0, p, 6, minOf(payload.size, 182)) }
        else { p[3] = (0x10 or (cc and 0xF)).toByte(); System.arraycopy(payload, 0, p, 4, minOf(payload.size, 184)) }
        return p
    }
    private val pat = byteArrayOf(0, 0, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0, 0, 0, 0, 0)
    private val pmt = byteArrayOf(0, 2, 0xB0.toByte(), 23, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0, 0xF0.toByte(), 0,
        0x1B, 0xE1.toByte(), 0, 0xF0.toByte(), 0, 0x0F, 0xE1.toByte(), 1, 0xF0.toByte(), 0, 0, 0, 0, 0)
    private fun pes(pts: Long) = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
        (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xFE) or 1).toByte(),
        (pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte(), 0, 0, 0, 1, 0x65)

    /** 25 fps, keyframe every second, video frames [from, to). PTS = base + frame * 3600. */
    private fun ts(from: Int, to: Int, base: Long = 900_000L): ByteArray {
        val out = ByteArrayOutputStream()
        var cc = 0
        for (f in from until to) {
            if (f % 25 == 0) { out.write(packet(0, true, cc, pat)); out.write(packet(0x1000, true, cc, pmt)) }
            out.write(packet(0x100, true, cc, pes(base + f * 3600L), rai = f % 25 == 0))
            out.write(packet(0x100, false, cc, ByteArray(184) { 1 }))
            cc++
        }
        return out.toByteArray()
    }

    /** A provider that serves [parts] one per connection, then hangs up for good. */
    private fun segmenter(vararg parts: ByteArray): TsSegmenter {
        val n = AtomicInteger(0)
        return TsSegmenter(CastProxy.Source("http://test/1.ts", emptyMap())) { _ ->
            val i = n.getAndIncrement()
            if (i < parts.size) parts[i].inputStream() else object : InputStream() { override fun read() = -1.also { Thread.sleep(50) } }
        }.also { it.start() }
    }

    private fun waitPlaylist(s: TsSegmenter, minSegments: Int): String {
        val end = System.currentTimeMillis() + 20_000
        var pl: String? = null
        while (System.currentTimeMillis() < end) {
            pl = s.playlist(1_000)
            if (pl != null && pl.lines().count { it.endsWith(".ts") } >= minSegments) return pl
            Thread.sleep(200)
        }
        throw AssertionError("no playlist with $minSegments segments: $pl")
    }

    private fun durations(pl: String) = Regex("#EXTINF:([0-9.]+),").findAll(pl).map { it.groupValues[1].toDouble() }.toList()

    @Test fun reconnectOverlapIsNotReplayed() {
        // 0-6 s, connection drops, provider resends from 4 s and carries on to 12 s.
        val s = segmenter(ts(0, 150), ts(100, 300))
        val pl = waitPlaylist(s, 6)
        Thread.sleep(1_500)                       // and nothing more arrives: a replay would add segments
        val after = s.playlist(1_000)!!
        assertEquals("no extra (replayed) segments appear", pl.lines().count { it.endsWith(".ts") }, after.lines().count { it.endsWith(".ts") })
        s.stop()
        val total = durations(pl).sum()
        assertTrue("12 s of video, not 14 (the 2 s overlap must not be sent twice): $total\n$pl", total in 11.0..12.5)
        assertFalse("seamless: no discontinuity\n$pl", pl.contains("#EXT-X-DISCONTINUITY\n"))
        assertTrue(pl, pl.contains("#EXT-X-TARGETDURATION:6\n"))
    }

    @Test fun newTimelineAfterReconnectIsMarked() {
        // Provider restarts its clock: the TV must be told to reset.
        val s = segmenter(ts(0, 150), ts(0, 200, base = 50_000_000L))
        val pl = waitPlaylist(s, 5)
        s.stop()
        assertTrue("discontinuity on a new timeline\n$pl", pl.contains("#EXT-X-DISCONTINUITY\n"))
        assertTrue(pl, durations(pl).sum() > 10.0)
    }

    @Test fun hlsSegmentNamesAreStableAcrossRefreshes() {
        val pl = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg100.ts\n#EXTINF:4,\nseg101.ts\n"
        val a = CastProxy.rewritePlaylist(pl, "http://p/live/index.m3u8", emptyMap(), "http://phone:1")
        val b = CastProxy.rewritePlaylist(pl, "http://p/live/index.m3u8", emptyMap(), "http://phone:1")
        assertEquals("same segment, same URL on every refresh", a, b)
    }
}
