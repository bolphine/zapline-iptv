package com.zapline.iptv

import com.zapline.iptv.lang.LangRules
import com.zapline.iptv.lang.Categories
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.lang.LanguageTagger
import com.zapline.iptv.lang.RegionTagger
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.M3uParser
import com.zapline.iptv.parse.PlaylistFilters
import com.zapline.iptv.parse.Xtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private fun parse(s: String) = M3uParser.parse(s.reader().buffered()).channels

    @Test fun quotedCommasAndAttributes() {
        val ch = parse(
            "﻿#EXTM3U url-tvg=\"http://epg/x.xml\"\r\n" +
                "#EXTINF:-1 tvg-id=\"BBCOne.uk\" tvg-name=\"BBC One\" tvg-logo=\"http://l/bbc.png\" group-title=\"UK | Entertainment, Drama\",BBC One HD\r\n" +
                "http://host/live/u/p/1.ts\r\n"
        )
        assertEquals(1, ch.size)
        assertEquals("BBC One HD", ch[0].name)
        assertEquals("UK | Entertainment, Drama", ch[0].group)
        assertEquals("BBCOne.uk", ch[0].tvgId)
        assertEquals("http://l/bbc.png", ch[0].logo)
    }

    @Test fun epgHeader() {
        val r = M3uParser.parse("#EXTM3U x-tvg-url=\"http://e/g.xml.gz\"\n".reader().buffered())
        assertEquals("http://e/g.xml.gz", r.epgUrl)
    }

    @Test fun unquotedAndSingleQuotedAttributes() {
        val ch = parse("#EXTINF:-1 tvg-id=cnn.us group-title='US News',CNN\nhttp://x/cnn.m3u8")
        assertEquals("cnn.us", ch[0].tvgId)
        assertEquals("US News", ch[0].group)
        assertEquals("CNN", ch[0].name)
    }

    @Test fun extGrpVlcOptAndPipeHeaders() {
        val ch = parse(
            "#EXTINF:-1,Channel A\n#EXTGRP:Sports\n#EXTVLCOPT:http-user-agent=MyUA/1.0\n#EXTVLCOPT:http-referrer=http://ref/\nhttp://a/1.m3u8\n" +
                "#EXTINF:-1 group-title=\"News\",Channel B\nhttp://b/2.m3u8|User-Agent=Foo%20Bar&Referer=http://r2/\n"
        )
        assertEquals("Sports", ch[0].group)
        assertEquals("MyUA/1.0", ch[0].userAgent)
        assertEquals("http://ref/", ch[0].referrer)
        assertEquals("http://b/2.m3u8", ch[1].url)
        assertEquals("Foo Bar", ch[1].userAgent)
        assertEquals("http://r2/", ch[1].referrer)
        // headers must not leak into the next entry
        assertNull(parse("#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=X\nhttp://a\n#EXTINF:-1,B\nhttp://b")[1].userAgent)
    }

    @Test fun missingNameAndBareUrls() {
        val ch = parse("#EXTINF:-1 tvg-name=\"Fallback Name\",\nhttp://a/1.ts\nhttp://bare/stream.m3u8\n#EXTINF:-1,  Spaced   Name  \nhttp://c")
        assertEquals("Fallback Name", ch[0].name)
        assertEquals("stream.m3u8", ch[1].name)
        assertEquals("Ungrouped", ch[1].group)
        assertEquals("Spaced Name", ch[2].name)
    }

    @Test fun separatorsAndVod() {
        assertTrue(PlaylistFilters.isSeparator("##### UK SPORTS #####"))
        assertTrue(PlaylistFilters.isSeparator("===== NEWS ====="))
        assertTrue(PlaylistFilters.isSeparator("★★★ USA ★★★"))
        assertTrue(PlaylistFilters.isSeparator("|UK| ------ SPORTS ------"))
        assertFalse(PlaylistFilters.isSeparator("|UK| BBC One"))
        assertFalse(PlaylistFilters.isSeparator("Sky Sports F1 - HD"))
        assertTrue(PlaylistFilters.isVod("http://h/movie/u/p/123.mkv"))
        assertTrue(PlaylistFilters.isVod("http://h/series/u/p/9.mp4"))
        assertFalse(PlaylistFilters.isVod("http://h/live/u/p/9.ts"))
        assertFalse(PlaylistFilters.isVod("http://h/u/p/9"))
    }

    private fun lang(name: String, group: String = "Ungrouped", tvgId: String? = null, tvgLang: String? = null, tvgCountry: String? = null) =
        LanguageTagger.detect(Channel(name = name, url = "u", group = group, tvgId = tvgId, tvgLanguage = tvgLang, tvgCountry = tvgCountry))

    @Test fun languageFromGroups() {
        assertEquals("en", lang("Sky Sports Main Event", "UK | SPORTS"))
        assertEquals("en", lang("x", "|UK| ENTERTAINMENT"))
        assertEquals("en", lang("x", "[UK] Documentaries"))
        assertEquals("en", lang("x", "USA: NEWS"))
        assertEquals("en", lang("x", "ENGLISH NEWS"))
        assertEquals("en", lang("x", "VIP UK SPORTS"))
        assertEquals("en", lang("x", "United Kingdom"))
        assertEquals("ar", lang("x", "AR | BEIN SPORTS"))
        assertEquals("ar", lang("x", "ARABIC MOVIES"))
        assertEquals("fr", lang("x", "FR - CINEMA"))
        assertEquals("de", lang("x", "DE: Sport"))
        assertEquals("es", lang("x", "ESPAÑA DEPORTES"))
        assertEquals("es", lang("x", "LATIN AMERICA"))
        assertEquals("sr", lang("x", "EX-YU | SPORT"))
        assertEquals("tr", lang("x", "TÜRKİYE ULUSAL"))
        assertEquals("pt", lang("x", "BR | Globo"))
        assertEquals("en", lang("x", "Sports (UK)"))
        assertEquals("", lang("x", "SPORTS"))
        assertEquals("", lang("x", "Movies & Docs"))
    }

    @Test fun languageFromNames() {
        assertEquals("en", lang("UK: BBC One HD", "Entertainment"))
        assertEquals("en", lang("CNN (US)", "News"))
        assertEquals("en", lang("BBC One [UK] HD", "News"))
        assertEquals("en", lang("ESPN - US", "Sports"))
        assertEquals("en", lang("Al Jazeera English", "AR | NEWS"))
        assertEquals("en", lang("France 24 English", "FR | INFO"))
        assertEquals("ar", lang("BBC Arabic", "UK | NEWS"))
        // ordinary words must not be mistaken for country codes
        assertEquals("", lang("Al Jazeera Documentary", "Documentaries"))
        assertEquals("en", lang("BT Sport 1 HD", "Sports"))   // UK broadcaster
        assertEquals("en", lang("ITV 1 HD", "Entertainment"))
        assertEquals("en", lang("Sky Sports F1 - HD", "Sports"))
        assertEquals("", lang("Sky Sport Bundesliga", "Sports"))
    }

    @Test fun languageFromAttributes() {
        assertEquals("en", lang("x", tvgLang = "English"))
        assertEquals("es", lang("x", tvgLang = "Spanish;English"))
        assertEquals("fr", lang("x", tvgLang = "fra"))
        assertEquals("en", lang("x", tvgCountry = "UK"))
        assertEquals("es", lang("x", tvgCountry = "AR"))   // ISO meaning: Argentina
        assertEquals("en", lang("x", tvgId = "BBCOne.uk@HD"))
        assertEquals("de", lang("x", tvgId = "DasErste.de"))
        assertEquals("", lang("x", tvgId = "something.com"))
        // explicit attribute beats the group
        assertEquals("ar", lang("x", "UK | NEWS", tvgLang = "Arabic"))
    }

    private fun region(name: String, group: String = "Ungrouped", tvgId: String? = null, tvgCountry: String? = null) =
        RegionTagger.detect(Channel(name = name, url = "u", group = group, tvgId = tvgId, tvgCountry = tvgCountry))

    @Test fun regionSeparatesUkFromOtherEnglish() {
        assertEquals("UK", region("x", "UK | SPORTS"))
        assertEquals("UK", region("x", "|UK| ENTERTAINMENT"))
        assertEquals("UK", region("x", "United Kingdom"))
        assertEquals("UK", region("x", "BRITISH DRAMA"))
        assertEquals("UK", region("x", "VIP GB SPORTS"))
        assertEquals("US", region("x", "USA | NEWS"))
        assertEquals("US", region("x", "US: LOCALS"))
        assertEquals("CA", region("x", "CA | SPORTS"))
        assertEquals("IE", region("x", "IRELAND"))
        assertEquals("AU", region("x", "AUS | FOXTEL"))
        assertEquals("UK", region("UK: BBC One HD", "ENTERTAINMENT"))
        assertEquals("US", region("CNN (US)", "NEWS"))
        assertEquals("UK", region("x", tvgCountry = "GB"))
        assertEquals("UK", region("x", tvgId = "BBCOne.uk"))
        assertEquals("US", region("x", tvgId = "cnn.us"))
    }

    @Test fun regionAvoidsFalseCountries() {
        assertEquals("", region("x", "ENGLISH NEWS"))       // a language, not a country
        assertEquals("", region("x", "EN | SPORTS"))
        assertEquals("", region("x", "AR | NEWS"))          // Arabic, not Argentina
        assertEquals("", region("x", "LATIN AMERICA"))      // must not become US via "America"
        assertEquals("AR", region("x", tvgCountry = "AR"))  // real ISO: Argentina
        assertEquals("", region("Al Jazeera", "NEWS"))
    }

    @Test fun ukBroadcastersWithNoTags() {
        assertEquals("UK", region("BBC One HD", "ENTERTAINMENT"))
        assertEquals("UK", region("ITV2 FHD", "ENTERTAINMENT"))
        assertEquals("UK", region("Sky Sports Main Event", "SPORTS"))
        assertEquals("UK", region("TNT Sports 1", "SPORTS"))
        assertEquals("UK", region("Channel 4", "ENTERTAINMENT"))
        assertEquals("", region("Sky Sport Bundesliga 1", "SPORTS"))   // German Sky is singular
        assertEquals("", region("Sky Sport NZ 1", "SPORTS").takeIf { it != "NZ" } ?: "")
        assertEquals("DE", region("Sky Sport Bundesliga 1", "DE | SPORT"))
        assertEquals("en", LanguageTagger.detect(Channel("Dave HD", "u", "ENTERTAINMENT")))
        // explicit tags still win over brand guesses
        assertEquals("US", region("BBC America", "USA | ENTERTAINMENT"))
    }

    @Test fun duplicateVersionsShareAKey() {
        val k = ChannelNames.key("BBC One")
        for (n in listOf("UK | BBC One FHD", "UK: BBC ONE HD", "BBC One (UK) SD", "BBC One HD ᴿᴬᵂ", "|UK| BBC One 1080p BACKUP", "BBC ONE ᴴᴰ"))
            assertEquals(n, k, ChannelNames.key(n))
        // genuinely different channels stay apart
        assertFalse(ChannelNames.key("BBC Two HD") == k)
        assertFalse(ChannelNames.key("ITV1 +1") == ChannelNames.key("ITV1 HD"))
        assertEquals(ChannelNames.key("ITV1+1"), ChannelNames.key("ITV1 +1"))
        assertFalse(ChannelNames.key("Sky Sports 1") == ChannelNames.key("Sky Sports 2"))
    }

    @Test fun displayNamesAndQuality() {
        assertEquals("BBC One", ChannelNames.display("UK | BBC One FHD"))
        assertEquals("Sky Sports Main Event", ChannelNames.display("|UK| Sky Sports Main Event HD ᴿᴬᵂ"))
        assertEquals("CNN", ChannelNames.display("CNN (US) HD"))
        assertEquals("ITV1 +1", ChannelNames.display("UK: ITV1 +1 HD"))
        assertEquals("HD", ChannelNames.display("HD"))    // never blank
        val ordered = listOf("BBC One SD", "BBC One 4K", "BBC One backup", "BBC One HD", "BBC One FHD", "BBC One")
            .sortedBy { ChannelNames.qualityRank(it) }
        assertEquals(listOf("BBC One FHD", "BBC One HD", "BBC One", "BBC One 4K", "BBC One SD", "BBC One backup"), ordered)
        assertEquals("FHD", ChannelNames.versionLabel("BBC One FHD"))
        assertEquals("Standard", ChannelNames.versionLabel("BBC One"))
    }

    @Test fun categories() {
        assertEquals("sports", Categories.detect("UK | SPORTS", "Anything"))
        assertEquals("sports", Categories.detect("ENTERTAINMENT", "Sky Sports Main Event"))
        assertEquals("news", Categories.detect("UK | ENTERTAINMENT", "Sky News HD"))
        assertEquals("news", Categories.detect("FR | INFO", "France 24"))
        assertEquals("movies", Categories.detect("UK | MOVIES", "Film4"))
        assertEquals("movies", Categories.detect("General", "Sky Cinema Premiere"))
        assertEquals("kids", Categories.detect("UK | KIDS", "CBeebies"))
        assertEquals("kids", Categories.detect("Other", "Nickelodeon"))
        assertEquals("docs", Categories.detect("UK | DOCUMENTARIES", "Discovery"))
        assertEquals("music", Categories.detect("UK | MUSIC", "MTV"))
        assertEquals("entertainment", Categories.detect("UK | ENTERTAINMENT", "BBC One"))
        assertEquals("adult", Categories.detect("XXX | ADULT", "Something"))
        assertEquals("adult", Categories.detect("FOR ADULTS 18+", "Something"))
        assertEquals("", Categories.detect("VIP", "BBC One"))
        // "Adult" must not trigger on ordinary words
        assertEquals("entertainment", Categories.detect("UK | ENTERTAINMENT", "Adult Swim"))
    }

    @Test fun countryPrefixVariantsProvidersUse() {
        for (g in listOf("UK| General", "UK|General", "UK\u00A0| General", "UK\u200B| General", "UK┃ General", "UKǀ General",
                "UK❘ General", "𝗨𝗞| General", "ＵＫ| General", "🇬🇧| General", "🇬🇧 UK| General", "[ UK ] General")) {
            assertEquals(g, "UK", RegionTagger.fromGroup(g))
            assertEquals(g, "en", LanguageTagger.fromGroup(g))
        }
        assertEquals("US", RegionTagger.fromGroup("🇺🇸| News"))
    }

    @Test fun alternateGroupFields() {
        val ch = parse("#EXTINF:-1 tvg-group=\"UK| General\",BBC One\nhttp://a/1.ts\n#EXTINF:-1 group=\"US| News\",CNN\nhttp://a/2.ts")
        assertEquals("UK| General", ch[0].group)
        assertEquals("US| News", ch[1].group)
    }

    @Test fun xtreamCategoryIdsList() {
        val cats = """[{"category_id":"7","category_name":"UK| General"}]"""
        val streams = """[{"name":"BBC One","stream_id":1,"category_id":null,"category_ids":[7]}]"""
        assertEquals("UK| General", Xtream.mapStreams(cats, streams, Xtream.Creds("h", "u", "p"))[0].group)
    }

    @Test fun splitsLiveMoviesAndEpisodes() {
        fun k(url: String, name: String = "x") = PlaylistFilters.kindOf(Channel(name, url, "g"))
        assertEquals(PlaylistFilters.Kind.LIVE, k("http://h/live/u/p/1.ts"))
        assertEquals(PlaylistFilters.Kind.LIVE, k("http://h/u/p/1"))
        assertEquals(PlaylistFilters.Kind.MOVIE, k("http://h/movie/u/p/1.mkv"))
        assertEquals(PlaylistFilters.Kind.MOVIE, k("http://cdn/films/Heat.1995.mp4"))
        assertEquals(PlaylistFilters.Kind.EPISODE, k("http://h/series/u/p/9.mkv"))
        assertEquals(PlaylistFilters.Kind.EPISODE, k("http://cdn/x/9.mp4", "Breaking Bad S01E02"))
        // a live channel name that happens to look like an episode stays live
        assertEquals(PlaylistFilters.Kind.LIVE, k("http://h/live/u/p/5.ts", "Show S01E01 Live"))
        assertEquals(PlaylistFilters.EpisodeName("Breaking Bad", 1, 2), PlaylistFilters.episodeName("Breaking Bad S01 E02"))
        assertEquals(PlaylistFilters.EpisodeName("The Office (US)", 3, 14), PlaylistFilters.episodeName("The Office (US) - S03E14 - Ben Franklin"))
        assertEquals(PlaylistFilters.EpisodeName("Doctor Who", 12, 1), PlaylistFilters.episodeName("Doctor Who S12.E01"))
    }

    @Test fun xtreamMoviesSeriesAndEpisodes() {
        val c = Xtream.Creds("http://h.tv:80", "u", "p")
        val movies = Xtream.mapMovies(
            """[{"category_id":"1","category_name":"EN| ACTION"}]""",
            """[{"name":"Heat","stream_id":11,"stream_icon":"http://i/heat.jpg","category_id":"1","container_extension":"mkv"},
                {"name":"No Ext","stream_id":12,"category_id":"1"}]""", c)
        assertEquals("http://h.tv:80/movie/u/p/11.mkv", movies[0].url)
        assertEquals("http://h.tv:80/movie/u/p/12.mp4", movies[1].url)
        assertEquals("EN| ACTION", movies[0].group)
        val series = Xtream.mapSeries("""[{"category_id":"5","category_name":"UK| DRAMA"}]""",
            """[{"name":"Line of Duty","series_id":77,"cover":"http://i/lod.jpg","category_id":"5"}]""")
        assertEquals("xtseries:77", series[0].url)
        assertEquals("UK| DRAMA", series[0].group)
        // episodes keyed by season...
        val a = Xtream.parseSeriesInfo("""{"info":{"plot":"Cops."},"episodes":{"2":[{"id":"202","episode_num":1,"season":2,"title":"S2E1","container_extension":"mkv"}],
            "1":[{"id":"102","episode_num":2,"season":1,"title":"Two"},{"id":"101","episode_num":1,"season":1,"title":"One","info":{"movie_image":"http://i/e1.jpg"}}]}}""", c)
        assertEquals("Cops.", a.plot)
        assertEquals(listOf("One", "Two", "S2E1"), a.episodes.map { it.title })
        assertEquals("http://h.tv:80/series/u/p/101.mp4", a.episodes[0].url)
        assertEquals("http://i/e1.jpg", a.episodes[0].image)
        // ...or as a list of lists on some panels
        val b = Xtream.parseSeriesInfo("""{"episodes":[[{"id":"1","episode_num":1,"season":1,"title":"A"}],[{"id":"2","episode_num":1,"season":2,"title":"B"}]]}""", c)
        assertEquals(listOf(1, 2), b.episodes.map { it.season })
    }

    @Test fun rules() {
        val r = LangRules.parse(
            """
            # comment
            SKY SPORTS => en
            group:^VIP => hide
            name:\bARABIA\b => Arabic
            broken line
            ( => en
            foo => klingon
            """.trimIndent()
        )
        assertEquals(3, r.rules.size)
        assertEquals(3, r.errors.size)
        assertEquals("en", r.match(Channel("Sky Sports Main Event", "u", "Sports")))
        assertEquals(LangRules.HIDE, r.match(Channel("Anything", "u", "VIP Channels")))
        assertNull(r.match(Channel("VIP Lounge", "u", "Other")))
        assertEquals("ar", r.match(Channel("MBC Arabia", "u", "News")))
    }

    @Test fun xtreamUrls() {
        assertEquals("http://host.tv:8080", Xtream.normalizeServer("host.tv:8080/"))
        assertEquals("https://h.tv", Xtream.normalizeServer("https://h.tv/player_api.php?username=a"))
        val c = Xtream.fromGetPhp("http://h.tv:8080/get.php?username=bob&password=s3cret&type=m3u_plus&output=m3u8")!!
        assertEquals("http://h.tv:8080", c.server)
        assertEquals("bob", c.user)
        assertEquals("s3cret", c.pass)
        assertEquals("m3u8", c.format)
        assertNull(Xtream.fromGetPhp("http://h.tv/playlist.m3u"))
    }

    @Test fun xtreamMapping() {
        val cats = """[{"category_id":"1","category_name":"UK | SPORTS","parent_id":0},{"category_id":2,"category_name":"AR | NEWS"}]"""
        val streams = """[
            {"num":1,"name":"Sky Sports  News","stream_type":"live","stream_id":101,"stream_icon":"http://l/1.png","epg_channel_id":"skynews.uk","category_id":"1"},
            {"num":2,"name":"Al Arabiya","stream_id":"202","stream_icon":"","epg_channel_id":null,"category_id":2},
            {"num":3,"name":"Orphan","stream_id":303,"category_id":"99"},
            {"num":4,"name":"","stream_id":404,"category_id":"1"}
        ]"""
        val list = Xtream.mapStreams(cats, streams, Xtream.Creds("h.tv:80", "u", "p", "ts"))
        assertEquals(3, list.size)
        assertEquals("Sky Sports News", list[0].name)
        assertEquals("http://h.tv:80/live/u/p/101.ts", list[0].url)
        assertEquals("UK | SPORTS", list[0].group)
        assertNull(list[1].logo)
        assertNull(list[1].tvgId)
        assertEquals("AR | NEWS", list[1].group)
        assertEquals("Ungrouped", list[2].group)
        val tagged = RegionTagger.detectAll(LanguageTagger.detectAll(list))
        assertEquals(listOf("en", "ar", ""), tagged.map { it.detectedLang })
        assertEquals(listOf("UK", "", ""), tagged.map { it.detectedRegion })
    }

    @Test(expected = java.io.IOException::class)
    fun xtreamRejectedLogin() {
        Xtream.checkCategoriesResponse("""{"user_info":{"auth":0}}""")
    }

    @Test fun bigPlaylistIsFast() {
        val sb = StringBuilder("#EXTM3U\n")
        val groups = listOf("UK | SPORTS", "US | NEWS", "AR | MOVIES", "FR | KIDS", "DE | DOCS")
        repeat(60_000) { i ->
            sb.append("#EXTINF:-1 tvg-id=\"c$i.xx\" tvg-logo=\"http://l/$i.png\" group-title=\"${groups[i % 5]}\",Channel $i HD\n")
            sb.append("http://host:8080/live/user/pass/$i.ts\n")
        }
        val t0 = System.nanoTime()
        val ch = LanguageTagger.detectAll(M3uParser.parse(sb.toString().reader().buffered()).channels)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(60_000, ch.size)
        assertEquals(12_000, ch.count { it.detectedLang == "en" && it.group.startsWith("UK") })
        println("Parsed and tagged 60k channels in ${ms}ms")
        val t1 = System.nanoTime()
        val keys = ch.map { ChannelNames.key(it.name) }
        val cats = ch.map { Categories.detect(it.group, it.name) }
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        println("Name keys + categories for 60k in ${ms2}ms (${keys.toSet().size} distinct, ${cats.count { it == "sports" }} sports)")
        assertTrue("navigation prep too slow: ${ms2}ms", ms2 < 5000)
        assertTrue("too slow: ${ms}ms", ms < 5000)
    }
}
