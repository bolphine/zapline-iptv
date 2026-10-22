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
class ColdStartUiTest : UiTestBase() {

    /** A cold start with a saved playlist shows placeholders, never "No channels yet". */
    @Test fun coldStartNeverFlashesEmpty() {
        com.zapline.iptv.data.ChannelCache.save(File(ctx.filesDir, "channels.bin"),
            (1..400).map { Channel("Channel $it", "http://h/$it.ts", "UK| General", detectedLang = "en", detectedRegion = "UK") })
        Prefs(ctx).apply { sourceType = "m3u"; m3uUrl = "http://127.0.0.1:9/never.m3u" }
        File(ctx.filesDir, "channels.bin").setLastModified(System.currentTimeMillis())
        ActivityScenario.launch(MainActivity::class.java).use {
            var sawEmpty = false
            waitFor(15_000, "channels") {
                if (compose.onAllNodesWithText("No channels yet").fetchSemanticsNodes().isNotEmpty()) sawEmpty = true
                compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty()
            }
            assert(!sawEmpty) { "showed the empty state during start-up" }
        }
    }
}
