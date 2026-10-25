package com.zapline.iptv.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Where you are in each show: the last episode started, and whether you finished it. */
data class SeriesProgress(
    val showUrl: String,
    val showName: String,
    val logo: String?,
    val episodeUrl: String,
    /** "S2 E5" */
    val code: String,
    val updatedAt: Long,
    val finished: Boolean,
)

object SeriesWatch {
    /** Episode URL -> its show, registered when an episode list is queued for playing. */
    data class Ref(val showUrl: String, val showName: String, val logo: String?, val code: String)

    private val refs = ConcurrentHashMap<String, Ref>()

    fun register(episodeUrl: String, ref: Ref) { refs[episodeUrl] = ref }

    private fun json(p: Prefs) = try { JSONObject(p.seriesJson) } catch (_: Exception) { JSONObject() }

    /** The player started (or finished) an episode. */
    fun record(p: Prefs, episodeUrl: String, finished: Boolean) {
        val r = refs[episodeUrl] ?: return
        val o = json(p)
        o.put(r.showUrl, JSONObject().put("name", r.showName).put("logo", r.logo ?: "").put("ep", episodeUrl)
            .put("code", r.code).put("t", System.currentTimeMillis()).put("done", finished))
        if (o.length() > 60) o.keys().asSequence().toList().sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0 }
            .take(o.length() - 60).forEach { o.remove(it) }
        p.seriesJson = o.toString()
    }

    fun all(p: Prefs): List<SeriesProgress> {
        val o = json(p)
        return o.keys().asSequence().mapNotNull { k ->
            val x = o.optJSONObject(k) ?: return@mapNotNull null
            SeriesProgress(k, x.optString("name"), x.optString("logo").ifEmpty { null }, x.optString("ep"), x.optString("code"),
                x.optLong("t"), x.optBoolean("done"))
        }.sortedByDescending { it.updatedAt }.toList()
    }

    fun remove(p: Prefs, showUrl: String) { val o = json(p); o.remove(showUrl); p.seriesJson = o.toString() }
}
