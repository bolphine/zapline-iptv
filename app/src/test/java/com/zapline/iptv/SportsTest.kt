package com.zapline.iptv

import com.zapline.iptv.data.Sports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsTest {

    private fun game(id: String, home: Pair<String, String>, away: Pair<String, String>, state: String = "in",
                     hs: String = "1", aws: String = "0") = """
        {"id":"$id","status":{"displayClock":"63'","type":{"state":"$state","shortDetail":"63'"}},
         "competitions":[{"competitors":[
            {"homeAway":"home","score":"$hs","team":{"id":"h$id","displayName":"${home.first}","shortDisplayName":"${home.second}","abbreviation":"${home.second.take(3).uppercase()}"},
             "statistics":[{"name":"possessionPct","displayValue":"58"},{"name":"totalShots","displayValue":"11"},{"name":"shotsOnTarget","displayValue":"4"}]},
            {"homeAway":"away","score":"$aws","team":{"id":"a$id","displayName":"${away.first}","shortDisplayName":"${away.second}","abbreviation":"${away.second.take(3).uppercase()}"},
             "statistics":[{"name":"possessionPct","displayValue":"42"},{"name":"totalShots","displayValue":"6"},{"name":"shotsOnTarget","displayValue":"2"}]}],
          "details":[
            {"type":{"text":"Goal"},"clock":{"displayValue":"12'"},"team":{"id":"h$id"},"scoringPlay":true,"athletesInvolved":[{"displayName":"B. Saka"}]},
            {"type":{"text":"Yellow Card"},"clock":{"displayValue":"40'"},"team":{"id":"a$id"},"yellowCard":true,"athletesInvolved":[{"displayName":"M. Caicedo"}]},
            {"type":{"text":"Substitution"},"clock":{"displayValue":"60'"},"team":{"id":"a$id"}}
          ]}]}"""

    private val board = """{"events":[
        ${game("1", "Arsenal" to "Arsenal", "Chelsea" to "Chelsea")},
        ${game("2", "Manchester United" to "Man United", "Liverpool" to "Liverpool")},
        ${game("3", "Manchester City" to "Man City", "Tottenham Hotspur" to "Tottenham", state = "pre")},
        ${game("4", "Wolverhampton Wanderers" to "Wolves", "Nottingham Forest" to "Nottm Forest")}
    ]}"""

    private val matches = Sports.parseScoreboard(board, "Premier League", "eng.1")

    @Test fun parsesScoresStatsAndEvents() {
        val m = matches.first()
        assertEquals("eng.1", m.leagueCode)
        assertEquals("1", m.home.score)
        assertEquals(listOf("Possession", "Shots", "On target"), m.stats.map { it.label })
        assertEquals("58%", m.stats[0].home)
        assertEquals(2, m.events.size)                         // substitution ignored
        assertEquals(Sports.Kind.GOAL, m.events[0].kind)
        assertEquals("B. Saka", m.events[0].player)
        assertEquals(true, m.events[0].home)
        assertEquals(false, m.events[1].home)
    }

    @Test fun extractsFixtures() {
        assertEquals("Arsenal" to "Chelsea", Sports.fixture("Arsenal v Chelsea"))
        assertEquals("Arsenal" to "Chelsea", Sports.fixture("Live: Arsenal vs Chelsea (Premier League)"))
        assertEquals("Flamengo" to "Palmeiras", Sports.fixture("Flamengo x Palmeiras"))
        assertNull(Sports.fixture("Match of the Day"))
        assertNull(Sports.fixture("Sky Sports News"))
    }

    @Test fun matchesProgrammeTitlesToGames() {
        assertEquals("1", Sports.find(matches, "Live: Arsenal v Chelsea")?.id)
        assertEquals("2", Sports.find(matches, "Man Utd v Liverpool")?.id)
        assertEquals("3", Sports.find(matches, "Man City v Spurs")?.id)          // not confused with Man Utd
        assertEquals("4", Sports.find(matches, "Wolves v Forest")?.id)
        assertEquals("1", Sports.find(matches, "Chelsea v Arsenal")?.id)          // order flipped in the title
        assertNull(Sports.find(matches, "Brentford v Fulham"))
        assertNull(Sports.find(matches, "Gogglebox"))
        // falls through to the next text when the first doesn't parse
        assertEquals("2", Sports.find(matches, "Super Sunday", "Manchester United v Liverpool")?.id)
    }
}
