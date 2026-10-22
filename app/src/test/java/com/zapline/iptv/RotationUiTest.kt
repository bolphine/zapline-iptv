package com.zapline.iptv

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RotationUiTest : UiTestBase() {
    @Test fun rotationLockIsKept() {
        PlayQueue.set(listOf(PlayEntry("k1", "BBC One", listOf(Channel("BBC One", "http://127.0.0.1:9/1.ts", "UK")))), 0)
        ActivityScenario.launch(PlayerActivity::class.java).use { sc ->
            sc.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, it.requestedOrientation) }
            waitFor(5_000, "settings button") { compose.onAllNodesWithText("BBC One", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Player settings").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Rotation")
            compose.onAllNodesWithText("Landscape").onLast().performSemanticsAction(SemanticsActions.OnClick)
            waitFor(5_000, "locked") { Prefs(ctx).playerOrientation == 1 }
            sc.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation) }
        }
        // Reopening the player keeps the lock.
        ActivityScenario.launch(PlayerActivity::class.java).use { sc ->
            sc.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation) }
        }
    }
}
