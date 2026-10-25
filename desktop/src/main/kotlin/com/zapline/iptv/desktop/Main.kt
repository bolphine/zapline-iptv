package com.zapline.iptv.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zapline.iptv.data.Health
import com.zapline.iptv.desktop.data.DesktopPlatform
import com.zapline.iptv.desktop.ui.AppShell
import com.zapline.iptv.desktop.ui.AppState
import com.zapline.iptv.desktop.ui.Mode
import com.zapline.iptv.desktop.ui.Notifier
import com.zapline.iptv.desktop.ui.Palette
import com.zapline.iptv.desktop.ui.Section
import com.zapline.iptv.desktop.ui.ZaplineDesktopTheme
import com.zapline.iptv.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    // Menus, tooltips and dialogs as their own little windows, so they can appear over the video
    // (which is a native window that ordinary drawing can't cover).
    System.setProperty("compose.layers.type", "WINDOW")
    val platform = DesktopPlatform()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    application {
        val app = remember {
            Palette.setAccent(Color(platform.prefs.accent))
            AppState(MainViewModel(platform, scope), platform, scope).also { it.startReminderWatch() }
        }
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1600.dp, 900.dp))
        LaunchedEffect(app.mode) {
            state.placement = if (app.mode == Mode.FULLSCREEN) WindowPlacement.Fullscreen
            else if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else state.placement
        }
        Window(
            onCloseRequest = {
                app.playback.releaseAll()
                Notifier.remove()
                exitApplication()
            },
            state = state,
            title = "Zapline",
            icon = painterResource("icon.png"),
            onKeyEvent = { handleKey(app, it) },
        ) {
            ZaplineDesktopTheme { AppShell(app) }
        }
    }
    Health.watching = false
}

/** Shortcuts while the app (not the picture) has focus; the picture handles its own through mpv. */
private fun handleKey(app: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val t = app.playback.main
    if (e.isCtrlPressed && e.key == Key.F) { app.mode = if (app.mode == Mode.FULLSCREEN) Mode.BROWSE else app.mode; app.section = Section.SEARCH; return true }
    when (e.key) {
        Key.Escape -> app.escape()
        Key.F, Key.F11 -> app.toggleFullscreen()
        Key.T -> app.toggleTheatre()
        Key.V -> if (app.mode == Mode.MULTIVIEW) app.exitMultiview() else app.enterMultiview()
        Key.Z -> app.cycleFit()
        Key.Spacebar -> t.player.togglePause()
        Key.M -> t.player.setMuted(!t.player.state.muted)
        Key.PageUp, Key.DirectionUp -> if (t.entry != null) t.step(-1) else return false
        Key.PageDown, Key.DirectionDown -> if (t.entry != null) t.step(1) else return false
        Key.DirectionLeft -> if (t.isVod) t.player.seekRelative(-10.0) else return false
        Key.DirectionRight -> if (t.isVod) t.player.seekRelative(10.0) else return false
        Key.Backspace -> t.lastChannel()
        Key.G -> { app.mode = Mode.BROWSE; app.section = Section.GUIDE }
        Key.L -> { app.mode = Mode.BROWSE; app.section = Section.LIVE }
        Key.I -> app.onVideoMessage(0, listOf("zapline", "info"))
        Key.A -> app.onVideoMessage(0, listOf("zapline", "audio"))
        Key.C -> app.onVideoMessage(0, listOf("zapline", "subtitles"))
        else -> {
            val d = digitOf(e.key) ?: return false
            if (t.entry != null) app.digit(d) else return false
        }
    }
    return true
}

private fun digitOf(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> null
}
