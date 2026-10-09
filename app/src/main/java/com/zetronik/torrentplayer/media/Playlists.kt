package com.zetronik.torrentplayer.media

import com.zetronik.torrentplayer.torrent.FileKind
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the local file browser shows; everything else is hidden. */
enum class LocalKind {
    VIDEO, PLAYLIST;

    companion object {
        private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")

        fun of(name: String): LocalKind? {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                FileKind.of(extension) == FileKind.VIDEO -> VIDEO
                extension in PLAYLIST_EXTENSIONS -> PLAYLIST
                else -> null
            }
        }
    }
}

/** One line of a playlist as written: a URL, an absolute path or a path relative to the playlist. */
data class PlaylistEntry(val location: String, val title: String?)

/** Parses M3U/M3U8 (with `#EXTINF` titles) and PLS. Pure, so it is unit-tested. */
object PlaylistParser {
    private val BYTE_ORDER_MARK = Char(0xFEFF)


    fun parse(text: String): List<PlaylistEntry> {
        val lines = text.removePrefix(BYTE_ORDER_MARK.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.firstOrNull()?.equals("[playlist]", ignoreCase = true) == true) parsePls(lines) else parseM3u(lines)
    }

    private fun parseM3u(lines: List<String>): List<PlaylistEntry> {
        val entries = ArrayList<PlaylistEntry>()
        var title: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) ->
                    title = line.substringAfter(',', "").trim().ifEmpty { null }
                line.startsWith("#") -> Unit
                else -> {
                    entries += PlaylistEntry(line, title)
                    title = null
                }
            }
        }
        return entries
    }

    private fun parsePls(lines: List<String>): List<PlaylistEntry> {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        for (line in lines) {
            val key = line.substringBefore('=', "").trim().lowercase()
            val value = line.substringAfter('=', "").trim()
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { files[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        return files.keys.sorted().mapNotNull { n ->
            files[n]?.takeIf { it.isNotEmpty() }?.let { PlaylistEntry(it, titles[n]?.ifEmpty { null }) }
        }
    }

    /**
     * Playlists saved on Russian Windows are often CP1251, M3U8 and most others UTF-8: UTF-8 is tried
     * strictly first, since CP1251 decodes any byte sequence without error.
     */
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
    }
}

/** How a playlist entry is to be found, see [PlaylistLocation.of]. */
sealed interface PlaylistLocation {
    /** Played as is. */
    data class Url(val uri: String) : PlaylistLocation

    /** An absolute path on this device; found through MediaStore. */
    data class Absolute(val path: String) : PlaylistLocation

    /** Path segments relative to the playlist's folder; may contain "..". */
    data class Relative(val segments: List<String>) : PlaylistLocation

    companion object {
        private val URL_SCHEMES = setOf("http", "https", "content")
        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/].*")

        fun of(location: String): PlaylistLocation? {
            val scheme = location.substringBefore("://", "").lowercase()
            if (scheme in URL_SCHEMES) return Url(location)
            if (scheme == "file") {
                val path = runCatching { URI(location).path }.getOrNull() ?: location.removePrefix("file://")
                return Absolute(path)
            }
            if (scheme.isNotEmpty()) return null
            val path = location.replace('\\', '/')
            return when {
                path.startsWith("/") -> Absolute(path)
                // A path on another computer: only the file name can still match something.
                WINDOWS_DRIVE.matches(location) -> Relative(listOf(path.substringAfterLast('/')))
                else -> Relative(path.split('/').filter { it.isNotEmpty() && it != "." })
            }
        }
    }
}
