package com.zapline.iptv.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.blur
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Sports
import com.zapline.iptv.ui.ChannelLogo
import com.zapline.iptv.ui.Palette
import com.zapline.iptv.ui.ProgressLine
import com.zapline.iptv.ui.Haptics
import com.zapline.iptv.ui.SearchField
import com.zapline.iptv.ui.hm
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Scrim = Color(0xE6100E18)
private val OnVideo = Color.White
private val OnVideoMuted = Color(0xCCFFFFFF)

// ================================================================== root

@Composable
fun PlayerRoot(host: PlayerHost, actions: PlayerActions) {
    var pickSlot by remember { mutableIntStateOf(1) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        // While a panel is open, the screen underneath ignores touches, so a tap meant for the panel
        // can never also press whatever button happens to sit beneath it.
        Box(Modifier.fillMaxSize().blockTouches(host.panel != Panel.NONE)) {
            when {
                host.pip -> VideoSurface(host.main, host, Modifier.fillMaxSize())
                host.surf && host.main.isVod -> VerticalVodView(host, actions)
                host.surf -> SurfMode(host, actions)
                host.multi -> MultiView(host, portrait) { slot -> pickSlot = slot; host.panel = Panel.PICK_SECOND }
                portrait -> PortraitPlayer(host, actions)
                else -> FullscreenPlayer(host, actions)
            }
        }
        if (!host.pip) UpNextCard(host)
        if (!host.pip) CastingOverlay(host, actions)
        if (!host.pip) PanelHost(host, actions, portrait, pickSlot)
    }
}

/** Swallows every touch before children see it (initial pass) while [on]. */
private fun Modifier.blockTouches(on: Boolean): Modifier = if (!on) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

// ================================================================== video

/** The picture itself: Media3's PlayerView with its own controls off (ours are drawn in Compose). */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    pane: Pane,
    host: PlayerHost,
    modifier: Modifier,
    resizeOverride: Int? = null,
    showErrors: Boolean = true,
    /** TextureView-backed, so the picture can be sampled (ambient backdrop). */
    texture: Boolean = false,
    /** Applied to the picture only (zoom/pan), not the overlays. */
    videoModifier: Modifier = Modifier,
    onView: ((PlayerView) -> Unit)? = null,
    /** Fill the space around the picture with its own blurred colours. */
    ambient: Boolean = false,
) {
    val resize = when (resizeOverride ?: host.resizeMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (ambient) AmbientBackdrop(playerView, pane)
        AndroidView(
            factory = { ctx ->
                val view = if (texture) android.view.LayoutInflater.from(ctx).inflate(com.zapline.iptv.R.layout.player_texture, null) as PlayerView
                else PlayerView(ctx)
                view.apply {
                    playerView = this
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    onView?.invoke(this)
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    subtitleView?.setStyle(
                        CaptionStyleCompat(android.graphics.Color.WHITE, 0x99000000.toInt(), android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null)
                    )
                }
            },
            update = { v ->
                v.player = pane.player
                v.resizeMode = resize
                v.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * host.subtitleScale)
            },
            modifier = Modifier.fillMaxSize().then(videoModifier),
        )
        if (pane.buffering && pane.error == null) {
            CircularProgressIndicator(Modifier.size(44.dp), color = Palette.Tally, strokeWidth = 3.dp, trackColor = Color(0x33FFFFFF))
        }
        if (showErrors) pane.error?.let { err ->
            Column(
                Modifier.padding(20.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Scrim).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = OnVideo, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Retry", accent = true) { pane.retry() }
                    if (pane.items.size > 1 && !pane.isVod) Pill("Next channel") { pane.step(1) }
                }
            }
        }
        if (showErrors) pane.busyText?.let { text ->
            val left by produceState(0L, pane.busyRetryAt) {
                while (pane.busyRetryAt > 0) { value = ((pane.busyRetryAt - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0); delay(250) }
            }
            Column(
                Modifier.padding(20.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Scrim).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Palette.Tally, strokeWidth = 2.dp)
                Text(text, Modifier.padding(top = 12.dp), color = OnVideo, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Text("Trying again in ${left}s", Modifier.padding(top = 6.dp), color = OnVideoMuted, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Try now", accent = true) { pane.retryBusyNow() }
                    Pill("Stop") { pane.cancelBusy() }
                }
            }
        }
        pane.status?.takeIf { pane.error == null }?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp).clip(RoundedCornerShape(12.dp))
                .background(Scrim).padding(horizontal = 14.dp, vertical = 8.dp), color = OnVideo, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Ambilight-style glow behind the picture: the frame reduced to a coarse grid of average colours,
 * stretched so each edge of the screen takes the colour of the nearest edge of the picture.
 * New samples arrive twice a second and the colours glide towards them, so it never flickers or dips to black.
 */
@Composable
private fun AmbientBackdrop(view: PlayerView?, pane: Pane) {
    val target = remember { FloatArray(Ambient.COLS * Ambient.ROWS * 3) }
    val current = remember { FloatArray(Ambient.COLS * Ambient.ROWS * 3) }
    var hasColour by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(view, pane.player) {
        while (true) {
            if (view != null && pane.player != null) {
                Ambient.sample(view.videoSurfaceView) { raw ->
                    raw?.let { Ambient.grid(it) }?.let { g ->
                        g.copyInto(target)
                        if (!hasColour) { g.copyInto(current); hasColour = true }
                    }
                }
            }
            delay(if (hasColour) 500 else 250)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            if (!hasColour) continue
            Ambient.ease(current, target, 0.08f)          // ~0.75 s to settle on a new scene
            frame = Ambient.toBitmap(current).asImageBitmap()
        }
    }
    val img = frame ?: return
    val boost = remember {
        androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(1.35f) }.also { m ->
            m.timesAssign(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, 6f,
                0f, 1.2f, 0f, 0f, 6f,
                0f, 0f, 1.2f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Image(
            img, null, contentScale = ContentScale.FillBounds,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,    // bilinear: smooth gradients between cells
            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(boost),
            modifier = Modifier.fillMaxSize().blur(48.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle),
        )
        Box(Modifier.fillMaxSize().background(Color(0x38000000)))
    }
}

@Composable
private fun Pill(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (accent) Palette.Tally else Color(0x33FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (accent) Palette.OnTally else OnVideo, style = MaterialTheme.typography.labelLarge,
    )
}

/** Wall clock that ticks while the player is visible. */
@Composable
private fun rememberNow(periodMs: Long = 15_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

/** Playback position, polled while [active]. */
@Composable
private fun rememberPosition(pane: Pane, active: Boolean): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, pane.player) {
        while (active) {
            pane.player?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }
            delay(500)
        }
    }
    return pos to dur
}

// ================================================================== gestures

/**
 * Touch layer over the picture:
 * tap shows/hides controls, double-tap left/right skips 10 s on films,
 * vertical swipe changes channel in the middle, brightness on the left edge, volume on the right.
 */
@Composable
private fun GestureLayer(host: PlayerHost, actions: PlayerActions, pane: Pane, edgeGestures: Boolean, onTouch: () -> Unit) {
    val density = LocalDensity.current
    val zapThreshold = with(density) { 70.dp.toPx() }
    val locked = host.locked
    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked, pane.isVod) {
                detectTapGestures(
                    onTap = { host.controls = !host.controls; onTouch() },
                    onDoubleTap = { off ->
                        if (locked) return@detectTapGestures
                        val p = pane.player ?: return@detectTapGestures
                        if (pane.isVod) {
                            if (off.x < size.width / 2) p.seekBack() else p.seekForward()
                            host.main.status = if (off.x < size.width / 2) "Back 10 seconds" else "Forward 10 seconds"
                        } else pane.togglePlay()
                    },
                )
            }
            .pointerInput(locked, edgeGestures, pane.items.size) {
                if (locked) return@pointerInput
                var mode = 0; var start = 0f; var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { off ->
                        total = 0f
                        mode = when {
                            !edgeGestures -> 0
                            off.x < size.width * 0.28f -> 1
                            off.x > size.width * 0.72f -> 2
                            else -> 0
                        }
                        start = when (mode) { 1 -> actions.brightness(); 2 -> actions.volume(); else -> 0f }
                    },
                    onVerticalDrag = { _, d ->
                        total += d
                        if (mode != 0) {
                            val v = (start - total / (size.height * 0.75f)).coerceIn(0f, 1f)
                            if (mode == 1) actions.setBrightness(v) else actions.setVolume(v)
                            host.gesture = (if (mode == 1) "Brightness" else "Volume") to v
                        }
                    },
                    onDragEnd = {
                        if (mode == 0 && !pane.isVod && pane.items.size > 1 && abs(total) > zapThreshold) {
                            pane.step(if (total < 0) 1 else -1)
                        }
                        host.gesture = null
                    },
                    onDragCancel = { host.gesture = null },
                )
            },
    )
}

@Composable
private fun GestureIndicator(host: PlayerHost, modifier: Modifier) {
    val g = host.gesture ?: return
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(Scrim).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (g.first == "Brightness") Icons.Default.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp, g.first, tint = OnVideo)
        Box(Modifier.padding(start = 12.dp).width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(g.second).fillMaxHeight().background(Palette.Tally))
        }
        Text("${(g.second * 100).toInt()}%", Modifier.padding(start = 12.dp).width(40.dp), color = OnVideo, style = MaterialTheme.typography.labelMedium)
    }
}

// ================================================================== landscape / full screen

@Composable
private fun FullscreenPlayer(host: PlayerHost, actions: PlayerActions) {
    val pane = host.main
    val now = rememberNow()
    var touch by remember { mutableIntStateOf(0) }
    val (cur, next) = host.nowNext(pane, now)

    // Hide controls after a few seconds of no touches while something plays.
    LaunchedEffect(host.controls, touch, pane.playing, host.panel) {
        if (host.controls && pane.playing && host.panel == Panel.NONE) { delay(4_500); host.controls = false }
    }
    AutoIdentifyMatch(host, actions, cur)

    Box(Modifier.fillMaxSize()) {
        VideoSurface(pane, host, Modifier.fillMaxSize(), ambient = host.ambient)
        GestureLayer(host, actions, pane, edgeGestures = host.prefs.gestures) { touch++ }

        AnimatedVisibility(host.controls && !host.locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB0000000), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xC0000000))))
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    TopBar(host, actions, pane, cur) { touch++ }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CenterControls(pane) { touch++ } }
                    BottomBar(host, actions, pane, cur, now, showModes = true) { touch++ }
                }
                // Lock on the left edge, where a thumb rests in landscape.
                IconButton(
                    onClick = { host.locked = true; host.controls = false },
                    modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp)
                        .clip(CircleShape).background(Color(0x55000000)),
                ) { Icon(Icons.Default.Lock, "Lock screen", tint = OnVideo) }
            }
        }
        if (host.locked && host.controls) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { host.locked = false }, modifier = Modifier.size(64.dp).clip(CircleShape).background(Scrim)) {
                    Icon(Icons.Default.LockOpen, "Unlock screen", tint = Palette.Tally, modifier = Modifier.size(30.dp))
                }
                Text("Screen locked. Tap to unlock.", Modifier.padding(top = 10.dp), color = OnVideoMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        ChannelBanner(host, pane, now, Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 18.dp, bottom = 22.dp),
            hidden = host.controls || host.panel != Panel.NONE)
        GestureIndicator(host, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 24.dp))
    }
}

@Composable
private fun TopBar(host: PlayerHost, actions: PlayerActions, pane: Pane, cur: Programme?, onTouch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions::exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OnVideo) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(pane.entry?.name ?: "", color = OnVideo, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                cur != null -> "${cur.title}, until ${hm(cur.end)}"
                pane.isVod -> pane.channel?.group ?: ""
                else -> pane.channel?.group ?: ""
            }
            Text(sub, color = OnVideoMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ScorePill(host, Modifier.padding(end = 4.dp))
        TopActions(host, actions, pane, onTouch)
    }
}

/** A programme titled like a fixture ("Arsenal v Chelsea") starts match tracking without being asked. */
@Composable
private fun AutoIdentifyMatch(host: PlayerHost, actions: PlayerActions, cur: Programme?) {
    LaunchedEffect(cur?.title) {
        if (cur != null && Sports.fixture(cur.title) != null) actions.refreshMatches()
    }
}

/** Compact live score once the game on screen is known; opens the match centre. */
@Composable
private fun ScorePill(host: PlayerHost, modifier: Modifier = Modifier) {
    if (host.main.isVod) return
    val m = host.currentMatch() ?: return
    if (m.state == "pre") return
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Scrim).clickable { host.panel = Panel.MATCH }
            .semantics { contentDescription = "Live score, open match centre" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${m.home.abbr.ifEmpty { m.home.short }} ${m.home.score}–${m.away.score} ${m.away.abbr.ifEmpty { m.away.short }}",
            color = OnVideo, style = MaterialTheme.typography.labelLarge)
        Text(if (m.isLive) m.clock else "FT", Modifier.padding(start = 8.dp), color = if (m.isLive) Palette.Tally else OnVideoMuted,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TopActions(host: PlayerHost, actions: PlayerActions, pane: Pane, onTouch: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") pane.tracksTick
    val ccOn = pane.subtitlesOn
    IconButton(onClick = { pane.subtitlesDefault(!ccOn); pane.status = if (!ccOn) "Subtitles on" else "Subtitles off"; onTouch() }) {
        Icon(Icons.Default.ClosedCaption, if (ccOn) "Turn subtitles off" else "Turn subtitles on", tint = if (ccOn) Palette.Tally else OnVideoMuted)
    }
    if (host.looksLikeFootball()) {
        IconButton(onClick = { host.panel = Panel.MATCH; actions.refreshMatches() }) { Icon(Icons.Default.SportsSoccer, "Match stats", tint = OnVideo) }
    }
    if (!pane.isVod && pane.items.size > 1) {
        IconButton(onClick = { host.panel = Panel.KEYPAD }) { Icon(Icons.Default.Dialpad, "Channel number", tint = OnVideo) }
        IconButton(onClick = { host.panel = Panel.CHANNELS }) { Icon(Icons.AutoMirrored.Filled.List, "Channel list", tint = OnVideo) }
    }
    CastButton(host)
    IconButton(onClick = { host.panel = Panel.SETTINGS }) { Icon(Icons.Default.Settings, "Player settings", tint = OnVideo) }
}

/** End of an episode: the next one starts in 10 seconds unless you say otherwise. */
@Composable
private fun UpNextCard(host: PlayerHost) {
    if (host.upNextAt == 0L) return
    val next = host.main.items.getOrNull(host.main.index + 1) ?: return
    val left by produceState(10L, host.upNextAt) {
        while (host.upNextAt > 0) { value = ((host.upNextAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0); delay(200) }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp)).background(Scrim).padding(16.dp)) {
            Text("Up next in ${left}s", color = Palette.Tally, style = MaterialTheme.typography.labelLarge)
            Text(next.name, Modifier.padding(top = 4.dp), color = OnVideo, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Play now", accent = true) { host.playNextNow() }
                Pill("Cancel") { host.cancelUpNext() }
            }
        }
    }
}

/** Cast to a TV; lit while connected. Only on phones with Google Play services. */
@Composable
internal fun CastButton(host: PlayerHost) {
    val c = host.caster ?: return
    val on = c.deviceName != null
    IconButton(onClick = { host.panel = Panel.CAST }) {
        Icon(if (on) Icons.Default.CastConnected else Icons.Default.Cast, if (on) "Casting to ${c.deviceName}" else "Cast to TV",
            tint = if (on) Palette.Tally else OnVideo)
    }
}

/** Device picker: TVs and Chromecasts found on this Wi-Fi. */
@Composable
private fun CastPanel(host: PlayerHost) {
    val c = host.caster ?: return
    DisposableEffect(Unit) { c.startScan(); onDispose { c.stopScan() } }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(8_000); waited = true }
    PanelTitle("Cast to TV") { host.panel = Panel.NONE }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        c.deviceName?.let { name ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.TallyDim).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CastConnected, null, tint = Palette.Tally)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text("Connected", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                Pill("Stop casting") { c.disconnect(); host.panel = Panel.NONE }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (c.state == Caster.State.ERROR && c.deviceName == null) {
            c.message?.let { Text(it, Modifier.padding(4.dp, 0.dp, 4.dp, 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        val others = c.devices.filter { it.name != c.deviceName }
        others.forEach { d ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { c.connect(d); host.panel = Panel.NONE }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tv, null, tint = OnVideo)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    (if (d.connecting) "Connecting…" else d.description)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (others.isEmpty()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Tally)
                Text("Looking for TVs on your Wi-Fi…", Modifier.padding(start = 12.dp), color = OnVideo)
            }
            if (waited) {
                Text(
                    "Nothing yet. Check the phone and the TV/Chromecast are on the same Wi-Fi network, and that the TV is on. " +
                        "Some guest or mesh networks block casting.",
                    Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
            }
        }
        Text(
            "Live channels are relayed through this phone to the TV, so keep the phone on Wi-Fi. You can lock it or leave the app.",
            Modifier.padding(10.dp, 18.dp, 10.dp, 12.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Faint,
        )
    }
}

/** Remote control shown over the player while the picture is on the TV. */
@Composable
private fun CastingOverlay(host: PlayerHost, actions: PlayerActions) {
    val c = host.caster ?: return
    val name = c.deviceName ?: return
    val pane = host.main
    val e = pane.entry
    val live = !pane.isVod
    val epg = EpgStore.data
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val cur = if (live) epg.nowNext(e?.epgId, now).first else null
    val status = when (c.state) {
        Caster.State.CONNECTING -> c.message ?: "Connecting to $name…"
        Caster.State.LOADING -> "Starting on $name…"
        Caster.State.BUFFERING -> "Buffering on $name…"
        Caster.State.PLAYING -> "Playing on $name"
        Caster.State.PAUSED -> "Paused on $name"
        Caster.State.ERROR -> "Problem on $name"
        Caster.State.IDLE -> "Connected to $name"
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF2101018), Color(0xF2000000))))) {
        IconButton(onClick = { actions.exit() }, modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OnVideo)
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (c.state == Caster.State.LOADING || c.state == Caster.State.BUFFERING || c.state == Caster.State.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(76.dp), strokeWidth = 2.dp, color = Palette.Tally)
                }
                Icon(Icons.Default.CastConnected, null, tint = if (c.state == Caster.State.ERROR) MaterialTheme.colorScheme.error else Palette.Tally,
                    modifier = Modifier.size(40.dp))
            }
            Text(status, Modifier.padding(top = 14.dp), color = OnVideoMuted, style = MaterialTheme.typography.labelLarge)
            Text(e?.name.orEmpty(), Modifier.padding(top = 6.dp), color = OnVideo, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            cur?.let { p ->
                Text(p.title, Modifier.padding(top = 2.dp), color = OnVideoMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressLine(p, now, Modifier.padding(top = 8.dp).width(220.dp))
            }
            if (c.state == Caster.State.ERROR) {
                c.message?.let {
                    Text(it, Modifier.padding(top = 14.dp).widthIn(max = 420.dp), color = OnVideo, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Retry", accent = true) { host.retryCast() }
                    if ((e?.variants?.size ?: 0) > 1) Pill("Try another version") { pane.cycleVersion() }
                }
            }
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (live && pane.items.size > 1) RoundButton(Icons.Default.SkipPrevious, "Previous channel", 52.dp) { pane.step(-1) }
                if (!live) RoundButton(Icons.Default.Replay10, "Back 10 seconds", 52.dp) { c.seekBy(-10_000) }
                RoundButton(
                    if (c.state == Caster.State.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (c.state == Caster.State.PAUSED) "Play on TV" else "Pause on TV", 68.dp, accent = true,
                ) { c.togglePause() }
                if (!live) RoundButton(Icons.Default.Forward10, "Forward 10 seconds", 52.dp) { c.seekBy(10_000) }
                if (live && pane.items.size > 1) RoundButton(Icons.Default.SkipNext, "Next channel", 52.dp) { pane.step(1) }
            }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c.setVolumeStep(false) }) { Icon(Icons.AutoMirrored.Filled.VolumeDown, "TV volume down", tint = OnVideo) }
                IconButton(onClick = { c.setVolumeStep(true) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "TV volume up", tint = OnVideo) }
                if (live && pane.items.size > 1) {
                    IconButton(onClick = { host.panel = Panel.KEYPAD }) { Icon(Icons.Default.Dialpad, "Channel number", tint = OnVideo) }
                    IconButton(onClick = { host.panel = Panel.CHANNELS }) { Icon(Icons.AutoMirrored.Filled.List, "Channel list", tint = OnVideo) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Pill("Stop casting") { c.disconnect() }
        }
    }
}

@Composable
private fun CenterControls(pane: Pane, large: Boolean = true, onTouch: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val big = if (large) 72.dp else 56.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (large) 36.dp else 20.dp)) {
        if (pane.isVod) {
            RoundButton(Icons.Default.Replay10, "Back 10 seconds", big * 0.7f) { pane.player?.seekBack(); onTouch() }
        } else if (pane.items.size > 1) {
            RoundButton(Icons.Default.SkipPrevious, "Previous channel", big * 0.7f) { Haptics.tick(view); pane.step(-1); onTouch() }
        }
        RoundButton(if (pane.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (pane.playing) "Pause" else "Play", big, accent = true) {
            pane.togglePlay(); onTouch()
        }
        if (pane.isVod) {
            RoundButton(Icons.Default.Forward10, "Forward 10 seconds", big * 0.7f) { pane.player?.seekForward(); onTouch() }
        } else if (pane.items.size > 1) {
            RoundButton(Icons.Default.SkipNext, "Next channel", big * 0.7f) { Haptics.tick(view); pane.step(1); onTouch() }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (accent) Palette.Tally else Color(0x40000000))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (accent) Palette.OnTally else OnVideo, modifier = Modifier.size(size * 0.5f))
    }
}

/** Live: on-air dot, programme progress and times. Film: seek bar. Plus watch-mode buttons. */
@Composable
private fun BottomBar(host: PlayerHost, actions: PlayerActions, pane: Pane, cur: Programme?, now: Long, showModes: Boolean, onTouch: () -> Unit) {
    Column {
        if (pane.isVod) {
            val (pos, dur) = rememberPosition(pane, host.controls)
            var dragging by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(Pane.formatTime(dragging?.let { (it * dur).toLong() } ?: pos), color = OnVideo, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { dragging = it; onTouch() },
                    onValueChangeFinished = { dragging?.let { pane.player?.seekTo((it * dur).toLong()) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(thumbColor = Palette.Tally, activeTrackColor = Palette.Tally, inactiveTrackColor = Color(0x55FFFFFF)),
                )
                Text(if (dur > 0) Pane.formatTime(dur) else "--:--", color = OnVideo, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                LiveBadge()
                if (cur != null) {
                    Text(hm(cur.start), Modifier.padding(start = 12.dp), color = OnVideoMuted, style = MaterialTheme.typography.labelMedium)
                    ProgressLine(cur, now, Modifier.weight(1f).padding(horizontal = 10.dp), height = 4.dp)
                    Text(hm(cur.end), color = OnVideoMuted, style = MaterialTheme.typography.labelMedium)
                    if (remember(pane.entry, cur) { pane.canStartOver(cur) }) {
                        Spacer(Modifier.width(10.dp))
                        Pill("Start over") { pane.startOver(cur); onTouch() }
                    }
                } else Spacer(Modifier.weight(1f))
            }
        }
        if (showModes) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                pane.versionLabel?.let { v -> Pill(v) { pane.cycleVersion(); onTouch() } }
                if (host.sleepAt > 0) {
                    Text("Sleep at ${hm(host.sleepAt)}", Modifier.padding(start = 10.dp), color = Palette.Tally, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                if (pane.isVod) {
                    ModeButton(Icons.Default.SwapVert, "Vertical view") { host.surf = true; host.controls = false }
                } else if (pane.items.size > 1) {
                    ModeButton(Icons.Default.SwapVert, "Surf mode") { host.surf = true; host.controls = false }
                    if (host.canMultiview) ModeButton(Icons.Default.VerticalSplit, "Multiview") { host.panel = Panel.PICK_SECOND }
                }
                ModeButton(Icons.Default.PictureInPictureAlt, "Picture in picture") { actions.enterPip() }
                ModeButton(
                    if (host.orientation == 0) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation,
                    when (host.orientation) { 1 -> "Rotation locked to landscape"; 2 -> "Rotation locked to portrait"; else -> "Rotate and lock" },
                ) { actions.toggleRotation() }
            }
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = OnVideo) }
}

/** On-air tally: a slowly breathing amber dot. The one ambient animation in the app. */
@Composable
private fun LiveBadge() {
    val t = rememberInfiniteTransition(label = "tally")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "tallyAlpha")
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(Palette.Tally))
        Text("Live", Modifier.padding(start = 6.dp), color = OnVideo, style = MaterialTheme.typography.labelMedium)
    }
}

/** TV-style banner each time the channel changes. */
@Composable
private fun ChannelBanner(host: PlayerHost, pane: Pane, now: Long, modifier: Modifier, hidden: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pane.index) {
        if (!pane.isVod) { visible = true; delay(5_000); visible = false }
    }
    val (cur, next) = host.nowNext(pane, now)
    AnimatedVisibility(
        visible && !hidden,
        enter = fadeIn(tween(220)) + androidx.compose.animation.slideInVertically(tween(260)) { -it / 2 },
        exit = fadeOut(tween(300)) + androidx.compose.animation.slideOutVertically(tween(300)) { -it / 3 },
        modifier = modifier,
    ) {
        Row(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(18.dp)).background(Scrim).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(pane.entry?.variants?.firstNotNullOfOrNull { it.logo }, pane.entry?.name ?: "", 52.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${pane.index + 1}", color = Palette.Tally, style = MaterialTheme.typography.titleMedium)
                    Text(pane.entry?.name ?: "", Modifier.padding(start = 10.dp), color = OnVideo,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (cur != null) {
                    Text("${hm(cur.start)}  ${cur.title}", Modifier.padding(top = 4.dp), color = OnVideo, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    ProgressLine(cur, now, Modifier.padding(top = 6.dp).width(260.dp))
                } else {
                    Text(if (EpgStore.data.channelCount == 0) "No TV guide loaded" else "No guide information for this channel",
                        Modifier.padding(top = 4.dp), color = OnVideoMuted, style = MaterialTheme.typography.bodySmall)
                }
                next?.let {
                    Text("Then ${hm(it.start)}  ${it.title}", Modifier.padding(top = 6.dp), color = OnVideoMuted,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ================================================================== portrait

/** Upright phone: picture on top, what's on and the channel list underneath. */
@Composable
private fun PortraitPlayer(host: PlayerHost, actions: PlayerActions) {
    val pane = host.main
    val now = rememberNow()
    var touch by remember { mutableIntStateOf(0) }
    val (cur, next) = host.nowNext(pane, now)
    LaunchedEffect(host.controls, touch, pane.playing, host.panel) {
        if (host.controls && pane.playing && host.panel == Panel.NONE) { delay(3_500); host.controls = false }
    }
    AutoIdentifyMatch(host, actions, cur)
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(Palette.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VideoSurface(pane, host, Modifier.fillMaxSize(), resizeOverride = if (host.resizeMode == 2) 2 else null, ambient = host.ambient)
            GestureLayer(host, actions, pane, edgeGestures = false) { touch++ }
            androidx.compose.animation.AnimatedVisibility(host.controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    IconButton(onClick = actions::exit, modifier = Modifier.align(Alignment.TopStart)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OnVideo)
                    }
                    Row(Modifier.align(Alignment.TopEnd)) { TopActions(host, actions, pane) { touch++ } }
                    Box(Modifier.align(Alignment.Center)) { CenterControls(pane, large = false) { touch++ } }
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { BottomBar(host, actions, pane, cur, now, showModes = false) { touch++ } }
                        IconButton(onClick = { actions.toggleRotation() }) { Icon(Icons.Default.Fullscreen, "Full screen", tint = OnVideo) }
                    }
                }
            }
            GestureIndicator(host, Modifier.align(Alignment.Center))
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "info") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(pane.entry?.variants?.firstNotNullOfOrNull { it.logo }, pane.entry?.name ?: "", 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(pane.entry?.name ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(pane.channel?.group ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1)
                        }
                        pane.versionLabel?.let { v ->
                            Text(v, Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Dusk).clickable { pane.cycleVersion() }
                                .padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                    }
                    ScorePill(host, Modifier.padding(top = 14.dp))
                    if (cur != null) {
                        Text(cur.title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hm(cur.start), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                            ProgressLine(cur, now, Modifier.weight(1f).padding(horizontal = 10.dp), height = 4.dp)
                            Text(hm(cur.end), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        cur.desc?.let { Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                        next?.let { Text("Then ${hm(it.start)}  ${it.title}", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted) }
                    }
                    // Watch modes, labelled, since there's room upright.
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pane.isVod) {
                            ModeTile(Icons.Default.SwapVert, "Vertical") { host.surf = true }
                        } else if (pane.items.size > 1) {
                            ModeTile(Icons.Default.SwapVert, "Surf") { host.surf = true }
                            if (host.canMultiview) ModeTile(Icons.Default.VerticalSplit, "Multiview") { host.panel = Panel.PICK_SECOND }
                        }
                        if (host.looksLikeFootball()) ModeTile(Icons.Default.SportsSoccer, "Match") { host.panel = Panel.MATCH; actions.refreshMatches() }
                        ModeTile(Icons.Default.PictureInPictureAlt, "Mini player") { actions.enterPip() }
                    }
                }
            }
            if (!pane.isVod && pane.items.size > 1) {
                item(key = "list-h") {
                    Text("Channels", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = Palette.Muted)
                }
                itemsIndexed(pane.items, key = { i, it -> "$i|${it.key}" }) { i, it ->
                    QueueRow(i, it, i == pane.index, now) { pane.play(i) }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Night).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Palette.Text)
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
}

@Composable
private fun QueueRow(i: Int, e: PlayEntry, current: Boolean, now: Long, onClick: () -> Unit) {
    val (c, _) = EpgStore.data.nowNext(e.epgId, now)
    Row(
        Modifier.fillMaxWidth().background(if (current) Palette.TallyDim else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${i + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium, color = if (current) Palette.Tally else Palette.Faint)
        ChannelLogo(e.variants.firstNotNullOfOrNull { it.logo }, e.name, 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (c != null) {
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                ProgressLine(c, now, Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ================================================================== surf (vertical feed)

/**
 * Surf: one channel per screen, swipe up for the next, like a short-video feed.
 * Only the settled page plays; neighbours show the logo and what's on, so swiping costs nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SurfMode(host: PlayerHost, actions: PlayerActions) {
    val pane = host.main
    val items = pane.items
    val pager = rememberPagerState(initialPage = pane.index) { items.size }
    val now = rememberNow()
    var hint by remember { mutableStateOf(true) }

    val surfView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != pane.index) { Haptics.tick(surfView); pane.play(pager.settledPage); hint = false }
    }
    LaunchedEffect(Unit) { delay(4_000); hint = false }
    AutoIdentifyMatch(host, actions, host.nowNext(pane, now).first)

    Box(Modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { "$it|${items[it].key}" }) { page ->
            val e = items[page]
            val (cur, next) = EpgStore.data.nowNext(e.epgId, now)
            Box(Modifier.fillMaxSize().background(Palette.Ink)) {
                // Backdrop: the logo, large and faint, so every page has an identity even before it plays.
                e.variants.firstNotNullOfOrNull { it.logo }?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.7f).alpha(0.08f))
                }
                if (page == pager.settledPage && page == pane.index) {
                    VideoSurface(pane, host, Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f))
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f)
                        .pointerInput(Unit) { detectTapGestures(onTap = { pane.togglePlay() }) })
                } else {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                        ChannelLogo(e.variants.firstNotNullOfOrNull { it.logo }, e.name, 72.dp)
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE015131F)))))
                Column(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 20.dp, end = 96.dp, bottom = 28.dp)) {
                    if (page == pane.index) ScorePill(host, Modifier.padding(bottom = 10.dp))
                    Text("${page + 1} of ${items.size}", style = MaterialTheme.typography.labelMedium, color = Palette.Tally)
                    Text(e.name, style = MaterialTheme.typography.headlineMedium, color = OnVideo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cur != null) {
                        Text(cur.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, color = OnVideo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hm(cur.start), style = MaterialTheme.typography.labelMedium, color = OnVideoMuted)
                            ProgressLine(cur, now, Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text(hm(cur.end), style = MaterialTheme.typography.labelMedium, color = OnVideoMuted)
                        }
                        next?.let { Text("Then ${hm(it.start)}  ${it.title}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = OnVideoMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }

        // Action rail on the right, thumb-reachable.
        Column(
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton(if (pane.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (pane.muted) "Unmute" else "Mute") { pane.mute(!pane.muted) }
            @Suppress("UNUSED_EXPRESSION") pane.tracksTick
            RailButton(Icons.Default.ClosedCaption, if (pane.subtitlesOn) "Subtitles off" else "Subtitles on", lit = pane.subtitlesOn) { pane.subtitlesDefault(!pane.subtitlesOn) }
            if (host.looksLikeFootball()) RailButton(Icons.Default.SportsSoccer, "Match stats") { host.panel = Panel.MATCH; actions.refreshMatches() }
            RailButton(Icons.AutoMirrored.Filled.List, "Channel list") { host.panel = Panel.CHANNELS }
            RailButton(Icons.Default.Fullscreen, "Leave surf mode") { host.surf = false; host.controls = true }
        }
        IconButton(onClick = { host.surf = false; host.controls = true },
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).clip(CircleShape).background(Color(0x66000000))) {
            Icon(Icons.Default.Close, "Close surf mode", tint = OnVideo)
        }
        AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 18.dp)) {
            Text("Swipe up for the next channel", Modifier.clip(RoundedCornerShape(16.dp)).background(Scrim).padding(horizontal = 14.dp, vertical = 8.dp),
                color = OnVideo, style = MaterialTheme.typography.labelLarge)
        }
        pane.error?.let {
            Text(it, Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(14.dp)).background(Scrim).padding(16.dp),
                color = OnVideo, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Surf-style viewing for a film or episode: upright and full screen, no swiping between titles.
 * Cropping a landscape picture to fill a tall screen throws away most of it, so instead the whole picture
 * sits in the middle over an ambient glow sampled from its own colours, and pinch-to-zoom lets you crop
 * exactly as much as you like and drag to choose what stays in view.
 */
@Composable
private fun VerticalVodView(host: PlayerHost, actions: PlayerActions) {
    val pane = host.main
    var overlay by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var view by remember { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(overlay, touch, pane.playing, host.panel) {
        if (overlay && pane.playing && host.panel == Panel.NONE) { delay(4_000); overlay = false }
    }
    LaunchedEffect(overlay) { host.controls = overlay }   // keeps the seek bar's position updating
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        // Most zoom that's useful: enough for a 16:9 picture to fill the screen's height.
        val maxZoom = (h / (w * 9f / 16f)).coerceAtLeast(1.5f)

        VideoSurface(
            pane, host, Modifier.fillMaxSize(), resizeOverride = 0, texture = true, ambient = host.ambient,
            onView = { view = it },
            videoModifier = Modifier.graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationX = pan.x; translationY = pan.y
            },
        )
        // Gestures: tap for controls, double-tap to skip, pinch to zoom, drag to pan while zoomed.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { overlay = !overlay; touch++ },
                        onDoubleTap = { off ->
                            val p = pane.player ?: return@detectTapGestures
                            if (off.x < size.width / 2) { p.seekBack(); pane.status = "Back 10 seconds" } else { p.seekForward(); pane.status = "Forward 10 seconds" }
                        },
                    )
                }
                .pointerInput(maxZoom) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        val z = (zoom * zoomChange).coerceIn(1f, maxZoom)
                        // Keep the picture covering the area it can: no dragging it off into empty space.
                        val limX = (w * z - w) / 2f
                        val limY = ((w * 9f / 16f) * z - h).coerceAtLeast(0f) / 2f
                        val next = pan + panChange
                        zoom = z
                        pan = if (z <= 1.01f) Offset.Zero else Offset(next.x.coerceIn(-limX, limX), next.y.coerceIn(-limY, limY))
                        touch++
                    }
                },
        )

        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xE015131F))))
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { host.surf = false; host.controls = true }, modifier = Modifier.clip(CircleShape).background(Color(0x66000000))) {
                        Icon(Icons.Default.Close, "Leave vertical view", tint = OnVideo)
                    }
                    Spacer(Modifier.weight(1f))
                    TopActions(host, actions, pane) { touch++ }
                }
                Box(Modifier.align(Alignment.Center)) { CenterControls(pane) { touch++ } }
                Column(
                    Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 12.dp, top = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RailButton(Icons.Default.BlurOn, if (host.ambient) "Turn ambient background off" else "Turn ambient background on", lit = host.ambient) {
                        host.updateAmbient(!host.ambient); touch++
                    }
                    if (zoom > 1.01f) RailButton(Icons.Default.ZoomOutMap, "Reset zoom") { zoom = 1f; pan = Offset.Zero; touch++ }
                    RailButton(Icons.Default.PictureInPictureAlt, "Mini player") { actions.enterPip() }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                    Text(pane.entry?.name ?: "", style = MaterialTheme.typography.headlineMedium, color = OnVideo, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(pane.channel?.group ?: "", Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = OnVideoMuted, maxLines = 1)
                    BottomBar(host, actions, pane, null, System.currentTimeMillis(), showModes = false) { touch++ }
                }
            }
        }
        // Zoom level while pinching, so it's clear how much is being cropped.
        if (zoom > 1.01f && !overlay) {
            Text("${(zoom * 100).toInt()}%", Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 16.dp)
                .clip(RoundedCornerShape(12.dp)).background(Scrim).padding(horizontal = 12.dp, vertical = 6.dp),
                color = OnVideo, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(50.dp).clip(CircleShape).background(if (lit) Palette.Tally else Color(0x66000000)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (lit) Palette.OnTally else OnVideo) }
}

// ================================================================== multiview

/**
 * Two streams at once. One has sound (amber frame); tap the other to move the sound to it,
 * the way Sky's multiview works.
 */
@Composable
private fun MultiView(host: PlayerHost, portrait: Boolean, onChange: (Int) -> Unit) {
    val panes = listOfNotNull(host.main, host.second)
    val now = rememberNow()
    Column(Modifier.fillMaxSize().background(Palette.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { host.closeSecond() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave multiview", tint = OnVideo) }
            Text("Multiview", style = MaterialTheme.typography.titleLarge, color = OnVideo, modifier = Modifier.weight(1f))
            Text("Tap a picture to hear it", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(end = 12.dp))
        }
        val content: @Composable (Modifier) -> Unit = { m ->
            panes.forEachIndexed { slot, p ->
                MultiPane(host, p, slot, now, m, onChange = { onChange(slot) })
            }
        }
        if (portrait) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                content(Modifier.weight(1f).aspectRatio(16f / 9f))
            }
        }
    }
}

@Composable
private fun MultiPane(host: PlayerHost, pane: Pane, slot: Int, now: Long, modifier: Modifier, onChange: () -> Unit) {
    val audible = host.audioSlot == slot
    val (cur, _) = host.nowNext(pane, now)
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .border(if (audible) 2.5.dp else 1.dp, if (audible) Palette.Tally else Palette.Line, RoundedCornerShape(14.dp)),
    ) {
        VideoSurface(pane, host, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(slot) { detectTapGestures(onTap = { host.setAudio(slot) }) })
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (audible) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, if (audible) "Sound on" else "Sound off",
                tint = if (audible) Palette.Tally else OnVideoMuted, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(pane.entry?.name ?: "", color = OnVideo, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cur?.let { Text(it.title, color = OnVideoMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = onChange) { Icon(Icons.AutoMirrored.Filled.List, "Change channel", tint = OnVideo) }
            if (slot == 1) IconButton(onClick = { host.closeSecond() }) { Icon(Icons.Default.Close, "Close this picture", tint = OnVideo) }
        }
    }
}

// ================================================================== panels

/**
 * Side sheet in landscape, bottom sheet upright. The dimmed backdrop sits beside or above the sheet
 * rather than under it, so a tap can only ever reach one of them: options never close the panel,
 * and tapping the backdrop always does.
 */
@Composable
private fun PanelHost(host: PlayerHost, actions: PlayerActions, portrait: Boolean, pickSlot: Int) {
    if (host.panel == Panel.NONE) return
    val backdrop: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.background(Color(0x88000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { host.panel = Panel.NONE }
                .semantics { contentDescription = "Close panel" },
        )
    }
    val content: @Composable ColumnScope.() -> Unit = {
        when (host.panel) {
            Panel.SETTINGS -> SettingsPanel(host, actions)
            Panel.CHANNELS -> ChannelPicker(host, "Channels") { i -> host.main.play(i); host.panel = Panel.NONE }
            Panel.PICK_SECOND -> ChannelPicker(host, if (host.multi) "Change channel" else "Watch a second channel") { i ->
                if (pickSlot == 0 && host.multi) host.main.play(i) else host.openSecond(i)
                host.panel = Panel.NONE
            }
            Panel.MATCH -> MatchPanel(host, actions)
            Panel.KEYPAD -> KeypadPanel(host)
            Panel.CAST -> CastPanel(host)
            Panel.NONE -> {}
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (portrait) {
            val sheetH = maxHeight * 0.78f
            Column(Modifier.fillMaxSize()) {
                backdrop(Modifier.fillMaxWidth().weight(1f))
                Column(
                    Modifier.fillMaxWidth().height(sheetH).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Palette.Night).testTag("panelSheet").windowInsetsPadding(WindowInsets.safeDrawing),
                    content = content,
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                backdrop(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(380.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                        .background(Palette.Night).testTag("panelSheet").windowInsetsPadding(WindowInsets.safeDrawing),
                    content = content,
                )
            }
        }
    }
}

/** Type a channel number like on a remote; it switches by itself after a short pause. */
@Composable
private fun KeypadPanel(host: PlayerHost) {
    val keyView = androidx.compose.ui.platform.LocalView.current
    var digits by remember { mutableStateOf("") }
    val items = host.main.items
    val target = digits.toIntOrNull()?.takeIf { it in 1..items.size }
    fun go() { target?.let { host.main.play(it - 1); host.panel = Panel.NONE } }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) { delay(if (digits.length >= items.size.toString().length) 700 else 1_800); go() }
    }
    PanelTitle("Channel number") { host.panel = Panel.NONE }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(digits.ifEmpty { "–" }, style = MaterialTheme.typography.displaySmall, color = Palette.Tally)
        Text(
            when {
                digits.isEmpty() -> "1 to ${items.size}"
                target != null -> items[target - 1].name
                else -> "No channel ${digits}"
            },
            Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "Go")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { k ->
                    val accent = k == "Go"
                    Box(
                        Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (accent) Palette.Tally else Palette.Dusk)
                            .clickable {
                                when (k) {
                                    "⌫" -> digits = digits.dropLast(1)
                                    "Go" -> go()
                                    else -> if (digits.length < 5) { Haptics.tick(keyView); digits += k }
                                }
                            }
                            .semantics { contentDescription = if (k == "⌫") "Delete digit" else k },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k, style = MaterialTheme.typography.titleLarge, color = if (accent) Palette.OnTally else Palette.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Palette.Muted) }
    }
}

@Composable
private fun ChannelPicker(host: PlayerHost, title: String, onPick: (Int) -> Unit) {
    val now = rememberNow()
    var q by remember { mutableStateOf("") }
    val items = host.main.items
    val shown = remember(items, q) {
        val t = q.trim().lowercase()
        items.withIndex().filter { t.isEmpty() || it.value.name.lowercase().contains(t) }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = if (q.isEmpty()) (host.main.index - 2).coerceAtLeast(0) else 0)
    PanelTitle(title) { host.panel = Panel.NONE }
    SearchField(q, { q = it }, "Find a channel", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    LazyColumn(state = state) {
        items(shown, key = { "${it.index}|${it.value.key}" }) { (i, e) ->
            QueueRow(i, e, i == host.main.index, now) { onPick(i) }
        }
    }
}

// ---------------------------------------------------------------- settings panel

@Composable
private fun SettingsPanel(host: PlayerHost, actions: PlayerActions) {
    // In multiview, settings apply to the picture you're listening to.
    val pane = if (host.multi && host.audioSlot == 1) host.second!! else host.main
    @Suppress("UNUSED_EXPRESSION") pane.tracksTick
    var info by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); info++ } }

    PanelTitle("Player settings") { host.panel = Panel.NONE }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SettingBlock("Subtitles") {
            val tracks = pane.textTracks()
            OptionRow("Off", !pane.subtitlesOn) { pane.selectText(null) }
            if (tracks.isEmpty()) Hint("This stream doesn't carry subtitles or captions.")
            tracks.forEach { t -> OptionRow(t.label, pane.subtitlesOn && t.selected) { pane.selectText(t) } }
            Chips(listOf(0.8f to "Small", 1f to "Normal", 1.3f to "Large", 1.6f to "Huge"), host.subtitleScale) { host.setSubtitleSize(it) }
        }
        val audio = pane.audioTracks()
        SettingBlock("Audio") {
            if (audio.size <= 1) Hint(audio.firstOrNull()?.let { "${it.label}. This stream has one audio track." } ?: "Waiting for the stream…")
            else audio.forEach { t -> OptionRow(t.label, t.selected) { pane.selectAudio(t) } }
        }
        val video = pane.videoTracks()
        if (video.size > 1) SettingBlock("Quality") {
            OptionRow("Automatic", pane.videoAuto) { pane.selectVideo(null) }
            video.forEach { t -> OptionRow(t.label, !pane.videoAuto && t.selected) { pane.selectVideo(t) } }
        }
        SettingBlock("Picture") {
            Chips(listOf(0 to "Fit", 1 to "Zoom", 2 to "Stretch"), host.resizeMode) { host.setResize(it) }
            Chips(listOf(true to "Ambient blur on", false to "Off"), host.ambient) { host.updateAmbient(it) }
            Hint("Ambient blur fills the space around a picture that doesn't fill the screen with its own colours.")
        }
        if (pane.isVod) SettingBlock("Speed") {
            Chips(listOf(0.5f to "0.5×", 0.75f to "0.75×", 1f to "Normal", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"), pane.speed) { pane.setSpeed(it) }
        }
        SettingBlock("Sleep timer") {
            val opts = buildList {
                add(0 to "Off"); add(15 to "15 min"); add(30 to "30 min"); add(60 to "1 hour"); add(90 to "90 min")
                if (host.nowNext().first != null) add(-1 to "End of programme")
            }
            Chips(opts, if (host.sleepAt == 0L) 0 else Int.MIN_VALUE) { actions.setSleep(it) }
            if (host.sleepAt > 0) Hint("Pauses at ${hm(host.sleepAt)}.")
        }
        SettingBlock("Watch") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pane.isVod) {
                    ModeTile(Icons.Default.SwapVert, "Vertical") { host.panel = Panel.NONE; host.surf = true }
                } else if (pane.items.size > 1) {
                    ModeTile(Icons.Default.SwapVert, "Surf") { host.panel = Panel.NONE; host.surf = true }
                    if (host.canMultiview) ModeTile(Icons.Default.VerticalSplit, "Multiview") { host.panel = Panel.PICK_SECOND }
                }
                ModeTile(Icons.Default.PictureInPictureAlt, "Mini player") { actions.enterPip() }
                ModeTile(Icons.Default.ScreenRotation, "Rotate") { host.panel = Panel.NONE; actions.toggleRotation() }
            }
        }
        SettingBlock("Rotation") {
            Chips(listOf(0 to "Auto", 1 to "Landscape", 2 to "Portrait"), host.orientation) { actions.setOrientation(it) }
            Hint(when (host.orientation) {
                1 -> "Stays landscape whichever way you hold the phone."
                2 -> "Stays portrait whichever way you hold the phone."
                else -> "Follows the phone. The rotate button locks it the other way."
            })
        }
        SettingBlock("Stream info") {
            @Suppress("UNUSED_EXPRESSION") info
            val rows = pane.streamInfo()
            if (rows.isEmpty()) Hint("Nothing playing yet.")
            rows.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Text(v, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Tally, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Palette.TallyDim else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) Palette.Tally else Palette.Line))
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = if (selected) Palette.TallyText else Palette.Text)
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (v, label) ->
            val on = v == current
            Text(
                label,
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.TallyDim else Palette.Dusk)
                    .border(1.dp, if (on) Palette.Tally else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onPick(v) }.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = if (on) Palette.TallyText else Palette.Text,
            )
        }
    }
}

// ---------------------------------------------------------------- match panel

@Composable
private fun MatchPanel(host: PlayerHost, actions: PlayerActions) {
    PanelTitle("Match centre") { host.panel = Panel.NONE }
    val match = host.currentMatch()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        when {
            host.matchesState == "loading" && host.matches.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Tally) }
            host.matchesState == "failed" -> {
                Hint2("Live scores couldn't be reached. They come from ESPN's public feed, which may be down or have changed.")
                Text("Try again", Modifier.padding(horizontal = 22.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Tally)
                    .clickable { actions.refreshMatches() }.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Palette.OnTally, style = MaterialTheme.typography.labelLarge)
            }
            else -> {
                if (match != null) {
                    Scoreboard(match)
                    // Full picture (stats, line-ups, moments, table, live text) for this one game only.
                    var detail by remember(match.id) { mutableStateOf<Sports.Detail?>(null) }
                    var loadingDetail by remember(match.id) { mutableStateOf(true) }
                    LaunchedEffect(match.id) {
                        while (true) {
                            Sports.detail(match)?.let { detail = it }
                            loadingDetail = false
                            if (match.state == "post") break
                            delay(45_000)
                        }
                    }
                    if (detail == null && !loadingDetail) {
                        if (match.stats.isNotEmpty()) StatBars(match) else Hint2(if (match.state == "pre") "Stats appear once the game kicks off." else "No stats for this game yet.")
                        if (match.events.isNotEmpty()) Timeline(match)
                    } else {
                        com.zapline.iptv.ui.MatchDetailView(match, detail, loadingDetail)
                    }
                    if (host.pinnedMatch != null) {
                        Text("Not this game? Pick another below.", Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                } else {
                    Hint2("Couldn't tell which game this channel is showing. Pick it from today's games:")
                }
                val others = host.matches.filter { it.id != match?.id }
                if (others.isNotEmpty()) {
                    Text("Today", Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = Palette.Tally)
                    others.take(40).forEach { m -> GameRow(m) { host.pinnedMatch = m.id } }
                } else if (host.matchesState == "ready" && match == null) {
                    Hint2("No games in the covered competitions today.")
                }
            }
        }
    }
}

@Composable
private fun Hint2(text: String) = Text(text, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)

@Composable
private fun Scoreboard(m: Sports.Match) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.isLive) LiveBadgeDark()
            Text(if (m.isLive) m.clock else m.detail, Modifier.padding(start = if (m.isLive) 8.dp else 0.dp),
                style = MaterialTheme.typography.labelLarge, color = if (m.isLive) Palette.Tally else Palette.Muted)
        }
        Text(m.league, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamBlock(m.home, Modifier.weight(1f))
            Text(if (m.state == "pre") "v" else "${m.home.score}  ${m.away.score}", style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 8.dp))
            TeamBlock(m.away, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TeamBlock(t: Sports.Team, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (t.logo != null) AsyncImage(model = t.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
            else Text(t.abbr, style = MaterialTheme.typography.titleSmall)
        }
        Text(t.short, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun LiveBadgeDark() {
    val t = rememberInfiniteTransition(label = "tally2")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(Palette.Tally))
}

/** Each stat as a split bar: home share in amber from the left, away in lilac from the right. */
@Composable
private fun StatBars(m: Sports.Match) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        m.stats.forEach { s ->
            val h = s.home.trimEnd('%').toFloatOrNull() ?: 0f
            val a = s.away.trimEnd('%').toFloatOrNull() ?: 0f
            val share = if (h + a > 0) h / (h + a) else 0.5f
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.home, Modifier.width(48.dp), style = MaterialTheme.typography.titleSmall)
                Text(s.label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                Text(s.away, Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(5.dp).clip(RoundedCornerShape(3.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (share > 0f) Box(Modifier.weight(share.coerceAtLeast(0.01f)).fillMaxHeight().background(Palette.Tally))
                if (share < 1f) Box(Modifier.weight((1 - share).coerceAtLeast(0.01f)).fillMaxHeight().background(Palette.Lilac))
            }
        }
    }
}

@Composable
private fun Timeline(m: Sports.Match) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("Goals and cards", style = MaterialTheme.typography.titleSmall, color = Palette.Tally, modifier = Modifier.padding(bottom = 6.dp))
        m.events.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (e.home) Arrangement.Start else Arrangement.End) {
                val marker: @Composable () -> Unit = {
                    when (e.kind) {
                        Sports.Kind.GOAL -> Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Tally))
                        Sports.Kind.YELLOW -> Box(Modifier.size(width = 8.dp, height = 11.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFFF5D547)))
                        Sports.Kind.RED -> Box(Modifier.size(width = 8.dp, height = 11.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFFE5484D)))
                        else -> {}
                    }
                }
                val text = listOfNotNull(e.player, e.text.takeIf { e.kind == Sports.Kind.GOAL && it != "Goal" && !it.startsWith("Goal -") }).joinToString(", ")
                if (e.home) {
                    Text(e.minute, Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    marker(); Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                    marker(); Text(e.minute, Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
        }
    }
}

@Composable
private fun GameRow(m: Sports.Match, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${m.home.short}  ${if (m.state == "pre") "v" else "${m.home.score}-${m.away.score}"}  ${m.away.short}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.league, style = MaterialTheme.typography.labelSmall, color = Palette.Faint)
        }
        Text(if (m.isLive) m.clock else m.detail, style = MaterialTheme.typography.labelMedium, color = if (m.isLive) Palette.Tally else Palette.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
    }
}

