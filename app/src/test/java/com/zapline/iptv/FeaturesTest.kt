package com.zapline.iptv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zapline.iptv.data.Backup
import com.zapline.iptv.data.Catchup
import com.zapline.iptv.data.Playlists
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Programme
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.M3uParser
import com.zapline.iptv.parse.Xtream
import com.zapline.iptv.player.Caster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // 2026-10-21 18:00 UTC
    private val t0 = 1792605600000L

    @Test fun xtreamCatchupUrlUsesServerTimezone() {
        val c = Channel("BBC One", "http://h.tv:8080/live/u/p/101.ts", "UK", catchupDays = 7, catchupType = "xc")
        assertEquals("http://h.tv:8080/timeshift/u/p/60/2026-10-21:18-00/101.ts", Catchup.url(c, t0, t0 + 3600_000, "UTC"))
        assertEquals("http://h.tv:8080/timeshift/u/p/30/2026-10-21:19-00/101.ts", Catchup.url(c, t0, t0 + 1800_000, "Europe/London"))
    }

    @Test fun m3uCatchupAttributesAndTemplates() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-days="5" catchup-source="?utc={utc}&lutc={lutc}",Alpha
            http://x/alpha.m3u8
            #EXTINF:-1 tvg-id="b" tvg-rec="3",Beta
            http://x/beta/index.m3u8
            #EXTINF:-1,Gamma
            http://x/gamma.m3u8
            """.trimIndent().reader().buffered()
        ).channels
        assertEquals(5, r[0].catchupDays); assertEquals("append", r[0].catchupType)
        assertEquals(3, r[1].catchupDays)
        assertEquals(0, r[2].catchupDays)
        val now = t0 + 7200_000
        assertEquals("http://x/alpha.m3u8?utc=${t0 / 1000}&lutc=${now / 1000}", Catchup.url(r[0], t0, t0 + 3600_000, null, now))
        val flus = r[1].copy(catchupType = "flussonic")
        assertEquals("http://x/beta/archive-${t0 / 1000}-3600.m3u8", Catchup.url(flus, t0, t0 + 3600_000, null, now))
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("http://a/rec/2026/10/21/18-00.ts?d=60", Catchup.fill("http://a/rec/{Y}/{m}/{d}/{H}-{M}.ts?d={duration:60}", t0, t0 + 3600_000, now))
            assertEquals("s=20261021180000", Catchup.fill("s={utc:YmdHMS}", t0, t0 + 3600_000, now))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun catchupWindowAndPlayEntry() {
        val c = Channel("Sky One", "http://h/live/u/p/7.ts", "UK", catchupDays = 2, catchupType = "xc")
        val now = t0
        assertNotNull(Catchup.channelFor(listOf(c), now - 3600_000, now))
        assertNull("older than the archive", Catchup.channelFor(listOf(c), now - 3 * 86_400_000L, now))
        assertNull("future", Catchup.channelFor(listOf(c), now + 60_000, now))
        val e = Catchup.playEntry("UK|en|SKY ONE", "Sky One", listOf(c), Programme(now - 3600_000, now - 1800_000, "The Simpsons", null), "UTC", now)!!
        assertTrue(e.key.startsWith("vod|catchup|"))
        assertEquals("The Simpsons · Sky One", e.name)
        assertTrue(e.variants[0].url.contains("/timeshift/u/p/30/"))
    }

    @Test fun xtreamArchiveFlags() {
        val cats = """[{"category_id":"1","category_name":"UK| General"}]"""
        val streams = """[
            {"name":"BBC One","stream_id":1,"category_id":"1","tv_archive":1,"tv_archive_duration":"7"},
            {"name":"BBC Two","stream_id":2,"category_id":"1","tv_archive":0,"tv_archive_duration":0}
        ]"""
        val l = Xtream.mapStreams(cats, streams, Xtream.Creds("h", "u", "p"))
        assertEquals(7, l[0].catchupDays); assertEquals("xc", l[0].catchupType)
        assertEquals(0, l[1].catchupDays)
        assertEquals("Europe/London", Xtream.serverTimezone("""{"user_info":{},"server_info":{"timezone":"Europe/London"}}"""))
    }

    @Test fun castUrls() {
        assertEquals(com.zapline.iptv.player.CastProxy.Kind.LIVE_TS, com.zapline.iptv.player.CastProxy.kindOf("http://h:80/live/u/p/101.ts", true))
        assertEquals(com.zapline.iptv.player.CastProxy.Kind.HLS, com.zapline.iptv.player.CastProxy.kindOf("http://h/a/index.m3u8?t=1", true))
        assertEquals(com.zapline.iptv.player.CastProxy.Kind.FILE, com.zapline.iptv.player.CastProxy.kindOf("http://h/movie/u/p/5.mkv", false))
        assertEquals("application/x-mpegURL", Caster.mimeOf("http://h/a.m3u8?token=1"))
        assertEquals("video/mp4", Caster.mimeOf("http://h/movie/u/p/5.mp4"))
        assertEquals("video/x-matroska", Caster.mimeOf("http://h/movie/u/p/5.mkv"))
    }

    @Test fun playlistsAreRememberedAndSwitched() {
        val p = Prefs(ctx)
        p.p.edit().clear().commit()
        p.sourceType = "xtream"; p.xtServer = "http://one.tv"; p.xtUser = "a"; p.xtPass = "1"
        Playlists.saveCurrent(p, "Provider One")
        p.sourceType = "m3u"; p.m3uUrl = "http://two.tv/list.m3u"; p.activePlaylist = ""
        Playlists.saveCurrent(p)
        val all = Playlists.all(p)
        assertEquals(2, all.size)
        assertEquals("Provider One", all[0].name)
        assertEquals("two.tv", all[1].name)
        Playlists.apply(p, all[0])
        assertEquals("xtream", p.sourceType); assertEquals("http://one.tv", p.xtServer); assertEquals(all[0].id, p.activePlaylist)
        // saving again updates rather than duplicates
        Playlists.saveCurrent(p)
        assertEquals(2, Playlists.all(p).size)
    }

    @Test fun backupRoundTrip() {
        val p = Prefs(ctx)
        p.p.edit().clear().commit()
        p.favourites = setOf("http://a", "http://b"); p.favOrder = listOf("http://b", "http://a")
        p.rules = "UK* => en"; p.accent = 0xFF33CCFF.toInt(); p.adultPin = "4321"; p.subtitlesOn = true
        val json = Backup.export(Prefs(ctx))
        p.p.edit().clear().commit()
        assertTrue(p.favourites.isEmpty())
        val n = Backup.import(Prefs(ctx), json)
        assertTrue(n >= 6)
        assertEquals(setOf("http://a", "http://b"), p.favourites)
        assertEquals(listOf("http://b", "http://a"), p.favOrder)
        assertEquals("UK* => en", p.rules); assertEquals(0xFF33CCFF.toInt(), p.accent)
        assertEquals("4321", p.adultPin); assertTrue(p.subtitlesOn)
        try { Backup.import(Prefs(ctx), "{\"hello\":1}"); throw AssertionError("should reject") } catch (e: IllegalArgumentException) {}
    }

    @Test fun seriesProgressIsTrackedPerShow() {
        val p = Prefs(ctx); p.p.edit().clear().commit()
        val sw = com.zapline.iptv.data.SeriesWatch
        sw.register("http://h/series/u/p/11.mkv", com.zapline.iptv.data.SeriesWatch.Ref("xtseries:1", "The Show", null, "S1 E1"))
        sw.register("http://h/series/u/p/12.mkv", com.zapline.iptv.data.SeriesWatch.Ref("xtseries:1", "The Show", null, "S1 E2"))
        sw.register("http://h/series/u/p/90.mkv", com.zapline.iptv.data.SeriesWatch.Ref("xtseries:2", "Other", null, "S3 E4"))
        sw.record(p, "http://h/series/u/p/11.mkv", finished = false)
        sw.record(p, "http://h/series/u/p/11.mkv", finished = true)
        Thread.sleep(5)
        sw.record(p, "http://h/series/u/p/90.mkv", finished = false)
        sw.record(p, "http://unknown/ep.mkv", finished = false)       // not from a show: ignored
        val all = sw.all(p)
        assertEquals(listOf("xtseries:2", "xtseries:1"), all.map { it.showUrl })   // newest first
        assertTrue(all[1].finished); assertEquals("S1 E1", all[1].code)
        sw.record(p, "http://h/series/u/p/12.mkv", finished = false)
        assertEquals("S1 E2", sw.all(p).first().code)
    }
}
