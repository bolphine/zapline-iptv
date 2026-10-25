package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.zapline.iptv.desktop.player.Mpv
import com.zapline.iptv.desktop.player.MpvPlayer
import com.zapline.iptv.desktop.player.Tile
import java.awt.Canvas

/** Set when there's no screen (tests, screenshots): the video area is drawn as a placeholder. */
object VideoHost {
    val headless: Boolean = java.awt.GraphicsEnvironment.isHeadless() || System.getProperty("zapline.noVideo") == "true"
}

/** A native window for mpv to draw into; tells us when it gets (or loses) its window handle. */
private class VideoCanvas(private val onReady: (Long) -> Unit, private val onGone: () -> Unit) : Canvas() {
    init { background = java.awt.Color.BLACK }
    override fun addNotify() { super.addNotify(); onReady(Native.getComponentID(this)) }
    override fun removeNotify() { onGone(); super.removeNotify() }
}

/**
 * The picture for one [Tile]. mpv draws straight into a native window, so nothing in Compose can be drawn
 * over it: controls sit around the picture instead (and the video's own keys come back via [onMessage]).
 */
@Composable
fun VideoSurface(tile: Tile, fit: Fit, modifier: Modifier, playerOptions: Map<String, String>, onMessage: (List<String>) -> Unit) {
    tile.player.onMessage = onMessage
    var size by remember { mutableStateOf(IntSize.Zero) }
    if (VideoHost.headless || !tile.player.available) {
        Box(modifier.background(Color.Black).onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
            if (!VideoHost.headless && !Mpv.available) {
                Text("The video engine (libmpv-2.dll) is missing from Zapline's folder.\n${Mpv.loadError ?: ""}",
                    color = Palette.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            } else tile.entry?.let { e ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(e.name, style = MaterialTheme.typography.headlineSmall, color = Palette.Muted)
                    Text("video", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Faint)
                }
            }
        }
        return
    }
    val canvas = remember(tile) {
        VideoCanvas(
            onReady = { wid ->
                tile.player.init(wid, MpvPlayer.inputConf, playerOptions)
                // Re-created (layout change): pick up what was playing.
                if (tile.entry != null) tile.retry()
            },
            onGone = { tile.saveProgress(); tile.player.destroy() },
        )
    }
    Box(modifier.onSizeChanged { size = it }) {
        SwingPanel(background = Color.Black, factory = { canvas }, modifier = androidx.compose.ui.Modifier.matchParentSize())
        val st = tile.player.state
        // Spinner and errors go *beside* nothing: they'd be hidden under the video, so they're shown by mpv's OSD.
        LaunchedEffect(st.buffering, st.error) {
            when {
                st.error != null -> tile.player.showText(st.error, 6_000)
                st.buffering && tile.entry != null -> tile.player.showText("Buffering…", 1_500)
            }
        }
    }
    LaunchedEffect(fit, size, tile.player.state.width, tile.player.state.height) {
        if (size.width > 0) tile.player.setFit(fit.key, size.width, size.height)
    }
}

/** Spinner used in Compose areas next to the picture. */
@Composable
fun SmallSpinner() = CircularProgressIndicator(Modifier.padding(2.dp), color = Palette.Tally, strokeWidth = 2.dp)
