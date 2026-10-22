package com.zapline.iptv.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Small, quiet taps: a tick for stepping (channels, digits), a firmer one for confirming (favourite, reminder). */
object Haptics {
    fun tick(v: View?) { try { v?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } catch (_: Exception) {} }
    fun confirm(v: View?) {
        try { v?.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {}
    }
}
