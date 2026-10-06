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
 * [playlistUris]/[playlistTitles] list local videos opened together (empty for a single video);
 * a torrent's playlist is its video files, read from the metadata.
 * [startPositionMs] (with the [startDurationMs] it belongs to) overrides the saved position, -1 for none:
 * set when playback moves between the phone and the TV.
 */
@Serializable
data class PlayerRoute(
    val uri: String,
    val title: String,
    val infoHash: String? = null,
    val fileIndex: Int = -1,
    val external: Boolean = false,
    val playlistUris: List<String> = emptyList(),
    val playlistTitles: List<String> = emptyList(),
    val startPositionMs: Long = -1,
    val startDurationMs: Long = 0,
)

/** The phone as a remote for a TV that plays a torrent sent from here. */
@Serializable
data class RemoteRoute(val deviceId: String, val deviceName: String, val host: String, val port: Int)

/** Navigation requested from outside the UI (incoming intents). */
sealed interface NavRequest {
    data class OpenTorrent(val source: String) : NavRequest
    /** [playlist] holds every video opened together, including the first one; empty for a single video. */
    /**
     * A torrent sent from a phone ([com.zetronik.torrentplayer.remote.RemoteReceiver]). [torrentActive] means
     * the torrent was already in the session, i.e. its file list may be the current screen.
     */
    data class PlayTorrent(val source: String, val player: PlayerRoute, val torrentActive: Boolean) : NavRequest

    data class PlayVideo(val uri: String, val title: String, val playlist: List<LocalVideo> = emptyList()) : NavRequest
}

data class LocalVideo(val uri: String, val title: String)
