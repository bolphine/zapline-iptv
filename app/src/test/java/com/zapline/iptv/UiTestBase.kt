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

abstract class UiTestBase {

    @get:Rule val compose = createEmptyComposeRule()

    protected val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        ctx.getSharedPreferences("zapline", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.filesDir.listFiles()?.filter { it.name.endsWith(".bin") || it.name.endsWith(".tmp") }?.forEach { it.delete() }
        com.zapline.iptv.data.EpgStore.data = com.zapline.iptv.data.EpgData.EMPTY
        File(ctx.filesDir, "health.jsonl").delete()
        com.zapline.iptv.data.Health.resetForTests()
        Prefs(ctx).autoCheck = false
    }

    /** Polls while letting the (paused) Robolectric main looper and background work progress. */
    protected fun waitFor(timeout: Long = 15_000, what: String = "condition", advanceMs: Long = 0, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            compose.waitForIdle()
            if (advanceMs > 0) ShadowLooper.idleMainLooper(advanceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            else ShadowLooper.idleMainLooper()
            if (cond()) return
            if (System.currentTimeMillis() > end) {
                val tree = try { compose.onAllNodes(isRoot()).printToString(maxDepth = 12).take(6000) } catch (e: Throwable) { "tree unavailable: $e" }
                throw AssertionError("Timed out waiting for $what\n$tree")
            }
            Thread.sleep(50)
        }
    }

    protected fun waitForText(text: String, timeout: Long = 15_000) =
        waitFor(timeout, "\"$text\"") { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    protected fun gone(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
}
