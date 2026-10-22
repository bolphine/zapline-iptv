package com.zapline.iptv

import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.CastProxy
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The phone-side relay the Chromecast pulls from. Runs on the JVM against a fake provider. */
class CastProxyTest {

    // ---------------- synthetic MPEG-TS: PAT, PMT (H.264 on 0x100, AAC on 0x101), video PES every 40 ms, keyframe every second

    private fun packet(pid: Int, pusi: Boolean, cc: Int, payload: ByteArray, rai: Boolean = false): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (((if (pusi) 0x40 else 0) or (pid shr 8 and 0x1F))).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (rai) {
            p[3] = (0x30 or (cc and 0xF)).toByte()
            p[4] = 1; p[5] = 0x40
            System.arraycopy(payload, 0, p, 6, minOf(payload.size, 182))
        } else {
            p[3] = (0x10 or (cc and 0xF)).toByte()
            System.arraycopy(payload, 0, p, 4, minOf(payload.size, 184))
        }
        return p
    }

    private fun pat(): ByteArray = byteArrayOf(0, 0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00, 0, 0, 0, 0)
    private fun pmt(): ByteArray = byteArrayOf(
        0, 0x02, 0xB0.toByte(), 23, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0,
        0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0,
        0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0,
        0, 0, 0, 0,
    )

    private fun pes(pts: Long): ByteArray = byteArrayOf(
        0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
        (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(), (pts shr 22).toByte(),
        (((pts shr 14) and 0xFE) or 1).toByte(), (pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte(),
        0, 0, 0, 1, 0x65,
    )

    private fun stream(seconds: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var cc = 0
        val start = 900_000L
        for (frame in 0 until seconds * 25) {
            if (frame % 25 == 0) { out.write(packet(0, true, cc, pat())); out.write(packet(0x1000, true, cc, pmt())) }
            out.write(packet(0x100, true, cc, pes(start + frame * 3600L), rai = frame % 25 == 0))
            out.write(packet(0x100, false, cc, ByteArray(184) { 1 }))
            if (frame % 2 == 0) out.write(packet(0x101, true, cc, ByteArray(20)))
            cc++
        }
        return out.toByteArray()
    }

    /** A one-route HTTP server standing in for the IPTV provider. */
    private fun provider(routes: Map<String, Pair<String, ByteArray>>): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = ss.accept()
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader()
                        val line = r.readLine() ?: return@thread
                        var h: String?
                        var ua = ""
                        do { h = r.readLine(); if (h?.startsWith("User-Agent:", true) == true) ua = h.substringAfter(':').trim() } while (!h.isNullOrEmpty())
                        val path = line.split(' ')[1]
                        val (type, body) = routes[path] ?: ("text/plain" to "nope".toByteArray())
                        val code = if (routes.containsKey(path) && (path != "/needs-ua.ts" || ua == "MyBox/1.0")) "200 OK" else "403 Forbidden"
                        val o = it.getOutputStream()
                        o.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        o.write(body); o.flush()
                    }
                }
            }
        }
        return ss.localPort
    }

    private fun get(url: String) = CastProxy.http.newCall(Request.Builder().url(url).build()).execute()

    @Test fun liveTsBecomesRollingHls() {
        val port = provider(mapOf("/live/u/p/1.ts" to ("video/mp2t" to stream(12))))
        val prep = CastProxy.prepare(Channel("BBC One", "http://127.0.0.1:$port/live/u/p/1.ts", "UK"), live = true, host = "127.0.0.1")!!
        assertEquals(CastProxy.Kind.LIVE_TS, prep.kind)
        val pl = get(prep.url).use { r ->
            assertEquals(200, r.code)
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            r.body!!.string()
        }
        assertTrue(pl, pl.startsWith("#EXTM3U"))
        assertTrue(pl, pl.contains("#EXT-X-TARGETDURATION:"))
        val infs = Regex("#EXTINF:([0-9.]+),").findAll(pl).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(pl, infs.size >= 2)
        infs.dropLast(1).forEach { assertEquals("keyframe every second, target 2s", 2.0, it, 0.05) }
        val first = pl.lines().first { it.endsWith(".ts") }
        val seg = get(prep.url.substringBeforeLast('/') + "/" + first).use { it.body!!.bytes() }
        assertEquals(0, seg.size % 188)
        assertEquals(0x47, seg[0].toInt())
        assertEquals("segment starts with the PAT", 0, ((seg[1].toInt() and 0x1F) shl 8) or (seg[2].toInt() and 0xFF))
        assertEquals("then the PMT", 0x1000, ((seg[189].toInt() and 0x1F) shl 8) or (seg[190].toInt() and 0xFF))
        assertEquals("then a keyframe", 0x40, seg[188 * 2 + 5].toInt() and 0x40)
        CastProxy.stopLive()
    }

    @Test fun providerHeadersAreSent() {
        val port = provider(mapOf("/needs-ua.ts" to ("video/mp2t" to stream(6))))
        val ch = Channel("X", "http://127.0.0.1:$port/needs-ua.ts", "UK", userAgent = "MyBox/1.0")
        val prep = CastProxy.prepare(ch, live = true, host = "127.0.0.1")!!
        get(prep.url).use { assertEquals(200, it.code) }
        CastProxy.stopLive()
    }

    @Test fun hlsIsRelayedWithRewrittenUris() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nhttp://cdn.example/abs/seg2.ts\n"
        val port = provider(mapOf(
            "/a/master.m3u8" to ("application/vnd.apple.mpegurl" to master.toByteArray()),
            "/a/low/index.m3u8" to ("application/vnd.apple.mpegurl" to media.toByteArray()),
            "/a/low/seg1.ts" to ("video/mp2t" to ByteArray(376) { 0x47 }),
        ))
        val prep = CastProxy.prepare(Channel("Y", "http://127.0.0.1:$port/a/master.m3u8", "UK"), live = true, host = "127.0.0.1")!!
        assertEquals(CastProxy.Kind.HLS, prep.kind)
        val m = get(prep.url).use { it.body!!.string() }
        val variant = m.lines().first { it.startsWith("http") }
        assertTrue(variant, variant.contains("/p/") && variant.endsWith("index.m3u8"))
        val pl = get(variant).use { r -> assertEquals("*", r.header("Access-Control-Allow-Origin")); r.body!!.string() }
        val uris = pl.lines().filter { it.startsWith("http") }
        assertEquals(2, uris.size)
        assertTrue("everything goes through the phone", uris.all { it.startsWith("http://127.0.0.1:") && "/p/" in it })
        assertTrue(pl, Regex("URI=\"http://127\\.0\\.0\\.1:\\d+/p/\\w+/key.bin\"").containsMatchIn(pl))
        get(uris[0]).use { assertEquals(376, it.body!!.bytes().size) }
    }
}
