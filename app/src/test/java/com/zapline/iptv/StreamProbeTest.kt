package com.zapline.iptv

import com.zapline.iptv.data.StreamInfo
import com.zapline.iptv.data.StreamProbe
import com.zapline.iptv.data.TsProbe
import com.zapline.iptv.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class StreamProbeTest {
    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    // x264 SPS, 1920x1080 High@4.0 and 1280x720 High@3.1
    private val sps1080 = hex("67 64 00 28 ac d9 40 78 02 27 e5 c0 44 00 00 03 00 04 00 00 03 00 c8 3c 60 c6 58")
    private val sps720 = hex("67 64 00 1f ac d9 40 50 05 bb 01 10 00 00 03 00 10 00 00 03 03 c0 f1 83 19 60")
    private val adtsStereo = hex("ff f1 4c 80 20 1f fc 21 00 49")
    private val adts51 = hex("ff f1 4d 80 20 1f fc 21 00 49")

    private fun packet(pid: Int, pusi: Boolean, cc: Int, payload: ByteArray, pcr: Long = -1): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8 and 0x1F)).toByte(); p[2] = (pid and 0xFF).toByte()
        if (pcr >= 0) {
            p[3] = (0x30 or (cc and 0xF)).toByte(); p[4] = 7; p[5] = 0x10
            p[6] = (pcr shr 25).toByte(); p[7] = (pcr shr 17).toByte(); p[8] = (pcr shr 9).toByte(); p[9] = (pcr shr 1).toByte()
            p[10] = (((pcr and 1) shl 7) or 0x7E).toInt().toByte(); p[11] = 0
            System.arraycopy(payload, 0, p, 12, minOf(payload.size, 176))
        } else {
            p[3] = (0x10 or (cc and 0xF)).toByte()
            System.arraycopy(payload, 0, p, 4, minOf(payload.size, 184))
        }
        return p
    }

    private fun pat() = hex("00 00 b0 0d 00 01 c1 00 00 00 01 f0 00 00 00 00 00")
    private fun pmt(audioType: Int) = byteArrayOf(0, 0x02, 0xB0.toByte(), 23, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0,
        0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0, audioType.toByte(), 0xE1.toByte(), 0x01, 0xF0.toByte(), 0, 0, 0, 0, 0)
    private fun pes(sid: Int, es: ByteArray) = byteArrayOf(0, 0, 1, sid.toByte(), 0, 0, 0x80.toByte(), 0, 0) + es

    private fun ts(sps: ByteArray, adts: ByteArray, seconds: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(packet(0, true, 0, pat())); out.write(packet(0x1000, true, 0, pmt(0x0F)))
        var cc = 0
        for (f in 0 until seconds * 25) {
            val pcr = 900_000L + f * 3600L
            val es = if (f == 0) byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()) else byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3)
            out.write(packet(0x100, true, cc, pes(0xE0, es), pcr))
            repeat(20) { out.write(packet(0x100, false, cc, ByteArray(184) { 7 })) }
            out.write(packet(0x101, true, cc, pes(0xC0, adts + ByteArray(40))))
            cc++
        }
        return out.toByteArray()
    }

    @Test fun readsResolutionAudioAndBitrateFromTs() {
        val r = TsProbe().run(ts(sps1080, adts51).inputStream(), 0L)
        assertEquals(StreamInfo.OK, r.state)
        assertEquals(1920, r.width); assertEquals(1080, r.height)
        assertEquals("1080p", r.resolution)
        assertEquals("H.264", r.videoCodec); assertEquals("AAC", r.audioCodec)
        assertEquals(6, r.audioChannels); assertEquals("5.1", r.audio)
        assertTrue("bitrate ${r.bitrate}", r.bitrate in 700_000..1_000_000)   // 22 packets x 188 B every 40 ms ≈ 0.83 Mb/s

        val s = TsProbe().run(ts(sps720, adtsStereo).inputStream(), 0L)
        assertEquals(1280, s.width); assertEquals(720, s.height); assertEquals("Stereo", s.audio)
        assertTrue("1080p 5.1 ranks above 720p stereo", r.score > s.score)
    }

    @Test fun hlsMasterGivesBestVariant() {
        val port = serve(mapOf("/v/hi.m3u8" to ("200 OK" to "#EXTM3U\n#EXTINF:6,\nseg1.ts\n")))
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="en",CHANNELS="6"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",AUDIO="a"
            lo.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=7800000,RESOLUTION=1920x1080,CODECS="avc1.640028,ec-3",FRAME-RATE=50.000,AUDIO="a"
            hi.m3u8
        """.trimIndent()
        val r = StreamProbe.hls(master, "http://127.0.0.1:$port/v/master.m3u8", "test", 0L)
        assertEquals(StreamInfo.OK, r.state)
        assertEquals(1080, r.height); assertEquals(50f, r.fps); assertEquals("Dolby Digital+", r.audioCodec); assertEquals(6, r.audioChannels)
        assertEquals(7_800_000, r.bitrate)
    }

    @Test fun refusalsAreBusyNotDead() {
        val port = serve(mapOf("/live/u/p/1.ts" to ("460 Refused" to "x"), "/live/u/p/2.ts" to ("404 Not Found" to "x")))
        assertEquals(StreamInfo.BUSY, StreamProbe.probe(Channel("a", "http://127.0.0.1:$port/live/u/p/1.ts", "g"), null).state)
        assertEquals(StreamInfo.DEAD, StreamProbe.probe(Channel("b", "http://127.0.0.1:$port/live/u/p/2.ts", "g"), null).state)
        assertEquals(StreamInfo.DEAD, StreamProbe.probe(Channel("c", "http://127.0.0.1:9/nothing.ts", "g"), null).state)
    }

    private fun serve(routes: Map<String, Pair<String, String>>): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader()
                        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return@thread
                        while (!r.readLine().isNullOrEmpty()) {}
                        val (code, body) = routes[path] ?: ("404 Not Found" to "")
                        it.getOutputStream().write("HTTP/1.1 $code\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    }
                }
            }
        }
        return ss.localPort
    }
}
