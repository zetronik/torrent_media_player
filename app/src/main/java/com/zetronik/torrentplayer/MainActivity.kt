package com.zetronik.torrentplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.zetronik.torrentplayer.ui.navigation.AppNavHost
import com.zetronik.torrentplayer.ui.navigation.NavRequest
import com.zetronik.torrentplayer.ui.theme.TorrentPlayerTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val navRequests = Channel<NavRequest>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled its launch intent.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TorrentPlayerTheme {
                AppNavHost(navRequests = navRequests.receiveAsFlow(), onExitApp = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        lifecycleScope.launch {
            IntentParser.parse(applicationContext, intent)?.let { navRequests.send(it) }
        }
    }
}
