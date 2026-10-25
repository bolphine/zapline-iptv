package com.zapline.iptv.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zapline.iptv.MainActivity
import com.zapline.iptv.R
import org.json.JSONArray
import org.json.JSONObject


object Reminders {
    const val EXTRA_PLAY_KEY = "playKey"
    private const val CHANNEL_ID = "reminders"
    private const val LEAD_MS = 60_000L   // notify a minute before the start

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zapline", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> = try {
        val a = JSONArray(prefs(ctx).getString("reminders", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Reminder(o.optString("title"), o.optString("key"), o.optString("channel"), o.optString("epg").ifEmpty { null }, o.optLong("start"))
        }.filter { it.start > System.currentTimeMillis() - 5 * 60_000 }.sortedBy { it.start }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("title", r.title).put("key", r.channelKey).put("channel", r.channelName)
                .put("epg", r.epgId ?: "").put("start", r.start))
        }
        prefs(ctx).edit().putString("reminders", a.toString()).apply()
    }

    fun has(ctx: Context, channelKey: String, start: Long) = all(ctx).any { it.channelKey == channelKey && it.start == start }

    fun add(ctx: Context, r: Reminder) {
        save(ctx, all(ctx).filterNot { it.id == r.id } + r)
        schedule(ctx, r)
    }

    fun remove(ctx: Context, r: Reminder) {
        save(ctx, all(ctx).filterNot { it.id == r.id })
        alarms(ctx).cancel(alarmIntent(ctx, r))
    }

    /** After a reboot (or app update) alarms are gone; put them back. */
    fun rescheduleAll(ctx: Context) = all(ctx).forEach { schedule(ctx, it) }

    private fun alarms(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun alarmIntent(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", r.id).putExtra("title", r.title).putExtra("channel", r.channelName)
            .putExtra("key", r.channelKey).putExtra("start", r.start)
        return PendingIntent.getBroadcast(ctx, r.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(ctx: Context, r: Reminder) {
        val at = (r.start - LEAD_MS).coerceAtLeast(System.currentTimeMillis() + 1_000)
        val am = alarms(ctx)
        val pi = alarmIntent(ctx, r)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Without the exact-alarm permission Android may deliver up to a couple of minutes early.
                am.setWindow(AlarmManager.RTC_WAKEUP, at - 60_000, 60_000, pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Programme reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A minute before a programme you asked about starts"
            })
        }
    }

    fun notify(ctx: Context, id: Int, title: String, channel: String, key: String, start: Long) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PLAY_KEY, key)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mins = ((start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (mins <= 0) "Starting now on $channel" else "Starts in $mins min on $channel")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Watch", pi)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
        // Drop it from the saved list once delivered.
        save(ctx, all(ctx).filterNot { it.id == id })
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.notify(
            ctx, intent.getIntExtra("id", 0), intent.getStringExtra("title") ?: "Programme starting",
            intent.getStringExtra("channel") ?: "", intent.getStringExtra("key") ?: "", intent.getLongExtra("start", 0),
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reminders.rescheduleAll(ctx)
    }
}
