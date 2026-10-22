package com.zapline.iptv

import com.zapline.iptv.data.Sports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing real ESPN replies (Nottingham Forest 0-1 Arsenal, 18 Oct 2026), saved as test fixtures. */
class SportsDetailTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun scoreboardHasKickoffTimes() {
        val ms = Sports.parseScoreboard(res("espn_scoreboard.json"), "Premier League", "eng.1")
        val m = ms.first { it.id == "401878769" }
        assertEquals(java.time.Instant.parse("2026-10-18T15:30:00Z").toEpochMilli(), m.start)
        assertEquals("post", m.state)
    }

    @Test fun summaryGivesStatsLineupsEventsAndTable() {
        val d = Sports.parseSummary(res("espn_summary.json"), homeId = "393")
        val poss = d.stats.first { it.label == "Possession" }
        assertEquals("26%", poss.home); assertEquals("74%", poss.away)
        assertEquals("221", d.stats.first { it.label == "Passes" }.home)
        assertEquals("90%", d.stats.first { it.label == "Pass accuracy" }.away)
        assertTrue(d.stats.size >= 12)

        val home = d.home!!; val away = d.away!!
        assertEquals("Nottingham Forest", home.team); assertEquals("3-4-2-1", home.formation)
        assertEquals(11, home.starters.size); assertEquals(11, away.starters.size)
        assertEquals("Matz Sels", home.starters.first().name); assertEquals("G", home.starters.first().position)
        assertTrue(away.subs.isNotEmpty())

        val goal = d.events.first { it.kind == "goal" }
        assertEquals("80'", goal.minute); assertEquals(false, goal.home)
        assertEquals("Noni Madueke", goal.players.first())
        assertTrue(d.events.any { it.kind == "sub" && it.players.size == 2 })
        assertTrue(d.events.any { it.kind == "yellow" })

        assertTrue(d.commentary.isNotEmpty())
        assertTrue(d.commentary.first().second.startsWith("Match ends"))      // newest first

        assertEquals(20, d.table.size)
        assertEquals(1, d.table.first().position)
        assertNotNull(d.table.firstOrNull { it.teamId == "359" })
        assertEquals("The City Ground", d.venue); assertEquals(30893, d.attendance)
    }
}
