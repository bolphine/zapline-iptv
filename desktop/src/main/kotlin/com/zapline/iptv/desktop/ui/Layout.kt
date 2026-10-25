package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/** A rectangle in the window. */
data class Rect(val x: Dp, val y: Dp, val w: Dp, val h: Dp) {
    val right get() = x + w
    val bottom get() = y + h
    val empty get() = w <= 0.dp || h <= 0.dp
    fun mod(): Modifier = Modifier.offset(x, y).size(max(w, 0.dp), max(h, 0.dp))
    companion object { val NONE = Rect(0.dp, 0.dp, 0.dp, 0.dp) }
}

/**
 * Where everything goes. The picture is a native window that nothing can be drawn over, so every panel is
 * placed *around* it. Tuned for 5120×1440 (Samsung G9) but works down to ordinary 16:9 screens.
 */
data class Geometry(
    val video: Rect,
    val rail: Rect? = null,
    val left: Rect? = null,
    val header: Rect? = null,
    val controls: Rect? = null,
    val side: Rect? = null,
    /** Full-width page (guide, films, sport, settings). */
    val content: Rect? = null,
    /** Strip above the mini player. */
    val miniBar: Rect? = null,
    /** Fullscreen side bars on ultrawide screens. */
    val fsLeft: Rect? = null,
    val fsRight: Rect? = null,
) {
    companion object {
        val RAIL = 76.dp
        private val PAD = 16.dp
        private val HEADER = 78.dp
        private val CONTROLS = 70.dp

        fun ultrawide(w: Dp, h: Dp) = w / h >= 2.3f

        /** Sections whose main area is the player. */
        fun playerSection(s: Section) = s == Section.LIVE || s == Section.FOURK || s == Section.SEARCH

        fun compute(mode: Mode, section: Section, playing: Boolean, fsPanels: Boolean, w: Dp, h: Dp): Geometry = when (mode) {
            Mode.FULLSCREEN -> {
                val vw = h * 16f / 9f
                if (fsPanels && w - vw >= 600.dp) {
                    val x = (w - vw) / 2
                    Geometry(video = Rect(x, 0.dp, vw, h), fsLeft = Rect(0.dp, 0.dp, x, h), fsRight = Rect(x + vw, 0.dp, w - x - vw, h))
                } else Geometry(video = Rect(0.dp, 0.dp, w, h))
            }
            Mode.THEATRE -> playerLayout(0.dp, w, h, showLeft = false, wide = ultrawide(w, h))
            Mode.MULTIVIEW -> Geometry(video = Rect.NONE)        // tiles are placed by multiview()
            Mode.BROWSE -> if (playerSection(section)) playerLayout(RAIL, w, h, showLeft = true, wide = ultrawide(w, h)) else {
                val content = Rect(RAIL, 0.dp, w - RAIL, h)
                if (playing) {
                    val mw = if (ultrawide(w, h)) 640.dp else 480.dp
                    val mh = mw * 9f / 16f
                    val v = Rect(w - PAD - mw, h - PAD - mh, mw, mh)
                    Geometry(video = v, rail = Rect(0.dp, 0.dp, RAIL, h), content = content, miniBar = Rect(v.x, v.y - 46.dp, mw, 46.dp))
                } else Geometry(video = Rect.NONE, rail = Rect(0.dp, 0.dp, RAIL, h), content = content)
            }
        }

        private fun playerLayout(x0: Dp, w: Dp, h: Dp, showLeft: Boolean, wide: Boolean): Geometry {
            val leftW = if (!showLeft) 0.dp else if (wide) 620.dp else min(440.dp, w * 0.3f)
            val cx = x0 + leftW + (if (showLeft) 0.dp else PAD)
            val avail = w - cx - PAD
            val maxVh = h - HEADER - CONTROLS - PAD * 2
            val sideMin = if (wide) 560.dp else 0.dp
            var vw = min(maxVh * 16f / 9f, avail - sideMin - (if (wide) PAD else 0.dp))
            if (vw < 320.dp) vw = avail
            val vh = vw * 9f / 16f
            val top = PAD + HEADER
            val video = Rect(cx, top, vw, vh)
            val sideX = video.right + PAD
            val sideW = w - sideX - PAD
            return Geometry(
                video = video,
                rail = if (showLeft) Rect(0.dp, 0.dp, RAIL, h) else null,
                left = if (showLeft) Rect(x0, 0.dp, leftW, h) else null,
                header = Rect(cx, PAD, vw, HEADER),
                controls = Rect(cx, video.bottom, vw, CONTROLS),
                side = if (sideW >= 360.dp) Rect(sideX, PAD, sideW, h - PAD * 2) else null,
            )
        }

        /**
         * Multiview tiles: [n] pictures side by side on an ultrawide screen (two 16:9 pictures fill a 32:9
         * screen exactly), a grid on ordinary screens. Each tile has a header strip above and info below.
         */
        fun multiview(n: Int, w: Dp, h: Dp): List<Triple<Rect, Rect, Rect>> {
            val top = 60.dp; val strip = 40.dp; val pad = 12.dp
            val cols = if (ultrawide(w, h)) n else when (n) { 1 -> 1; 2 -> 2; else -> 2 }
            val rows = (n + cols - 1) / cols
            val cellW = (w - pad * (cols + 1)) / cols
            val cellH = (h - top - pad * (rows + 1)) / rows
            val vw = min(cellW, (cellH - strip - 8.dp) * 16f / 9f)
            val vh = vw * 9f / 16f
            return (0 until n).map { i ->
                val c = i % cols; val r = i / cols
                val x = pad + (cellW + pad) * c + (cellW - vw) / 2
                val y = top + pad + (cellH + pad) * r
                val header = Rect(x, y, vw, strip)
                val video = Rect(x, y + strip, vw, vh)
                val info = Rect(x, video.bottom + 6.dp, vw, max(0.dp, y + cellH - video.bottom - 6.dp))
                Triple(header, video, info)
            }
        }
    }
}
