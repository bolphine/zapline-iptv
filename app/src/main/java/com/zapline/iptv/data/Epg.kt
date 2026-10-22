package com.zapline.iptv.data

import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.model.Channel
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class Programme(val start: Long, val end: Long, val title: String, val desc: String?)

/**
 * TV guide data, keyed by the guide's own channel id.
 * Channels are matched by tvg-id / epg_channel_id first, then by normalised display name.
 */
class EpgData(
    val byId: Map<String, List<Programme>>,
    /** ChannelNames.key(display-name) -> guide channel id, for playlists without tvg-ids. */
    val idByNameKey: Map<String, String>,
    val loadedAt: Long,
) {
    /** Guide id for any of these playlist channels (versions of one channel), or null. */
    fun resolve(channels: List<Channel>): String? {
        for (c in channels) {
            val id = c.tvgId ?: continue
            if (byId.containsKey(id)) return id
            // ids differ only in case surprisingly often
            byIdLower[id.lowercase()]?.let { return it }
        }
        for (c in channels) idByNameKey[ChannelNames.key(c.name)]?.let { return it }
        return null
    }

    private val byIdLower: Map<String, String> by lazy { byId.keys.associateBy { it.lowercase() } }

    fun programmes(id: String?): List<Programme> = if (id == null) emptyList() else byId[id] ?: emptyList()

    /** The programme airing at [now] and the one after it. */
    fun nowNext(id: String?, now: Long): Pair<Programme?, Programme?> {
        val list = programmes(id)
        if (list.isEmpty()) return null to null
        // binary search for the last programme starting at or before now
        var lo = 0; var hi = list.size - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val cur = list.getOrNull(idx)?.takeIf { now < it.end }
        val next = list.getOrNull(idx + 1) ?: if (idx == -1) list.firstOrNull() else null
        return cur to next
    }

    val channelCount get() = byId.size

    companion object { val EMPTY = EpgData(emptyMap(), emptyMap(), 0) }
}

/** Streaming XMLTV reader. Keeps only wanted channels and a time window, so huge guides fit in memory. */
object XmltvParser {

    /**
     * @param wantedIds tvg-ids used by the playlist
     * @param wantedNameKeys ChannelNames.key() of playlist channel names (fallback matching)
     */
    fun parse(
        parser: XmlPullParser,
        wantedIds: Set<String>,
        wantedNameKeys: Set<String>,
        windowStart: Long,
        windowEnd: Long,
        onProgress: ((Int) -> Unit)? = null,
    ): EpgData {
        val wantedLower = wantedIds.mapTo(HashSet()) { it.lowercase() }
        val keepIds = HashSet<String>()
        val idByNameKey = HashMap<String, String>()
        val progs = HashMap<String, ArrayList<Programme>>()
        var count = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val names = ArrayList<String>(2)
                        // read display-names until </channel>
                        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "channel")) {
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name") {
                                names += parser.nextText().trim()
                            }
                            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                        }
                        if (id != null) {
                            if (id.lowercase() in wantedLower) keepIds += id
                            for (n in names) {
                                val k = ChannelNames.key(n)
                                if (k in wantedNameKeys && !idByNameKey.containsKey(k)) { idByNameKey[k] = id; keepIds += id }
                            }
                        }
                    }
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")
                        // Programmes for channels not declared above can still match by id.
                        val keep = ch != null && (ch in keepIds || ch.lowercase() in wantedLower)
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        var title: String? = null
                        var desc: String? = null
                        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
                            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                            if (keep && parser.eventType == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "title" -> if (title == null) title = parser.nextText().trim()
                                    "desc" -> if (desc == null) desc = parser.nextText().trim().take(400)
                                }
                            }
                        }
                        if (keep && start > 0 && stop > start && stop > windowStart && start < windowEnd) {
                            keepIds += ch!!
                            progs.getOrPut(ch) { ArrayList() } += Programme(start, stop, title?.ifEmpty { null } ?: "Untitled", desc?.ifEmpty { null })
                            count++
                            if (onProgress != null && count % 2000 == 0) onProgress(count)
                        }
                    }
                }
            }
            event = parser.next()
        }
        val byId = progs.mapValues { (_, l) -> dedupe(l.sortedBy { it.start }) }
        return EpgData(byId, idByNameKey.filterValues { byId.containsKey(it) }, System.currentTimeMillis())
    }

    /** Guides sometimes repeat or overlap entries; keep a clean, non-overlapping sequence. */
    private fun dedupe(l: List<Programme>): List<Programme> {
        val out = ArrayList<Programme>(l.size)
        for (p in l) {
            val last = out.lastOrNull()
            if (last != null && p.start < last.end) {
                if (p.start == last.start) continue
                out[out.size - 1] = last.copy(end = p.start)
            }
            out += p
        }
        return out
    }

    /** XMLTV time: "20261021180000 +0100", offset optional (then UTC), seconds optional. */
    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.length < 12) return 0
        return try {
            val y = digits.substring(0, 4).toInt(); val mo = digits.substring(4, 6).toInt(); val d = digits.substring(6, 8).toInt()
            val h = digits.substring(8, 10).toInt(); val mi = digits.substring(10, 12).toInt()
            val sec = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            var epoch = java.time.LocalDateTime.of(y, mo, d, h, mi, sec).toEpochSecond(java.time.ZoneOffset.UTC) * 1000
            val off = Regex("([+-])(\\d{2}):?(\\d{2})").find(t.substring(digits.length))
            if (off != null) {
                val sign = if (off.groupValues[1] == "-") -1 else 1
                val mins = off.groupValues[2].toInt() * 60 + off.groupValues[3].toInt()
                epoch -= sign * mins * 60_000L
            }
            epoch
        } catch (e: Exception) { 0 }
    }

    /** Handles plain and gzipped guides (by magic bytes, not by file name). */
    fun maybeGunzip(input: InputStream): InputStream {
        val b = BufferedInputStream(input, 1 shl 16)
        b.mark(2)
        val m1 = b.read(); val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) BufferedInputStream(GZIPInputStream(b, 1 shl 16), 1 shl 16) else b
    }
}

object EpgCache {
    private const val VERSION = 1

    fun save(file: File, e: EpgData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 1 shl 16)).use { o ->
            o.writeInt(VERSION); o.writeLong(e.loadedAt)
            o.writeInt(e.byId.size)
            for ((id, l) in e.byId) {
                o.writeUTF(id); o.writeInt(l.size)
                for (p in l) {
                    o.writeLong(p.start); o.writeLong(p.end); o.writeUTF(p.title.take(500))
                    o.writeBoolean(p.desc != null); p.desc?.let { o.writeUTF(it) }
                }
            }
            o.writeInt(e.idByNameKey.size)
            for ((k, v) in e.idByNameKey) { o.writeUTF(k); o.writeUTF(v) }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun load(file: File): EpgData? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()), 1 shl 16)).use { i ->
                if (i.readInt() != VERSION) return null
                val at = i.readLong()
                val byId = HashMap<String, List<Programme>>()
                repeat(i.readInt()) {
                    val id = i.readUTF()
                    val n = i.readInt()
                    val l = ArrayList<Programme>(n)
                    repeat(n) {
                        val s = i.readLong(); val e = i.readLong(); val t = i.readUTF()
                        val d = if (i.readBoolean()) i.readUTF() else null
                        l += Programme(s, e, t, d)
                    }
                    byId[id] = l
                }
                val names = HashMap<String, String>()
                repeat(i.readInt()) { names[i.readUTF()] = i.readUTF() }
                EpgData(byId, names, at)
            }
        } catch (e: Exception) { null }
    }
}

/** Process-wide guide, shared by the list screens and the player. */
object EpgStore {
    @Volatile var data: EpgData = EpgData.EMPTY
}
