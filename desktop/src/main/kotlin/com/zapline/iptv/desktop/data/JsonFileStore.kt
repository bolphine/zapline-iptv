package com.zapline.iptv.desktop.data

import com.zapline.iptv.data.KeyValueStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings in a JSON file (%APPDATA%\Zapline\settings.json). Values keep their types; writes are atomic
 * and happen off the UI thread on apply(), immediately on commit().
 */
class JsonFileStore(private val file: File) : KeyValueStore {
    private val map = LinkedHashMap<String, Any>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }

    init {
        if (file.exists()) try {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                val v = o.optJSONObject(k) ?: continue
                map[k] = when {
                    v.has("s") -> v.getString("s")
                    v.has("b") -> v.getBoolean("b")
                    v.has("i") -> v.getInt("i")
                    v.has("l") -> v.getLong("l")
                    v.has("f") -> v.getDouble("f").toFloat()
                    v.has("set") -> v.getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
            }
        } catch (_: Exception) {
            // Unreadable settings: keep a copy for diagnosis and start fresh.
            try { file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true) } catch (_: Exception) {}
        }
    }

    @Synchronized private fun snapshot(): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, when (v) {
            is String -> JSONObject().put("s", v)
            is Boolean -> JSONObject().put("b", v)
            is Int -> JSONObject().put("i", v)
            is Long -> JSONObject().put("l", v)
            is Float -> JSONObject().put("f", v.toDouble())
            is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
            else -> JSONObject().put("s", v.toString())
        })
        return o.toString(1)
    }

    private fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized override fun getString(key: String, def: String?) = map[key] as? String ?: def
    @Synchronized override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    @Synchronized override fun getInt(key: String, def: Int) = (map[key] as? Number)?.toInt() ?: def
    @Synchronized override fun getLong(key: String, def: Long) = (map[key] as? Number)?.toLong() ?: def
    @Synchronized override fun getFloat(key: String, def: Float) = (map[key] as? Number)?.toFloat() ?: def
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, def: Set<String>?) = (map[key] as? Set<String>)?.toSet() ?: def
    @Synchronized override fun contains(key: String) = map.containsKey(key)
    override val all: Map<String, *> @Synchronized get() = HashMap(map)

    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, v: String?) = apply { puts[key] = v }
        override fun putBoolean(key: String, v: Boolean) = apply { puts[key] = v }
        override fun putInt(key: String, v: Int) = apply { puts[key] = v }
        override fun putLong(key: String, v: Long) = apply { puts[key] = v }
        override fun putFloat(key: String, v: Float) = apply { puts[key] = v }
        override fun putStringSet(key: String, v: Set<String>?) = apply { puts[key] = v?.toSet() }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        private fun merge(): String = synchronized(this@JsonFileStore) {
            if (clear) map.clear()
            for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
            snapshot()
        }
        override fun apply() { val text = merge(); writer.execute { try { write(text) } catch (_: Exception) {} } }
        override fun commit(): Boolean = try { write(merge()); true } catch (_: Exception) { false }
    }
}
