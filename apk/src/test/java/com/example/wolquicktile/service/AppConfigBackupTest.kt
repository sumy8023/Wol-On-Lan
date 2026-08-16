package com.example.wolquicktile.service

import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.entity.GroupEntity
import com.example.wolquicktile.data.entity.ProxyNodeEntity
import com.example.wolquicktile.data.entity.TileBindingEntity
import com.example.wolquicktile.data.preferences.ProxySettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigBackupTest {
    private val snapshot = AppConfigSnapshot(
        groups = listOf(GroupEntity(id = 1, name = "办公室", sortOrder = 0, createdTime = 100)),
        proxyNodes = listOf(
            ProxyNodeEntity(
                id = 2,
                name = "家庭代理",
                address = "http://example.test",
                port = 14250,
                key = "node-key",
                enabled = true,
                autoTestIntervalSeconds = 45,
                createdTime = 200
            )
        ),
        devices = listOf(
            DeviceEntity(
                id = 3,
                name = "工作站",
                macAddress = "AA:BB:CC:DD:EE:FF",
                broadcastAddress = "192.168.1.255",
                port = 9,
                tileEnabled = true,
                useProxyWake = true,
                proxyAlsoLocalWake = false,
                proxyNodeId = 2,
                groupId = 1,
                createdTime = 300
            )
        ),
        tileBindings = listOf(TileBindingEntity(tileIndex = 1, deviceId = 3)),
        proxySettings = ProxySettings("http://legacy.test:14250", "legacy-key"),
        androidProxy = AndroidProxyBackup(15432, "android-key", enabled = true)
    )

    @Test
    fun completeApplicationConfigRoundTrips() {
        val encoded = AppConfigBackup.serialize(snapshot)
        val decoded = AppConfigBackup.parse(encoded).getOrThrow()

        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("\"format\": \"wol-android-config\""))
        assertTrue(encoded.contains("\"proxy_nodes\""))
        assertTrue(encoded.contains("\"auto_test_interval_seconds\": 45"))
        assertTrue(encoded.contains("\"tile_bindings\""))
    }

    @Test
    fun oldConfigWithoutAutoTestIntervalUsesDefault() {
        val root = JSONObject(AppConfigBackup.serialize(snapshot))
        root.getJSONArray("proxy_nodes").getJSONObject(0).remove("auto_test_interval_seconds")

        val decoded = AppConfigBackup.parse(root.toString()).getOrThrow()

        assertEquals(ProxyNodeEntity.DEFAULT_AUTO_TEST_INTERVAL_SECONDS, decoded.proxyNodes.single().autoTestIntervalSeconds)
    }

    @Test
    fun rejectsDanglingReferencesBeforeImport() {
        val root = JSONObject(AppConfigBackup.serialize(snapshot))
        root.getJSONArray("devices").getJSONObject(0).put("group_id", 999)

        val result = AppConfigBackup.parse(root.toString())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不存在的分组"))
    }

    @Test
    fun rejectsUnknownFormatAndInvalidPorts() {
        val wrongFormat = JSONObject(AppConfigBackup.serialize(snapshot)).put("format", "other")
        assertTrue(AppConfigBackup.parse(wrongFormat.toString()).isFailure)

        val invalidPort = JSONObject(AppConfigBackup.serialize(snapshot))
        invalidPort.getJSONArray("proxy_nodes").getJSONObject(0).put("port", 70000)
        assertTrue(AppConfigBackup.parse(invalidPort.toString()).isFailure)

        val invalidInterval = JSONObject(AppConfigBackup.serialize(snapshot))
        invalidInterval.getJSONArray("proxy_nodes").getJSONObject(0).put("auto_test_interval_seconds", 4)
        val invalidIntervalResult = AppConfigBackup.parse(invalidInterval.toString())
        assertTrue(invalidIntervalResult.isFailure)
        assertTrue(invalidIntervalResult.exceptionOrNull()?.message.orEmpty().contains("不能少于 5 秒"))
    }
}
