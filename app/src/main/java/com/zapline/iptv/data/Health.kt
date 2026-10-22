package com.zapline.iptv.data

import android.content.Context
import com.zapline.iptv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What we know about one stream URL: whether it works, and its picture and sound. */
data class StreamInfo(
    val state: Int,
    val width: Int = 0,
    val height: Int = 0,
    val interlaced: Boolean = false,
    val fps: Float = 0f,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val bitrate: Int = 0,
    val checkedAt: Long = 0,
    /** True when this came from actually watching it (more trustworthy than a probe). */
    val watched: Boolean = false,
    val note: String? = null,
    /** Failed checks in a row (a channel is only called dead after two, minutes apart). */
    val failures: Int = 0,
    /** When the current run of failures started. */
    val firstFailedAt: Long = 0,
) {
    companion object {
        const val OK = 1
        const val DEAD = 2
        /** The provider refused because the account was busy: says nothing about the channel. */
        const val BUSY = 3
        /** Failed once: probably dead, but checked again before saying so. */
        const val SUSPECT = 4
    }

    val ok get() = state == OK
    val dead get() = state == DEAD
    val suspect get() = state == SUSPECT

    /** "4K", "1080p", "720p", "576i"… or null if unknown. */
    val resolution: String? get() {
        val h = maxOf(height, if (width >= 3200) 2160 else 0)
        if (h <= 0) return null
        val p = if (interlaced) "i" else "p"
        return when {
            h >= 2000 || width >= 3200 -> "4K"
            h >= 1400 -> "1440$p"
            h >= 1000 || width >= 1800 -> "1080$p"
            h >= 700 || width >= 1200 -> "720$p"
            h >= 540 -> "576$p"
            h >= 450 -> "480$p"
            else -> "${h}$p"
        }
    }

    val audio: String? get() = when {
        audioChannels >= 8 -> "7.1"
        audioChannels >= 6 -> "5.1"
        audioChannels == 2 -> "Stereo"
        audioChannels == 1 -> "Mono"
        else -> null
    }

    /** Higher is better: picture first, then sound, then bitrate. */
    val score: Long get() = if (!ok) -1 else
        (maxOf(height, 1) * (if (interlaced) 8L else 10L)) * 1_000_000L + audioChannels * 10_000L + (bitrate / 100_000).coerceAtMost(9_999)

    fun toJson(): JSONObject = JSONObject().put("s", state).put("w", width).put("h", height).put("i", interlaced).put("f", fps.toDouble())
        .put("vc", videoCodec ?: "").put("ac", audioCodec ?: "").put("ch", audioChannels).put("br", bitrate)
        .put("t", checkedAt).put("wt", watched).put("n", note ?: "").put("fl", failures).put("ff", firstFailedAt)

    fun summary(): String = listOfNotNull(
        resolution, videoCodec, audio?.let { a -> listOfNotNull(audioCodec, a).joinToString(" ") },
        bitrate.takeIf { it > 0 }?.let { "%.1f Mb/s".format(java.util.Locale.UK, it / 1_000_000.0) },
    ).joinToString(" · ")
}

/** Stream health, kept per URL on disk; updated by playback and by the background check. */
object Health {
    private val map = ConcurrentHashMap<String, StreamInfo>()
    /** Bumped on every change (throttled by readers). */
    val version = MutableStateFlow(0)
    @Volatile private var file: File? = null
    @Volatile private var loaded = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun load(ctx: Context) {
        if (loaded) return
        val f = File(ctx.filesDir, "health.jsonl").also { file = it }
        loaded = true
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val o = try { JSONObject(line) } catch (_: Exception) { return@forEachLine }
                val u = o.optString("u").ifEmpty { return@forEachLine }
                map[u] = StreamInfo(
                    o.optInt("s"), o.optInt("w"), o.optInt("h"), o.optBoolean("i"), o.optDouble("f", 0.0).toFloat(),
                    o.optString("vc").ifEmpty { null }, o.optString("ac").ifEmpty { null }, o.optInt("ch"), o.optInt("br"),
                    o.optLong("t"), o.optBoolean("wt"), o.optString("n").ifEmpty { null },
                    o.optInt("fl"), o.optLong("ff"),
                )
            }
        } catch (_: Exception) {}
        version.value++
    }

    operator fun get(url: String): StreamInfo? = map[url]

    /** Two failures at least this far apart before a channel is called dead (shortened in tests). */
    @Volatile var confirmGapMs = 2 * 60_000L
        internal set

    fun put(url: String, raw: StreamInfo) {
        val old = map[url]
        // A busy refusal never overwrites real knowledge.
        if (raw.state == StreamInfo.BUSY && old != null && old.state != StreamInfo.BUSY) return
        // A failure only counts as "dead" when it repeats, minutes apart, with no success in between.
        val info = if (raw.state == StreamInfo.DEAD || raw.state == StreamInfo.SUSPECT) {
            val prior = if (old != null && (old.dead || old.suspect)) old.failures else 0
            val first = if (prior > 0 && old != null && old.firstFailedAt > 0) old.firstFailedAt else raw.checkedAt
            val n = prior + 1
            val confirmed = n >= 2 && raw.checkedAt - first >= confirmGapMs
            // Keep what we knew about the picture, in case it comes back.
            (old?.takeIf { it.height > 0 } ?: raw).copy(
                state = if (confirmed) StreamInfo.DEAD else StreamInfo.SUSPECT, failures = n, firstFailedAt = first,
                checkedAt = raw.checkedAt, note = raw.note, watched = false,
            )
        } else raw
        // Keep picture details learned earlier if this result has none (e.g. HLS alive-only check).
        val merged = if (info.ok && info.height == 0 && old != null && old.height > 0)
            info.copy(width = old.width, height = old.height, interlaced = old.interlaced, videoCodec = info.videoCodec ?: old.videoCodec,
                audioCodec = info.audioCodec ?: old.audioCodec, audioChannels = if (info.audioChannels > 0) info.audioChannels else old.audioChannels)
        else info
        map[url] = merged
        version.value++
        scheduleSave()
    }

    /** Called by the player when a version actually plays. */
    fun markWatched(url: String, width: Int, height: Int, fps: Float, videoCodec: String?, audioCodec: String?, channels: Int, bitrate: Int) {
        val old = map[url]
        put(url, StreamInfo(
            StreamInfo.OK,
            width = if (width > 0) width else old?.width ?: 0, height = if (height > 0) height else old?.height ?: 0,
            interlaced = old?.interlaced ?: false, fps = fps,
            videoCodec = videoCodec ?: old?.videoCodec, audioCodec = audioCodec ?: old?.audioCodec,
            audioChannels = if (channels > 0) channels else old?.audioChannels ?: 0,
            bitrate = if (bitrate > 0) bitrate else old?.bitrate ?: 0,
            checkedAt = System.currentTimeMillis(), watched = true,
        ))
    }

    fun markFailed(url: String, note: String, busy: Boolean) =
        put(url, StreamInfo(if (busy) StreamInfo.BUSY else StreamInfo.DEAD, checkedAt = System.currentTimeMillis(), note = note))

    internal fun resetForTests() { generation++; scanJob?.cancel(); map.clear(); loaded = false; file = null; watching = false; scan.value = ScanState() }

    fun clear() { map.clear(); version.value++; scheduleSave() }

    private fun scheduleSave() {
        val f = file ?: return
        saveJob?.cancel()
        saveJob = io.launch {
            delay(2_000)
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.bufferedWriter().use { w -> for ((u, s) in map) { w.write(s.toJson().put("u", u).toString()); w.newLine() } }
                tmp.renameTo(f)
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ background check

    data class ScanState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val working: Int = 0,
        val dead: Int = 0,
        val current: String? = null,
        /** Why it's waiting, e.g. "Paused while you watch". */
        val paused: String? = null,
        val finishedAt: Long = 0,
    )

    val scan = MutableStateFlow(ScanState())
    /** Set by the player while it's on screen: checks never compete with what you're watching. */
    @Volatile var watching = false
        set(v) { field = v; if (v) StreamProbe.cancelInFlight() }   // the channel you open gets the connection
    private var scanJob: Job? = null

    /** How long a result stays fresh before the background check looks again. */
    const val FRESH_MS = 3 * 86_400_000L

    fun isStale(url: String, now: Long = System.currentTimeMillis()): Boolean {
        val s = map[url] ?: return true
        return when (s.state) {
            StreamInfo.BUSY, StreamInfo.SUSPECT -> true
            StreamInfo.DEAD -> now - s.checkedAt > 86_400_000L      // dead ones get another look daily
            else -> now - s.checkedAt > FRESH_MS
        }
    }

    @Volatile private var generation = 0

    /**
     * Checks [channels] one at a time (one provider connection at most), skipping fresh results unless [force].
     *
     * Pacing adapts to the provider: providers keep counting a connection for a few seconds after it closes,
     * so checking too fast gets refused (460 and friends). Each refusal doubles the gap and retries the same
     * channel; successes slowly speed it back up. Failures are retried at the end of the run, minutes later,
     * before anything is called dead. Pauses while the player is open.
     */
    fun startScan(channels: List<Pair<String, Channel>>, prefs: Prefs, force: Boolean = false) {
        if (scanJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val todo = channels.filter { force || isStale(it.second.url, now) }.distinctBy { it.second.url }
        if (todo.isEmpty()) { scan.value = ScanState(running = false, finishedAt = now); return }
        val old = scanJob
        val gen = ++generation
        fun publish(s: ScanState) { if (gen == generation) scan.value = s }
        scanJob = io.launch {
            old?.cancelAndJoin()          // never two checks (two connections) at once
            val base = if (XtreamAuth.singleConnection) 3_000L else 1_500L
            var gap = base
            var st = ScanState(running = true, total = todo.size)
            publish(st)
            val queue = ArrayDeque(todo)
            val retry = ArrayList<Pair<String, Channel>>()
            var retried = false
            var refusals = 0
            var lastProbeEnd = 0L
            var lastAccountCheck = 0L
            while (isActive) {
                if (queue.isEmpty()) {
                    if (retry.isEmpty() || retried) break
                    // Second look at the ones that failed, a few minutes after the first.
                    retried = true
                    queue.addAll(retry); retry.clear()
                    st = st.copy(total = st.total + queue.size)
                    val wait = confirmGapMs - (System.currentTimeMillis() - lastProbeEnd).coerceAtLeast(0)
                    if (wait > 0) { publish(st.copy(paused = "Rechecking ${queue.size} that failed, shortly…")); delay(wait) }
                }
                if (watching) { publish(st.copy(paused = "Paused while you watch")); delay(3_000); continue }
                // Never take a connection someone else is using: some providers drop the oldest viewer when
                // a new connection arrives at the limit. Ask the provider first (and every minute).
                if (XtreamAuth.creds(prefs) != null && System.currentTimeMillis() - lastAccountCheck > 60_000) {
                    val acct = XtreamAuth.login(prefs, force = true)
                    lastAccountCheck = System.currentTimeMillis()
                    if (acct.ok && acct.full) {
                        val ours = System.currentTimeMillis() - lastProbeEnd < 90_000
                        publish(st.copy(paused = if (ours) "Letting your provider close the last check…" else "Waiting: your account is in use on another device"))
                        delay(if (ours) 15_000 else 60_000)
                        lastAccountCheck = 0L    // look again before probing
                        continue
                    }
                }
                val (name, ch) = queue.first()
                st = st.copy(current = name, paused = null); publish(st)
                val r = StreamProbe.probe(ch, prefs)
                lastProbeEnd = System.currentTimeMillis()
                if (!isActive || gen != generation) break
                if (r.note == StreamProbe.CANCELLED) continue          // stopped, or the player opened: try it again later
                if (r.state == StreamInfo.BUSY) {
                    refusals++
                    gap = (gap * 2).coerceAtMost(20_000)
                    if (refusals >= 8) {
                        publish(st.copy(running = false, current = null, paused = "Stopped: your provider keeps refusing new connections. Try again later."))
                        return@launch
                    }
                    publish(st.copy(paused = "Provider asked us to slow down, waiting ${gap / 1000}s…"))
                    delay(gap)
                    continue
                }
                refusals = 0
                gap = maxOf(base, (gap * 0.8).toLong())
                queue.removeFirst()
                put(ch.url, r)
                val stored = this@Health[ch.url]
                if (stored?.suspect == true && !retried) retry += name to ch
                st = st.copy(done = st.done + 1, working = st.working + if (stored?.ok == true) 1 else 0, dead = st.dead + if (stored?.dead == true) 1 else 0)
                publish(st)
                delay(gap)
            }
            publish(st.copy(running = false, current = null, paused = null, finishedAt = System.currentTimeMillis()))
        }
    }

    fun stopScan() {
        generation++
        scanJob?.cancel()
        StreamProbe.cancelInFlight()
        scan.value = scan.value.copy(running = false, current = null, paused = null)
    }

}
