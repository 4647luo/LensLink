package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Optional standard PTP discovery. Absence of advertising does not imply absence of a camera. */
object NikonDiscovery {
    data class Device(val name: String, val ip: String, val port: Int)
    @Suppress("DEPRECATION")
    fun discover(context: Context) = callbackFlow {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val queue = java.util.ArrayDeque<NsdServiceInfo>()
        val lock = Any()
        var resolving = false
        var stopped = false
        fun next() {
            val service = synchronized(lock) {
                if (stopped || resolving || queue.isEmpty()) null
                else queue.removeFirst().also { resolving = true }
            } ?: return
            try {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }; next()
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress
                        if (ip != null && !ip.contains(':')) trySend(Device(info.serviceName, ip, info.port))
                        synchronized(lock) { resolving = false }; next()
                    }
                })
            } catch (e: Exception) {
                synchronized(lock) { resolving = false }; next()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { close(java.io.IOException("局域网发现失败 ($code)")) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { if (!stopped) queue.add(info) }; next()
            }
        }
        try { manager.discoverServices("_ptp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { close(e) }
        awaitClose {
            synchronized(lock) { stopped = true; queue.clear() }
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
