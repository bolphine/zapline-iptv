package com.zapline.iptv

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.isRoot

import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VodUiTest : UiTestBase() {

    @Test fun moviesAndSeriesLiveInTheirOwnSections() {
        val f = File(ctx.cacheDir, "vod.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 group-title="UK| General",BBC One
            http://h/live/u/p/1.ts
            #EXTINF:-1 tvg-logo="http://i/heat.jpg" group-title="EN| Action",Heat (1995)
            http://h/movie/u/p/10.mkv
            #EXTINF:-1 group-title="FR| Films",Amelie
            http://h/movie/u/p/11.mp4
            #EXTINF:-1 group-title="Drama",Line of Duty S01 E01
            http://h/series/u/p/20.mkv
            #EXTINF:-1 group-title="Drama",Line of Duty S01 E02
            http://h/series/u/p/21.mkv
            #EXTINF:-1 group-title="Drama",Line of Duty S02 E01
            http://h/series/u/p/22.mkv
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "vod.m3u" }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("BBC One")
            assert(gone("Heat (1995)")) { "movies must not appear in Live TV" }
            assert(gone("Line of Duty S01 E01")) { "episodes must not appear in Live TV" }

            // Movies tab: English + untagged shown, French filtered by the default English filter
            compose.onNodeWithText("Movies").performClick()
            waitForText("Search movies")
            waitFor(5_000, "movie poster") { compose.onAllNodesWithText("Heat (1995)").fetchSemanticsNodes().isNotEmpty() }
            assert(gone("Amelie")) { "French movie should follow the language filter" }
            compose.onNodeWithText("1 movie").assertExists()

            // Series tab: three episodes become one show with two seasons
            compose.onNodeWithText("Series").performClick()
            waitForText("Search shows")
            waitFor(5_000, "show") { compose.onAllNodesWithText("Line of Duty").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Line of Duty")[0].performClick()
            waitForText("2 seasons, 3 episodes")
            compose.onNodeWithText("Season 2").assertExists()
            compose.onNodeWithText("Episode 2").performClick()
            scenario.onActivity { a ->
                assertEquals(PlayerActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
            }
            assertEquals(2, PlayQueue.items.size)          // season 1 queued so the next episode auto-plays
            assertEquals(1, PlayQueue.index)
            assertTrue(PlayQueue.items[1].key.startsWith("vod|"))

            // back to the show list, then Live TV still intact
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Search shows")
            compose.onNodeWithText("Live TV").performClick()
            waitForText("BBC One")
        }
    }
}
