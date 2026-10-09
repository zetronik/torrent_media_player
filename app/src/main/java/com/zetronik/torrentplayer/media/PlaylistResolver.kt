package com.zetronik.torrentplayer.media

import android.util.Log
import androidx.core.net.toUri
import com.zetronik.torrentplayer.ui.navigation.LocalVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Turns a playlist file into videos the player can open: URLs as they are, paths (absolute or relative to
 * the playlist) as files. A path that does not exist is retried by its bare file name next to the playlist,
 * which covers playlists written on another computer. Entries that still cannot be found are skipped.
 */
class PlaylistResolver(private val files: LocalFiles) {

    suspend fun resolve(playlist: File): List<LocalVideo> = withContext(Dispatchers.IO) {
        val text = read(playlist) ?: return@withContext emptyList()
        val dir = playlist.parentFile ?: return@withContext emptyList()
        PlaylistParser.parse(text).mapNotNull { entry ->
            val video = when (val location = PlaylistLocation.of(entry.location)) {
                is PlaylistLocation.Url -> LocalVideo(location.uri, location.uri.toUri().lastPathSegment ?: location.uri)
                is PlaylistLocation.Absolute -> find(File(location.path)) ?: find(File(dir, File(location.path).name))
                is PlaylistLocation.Relative ->
                    find(File(dir, location.segments.joinToString("/"))) ?: location.segments.lastOrNull()?.let { find(File(dir, it)) }
                null -> null
            }
            video?.let { if (entry.title != null) it.copy(title = entry.title) else it }
        }
    }

    private fun find(file: File): LocalVideo? =
        files.video(runCatching { file.canonicalFile }.getOrDefault(file))?.let { LocalVideo(it.uri, it.name) }

    private fun read(file: File): String? = try {
        if (file.length() > MAX_PLAYLIST_BYTES) null else PlaylistParser.decode(file.readBytes())
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read playlist $file", e)
        null
    }

    private companion object {
        const val TAG = "PlaylistResolver"

        /** A text playlist of thousands of entries is well under this; anything bigger is not a playlist. */
        const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
    }
}
