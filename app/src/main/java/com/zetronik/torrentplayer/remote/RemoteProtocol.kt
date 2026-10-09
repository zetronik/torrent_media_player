package com.zetronik.torrentplayer.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Phone → TV protocol. The TV advertises [SERVICE_TYPE] over mDNS (NSD) with its stable id in the
 * [ATTR_ID] TXT attribute. Each TCP connection carries one request and one response, each a single line
 * of JSON. Plain sockets rather than HTTP: Android blocks cleartext HTTP, and there is nothing to gain
 * from it inside a LAN.
 *
 * A phone pairs once by typing the code the TV shows ([RequestType.PAIR_START], [RequestType.PAIR_CONFIRM])
 * and then sends the token it got with every request.
 */
object RemoteProtocol {
    const val SERVICE_TYPE = "_torrentplayer._tcp"
    const val ATTR_ID = "id"

    /** Big enough for a `.torrent` file (the engine accepts up to 16 MB) in base64. */
    const val MAX_LINE_BYTES = 24 * 1024 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed")
            if (b == '\n'.code) break
            out.write(b)
            if (out.size() > MAX_LINE_BYTES) throw IOException("Message too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun writeLine(output: OutputStream, line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }
}

object RequestType {
    const val PAIR_START = "pair_start"
    const val PAIR_CONFIRM = "pair_confirm"
    const val PLAY = "play"
    const val STATUS = "status"
    const val CONTROL = "control"
}

object RemoteError {
    const val UNAUTHORIZED = "unauthorized"
    const val WRONG_CODE = "wrong_code"
    const val NO_PAIRING = "no_pairing"
    const val BAD_REQUEST = "bad_request"
    const val FAILED = "failed"
}

@Serializable
data class RemoteRequest(
    val type: String,
    val token: String? = null,
    /** Name of the phone, shown on the TV while pairing. */
    val client: String? = null,
    val code: String? = null,
    val play: PlayRequest? = null,
    val command: RemoteCommand? = null,
)

/**
 * Start playing on the TV: a torrent file ([infoHash], [fileIndex], [magnet]), or local videos the phone
 * streams itself ([stream]).
 */
@Serializable
data class PlayRequest(
    val infoHash: String = "",
    val fileIndex: Int = -1,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val magnet: String = "",
    /** The `.torrent` file in base64, so the TV does not have to fetch the metadata from peers. */
    val torrent: String? = null,
    val stream: StreamOffer? = null,
)

/**
 * Local videos served by the phone ([com.zetronik.torrentplayer.remote.LocalStreamServer]) on [port] of
 * the address the request came from. [secret] authorizes reads; [index] is the item to start with.
 */
@Serializable
data class StreamOffer(val port: Int, val secret: String, val items: List<StreamItem>, val index: Int)

/** [url] is set for playlist entries the TV can open itself (http/https); everything else is streamed. */
@Serializable
data class StreamItem(val title: String, val size: Long, val url: String? = null)

/**
 * The phone's stream server: one connection per read. The TV sends one JSON line [StreamRead], the phone
 * answers with one JSON line [StreamHeader] followed by the raw bytes from [StreamRead.offset] to the end
 * of the file; the TV closes the connection when it has read enough (a seek opens a new one).
 */
@Serializable
data class StreamRead(val secret: String, val item: Int, val offset: Long)

@Serializable
data class StreamHeader(val ok: Boolean, val size: Long = 0, val error: String? = null)

@Serializable
data class RemoteCommand(val action: String, val value: Long = 0)

object RemoteAction {
    const val TOGGLE = "toggle"
    const val SEEK_TO = "seek_to"
    const val SEEK_BY = "seek_by"
    const val PREVIOUS = "previous"
    const val NEXT = "next"
    /** [RemoteCommand.value] is the playlist index. */
    const val PLAY_ITEM = "play_item"
    /** [RemoteCommand.value] is the index in [RemoteStatus.audio]. */
    const val AUDIO = "audio"
    /** [RemoteCommand.value] is the index in [RemoteStatus.subtitles], or -1 to turn subtitles off. */
    const val SUBTITLES = "subtitles"
    /** Closes the player on the TV. */
    const val STOP = "stop"
}

@Serializable
data class RemoteResponse(
    val ok: Boolean,
    val error: String? = null,
    val token: String? = null,
    val status: RemoteStatus? = null,
)

/** What the TV is playing. [active] is false when its player is not open. */
@Serializable
data class RemoteStatus(
    val active: Boolean,
    val title: String = "",
    val infoHash: String? = null,
    val fileIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val playlist: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val audio: List<RemoteTrack> = emptyList(),
    val subtitles: List<RemoteTrack> = emptyList(),
    val subtitlesOff: Boolean = true,
)

@Serializable
data class RemoteTrack(val label: String, val selected: Boolean)
