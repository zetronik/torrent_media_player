package com.zetronik.torrentplayer.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.zetronik.torrentplayer.torrent.TorrentEngine
import com.zetronik.torrentplayer.torrent.TorrentInput
import com.zetronik.torrentplayer.torrent.TorrentUris
import com.zetronik.torrentplayer.ui.navigation.NavRequest
import com.zetronik.torrentplayer.ui.navigation.PlayerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/**
 * TV side of "play on TV": advertises the device over NSD and executes requests from paired phones.
 * Runs only while the app is in the foreground ([start] / [stop] from the activity): Android does not let
 * a background app bring its UI to the front, so a TV that cannot show the player should not be offered.
 */
class RemoteReceiver(
    private val context: Context,
    private val engine: TorrentEngine,
    private val session: RemoteSession,
    private val scope: CoroutineScope,
) {
    /** Shown on the TV while a phone pairs; the phone's user types [code]. */
    data class PairingPrompt(val client: String, val code: String)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val random = SecureRandom()

    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val prompt: StateFlow<PairingPrompt?> = _prompt.asStateFlow()
    private var promptExpiresAt = 0L
    private var wrongAttempts = 0

    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile
    private var navigate: ((NavRequest) -> Unit)? = null

    private val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE_ID, id) }
        }
    }

    /** Main thread. [navigate] opens the player for a request from a phone. */
    fun start(navigate: (NavRequest) -> Unit) {
        this.navigate = navigate
        if (server != null) return
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot open the remote control socket", e)
            return
        }
        server = socket
        scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        register(socket.localPort)
    }

    /** Main thread. */
    fun stop() {
        navigate = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        _prompt.value = null
    }

    fun cancelPairing() {
        synchronized(this) { _prompt.value = null }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName(context)
            serviceType = RemoteProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute(RemoteProtocol.ATTR_ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private suspend fun acceptLoop(socket: ServerSocket) = coroutineScope {
        while (true) {
            // Throws once stop() closes the socket.
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            launch { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) = client.use { socket ->
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val line = RemoteProtocol.readLine(BufferedInputStream(socket.getInputStream()))
            val response = try {
                handle(RemoteProtocol.json.decodeFromString<RemoteRequest>(line))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Bad request", e)
                failure(RemoteError.BAD_REQUEST)
            }
            RemoteProtocol.writeLine(socket.getOutputStream(), RemoteProtocol.json.encodeToString(response))
        } catch (e: IOException) {
            Log.w(TAG, "Remote connection failed", e)
        }
    }

    private suspend fun handle(request: RemoteRequest): RemoteResponse {
        when (request.type) {
            RequestType.PAIR_START -> return startPairing(request.client.orEmpty())
            RequestType.PAIR_CONFIRM -> return confirmPairing(request.code.orEmpty())
        }
        if (!isPaired(request.token)) return failure(RemoteError.UNAUTHORIZED)
        return when (request.type) {
            RequestType.PLAY -> request.play?.let { play(it) } ?: failure(RemoteError.BAD_REQUEST)
            RequestType.STATUS -> {
                val status = withContext(Dispatchers.Main) { session.playback?.status() }
                RemoteResponse(ok = true, status = status ?: RemoteStatus(active = false))
            }
            RequestType.CONTROL -> {
                val command = request.command ?: return failure(RemoteError.BAD_REQUEST)
                withContext(Dispatchers.Main) { session.playback?.execute(command) }
                RemoteResponse(ok = true)
            }
            else -> failure(RemoteError.BAD_REQUEST)
        }
    }

    private suspend fun play(request: PlayRequest): RemoteResponse {
        val infoHash = request.infoHash.lowercase()
        // Also used in a file name below.
        if (!INFO_HASH.matches(infoHash) || request.fileIndex < 0) return failure(RemoteError.BAD_REQUEST)

        val current = withContext(Dispatchers.Main) { session.playback }
        if (current != null && current.infoHash == infoHash) {
            withContext(Dispatchers.Main) { current.playFile(request.fileIndex, request.positionMs) }
            return RemoteResponse(ok = true)
        }

        request.torrent
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { engine.importMetadata(infoHash, it) }
        val magnet = request.magnet.takeIf { it.contains(infoHash, ignoreCase = true) } ?: "magnet:?xt=urn:btih:$infoHash"
        val navigate = navigate ?: return failure(RemoteError.FAILED)
        val route = PlayerRoute(
            uri = TorrentUris.build(infoHash, request.fileIndex).toString(),
            title = request.title,
            infoHash = infoHash,
            fileIndex = request.fileIndex,
            startPositionMs = request.positionMs,
            startDurationMs = request.durationMs,
        )
        val torrentActive = engine.isActive(infoHash)
        withContext(Dispatchers.Main) {
            navigate(NavRequest.PlayTorrent(TorrentInput.Magnet(magnet).source, route, torrentActive))
        }
        return RemoteResponse(ok = true)
    }

    private fun startPairing(client: String): RemoteResponse {
        val prompt = PairingPrompt(client.take(MAX_CLIENT_NAME).ifBlank { "?" }, "%04d".format(random.nextInt(10_000)))
        synchronized(this) {
            _prompt.value = prompt
            promptExpiresAt = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
            wrongAttempts = 0
        }
        scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            synchronized(this@RemoteReceiver) { if (_prompt.value === prompt) _prompt.value = null }
        }
        return RemoteResponse(ok = true)
    }

    private fun confirmPairing(code: String): RemoteResponse = synchronized(this) {
        val prompt = _prompt.value
        if (prompt == null || System.currentTimeMillis() > promptExpiresAt) return failure(RemoteError.NO_PAIRING)
        if (code.trim() != prompt.code) {
            // A few attempts only: four digits are not much of a secret otherwise.
            if (++wrongAttempts >= MAX_WRONG_ATTEMPTS) _prompt.value = null
            return failure(RemoteError.WRONG_CODE)
        }
        _prompt.value = null
        val token = UUID.randomUUID().toString()
        val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty() + token
        prefs.edit { putStringSet(KEY_TOKENS, tokens) }
        RemoteResponse(ok = true, token = token)
    }

    private fun isPaired(token: String?): Boolean =
        token != null && token in prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()

    private fun failure(error: String) = RemoteResponse(ok = false, error = error)

    private companion object {
        const val TAG = "RemoteReceiver"
        const val PREFS_NAME = "remote_receiver"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKENS = "tokens"
        const val READ_TIMEOUT_MS = 15_000
        const val PAIRING_TIMEOUT_MS = 120_000L
        const val MAX_WRONG_ATTEMPTS = 3
        const val MAX_CLIENT_NAME = 64
        val INFO_HASH = Regex("^([0-9a-f]{40}|[0-9a-f]{64})$")
    }
}
