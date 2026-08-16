package com.example.wolquicktile.domain.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyWakeClientTest {
    @Test
    fun acceptsOnlyTheExpectedProxyHealthResponse() {
        assertTrue(
            ProxyWakeClient.isExpectedHealthResponse(
                """{"version":"1.0.2","name":"WOL Proxy","ok":true}"""
            )
        )

        assertFalse(ProxyWakeClient.isExpectedHealthResponse("""{"ok":true}"""))
        assertFalse(ProxyWakeClient.isExpectedHealthResponse("""{"ok":true,"name":"Other Service"}"""))
        assertFalse(ProxyWakeClient.isExpectedHealthResponse("""{"ok":false,"name":"WOL Proxy"}"""))
        assertFalse(ProxyWakeClient.isExpectedHealthResponse("<html>OK</html>"))
    }

    @Test
    fun readsCooldownFromHealthAndKeepsLegacyFallback() {
        val current = ProxyWakeClient.parseHealthResponse(
            """{"ok":true,"name":"WOL Proxy","version":"1.0.3","cooldown_seconds":12}"""
        )
        assertEquals("1.0.3", current.version)
        assertEquals(12, current.cooldownSeconds)

        val legacy = ProxyWakeClient.parseHealthResponse(
            """{"ok":true,"name":"WOL Proxy","version":"1.0.2"}"""
        )
        assertEquals(ProxyWakeClient.LEGACY_COOLDOWN_SECONDS, legacy.cooldownSeconds)
    }

    @Test
    fun translatesMacWhitelistRejectionExactly() {
        assertEquals(
            "该 MAC 地址不在代理白名单中",
            ProxyWakeClient.errorMessage("mac_not_allowed", null)
        )
    }
}
