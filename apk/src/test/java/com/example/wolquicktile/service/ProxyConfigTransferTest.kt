package com.example.wolquicktile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTransferTest {
    @Test
    fun importsDesktopYamlConfig() {
        val config = ProxyConfigTransfer.parse(
            """
            listen: ":15432"
            key: "proxy-key#1"
            cooldown_seconds: 9
            allow_macs: []
            """.trimIndent()
        ).getOrThrow()

        assertEquals(15432, config.port)
        assertEquals("proxy-key#1", config.key)
    }

    @Test
    fun importsAdminApiJsonWrapper() {
        val config = ProxyConfigTransfer.parse(
            """{"ok":true,"config":{"listen":"0.0.0.0:14251","key":"json-key"}}"""
        ).getOrThrow()

        assertEquals(14251, config.port)
        assertEquals("json-key", config.key)
    }

    @Test
    fun exportedFormatsRoundTripAndUseDesktopFieldNames() {
        val source = AndroidProxyConfig(14250, "round-trip-key")
        ProxyConfigFormat.entries.forEach { format ->
            val exported = ProxyConfigTransfer.serialize(source, format)
            assertTrue(exported.contains("listen"))
            assertTrue(exported.contains("key"))
            assertEquals(source, ProxyConfigTransfer.parse(exported).getOrThrow())
        }
    }
}
