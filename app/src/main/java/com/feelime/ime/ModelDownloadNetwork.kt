package com.feelime.ime

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A download keeps the network for which the user granted consent. Android
 * must not silently route a retry or redirect onto the new default network.
 * Both DNS and sockets are bound to that network. */
internal class ModelDownloadNetwork(
    private val manager: ConnectivityManager,
    private val network: Network,
    private val allowMetered: Boolean,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    @Volatile var cancelledByUser = false
        private set
    private val client = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val transport = CancellableModelTransport(
        client, BuildConfig.DEBUG && !BuildConfig.PLAY_DISTRIBUTION, ::check,
    )
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            if (available != network) stop()
        }

        override fun onLost(lost: Network) {
            if (lost == network) stop()
        }

        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
            if (changed == network && !allowed(capabilities)) stop()
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    fun check() {
        if (stopped.get() || manager.activeNetwork != network ||
            !allowed(manager.getNetworkCapabilities(network))) {
            stop()
            throw ModelDownloadNetworkChanged()
        }
    }

    private fun allowed(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (allowMetered || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))

    private fun stop() {
        stopped.set(true)
        transport.close()
    }

    fun cancelByUser() {
        cancelledByUser = true
        close()
    }

    override fun close() {
        runCatching { manager.unregisterNetworkCallback(callback) }
        stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
