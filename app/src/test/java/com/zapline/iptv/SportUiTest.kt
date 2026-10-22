package com.zapline.iptv

import android.net.Uri
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.zapline.iptv.data.EpgCache
import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Sports
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SportUiTest : UiTestBase() {
    private fun team(id: String, name: String, abbr: String, score: String) = Sports.Team(id, name, name, abbr, null, score)

    @Test fun gamesListTheirChannels() {
        val now = System.currentTimeMillis()
        val f = File(ctx.cacheDir, "s.m3u")
        f.writeText(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="SkyPL.uk" group-title="UK| Sports",Sky Sports Premier League
            http://h/1.ts
            #EXTINF:-1 tvg-id="BBCOne.uk" group-title="UK| General",BBC One
            http://h/2.ts
            """.trimIndent()
        )
        Prefs(ctx).apply { sourceType = "file"; fileUri = Uri.fromFile(f).toString(); fileName = "s.m3u" }
        EpgCache.save(File(ctx.filesDir, "epg.bin"), EpgData(
            mapOf(
                "SkyPL.uk" to listOf(Programme(now - 40 * 60_000, now + 80 * 60_000, "Live Premier League: Arsenal v Chelsea", null)),
                "BBCOne.uk" to listOf(Programme(now - 40 * 60_000, now + 80 * 60_000, "Bargain Hunt", null)),
            ), emptyMap(), now,
        ))
        Sports.setTodayForTests(listOf(
            Sports.Match("1", "Premier League", "eng.1", team("359", "Arsenal", "ARS", "2"), team("363", "Chelsea", "CHE", "1"),
                "in", "63'", "63'", emptyList(), emptyList(), start = now - 63 * 60_000),
            Sports.Match("2", "LaLiga", "esp.1", team("83", "Barcelona", "BAR", "0"), team("86", "Real Madrid", "RMA", "0"),
                "pre", "", "", emptyList(), emptyList(), start = now + 4 * 3600_000),
        ))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                waitForText("BBC One")
                compose.onNodeWithContentDescription("Sport").performClick()
                waitForText("Live now")
                waitForText("LIVE 63'")
                waitForText("Sky Sports Premier League")
                compose.onNodeWithText("On now").assertExists()
                waitForText("Later today")
                compose.onNodeWithText("Not found in your TV guide").assertExists()   // the LaLiga game
                compose.onNodeWithText("Sky Sports Premier League").performClick()
                sc.onActivity { a -> assertEquals(PlayerActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className) }
                assertEquals("Sky Sports Premier League", PlayQueue.items[PlayQueue.index].name)
            }
        } finally { Sports.setTodayForTests(null) }
    }
}
