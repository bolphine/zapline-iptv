package com.zapline.iptv.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View

/** Helpers for the blurred "ambient" backdrop around a picture that doesn't fill the screen. */
object Ambient {
    const val COLS = 6
    const val ROWS = 4

    /**
     * The frame reduced to a [COLS]×[ROWS] grid of average colours (RGB floats, row by row), black bars
     * removed first. Far too coarse to show faces or detail: it's only the light and colour of each region.
     * Null for a fully black frame.
     */
    fun grid(src: Bitmap): FloatArray? {
        val b = withoutBars(src) ?: return null
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(COLS * ROWS * 3)
        for (gy in 0 until ROWS) for (gx in 0 until COLS) {
            val x0 = gx * w / COLS; val x1 = maxOf(x0 + 1, (gx + 1) * w / COLS)
            val y0 = gy * h / ROWS; val y1 = maxOf(y0 + 1, (gy + 1) * h / ROWS)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) {
                val c = px[y * w + x]; r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
            }
            val i = (gy * COLS + gx) * 3
            if (n > 0) { out[i] = r.toFloat() / n; out[i + 1] = g.toFloat() / n; out[i + 2] = bl.toFloat() / n }
        }
        return out
    }

    /** Moves [current] a fraction [k] (0..1) of the way to [target]. */
    fun ease(current: FloatArray, target: FloatArray, k: Float) {
        for (i in current.indices) current[i] += (target[i] - current[i]) * k
    }

    /** The grid as a tiny bitmap, to be drawn stretched (bilinear) and blurred. */
    fun toBitmap(grid: FloatArray): Bitmap {
        val px = IntArray(COLS * ROWS) { i ->
            Color.rgb(grid[i * 3].toInt().coerceIn(0, 255), grid[i * 3 + 1].toInt().coerceIn(0, 255), grid[i * 3 + 2].toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, COLS, ROWS, Bitmap.Config.ARGB_8888)
    }

    /** Brightness (0..255) under which a pixel counts as black. */
    private const val BLACK = 24
    private const val W = 64
    private const val H = 36

    /**
     * Grabs a small copy of the current frame from whichever view the player draws into.
     * TextureView: read directly. SurfaceView: PixelCopy (Android 7+), which works for video
     * where reading the view itself would only give black.
     */
    fun sample(surface: View?, onResult: (Bitmap?) -> Unit) {
        try {
            when (surface) {
                is TextureView -> onResult(if (surface.isAvailable) surface.getBitmap(W, H) else null)
                is SurfaceView -> {
                    val valid = surface.holder.surface?.isValid == true && surface.width > 0 && surface.height > 0
                    if (!valid) return onResult(null)
                    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(surface, bmp, { r -> onResult(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
                }
                else -> onResult(null)
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    /**
     * Crops black bars (letterbox top/bottom, pillarbox sides) off a sampled frame so they don't
     * become the backdrop. A row/column counts as a bar only if almost every pixel in it is black,
     * so dark scenes are kept. Returns null only for a completely black frame (fade to black), so the
     * previous colours stay meanwhile.
     */
    fun withoutBars(src: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        if (w < 4 || h < 4) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        if (px.all { luma(it) < 6 }) return null
        fun rowLit(y: Int): Boolean { var n = 0; for (x in 0 until w) if (luma(px[y * w + x]) > BLACK) n++; return n * 20 > w }        // > 5% lit
        fun colLit(x: Int, top: Int, bottom: Int): Boolean { var n = 0; for (y in top..bottom) if (luma(px[y * w + x]) > BLACK) n++; return n * 20 > (bottom - top + 1) }
        var top = 0; while (top < h && !rowLit(top)) top++
        if (top == h) return src            // dark scene, but not black: keep its colours
        var bottom = h - 1; while (bottom > top && !rowLit(bottom)) bottom--
        var left = 0; while (left < w - 1 && !colLit(left, top, bottom)) left++
        var right = w - 1; while (right > left && !colLit(right, top, bottom)) right--
        // Step one more row/column in: bar edges are often a soft grey line.
        if (top > 0) top = minOf(top + 1, bottom)
        if (bottom < h - 1) bottom = maxOf(bottom - 1, top)
        if (left > 0) left = minOf(left + 1, right)
        if (right < w - 1) right = maxOf(right - 1, left)
        val cw = right - left + 1; val ch = bottom - top + 1
        if (cw == w && ch == h) return src
        if (cw < 4 || ch < 3) return src
        return Bitmap.createBitmap(src, left, top, cw, ch)
    }
}
