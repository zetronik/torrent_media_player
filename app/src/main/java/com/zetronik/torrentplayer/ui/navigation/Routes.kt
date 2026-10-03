package com.zetronik.torrentplayer.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object RecentRoute

/** [source] is a serialized [com.zetronik.torrentplayer.torrent.TorrentInput]. */
@Serializable
data class TorrentRoute(val source: String)

/**
 * [uri] is `torrent://<infohash>/<index>` for torrent files or a content/file URI for local videos.
 * [external] marks a video opened from another app: leaving the player closes the activity.
 */
@Serializable
data class PlayerRoute(
    val uri: String,
    val title: String,
    val infoHash: String? = null,
    val fileIndex: Int = -1,
    val external: Boolean = false,
)

/** Navigation requested from outside the UI (incoming intents). */
sealed interface NavRequest {
    data class OpenTorrent(val source: String) : NavRequest
    data class PlayVideo(val uri: String, val title: String) : NavRequest
}
