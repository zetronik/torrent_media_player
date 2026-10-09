package com.zetronik.torrentplayer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings from the settings screen. Look-and-feel choices live in
 * [com.zetronik.torrentplayer.ui.theme.UiPreferences].
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Torrent stream cache size in MB, [TORRENT_CACHE_AUTO] for the size picked per file. */
    private val _torrentCacheMb = MutableStateFlow(
        prefs.getInt(KEY_TORRENT_CACHE_MB, TORRENT_CACHE_AUTO).takeIf { it in TORRENT_CACHE_OPTIONS } ?: TORRENT_CACHE_AUTO
    )
    val torrentCacheMb: StateFlow<Int> = _torrentCacheMb.asStateFlow()

    val torrentCacheBytes: Long get() = _torrentCacheMb.value * MB

    fun setTorrentCacheMb(mb: Int) {
        require(mb in TORRENT_CACHE_OPTIONS)
        prefs.edit { putInt(KEY_TORRENT_CACHE_MB, mb) }
        _torrentCacheMb.value = mb
    }

    companion object {
        const val TORRENT_CACHE_AUTO = 0
        /** Choices on the settings screen, in MB; the first one is automatic. */
        val TORRENT_CACHE_OPTIONS = listOf(TORRENT_CACHE_AUTO, 256, 512, 1024, 1536, 2048, 4096)

        private const val MB = 1024L * 1024
        private const val PREFS_NAME = "settings"
        private const val KEY_TORRENT_CACHE_MB = "torrent_cache_mb"
    }
}
