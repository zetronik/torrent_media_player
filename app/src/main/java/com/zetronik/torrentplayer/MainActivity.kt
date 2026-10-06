package com.zetronik.torrentplayer

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.zetronik.torrentplayer.ui.navigation.AppNavHost
import com.zetronik.torrentplayer.ui.navigation.NavRequest
import com.zetronik.torrentplayer.ui.remote.PairingCodeDialog
import com.zetronik.torrentplayer.ui.theme.TorrentPlayerTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val navRequests = Channel<NavRequest>(Channel.BUFFERED)

    private val isTv: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    override fun onCreate(savedInstanceState: Bundle?) {
        // Bar icon colours are then set by the theme (light or dark template).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled its launch intent.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TorrentPlayerTheme(appContainer.uiPreferences) {
                AppNavHost(navRequests = navRequests.receiveAsFlow(), onExitApp = ::finish)
                if (isTv) PairingCodeDialog(appContainer.remoteReceiver)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A TV accepts videos from phones only while the app is on screen (see RemoteReceiver).
        if (isTv) appContainer.remoteReceiver.start { request -> navRequests.trySend(request) }
    }

    override fun onStop() {
        if (isTv) appContainer.remoteReceiver.stop()
        super.onStop()
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
