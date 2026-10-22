package com.zapline.iptv.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Sports
import com.zapline.iptv.ui.ZaplineTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Panel { NONE, SETTINGS, CHANNELS, MATCH, PICK_SECOND, KEYPAD, CAST }

/** Everything the player UI reads and the actions it can take. */
class PlayerHost(val prefs: Prefs, val main: Pane, private val newPane: (Int) -> Pane) {
    var second by mutableStateOf<Pane?>(null); private set
    /** Which pane's sound is on in multiview (0 = main). */
    var audioSlot by mutableIntStateOf(0); private set
    var surf by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var panel by mutableStateOf(Panel.NONE)
    var resizeMode by mutableIntStateOf(prefs.resizeMode)
    /** Blurred colours around a picture that doesn't fill the screen. */
    var ambient by mutableStateOf(prefs.ambient)
    fun updateAmbient(on: Boolean) { ambient = on; prefs.ambient = on }
    /** 0 auto, 1 landscape, 2 portrait. */
    var orientation by mutableIntStateOf(prefs.playerOrientation)
    var subtitleScale by mutableFloatStateOf(prefs.subtitleScale)
    var sleepAt by mutableLongStateOf(0L)
    var pip by mutableStateOf(false)
    /** Brightness / volume feedback while swiping: label to 0..1. */
    var gesture by mutableStateOf<Pair<String, Float>?>(null)
    var matches by mutableStateOf<List<Sports.Match>>(emptyList())
    var matchesState by mutableStateOf("idle")   // idle, loading, ready, failed
    /** A game the viewer picked by hand when the programme title couldn't be matched. */
    var pinnedMatch by mutableStateOf<String?>(null)
    /** Chromecast control; null on phones without Google Play services. */
    var caster: Caster? = null
    /** True while a TV is connected (the phone's own player is stopped then). */
    val casting: Boolean get() = caster?.deviceName != null

    /** Hands playback to the TV: the phone stops streaming (providers often allow one connection) and every channel change goes to the TV. */
    fun beginCasting() {
        val c = caster ?: return
        closeSecond()
        surf = false
        val pos = if (main.isVod) main.player?.currentPosition ?: 0L else 0L
        if (main.isVod && pos > 5_000) main.channel?.let { prefs.saveResume(it.url, pos, main.player?.duration ?: 0L) }
        main.castSink = { e, ch, at -> c.load(ch, e.name, !e.key.startsWith("vod|"), at) }
        main.stopLocal()
        main.play(main.index, recordRecent = false)
    }

    fun endCasting() {
        if (main.castSink == null) return
        main.castSink = null
        if (main.player != null) main.play(main.index, recordRecent = false)
    }

    fun retryCast() {
        val c = caster ?: return
        val ch = main.channel ?: return
        val e = main.entry ?: return
        c.load(ch, e.name, !main.isVod, 0, force = true)
    }

    val multi: Boolean get() = second != null

    // ---- up next (end of an episode) ----
    /** When the next episode starts by itself (elapsedRealtime); 0 when no countdown is showing. */
    var upNextAt by mutableLongStateOf(0L); private set
    private var upNextFrom = -1
    private val upNextHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val upNextRun = Runnable {
        upNextAt = 0
        if (main.index == upNextFrom) main.step(1)
    }

    fun startUpNext() {
        upNextFrom = main.index
        upNextAt = android.os.SystemClock.elapsedRealtime() + 10_000
        upNextHandler.removeCallbacks(upNextRun)
        upNextHandler.postDelayed(upNextRun, 10_000)
    }

    fun playNextNow() { upNextHandler.removeCallbacks(upNextRun); upNextRun.run() }
    fun cancelUpNext() { upNextHandler.removeCallbacks(upNextRun); upNextAt = 0 }

    /** Multiview needs a second connection; some accounts only allow one. */
    val canMultiview: Boolean get() = !com.zapline.iptv.data.XtreamAuth.singleConnection

    fun openSecond(index: Int) {
        if (!canMultiview) {
            main.status = "Your account allows one stream at a time, so multiview can't open a second channel."
            return
        }
        val p = second ?: newPane(1).also { it.mute(true); it.create(); second = it }
        p.play(index, recordRecent = false)
        setAudio(audioSlot)
    }

    fun closeSecond() {
        second?.release()
        second = null
        setAudio(0)
    }

    fun setAudio(slot: Int) {
        audioSlot = if (second == null) 0 else slot
        main.mute(audioSlot != 0)
        second?.mute(audioSlot != 1)
    }

    fun setResize(m: Int) { resizeMode = m; prefs.resizeMode = m }
    fun setSubtitleSize(s: Float) { subtitleScale = s; prefs.subtitleScale = s }

    /** The current programme on a pane, if the guide knows it. */
    fun nowNext(pane: Pane = main, now: Long = System.currentTimeMillis()) =
        if (pane.isVod) null to null else EpgStore.data.nowNext(pane.entry?.epgId, now)

    /** The game matching what's on, or the one the viewer picked. */
    fun currentMatch(): Sports.Match? {
        pinnedMatch?.let { id -> matches.firstOrNull { it.id == id }?.let { return it } }
        val (cur, _) = nowNext()
        return Sports.find(matches, cur?.title, cur?.desc, main.entry?.name)
    }

    /** Whether the match button is worth showing: the programme looks like a fixture, or it's a sports channel. */
    fun looksLikeFootball(): Boolean {
        if (main.isVod) return false
        val (cur, _) = nowNext()
        val t = listOfNotNull(cur?.title, main.entry?.name, main.channel?.group).joinToString(" ").lowercase()
        return Sports.fixture(cur?.title ?: "") != null ||
            listOf("sport", "football", "soccer", "premier", "league", "bein", "dazn", "tnt", "espn", "eurosport", "laliga").any { it in t }
    }
}

/** Window-level actions the UI can ask for. */
interface PlayerActions {
    fun exit()
    fun enterPip()
    fun toggleRotation()
    fun setOrientation(mode: Int)
    fun brightness(): Float
    fun setBrightness(v: Float)
    fun volume(): Float
    fun setVolume(v: Float)
    fun setSleep(minutes: Int)
    fun refreshMatches()
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerActions {

    private lateinit var prefs: Prefs
    private lateinit var host: PlayerHost
    private var sleepJob: Job? = null
    private var matchJob: Job? = null
    private var rotationJob: Job? = null
    private var pendingStart: Int? = null
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlayQueue.items.isEmpty()) { finish(); return }
        prefs = Prefs(this)
        com.zapline.iptv.ui.Palette.setAccent(androidx.compose.ui.graphics.Color(prefs.accent))
        val main = Pane(this, prefs, 0)
        host = PlayerHost(prefs, main) { slot -> Pane(this, prefs, slot) }
        main.onEnded = {
            if (main.index + 1 < main.items.size) { if (host.casting || host.pip) main.step(1) else host.startUpNext() }
        }
        pendingStart = savedInstanceState?.getInt("index") ?: PlayQueue.index

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        host.caster = Caster(this) { connected -> if (connected) host.beginCasting() else host.endCasting() }.takeIf { it.available }
        applyOrientation(host.orientation)
        setContent { ZaplineTheme { PlayerRoot(host, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Opened again (e.g. while in picture-in-picture): play the newly chosen channel.
        if (!::host.isInitialized) return
        host.closeSecond()
        host.surf = false
        host.main.play(PlayQueue.index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::host.isInitialized) outState.putInt("index", host.main.index)
    }

    override fun onStart() {
        super.onStart()
        if (!::host.isInitialized || PlayQueue.items.isEmpty()) return
        host.main.create()
        host.main.play(pendingStart ?: host.main.index)
        pendingStart = null
        host.second?.let { it.create(); it.play(it.index, recordRecent = false) }
        host.setAudio(host.audioSlot)
        host.caster?.start()
        com.zapline.iptv.data.Health.watching = true
    }

    override fun onStop() {
        super.onStop()
        if (!::host.isInitialized) return
        host.caster?.stop()
        com.zapline.iptv.data.Health.watching = false
        pendingStart = host.main.index
        host.main.release()
        host.second?.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepJob?.cancel(); matchJob?.cancel(); rotationJob?.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ picture-in-picture

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(host.main.playing && !host.multi).setSeamlessResizeEnabled(true)
        return b.build()
    }

    override fun enterPip() {
        host.panel = Panel.NONE
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
    }

    /** Leaving with the home button while something plays drops into picture-in-picture, like YouTube. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::host.isInitialized && Build.VERSION.SDK_INT < 31 && host.main.playing && !host.multi) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        host.pip = isInPictureInPictureMode
        if (isInPictureInPictureMode) { host.controls = false; host.panel = Panel.NONE }
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized && Build.VERSION.SDK_INT >= 31) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ actions

    override fun exit() = finish()

    /** Flips portrait/landscape; after a few seconds rotation follows the phone again. */
    /** Rotate button: turns the picture and locks it that way until changed (Player settings → Rotation → Auto to undo). */
    override fun toggleRotation() {
        val portraitNow = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        setOrientation(if (portraitNow) 1 else 2)
        host.main.status = if (portraitNow) "Locked to landscape" else "Locked to portrait"
    }

    override fun setOrientation(mode: Int) {
        host.orientation = mode
        prefs.playerOrientation = mode
        applyOrientation(mode)
    }

    private fun applyOrientation(mode: Int) {
        rotationJob?.cancel()
        requestedOrientation = when (mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE   // either landscape side, never portrait
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    override fun brightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0) 0.5f else b
    }

    override fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.02f, 1f) }
    }

    override fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v.coerceIn(0f, 1f) * max), 0)
    }

    /** 0 cancels; -1 means "when the current programme ends". */
    override fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes == 0) { host.sleepAt = 0; return }
        val at = if (minutes < 0) host.nowNext().first?.end ?: return else System.currentTimeMillis() + minutes * 60_000L
        host.sleepAt = at
        sleepJob = lifecycleScope.launch {
            delay((at - System.currentTimeMillis()).coerceAtLeast(0))
            host.main.player?.pause()
            host.second?.player?.pause()
            host.sleepAt = 0
            host.main.status = "Sleep timer ended, playback paused"
        }
    }

    /**
     * Match stats. Today's games are swept once (cached 10 min) only to identify the game on screen;
     * after that, only that game is refreshed: one request every 20 s with the panel open, 30 s otherwise,
     * stopping when the game ends or the channel isn't showing one.
     */
    override fun refreshMatches() {
        if (matchJob?.isActive == true && host.matches.isNotEmpty()) return
        matchJob?.cancel()
        matchJob = lifecycleScope.launch {
            if (host.matches.isEmpty()) {
                host.matchesState = "loading"
                val list = try { Sports.today() } catch (_: Exception) { emptyList() }
                host.matches = list
                host.matchesState = if (list.isEmpty()) "failed" else "ready"
            }
            while (true) {
                val m = host.currentMatch()
                if (m == null || m.state == "post") {
                    if (host.panel != Panel.MATCH) break
                } else {
                    Sports.single(m)?.let { fresh -> host.matches = host.matches.map { if (it.id == fresh.id) fresh else it } }
                }
                delay(if (host.panel == Panel.MATCH) 20_000 else 30_000)
            }
        }
    }
}
