package com.zapline.iptv.lang

import java.text.Normalizer

/** Coarse genre for browsing: worked out from the group title first, then the channel name. */
object Categories {
    const val ADULT = "adult"

    val labels: LinkedHashMap<String, String> = linkedMapOf(
        "sports" to "Sports",
        "news" to "News",
        "movies" to "Movies",
        "entertainment" to "Entertainment",
        "kids" to "Kids",
        "docs" to "Documentary",
        "music" to "Music & radio",
        "religious" to "Religious",
        ADULT to "Adult",
    )

    fun label(code: String) = if (code.isEmpty()) "Other" else labels[code] ?: code

    private val words = HashMap<String, String>()
    private val phrases = ArrayList<Pair<String, String>>()
    /** Unambiguous adult terms, safe to match in channel names. */
    private val adultNameRe = Regex("(?i)(\\bXXX\\b|\\b18\\s*\\+|\\bPLAYBOY\\b|\\bHUSTLER\\b|\\bREDLIGHT\\b|\\bBRAZZERS\\b|\\bPENTHOUSE\\b)")
    /** Groups may also just say "Adult" ("Adult Swim" as a channel name must not match). */
    private val adultGroupRe = Regex("(?i)(\\bXXX\\b|\\b18\\s*\\+|\\bADULTS?\\b|\\bPLAYBOY\\b|\\bHUSTLER\\b|\\bREDLIGHT\\b|\\bBRAZZERS\\b|\\bPENTHOUSE\\b)")

    init {
        fun c(cat: String, vararg w: String) = w.forEach { if (it.contains(' ')) phrases += " $it " to cat else words[it] = cat }
        c("sports", "SPORT", "SPORTS", "SPORTING", "FOOTBALL", "SOCCER", "NFL", "NBA", "NHL", "MLB", "F1", "FORMULA",
            "RACING", "MOTORSPORT", "MOTORSPORTS", "GOLF", "TENNIS", "CRICKET", "RUGBY", "BOXING", "UFC", "MMA", "WWE",
            "PPV", "EPL", "LALIGA", "BUNDESLIGA", "DAZN", "ESPN", "BEIN", "EUROSPORT", "SUPERSPORT", "SUPERSPORTS",
            "DEPORTES", "DEPORTE", "SPORTV", "SPORTITALIA", "EVENTS", "MATCHDAY", "SPORTSNET", "TSN", "NBCSN",
            "PREMIER LEAGUE", "CHAMPIONS LEAGUE", "SERIE A", "TNT SPORTS", "BT SPORT", "SKY SPORTS", "SKY SPORT")
        c("news", "NEWS", "NOTICIAS", "INFO", "NEWSMAX", "CNN", "CNBC", "BLOOMBERG", "MSNBC", "EURONEWS", "NHK",
            "AL JAZEERA", "FRANCE 24", "SKY NEWS", "GB NEWS", "FOX NEWS", "BBC NEWS", "BBC WORLD")
        c("movies", "MOVIE", "MOVIES", "CINEMA", "CINEMAS", "FILM", "FILMS", "FILM4", "PELICULAS", "CINE", "HBO",
            "SHOWTIME", "STARZ", "TCM", "CINEMAX", "PREMIERE", "SKY CINEMA", "SKY MOVIES")
        c("kids", "KIDS", "KID", "CHILDREN", "CHILD", "JUNIOR", "CARTOON", "CARTOONS", "CARTOONITO", "CBEEBIES",
            "CBBC", "CITV", "NICKELODEON", "NICK", "NICKTOONS", "BOOMERANG", "BABY", "BABYTV", "DISNEY", "ENFANTS",
            "INFANTIL", "INFANTILES", "ANIMATION", "TOONS", "TINY POP", "POP MAX")
        c("docs", "DOC", "DOCS", "DOCU", "DOCUMENTARY", "DOCUMENTARIES", "DOCUMENTAIRE", "DOCUMENTAIRES",
            "DOCUMENTALES", "DISCOVERY", "NATGEO", "HISTORY", "NATURE", "SCIENCE", "INVESTIGATION", "NAT GEO",
            "NATIONAL GEOGRAPHIC", "ANIMAL PLANET", "SKY DOCUMENTARIES", "SKY NATURE", "SKY HISTORY", "TRUE CRIME")
        c("music", "MUSIC", "MUSICA", "MUSIQUE", "MTV", "VH1", "RADIO", "RADIOS", "KISS", "CLUBLAND", "CONCERTS")
        c("religious", "RELIGIOUS", "RELIGION", "CHRISTIAN", "CHURCH", "GOSPEL", "ISLAMIC", "ISLAM", "QURAN",
            "BIBLE", "CATHOLIC", "GOD TV")
        c("entertainment", "ENTERTAINMENT", "GENERAL", "GENERALIST", "DIVERTISSEMENT", "ENTRETENIMIENTO",
            "VARIETY", "DRAMA", "COMEDY", "LIFESTYLE", "REALITY", "SERIES", "SHOWS", "ENTERTAIN", "24 7")
    }

    private fun normalise(s: String): List<String> =
        Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKC), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").uppercase()
            .split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }

    private fun scan(s: String): String? {
        val w = normalise(s)
        val padded = " " + w.joinToString(" ") + " "
        // Phrases first ("SKY NEWS" must not fall through to "SKY" anything), then the earliest word.
        var best: Pair<Int, String>? = null
        for ((p, cat) in phrases) {
            val i = padded.indexOf(p)
            if (i >= 0 && (best == null || i < best.first)) best = i to cat
        }
        best?.let { return it.second }
        for (x in w) words[x]?.let { return it }
        return null
    }

    fun isAdult(group: String, name: String) = adultGroupRe.containsMatchIn(group) || adultNameRe.containsMatchIn(name)

    fun detect(group: String, name: String, groupCategory: String? = null): String {
        if (isAdult(group, name)) return ADULT
        val g = groupCategory ?: (scan(group) ?: "")
        if (g.isNotEmpty() && g != "entertainment") return g
        // A generic "Entertainment" group can still hold an obvious news or sports channel.
        return scan(name)?.takeIf { it != ADULT } ?: g
    }

    fun forGroup(group: String): String = if (adultGroupRe.containsMatchIn(group)) ADULT else scan(group) ?: ""
}
