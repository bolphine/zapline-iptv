package com.zapline.iptv.data

import android.content.Context
import android.net.Uri

/** The phone side of [Platform]: app storage, SharedPreferences, alarm-based reminders, network type. */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext
    override val filesDir = app.filesDir
    override val repository = Repository(
        app.filesDir, Prefs(app),
        openFile = { uri -> app.contentResolver.openInputStream(Uri.parse(uri)) },
        newXmlParser = { android.util.Xml.newPullParser() },
    )
    override val reminders = object : ReminderStore {
        override fun all() = Reminders.all(app)
        override fun add(r: Reminder) = Reminders.add(app, r)
        override fun remove(r: Reminder) = Reminders.remove(app, r)
        override fun rescheduleAll() = Reminders.rescheduleAll(app)
    }
    override fun isMetered(): Boolean = try {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { true }
}
