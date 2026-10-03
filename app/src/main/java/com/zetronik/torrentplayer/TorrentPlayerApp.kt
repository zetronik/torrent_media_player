package com.zetronik.torrentplayer

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TorrentPlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start the session early so DHT is bootstrapped by the time the user pastes a link.
        container.appScope.launch(Dispatchers.IO) { container.torrentEngine.start() }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TorrentPlayerApp).container
