package com.zetronik.torrentplayer.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom

/**
 * Phone side of streaming local videos to a TV: serves the offered files to whoever knows the secret,
 * with random access (each read starts at an offset, see [StreamRead]). Kept alive by [StreamService]
 * while the phone's screen is off, until [stop] (stop on TV, watch on phone, the notification's button)
 * or the next offer.
 */
class LocalStreamServer(private val context: Context, private val scope: CoroutineScope) {

    /** A video the phone can play: a `file://` or `content://` URI, or an http(s) URL the TV opens itself. */
    data class Source(val uri: String, val title: String)

    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile
    private var secret = ""

    @Volatile
    var sources: List<Source> = emptyList()
        private set

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Starts serving [items] (replacing an earlier offer) and returns what the TV needs to read them. */
    @Synchronized
    fun offer(items: List<Source>, index: Int): StreamOffer {
        stop()
        val socket = ServerSocket(0)
        server = socket
        sources = items
        secret = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        _active.value = true
        StreamService.start(context)
        return StreamOffer(
            port = socket.localPort,
            secret = secret,
            items = items.map { StreamItem(it.title, size(it), it.uri.takeIf(::isUrl)) },
            index = index,
        )
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        acceptJob = null
        if (_active.value) {
            _active.value = false
            StreamService.stop(context)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            // Throws once stop() closes the socket.
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            scope.launch(Dispatchers.IO) { serve(client) }
        }
    }

    private fun serve(client: Socket) = client.use { socket ->
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.sendBufferSize = BUFFER_BYTES
            val output = socket.getOutputStream()
            val request = RemoteProtocol.json.decodeFromString<StreamRead>(
                RemoteProtocol.readLine(BufferedInputStream(socket.getInputStream()))
            )
            val source = sources.getOrNull(request.item)
            if (request.secret != secret || source == null || isUrl(source.uri) || request.offset < 0) {
                RemoteProtocol.writeLine(output, RemoteProtocol.json.encodeToString(StreamHeader(ok = false, error = RemoteError.UNAUTHORIZED)))
                return@use
            }
            val (input, size) = open(source.uri.toUri(), request.offset)
            input.use {
                RemoteProtocol.writeLine(output, RemoteProtocol.json.encodeToString(StreamHeader(ok = true, size = size)))
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.flush()
            }
        } catch (_: SocketException) {
            // The TV closed the connection: it seeked or has buffered enough.
        } catch (e: IOException) {
            Log.w(TAG, "Stream read failed", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad stream request", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to the streamed file", e)
        }
    }

    /** The file positioned at [offset], with its total size. */
    private fun open(uri: Uri, offset: Long): Pair<InputStream, Long> {
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path))
            val input = FileInputStream(file)
            input.channel.position(offset)
            return input to file.length()
        }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        val input = FileInputStream(descriptor.fileDescriptor)
        val size = descriptor.statSize
        input.channel.position(offset)
        val stream = object : InputStream() {
            override fun read() = input.read()
            override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
            override fun close() {
                input.close()
                descriptor.close()
            }
        }
        return stream to size
    }

    private fun size(source: Source): Long = runCatching {
        val uri = source.uri.toUri()
        when (uri.scheme) {
            "file" -> File(checkNotNull(uri.path)).length()
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            else -> -1
        }
    }.getOrDefault(-1)

    private fun isUrl(uri: String) = uri.startsWith("http://") || uri.startsWith("https://")

    private companion object {
        const val TAG = "LocalStreamServer"
        const val READ_TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 256 * 1024
    }
}
