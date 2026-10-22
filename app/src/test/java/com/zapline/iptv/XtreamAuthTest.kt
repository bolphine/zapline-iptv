package com.zapline.iptv

import android.content.Context
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.XtreamAuth
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XtreamAuthTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun xtream() = Prefs(ctx).apply {
        p.edit().clear().commit()
        sourceType = "xtream"; xtServer = "http://h.tv:8080"; xtUser = "new"; xtPass = "pw2"
    }

    @Test fun oldLinksUseTheCurrentLogin() {
        val p = xtream()
        assertEquals("http://h.tv:8080/movie/new/pw2/55.mkv", XtreamAuth.withCurrentLogin("http://h.tv:8080/movie/old/pw1/55.mkv", p))
        assertEquals("http://h.tv:8080/live/new/pw2/1.ts", XtreamAuth.withCurrentLogin("http://h.tv:8080/live/old/pw1/1.ts", p))
        assertEquals("http://h.tv:8080/timeshift/new/pw2/60/2026-10-21:18-00/1.ts",
            XtreamAuth.withCurrentLogin("http://h.tv:8080/timeshift/old/pw1/60/2026-10-21:18-00/1.ts", p))
        assertEquals("http://h.tv:8080/live/new/pw2/1.ts", XtreamAuth.withCurrentLogin("http://h.tv:8080/live/new/pw2/1.ts", p))
        assertEquals("non-Xtream links untouched", "http://cdn.x/a/b.m3u8", XtreamAuth.withCurrentLogin("http://cdn.x/a/b.m3u8", p))
        p.sourceType = "m3u"
        assertEquals("only for Xtream sources", "http://h.tv:8080/live/old/pw1/1.ts", XtreamAuth.withCurrentLogin("http://h.tv:8080/live/old/pw1/1.ts", p))
    }

    @Test fun loginReplies() {
        assertTrue(XtreamAuth.parse("""{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800"}}""").ok)
        assertFalse(XtreamAuth.parse("""{"user_info":{"auth":0}}""").ok)
        assertEquals("Your subscription has expired.", XtreamAuth.parse("""{"user_info":{"auth":1,"status":"Expired"}}""").message)
        assertEquals("Your subscription has expired.", XtreamAuth.parse("""{"user_info":{"auth":1,"status":"Active","exp_date":"1600000000"}}""").message)
        assertFalse(XtreamAuth.parse("<html>nope</html>").ok)
        assertTrue(XtreamAuth.isRefusal(460)); assertTrue(XtreamAuth.isRefusal(406)); assertTrue(XtreamAuth.isRefusal(458))
        assertFalse(XtreamAuth.isRefusal(404)); assertFalse(XtreamAuth.isRefusal(500))
    }
}

/** The player signs in again, then tries other player identities, when a provider refuses a stream with an odd code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RefusalRecoveryUiTest : UiTestBase() {
    @Test fun signsInAgainThenTriesOtherIdentities() {
        val hits = Collections.synchronizedList(ArrayList<String>())
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader()
                        val path = r.readLine()?.split(' ')?.getOrNull(1) ?: return@thread
                        var ua = ""; var h: String?
                        do { h = r.readLine(); if (h?.startsWith("User-Agent:", true) == true) ua = h.substringAfter(':').trim() } while (!h.isNullOrEmpty())
                        hits += "$path|$ua"
                        val (code, body) = if (path.startsWith("/player_api.php")) "200 OK" to """{"user_info":{"auth":1,"status":"Active"}}"""
                            else "460 Refused" to "no"
                        it.getOutputStream().write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    }
                }
            }
        }
        val server = "http://127.0.0.1:${ss.localPort}"
        Prefs(ctx).apply { sourceType = "xtream"; xtServer = server; xtUser = "u"; xtPass = "p" }
        // Saved under an old password: the player must use the current one.
        PlayQueue.set(listOf(PlayEntry("k", "BBC One", listOf(Channel("BBC One", "$server/live/u/OLD/1.ts", "UK")))), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use {
            waitFor(30_000, "refusal message", advanceMs = 200) {
                compose.onAllNodesWithText("even after signing in again", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val streams = hits.filter { it.startsWith("/live/") }
        assertTrue(hits.toString(), streams.isNotEmpty() && streams.all { it.startsWith("/live/u/p/1.ts") })
        assertTrue("signed in: $hits", hits.any { it.startsWith("/player_api.php?username=u&password=p") })
        val uas = streams.map { it.substringAfter('|') }.distinct()
        assertTrue("tried other identities: $uas", uas.size >= XtreamAuth.userAgents.size)
        ss.close()
    }
}
