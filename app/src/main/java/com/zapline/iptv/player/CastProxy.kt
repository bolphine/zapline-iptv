package com.zapline.iptv.player

import com.zapline.iptv.data.DEFAULT_USER_AGENT
import com.zapline.iptv.model.Channel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A small web server on the phone that the Chromecast fetches streams from.
 *
 * Why: the Chromecast's built-in player can't play the raw MPEG-TS live streams IPTV providers send,
 * it refuses HLS from servers without CORS headers, and it can't send the user agent some providers insist on.
 * So the phone fetches the stream itself (one connection to the provider, as when watching on the phone) and:
 *  - live TS: cuts it into short HLS segments at keyframes ([TsSegmenter]) and serves a rolling playlist;
 *  - HLS: rewrites the playlists so every request goes through the phone, adding CORS and headers;
 *  - files: passes byte ranges through untouched.
 */
object CastProxy {
    data class Source(val url: String, val headers: Map<String, String>)

    private val sources = ConcurrentHashMap<String, Source>()
    private val segmenters = ConcurrentHashMap<String, TsSegmenter>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-proxy").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    /** The phone's address on the local network (Wi-Fi first), or null when there isn't one (e.g. mobile data only). */
    fun lanAddress(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        val ordered = all.sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("eth")) 0 else if (n.name.startsWith("rmnet")) 2 else 1 }
        ordered.asSequence()
            .filterNot { it.name.startsWith("rmnet") || it.name.startsWith("ccmni") || it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    @Synchronized
    fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        server = s
        thread(name = "cast-proxy-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    fun headersOf(ch: Channel): Map<String, String> = buildMap {
        put("User-Agent", ch.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
        ch.referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        ch.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
    }

    enum class Kind { LIVE_TS, HLS, FILE }

    data class Prepared(val url: String, val kind: Kind)

    fun kindOf(url: String, live: Boolean): Kind {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8") || path.endsWith("/hls") -> Kind.HLS
            live -> Kind.LIVE_TS
            else -> Kind.FILE
        }
    }

    /**
     * A URL the TV can fetch for [ch], served by this phone; null if the phone has no local address.
     * Starting a new live stream stops the previous one, so only one provider connection is ever open.
     */
    fun prepare(ch: Channel, live: Boolean, host: String? = lanAddress()): Prepared? {
        val ip = host ?: return null
        val kind = kindOf(ch.url, live)
        val src = Source(ch.url, headersOf(ch))
        val base = "http://$ip:${port()}"
        stopLive()
        return when (kind) {
            Kind.LIVE_TS -> {
                val id = register(src)
                segmenters[id] = TsSegmenter(src).also { it.start() }
                Prepared("$base/t/$id/live.m3u8", kind)
            }
            Kind.HLS -> Prepared("$base/p/${register(src)}/index.m3u8", kind)
            Kind.FILE -> Prepared("$base/p/${register(src)}/media.${extOf(ch.url, "mp4")}", kind)
        }
    }

    fun stopLive() {
        segmenters.values.forEach { it.stop() }
        segmenters.clear()
    }

    /**
     * The same URL always gets the same id, so a refreshed HLS playlist names segments the TV has
     * already played exactly as before (otherwise it can mistake them for new ones and replay them).
     */
    private fun register(s: Source): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest((s.url + "\u0000" + s.headers.toSortedMap()).toByteArray())
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        sources[id] = s
        if (sources.size > 4000) sources.keys.take(1000).forEach { sources.remove(it) }   // long HLS sessions
        return id
    }

    private fun extOf(url: String, def: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: def

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Req? {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r'); sb.clear()
                if (line.isEmpty()) break
                lines += line
                if (lines.size > 100) return null
            } else if (sb.length < 8192) sb.append(b.toChar())
        }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        return Req(first[0].uppercase(), first[1], headers)
    }

    private fun head(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Content-Type\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean = false, extra: Map<String, String> = emptyMap()) {
        head(out, code, if (code == 200) "OK" else "Error", mapOf("Content-Type" to type, "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-cache") + extra)
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream().buffered(64 * 1024)
                val req = readRequest(input) ?: return
                if (req.method == "OPTIONS") { send(out, 200, "text/plain", ByteArray(0)); return }
                val headOnly = req.method == "HEAD"
                val parts = req.path.substringBefore('?').trim('/').split('/')
                when (parts.getOrNull(0)) {
                    "t" -> serveLive(out, parts.getOrNull(1), parts.getOrNull(2), headOnly)
                    "p" -> serveProxied(out, sources[parts.getOrNull(1) ?: ""], req, headOnly)
                    else -> send(out, 404, "text/plain", "not found".toByteArray())
                }
            } catch (_: Exception) {
                // Client went away mid-stream: normal when the TV switches channel or seeks.
            }
        }
    }

    private fun serveLive(out: OutputStream, id: String?, file: String?, headOnly: Boolean) {
        val seg = segmenters[id ?: ""] ?: return send(out, 404, "text/plain", "stream ended".toByteArray())
        when {
            file == "live.m3u8" -> {
                val pl = seg.playlist(waitMs = 25_000)
                if (pl == null) send(out, 502, "text/plain", (seg.error ?: "no video yet").toByteArray())
                else send(out, 200, "application/vnd.apple.mpegurl", pl.toByteArray(), headOnly)
            }
            file != null && file.endsWith(".ts") -> {
                val data = seg.segment(file.removeSuffix(".ts").toLongOrNull() ?: -1)
                if (data == null) send(out, 404, "text/plain", "gone".toByteArray())
                else send(out, 200, "video/mp2t", data, headOnly)
            }
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun serveProxied(out: OutputStream, src: Source?, req: Req, headOnly: Boolean) {
        if (src == null) return send(out, 404, "text/plain", "unknown".toByteArray())
        val rb = Request.Builder().url(src.url)
        src.headers.forEach { (k, v) -> rb.header(k, v) }
        req.headers["range"]?.let { rb.header("Range", it) }
        http.newCall(rb.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val finalUrl = r.request.url.toString()
            val maybePlaylist = type.contains("mpegurl", true) || finalUrl.substringBefore('?').endsWith(".m3u8", true) ||
                src.url.substringBefore('?').endsWith(".m3u8", true)
            if (!r.isSuccessful) return send(out, r.code, "text/plain", "upstream ${r.code}".toByteArray())
            if (maybePlaylist) {
                val text = r.body?.string().orEmpty()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val origin = req.headers["host"]?.let { "http://$it" } ?: ""
                    val rewritten = rewritePlaylist(text, finalUrl, src.headers, origin)
                    return send(out, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(), headOnly)
                }
                return send(out, 200, type.ifEmpty { "application/octet-stream" }, text.toByteArray(), headOnly)
            }
            val h = LinkedHashMap<String, String>()
            h["Content-Type"] = type.ifEmpty { "application/octet-stream" }
            r.header("Content-Length")?.let { h["Content-Length"] = it }
            r.header("Content-Range")?.let { h["Content-Range"] = it }
            h["Accept-Ranges"] = r.header("Accept-Ranges") ?: "bytes"
            head(out, r.code, if (r.code == 206) "Partial Content" else "OK", h)
            if (!headOnly) r.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    /** Points every URI in an HLS playlist back at this proxy, resolved against the playlist's own address. */
    fun rewritePlaylist(text: String, baseUrl: String, headers: Map<String, String>, origin: String): String {
        val base = baseUrl.toHttpUrlOrNull()
        fun proxied(ref: String): String {
            val abs = base?.resolve(ref.trim())?.toString() ?: ref.trim()
            val id = register(Source(abs, headers))
            val name = abs.substringBefore('?').substringAfterLast('/').ifEmpty { "x" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return "$origin/p/$id/$name"
        }
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> if ("URI=\"" in t) uriAttr.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" } else line
                else -> proxied(t)
            }
        }
    }
}

/**
 * Turns a continuous MPEG-TS live stream into rolling HLS: a few seconds per segment, each starting
 * at a keyframe with the stream's PAT/PMT in front, durations taken from the video timestamps.
 */
class TsSegmenter(private val src: CastProxy.Source, private val connect: ((CastProxy.Source) -> InputStream)? = null) {
    class Segment(val seq: Long, val data: ByteArray, val duration: Double, val discontinuity: Boolean)

    private val segments = ArrayDeque<Segment>()
    private val lock = Object()
    @Volatile private var running = false
    @Volatile var lastAccess = System.currentTimeMillis(); private set
    @Volatile var error: String? = null; private set
    private var nextSeq = 0L
    private var droppedDiscontinuities = 0
    @Volatile private var input: InputStream? = null

    // parser state
    private var pat: ByteArray? = null
    private val pmt = HashMap<Int, ByteArray>()
    private var pmtPids = emptySet<Int>()
    private var videoPid = -1
    private var audioPid = -1
    private var cur = java.io.ByteArrayOutputStream(512 * 1024)
    private var curStartPts = -1L
    private var curStarted = false
    private var curDisc = false
    private var lastPts = -1L
    private var curWallStart = 0L

    companion object {
        const val TARGET = 2.0         // seconds; cut at the first keyframe after this
        const val FORCE = 6.0          // cut even without a keyframe flag
        /** Fixed: players expect #EXT-X-TARGETDURATION never to change during a stream. */
        const val TARGET_DURATION = 6
        const val KEEP = 40            // segments kept in memory (~80 s)
        const val WINDOW = 15          // segments listed in the playlist (~30 s), so a TV that fell behind can catch up
        const val START_SEGMENTS = 3   // segments ready before the first playlist, so the TV starts with a buffer
        const val IDLE_MS = 45_000L    // stop pulling from the provider once the TV stops asking
        private const val WRAP = 1L shl 33
    }

    fun start() {
        if (running) return
        running = true
        lastAccess = System.currentTimeMillis()
        thread(name = "ts-segmenter", isDaemon = true) { loop() }
    }

    @Volatile private var stopped = false

    fun stop() {
        stopped = true
        running = false
        try { input?.close() } catch (_: Exception) {}
    }

    private fun open(): InputStream {
        connect?.let { return it(src) }
        val rb = Request.Builder().url(src.url)
        src.headers.forEach { (k, v) -> rb.header(k, v) }
        val call = CastProxy.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build().newCall(rb.build())
        val r = call.execute()
        if (!r.isSuccessful) { r.close(); throw IOException("The provider answered ${r.code}") }
        return r.body!!.byteStream()
    }

    private fun loop() {
        var failures = 0
        while (running && System.currentTimeMillis() - lastAccess < IDLE_MS) {
            try {
                val ins = open().also { input = it }
                error = null
                val gotData = pump(BufferedInputStream(ins, 188 * 512))
                if (gotData) failures = 0
            } catch (e: Exception) {
                if (!running) break
                error = e.message ?: "Stream connection failed"
            } finally {
                try { input?.close() } catch (_: Exception) {}
            }
            if (!running) break
            // Reconnect. Providers usually resend the last few seconds they had buffered: those are skipped
            // (see onPacket) so the TV never sees the same moment twice, and the timeline carries on unbroken.
            finishSegment(lastPts, force = true)
            resumeAfterPts = lastPts
            skipStartedAt = 0L
            firstSeenPts = -1
            curStarted = false
            failures++
            Thread.sleep((failures * 1_000L).coerceAtMost(5_000))
        }
        running = false
    }

    /** Reads packets until the stream ends or we're stopped. Returns whether any video arrived. */
    internal fun pump(ins: InputStream): Boolean {
        val pkt = ByteArray(188)
        var any = false
        while (running) {
            // sync to 0x47
            val first = ins.read()
            if (first < 0) return any
            if (first != 0x47) continue
            pkt[0] = 0x47
            var off = 1
            while (off < 188) {
                val n = ins.read(pkt, off, 188 - off)
                if (n < 0) return any
                off += n
            }
            onPacket(pkt)
            any = true
            if (System.currentTimeMillis() - lastAccess > IDLE_MS) { running = false; return any }
        }
        return any
    }

    internal fun onPacket(p: ByteArray) {
        val pusi = (p[1].toInt() and 0x40) != 0
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val afc = (p[3].toInt() shr 4) and 3
        val adaptLen = if (afc and 2 != 0) (p[4].toInt() and 0xFF) else -1
        val payload = when (afc) { 1 -> 4; 3 -> 5 + adaptLen; else -> 188 }
        val rai = adaptLen > 0 && (p[5].toInt() and 0x40) != 0

        if (pid == 0 && pusi) { pat = p.copyOf(); parsePat(p, payload) }
        if (pid in pmtPids && pusi) { pmt[pid] = p.copyOf(); parsePmt(p, payload) }

        val clockPid = if (videoPid >= 0) videoPid else audioPid
        if (pid == clockPid && pusi && payload < 188) {
            val pts = readPts(p, payload)
            if (pts >= 0) {
                val isKey = rai || (videoPid < 0)
                if (!curStarted) {
                    val resume = resumeAfterPts
                    if (resume >= 0) {
                        val d = dur(resume, pts)
                        val now = System.currentTimeMillis()
                        if (skipStartedAt == 0L) skipStartedAt = now
                        when {
                            // Already sent to the TV: skip (but never for long, in case the provider restarted its clock).
                            d <= 0.02 && d > -30.0 && now - skipStartedAt < 8_000 -> return
                            // Carries straight on from where we were: no discontinuity, the TV doesn't notice.
                            d > 0.02 && d < 15.0 -> if (isKey || (firstSeenPts >= 0 && dur(firstSeenPts, pts) > 3.0)) {
                                resumeAfterPts = -1; beginSegment(pts)
                            }
                            // A different timeline: tell the TV so it resets cleanly.
                            else -> if (isKey || (firstSeenPts >= 0 && dur(firstSeenPts, pts) > 3.0)) {
                                resumeAfterPts = -1; curDisc = true; beginSegment(pts)
                            }
                        }
                        if (firstSeenPts < 0) firstSeenPts = pts
                    } else {
                        // Start the first segment at a keyframe (or give up waiting after a few seconds of timestamps).
                        if (isKey || (firstSeenPts >= 0 && dur(firstSeenPts, pts) > 3.0)) beginSegment(pts)
                        if (firstSeenPts < 0) firstSeenPts = pts
                    }
                    if (curStarted) lastPts = pts
                } else {
                    lastPts = pts
                    val d = dur(curStartPts, pts)
                    if ((isKey && d >= TARGET) || d >= FORCE || cur.size() > 12 * 1024 * 1024) {
                        finishSegment(pts, force = false)
                        beginSegment(pts)
                    }
                }
            }
        }
        if (curStarted && pid != 0 && pid !in pmtPids) cur.write(p, 0, 188)
    }

    private var firstSeenPts = -1L
    /** After a reconnect: video up to this PTS has already gone to the TV. */
    private var resumeAfterPts = -1L
    private var skipStartedAt = 0L

    private fun beginSegment(pts: Long) {
        cur = java.io.ByteArrayOutputStream(1024 * 1024)
        pat?.let { cur.write(it) }
        pmt.values.forEach { cur.write(it) }
        curStartPts = pts
        curStarted = true
        curWallStart = System.currentTimeMillis()
    }

    private fun finishSegment(endPts: Long, force: Boolean) {
        if (!curStarted || cur.size() <= 188 * 3) return
        var d = if (endPts >= 0 && curStartPts >= 0) dur(curStartPts, endPts) else 0.0
        if (d <= 0.05 || d > 30) d = ((System.currentTimeMillis() - curWallStart) / 1000.0).coerceIn(0.5, 10.0)
        val seg = Segment(nextSeq++, cur.toByteArray(), d, curDisc)
        curDisc = false
        synchronized(lock) {
            segments.addLast(seg)
            while (segments.size > KEEP) { if (segments.removeFirst().discontinuity) droppedDiscontinuities++ }
            lock.notifyAll()
        }
        if (force) curStarted = false
    }

    private fun dur(a: Long, b: Long): Double {
        var d = b - a
        if (d < -WRAP / 2) d += WRAP
        if (d > WRAP / 2) d -= WRAP
        return d / 90_000.0
    }

    private fun parsePat(p: ByteArray, payload: Int) {
        val s = payload + 1 + (p[payload].toInt() and 0xFF)   // pointer field
        if (s + 8 >= 188) return
        val len = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = minOf(s + 3 + len - 4, 188)
        val pids = HashSet<Int>()
        var i = s + 8
        while (i + 4 <= end) {
            val prog = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (prog != 0) pids += pid
            i += 4
        }
        if (pids.isNotEmpty()) pmtPids = pids
    }

    private fun parsePmt(p: ByteArray, payload: Int) {
        val s = payload + 1 + (p[payload].toInt() and 0xFF)
        if (s + 12 >= 188) return
        val len = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = minOf(s + 3 + len - 4, 188)
        val infoLen = ((p[s + 10].toInt() and 0x0F) shl 8) or (p[s + 11].toInt() and 0xFF)
        var i = s + 12 + infoLen
        var v = -1; var a = -1
        while (i + 5 <= end) {
            val type = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            val esLen = ((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)
            when (type) {
                0x01, 0x02, 0x10, 0x1B, 0x24 -> if (v < 0) v = pid
                0x03, 0x04, 0x0F, 0x11, 0x81, 0x87 -> if (a < 0) a = pid
            }
            i += 5 + esLen
        }
        if (v >= 0) videoPid = v
        if (a >= 0) audioPid = a
    }

    private fun readPts(p: ByteArray, o: Int): Long {
        if (o + 14 > 188) return -1
        if (p[o].toInt() != 0 || p[o + 1].toInt() != 0 || p[o + 2].toInt() != 1) return -1
        if ((p[o + 7].toInt() and 0x80) == 0) return -1
        val b = IntArray(5) { p[o + 9 + it].toInt() and 0xFF }
        return ((b[0].toLong() shr 1) and 0x07 shl 30) or
            (b[1].toLong() shl 22) or ((b[2].toLong() shr 1) shl 15) or
            (b[3].toLong() shl 7) or (b[4].toLong() shr 1)
    }

    /** Rolling live playlist; waits for the first couple of segments so the TV starts with some buffer. */
    fun playlist(waitMs: Long): String? {
        lastAccess = System.currentTimeMillis()
        // Paused on the TV for a while, so we stopped pulling from the provider: pick the stream up again.
        if (!running && !stopped) { resumeAfterPts = lastPts; skipStartedAt = 0L; firstSeenPts = -1; curStarted = false; start() }
        val deadline = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (segments.size < START_SEGMENTS) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0 || !running) return null
                lock.wait(minOf(left, 500))
            }
            val win = segments.toList().takeLast(WINDOW)
            val target = maxOf(TARGET_DURATION, kotlin.math.ceil(win.maxOf { it.duration }).toInt())
            val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
            sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(win.first().seq).append('\n')
            val discBefore = droppedDiscontinuities + segments.takeWhile { it.seq < win.first().seq }.count { it.discontinuity }
            if (discBefore > 0) sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discBefore).append('\n')
            for (s in win) {
                if (s.discontinuity && s !== win.first()) sb.append("#EXT-X-DISCONTINUITY\n")
                sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", s.duration)).append(",\n")
                sb.append(s.seq).append(".ts\n")
            }
            return sb.toString()
        }
    }

    fun segment(seq: Long): ByteArray? {
        lastAccess = System.currentTimeMillis()
        synchronized(lock) { return segments.firstOrNull { it.seq == seq }?.data }
    }

    internal fun segmentCount() = synchronized(lock) { segments.size }
    internal fun markRunning() { running = true }
}
