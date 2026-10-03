package com.zetronik.torrentplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.zetronik.torrentplayer.torrent.TorrentInput
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
        Intent.ACTION_VIEW -> intent.data?.let { parseView(context, it, intent.type) }
        Intent.ACTION_SEND -> TorrentInput.parse(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?.let { NavRequest.OpenTorrent(it.source) }
        else -> null
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
