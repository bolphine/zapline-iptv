package com.zapline.iptv

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.StreamInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class HealthUiTest : UiTestBase() {
    @Test fun badgesFilterAndBestVersionFirst() {
        val f = File(ctx.cacheDir, "h.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 group-title="UK| General",BBC One FHD
            http://h/fhd.ts
            #EXTINF:-1 group-title="UK| General",BBC One HD
            http://h/hd.ts
            #EXTINF:-1 group-title="UK| General",Dead Channel
            http://h/dead.ts
            #EXTINF:-1 group-title="UK| General",Fresh Channel
            http://h/fresh.ts
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "h.m3u"; langFilter = emptySet(); regionFilter = emptySet() }
        val now = System.currentTimeMillis()
        File(ctx.filesDir, "health.jsonl").writeText(listOf(
            StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "HTTP 404").toJson().put("u", "http://h/fhd.ts"),
            StreamInfo(StreamInfo.OK, 1920, 1080, videoCodec = "H.264", audioCodec = "AAC", audioChannels = 6, checkedAt = now).toJson().put("u", "http://h/hd.ts"),
            StreamInfo(StreamInfo.DEAD, checkedAt = now, note = "Timed out").toJson().put("u", "http://h/dead.ts"),
        ).joinToString("\n"))

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("BBC One")
            waitForText("1080p")
            compose.onNodeWithText("5.1").assertExists()
            waitForText("Not working")
            // Working only
            compose.onNodeWithText("Status").performClick()
            waitForText("Working  1")
            compose.onNodeWithText("Working  1").performClick()
            waitFor(5_000, "dead hidden") { gone("Dead Channel") }
            assertTrue(gone("Fresh Channel"))
            compose.onAllNodesWithText("BBC One").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
            // the working HD version is listed first, the dead FHD last
            compose.onNodeWithText("2×").performSemanticsAction(SemanticsActions.OnClick)
            waitFor(5_000, "sheet") { compose.onAllNodesWithText("best working one first", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitFor(5_000, "details") { compose.onAllNodesWithText("1080p · H.264 · AAC 5.1", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val hdTop = compose.onAllNodesWithText("BBC One HD").fetchSemanticsNodes().first().boundsInRoot.top
            val fhdTop = compose.onAllNodesWithText("BBC One FHD").fetchSemanticsNodes().first().boundsInRoot.top
            assertTrue("HD ($hdTop) above FHD ($fhdTop)", hdTop < fhdTop)
        }
    }
}
