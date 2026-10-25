package com.zapline.iptv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.zapline.iptv.data.Reminders
import com.zapline.iptv.player.PlayEntry
import com.zapline.iptv.player.PlayQueue
import com.zapline.iptv.player.PlayerActivity
import com.zapline.iptv.ui.AppRoot
import com.zapline.iptv.ui.ZaplineTheme
import com.zapline.iptv.ui.MainViewModel
import com.zapline.iptv.ui.MainViewModelHolder
import com.zapline.iptv.ui.Palette
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val holder: MainViewModelHolder by viewModels()
    private val vm: MainViewModel get() = holder.vm

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Palette.setAccent(androidx.compose.ui.graphics.Color(com.zapline.iptv.data.Prefs(this).accent))
        setContent {
            ZaplineTheme {
                AppRoot(vm) { list, index -> play(list, index) }
            }
        }
        if (savedInstanceState == null) handleReminder(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminder(intent)
    }

    private fun play(list: List<PlayEntry>, index: Int) {
        PlayQueue.set(list, index)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Tapping a reminder notification opens that channel as soon as the channel list is ready. */
    private fun handleReminder(intent: Intent?) {
        val key = intent?.getStringExtra(Reminders.EXTRA_PLAY_KEY)?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(Reminders.EXTRA_PLAY_KEY)
        lifecycleScope.launch {
            val e = vm.entryForKey(key)
            if (e == null) Toast.makeText(this@MainActivity, "That channel isn't in your playlist any more", Toast.LENGTH_LONG).show()
            else play(listOf(e.toPlay(vm.epgIdFor(e))), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.reloadRecents()
    }
}
