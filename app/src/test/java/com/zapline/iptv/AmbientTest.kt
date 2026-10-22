package com.zapline.iptv

import android.graphics.Bitmap
import android.graphics.Color
import com.zapline.iptv.player.Ambient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientTest {
    private fun frame(w: Int, h: Int, lit: (Int, Int) -> Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if (lit(x, y)) Color.rgb(200, 90, 40) else Color.BLACK)
        return b
    }

    @Test fun letterboxBarsAreCut() {
        // 2.39:1 film in a 16:9 frame: black rows top and bottom
        val out = Ambient.withoutBars(frame(64, 36) { _, y -> y in 5..30 })!!
        assertEquals(64, out.width)
        assertEquals(24, out.height)                      // rows 6..29
        assertEquals(Color.rgb(200, 90, 40), out.getPixel(0, 0))
        assertEquals(Color.rgb(200, 90, 40), out.getPixel(63, 23))
    }

    @Test fun pillarboxBarsAreCut() {
        val out = Ambient.withoutBars(frame(64, 36) { x, _ -> x in 8..55 })!!
        assertEquals(36, out.height)
        assertEquals(46, out.width)
    }

    @Test fun darkSceneKeepsItsColours() {
        val dark = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 14, 30)) }
        assertSame("a dim old film is not a black bar", dark, Ambient.withoutBars(dark))
    }

    @Test fun fullFrameUntouchedAndBlackFrameSkipped() {
        val full = frame(64, 36) { _, _ -> true }
        assertSame(full, Ambient.withoutBars(full))
        assertNull(Ambient.withoutBars(frame(64, 36) { _, _ -> false }))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientGridTest {
    @Test fun gridAveragesRegionsAndIgnoresBars() {
        // Letterboxed frame: left half red, right half blue, black bars top and bottom.
        val b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        for (y in 0 until 36) for (x in 0 until 64)
            b.setPixel(x, y, if (y < 6 || y > 29) Color.BLACK else if (x < 32) Color.rgb(220, 20, 20) else Color.rgb(20, 20, 220))
        val g = Ambient.grid(b)!!
        assertEquals(Ambient.COLS * Ambient.ROWS * 3, g.size)
        // top-left cell is red, bottom-right cell is blue: bars didn't darken the edges
        assertEquals(220f, g[0], 1f); assertEquals(20f, g[2], 1f)
        val last = (Ambient.ROWS * Ambient.COLS - 1) * 3
        assertEquals(20f, g[last], 1f); assertEquals(220f, g[last + 2], 1f)
    }

    @Test fun easingGlidesInsteadOfJumping() {
        val cur = floatArrayOf(0f, 0f, 0f); val target = floatArrayOf(200f, 100f, 50f)
        Ambient.ease(cur, target, 0.1f)
        assertEquals(20f, cur[0], 0.01f); assertEquals(10f, cur[1], 0.01f)
        repeat(100) { Ambient.ease(cur, target, 0.1f) }
        assertEquals(200f, cur[0], 0.5f)
    }
}
