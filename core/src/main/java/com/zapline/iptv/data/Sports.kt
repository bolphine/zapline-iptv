package com.zapline.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Live football scores and stats from ESPN's public scoreboard feed.
 *
 * The feed is unofficial (free, no key) and may change; every failure here is swallowed so the
 * match panel simply has nothing to show rather than breaking playback.
 */
object Sports {

    data class Team(val id: String, val name: String, val short: String, val abbr: String, val logo: String?, val score: String)
    data class Stat(val label: String, val home: String, val away: String)
    data class Event(val minute: String, val text: String, val home: Boolean, val player: String?, val kind: Kind)
    enum class Kind { GOAL, YELLOW, RED, OTHER }

    data class Match(
        val id: String,
        val league: String,
        /** ESPN competition code, so this one game can be refreshed with a single request. */
        val leagueCode: String,
        val home: Team,
        val away: Team,
        /** "pre", "in" or "post". */
        val state: String,
        val clock: String,
        val detail: String,
        val stats: List<Stat>,
        val events: List<Event>,
        /** Kick-off time (ms), 0 if unknown. */
        val start: Long = 0,
    ) {
        val isLive get() = state == "in"
    }

    // ---- the full picture of one game (ESPN match summary) ----

    data class Player(val number: String, val name: String, val position: String, val subbedIn: Boolean, val subbedOut: Boolean)
    data class Lineup(val team: String, val formation: String?, val starters: List<Player>, val subs: List<Player>)
    data class KeyEvent(val minute: String, val kind: String, val home: Boolean?, val text: String, val players: List<String>)
    data class TableRow(val position: Int, val teamId: String, val team: String, val played: String, val won: String, val drawn: String,
                        val lost: String, val goalDiff: String, val points: String)
    data class Detail(
        val stats: List<Stat>,
        val home: Lineup?,
        val away: Lineup?,
        val events: List<KeyEvent>,
        val commentary: List<Pair<String, String>>,
        val table: List<TableRow>,
        val tableName: String?,
        val venue: String?,
        val attendance: Int,
    )

    /** Everything about one game: full stats, line-ups, key events, live text and the league table. One request. */
    suspend fun detail(m: Match): Detail? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/soccer/${m.leagueCode}/summary?event=${m.id}")
                .header("User-Agent", "Mozilla/5.0").build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) parseSummary(r.body!!.string(), m.home.id) else null }
        } catch (_: Exception) { null }
    }

    /** Stats shown in the full view, in this order. */
    private val detailStats = listOf(
        "possessionPct" to "Possession", "totalShots" to "Shots", "shotsOnTarget" to "On target", "blockedShots" to "Blocked shots",
        "wonCorners" to "Corners", "totalPasses" to "Passes", "passPct" to "Pass accuracy", "totalCrosses" to "Crosses",
        "totalTackles" to "Tackles", "interceptions" to "Interceptions", "totalClearance" to "Clearances",
        "foulsCommitted" to "Fouls", "yellowCards" to "Yellow cards", "redCards" to "Red cards", "offsides" to "Offsides", "saves" to "Saves",
    )

    fun parseSummary(json: String, homeId: String): Detail {
        val o = JSONObject(json)
        // Stats: boxscore.teams[] each with statistics[]
        val teams = o.optJSONObject("boxscore")?.optJSONArray("teams")
        val byTeam = HashMap<String, Map<String, String>>()
        if (teams != null) for (i in 0 until teams.length()) {
            val t = teams.optJSONObject(i) ?: continue
            val id = t.optJSONObject("team")?.optString("id") ?: continue
            val m = HashMap<String, String>()
            t.optJSONArray("statistics")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { m[it.optString("name")] = it.optString("displayValue") } }
            byTeam[id] = m
        }
        val hs = byTeam[homeId] ?: byTeam.values.firstOrNull() ?: emptyMap()
        val aw = byTeam.entries.firstOrNull { it.key != homeId }?.value ?: emptyMap()
        fun fmt(k: String, v: String) = when (k) {
            "possessionPct" -> "${v.toDoubleOrNull()?.let { Math.round(it) } ?: v}%"
            "passPct" -> v.toDoubleOrNull()?.let { "${Math.round(if (it <= 1.0) it * 100 else it)}%" } ?: v
            else -> v
        }
        val stats = detailStats.mapNotNull { (k, label) ->
            val h = hs[k]; val a = aw[k]
            if (h == null || a == null) null else Stat(label, fmt(k, h), fmt(k, a))
        }

        // Line-ups
        var home: Lineup? = null; var away: Lineup? = null
        o.optJSONArray("rosters")?.let { rs ->
            for (i in 0 until rs.length()) {
                val r = rs.optJSONObject(i) ?: continue
                val players = ArrayList<Pair<Boolean, Player>>()
                r.optJSONArray("roster")?.let { a ->
                    for (k in 0 until a.length()) {
                        val p = a.optJSONObject(k) ?: continue
                        val ath = p.optJSONObject("athlete") ?: continue
                        players += p.optBoolean("starter") to Player(
                            p.optString("jersey"), ath.optString("displayName"),
                            p.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                            p.optBoolean("subbedIn"), p.optBoolean("subbedOut"),
                        )
                    }
                }
                val l = Lineup(r.optJSONObject("team")?.optString("displayName").orEmpty(), r.optString("formation").ifEmpty { null },
                    players.filter { it.first }.map { it.second }, players.filterNot { it.first }.map { it.second })
                val isHome = r.optString("homeAway") == "home" || r.optJSONObject("team")?.optString("id") == homeId
                if (isHome) home = l else away = l
            }
        }

        // Key events: goals, cards, substitutions, half time / full time
        val events = ArrayList<KeyEvent>()
        o.optJSONArray("keyEvents")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val type = e.optJSONObject("type")?.optString("type").orEmpty()
                val kind = when {
                    e.optBoolean("scoringPlay") || type.startsWith("goal") || type.startsWith("penalty---scored") -> "goal"
                    type == "own-goal" -> "goal"
                    type.contains("yellow") -> "yellow"
                    type.contains("red") -> "red"
                    type == "substitution" -> "sub"
                    type == "halftime" -> "ht"
                    type == "end-regular-time" || type == "end" -> "ft"
                    else -> continue
                }
                val names = ArrayList<String>()
                e.optJSONArray("participants")?.let { p -> for (k in 0 until p.length()) p.optJSONObject(k)?.optJSONObject("athlete")?.optString("displayName")?.takeIf { it.isNotEmpty() }?.let { names += it } }
                val teamId = e.optJSONObject("team")?.optString("id")
                events += KeyEvent(
                    e.optJSONObject("clock")?.optString("displayValue").orEmpty(), kind,
                    if (teamId.isNullOrEmpty()) null else teamId == homeId,
                    e.optString("shortText").ifEmpty { e.optString("text") }, names,
                )
            }
        }

        // Live text, newest first
        val commentary = ArrayList<Pair<String, String>>()
        o.optJSONArray("commentary")?.let { a ->
            for (i in a.length() - 1 downTo 0) {
                val c = a.optJSONObject(i) ?: continue
                val t = c.optString("text")
                if (t.isEmpty()) continue
                commentary += (c.optJSONObject("time")?.optString("displayValue").orEmpty()) to t
            }
        }

        // League table
        val table = ArrayList<TableRow>()
        var tableName: String? = null
        o.optJSONObject("standings")?.optJSONArray("groups")?.let { gs ->
            // The group containing this game's home team (cup groups), else the first.
            val pick = (0 until gs.length()).mapNotNull { gs.optJSONObject(it) }.firstOrNull { g ->
                val es = g.optJSONObject("standings")?.optJSONArray("entries")
                es != null && (0 until es.length()).any { es.optJSONObject(it)?.optString("id") == homeId }
            } ?: gs.optJSONObject(0)
            tableName = pick?.optString("header")?.ifEmpty { null }
            val es = pick?.optJSONObject("standings")?.optJSONArray("entries")
            if (es != null) for (i in 0 until es.length()) {
                val e = es.optJSONObject(i) ?: continue
                val st = HashMap<String, String>()
                e.optJSONArray("stats")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { st[it.optString("name")] = it.optString("displayValue") } }
                table += TableRow(
                    st["rank"]?.toIntOrNull() ?: (i + 1), e.optString("id"), e.optString("team"),
                    st["gamesPlayed"] ?: "", st["wins"] ?: "", st["ties"] ?: "", st["losses"] ?: "", st["pointDifferential"] ?: "", st["points"] ?: "",
                )
            }
        }
        val gi = o.optJSONObject("gameInfo")
        return Detail(stats, home, away, events, commentary, table, tableName,
            gi?.optJSONObject("venue")?.optString("fullName")?.ifEmpty { null }, gi?.optInt("attendance") ?: 0)
    }

    /** Competitions most likely to be on UK sports channels. */
    val leagues = listOf(
        "eng.1" to "Premier League", "eng.2" to "Championship", "eng.3" to "League One", "eng.4" to "League Two",
        "eng.fa" to "FA Cup", "eng.league_cup" to "League Cup", "sco.1" to "Scottish Premiership",
        "uefa.champions" to "Champions League", "uefa.europa" to "Europa League", "uefa.europa.conf" to "Conference League",
        "esp.1" to "LaLiga", "ita.1" to "Serie A", "ger.1" to "Bundesliga", "fra.1" to "Ligue 1",
        "ned.1" to "Eredivisie", "por.1" to "Primeira Liga", "usa.1" to "MLS", "uefa.nations" to "Nations League",
        "fifa.world" to "World Cup", "fifa.worldq.uefa" to "WC Qualifying", "uefa.euro" to "Euros",
    )

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    @Volatile private var cache: Pair<Long, List<Match>>? = null

    /**
     * Today's games across [leagues]: one sweep, only used to work out which game a channel is showing.
     * Cached for 10 minutes; live updates afterwards go through [single].
     */
    suspend fun today(force: Boolean = false): List<Match> {
        val c = cache
        if (!force && c != null && System.currentTimeMillis() - c.first < 600_000) return c.second
        val all = withContext(Dispatchers.IO) {
            coroutineScope {
                leagues.map { (code, name) -> async { fetchLeague(code, name) } }.awaitAll().flatten()
            }
        }
        // Live first, then upcoming, then finished.
        val sorted = all.sortedBy { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }
        cache = System.currentTimeMillis() to sorted
        return sorted
    }

    fun setTodayForTests(list: List<Match>?) { cache = list?.let { System.currentTimeMillis() to it } }

    /** Live refresh of the one game being watched: a single request for its competition. */
    suspend fun single(m: Match): Match? = withContext(Dispatchers.IO) {
        fetchLeague(m.leagueCode, m.league).firstOrNull { it.id == m.id }
    }

    private fun fetchLeague(code: String, name: String): List<Match> = try {
        val req = Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/soccer/$code/scoreboard")
            .header("User-Agent", "Mozilla/5.0").build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) parseScoreboard(r.body!!.string(), name, code) else emptyList() }
    } catch (e: Exception) { emptyList() }

    fun parseScoreboard(json: String, league: String, leagueCode: String = ""): List<Match> {
        val out = ArrayList<Match>()
        val events = JSONObject(json).optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val cs = comp.optJSONArray("competitors") ?: continue
            var home: JSONObject? = null; var away: JSONObject? = null
            for (j in 0 until cs.length()) {
                val c = cs.optJSONObject(j) ?: continue
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) continue
            val status = ev.optJSONObject("status") ?: comp.optJSONObject("status")
            val type = status?.optJSONObject("type")
            val ht = team(home); val at = team(away)
            out += Match(
                id = ev.optString("id"),
                league = league,
                leagueCode = leagueCode,
                home = ht, away = at,
                state = type?.optString("state") ?: "pre",
                clock = status?.optString("displayClock") ?: "",
                detail = type?.optString("shortDetail")?.ifEmpty { null } ?: type?.optString("detail") ?: "",
                stats = stats(home.optJSONArray("statistics"), away.optJSONArray("statistics")),
                events = events(comp.optJSONArray("details"), ht.id),
                start = parseDate(ev.optString("date")),
            )
        }
        return out
    }

    /** ESPN dates look like "2026-10-18T15:30Z". */
    private fun parseDate(s: String): Long = try {
        java.time.OffsetDateTime.parse(s.replace(Regex("Z$"), "+00:00")).toInstant().toEpochMilli()
    } catch (_: Exception) { 0L }

    private fun team(c: JSONObject): Team {
        val t = c.optJSONObject("team") ?: JSONObject()
        return Team(
            id = t.optString("id"),
            name = t.optString("displayName"),
            short = t.optString("shortDisplayName").ifEmpty { t.optString("displayName") },
            abbr = t.optString("abbreviation"),
            logo = t.optString("logo").ifEmpty { null },
            score = c.optString("score", "0").ifEmpty { "0" },
        )
    }

    /** Stats we show, in this order, with plain labels. */
    private val statLabels = linkedMapOf(
        "possessionPct" to "Possession",
        "totalShots" to "Shots",
        "shotsOnTarget" to "On target",
        "wonCorners" to "Corners",
        "foulsCommitted" to "Fouls",
        "offsides" to "Offsides",
        "saves" to "Saves",
    )

    private fun stats(h: JSONArray?, a: JSONArray?): List<Stat> {
        fun map(arr: JSONArray?): Map<String, String> {
            val m = HashMap<String, String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { m[it.optString("name")] = it.optString("displayValue") }
            return m
        }
        val hm = map(h); val am = map(a)
        return statLabels.mapNotNull { (k, label) ->
            val hv = hm[k]; val av = am[k]
            if (hv == null || av == null) null
            else Stat(label, if (k == "possessionPct") "$hv%" else hv, if (k == "possessionPct") "$av%" else av)
        }
    }

    private fun events(arr: JSONArray?, homeId: String): List<Event> {
        val out = ArrayList<Event>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val kind = when {
                d.optBoolean("scoringPlay") -> Kind.GOAL
                d.optBoolean("redCard") -> Kind.RED
                d.optBoolean("yellowCard") -> Kind.YELLOW
                else -> Kind.OTHER
            }
            if (kind == Kind.OTHER) continue
            val player = d.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName")?.ifEmpty { null }
            var text = d.optJSONObject("type")?.optString("text") ?: ""
            if (d.optBoolean("ownGoal")) text = "Own goal"
            else if (d.optBoolean("penaltyKick") && kind == Kind.GOAL) text = "Penalty"
            out += Event(
                minute = d.optJSONObject("clock")?.optString("displayValue") ?: "",
                text = text,
                home = d.optJSONObject("team")?.optString("id") == homeId,
                player = player,
                kind = kind,
            )
        }
        return out
    }

    // ---- matching a programme title to a game ----

    private val aliases = mapOf(
        "utd" to "united", "man" to "manchester", "spurs" to "tottenham", "wolves" to "wolverhampton",
        "boro" to "middlesbrough", "forest" to "nottingham", "palace" to "crystal", "villa" to "aston",
        "brighton" to "brighton", "newcastle" to "newcastle", "psg" to "paris", "inter" to "internazionale",
        "atletico" to "atletico", "bayern" to "bayern", "barca" to "barcelona", "real" to "real", "city" to "city",
    )
    private val noise = setOf("fc", "afc", "cf", "sc", "the", "live", "football", "hd", "uk", "and", "de", "club", "ss", "ac", "as")
    private val fixtureSplit = Regex("\\s+(?:v|vs|v\\.|vs\\.|versus|-|–|—|x)\\s+", RegexOption.IGNORE_CASE)

    private fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in noise }
            .map { aliases[it] ?: it }

    /** How well a side of a fixture ("Man Utd") matches a team (0 = no match). */
    private fun sideScore(side: String, t: Team): Int {
        val want = tokens(side)
        if (want.isEmpty()) return 0
        val have = (tokens(t.name) + tokens(t.short)).toSet()
        if (side.trim().equals(t.abbr, ignoreCase = true)) return 3
        val hits = want.count { w -> have.any { h -> h == w || (w.length >= 4 && h.startsWith(w)) || (h.length >= 4 && w.startsWith(h)) } }
        return if (hits == want.size) 2 else if (hits > 0 && want.size > 1) 1 else 0
    }

    /** Pulls "Arsenal v Chelsea" out of "Live: Arsenal v Chelsea (Premier League)". */
    fun fixture(text: String): Pair<String, String>? {
        val clean = text.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("^.*?:\\s*"), "")
        val parts = clean.split(fixtureSplit)
        if (parts.size != 2) return null
        val a = parts[0].trim(); val b = parts[1].trim().split(Regex("\\s+-\\s+|,")).first().trim()
        return if (a.isNotEmpty() && b.isNotEmpty()) a to b else null
    }

    /** The game a programme is showing, if any. Prefers live games, then today's others. */
    fun find(matches: List<Match>, vararg texts: String?): Match? {
        for (text in texts.filterNotNull()) {
            val fx = fixture(text) ?: continue
            val best = matches.map { m ->
                val direct = sideScore(fx.first, m.home) + sideScore(fx.second, m.away)
                val swapped = sideScore(fx.first, m.away) + sideScore(fx.second, m.home)
                val ok = (sideScore(fx.first, m.home) > 0 && sideScore(fx.second, m.away) > 0) ||
                    (sideScore(fx.first, m.away) > 0 && sideScore(fx.second, m.home) > 0)
                m to if (ok) maxOf(direct, swapped) * 10 + (if (m.isLive) 5 else 0) else 0
            }.filter { it.second > 0 }.maxByOrNull { it.second }
            if (best != null) return best.first
        }
        return null
    }
}
