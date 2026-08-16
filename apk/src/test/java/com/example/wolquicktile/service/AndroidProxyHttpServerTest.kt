package com.example.wolquicktile.service

import com.example.wolquicktile.data.preferences.ProxySettings
import com.example.wolquicktile.domain.proxy.ProxyWakeClient
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

class AndroidProxyHttpServerTest {
    @Test
    fun generatedAndroidProxyKeyIsEightReadableCharacters() {
        repeat(20) {
            val key = AndroidProxyServiceController.generateKey()
            assertEquals(8, key.length)
            assertTrue(key.matches(Regex("[A-Za-z2-9]{8}")))
        }
    }

    @Test
    fun healthEndpointUsesTheSameSignedProtocolAsTheClient() {
        val port = ServerSocket(0).use { it.localPort }
        val key = "test-key-for-android-proxy"
        val server = AndroidProxyHttpServer(AndroidProxyConfig(port, key)) {}
        server.start()
        try {
            val endpoint = "http://127.0.0.1:$port"
            assertTrue(ProxyWakeClient.testConnection(ProxySettings(endpoint, key)).isSuccess)
            assertEquals(5, ProxyWakeClient.fetchHealth(ProxySettings(endpoint, key)).getOrThrow().cooldownSeconds)
            assertTrue(ProxyWakeClient.testConnection(ProxySettings(endpoint, "wrong-key")).isFailure)
        } finally {
            server.stop()
        }
    }
}
