package com.zetronik.torrentplayer.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** A TV found on the network. [id] is stable across restarts and address changes; pairings are keyed by it. */
data class RemoteDevice(val id: String, val name: String, val host: String, val port: Int)

class RemoteException(val error: String) : IOException(error)

/** Phone side of the protocol: one request per connection, see [RemoteProtocol]. */
class RemoteClient(private val host: String, private val port: Int, private val token: String?) {

    constructor(device: RemoteDevice, token: String?) : this(device.host, device.port, token)

    suspend fun startPairing(client: String) {
        send(RemoteRequest(RequestType.PAIR_START, client = client))
    }

    /** Returns the token for later requests. */
    suspend fun confirmPairing(client: String, code: String): String =
        send(RemoteRequest(RequestType.PAIR_CONFIRM, client = client, code = code)).token
            ?: throw RemoteException(RemoteError.FAILED)

    suspend fun play(request: PlayRequest) {
        send(RemoteRequest(RequestType.PLAY, token = token, play = request))
    }

    suspend fun status(): RemoteStatus =
        send(RemoteRequest(RequestType.STATUS, token = token)).status ?: RemoteStatus(active = false)

    suspend fun control(action: String, value: Long = 0) {
        send(RemoteRequest(RequestType.CONTROL, token = token, command = RemoteCommand(action, value)))
    }

    /** @throws IOException when the TV is unreachable, [RemoteException] when it refuses the request. */
    private suspend fun send(request: RemoteRequest): RemoteResponse = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            RemoteProtocol.writeLine(socket.getOutputStream(), RemoteProtocol.json.encodeToString(request))
            val line = RemoteProtocol.readLine(BufferedInputStream(socket.getInputStream()))
            val response = try {
                RemoteProtocol.json.decodeFromString<RemoteResponse>(line)
            } catch (e: IllegalArgumentException) {
                throw IOException("Bad response", e)
            }
            if (!response.ok) throw RemoteException(response.error ?: RemoteError.FAILED)
            response
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

/** Tokens this phone got from TVs, by TV id. */
class RemotePairings(context: Context) {
    private val prefs = context.getSharedPreferences("remote_pairings", Context.MODE_PRIVATE)

    fun token(deviceId: String): String? = prefs.getString(deviceId, null)

    fun save(deviceId: String, token: String) = prefs.edit { putString(deviceId, token) }

    fun remove(deviceId: String) = prefs.edit { remove(deviceId) }
}
