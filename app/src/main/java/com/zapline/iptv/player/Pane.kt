package com.zapline.iptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.zapline.iptv.data.DEFAULT_USER_AGENT
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.XtreamAuth
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.model.Channel
import java.util.Locale

/** One selectable audio, subtitle or video track. */
data class TrackOption(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

/**
 * One stream on screen: its own ExoPlayer, position in the play queue, version fallback,
 * resume points and track choices. The player screen runs one, multiview runs two.
 */
@OptIn(UnstableApi::class)
class Pane(private val ctx: Context, private val prefs: Prefs, val slot: Int) {

    var player by mutableStateOf<ExoPlayer?>(null); private set
    var index by mutableIntStateOf(0); private set
    var variantIndex by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    private val statusState = mutableStateOf<String?>(null)
    private val clearStatus = Runnable { statusState.value = null }
    /** Short on-screen message ("Subtitles on", "Locked to landscape"); disappears by itself after a few seconds. */
    var status: String?
        get() = statusState.value
        set(v) {
            statusState.value = v
            statusHandler.removeCallbacks(clearStatus)
            if (v != null) statusHandler.postDelayed(clearStatus, 3_500)
        }
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    /** Bumped whenever available tracks change, so the settings panel re-reads them. */
    var tracksTick by mutableIntStateOf(0); private set
    var muted by mutableStateOf(false); private set

    private var useHls = false
    private var triedAlternate = false
    var onEnded: (() -> Unit)? = null

    // ---- stall watchdog ----
    // IPTV streams often freeze without raising an error: endless buffering, or a picture that stops moving.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stallSince = 0L
    private var frozenSince = 0L
    private var lastPosition = -1L
    private var healthySince = 0L
    private var reconnects = 0
    private val watchdog = object : Runnable {
        override fun run() { checkHealth(); handler.postDelayed(this, 2_000) }
    }

    /** Whether data saver applies right now (always, or on a metered connection). */
    val saverActive: Boolean
        get() = when (prefs.dataSaver) {
            2 -> true
            1 -> try {
                (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
            } catch (_: Exception) { false }
            else -> false
        }

    val items: List<PlayEntry> get() = PlayQueue.items
    val entry: PlayEntry? get() = items.getOrNull(index)
    val channel: Channel? get() = entry?.variants?.getOrNull(variantIndex)
    val isVod: Boolean get() = entry?.key?.startsWith("vod|") == true

    val versionLabel: String?
        get() {
            val e = entry ?: return null
            if (e.variants.size < 2) return null
            return "${ChannelNames.versionLabel(e.variants[variantIndex].name)} ${variantIndex + 1}/${e.variants.size}"
        }

    // ---------------------------------------------------------------- lifecycle

    /** While casting, "playing" a channel sends it to the TV instead of starting the phone's player. */
    var castSink: ((PlayEntry, Channel, Long) -> Unit)? = null

    /** Stops the phone's own stream (frees the provider connection) without forgetting where we are. */
    fun stopLocal() {
        saveProgress()
        player?.stop()
        player?.clearMediaItems()
        playing = false; buffering = false; error = null; status = null
    }

    fun create() {
        if (player != null) return
        val p = ExoPlayer.Builder(ctx)
            .setAudioAttributes(AudioAttributes.DEFAULT, slot == 0)   // only the main pane takes audio focus
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(isoLang(prefs.audioLang))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesOn)
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .apply { if (saverActive) setMaxVideoSize(1280, 720).setMaxVideoBitrate(3_000_000) }
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    if (authStage > 0) status = null
                    // Another player identity got in: use it for this provider from now on.
                    if (uaIndex > 0) { val c = channel; val ua = currentUa; if (c != null && ua != null) XtreamAuth.rememberUserAgent(prefs, c.url, ua) }
                    authStage = 0
                    busyRetries = 0
                    recordHealth()
                    if (isVod) channel?.let { com.zapline.iptv.data.SeriesWatch.record(prefs, it.url, finished = false) }
                }
                if (state == Player.STATE_ENDED && isVod) {
                    saveProgress(finished = true)
                    channel?.let { com.zapline.iptv.data.SeriesWatch.record(prefs, it.url, finished = true) }
                    onEnded?.invoke()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onTracksChanged(tracks: Tracks) { tracksTick++ }
        })
        p.volume = if (muted) 0f else 1f
        player = p
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 2_000)
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        clearBusy()
        saveProgress()
        player?.release()
        player = null
        playing = false
    }

    // ---------------------------------------------------------------- navigation

    /** Starts entry [i] from its best version. */
    fun play(i: Int, recordRecent: Boolean = true) {
        if (items.isEmpty()) return
        clearBusy(); busyRetries = 0
        saveProgress()
        index = i.coerceIn(0, items.size - 1)
        status = null
        reconnects = 0
        val e = entry ?: return
        if (recordRecent && !isVod) try { prefs.addRecent(e.key) } catch (_: Exception) {}
        playVariant(startingVariant(e))
    }

    /** Best version normally; with data saver on, an HD or standard version before Full HD / 4K. */
    private fun startingVariant(e: PlayEntry): Int {
        if (e.variants.size < 2 || !saverActive) return 0
        // Known sizes first: the sharpest working version at 720p or below.
        e.variants.indices.filter { com.zapline.iptv.data.Health[e.variants[it].url]?.let { s -> s.ok && s.height in 1..760 } == true }
            .maxByOrNull { com.zapline.iptv.data.Health[e.variants[it].url]!!.score }?.let { return it }
        val saverOrder = mapOf(1 to 0, 2 to 1, 4 to 2, 0 to 3, 3 to 4)
        return e.variants.indices.minByOrNull { saverOrder[ChannelNames.qualityRank(e.variants[it].name) % 10] ?: 5 } ?: 0
    }

    private fun checkHealth() {
        val p = player ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!p.playWhenReady || error != null || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            stallSince = 0; frozenSince = 0; return
        }
        if (p.playbackState == Player.STATE_BUFFERING) {
            healthySince = 0
            if (stallSince == 0L) stallSince = now else if (now - stallSince > 12_000) reconnect()
            return
        }
        stallSince = 0
        if (p.playbackState == Player.STATE_READY && p.isPlaying) {
            val pos = p.currentPosition
            if (pos == lastPosition) {
                if (frozenSince == 0L) frozenSince = now else if (now - frozenSince > 8_000) reconnect()
            } else {
                frozenSince = 0; lastPosition = pos
                if (healthySince == 0L) healthySince = now else if (now - healthySince > 60_000) reconnects = 0
            }
        }
    }

    /** Reload the stream; after repeated stalls move to another version; after that, stop and say so. */
    private fun reconnect() {
        stallSince = 0; frozenSince = 0; lastPosition = -1
        reconnects++
        val n = entry?.variants?.size ?: 0
        when {
            reconnects > 3 -> {
                player?.stop()
                error = "This stream keeps freezing." + if (n > 1) " Tried other versions too." else ""
            }
            reconnects >= 2 && n > 1 -> {
                val next = (variantIndex + 1) % n
                status = "Stream froze, switching to ${ChannelNames.versionLabel(entry!!.variants[next].name)}"
                saveProgress(); playVariant(next)
            }
            else -> {
                status = "Stream froze, reconnecting"
                saveProgress(); channel?.let { start(it) }
            }
        }
    }

    /** Live channel with catch-up: replay the current programme from its start, as a seekable item after this one. */
    fun canStartOver(p: Programme?): Boolean {
        val e = entry ?: return false
        if (p == null || isVod) return false
        return com.zapline.iptv.data.Catchup.playEntry(e.key, e.name, e.variants, p, prefs.xtTimezone) != null
    }

    fun startOver(p: Programme): Boolean {
        val e = entry ?: return false
        val ce = com.zapline.iptv.data.Catchup.playEntry(e.key, e.name, e.variants, p, prefs.xtTimezone) ?: return false
        PlayQueue.items = items.toMutableList().apply { add(index + 1, ce) }
        play(index + 1, recordRecent = false)
        return true
    }

    fun step(delta: Int) {
        val n = items.size
        if (n == 0) return
        play(((index + delta) % n + n) % n)
    }

    fun retry() = play(index, recordRecent = false)

    fun cycleVersion() {
        val n = entry?.variants?.size ?: return
        if (n > 1) { status = null; saveProgress(); playVariant((variantIndex + 1) % n) }
    }

    private fun playVariant(v: Int) {
        val e = entry ?: return
        authStage = 0; uaIndex = 0; currentUa = null
        variantIndex = v.coerceIn(0, e.variants.size - 1)
        val ch = e.variants[variantIndex]
        triedAlternate = false
        useHls = looksLikeHls(ch.url)
        start(ch)
    }

    /** This version plays: note its picture and sound, so the best working version is tried first next time. */
    private fun recordHealth() {
        val p = player ?: return
        val url = channel?.url ?: return
        handler.postDelayed({
            if (player !== p || channel?.url != url) return@postDelayed
            val v = p.videoFormat; val a = p.audioFormat
            com.zapline.iptv.data.Health.markWatched(
                url, v?.width ?: 0, v?.height ?: 0, v?.frameRate?.takeIf { it > 0 } ?: 0f,
                codecLabel(v?.sampleMimeType), codecLabel(a?.sampleMimeType), a?.channelCount?.takeIf { it > 0 } ?: 0,
                listOfNotNull(v?.averageBitrate, v?.peakBitrate, v?.bitrate).firstOrNull { it > 0 } ?: 0,
            )
        }, 1_500)   // formats settle a moment after the first frame
    }

    private fun codecLabel(mime: String?): String? = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_AC3 -> "Dolby Digital"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"; MimeTypes.AUDIO_MPEG -> "MP3"
        else -> null
    }

    // ---- account busy (connection limit) ----
    /** Shown while waiting for the account to have a free connection; null otherwise. */
    var busyText by mutableStateOf<String?>(null); private set
    /** When the next automatic try happens (elapsedRealtime), 0 when not waiting. */
    var busyRetryAt by mutableStateOf(0L); private set
    private var busyRetries = 0
    private val busyRetry = Runnable {
        busyRetryAt = 0; busyText = null; authStage = 0
        channel?.let { start(it) }
    }

    /**
     * The account's connections are all in use. Often that's our own previous channel still closing on the
     * provider's side (after zapping), so wait a few seconds and try again by itself; give up after a minute.
     */
    private fun waitForFreeConnection(st: XtreamAuth.Status) {
        busyRetries++
        buffering = false
        status = null
        if (busyRetries > 6) {
            busyText = null; busyRetryAt = 0
            error = "Your account is in use on another device (${st.activeConnections} of ${st.maxConnections} " +
                "connection${if (st.maxConnections == 1) "" else "s"}). Stop watching there, then tap Retry."
            return
        }
        val wait = if (busyRetries == 1) 5_000L else 10_000L
        busyText = "Your account is in use (${st.activeConnections} of ${st.maxConnections} connection${if (st.maxConnections == 1) "" else "s"}). " +
            if (busyRetries == 1) "Usually that's the last channel still closing." else "Is something playing on another device?"
        busyRetryAt = android.os.SystemClock.elapsedRealtime() + wait
        handler.removeCallbacks(busyRetry)
        handler.postDelayed(busyRetry, wait)
    }

    fun retryBusyNow() { handler.removeCallbacks(busyRetry); busyRetry.run() }

    fun cancelBusy() {
        handler.removeCallbacks(busyRetry)
        busyRetryAt = 0; busyText = null
        error = "Stopped waiting for a free connection."
    }

    private fun clearBusy() { handler.removeCallbacks(busyRetry); busyRetryAt = 0; busyText = null }

    // ---- provider sign-in recovery ----
    /** 0 = normal, 1 = signed in again, 2+ = trying other player identities. */
    private var authStage = 0
    private var uaIndex = 0
    private var currentUa: String? = null

    private fun start(original: Channel) {
        error = null
        // Links downloaded under an older login are pointed at the current one.
        val fixed = XtreamAuth.withCurrentLogin(original.url, prefs)
        val ua = original.userAgent ?: currentUa ?: XtreamAuth.userAgentFor(prefs, fixed)
        currentUa = ua
        val ch = if (fixed != original.url || ua != original.userAgent) original.copy(url = fixed, userAgent = ua) else original
        val resumeAt = if (isVod) try { prefs.resumePosition(original.url) } catch (_: Exception) { 0L } else 0L
        castSink?.let { sink -> entry?.let { sink(it, ch, resumeAt) }; return }
        val p = player ?: return
        if (resumeAt > 0) {
            p.setMediaSource(buildSource(ctx, ch, useHls), resumeAt)
            status = "Resuming from ${formatTime(resumeAt)}"
        } else {
            p.setMediaSource(buildSource(ctx, ch, useHls))
        }
        p.prepare()
        p.playWhenReady = true
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            // Live: resuming after a pause jumps back to the live edge.
            if (!isVod && p.isCurrentMediaItemLive) p.seekToDefaultPosition()
            p.play()
        }
    }

    fun mute(m: Boolean) {
        muted = m
        player?.volume = if (m) 0f else 1f
    }

    // ---------------------------------------------------------------- errors

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        val n = entry?.variants?.size ?: 0
        val code = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val ch = channel
        if (code != null && ch != null && slot == 0 && XtreamAuth.isRefusal(code) && recoverRefusal(ch, code)) return
        when {
            e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> { p.seekToDefaultPosition(); p.prepare() }
            // Many IPTV links have no extension: if the container guess was wrong, try the other one once.
            !triedAlternate && e.errorCode in formatErrors -> {
                triedAlternate = true
                useHls = !useHls
                channel?.let { start(it) }
            }
            // This version is dead: quietly move to the next version of the same channel.
            variantIndex + 1 < n -> {
                channel?.let { com.zapline.iptv.data.Health.markFailed(it.url, describe(e), busy = code != null && XtreamAuth.isRefusal(code)) }
                val next = entry!!.variants[variantIndex + 1]
                status = "That version failed, trying ${ChannelNames.versionLabel(next.name)}"
                playVariant(variantIndex + 1)
            }
            else -> {
                status = null
                channel?.let { com.zapline.iptv.data.Health.markFailed(it.url, describe(e), busy = code != null && XtreamAuth.isRefusal(code)) }
                error = describe(e) + if (n > 1) "\nTried all $n versions." else ""
            }
        }
    }

    private val formatErrors = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )

    /**
     * The provider refused the stream. First sign in again (panels often forget a player after an update or
     * a while idle), then try other common player identities. Returns false when out of ideas.
     */
    private fun recoverRefusal(ch: Channel, code: Int): Boolean {
        val xtream = XtreamAuth.creds(prefs) != null && XtreamAuth.isXtreamStream(ch.url)
        // A user agent written into the playlist itself is the provider's own instruction; don't second-guess it.
        if (!xtream && ch.userAgent != null) return false
        val playingIndex = index; val playingVariant = variantIndex
        fun stillSame() = index == playingIndex && variantIndex == playingVariant && player != null
        when {
            authStage == 0 && xtream -> {
                authStage = 1
                status = "Signing in to your provider again…"
                buffering = true
                val ua = currentUa ?: DEFAULT_USER_AGENT
                Thread {
                    val st = XtreamAuth.login(prefs, ua, force = true)
                    handler.post {
                        if (!stillSame()) return@post
                        if (!st.ok) { buffering = false; status = null; error = st.message }
                        else if (st.full) waitForFreeConnection(st)
                        else channel?.let { start(it) }
                    }
                }.start()
                return true
            }
            // Signed in fine, still refused, and the provider says every connection is taken: wait for one.
            xtream && XtreamAuth.lastStatus?.full == true -> {
                waitForFreeConnection(XtreamAuth.lastStatus!!)
                return true
            }
            uaIndex + 1 < XtreamAuth.userAgents.size -> {
                authStage = maxOf(authStage, 2)
                uaIndex++
                currentUa = XtreamAuth.userAgents[uaIndex]
                status = "Provider refused (HTTP $code), trying another way in…"
                channel?.let { start(it.copy(userAgent = null)) }
                return true
            }
            else -> return false
        }
    }

    private fun describe(e: PlaybackException): String {
        val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (http?.responseCode) {
                in 401..403, 405, 406, 407, 429, in 451..470, 509, 511 ->
                    if (slot > 0) "Your provider refused a second stream. Many accounts only allow one connection at a time."
                    else "Your provider refused this stream (HTTP ${http?.responseCode}), even after signing in again. " +
                        "The account may be at its connection limit (another device watching?), or the provider has blocked this channel."
                404 -> "This channel isn't available on the server right now."
                else -> "The server returned an error (HTTP ${http?.responseCode ?: "?"})."
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Couldn't connect to the stream."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The stream timed out."
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Plain-http streams are blocked on this device."
            in 3000..3999 -> "This stream's format isn't recognised."
            in 4000..4999 -> "This phone can't decode this stream's video or audio."
            in 6000..6999 -> "This stream is DRM-protected."
            else -> "Playback failed (${e.errorCodeName})."
        }
    }

    // ---------------------------------------------------------------- progress

    fun saveProgress(finished: Boolean = false) {
        val p = player ?: return
        if (!isVod) return
        val url = channel?.url ?: return
        val dur = p.duration
        if (dur <= 0) return
        try { prefs.saveResume(url, if (finished) dur else p.currentPosition, dur) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- tracks

    private fun options(type: Int, label: (Format) -> String): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackOption>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(label(g.getTrackFormat(i)), g, i, g.isTrackSelected(i))
            }
        }
        // Several identical labels ("English", "English") get numbered so they can be told apart.
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map { o -> if ((counts[o.label] ?: 0) > 1) o.copy(label = "${o.label} ${(seen.merge(o.label, 1, Int::plus))}") else o }
    }

    fun audioTracks() = options(C.TRACK_TYPE_AUDIO) { f ->
        val lang = langName(f.language) ?: f.label ?: "Audio"
        val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        listOfNotNull(f.label?.takeIf { it != lang }?.let { "$lang, $it" } ?: lang, ch).joinToString(", ")
    }

    fun textTracks() = options(C.TRACK_TYPE_TEXT) { f ->
        val lang = langName(f.language) ?: f.label ?: "Subtitles"
        val cc = f.sampleMimeType == MimeTypes.APPLICATION_CEA608 || f.sampleMimeType == MimeTypes.APPLICATION_CEA708
        if (cc) "$lang (closed captions)" else lang
    }

    fun videoTracks() = options(C.TRACK_TYPE_VIDEO) { f ->
        val res = if (f.height > 0) "${f.height}p" else "Video"
        val br = if (f.bitrate > 0) "%.1f Mbps".format(f.bitrate / 1_000_000f) else null
        listOfNotNull(res, br).joinToString(", ")
    }

    val subtitlesOn: Boolean get() = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == false

    /** null turns subtitles off. */
    fun selectText(o: TrackOption?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (o == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        p.trackSelectionParameters = b.build()
        tracksTick++
    }

    /** Subtitles on with the best default track (quick CC button). */
    fun subtitlesDefault(on: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        tracksTick++
    }

    fun selectAudio(o: TrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
        tracksTick++
    }

    /** null = automatic quality. */
    fun selectVideo(o: TrackOption?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (o == null) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        else b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        p.trackSelectionParameters = b.build()
        tracksTick++
    }

    val videoAuto: Boolean
        get() = player?.trackSelectionParameters?.overrides?.keys?.none { it.type == C.TRACK_TYPE_VIDEO } != false

    fun setSpeed(s: Float) { player?.setPlaybackSpeed(s) }
    val speed: Float get() = player?.playbackParameters?.speed ?: 1f

    /** Technical details for the stream info panel. */
    fun streamInfo(): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val v = p.videoFormat; val a = p.audioFormat
        val out = ArrayList<Pair<String, String>>()
        if (v != null) {
            out += "Resolution" to "${v.width} × ${v.height}" + if (v.frameRate > 0) ", ${"%.0f".format(v.frameRate)} fps" else ""
            out += "Video" to codecName(v.sampleMimeType, v.codecs)
            if (v.bitrate > 0) out += "Bitrate" to "%.1f Mbps".format(v.bitrate / 1_000_000f)
        }
        if (a != null) {
            out += "Audio" to listOfNotNull(codecName(a.sampleMimeType, a.codecs),
                a.channelCount.takeIf { it > 0 }?.let { "$it ch" }, a.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }).joinToString(", ")
        }
        out += "Buffered" to "%.1f s".format(p.totalBufferedDuration / 1000f)
        if (saverActive) out += "Data saver" to "On, up to 720p"
        out += "Container" to if (useHls) "HLS" else "Direct stream"
        channel?.url?.let { u -> try { java.net.URI(u).host }catch (_: Exception) { null } }?.let { out += "Server" to it }
        return out
    }

    companion object {
        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun looksLikeHls(url: String): Boolean {
            val u = url.lowercase()
            val path = u.substringBefore('?')
            return path.endsWith(".m3u8") || path.endsWith(".m3u") || "m3u8" in u || "/hls/" in path
        }

        /** Language keys are ISO 639-1 except Ukrainian ("ua"). */
        fun isoLang(code: String): String? = when (code) { "" -> null; "ua" -> "uk"; else -> code }

        private fun langName(code: String?): String? {
            if (code.isNullOrBlank() || code == "und") return null
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.UK)
            return name.takeIf { it.isNotBlank() && !it.equals(code, true) }?.replaceFirstChar { it.uppercase() } ?: code.uppercase()
        }

        private fun codecName(mime: String?, codecs: String?): String = when {
            mime == null -> codecs ?: "Unknown"
            mime.contains("avc") -> "H.264"
            mime.contains("hevc") -> "H.265 / HEVC"
            mime.contains("av01") -> "AV1"
            mime.contains("vp9") -> "VP9"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("eac3") -> "Dolby Digital Plus"
            mime.contains("ac3") -> "Dolby Digital"
            mime.contains("mpeg") && mime.startsWith("audio") -> "MP2/MP3"
            mime.contains("mpeg2") -> "MPEG-2"
            else -> mime.substringAfter('/')
        }

        fun buildSource(ctx: Context, ch: Channel, hls: Boolean): MediaSource {
            val headers = HashMap<String, String>()
            ch.referrer?.let { headers["Referer"] = it }
            ch.origin?.let { headers["Origin"] = it }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(ch.userAgent ?: DEFAULT_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(headers)
            val ds = DefaultDataSource.Factory(ctx, http)
            return if (hls) {
                HlsMediaSource.Factory(ds)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.Builder().setUri(ch.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            } else {
                // Default TS reader includes CEA-608 captions, so broadcast closed captions can be switched on and off.
                val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )
                ProgressiveMediaSource.Factory(ds, extractors).createMediaSource(MediaItem.fromUri(ch.url))
            }
        }
    }
}
