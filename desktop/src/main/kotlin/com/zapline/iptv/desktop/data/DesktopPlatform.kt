package com.zapline.iptv.desktop.data

import com.zapline.iptv.data.Platform
import com.zapline.iptv.data.Prefs
import com.zapline.iptv.data.Reminder
import com.zapline.iptv.data.ReminderStore
import com.zapline.iptv.data.Repository
import org.json.JSONArray
import org.json.JSONObject
import org.kxml2.io.KXmlParser
import java.io.File

/** Where Zapline keeps its things on Windows: %APPDATA%\Zapline (settings, caches, channel checks). */
object AppDirs {
    val root: File by lazy {
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        File(base, "Zapline").also { it.mkdirs() }
    }
    val cache: File by lazy { File(root, "cache").also { it.mkdirs() } }
}

class DesktopPlatform(val root: File = AppDirs.root) : Platform {
    val prefs = Prefs(JsonFileStore(File(root, "settings.json")))
    override val filesDir: File = File(root, "cache").also { it.mkdirs() }
    override val repository = Repository(
        filesDir, prefs,
        openFile = { path -> File(path).takeIf { it.isFile }?.inputStream() },
        newXmlParser = { KXmlParser() },
    )
    override val reminders = DesktopReminders(prefs)
    override fun isMetered() = false

    init {
        // On a PC: highest quality first, and the full channel check is fine to run on its own.
        if (!prefs.p.contains("preferHighest")) prefs.preferHighest = true
    }
}

/**
 * Reminders on Windows: saved with the settings; while Zapline is running, [due] reports any that are
 * about to start so the app can pop a notification and offer to switch over.
 */
class DesktopReminders(private val prefs: Prefs) : ReminderStore {
    private val key = "reminders"

    override fun all(): List<Reminder> = try {
        val a = JSONArray(prefs.p.getString(key, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Reminder(o.optString("title"), o.optString("key"), o.optString("channel"), o.optString("epg").ifEmpty { null }, o.optLong("start"))
        }.filter { it.start > System.currentTimeMillis() - 5 * 60_000 }.sortedBy { it.start }
    } catch (_: Exception) { emptyList() }

    private fun save(list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { r -> a.put(JSONObject().put("title", r.title).put("key", r.channelKey).put("channel", r.channelName).put("epg", r.epgId ?: "").put("start", r.start)) }
        prefs.p.edit().putString(key, a.toString()).apply()
    }

    override fun add(r: Reminder) = save(all().filterNot { it.id == r.id } + r)
    override fun remove(r: Reminder) = save(all().filterNot { it.id == r.id })
    override fun rescheduleAll() {}

    /** Reminders starting within the next minute; each is returned once, then dropped. */
    fun due(now: Long = System.currentTimeMillis()): List<Reminder> {
        val list = all()
        val hit = list.filter { it.start - now <= 60_000 }
        if (hit.isNotEmpty()) save(list - hit.toSet())
        return hit
    }
}
