package com.zapline.iptv.desktop.ui

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** Windows notifications (via the system tray), for reminders. Quietly does nothing where unsupported. */
object Notifier {
    private val icon: TrayIcon? by lazy {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return@lazy null
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics(); g.color = java.awt.Color(0xFF, 0xB5, 0x47); g.fillOval(1, 1, 14, 14); g.dispose()
            }
            TrayIcon(img, "Zapline").also { it.isImageAutoSize = true; SystemTray.getSystemTray().add(it) }
        } catch (_: Throwable) { null }
    }

    fun notify(title: String, text: String) {
        try { icon?.displayMessage(title, text, TrayIcon.MessageType.INFO) } catch (_: Throwable) {}
    }

    fun remove() { try { icon?.let { SystemTray.getSystemTray().remove(it) } } catch (_: Throwable) {} }
}
