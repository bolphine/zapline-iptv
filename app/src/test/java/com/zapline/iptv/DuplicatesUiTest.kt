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
class DuplicatesUiTest : UiTestBase() {

    @Test fun duplicateTitlesDoNotCrash() {
        val f = File(ctx.cacheDir, "dupes.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 group-title="UK| General",BBC One
            http://h/live/u/p/1.ts
            #EXTINF:-1 group-title="EN| Action",Heat
            http://h/movie/u/p/10.mkv
            #EXTINF:-1 group-title="EN| Top Picks",Heat
            http://h/movie/u/p/10.mkv
            #EXTINF:-1 group-title="Drama",Show S01 E01
            http://h/series/u/p/20.mkv
            #EXTINF:-1 group-title="Drama",Show S01 E01
            http://h/series/u/p/20.mkv
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "dupes.m3u" }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("BBC One")
            compose.onNodeWithText("Movies").performClick()
            waitFor(5_000, "movie poster") { compose.onAllNodesWithText("Heat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Series").performClick()
            waitFor(5_000, "show") { compose.onAllNodesWithText("Show").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Show")[0].performClick()
            waitForText("1 season, 1 episode")
        }
    }
}
