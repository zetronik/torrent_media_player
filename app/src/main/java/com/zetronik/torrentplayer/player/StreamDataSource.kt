package com.zetronik.torrentplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.zetronik.torrentplayer.remote.RemoteProtocol
import com.zetronik.torrentplayer.remote.StreamHeader
import com.zetronik.torrentplayer.remote.StreamRead
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `tpstream://<host>:<port>/<item>?s=<secret>`: a local video of a phone, read from its
 * [com.zetronik.torrentplayer.remote.LocalStreamServer]. Plain sockets, like the rest of the remote
 * protocol, since cleartext HTTP is blocked.
 */
object StreamUris {
    const val SCHEME = "tpstream"

    fun build(host: String, port: Int, item: Int, secret: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .encodedAuthority(if (host.contains(':')) "[$host]:$port" else "$host:$port")
        .appendPath(item.toString())
        .appendQueryParameter("s", secret)
        .build()
}

/** Reads one [StreamUris] URI; every open (start or seek) is a new connection starting at the wanted offset. */
@UnstableApi
class StreamDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        this.uri = uri
        transferInitializing(dataSpec)
        val host = uri.host ?: throw IOException("No host in $uri")
        val item = uri.lastPathSegment?.toIntOrNull() ?: throw IOException("No item in $uri")
        val socket = Socket().also { this.socket = it }
        socket.connect(InetSocketAddress(host, uri.port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.receiveBufferSize = BUFFER_BYTES
        val request = StreamRead(uri.getQueryParameter("s").orEmpty(), item, dataSpec.position)
        RemoteProtocol.writeLine(socket.getOutputStream(), RemoteProtocol.json.encodeToString(request))
        val input = BufferedInputStream(socket.getInputStream(), BUFFER_BYTES).also { this.input = it }
        val header = RemoteProtocol.json.decodeFromString<StreamHeader>(RemoteProtocol.readLine(input))
        if (!header.ok) throw IOException("Phone refused the stream: ${header.error}")
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (header.size - dataSpec.position).coerceAtLeast(0)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val n = checkNotNull(input).read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (n < 0) throw EOFException("Stream ended early")
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { socket?.close() }
        socket = null
        input = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 256 * 1024
    }
}

/** For non-torrent playback: phone streams through [StreamDataSource], everything else through the default. */
@UnstableApi
class LocalDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(context)
}

@UnstableApi
private class RoutingDataSource(context: Context) : DataSource {
    private val default = DefaultDataSource.Factory(context).createDataSource()
    private val stream = StreamDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        stream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == StreamUris.SCHEME) stream else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        current?.close()
        current = null
    }
}
