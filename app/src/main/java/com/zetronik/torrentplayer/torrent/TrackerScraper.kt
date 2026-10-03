package com.zetronik.torrentplayer.torrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import kotlin.random.Random

/** Swarm size as reported by trackers. */
data class SwarmInfo(val seeds: Int, val peers: Int)

/** Trackers added to every magnet link; also scraped for every torrent in the recent list. */
object PublicTrackers {
    val urls = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
    )
}

/**
 * Asks trackers for seed/peer counts without joining the swarm (scrape: BEP 15 over UDP, the
 * `/scrape` convention over HTTP). Independent of the libtorrent session, which keeps one active torrent.
 * One request per tracker covers every torrent that lists it.
 */
object TrackerScraper {
    private const val TAG = "TrackerScraper"

    /** [trackersByHash]: hex info hash → tracker announce URLs. Returns the best answer per hash. */
    suspend fun scrape(trackersByHash: Map<String, List<String>>): Map<String, SwarmInfo> = coroutineScope {
        val hashesByTracker = LinkedHashMap<String, MutableSet<String>>()
        for ((hash, trackers) in trackersByHash) {
            for (tracker in trackers) hashesByTracker.getOrPut(tracker) { LinkedHashSet() }.add(hash)
        }
        hashesByTracker
            .map { (tracker, hashes) ->
                async(Dispatchers.IO) {
                    try {
                        scrapeTracker(tracker, hashes.toList())
                    } catch (e: Exception) {
                        Log.d(TAG, "Scrape of $tracker failed: $e")
                        emptyMap()
                    }
                }
            }
            .awaitAll()
            .fold(HashMap<String, SwarmInfo>()) { best, answers ->
                for ((hash, info) in answers) {
                    val current = best[hash]
                    best[hash] = if (current == null) info else {
                        SwarmInfo(maxOf(current.seeds, info.seeds), maxOf(current.peers, info.peers))
                    }
                }
                best
            }
    }

    private fun scrapeTracker(tracker: String, hashes: List<String>): Map<String, SwarmInfo> = when {
        tracker.startsWith("udp://", ignoreCase = true) -> hashes.chunked(UdpTrackerProtocol.MAX_HASHES)
            .flatMap { scrapeUdp(tracker, it).entries }
            .associate { it.key to it.value }
        tracker.startsWith("http://", ignoreCase = true) || tracker.startsWith("https://", ignoreCase = true) ->
            scrapeHttp(tracker, hashes)
        else -> emptyMap()
    }

    private fun scrapeUdp(tracker: String, hashes: List<String>): Map<String, SwarmInfo> {
        val uri = URI(tracker)
        val address = InetAddress.getByName(uri.host)
        val port = uri.port.takeIf { it > 0 } ?: return emptyMap()
        DatagramSocket().use { socket ->
            socket.soTimeout = UDP_TIMEOUT_MS
            val buffer = ByteArray(UdpTrackerProtocol.responseSize(hashes.size).coerceAtLeast(16))

            fun exchange(request: ByteArray): Int {
                repeat(UDP_ATTEMPTS) {
                    socket.send(DatagramPacket(request, request.size, address, port))
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        return response.length
                    } catch (_: java.net.SocketTimeoutException) {
                        // UDP may drop the datagram: try again.
                    }
                }
                return -1
            }

            val connectTx = Random.nextInt()
            val connectLength = exchange(UdpTrackerProtocol.connectRequest(connectTx))
            val connectionId = UdpTrackerProtocol.parseConnectResponse(buffer, connectLength, connectTx)
                ?: return emptyMap()
            val scrapeTx = Random.nextInt()
            val request = UdpTrackerProtocol.scrapeRequest(connectionId, scrapeTx, hashes.map(::hexToBytes))
            val length = exchange(request)
            val infos = UdpTrackerProtocol.parseScrapeResponse(buffer, length, scrapeTx, hashes.size)
                ?: return emptyMap()
            return hashes.zip(infos).toMap()
        }
    }

    private fun scrapeHttp(tracker: String, hashes: List<String>): Map<String, SwarmInfo> {
        val base = HttpScrape.scrapeUrl(tracker) ?: return emptyMap()
        val url = HttpScrape.withInfoHashes(base, hashes.map(::hexToBytes))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("User-Agent", "TorrentPlayer/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) return emptyMap()
            val body = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
            return HttpScrape.parseResponse(body).filterKeys { it in hashes }
        } finally {
            connection.disconnect()
        }
    }

    private const val UDP_TIMEOUT_MS = 3_000
    private const val UDP_ATTEMPTS = 2
    private const val HTTP_TIMEOUT_MS = 8_000
}

/** BEP 15 packets. Pure byte handling, unit-tested. */
internal object UdpTrackerProtocol {
    private const val PROTOCOL_ID = 0x41727101980L
    private const val ACTION_CONNECT = 0
    private const val ACTION_SCRAPE = 2
    /** Keeps a scrape request well under typical MTUs (BEP 15 allows about 74). */
    const val MAX_HASHES = 70

    fun connectRequest(transactionId: Int): ByteArray =
        ByteBuffer.allocate(16).putLong(PROTOCOL_ID).putInt(ACTION_CONNECT).putInt(transactionId).array()

    fun parseConnectResponse(data: ByteArray, length: Int, transactionId: Int): Long? {
        if (length < 16) return null
        val buffer = ByteBuffer.wrap(data, 0, length)
        if (buffer.int != ACTION_CONNECT || buffer.int != transactionId) return null
        return buffer.long
    }

    fun scrapeRequest(connectionId: Long, transactionId: Int, infoHashes: List<ByteArray>): ByteArray {
        val buffer = ByteBuffer.allocate(16 + 20 * infoHashes.size)
            .putLong(connectionId).putInt(ACTION_SCRAPE).putInt(transactionId)
        infoHashes.forEach { buffer.put(it, 0, 20) }
        return buffer.array()
    }

    fun responseSize(hashCount: Int) = 8 + 12 * hashCount

    /** Seeds and peers ("leechers") per requested hash, in request order. */
    fun parseScrapeResponse(data: ByteArray, length: Int, transactionId: Int, hashCount: Int): List<SwarmInfo>? {
        if (length < responseSize(hashCount)) return null
        val buffer = ByteBuffer.wrap(data, 0, length)
        if (buffer.int != ACTION_SCRAPE || buffer.int != transactionId) return null
        return List(hashCount) {
            val seeders = buffer.int
            buffer.int // completed downloads
            val leechers = buffer.int
            SwarmInfo(seeders, leechers)
        }
    }
}

/** HTTP scrape convention: `.../announce?x` → `.../scrape?x`, bencoded `files` dictionary in the response. */
internal object HttpScrape {
    fun scrapeUrl(announce: String): String? {
        val queryStart = announce.indexOf('?').takeIf { it >= 0 } ?: announce.length
        val path = announce.substring(0, queryStart)
        val slash = path.lastIndexOf('/')
        if (slash < 0 || !path.startsWith("announce", slash + 1)) return null
        return path.substring(0, slash + 1) + "scrape" + path.substring(slash + 1 + "announce".length) +
            announce.substring(queryStart)
    }

    fun withInfoHashes(scrapeUrl: String, infoHashes: List<ByteArray>): String {
        val params = infoHashes.joinToString("&") { "info_hash=" + percentEncode(it) }
        return scrapeUrl + (if ('?' in scrapeUrl) "&" else "?") + params
    }

    fun parseResponse(body: ByteArray): Map<String, SwarmInfo> {
        val root = Bencode.decode(body) as? Map<*, *> ?: return emptyMap()
        val files = root["files"] as? Map<*, *> ?: return emptyMap()
        return files.entries.mapNotNull { (key, value) ->
            val hash = key as? String ?: return@mapNotNull null
            val stats = value as? Map<*, *> ?: return@mapNotNull null
            val seeds = (stats["complete"] as? Long)?.toInt() ?: return@mapNotNull null
            val peers = (stats["incomplete"] as? Long)?.toInt() ?: 0
            bytesToHex(hash.toByteArray(Charsets.ISO_8859_1)) to SwarmInfo(seeds, peers)
        }.toMap()
    }

    private fun percentEncode(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c) else append("%%%02X".format(b.toInt() and 0xFF))
        }
    }
}

/** Minimal bencode decoder: integers → Long, strings → ISO-8859-1 String, lists and dictionaries. */
internal object Bencode {
    fun decode(data: ByteArray): Any? = try {
        Parser(data).value()
    } catch (_: RuntimeException) {
        null
    }

    private class Parser(private val data: ByteArray) {
        private var pos = 0

        fun value(): Any = when (val c = data[pos].toInt().toChar()) {
            'i' -> {
                val end = indexOf('e', pos)
                String(data, pos + 1, end - pos - 1, Charsets.US_ASCII).toLong().also { pos = end + 1 }
            }
            'l' -> {
                pos++
                buildList { while (data[pos].toInt().toChar() != 'e') add(value()) }.also { pos++ }
            }
            'd' -> {
                pos++
                buildMap { while (data[pos].toInt().toChar() != 'e') put(value() as String, value()) }.also { pos++ }
            }
            in '0'..'9' -> {
                val colon = indexOf(':', pos)
                val length = String(data, pos, colon - pos, Charsets.US_ASCII).toInt()
                String(data, colon + 1, length, Charsets.ISO_8859_1).also { pos = colon + 1 + length }
            }
            else -> throw IllegalArgumentException("Unexpected '$c' at $pos")
        }

        private fun indexOf(c: Char, from: Int): Int {
            var i = from
            while (data[i].toInt().toChar() != c) i++
            return i
        }
    }
}

/** `tr=` parameters of a magnet link. */
fun magnetTrackers(magnet: String): List<String> =
    magnet.substringAfter('?', "").split('&')
        .filter { it.startsWith("tr=") }
        .map { URLDecoder.decode(it.removePrefix("tr="), "UTF-8") }

internal fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
    hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
}

internal fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
