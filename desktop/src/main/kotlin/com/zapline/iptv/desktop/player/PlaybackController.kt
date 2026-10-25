package com.zapline.iptv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zapline.iptv.data.DEFAULT_USER_AGENT
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.SeriesWatch
import com.zapline.iptv.data.XtreamAuth
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One picture on screen: a player plus what it's showing. The main view is tile 0; multiview adds more. */
class Tile(val id: Int, private val prefs: Prefs, private val scope: CoroutineScope) {
    val player = MpvPlayer("tile$id")

    var queue by mutableStateOf<List<PlayEntry>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var variant by mutableIntStateOf(0); private set
    /** Short message about what's happening ("That version failed, trying FHD"). */
    var status by mutableStateOf<String?>(null)
    /** Index before the last channel change, for "last channel". */
    private var previousIndex: Int? = null

    val entry: PlayEntry? get() = queue.getOrNull(index)
    val channel: Channel? get() = entry?.variants?.getOrNull(variant)
    val isVod: Boolean get() = entry?.key?.startsWith("vod|") == true

    /** Called when a VOD item ends and there's another one queued (episodes). */
    var onEndedWithNext: (() -> Unit)? = null

    private var signedInAgain = false
    private var liveRetries = 0
    private var progressJob: Job? = null

    init {
        player.onEnded = { err -> scope.launch { if (err != null) failed(err) else ended() } }
        player.onStarted = { scope.launch { started() } }
    }

    fun play(list: List<PlayEntry>, i: Int) {
        queue = list
        jump(i)
    }

    /** A different item from the same queue (channel up/down, a number, the list). */
    fun jump(i: Int) {
        if (queue.isEmpty()) return
        saveProgress()
        if (i != index) previousIndex = index
        index = ((i % queue.size) + queue.size) % queue.size
        val e = entry ?: return
        if (!isVod) try { prefs.addRecent(e.key) } catch (_: Exception) {}
        variant = startingVariant(e)
        signedInAgain = false; liveRetries = 0
        start()
    }

    fun step(delta: Int) = jump(index + delta)
    fun lastChannel() { previousIndex?.let { jump(it) } }

    fun nextVersion() {
        val n = entry?.variants?.size ?: return
        if (n > 1) { saveProgress(); variant = (variant + 1) % n; status = "Switching to ${ChannelNames.versionLabel(channel?.name ?: "")}"; start() }
    }

    fun pickVersion(i: Int) {
        val n = entry?.variants?.size ?: return
        if (i in 0 until n && i != variant) { saveProgress(); variant = i; status = "Switching to ${ChannelNames.versionLabel(channel?.name ?: "")}"; start() }
    }

    fun retry() { signedInAgain = false; liveRetries = 0; start() }

    fun stop() { saveProgress(); player.stop(); queue = emptyList() }

    /** Versions are already ordered best-first by the channel list; just take the first. */
    private fun startingVariant(e: PlayEntry) = 0

    private fun start() {
        val ch = channel ?: return
        val url = XtreamAuth.withCurrentLogin(ch.url, prefs)
        val ua = ch.userAgent ?: XtreamAuth.userAgentFor(prefs, url) ?: DEFAULT_USER_AGENT
        val headers = buildMap {
            ch.referrer?.let { put("Referer", it) }
            ch.origin?.let { put("Origin", it) }
        }
        val resume = if (isVod) prefs.resumePosition(ch.url) / 1000.0 else 0.0
        player.load(url, ua, headers, resume, entry?.name)
        if (resume > 1) status = "Resuming from ${formatTime(resume)}"
        progressJob?.cancel()
        if (isVod) progressJob = scope.launch { while (true) { delay(10_000); saveProgress() } }
    }

    private suspend fun started() {
        if (status?.startsWith("Resuming") != true) status = null
        liveRetries = 0
        if (isVod) channel?.let { SeriesWatch.record(prefs, it.url, finished = false) }
        // What this version really is (resolution, codecs, sound): feeds the 4K checks and "best version first".
        val ch = channel ?: return
        delay(2_500)
        if (channel?.url != ch.url) return
        val s = player.state
        if (s.height > 0) Health.markWatched(
            ch.url, s.width, s.height, s.fps.toFloat(), codecLabel(s.videoCodec), codecLabel(s.audioCodec),
            s.audioChannels, s.videoBitrate,
        )
    }

    private suspend fun failed(err: String) {
        val ch = channel ?: return
        // Xtream panels often refuse a player that hasn't signed in lately: sign in once, then try again.
        if (!signedInAgain && XtreamAuth.creds(prefs) != null && XtreamAuth.isXtreamStream(ch.url)) {
            signedInAgain = true
            status = "Signing in to your provider again…"
            val st = withContext(Dispatchers.IO) { XtreamAuth.login(prefs, force = true) }
            if (!st.ok) { status = st.message; return }
            if (st.full) { status = "Your account is in use (${st.activeConnections} of ${st.maxConnections} connections). Retrying shortly…"; delay(6_000) }
            start(); return
        }
        val n = entry?.variants?.size ?: 0
        Health.markFailed(ch.url, err, busy = false)
        if (variant + 1 < n) {
            variant++
            signedInAgain = false
            status = "That version failed, trying ${ChannelNames.versionLabel(channel?.name ?: "")}"
            start()
        } else {
            status = if (n > 1) "$err Tried all $n versions." else err
        }
    }

    private suspend fun ended() {
        if (isVod) {
            channel?.let { prefs.saveResume(it.url, 1, 1); SeriesWatch.record(prefs, it.url, finished = true) }
            if (index + 1 < queue.size) onEndedWithNext?.invoke()
        } else if (liveRetries < 5) {
            // A live channel shouldn't end: the stream dropped. Reconnect.
            liveRetries++
            status = "Stream dropped, reconnecting…"
            delay(1_000L * liveRetries)
            start()
        } else status = "The stream keeps dropping. Try another version or channel."
    }

    fun saveProgress() {
        if (!isVod) return
        val ch = channel ?: return
        val s = player.state
        if (s.duration > 0) prefs.saveResume(ch.url, (s.position * 1000).toLong(), (s.duration * 1000).toLong())
    }

    fun release() { saveProgress(); progressJob?.cancel(); player.destroy() }

    companion object {
        fun formatTime(sec: Double): String {
            val t = sec.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun codecLabel(c: String?): String? {
            val l = c?.lowercase() ?: return null
            return when {
                "hevc" in l || "265" in l -> "HEVC"; "264" in l || "avc" in l -> "H.264"; "av1" in l -> "AV1"; "mpeg2" in l -> "MPEG-2"
                "eac3" in l || "e-ac-3" in l -> "Dolby Digital+"; "ac3" in l || "ac-3" in l -> "Dolby Digital"; "aac" in l -> "AAC"
                "mp2" in l -> "MP2"; "mp3" in l -> "MP3"; "opus" in l -> "Opus"
                else -> c
            }
        }
    }
}

/**
 * The main picture plus any multiview tiles. Only the tile with audio focus makes sound; the
 * background channel check stays paused while anything plays (one provider connection each).
 */
class PlaybackController(val prefs: Prefs, private val scope: CoroutineScope) {
    val main = Tile(0, prefs, scope)
    /** Extra pictures in multiview (main is always tile 0). */
    val extra = mutableStateListOf<Tile>()
    var audioTile by mutableIntStateOf(0); private set
    private var lastId = 0

    val tiles: List<Tile> get() = listOf(main) + extra
    val playing: Boolean get() = tiles.any { it.entry != null }

    fun play(list: List<PlayEntry>, i: Int) {
        main.play(list, i)
        Health.watching = true
    }

    fun addTile(): Tile {
        val t = Tile(++lastId, prefs, scope)
        extra += t
        applyAudio()
        return t
    }

    fun removeTile(t: Tile) {
        t.release(); extra.remove(t)
        if (audioTile >= tiles.size) audioTile = 0
        applyAudio()
    }

    fun clearMultiview() { extra.toList().forEach { it.release() }; extra.clear(); audioTile = 0; applyAudio() }

    fun focusAudio(tileIndex: Int) { audioTile = tileIndex.coerceIn(0, tiles.size - 1); applyAudio() }

    fun applyAudio() { tiles.forEachIndexed { i, t -> t.player.setMuted(i != audioTile && tiles.size > 1) } }

    fun stopAll() { tiles.forEach { it.stop() }; Health.watching = false }

    fun releaseAll() { tiles.forEach { it.release() }; Health.watching = false }
}
