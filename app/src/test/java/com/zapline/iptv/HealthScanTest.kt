package com.zapline.iptv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.StreamInfo
import com.zapline.iptv.model.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A fake provider that behaves like a strict IPTV panel: it keeps counting a connection for [linger] ms after
 * it closes and refuses (HTTP 460) anything that arrives meanwhile. The check must pace itself, never open two
 * connections at once, and not call working channels dead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthScanTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var ss: ServerSocket
    private val open = AtomicInteger(0)
    private val maxOpen = AtomicInteger(0)
    private val refused = AtomicInteger(0)
    @Volatile private var lastClosed = 0L
    @Volatile private var responseDelay = 0L
    private val linger = 2_000L

    private fun ts(): ByteArray {
        val out = ByteArrayOutputStream()
        fun pkt(pid: Int, pusi: Boolean, payload: ByteArray) = ByteArray(188) { 0xFF.toByte() }.also { p ->
            p[0] = 0x47; p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte(); p[2] = (pid and 0xFF).toByte(); p[3] = 0x10
            System.arraycopy(payload, 0, p, 4, minOf(payload.size, 184))
        }
        val pat = byteArrayOf(0, 0, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0, 0, 0, 0, 0)
        val pmt = byteArrayOf(0, 2, 0xB0.toByte(), 18, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0, 0xF0.toByte(), 0, 0x1B, 0xE1.toByte(), 0, 0xF0.toByte(), 0, 0, 0, 0, 0)
        repeat(300) { out.write(pkt(0, true, pat)); out.write(pkt(0x1000, true, pmt)); out.write(pkt(0x100, false, ByteArray(184))) }
        return out.toByteArray()
    }

    @Before fun setUp() {
        ctx.getSharedPreferences("zapline", 0).edit().clear().commit()
        Health.resetForTests()
        Health.confirmGapMs = 1_500
        val body = ts()
        ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    val n = open.incrementAndGet(); maxOpen.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    try {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return@thread
                            while (!r.readLine().isNullOrEmpty()) {}
                            val o = it.getOutputStream()
                            if (open.get() > 1 || System.currentTimeMillis() - lastClosed < linger) {
                                refused.incrementAndGet()
                                o.write("HTTP/1.1 460 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                return@thread
                            }
                            if (responseDelay > 0) Thread.sleep(responseDelay)
                            if (path.startsWith("/dead")) { o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@thread }
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            o.write(body); o.flush()
                        }
                    } catch (_: Exception) {
                    } finally { open.decrementAndGet(); lastClosed = System.currentTimeMillis() }
                }
            }
        }
    }

    @After fun tearDown() { Health.stopScan(); ss.close(); Health.confirmGapMs = 2 * 60_000L }

    private fun channels(vararg names: String) = names.map { it to Channel(it, "http://127.0.0.1:${ss.localPort}/$it.ts", "UK") }

    private fun waitDone(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val st = Health.scan.value
            if (!st.running && st.finishedAt > 0) return
            Thread.sleep(100)
        }
        throw AssertionError("scan didn't finish: ${Health.scan.value}")
    }

    @Test fun pacesItselfAndConfirmsFailuresBeforeCallingThemDead() {
        val list = channels("a", "b", "c", "d", "dead1")
        Health.startScan(list, Prefs(ctx), force = true)
        waitDone(120_000)
        assertNull("never gave up", Health.scan.value.paused)
        assertEquals("never two connections at once", 1, maxOpen.get())
        for (n in listOf("a", "b", "c", "d")) assertTrue("$n works: ${Health[list.first { it.first == n }.second.url]}", Health[list.first { it.first == n }.second.url]!!.ok)
        val dead = Health[list.last().second.url]!!
        assertEquals("dead only after a second failure", StreamInfo.DEAD, dead.state)
        assertEquals(2, dead.failures)
    }

    @Test fun stopThenStartWorks() {
        responseDelay = 3_000
        val list = channels("a", "b", "c")
        Health.startScan(list, Prefs(ctx), force = true)
        Thread.sleep(1_000)                      // first probe is mid-request
        Health.stopScan()
        assertTrue(!Health.scan.value.running)
        responseDelay = 0
        Thread.sleep(2_500)                      // let the provider forget the cancelled connection
        Health.startScan(list, Prefs(ctx), force = true)
        waitDone(90_000)
        val st = Health.scan.value
        assertEquals(3, st.done); assertEquals(3, st.working)
        assertEquals("never two connections at once", 1, maxOpen.get())
    }
}
