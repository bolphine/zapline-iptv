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
import com.zapline.iptv.data.EpgCache

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Programme
import com.zapline.iptv.player.PlayEntry
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerModesUiTest : UiTestBase() {

    private fun queue() {
        val now = System.currentTimeMillis()
        EpgStore.data = EpgData(mapOf("BBCOne.uk" to listOf(Programme(now - 600_000, now + 600_000, "Morning Show", "Chat."))), emptyMap(), now)
        PlayQueue.set(listOf(
            PlayEntry("k1", "BBC One", listOf(Channel("BBC One", "http://127.0.0.1:9/1.ts", "UK| General")), "BBCOne.uk"),
            PlayEntry("k2", "Sky News", listOf(Channel("Sky News", "http://127.0.0.1:9/2.ts", "UK| News"))),
            PlayEntry("k3", "Dave", listOf(Channel("Dave", "http://127.0.0.1:9/3.ts", "UK| General"))),
        ), 0)
    }

    @Test fun portraitLayoutSettingsMultiviewAndSurf() {
        queue()
        ActivityScenario.launch(PlayerActivity::class.java).use {
            // Upright: picture on top, programme and channel list below
            waitForText("Morning Show")
            waitForText("Channels")
            compose.onNodeWithText("Dave").assertExists()

            // Settings panel with subtitle and picture options
            compose.onNodeWithContentDescription("Player settings").performClick()
            waitForText("Player settings")
            compose.onNodeWithText("This stream doesn't carry subtitles or captions.").assertExists()
            // A real tap on a visible option (subtitle size, near the top of the sheet)
            // Robolectric offsets injected taps by the hidden status bar in this full-screen activity,
            // so in-panel options are pressed through their accessibility click action instead.
            fun press(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            press("Large")
            waitFor(5_000, "subtitle size applied") { Prefs(ctx).subtitleScale == 1.3f }
            compose.onNodeWithText("Player settings").assertExists()     // choosing an option keeps the panel open
            // An option further down the sheet, after scrolling to it
            press("Zoom")
            waitFor(5_000, "zoom applied") { Prefs(ctx).resizeMode == 1 }
            compose.onNodeWithText("Player settings").assertExists()     // choosing an option keeps the panel open
            assertEquals(1, Prefs(ctx).resizeMode)
            compose.onNodeWithText("Stream info").assertExists()
            compose.onNodeWithContentDescription("Close panel").performClick()   // tap outside the sheet
            waitFor(5_000, "panel closed") { gone("Player settings") }

            // Multiview: pick a second channel, sound follows the tapped picture
            compose.onNodeWithText("Multiview").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Watch a second channel")
            compose.onAllNodesWithText("Sky News").let { n -> n[n.fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
            waitForText("Tap a picture to hear it")
            compose.onNodeWithContentDescription("Sound on").assertExists()
            compose.onNodeWithContentDescription("Sound off").assertExists()
            compose.onNodeWithContentDescription("Close this picture").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Morning Show")

            // Surf: vertical feed with its own controls
            compose.onNodeWithText("Surf").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Swipe up for the next channel")
            compose.onNodeWithText("1 of 3").assertExists()
            compose.onNodeWithContentDescription("Leave surf mode").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Channels")
        }
    }

    @Test fun subtitlesDefaultOffAndRemembered() {
        assertEquals(false, Prefs(ctx).subtitlesOn)
        Prefs(ctx).subtitlesOn = true
        queue()
        ActivityScenario.launch(PlayerActivity::class.java).use {
            waitForText("Morning Show")
            compose.onNodeWithContentDescription("Turn subtitles off").assertExists()
            compose.onNodeWithContentDescription("Turn subtitles off").performClick()
            waitFor(5_000, "cc toggled") { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Turn subtitles on")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun verticalViewForFilms() {
        PlayQueue.set(listOf(PlayEntry("vod|http://127.0.0.1:9/heat.mkv", "Heat", listOf(Channel("Heat", "http://127.0.0.1:9/heat.mkv", "EN| Action")))), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use {
            waitForText("Heat")
            compose.onNodeWithText("Vertical").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitFor(5_000, "vertical view") { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Leave vertical view")).fetchSemanticsNodes().isNotEmpty() }
            assert(gone("Swipe up for the next channel")) { "films don't swipe between titles" }
            compose.onNodeWithContentDescription("Back 10 seconds").assertExists()
            // Whole picture with an ambient glow behind it; no fixed crop, no zoom until pinched
            assert(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Reset zoom")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Turn ambient background off").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitFor(5_000, "ambient toggle") { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Turn ambient background on")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Leave vertical view").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Vertical")
        }
    }
}
