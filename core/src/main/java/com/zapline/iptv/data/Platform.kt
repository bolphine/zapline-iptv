package com.zapline.iptv.data

import java.io.File

/** A programme the viewer asked to be reminded about. */
data class Reminder(
    val title: String,
    val channelKey: String,
    val channelName: String,
    val epgId: String?,
    val start: Long,
) {
    /** Stable id: one reminder per channel and start time. */
    val id: Int get() = (channelKey + start).hashCode()
}

/** Saved reminders and however the platform alerts you (phone notifications, desktop toasts). */
interface ReminderStore {
    fun all(): List<Reminder>
    fun add(r: Reminder)
    fun remove(r: Reminder)
    fun rescheduleAll()
}

/** What the shared app logic needs from the phone or the PC it's running on. */
interface Platform {
    val repository: Repository
    /** Where caches and channel-check results live. */
    val filesDir: File
    val reminders: ReminderStore
    /** True on mobile data and other metered connections (background checks wait for Wi-Fi). */
    fun isMetered(): Boolean
}
