package com.zapline.iptv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Pointer
import java.io.File
import kotlin.concurrent.thread

/** One audio, video or subtitle track in the playing stream. */
data class Track(
    val id: Int,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val selected: Boolean,
    val channels: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val label: String get() = listOfNotNull(
        lang?.uppercase(), title, codec?.uppercase(),
        channels.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${it}ch" } },
        height.takeIf { it > 0 }?.let { "${it}p" },
    ).distinct().joinToString(" · ").ifEmpty { "Track $id" }
}

/** What the player is doing, refreshed a few times a second for the UI. */
data class PlayerState(
    val ready: Boolean = false,
    val idle: Boolean = true,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val bufferPercent: Int = 0,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val seekable: Boolean = false,
    /** Seconds of video buffered ahead. */
    val cacheSeconds: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    /** Hardware decoder in use ("d3d11va", "no"...). */
    val hwdec: String? = null,
    /** "pq" or "hlg" for HDR video. */
    val gamma: String? = null,
    val videoBitrate: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val error: String? = null,
) {
    val hdr: Boolean get() = gamma == "pq" || gamma == "hlg"
    val resolution: String? get() = when {
        height <= 0 -> null
        height >= 2000 || width >= 3200 -> "4K"
        height >= 1400 -> "1440p"
        height >= 1000 || width >= 1800 -> "1080p"
        height >= 700 -> "720p"
        else -> "${height}p"
    }
}

/**
 * One libmpv instance drawing into a native window (the video area). Several can run at once for multiview.
 * Keys pressed while the video has focus go to mpv and come back through [onMessage] (see [inputConf]).
 */
class MpvPlayer(private val label: String = "main") {
    private val lib = Mpv.lib
    @Volatile private var ctx: Pointer? = null
    /** Bumped on every init/destroy: background threads of an older instance stop as soon as it changes. */
    @Volatile private var generation = 0

    var state by mutableStateOf(PlayerState()); private set

    /** Playback finished (error = null) or failed (error = mpv's reason). */
    var onEnded: ((error: String?) -> Unit)? = null
    /** A key binding fired (e.g. listOf("zapline", "channel-up")). */
    var onMessage: ((List<String>) -> Unit)? = null
    /** First frame shown after a load or seek. */
    var onStarted: (() -> Unit)? = null

    val available get() = lib != null
    val initialized get() = ctx != null

    /**
     * Creates the player. [wid] is the native window to draw into (null = no video output, for tests).
     * Returns false if libmpv isn't available.
     */
    fun init(wid: Long?, inputConf: File? = null, options: Map<String, String> = emptyMap(), headless: Boolean = false): Boolean {
        val l = lib ?: return false
        if (ctx != null) return true
        val c = l.mpv_create() ?: return false
        fun opt(k: String, v: String) { l.mpv_set_option_string(c, k, v) }
        if (wid != null) opt("wid", wid.toString())
        if (headless) { opt("vo", "null"); opt("ao", "null") } else {
            opt("vo", "gpu-next")
            opt("hwdec", "auto-safe")                 // d3d11va on Windows: 4K HEVC/AV1 decoded on the graphics card
            opt("target-colorspace-hint", "yes")      // HDR passthrough when Windows HDR is on
        }
        opt("terminal", "no")
        opt("idle", "yes")
        opt("keep-open", "no")
        opt("osc", "no")
        opt("input-default-bindings", "no")
        opt("input-vo-keyboard", "yes")
        opt("cursor-autohide", "1000")
        inputConf?.let { opt("input-conf", it.absolutePath) }
        // Live IPTV: a decent buffer, and ffmpeg reconnects by itself when a stream drops.
        opt("cache", "yes")
        opt("demuxer-max-bytes", "400MiB")
        opt("demuxer-max-back-bytes", "150MiB")
        opt("demuxer-readahead-secs", "20")
        opt("cache-pause-wait", "2")
        opt("network-timeout", "20")
        opt("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5")
        opt("hr-seek", "yes")
        opt("osd-font", "Segoe UI")
        opt("osd-font-size", "34")
        opt("osd-border-size", "2")
        opt("osd-duration", "2500")
        opt("osd-margin-x", "36")
        opt("osd-margin-y", "28")
        opt("slang", "en,eng")
        opt("sub-auto", "no")
        for ((k, v) in options) opt(k, v)
        val r = l.mpv_initialize(c)
        if (r < 0) { l.mpv_terminate_destroy(c); state = state.copy(error = "Player failed to start: ${l.mpv_error_string(r)}"); return false }
        ctx = c
        val gen = ++generation
        val poller = thread(name = "mpv-poll-$label", isDaemon = true) { poll(l, c, gen) }
        // The event thread owns the instance: when this player is destroyed (or re-created) it waits for the
        // poller to stop, then tears mpv down, so nothing ever calls into a destroyed instance.
        thread(name = "mpv-events-$label", isDaemon = true) {
            events(l, c, gen)
            try { poller.join(2_000) } catch (_: InterruptedException) {}
            try { l.mpv_terminate_destroy(c) } catch (_: Throwable) {}
        }
        return true
    }

    fun destroy() {
        val l = lib ?: return
        val c = ctx ?: return
        generation++
        ctx = null
        try { l.mpv_wakeup(c) } catch (_: Throwable) {}
        state = PlayerState()
    }

    // ------------------------------------------------------------------ commands

    private fun cmd(vararg args: String): Int {
        val l = lib ?: return -1; val c = ctx ?: return -1
        return l.mpv_command(c, arrayOf(*args, null))
    }

    fun set(name: String, value: String): Int {
        val l = lib ?: return -1; val c = ctx ?: return -1
        return l.mpv_set_property_string(c, name, value)
    }

    fun get(name: String): String? {
        val l = lib ?: return null; val c = ctx ?: return null
        val p = l.mpv_get_property_string(c, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { l.mpv_free(p) }
    }

    /** Plays [url] with the provider's headers; [startSeconds] resumes a film. */
    fun load(url: String, userAgent: String, headers: Map<String, String> = emptyMap(), startSeconds: Double = 0.0, title: String? = null) {
        state = state.copy(error = null, ready = false, buffering = true, tracks = emptyList(), width = 0, height = 0)
        set("user-agent", userAgent)
        set("http-header-fields", headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" })
        set("force-media-title", title ?: "")
        set("start", if (startSeconds > 1) "$startSeconds" else "none")
        cmd("loadfile", url, "replace")
    }

    fun stop() { cmd("stop"); state = state.copy(idle = true, ready = false) }
    fun togglePause() { cmd("cycle", "pause") }
    fun setPaused(p: Boolean) { set("pause", if (p) "yes" else "no") }
    fun seekRelative(seconds: Double) { cmd("seek", seconds.toString(), "relative") }
    fun seekTo(seconds: Double) { cmd("seek", seconds.toString(), "absolute") }
    fun setVolume(v: Int) { set("volume", v.coerceIn(0, 130).toString()) }
    fun setMuted(m: Boolean) { set("mute", if (m) "yes" else "no") }
    fun selectAudio(id: Int) { set("aid", id.toString()) }
    fun selectSubtitle(id: Int?) { set("sid", id?.toString() ?: "no") }
    fun showText(text: String, ms: Int = 2500) { cmd("show-text", text, ms.toString()) }

    /**
     * How the picture fills an area of [areaW]×[areaH]. On a 32:9 screen a 16:9 picture leaves wide side bars:
     * "fit" keeps them, "fill219" zooms to 21:9 (crops some top and bottom), "fillwidth" fills the whole width
     * (crops the most), "stretch" fills it all by distorting.
     */
    fun setFit(mode: String, areaW: Int, areaH: Int) {
        val vw = state.width.takeIf { it > 0 } ?: 16; val vh = state.height.takeIf { it > 0 } ?: 9
        if (mode == "stretch") { set("keepaspect", "no"); set("video-zoom", "0"); return }
        set("keepaspect", "yes")
        val zoom = fitZoom(mode, areaW, areaH, vw, vh)
        set("video-zoom", "%.4f".format(java.util.Locale.US, zoom))
    }

    // ------------------------------------------------------------------ events and state

    private fun events(l: MpvLib, c: Pointer, gen: Int) {
        while (gen == generation) {
            val ev = try { l.mpv_wait_event(c, 0.5) } catch (_: Throwable) { break }
            if (gen != generation) break
            val id = ev.getInt(0)
            when (id) {
                MpvLib.EVENT_NONE -> {}
                MpvLib.EVENT_SHUTDOWN -> return
                MpvLib.EVENT_END_FILE -> {
                    val data = ev.getPointer(16)
                    val reason = data?.getInt(0) ?: -1
                    val err = data?.getInt(4) ?: 0
                    when (reason) {
                        MpvLib.END_ERROR -> {
                            val msg = friendlyError(l.mpv_error_string(err))
                            state = state.copy(error = msg, buffering = false, ready = false)
                            onEnded?.invoke(msg)
                        }
                        MpvLib.END_EOF -> onEnded?.invoke(null)
                    }
                }
                MpvLib.EVENT_FILE_LOADED -> { set("start", "none"); state = state.copy(error = null) }
                MpvLib.EVENT_PLAYBACK_RESTART -> { state = state.copy(ready = true, buffering = false); onStarted?.invoke() }
                MpvLib.EVENT_VIDEO_RECONFIG -> refreshTracks()
                MpvLib.EVENT_CLIENT_MESSAGE -> {
                    val data = ev.getPointer(16) ?: continue
                    val n = data.getInt(0)
                    val args = if (n > 0) data.getPointer(8)?.getStringArray(0, n, "UTF-8")?.toList().orEmpty() else emptyList()
                    if (args.isNotEmpty()) onMessage?.invoke(args)
                }
            }
        }
    }

    private fun friendlyError(e: String): String = when {
        e.contains("loading failed", true) || e.contains("unrecognized file format", true) -> "The stream couldn't be opened (offline, refused by the provider, or not a video)."
        e.contains("audio output", true) -> "No sound device could be opened."
        e.contains("video output", true) -> "The video output couldn't start."
        else -> e.replaceFirstChar(Char::uppercase)
    }

    private var tracksDirty = true

    private fun refreshTracks() { tracksDirty = true }

    private fun readTracks(): List<Track> {
        val n = get("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            val p = "track-list/$i/"
            val id = get(p + "id")?.toIntOrNull() ?: return@mapNotNull null
            Track(
                id = id, type = get(p + "type") ?: return@mapNotNull null,
                title = get(p + "title")?.takeIf { it.isNotBlank() }, lang = get(p + "lang")?.takeIf { it.isNotBlank() },
                codec = get(p + "codec"), selected = get(p + "selected") == "yes",
                channels = get(p + "demux-channel-count")?.toIntOrNull() ?: 0,
                width = get(p + "demux-w")?.toIntOrNull() ?: 0, height = get(p + "demux-h")?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun poll(l: MpvLib, c: Pointer, gen: Int) {
        var tick = 0
        while (gen == generation) {
            try {
                val idle = get("idle-active") == "yes"
                val tracks = if (tracksDirty || tick % 8 == 0) readTracks().also { tracksDirty = false } else state.tracks
                state = state.copy(
                    idle = idle,
                    paused = get("pause") == "yes",
                    buffering = !idle && (get("paused-for-cache") == "yes" || (get("core-idle") == "yes" && get("pause") != "yes" && !state.ready)),
                    bufferPercent = get("cache-buffering-state")?.toIntOrNull() ?: 0,
                    position = get("time-pos")?.toDoubleOrNull() ?: 0.0,
                    duration = get("duration")?.toDoubleOrNull() ?: 0.0,
                    seekable = get("seekable") == "yes",
                    cacheSeconds = get("demuxer-cache-duration")?.toDoubleOrNull() ?: 0.0,
                    width = get("video-params/w")?.toIntOrNull() ?: 0,
                    height = get("video-params/h")?.toIntOrNull() ?: 0,
                    fps = get("estimated-vf-fps")?.toDoubleOrNull() ?: get("container-fps")?.toDoubleOrNull() ?: 0.0,
                    videoCodec = get("video-codec")?.substringBefore(" ")?.takeIf { it.isNotBlank() },
                    audioCodec = get("audio-codec-name")?.takeIf { it.isNotBlank() },
                    audioChannels = get("audio-params/channel-count")?.toIntOrNull() ?: 0,
                    hwdec = get("hwdec-current"),
                    gamma = get("video-params/gamma"),
                    videoBitrate = get("video-bitrate")?.toDoubleOrNull()?.toInt() ?: 0,
                    volume = get("volume")?.toDoubleOrNull()?.toInt() ?: 100,
                    muted = get("mute") == "yes",
                    tracks = tracks,
                )
            } catch (_: Throwable) {}
            tick++
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
    }

    companion object {
        /**
         * mpv's video-zoom (log2 of the scale) that makes a [vw]×[vh] picture, fitted into [areaW]×[areaH],
         * fill the given aspect: "fill219" = 21:9 (never wider than the area), "fillwidth" = the whole width.
         */
        fun fitZoom(mode: String, areaW: Int, areaH: Int, vw: Int, vh: Int): Double {
            if (areaW <= 0 || areaH <= 0 || vw <= 0 || vh <= 0) return 0.0
            val va = vw.toDouble() / vh; val aa = areaW.toDouble() / areaH
            if (va >= aa) return 0.0                                  // already fills the width
            val fittedW = areaH * va
            val wantW = when (mode) { "fill219" -> minOf(areaW.toDouble(), areaH * 21.0 / 9.0); "fillwidth" -> areaW.toDouble(); else -> fittedW }
            return kotlin.math.ln(maxOf(1.0, wantW / fittedW)) / kotlin.math.ln(2.0)
        }

        /**
         * Key bindings while the video has focus. Everything app-level is sent back to Zapline as
         * "script-message zapline <action>"; playback basics are handled by mpv directly.
         */
        val inputConf: File by lazy {
            val f = File.createTempFile("zapline-input", ".conf")
            f.deleteOnExit()
            f.writeText(
                """
                SPACE cycle pause
                MBTN_LEFT_DBL script-message zapline fullscreen
                MBTN_RIGHT script-message zapline menu
                f script-message zapline fullscreen
                ESC script-message zapline escape
                UP script-message zapline channel-up
                DOWN script-message zapline channel-down
                PGUP script-message zapline channel-up
                PGDWN script-message zapline channel-down
                LEFT seek -10
                RIGHT seek 10
                Shift+LEFT seek -60
                Shift+RIGHT seek 60
                WHEEL_UP add volume 5
                WHEEL_DOWN add volume -5
                m cycle mute
                c script-message zapline subtitles
                a script-message zapline audio
                g script-message zapline guide
                i script-message zapline info
                l script-message zapline list
                t script-message zapline theatre
                v script-message zapline multiview
                z script-message zapline fit
                BS script-message zapline last-channel
                """.trimIndent() + "\n" + (0..9).joinToString("\n") { "$it script-message zapline digit $it" } + "\n"
            )
            f
        }
    }
}
