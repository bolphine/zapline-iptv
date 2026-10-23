package com.zapline.iptv.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.zapline.iptv.MainActivity
import com.zapline.iptv.R
import com.zapline.iptv.data.Health

/**
 * Runs for as long as a TV is connected. Live channels reach the TV through this phone (see [CastProxy]),
 * so the phone has to keep working with the screen off: this foreground service keeps the app alive,
 * holds the CPU awake, keeps Wi-Fi at full speed, and shows the "Casting to…" notification with controls.
 */
class CastService : Service() {
    companion object {
        private const val CHANNEL = "casting"
        private const val ID = 4242
        private const val ACTION_TOGGLE = "com.zapline.iptv.cast.TOGGLE"
        private const val ACTION_STOP = "com.zapline.iptv.cast.STOP"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_TITLE = "title"

        /** Starts the service, or updates its notification if it's already running. */
        fun show(ctx: Context, device: String, title: String?) {
            val i = Intent(ctx, CastService::class.java).putExtra(EXTRA_DEVICE, device).putExtra(EXTRA_TITLE, title)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {
                // Android refuses to start foreground services from the background; it'll start next time the app is open.
            }
        }

        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, CastService::class.java)) } catch (_: Exception) {} }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var device = "TV"
    private var title: String? = null

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(s: CastSession, error: Int) = stopSelf()
        override fun onSessionStartFailed(s: CastSession, error: Int) = stopSelf()
        override fun onSessionResumeFailed(s: CastSession, error: Int) = stopSelf()
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, id: String) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    private fun cast(): CastContext? = try { CastContext.getSharedInstance(this) } catch (_: Throwable) { null }

    override fun onCreate() {
        super.onCreate()
        Health.casting = true
        try { cast()?.sessionManager?.addSessionManagerListener(sessions, CastSession::class.java) } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> try { cast()?.sessionManager?.currentCastSession?.remoteMediaClient?.togglePlayback() } catch (_: Throwable) {}
            ACTION_STOP -> {
                try { cast()?.sessionManager?.endCurrentSession(true) } catch (_: Throwable) {}
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                intent?.getStringExtra(EXTRA_DEVICE)?.let { device = it }
                title = intent?.getStringExtra(EXTRA_TITLE) ?: title
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try { ServiceCompat.startForeground(this, ID, notification(), type) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        holdLocks()
        return START_NOT_STICKY
    }

    private fun holdLocks() {
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zapline:casting").apply { setReferenceCounted(false); acquire(6 * 3_600_000L) }
        }
        if (wifi == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "Zapline:casting").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Zapline is sending a channel to your TV"
                setShowBadge(false)
            })
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val toggle = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_TOGGLE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title ?: "Casting")
            .setContentText("Playing on $device")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, "Pause / play", toggle)
            .addAction(0, "Stop casting", stop)
            .build()
    }

    override fun onDestroy() {
        try { cast()?.sessionManager?.removeSessionManagerListener(sessions, CastSession::class.java) } catch (_: Throwable) {}
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        wake = null; wifi = null
        Health.casting = false
        CastProxy.stopLive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
