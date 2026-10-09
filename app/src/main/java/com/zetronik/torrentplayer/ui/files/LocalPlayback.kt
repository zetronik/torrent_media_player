package com.zetronik.torrentplayer.ui.files

import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.media.LocalKind
import com.zetronik.torrentplayer.ui.navigation.LocalVideo
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute

/** The route travels in the back stack's saved state, so a huge folder is cut to a window around the pick. */
private const val MAX_PLAYLIST = 300

/** Plays [selected] with the other videos of its folder as the playlist, in folder order. */
fun folderPlayerRoute(files: List<FolderItem.File>, selected: FolderItem.File): PlayerRoute {
    val videos = files.filter { it.kind == LocalKind.VIDEO }.map { LocalVideo(it.uri, it.name) }
    val index = videos.indexOfFirst { it.uri == selected.uri }.coerceAtLeast(0)
    return playlistRoute(videos, index)
}

/** Plays a resolved playlist from its first entry; null when nothing in it could be found. */
fun playlistPlayerRoute(videos: List<LocalVideo>): PlayerRoute? =
    if (videos.isEmpty()) null else playlistRoute(videos, 0)

private fun playlistRoute(videos: List<LocalVideo>, index: Int): PlayerRoute {
    val start = (index - MAX_PLAYLIST / 2).coerceIn(0, (videos.size - MAX_PLAYLIST).coerceAtLeast(0))
    val window = videos.subList(start, minOf(videos.size, start + MAX_PLAYLIST))
    val current = videos[index]
    return PlayerRoute(
        uri = current.uri,
        title = current.title,
        playlistUris = if (window.size > 1) window.map { it.uri } else emptyList(),
        playlistTitles = if (window.size > 1) window.map { it.title } else emptyList(),
    )
}
