package com.feelime.ime

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI

class CancellableModelTransportTest {
    @Test
    fun `cancel after registration but before execute connects sends no request`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 200
            lateinit var transport: CancellableModelTransport
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) {
                        // execute has started, but no DNS or socket has been
                        // created. A network callback can cancel at this point.
                        transport.close()
                    }
                }).build()
            transport = CancellableModelTransport(client, true) {}
            val error = runCatching {
                transport.open(URI("http://127.0.0.1:${server.localPort}/model"), 0)
            }.exceptionOrNull()
            assertTrue(error is ModelDownloadNetworkChanged)
            val incoming = runCatching { server.accept().use { } }.exceptionOrNull()
            assertTrue("cancelled call must not open a socket", incoming is SocketTimeoutException)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
