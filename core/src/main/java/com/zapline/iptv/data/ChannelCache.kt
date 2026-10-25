package com.zapline.iptv.data

import com.zapline.iptv.model.Channel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compact binary cache of the parsed playlist so the app opens instantly. */
object ChannelCache {
    private const val VERSION = 4

    fun save(file: File, list: List<Channel>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 1 shl 16)).use { o ->
            o.writeInt(VERSION)
            o.writeInt(list.size)
            for (c in list) {
                o.s(c.name); o.s(c.url); o.s(c.group); o.s(c.logo); o.s(c.tvgId)
                o.s(c.tvgLanguage); o.s(c.tvgCountry); o.s(c.userAgent); o.s(c.referrer); o.s(c.origin)
                o.s(c.detectedLang); o.s(c.detectedRegion)
                o.s(c.catchupDays.toString()); o.s(c.catchupType); o.s(c.catchupSource)
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun load(file: File): List<Channel>? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()), 1 shl 16)).use { i ->
                if (i.readInt() != VERSION) return null
                val n = i.readInt()
                val out = ArrayList<Channel>(n)
                repeat(n) {
                    out += Channel(
                        name = i.s()!!, url = i.s()!!, group = i.s()!!, logo = i.s(), tvgId = i.s(),
                        tvgLanguage = i.s(), tvgCountry = i.s(), userAgent = i.s(), referrer = i.s(), origin = i.s(),
                        detectedLang = i.s() ?: "", detectedRegion = i.s() ?: "",
                        catchupDays = i.s()?.toIntOrNull() ?: 0, catchupType = i.s(), catchupSource = i.s(),
                    )
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun DataOutputStream.s(v: String?) {
        if (v == null) { writeInt(-1); return }
        val b = v.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    private fun DataInputStream.s(): String? {
        val n = readInt()
        if (n < 0) return null
        val b = ByteArray(n); readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
