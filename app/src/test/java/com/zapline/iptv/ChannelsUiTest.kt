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
import androidx.compose.ui.test.performScrollTo
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

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ChannelsUiTest : UiTestBase() {

    @Test fun loadsFiltersSearchesAndNavigates() {
        val f = File(ctx.cacheDir, "test.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 group-title="##### UK #####",##### UK CHANNELS #####
            http://h/live/u/p/0.ts
            #EXTINF:-1 tvg-id="BBCOne.uk" group-title="UK | ENTERTAINMENT",BBC One HD
            http://h/live/u/p/1.ts
            #EXTINF:-1 group-title="UK | NEWS",Sky News
            http://h/live/u/p/2.ts
            #EXTINF:-1 tvg-id="BBCOne.uk" group-title="UK | ENTERTAINMENT",UK: BBC One SD
            http://h/live/u/p/7.ts
            #EXTINF:-1 group-title="AR | NEWS",Al Jazeera Arabic
            http://h/live/u/p/3.ts
            #EXTINF:-1 group-title="FR | INFO",France 24 English
            http://h/live/u/p/4.ts
            #EXTINF:-1 group-title="USA | NEWS",CNN
            http://h/live/u/p/6.ts
            #EXTINF:-1 group-title="MOVIES",Some Film
            http://h/movie/u/p/5.mkv
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "test.m3u" }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("BBC One")
            compose.onNodeWithText("Sky News").assertIsDisplayed()
            compose.onNodeWithText("France 24 English").assertIsDisplayed()   // name says English, beats FR group
            assert(gone("Al Jazeera Arabic")) { "Arabic channel should be filtered out by default" }
            assert(gone("##### UK CHANNELS #####")) { "separator should be removed" }
            assert(gone("Some Film")) { "VOD should be removed" }
            compose.onNodeWithText("CNN").assertIsDisplayed()
            compose.onNodeWithText("4 channels (1 duplicate merged)").assertExists()
            compose.onNodeWithText("2×").assertExists()   // BBC One HD + SD collapsed
            compose.onNodeWithText("News").assertExists()
            compose.onNodeWithText("News").performClick()
            waitFor(5_000, "news category") { gone("BBC One") }
            compose.onNodeWithText("Everything").performClick()
            waitForText("BBC One")

            // country filter: UK only drops the US and French-grouped English channels
            compose.onNodeWithText("All countries").performClick()
            waitForText("Countries")
            compose.onNodeWithText("UK only").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(SemanticsActions.OnClick)
            waitFor(5_000, "UK filter applied") { gone("CNN") && gone("France 24 English") }
            waitFor(5_000, "country sheet closed") { gone("Countries") }
            compose.onNodeWithText("Sky News").assertIsDisplayed()
            compose.onNodeWithText("2 channels (1 duplicate merged)").assertExists()
            compose.onNodeWithText("United Kingdom").performClick()
            waitForText("Countries")
            compose.onNodeWithText("All").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("CNN")
            waitFor(5_000, "country sheet closed") { gone("Countries") }

            // search
            compose.onNode(hasSetTextAction()).performTextInput("sky")
            waitFor(5_000) { gone("BBC One") }
            compose.onNodeWithText("Sky News").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextClearance()
            waitForText("BBC One")

            // language sheet -> show all
            compose.onNodeWithText("English").performClick()
            waitForText("Languages")
            compose.onNodeWithText("All").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Al Jazeera Arabic")
            compose.onNodeWithText("English only").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(SemanticsActions.OnClick)
            waitFor(5_000, "Arabic hidden again") { gone("Al Jazeera Arabic") }
            waitFor(5_000, "sheet closed") { gone("Languages") }

            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Organise channels")
            compose.onNodeWithText("Groups & languages").performScrollTo().performClick()
            waitForText("AR | NEWS")
            compose.onNodeWithText("UK | ENTERTAINMENT").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Organise channels")

            // rules screen opens and validates
            compose.onNodeWithText("Language rules").performScrollTo().performClick()
            waitForText("0 rules")
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Organise channels")
            compose.onNodeWithText("Subtitles").assertExists()        // playback defaults live in Settings
            // Accent colour: tapping a swatch recolours the app and is remembered
            compose.onNodeWithContentDescription("Sky accent").performScrollTo().performClick()
            waitFor(5_000, "accent applied") { com.zapline.iptv.ui.Palette.Tally == androidx.compose.ui.graphics.Color(0xFF5CC8FF) }
            assertEquals(0xFF5CC8FF.toInt(), Prefs(ctx).accent)
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("BBC One")

            // tapping a channel opens the player with the filtered list
            compose.onNodeWithText("BBC One").performClick()
            scenario.onActivity { a ->
                val next = shadowOf(a).nextStartedActivity
                assertEquals(PlayerActivity::class.java.name, next.component?.className)
            }
            assertEquals(4, PlayQueue.items.size)
            assertEquals("BBC One", PlayQueue.items[PlayQueue.index].name)
            assertEquals(listOf("BBC One HD", "UK: BBC One SD"), PlayQueue.items[PlayQueue.index].variants.map { it.name })
        }
    }
}
