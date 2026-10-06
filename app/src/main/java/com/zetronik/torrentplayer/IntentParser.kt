package com.zetronik.torrentplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.ui.navigation.LocalVideo
import com.zetronik.torrentplayer.ui.navigation.NavRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Turns VIEW/SEND intents from other apps into navigation requests. */
object IntentParser {
    private const val TAG = "IntentParser"
    private const val TORRENT_MIME = "application/x-bittorrent"

    suspend fun parse(context: Context, intent: Intent): NavRequest? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.let { uri ->
            val request = parseView(context, uri, intent.type)
            // Some file managers pass the rest of a multi-selection in the clip data.
            if (request is NavRequest.PlayVideo) withPlaylist(context, request, clipUris(intent)) else request
        }
        Intent.ACTION_SEND -> TorrentInput.parse(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?.let { NavRequest.OpenTorrent(it.source) }
        Intent.ACTION_SEND_MULTIPLE -> parseVideos(context, sharedStreams(intent))
        else -> null
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun sharedStreams(intent: Intent): List<Uri> =
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty() +
            clipUris(intent)

    private suspend fun parseVideos(context: Context, uris: List<Uri>): NavRequest? {
        val first = uris.firstOrNull() ?: return null
        val request = NavRequest.PlayVideo(first.toString(), displayName(context, first))
        return withPlaylist(context, request, uris)
    }

    private suspend fun withPlaylist(context: Context, request: NavRequest.PlayVideo, uris: List<Uri>): NavRequest.PlayVideo {
        val videos = (listOf(request.uri.toUri()) + uris).distinct().filter { isVideo(context, it) }
        if (videos.size < 2) return request
        return request.copy(playlist = videos.map { LocalVideo(it.toString(), displayName(context, it)) })
    }

    private fun isVideo(context: Context, uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return type == null || type.startsWith("video/")
    }

    private suspend fun parseView(context: Context, uri: Uri, intentType: String?): NavRequest? {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "magnet") return NavRequest.OpenTorrent(uri.toString())

        val type = intentType ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = displayName(context, uri)
        val isTorrent = type == TORRENT_MIME || name.endsWith(".torrent", ignoreCase = true)
        return when {
            isTorrent && (scheme == "http" || scheme == "https") -> NavRequest.OpenTorrent(uri.toString())
            isTorrent -> copyTorrent(context, uri)?.let { NavRequest.OpenTorrent(TorrentInput.TorrentFile(it).source) }
            type == null || type.startsWith("video/") -> NavRequest.PlayVideo(uri.toString(), name)
            else -> null
        }
    }

    /** Content URI grants do not outlive the incoming intent, so the file is copied right away. */
    private suspend fun copyTorrent(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "incoming").apply {
                deleteRecursively()
                mkdirs()
            }
            val target = File(dir, "${System.currentTimeMillis()}.torrent")
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target.absolutePath
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read $uri", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to $uri", e)
            null
        }
    }

    private suspend fun displayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val fromProvider = if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        } else {
            null
        }
        fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }
}
