package com.zetronik.torrentplayer.torrent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where a torrent comes from: clipboard text, a shared link or an opened file. */
sealed interface TorrentInput {
    data class Magnet(val uri: String) : TorrentInput
    data class Url(val url: String) : TorrentInput
    /** A `.torrent` file already copied into app storage. */
    data class TorrentFile(val path: String) : TorrentInput

    /** Serialized form, used as a navigation argument. */
    val source: String
        get() = when (this) {
            is Magnet -> uri
            is Url -> url
            is TorrentFile -> FILE_PREFIX + path
        }

    companion object {
        private const val FILE_PREFIX = "file://"
        private val MAGNET = Regex("magnet:\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        private val URL = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        private val HEX_HASH = Regex("^[0-9a-fA-F]{40}$")
        private val BASE32_HASH = Regex("^[A-Za-z2-7]{32}$")

        /** Extracts a torrent reference from arbitrary text (clipboard, shared text, navigation argument). */
        fun parse(text: String?): TorrentInput? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith(FILE_PREFIX)) return TorrentFile(trimmed.removePrefix(FILE_PREFIX))
            MAGNET.find(trimmed)?.let { return Magnet(it.value) }
            if (HEX_HASH.matches(trimmed) || BASE32_HASH.matches(trimmed)) {
                return Magnet("magnet:?xt=urn:btih:$trimmed")
            }
            URL.find(trimmed)?.let { return Url(it.value) }
            return null
        }
    }
}

/** Downloads a `.torrent` file. Some trackers answer with a redirect to a magnet link instead. */
internal object TorrentDownloader {
    private const val MAX_SIZE = 16 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    sealed interface Result {
        class Bytes(val data: ByteArray) : Result
        class Magnet(val uri: String) : Result
    }

    fun download(url: String): Result {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "TorrentPlayer/1.0")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    if (location.startsWith("magnet:", ignoreCase = true)) return Result.Magnet(location)
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return Result.Bytes(connection.inputStream.use { it.readLimited() })
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun java.io.InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_SIZE) throw IOException("File is too large for a .torrent")
        }
        return out.toByteArray()
    }
}
