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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import com.zapline.iptv.data.EpgCache

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Programme
import com.zapline.iptv.player.PlayEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class GuideUiTest : UiTestBase() {

    private fun guideAround(now: Long) = EpgData(
        mapOf(
            "BBCOne.uk" to listOf(
                Programme(now - 30 * 60_000, now + 30 * 60_000, "Morning Show", "Chat and news."),
                Programme(now + 30 * 60_000, now + 90 * 60_000, "Bargain Hunt", null),
            ),
            "SkyNews.uk" to listOf(Programme(now - 10 * 60_000, now + 50 * 60_000, "Sky News Today", null)),
        ),
        emptyMap(), now,
    )

    @Test fun nowNextInListAndGuideGrid() {
        val f = File(ctx.cacheDir, "g.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="BBCOne.uk" group-title="UK| General",BBC One
            http://h/live/u/p/1.ts
            #EXTINF:-1 tvg-id="SkyNews.uk" group-title="UK| News",Sky News
            http://h/live/u/p/2.ts
            #EXTINF:-1 group-title="UK| General",No Guide Channel
            http://h/live/u/p/3.ts
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "g.m3u" }
        EpgCache.save(File(ctx.filesDir, "epg.bin"), guideAround(System.currentTimeMillis()))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("BBC One")
            waitForText("Morning Show")                                  // now playing on the row
            waitFor(5_000, "next line") { compose.onAllNodesWithText("Bargain Hunt", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sky News Today").assertExists()

            compose.onNodeWithContentDescription("TV guide").performClick()
            waitForText("Only channels with guide")
            waitForText("Morning Show")
            assert(gone("No Guide Channel")) { "channels without guide hidden by default" }
            compose.onNodeWithText("Only channels with guide").performClick()
            waitForText("No Guide Channel")

            // a future programme opens its details
            compose.onAllNodesWithText("Bargain Hunt", substring = true)[0].performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Watch channel")
            compose.onNodeWithText("Watch channel").performClick()
            scenario.onActivity { a ->
                assertEquals(PlayerActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
            }
            assertEquals("BBCOne.uk", PlayQueue.items[PlayQueue.index].epgId)
        }
    }

    @Test fun playerBannerShowsNowAndNext() {
        val now = System.currentTimeMillis()
        EpgStore.data = guideAround(now)
        PlayQueue.set(listOf(
            PlayEntry("k1", "BBC One", listOf(Channel("BBC One", "http://127.0.0.1:9/1.ts", "UK")), "BBCOne.uk"),
            PlayEntry("k2", "Sky News", listOf(Channel("Sky News", "http://127.0.0.1:9/2.ts", "UK")), "SkyNews.uk"),
        ), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use {
            waitFor(10_000, "banner") { compose.onAllNodesWithText("Morning Show", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitFor(5_000, "next in banner") { compose.onAllNodesWithText("Bargain Hunt", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Channel list").performClick()
            waitForText("Find a channel")
            compose.onAllNodesWithText("Sky News").fetchSemanticsNodes().let { assert(it.size >= 2) }
            compose.onAllNodesWithText("Sky News")[compose.onAllNodesWithText("Sky News").fetchSemanticsNodes().size - 1].performClick()
            waitFor(10_000, "switched") { compose.onAllNodesWithText("Sky News Today", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
