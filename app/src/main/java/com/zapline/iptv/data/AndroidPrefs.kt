package com.zapline.iptv.data

import android.content.Context
import android.content.SharedPreferences

/** The app's settings, stored in SharedPreferences. */
fun Prefs(context: Context): Prefs = Prefs(SharedPrefsStore(context.getSharedPreferences("zapline", Context.MODE_PRIVATE)))

class SharedPrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, def: String?) = sp.getString(key, def)
    override fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
    override fun getInt(key: String, def: Int) = sp.getInt(key, def)
    override fun getLong(key: String, def: Long) = sp.getLong(key, def)
    override fun getFloat(key: String, def: Float) = sp.getFloat(key, def)
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? = sp.getStringSet(key, def)
    override fun contains(key: String) = sp.contains(key)
    override val all: Map<String, *> get() = sp.all
    override fun edit(): KeyValueStore.Editor = Ed(sp.edit())

    private class Ed(private val e: SharedPreferences.Editor) : KeyValueStore.Editor {
        override fun putString(key: String, v: String?) = apply { e.putString(key, v) }
        override fun putBoolean(key: String, v: Boolean) = apply { e.putBoolean(key, v) }
        override fun putInt(key: String, v: Int) = apply { e.putInt(key, v) }
        override fun putLong(key: String, v: Long) = apply { e.putLong(key, v) }
        override fun putFloat(key: String, v: Float) = apply { e.putFloat(key, v) }
        override fun putStringSet(key: String, v: Set<String>?) = apply { e.putStringSet(key, v) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
