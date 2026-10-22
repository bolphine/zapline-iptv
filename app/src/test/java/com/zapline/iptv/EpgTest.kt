package com.zapline.iptv

import com.zapline.iptv.data.EpgCache
import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.XmltvParser
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.Xtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Reader
import java.util.zip.GZIPOutputStream

class EpgTest {

    private val base = XmltvParser.parseTime("20261021180000 +0000")

    private fun xml(body: String) = """<?xml version="1.0" encoding="UTF-8"?><tv>$body</tv>"""

    private fun parse(bytes: ByteArray, ids: Set<String>, names: Set<String> = emptySet(), from: Long = base - 86_400_000, to: Long = base + 86_400_000): EpgData {
        val p = KXmlParser()
        p.setInput(XmltvParser.maybeGunzip(ByteArrayInputStream(bytes)), null)
        return XmltvParser.parse(p, ids, names, from, to)
    }

    @Test fun times() {
        assertEquals(1792605600000L, base)                                   // 2026-10-21 18:00 UTC
        assertEquals(base - 3_600_000, XmltvParser.parseTime("20261021180000 +0100"))
        assertEquals(base + 5 * 3_600_000, XmltvParser.parseTime("20261021180000 -0500"))
        assertEquals(base, XmltvParser.parseTime("20261021180000"))          // no offset = UTC
        assertEquals(base, XmltvParser.parseTime("202610211800"))            // no seconds
        assertEquals(0L, XmltvParser.parseTime("garbage"))
    }

    @Test fun parsesMatchesAndFilters() {
        val doc = xml(
            """
            <channel id="BBCOne.uk"><display-name>BBC One</display-name></channel>
            <channel id="itv1.hd"><display-name lang="en">ITV1 HD</display-name><icon src="x"/></channel>
            <channel id="unwanted.fr"><display-name>TF1</display-name></channel>
            <programme start="20261021170000 +0000" stop="20261021180000 +0000" channel="BBCOne.uk"><title lang="en">Before</title></programme>
            <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="BBCOne.uk"><title>News at Six</title><desc>The headlines.</desc><category>News</category></programme>
            <programme start="20261021190000 +0000" stop="20261021200000 +0000" channel="BBCOne.uk"><title>The One Show</title></programme>
            <programme start="20261021180000 +0000" stop="20261021183000 +0000" channel="itv1.hd"><title>Tipping Point</title></programme>
            <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="unwanted.fr"><title>Journal</title></programme>
            <programme start="20261030180000 +0000" stop="20261030190000 +0000" channel="BBCOne.uk"><title>Far future</title></programme>
            """
        ).toByteArray()
        // BBC by tvg-id; ITV only by name ("ITV1" in the playlist, "ITV1 HD" in the guide)
        val e = parse(doc, setOf("BBCOne.uk"), setOf(ChannelNames.key("UK: ITV1")))
        assertEquals(setOf("BBCOne.uk", "itv1.hd"), e.byId.keys)
        assertEquals(listOf("Before", "News at Six", "The One Show"), e.programmes("BBCOne.uk").map { it.title })   // far future dropped
        val (now, next) = e.nowNext("BBCOne.uk", base + 10 * 60_000)
        assertEquals("News at Six", now!!.title)
        assertEquals("The headlines.", now.desc)
        assertEquals("The One Show", next!!.title)
        assertEquals("itv1.hd", e.resolve(listOf(Channel("ITV1 FHD", "u", "g"))))
        assertEquals("BBCOne.uk", e.resolve(listOf(Channel("BBC One", "u", "g", tvgId = "bbcone.UK"))))   // case-insensitive id
        assertNull(e.resolve(listOf(Channel("Nothing", "u", "g"))))
        // gap in the schedule: nothing on now, but "next" still known
        val (gapNow, gapNext) = e.nowNext("itv1.hd", base - 30 * 60_000)
        assertNull(gapNow); assertEquals("Tipping Point", gapNext!!.title)
    }

    @Test fun gzippedGuidesAndCache() {
        val doc = xml("""<channel id="a"><display-name>A</display-name></channel>
            <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="a"><title>Show</title></programme>""")
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(doc.toByteArray()) } }.toByteArray()
        val e = parse(gz, setOf("a"))
        assertEquals("Show", e.programmes("a").single().title)
        val f = File.createTempFile("epg", ".bin")
        EpgCache.save(f, e)
        val back = EpgCache.load(f)!!
        assertEquals(e.programmes("a"), back.programmes("a"))
        f.delete()
    }

    @Test fun overlappingEntriesAreCleaned() {
        val doc = xml("""
            <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="a"><title>One</title></programme>
            <programme start="20261021180000 +0000" stop="20261021190000 +0000" channel="a"><title>One dup</title></programme>
            <programme start="20261021183000 +0000" stop="20261021193000 +0000" channel="a"><title>Two</title></programme>""")
        val l = parse(doc.toByteArray(), setOf("a")).programmes("a")
        assertEquals(listOf("One", "Two"), l.map { it.title })
        assertEquals(l[1].start, l[0].end)
    }

    /** A 150k-title movie catalogue streamed from a Reader, never materialised as one String. */
    @Test fun hugeXtreamCatalogueStreams() {
        val n = 150_000
        val reader = object : Reader() {
            var i = -1; var buf = "["; var pos = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (pos >= buf.length) {
                    i++
                    if (i > n) return -1
                    buf = if (i == n) "]" else (if (i > 0) "," else "") +
                        """{"num":$i,"name":"Movie $i","stream_id":$i,"stream_icon":"http://i/$i.jpg","category_id":"${i % 40}","container_extension":"mkv","rating":"7.1","added":"1700000000"}"""
                    pos = 0
                }
                val k = minOf(len, buf.length - pos)
                buf.toCharArray(cbuf, off, pos, pos + k); pos += k
                return k
            }
            override fun close() {}
        }
        val cats = (0 until 40).associate { "$it" to "EN| Cat $it" }
        val t0 = System.nanoTime()
        val list = Xtream.mapMovies(cats, reader, Xtream.Creds("h", "u", "p"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Streamed $n movies in ${ms}ms")
        assertEquals(n, list.size)
        assertEquals("EN| Cat 7", list[7].group)
        assertEquals("http://h/movie/u/p/149999.mkv", list.last().url)
        assertTrue(ms < 10_000)
    }

    @Test fun nonArrayBodiesAreEmptyNotCrashes() {
        assertEquals(0, Xtream.mapMovies(emptyMap(), "{}".reader(), Xtream.Creds("h", "u", "p")).size)
        assertEquals(0, Xtream.mapMovies(emptyMap(), "".reader(), Xtream.Creds("h", "u", "p")).size)
        assertEquals(0, Xtream.mapSeries(emptyMap(), "null".reader()).size)
    }
}
