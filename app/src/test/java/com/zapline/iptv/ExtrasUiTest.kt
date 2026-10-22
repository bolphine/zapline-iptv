package com.zapline.iptv

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.zapline.iptv.data.EpgCache
import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Reminders
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

abstract class ExtrasBase : UiTestBase() {

    protected fun setUp(now: Long) {
        val f = File(ctx.cacheDir, "x.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="BBCOne.uk" catchup="append" catchup-days="3" catchup-source="?utc={utc}" group-title="UK| General",BBC One
            http://h/bbc1.m3u8
            #EXTINF:-1 tvg-id="SkyNews.uk" group-title="UK| News",Sky News
            http://h/sky.m3u8
            #EXTINF:-1 group-title="XXX | ADULT",Late Night Channel
            http://h/adult.m3u8
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "x.m3u"; regionFilter = emptySet(); langFilter = emptySet() }
        EpgCache.save(File(ctx.filesDir, "epg.bin"), EpgData(
            mapOf(
                "BBCOne.uk" to listOf(
                    Programme(now - 5 * 3600_000, now - 4 * 3600_000, "Antiques Roadshow", "Treasures."),
                    Programme(now - 30 * 60_000, now + 30 * 60_000, "Morning Show", null),
                    Programme(now + 2 * 3600_000, now + 3 * 3600_000, "Bargain Hunt", null),
                ),
                "SkyNews.uk" to listOf(Programme(now - 10 * 60_000, now + 50 * 60_000, "Sky News Today", null)),
            ),
            emptyMap(), now,
        ))
    }

    protected fun search(text: String) {
        val box = "Channels, programmes, films, series"
        if (compose.onAllNodesWithContentDescription(box).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Search everything").performClick()
        waitFor(5_000, "search box") { compose.onAllNodesWithContentDescription(box).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(box).performTextReplacement(text)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchExtrasUiTest : ExtrasBase() {
    @Test fun searchRemindAndCatchUp() {
        val now = System.currentTimeMillis()
        setUp(now)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("BBC One")

            // Coming up -> remind me
            search("bargain")
            waitForText("Coming up")
            compose.onNodeWithText("Bargain Hunt").performClick()
            waitForText("Remind me")
            compose.onNodeWithText("Remind me").performClick()
            waitFor(5_000, "saved") { Reminders.all(ctx).size == 1 }
            assertEquals("Bargain Hunt", Reminders.all(ctx)[0].title)
            waitForText("Remove reminder")
            compose.onNodeWithText("Close").performClick()

            // On now + channels
            search("sky")
            waitForText("On now")
            waitForText("Sky News Today")
            waitForText("Channels")

            // Catch-up of an earlier programme
            search("antiques")
            waitForText("Catch-up")
            compose.onNodeWithText("Antiques Roadshow").performClick()
            waitForText("Watch catch-up")
            compose.onNodeWithText("Watch catch-up").performClick()
            scenario.onActivity { a ->
                assertEquals(PlayerActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
            }
            val e = PlayQueue.items[PlayQueue.index]
            assertTrue(e.key, e.key.startsWith("vod|catchup|"))
            assertEquals("http://h/bbc1.m3u8?utc=${(now - 5 * 3600_000) / 1000}", e.variants[0].url)

            // Adult channels never show in search while a PIN is set
            Prefs(ctx).adultPin = "1234"
            search("late night")
            waitForText("Nothing found for “late night”")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RemindersUiTest : ExtrasBase() {
    @Test fun remindersListInSettings() {
        val now = System.currentTimeMillis()
        setUp(now)
        Reminders.add(ctx, com.zapline.iptv.data.Reminder("Bargain Hunt", "k", "BBC One", null, now + 7200_000))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitForText("BBC One")
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Programme reminders")
            compose.onNodeWithText("1 upcoming").performClick()
            waitForText("Bargain Hunt")
            compose.onNodeWithContentDescription("Remove reminder for Bargain Hunt").performClick()
            waitForText("No reminders")
            assertTrue(Reminders.all(ctx).toString() + " raw=" + ctx.getSharedPreferences("zapline", 0).getString("reminders", null), Reminders.all(ctx).isEmpty())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AdultPinUiTest : ExtrasBase() {
    @Test fun adultCategoryNeedsPin() {
        setUp(System.currentTimeMillis())
        Prefs(ctx).adultPin = "1234"
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("BBC One")
            assertTrue(gone("Late Night Channel"))
            compose.onAllNodesWithText("Adult", substring = true)[0].performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Enter your PIN to show this category.")
            fun key(d: String) = compose.onAllNodesWithText(d).onLast().performSemanticsAction(SemanticsActions.OnClick)
            "9999".forEach { key(it.toString()) }
            waitForText("That PIN isn't right")
            "1234".forEach { key(it.toString()) }
            waitForText("Late Night Channel")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlaylistsUiTest : ExtrasBase() {
    @Test fun playlistsScreenKeepsSources() {
        setUp(System.currentTimeMillis())
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("BBC One")
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Playlists")
            compose.onNodeWithText("Playlists").performClick()
            waitForText("In use · File · x.m3u")
            compose.onNodeWithText("Add").performClick()
            waitFor(5_000, "setup form") { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, com.zapline.iptv.data.Playlists.all(Prefs(ctx)).size)
        }
    }
}
