package com.example.wolquicktile.watch

import com.example.wolquicktile.watch.domain.ProxyWakeClient
import com.example.wolquicktile.watch.domain.WakeOnLanSender
import com.example.wolquicktile.watch.data.WatchProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCoreTest {
    @Test
    fun acceptsCommonMacFormats() {
        assertTrue(WakeOnLanSender.isValidMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(WakeOnLanSender.isValidMac("aa-bb-cc-dd-ee-ff"))
        assertTrue(WakeOnLanSender.isValidMac("AABBCCDDEEFF"))
    }

    @Test
    fun rejectsMalformedMac() {
        assertTrue(!WakeOnLanSender.isValidMac("AA:BB:CC:DD:EE"))
        assertTrue(!WakeOnLanSender.isValidMac("not-a-mac"))
    }

    @Test
    fun parsesExpectedProxyHealth() {
        val health = ProxyWakeClient.parseHealthResponse(
            "{\"ok\":true,\"name\":\"WOL Proxy\",\"version\":\"1.0.2\",\"cooldown_seconds\":7}"
        )
        assertEquals("1.0.2", health.version)
        assertEquals(7, health.cooldownSeconds)
    }

    @Test
    fun rejectsNonProxyHealthResponse() {
        val error = runCatching {
            ProxyWakeClient.parseHealthResponse("{\"ok\":true,\"name\":\"Other Service\"}")
        }.exceptionOrNull()
        assertEquals("目标地址不是 WOL 代理服务", error?.message)
    }

    @Test
    fun normalizesProxyEndpointWithoutScheme() {
        val node = WatchProxyNode(address = "192.168.1.20", key = "secret")
        assertEquals("http://192.168.1.20:14250", ProxyWakeClient.endpoint(node))
    }

    @Test
    fun normalizesExplicitPortWithoutScheme() {
        val node = WatchProxyNode(address = "proxy.example.test:15000", key = "secret")
        assertEquals("http://proxy.example.test:15000", ProxyWakeClient.endpoint(node))
    }

    @Test
    fun preservesExplicitProxyPortAndHttps() {
        val node = WatchProxyNode(address = "https://proxy.example.test:8443/base", key = "secret")
        assertEquals("https://proxy.example.test:8443/base", ProxyWakeClient.endpoint(node))
    }

    @Test
    fun rejectsUnsupportedProxyScheme() {
        val error = runCatching {
            ProxyWakeClient.endpoint(WatchProxyNode(address = "ftp://proxy.example.test", key = "secret"))
        }.exceptionOrNull()
        assertEquals("代理服务器地址仅支持 HTTP 或 HTTPS", error?.message)
    }

    @Test
    fun rejectsProxyQueryParameters() {
        val error = runCatching {
            ProxyWakeClient.endpoint(WatchProxyNode(address = "https://proxy.example.test?token=1", key = "secret"))
        }.exceptionOrNull()
        assertEquals("代理服务器地址不支持查询参数或片段", error?.message)
    }
}
