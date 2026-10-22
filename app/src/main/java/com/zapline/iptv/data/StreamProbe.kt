package com.zapline.iptv.data

import com.zapline.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Opens a stream for a few seconds and works out what's in it, without playing it:
 * resolution (from the H.264/HEVC/MPEG-2 headers), interlacing, codecs, audio channels and bitrate (from PCR timing).
 * HLS is read from its playlist (RESOLUTION / BANDWIDTH / CODECS), checking the variant playlist is alive.
 */
object StreamProbe {
    const val CANCELLED = "Cancelled"

    // No idle connections kept around: providers can count a lingering socket against the account's limit.
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .followRedirects(true).followSslRedirects(true).build()
    }

    @Volatile private var inFlight: okhttp3.Call? = null

    /** Abandons the check in progress at once (Stop, or the player opening). */
    fun cancelInFlight() { inFlight?.cancel() }

    private fun call(req: Request): okhttp3.Call = http.newCall(req.newBuilder().header("Connection", "close").build()).also { inFlight = it }

    private const val MAX_BYTES = 1_500_000
    private const val MAX_MS = 7_000L

    fun probe(ch: Channel, prefs: Prefs?): StreamInfo {
        val url = if (prefs != null) XtreamAuth.withCurrentLogin(ch.url, prefs) else ch.url
        val ua = ch.userAgent ?: prefs?.let { XtreamAuth.userAgentFor(it, url) } ?: DEFAULT_USER_AGENT
        val now = System.currentTimeMillis()
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", ua)
            ch.referrer?.let { rb.header("Referer", it) }
            ch.origin?.let { rb.header("Origin", it) }
            call(rb.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    return if (XtreamAuth.isRefusal(r.code)) StreamInfo(StreamInfo.BUSY, checkedAt = now, note = "HTTP ${r.code}")
                    else StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "HTTP ${r.code}")
                }
                val body = r.body ?: return StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "Empty reply")
                val ins = body.byteStream().buffered(64 * 1024)
                ins.mark(16)
                val head = ByteArray(7).also { val n = ins.read(it); if (n <= 0) return StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "No data") }
                ins.reset()
                if (String(head, Charsets.ISO_8859_1).startsWith("#EXTM3U") || r.header("Content-Type").orEmpty().contains("mpegurl", true)) {
                    hls(String(ins.readNBytesCompat(512 * 1024), Charsets.UTF_8), r.request.url.toString(), ua, now)
                } else {
                    TsProbe().run(ins, now)
                }
            }
        } catch (e: Exception) {
            when {
                inFlight?.isCanceled() == true -> StreamInfo(StreamInfo.BUSY, checkedAt = now, note = CANCELLED)
                e is java.net.SocketTimeoutException -> StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "Timed out")
                else -> StreamInfo(StreamInfo.DEAD, checkedAt = now, note = e.message?.take(60) ?: "Failed")
            }
        } finally {
            inFlight = null
        }
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ HLS

    private val attr = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    internal fun attrs(line: String): Map<String, String> =
        attr.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun hls(text: String, base: String, ua: String, now: Long): StreamInfo {
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<Pair<Map<String, String>, String>>()
        var audioCh = 0
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                variants += attrs(l) to uri
            } else if (l.startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in l) {
                attrs(l)["CHANNELS"]?.substringBefore('/')?.toIntOrNull()?.let { audioCh = maxOf(audioCh, it) }
            }
        }
        if (variants.isEmpty()) {
            // A media playlist: alive if it lists segments; read the first segment for the details.
            val seg = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "Empty playlist")
            val segUrl = resolve(base, seg)
            return try {
                call(Request.Builder().url(segUrl).header("User-Agent", ua).build()).execute().use { r ->
                    if (!r.isSuccessful) StreamInfo(if (XtreamAuth.isRefusal(r.code)) StreamInfo.BUSY else StreamInfo.DEAD, checkedAt = now, note = "Segment HTTP ${r.code}")
                    else TsProbe().run(r.body!!.byteStream().buffered(64 * 1024), now).let { if (it.state == StreamInfo.DEAD) it.copy(state = StreamInfo.OK, note = "HLS") else it }
                }
            } catch (e: Exception) { StreamInfo(StreamInfo.OK, checkedAt = now, note = "HLS") }
        }
        val best = variants.maxByOrNull { (a, _) ->
            val (w, h) = res(a["RESOLUTION"]); w.toLong() * h * 10 + (a["BANDWIDTH"]?.toLongOrNull() ?: 0) / 1000
        }!!
        val a = best.first
        val (w, h) = res(a["RESOLUTION"])
        val codecs = a["CODECS"].orEmpty().lowercase()
        // Alive check: the chosen variant's playlist must load and list segments.
        val alive = try {
            call(Request.Builder().url(resolve(base, best.second)).header("User-Agent", ua).build()).execute().use { r ->
                if (!r.isSuccessful) if (XtreamAuth.isRefusal(r.code)) StreamInfo.BUSY else StreamInfo.DEAD
                else if (r.body!!.string().lines().any { it.isNotBlank() && !it.startsWith("#") }) StreamInfo.OK else StreamInfo.DEAD
            }
        } catch (_: Exception) { StreamInfo.DEAD }
        return StreamInfo(
            state = alive, width = w, height = h,
            fps = a["FRAME-RATE"]?.toFloatOrNull() ?: 0f,
            videoCodec = when { "hvc1" in codecs || "hev1" in codecs -> "HEVC"; "avc1" in codecs -> "H.264"; else -> null },
            audioCodec = when { "ec-3" in codecs -> "Dolby Digital+"; "ac-3" in codecs -> "Dolby Digital"; "mp4a" in codecs -> "AAC"; else -> null },
            audioChannels = if (audioCh > 0) audioCh else if ("ec-3" in codecs || "ac-3" in codecs) 6 else 0,
            bitrate = a["BANDWIDTH"]?.toIntOrNull() ?: 0,
            checkedAt = now, note = "HLS, ${variants.size} qualities",
        )
    }

    private fun res(s: String?): Pair<Int, Int> {
        val p = s?.lowercase()?.split('x') ?: return 0 to 0
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun resolve(base: String, ref: String): String = try { java.net.URI(base).resolve(ref).toString() } catch (_: Exception) { ref }
}

/** Reads MPEG-TS until it knows the video and audio format and has a bitrate, or gives up. */
class TsProbe {
    private var pmtPids = emptySet<Int>()
    private var videoPid = -1; private var videoType = -1
    private var audioPid = -1; private var audioType = -1
    private var pcrPid = -1
    private val video = java.io.ByteArrayOutputStream()
    private val audio = java.io.ByteArrayOutputStream()
    private var firstPcr = -1L; private var firstPcrBytes = 0L; private var lastPcr = -1L; private var lastPcrBytes = 0L
    private var bytes = 0L
    private var packets = 0

    var width = 0; private set
    var height = 0; private set
    var interlaced = false; private set
    var videoCodec: String? = null; private set
    var audioCodec: String? = null; private set
    var audioChannels = 0; private set

    fun run(ins: InputStream, now: Long, maxBytes: Int = 1_500_000, maxMs: Long = 7_000): StreamInfo {
        val start = System.currentTimeMillis()
        val p = ByteArray(188)
        while (bytes < maxBytes && System.currentTimeMillis() - start < maxMs) {
            val b = ins.read()
            if (b < 0) break
            bytes++
            if (b != 0x47) continue
            p[0] = 0x47
            var off = 1
            while (off < 188) { val n = ins.read(p, off, 188 - off); if (n < 0) break; off += n }
            if (off < 188) break
            bytes += 187
            packets++
            onPacket(p)
            if (done()) break
        }
        if (bytes == 0L) return StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "No data")
        // Sends data in a format this check can't read (e.g. MP4): it streams, so it works; just no details.
        if (packets < 10) return StreamInfo(StreamInfo.OK, checkedAt = now, note = if (bytes > 32_000) "Streams (format not analysed)" else "Very little data")
            .let { if (bytes > 32_000) it else it.copy(state = StreamInfo.DEAD) }
        val bitrate = if (firstPcr >= 0 && lastPcr > firstPcr) {
            val secs = (lastPcr - firstPcr) / 90_000.0
            if (secs > 0.05) ((lastPcrBytes - firstPcrBytes) * 8 / secs).toInt() else 0
        } else 0
        return StreamInfo(
            StreamInfo.OK, width = width, height = height, interlaced = interlaced,
            videoCodec = videoCodec, audioCodec = audioCodec, audioChannels = audioChannels,
            bitrate = bitrate, checkedAt = now,
        )
    }

    private fun done() = (width > 0 || videoPid < 0 && packets > 2000) && (audioChannels > 0 || audioPid < 0 && packets > 2000) &&
        (lastPcr - firstPcr > 45_000 || packets > 6000)

    internal fun onPacket(p: ByteArray) {
        val pusi = (p[1].toInt() and 0x40) != 0
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val afc = (p[3].toInt() shr 4) and 3
        val adaptLen = if (afc and 2 != 0) (p[4].toInt() and 0xFF) else -1
        var payload = when (afc) { 1 -> 4; 3 -> 5 + adaptLen; else -> 188 }
        if (pid == pcrPid && adaptLen >= 7 && (p[5].toInt() and 0x10) != 0) {
            val pcr = ((p[6].toLong() and 0xFF) shl 25) or ((p[7].toLong() and 0xFF) shl 17) or ((p[8].toLong() and 0xFF) shl 9) or
                ((p[9].toLong() and 0xFF) shl 1) or ((p[10].toLong() and 0x80) shr 7)
            if (firstPcr < 0) { firstPcr = pcr; firstPcrBytes = bytes } else if (pcr > firstPcr) { lastPcr = pcr; lastPcrBytes = bytes }
        }
        if (payload >= 188) return
        if (pid == 0 && pusi) parsePat(p, payload)
        else if (pid in pmtPids && pusi && videoPid < 0 && audioPid < 0) parsePmt(p, payload)
        else if (pid == videoPid && width == 0) {
            if (pusi) payload = skipPes(p, payload) ?: return
            if (video.size() < 400_000) video.write(p, payload, 188 - payload)
            if (video.size() > 1024) findVideoHeader()
        } else if (pid == audioPid && audioChannels == 0) {
            if (pusi) payload = skipPes(p, payload) ?: return
            if (audio.size() < 16_000) audio.write(p, payload, 188 - payload)
            if (audio.size() > 64) findAudioHeader()
        }
    }

    private fun skipPes(p: ByteArray, o: Int): Int? {
        if (o + 9 > 188 || p[o].toInt() != 0 || p[o + 1].toInt() != 0 || p[o + 2].toInt() != 1) return null
        val n = o + 9 + (p[o + 8].toInt() and 0xFF)
        return if (n < 188) n else null
    }

    private fun parsePat(p: ByteArray, payload: Int) {
        val s = payload + 1 + (p[payload].toInt() and 0xFF)
        if (s + 8 >= 188) return
        val len = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = minOf(s + 3 + len - 4, 188)
        val pids = HashSet<Int>()
        var i = s + 8
        while (i + 4 <= end) {
            val prog = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            if (prog != 0) pids += ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            i += 4
        }
        if (pids.isNotEmpty()) pmtPids = pids
    }

    private fun parsePmt(p: ByteArray, payload: Int) {
        val s = payload + 1 + (p[payload].toInt() and 0xFF)
        if (s + 12 >= 188) return
        val len = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = minOf(s + 3 + len - 4, 188)
        pcrPid = ((p[s + 8].toInt() and 0x1F) shl 8) or (p[s + 9].toInt() and 0xFF)
        val infoLen = ((p[s + 10].toInt() and 0x0F) shl 8) or (p[s + 11].toInt() and 0xFF)
        var i = s + 12 + infoLen
        while (i + 5 <= end) {
            var type = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            val esLen = ((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)
            if (type == 0x06) {
                // Private data: look for AC-3 / E-AC-3 descriptors.
                var d = i + 5
                while (d + 2 <= minOf(i + 5 + esLen, end)) {
                    when (p[d].toInt() and 0xFF) { 0x6A -> type = 0x81; 0x7A -> type = 0x87 }
                    d += 2 + (p[d + 1].toInt() and 0xFF)
                }
            }
            when (type) {
                0x01, 0x02, 0x10, 0x1B, 0x24 -> if (videoPid < 0) { videoPid = pid; videoType = type }
                0x03, 0x04, 0x0F, 0x11, 0x81, 0x87 -> if (audioPid < 0 || (audioType in setOf(0x03, 0x04, 0x0F, 0x11) && type in setOf(0x81, 0x87))) { audioPid = pid; audioType = type }
            }
            i += 5 + esLen
        }
        videoCodec = when (videoType) { 0x1B -> "H.264"; 0x24 -> "HEVC"; 0x01, 0x02 -> "MPEG-2"; 0x10 -> "MPEG-4"; else -> null }
        audioCodec = when (audioType) { 0x0F, 0x11 -> "AAC"; 0x03, 0x04 -> "MP2"; 0x81 -> "Dolby Digital"; 0x87 -> "Dolby Digital+"; else -> null }
    }

    // ------------------------------------------------------------------ video headers

    private fun findVideoHeader() {
        val b = video.toByteArray()
        var i = 0
        while (i + 4 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val start = i + 3
                val end = nextStart(b, start)
                if (end < 0 && b.size - start < 64) return       // header not complete yet
                val nal = b.copyOfRange(start, if (end < 0) b.size else end)
                try {
                    when (videoType) {
                        0x1B -> if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) { h264Sps(unescape(nal, 1)); return }
                        0x24 -> if (nal.size > 2 && ((nal[0].toInt() shr 1) and 0x3F) == 33) { hevcSps(unescape(nal, 2)); return }
                        0x01, 0x02 -> if (nal.isNotEmpty() && (nal[0].toInt() and 0xFF) == 0xB3 && nal.size >= 4) {
                            width = ((nal[1].toInt() and 0xFF) shl 4) or ((nal[2].toInt() and 0xF0) shr 4)
                            height = ((nal[2].toInt() and 0x0F) shl 8) or (nal[3].toInt() and 0xFF)
                            return
                        }
                    }
                } catch (_: Exception) {}
                i = start
            } else i++
        }
    }

    private fun nextStart(b: ByteArray, from: Int): Int {
        var j = from
        while (j + 2 < b.size) { if (b[j].toInt() == 0 && b[j + 1].toInt() == 0 && b[j + 2].toInt() == 1) return if (j > 0 && b[j - 1].toInt() == 0) j - 1 else j; j++ }
        return -1
    }

    /** Drops emulation-prevention bytes (00 00 03 → 00 00) after the NAL header. */
    private fun unescape(nal: ByteArray, headerLen: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (k in headerLen until nal.size) {
            val v = nal[k].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal fun h264Sps(d: ByteArray) {
        val r = Bits(d)
        val profile = r.u(8); r.u(8); r.u(8); r.ue()
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) {
                for (k in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) {
                    var last = 8; var next = 8
                    for (j in 0 until if (k < 6) 16 else 64) { if (next != 0) next = (last + r.se() + 256) % 256; if (next != 0) last = next }
                }
            }
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.u(1)
        val wMbs = r.ue() + 1; val hMaps = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.u(1)
        r.u(1)
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (r.u(1) == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
        val subW = if (chroma == 1 || chroma == 2) 2 else 1
        val subH = if (chroma == 1) 2 else 1
        val cropX = if (chroma == 0) 1 else subW
        val cropY = (if (chroma == 0) 1 else subH) * (2 - frameMbsOnly)
        width = wMbs * 16 - cropX * (cl + cr)
        height = (2 - frameMbsOnly) * hMaps * 16 - cropY * (ct + cb)
        interlaced = frameMbsOnly == 0
    }

    internal fun hevcSps(d: ByteArray) {
        val r = Bits(d)
        r.u(4)
        val maxSub = r.u(3)
        r.u(1)
        r.skip(88); r.u(8)
        val profilePresent = BooleanArray(maxSub); val levelPresent = BooleanArray(maxSub)
        for (k in 0 until maxSub) { profilePresent[k] = r.u(1) == 1; levelPresent[k] = r.u(1) == 1 }
        if (maxSub > 0) for (k in maxSub until 8) r.u(2)
        for (k in 0 until maxSub) { if (profilePresent[k]) r.skip(88); if (levelPresent[k]) r.u(8) }
        r.ue()
        val chroma = r.ue()
        if (chroma == 3) r.u(1)
        var w = r.ue(); var h = r.ue()
        if (r.u(1) == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            w -= subW * (r.ue() + r.ue()); h -= subH * (r.ue() + r.ue())
        }
        width = w; height = h
    }

    // ------------------------------------------------------------------ audio headers

    private fun findAudioHeader() {
        val b = audio.toByteArray()
        for (i in 0 until b.size - 8) {
            val b0 = b[i].toInt() and 0xFF; val b1 = b[i + 1].toInt() and 0xFF
            when (audioType) {
                0x0F -> if (b0 == 0xFF && (b1 and 0xF6) == 0xF0) {
                    val cfg = ((b[i + 2].toInt() and 0x01) shl 2) or ((b[i + 3].toInt() and 0xC0) shr 6)
                    audioChannels = when (cfg) { 0 -> 2; 7 -> 8; else -> cfg }; return
                }
                0x81, 0x87 -> if (b0 == 0x0B && b1 == 0x77) {
                    val bsid = (b[i + 5].toInt() and 0xF8) shr 3
                    val table = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
                    if (bsid > 10) {
                        val x = b[i + 4].toInt() and 0xFF
                        audioChannels = table[(x shr 1) and 7] + (x and 1)
                    } else {
                        val r = Bits(b.copyOfRange(i + 6, minOf(i + 10, b.size)))
                        val acmod = r.u(3)
                        if ((acmod and 1) != 0 && acmod != 1) r.u(2)
                        if ((acmod and 4) != 0) r.u(2)
                        if (acmod == 2) r.u(2)
                        audioChannels = table[acmod] + r.u(1)
                    }
                    return
                }
                0x03, 0x04 -> if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    audioChannels = if (((b[i + 3].toInt() and 0xC0) shr 6) == 3) 1 else 2; return
                }
                else -> { audioChannels = 2; return }
            }
        }
    }

    private class Bits(private val d: ByteArray) {
        private var pos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = pos / 8
                val bit = if (byte < d.size) (d[byte].toInt() shr (7 - pos % 8)) and 1 else throw IllegalStateException("eof")
                v = (v shl 1) or bit; pos++
            }
            return v
        }
        fun skip(n: Int) { pos += n }
        fun ue(): Int { var z = 0; while (u(1) == 0) { z++; if (z > 31) throw IllegalStateException("bad ue") }; return ((1 shl z) - 1) + if (z > 0) u(z) else 0 }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
    }
}
