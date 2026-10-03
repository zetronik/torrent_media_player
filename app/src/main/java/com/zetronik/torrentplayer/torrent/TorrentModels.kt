package com.zetronik.torrentplayer.torrent

import android.net.Uri

data class TorrentMeta(
    val infoHash: String,
    val name: String,
    val totalSize: Long,
    val magnetUri: String,
    val files: List<TorrentFileEntry>,
) {
    val videoFiles: List<TorrentFileEntry> by lazy {
        files.filter { it.kind == FileKind.VIDEO }.sortedWith(compareBy(NaturalOrder) { it.path })
    }
}

data class TorrentFileEntry(
    val index: Int,
    /** Path inside the torrent, '/'-separated, including the torrent root folder for multi-file torrents. */
    val path: String,
    val size: Long,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val kind: FileKind get() = FileKind.of(extension)
}

enum class FileKind {
    VIDEO, SUBTITLE, OTHER;

    companion object {
        // Containers ExoPlayer can demux. WMV/ASF and VOB are intentionally absent: they would show up but fail to play.
        private val VIDEO = setOf(
            "mkv", "mp4", "m4v", "mov", "webm", "avi", "ts", "m2ts", "mts", "flv", "mpg", "mpeg", "3gp",
        )
        private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt")

        fun of(extension: String): FileKind = when (extension) {
            in VIDEO -> FileKind.VIDEO
            in SUBTITLE -> FileKind.SUBTITLE
            else -> OTHER
        }
    }
}

data class TorrentStats(
    /** Contiguous bytes on disk ahead of the player's read position. */
    val bufferedBytes: Long,
    /** The same in media time, or -1 while the bitrate is unknown. */
    val bufferedMs: Long,
    /** Buffer the player waits for before (re)starting, 0 for the player default; see [PrebufferPolicy]. */
    val requiredMs: Long,
    /** Maximum read-ahead allowed by the disk budget. */
    val budgetBytes: Long,
    val hasMetadata: Boolean,
    val downloadRate: Int,
    /** Connected seeds / peers. */
    val connectedSeeds: Int,
    val connectedPeers: Int,
    /** Swarm size reported by trackers, or connected counts when no tracker answered yet. */
    val seeds: Int,
    val peers: Int,
    val dhtNodes: Long,
    val error: String?,
) {
    companion object {
        val Empty = TorrentStats(0, -1, 0, 0, false, 0, 0, 0, 0, 0, 0, null)
    }
}

/** `torrent://<infohash>/<fileIndex>` — the URI the player uses for files streamed from a torrent. */
object TorrentUris {
    const val SCHEME = "torrent"

    fun build(infoHash: String, fileIndex: Int): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(infoHash)
        .appendPath(fileIndex.toString())
        .build()

    fun parse(uri: Uri): Pair<String, Int>? {
        if (uri.scheme != SCHEME) return null
        val hash = uri.authority ?: return null
        val index = uri.lastPathSegment?.toIntOrNull() ?: return null
        return hash to index
    }
}

/** Orders "Episode 2" before "Episode 10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
