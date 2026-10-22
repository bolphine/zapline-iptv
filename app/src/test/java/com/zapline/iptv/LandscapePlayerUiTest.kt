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
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapePlayerUiTest : UiTestBase() {

    @Test fun fullScreenControlsLockAndBanner() {
        val now = System.currentTimeMillis()
        EpgStore.data = EpgData(mapOf("BBCOne.uk" to listOf(
            Programme(now - 600_000, now + 600_000, "Arsenal v Chelsea", null),
            Programme(now + 600_000, now + 1_800_000, "Match of the Day", null),
        )), emptyMap(), now)
        PlayQueue.set(listOf(
            PlayEntry("k1", "BBC One", listOf(Channel("BBC One", "http://127.0.0.1:9/1.ts", "UK| Sports")), "BBCOne.uk"),
            PlayEntry("k2", "Sky News", listOf(Channel("Sky News", "http://127.0.0.1:9/2.ts", "UK| News"))),
        ), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use {
            // Top bar carries what's on; bottom bar the live indicator and watch modes
            waitFor(10_000, "top bar") { compose.onAllNodesWithText("Arsenal v Chelsea, until", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Live").assertExists()
            compose.onNodeWithContentDescription("Match stats").assertExists()        // fixture-like programme
            compose.onNodeWithContentDescription("Surf mode").assertExists()
            compose.onNodeWithContentDescription("Multiview").assertExists()
            compose.onNodeWithContentDescription("Picture in picture").assertExists()
            compose.onNodeWithContentDescription("Next channel").assertExists()

            // Lock hides the controls; only the unlock button remains
            compose.onNodeWithContentDescription("Lock screen").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitFor(5_000, "controls hidden") { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Next channel")).fetchSemanticsNodes().isEmpty() }
            assert(gone("Live"))
        }
    }
}
