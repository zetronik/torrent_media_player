package com.zetronik.torrentplayer.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Finds TVs running the app on the local network (see [RemoteReceiver]). */
class DeviceDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val byService = ConcurrentHashMap<String, RemoteDevice>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolver: Job? = null

    /** Main thread. Resolving runs in [scope] until [stop]. */
    fun start(scope: CoroutineScope) {
        if (listener != null) return
        // Before Android 14 NsdManager resolves one service at a time, so found services are queued.
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                found.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                byService.remove(info.serviceName)
                publish()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        listener = discoveryListener
        nsd.discoverServices(RemoteProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        resolver = scope.launch {
            for (info in found) {
                val device = resolve(info) ?: continue
                byService[info.serviceName] = device
                publish()
            }
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        resolver?.cancel()
        resolver = null
        byService.clear()
        publish()
    }

    private fun publish() {
        _devices.value = byService.values.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement needs API 34
    private suspend fun resolve(info: NsdServiceInfo): RemoteDevice? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Cannot resolve ${info.serviceName}: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(info.toDevice())
                }
            })
        }
    }

    @Suppress("DEPRECATION") // host: its replacement needs API 34
    private fun NsdServiceInfo.toDevice(): RemoteDevice? {
        val address = host?.hostAddress ?: return null
        val id = attributes[RemoteProtocol.ATTR_ID]?.toString(Charsets.UTF_8) ?: serviceName
        return RemoteDevice(id = id, name = serviceName, host = address, port = port)
    }

    private companion object {
        const val TAG = "DeviceDiscovery"
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
