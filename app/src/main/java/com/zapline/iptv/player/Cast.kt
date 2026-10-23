package com.zapline.iptv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.zapline.iptv.model.Channel
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

/** Uses Google's Default Media Receiver, so no receiver app has to be registered. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // Zapline shows its own casting notification from CastService (which also keeps the relay running with
        // the screen off), so the framework's one is turned off to avoid two.
        val media = CastMediaOptions.Builder().setNotificationOptions(null).build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast / Google TV casting with our own device picker (no AppCompat dialogs).
 * On phones without Google Play services [available] is false and casting is hidden.
 */
class Caster(ctx: Context, private val onConnected: (Boolean) -> Unit) {
    enum class State { IDLE, CONNECTING, LOADING, BUFFERING, PLAYING, PAUSED, ERROR }

    class Device(val id: String, val name: String, val description: String?, val connecting: Boolean, val selected: Boolean)

    private val app = ctx.applicationContext
    private val cast: CastContext? = try { CastContext.getSharedInstance(app) } catch (_: Throwable) { null }
    private val router: MediaRouter? = if (cast == null) null else try { MediaRouter.getInstance(app) } catch (_: Throwable) { null }
    private val selector: MediaRouteSelector? = try { cast?.mergedSelector } catch (_: Throwable) { null }
        ?: try { MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build() } catch (_: Throwable) { null }

    val available get() = cast != null && router != null && selector != null

    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var state by mutableStateOf(State.IDLE); private set
    var message by mutableStateOf<String?>(null); private set
    var scanning by mutableStateOf(false); private set
    /** Title on the TV right now. */
    var nowTitle by mutableStateOf<String?>(null); private set

    private var loadedUrl: String? = null
    private var client: RemoteMediaClient? = null

    // ------------------------------------------------------------------ discovery

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshDevices()
        override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshDevices()
    }

    fun startScan() {
        val r = router ?: return; val s = selector ?: return
        try {
            r.addCallback(s, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            scanning = true
            refreshDevices()
        } catch (_: Throwable) {}
    }

    fun stopScan() {
        try { router?.removeCallback(routerCallback) } catch (_: Throwable) {}
        scanning = false
    }

    private fun refreshDevices() {
        val r = router ?: return; val s = selector ?: return
        devices = try {
            r.routes.filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(s) }.map {
                Device(it.id, it.name, it.description, it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING, it.isSelected)
            }.sortedBy { it.name.lowercase() }
        } catch (_: Throwable) { emptyList() }
    }

    fun connect(d: Device) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == d.id } ?: return
        state = State.CONNECTING; message = null; deviceName = d.name
        try { r.selectRoute(route) } catch (e: Throwable) { state = State.ERROR; message = "Couldn't connect to ${d.name}" }
    }

    fun disconnect() {
        try { cast?.sessionManager?.endCurrentSession(true) } catch (_: Throwable) {}
        try { router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ session

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateFromRemote()
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = attached(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attached(s)
        override fun onSessionEnded(s: CastSession, error: Int) = detached(null)
        override fun onSessionStarting(s: CastSession) { state = State.CONNECTING; deviceName = s.castDevice?.friendlyName ?: deviceName }
        override fun onSessionStartFailed(s: CastSession, error: Int) = detached("Couldn't connect to the TV. Check it's on the same Wi-Fi.")
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) = detached(null)
        override fun onSessionSuspended(s: CastSession, reason: Int) { state = State.CONNECTING; message = "Reconnecting to the TV…" }
    }

    private fun attached(s: CastSession) {
        deviceName = s.castDevice?.friendlyName ?: "TV"
        client?.unregisterCallback(remoteCallback)
        client = s.remoteMediaClient?.also { it.registerCallback(remoteCallback) }
        if (state == State.CONNECTING || state == State.IDLE) state = State.LOADING
        message = null
        CastService.show(app, deviceName ?: "TV", nowTitle)
        onConnected(true)
    }

    private fun detached(msg: String?) {
        client?.unregisterCallback(remoteCallback)
        client = null
        val was = deviceName != null && state != State.IDLE
        deviceName = null; loadedUrl = null; nowTitle = null
        state = if (msg != null) State.ERROR else State.IDLE
        message = msg
        CastProxy.stopLive()
        CastService.stop(app)
        if (was || msg == null) onConnected(false)
    }

    val connected: Boolean get() = client != null

    fun start() {
        val sm = cast?.sessionManager ?: return
        kotlin.concurrent.thread(isDaemon = true) { try { CastProxy.port() } catch (_: Exception) {} }   // bind off the main thread
        try {
            sm.addSessionManagerListener(listener, CastSession::class.java)
            sm.currentCastSession?.takeIf { it.isConnected }?.let { attached(it) }
        } catch (_: Throwable) {}
    }

    fun stop() {
        try { cast?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java) } catch (_: Throwable) {}
        stopScan()
    }

    // ------------------------------------------------------------------ playback

    private var lastChannelUrl: String? = null

    /**
     * Sends [ch] to the TV. Live TS streams go through the phone ([CastProxy]) and become HLS;
     * HLS is relayed with CORS; films go direct unless the provider needs special headers.
     */
    fun load(ch: Channel, title: String, live: Boolean, positionMs: Long, force: Boolean = false) {
        val c = client ?: return
        if (!force && ch.url == lastChannelUrl && state != State.ERROR && state != State.IDLE) return
        lastChannelUrl = ch.url
        val needsHeaders = !ch.userAgent.isNullOrBlank() || !ch.referrer.isNullOrBlank() || !ch.origin.isNullOrBlank()
        val kind = CastProxy.kindOf(ch.url, live)
        val prepared = if (kind == CastProxy.Kind.FILE && !needsHeaders) null else try { CastProxy.prepare(ch, live) } catch (_: Exception) { null }
        if (prepared == null && kind == CastProxy.Kind.LIVE_TS) {
            CastProxy.stopLive()
            state = State.ERROR
            message = "Live channels are sent to the TV through this phone, so the phone needs to be on the same Wi-Fi as the TV."
            return
        }
        val url = prepared?.url ?: ch.url
        val meta = MediaMetadata(if (live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, if (live) "Live · Zapline" else "Zapline")
            ch.logo?.takeIf { it.startsWith("http") }?.let { try { addImage(WebImage(android.net.Uri.parse(it))) } catch (_: Exception) {} }
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (prepared != null && prepared.kind != CastProxy.Kind.FILE) "application/x-mpegURL" else mimeOf(ch.url))
            .setMetadata(meta)
            .apply {
                if (prepared?.kind == CastProxy.Kind.LIVE_TS) {
                    setHlsSegmentFormat(HlsSegmentFormat.TS)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
                }
            }
            .build()
        state = State.LOADING; message = null; nowTitle = title; loadedUrl = url
        deviceName?.let { CastService.show(app, it, title) }
        try {
            c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                .setCurrentTime(if (live) 0 else positionMs).build())
                .setResultCallback { r ->
                    if (!r.status.isSuccess && loadedUrl == url) {
                        state = State.ERROR
                        message = failureText(live, prepared?.kind)
                    }
                }
        } catch (e: Throwable) {
            state = State.ERROR; message = "Couldn't send this to the TV"
        }
    }

    private fun failureText(live: Boolean, kind: CastProxy.Kind?) = when {
        live -> "The TV couldn't play this channel. Try another version, or a different channel. " +
            "Chromecasts play H.264 video with AAC audio; some HD/4K channels use formats they can't decode."
        kind == null -> "The TV couldn't play this file. Chromecasts handle MP4 best; some MKV or AVI files won't play."
        else -> "The TV couldn't play this."
    }

    private fun updateFromRemote() {
        val s = client?.mediaStatus ?: return
        state = when (s.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> State.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> State.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING -> State.BUFFERING
            MediaStatus.PLAYER_STATE_LOADING -> State.LOADING
            MediaStatus.PLAYER_STATE_IDLE -> when (s.idleReason) {
                MediaStatus.IDLE_REASON_ERROR -> { message = failureText(s.mediaInfo?.streamType == MediaInfo.STREAM_TYPE_LIVE, CastProxy.Kind.HLS); State.ERROR }
                MediaStatus.IDLE_REASON_FINISHED -> State.IDLE
                else -> if (state == State.LOADING) State.LOADING else State.IDLE
            }
            else -> state
        }
        if (state == State.PLAYING) message = null
    }

    fun togglePause() {
        val c = client ?: return
        try { if (state == State.PLAYING || state == State.BUFFERING) c.pause() else c.play() } catch (_: Throwable) {}
    }

    fun seekBy(ms: Long) {
        val c = client ?: return
        try {
            c.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition((c.approximateStreamPosition + ms).coerceAtLeast(0)).build())
        } catch (_: Throwable) {}
    }

    fun setVolumeStep(up: Boolean) {
        val s = try { cast?.sessionManager?.currentCastSession } catch (_: Throwable) { null } ?: return
        try { s.volume = (s.volume + if (up) 0.05 else -0.05).coerceIn(0.0, 1.0) } catch (_: Throwable) {}
    }

    companion object {
        fun mimeOf(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m3u8") || path.contains("m3u8") -> "application/x-mpegURL"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".ts") -> "video/mp2t"
                else -> "video/mp4"
            }
        }
    }
}
