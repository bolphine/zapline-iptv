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
import com.zapline.iptv.player.PlayEntry
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
class PlayerUiTest : UiTestBase() {

    @Test fun playerOpensAndSwitchesChannels() {
        PlayQueue.set(
            listOf(
                PlayEntry("k1", "Channel One", listOf(
                    Channel("Channel One HD", "http://127.0.0.1:9/one.ts", "UK | TEST"),
                    Channel("Channel One SD", "http://127.0.0.1:9/one-sd.ts", "UK | TEST"),
                )),
                PlayEntry("k2", "Channel Two", listOf(Channel("Channel Two", "http://127.0.0.1:9/two.m3u8", "UK | TEST"))),
            ),
            0,
        )
        ActivityScenario.launch(PlayerActivity::class.java).use {
            waitForText("Channel One")
            // dead stream on 127.0.0.1:9 -> falls back through both versions, then shows the error
            // The player's retry back-off runs on the (simulated) clock, so move time forward while waiting.
            waitFor(30_000, "fallback to SD", advanceMs = 500) { compose.onAllNodesWithText("SD 2/2").fetchSemanticsNodes().isNotEmpty() }
            waitFor(30_000, "error after all versions", advanceMs = 500) { compose.onAllNodesWithText("Tried all 2 versions", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("k1"), com.zapline.iptv.data.Prefs(ctx).recents)
            compose.onNodeWithContentDescription("Next channel").performClick()
            waitForText("Channel Two")
            compose.onNodeWithContentDescription("Next channel").performClick()
            waitForText("Channel One")   // wraps around
        }
    }
}
