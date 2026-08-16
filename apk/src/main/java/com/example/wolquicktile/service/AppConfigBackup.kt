package com.example.wolquicktile.service

import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.entity.GroupEntity
import com.example.wolquicktile.data.entity.ProxyNodeEntity
import com.example.wolquicktile.data.entity.TileBindingEntity
import com.example.wolquicktile.data.preferences.ProxySettings
import org.json.JSONArray
import org.json.JSONObject

data class AndroidProxyBackup(
    val port: Int,
    val key: String,
    val enabled: Boolean
)

data class AppConfigSnapshot(
    val groups: List<GroupEntity>,
    val devices: List<DeviceEntity>,
    val proxyNodes: List<ProxyNodeEntity>,
    val tileBindings: List<TileBindingEntity>,
    val proxySettings: ProxySettings,
    val androidProxy: AndroidProxyBackup?
)

object AppConfigBackup {
    private const val FORMAT = "wol-android-config"
    private const val VERSION = 1
    private const val MAX_ITEMS = 10_000
    private val MAC_PATTERN = Regex("^(([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})$")

    fun serialize(snapshot: AppConfigSnapshot): String {
        validate(snapshot)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exported_at", System.currentTimeMillis())
            .put("groups", JSONArray().apply {
                snapshot.groups.forEach { group ->
                    put(JSONObject()
                        .put("id", group.id)
                        .put("name", group.name)
                        .put("sort_order", group.sortOrder)
                        .put("created_time", group.createdTime))
                }
            })
            .put("proxy_nodes", JSONArray().apply {
                snapshot.proxyNodes.forEach { node ->
                    put(JSONObject()
                        .put("id", node.id)
                        .put("name", node.name)
                        .put("address", node.address)
                        .put("port", node.port)
                        .put("key", node.key)
                        .put("enabled", node.enabled)
                        .put("auto_test_interval_seconds", node.autoTestIntervalSeconds)
                        .put("created_time", node.createdTime))
                }
            })
            .put("devices", JSONArray().apply {
                snapshot.devices.forEach { device ->
                    put(JSONObject()
                        .put("id", device.id)
                        .put("name", device.name)
                        .put("mac_address", device.macAddress)
                        .put("broadcast_address", device.broadcastAddress)
                        .put("port", device.port)
                        .put("tile_enabled", device.tileEnabled)
                        .put("use_proxy_wake", device.useProxyWake)
                        .put("proxy_also_local_wake", device.proxyAlsoLocalWake)
                        .putNullable("proxy_node_id", device.proxyNodeId)
                        .putNullable("group_id", device.groupId)
                        .put("created_time", device.createdTime))
                }
            })
            .put("tile_bindings", JSONArray().apply {
                snapshot.tileBindings.forEach { binding ->
                    put(JSONObject()
                        .put("tile_index", binding.tileIndex)
                        .put("device_id", binding.deviceId))
                }
            })
            .put("proxy_settings", JSONObject()
                .put("server_url", snapshot.proxySettings.serverUrl)
                .put("key", snapshot.proxySettings.key))
            .put("android_proxy", snapshot.androidProxy?.let { proxy ->
                JSONObject()
                    .put("port", proxy.port)
                    .put("key", proxy.key)
                    .put("enabled", proxy.enabled)
            } ?: JSONObject.NULL)
            .toString(2) + "\n"
    }

    fun parse(text: String): Result<AppConfigSnapshot> = runCatching {
        val content = text.removePrefix("\uFEFF").trim()
        require(content.isNotBlank()) { "配置文件为空" }
        val root = JSONObject(content)
        require(root.requiredString("format") == FORMAT) { "不是 WOL Android 配置文件" }
        require(root.requiredInt("version") == VERSION) { "不支持的配置版本" }

        val groups = root.requiredArray("groups").mapObjects("分组") { value ->
            GroupEntity(
                id = value.requiredPositiveId("id", "分组"),
                name = value.requiredNonBlank("name", "分组名称"),
                sortOrder = value.requiredInt("sort_order"),
                createdTime = value.requiredLong("created_time")
            )
        }
        val proxyNodes = root.requiredArray("proxy_nodes").mapObjects("代理节点") { value ->
            ProxyNodeEntity(
                id = value.requiredPositiveId("id", "代理节点"),
                name = value.requiredNonBlank("name", "代理名称"),
                address = value.requiredNonBlank("address", "代理地址"),
                port = value.requiredPort("port", "代理端口"),
                key = value.requiredNonBlank("key", "代理 Key"),
                enabled = value.requiredBoolean("enabled"),
                autoTestIntervalSeconds = value.optionalInt(
                    "auto_test_interval_seconds",
                    ProxyNodeEntity.DEFAULT_AUTO_TEST_INTERVAL_SECONDS
                ),
                createdTime = value.requiredLong("created_time")
            )
        }
        val devices = root.requiredArray("devices").mapObjects("设备") { value ->
            DeviceEntity(
                id = value.requiredPositiveId("id", "设备"),
                name = value.requiredNonBlank("name", "设备名称"),
                macAddress = value.requiredNonBlank("mac_address", "MAC 地址"),
                broadcastAddress = value.requiredNonBlank("broadcast_address", "广播地址"),
                port = value.requiredPort("port", "UDP 端口"),
                tileEnabled = value.requiredBoolean("tile_enabled"),
                useProxyWake = value.requiredBoolean("use_proxy_wake"),
                proxyAlsoLocalWake = value.requiredBoolean("proxy_also_local_wake"),
                proxyNodeId = value.nullableLong("proxy_node_id"),
                groupId = value.nullableLong("group_id"),
                createdTime = value.requiredLong("created_time")
            )
        }
        val tileBindings = root.requiredArray("tile_bindings").mapObjects("磁贴绑定") { value ->
            TileBindingEntity(
                tileIndex = value.requiredInt("tile_index"),
                deviceId = value.requiredPositiveId("device_id", "磁贴设备")
            )
        }
        val proxyValue = root.requiredObject("proxy_settings")
        val proxySettings = ProxySettings(
            serverUrl = proxyValue.requiredString("server_url"),
            key = proxyValue.requiredString("key")
        )
        val androidProxy = if (!root.has("android_proxy") || root.isNull("android_proxy")) {
            null
        } else {
            root.requiredObject("android_proxy").let { value ->
                AndroidProxyBackup(
                    port = value.requiredPort("port", "本机代理端口"),
                    key = value.requiredNonBlank("key", "本机代理 Key"),
                    enabled = value.requiredBoolean("enabled")
                )
            }
        }
        AppConfigSnapshot(groups, devices, proxyNodes, tileBindings, proxySettings, androidProxy)
            .also(::validate)
    }

    private fun validate(snapshot: AppConfigSnapshot) {
        require(snapshot.groups.isNotEmpty()) { "配置至少需要一个分组" }
        require(snapshot.groups.size <= MAX_ITEMS && snapshot.devices.size <= MAX_ITEMS &&
            snapshot.proxyNodes.size <= MAX_ITEMS && snapshot.tileBindings.size <= TileRegistry.MAX_TILES
        ) { "配置条目过多" }

        val groupIds = snapshot.groups.map { it.id }.toSet()
        val nodeIds = snapshot.proxyNodes.map { it.id }.toSet()
        val deviceIds = snapshot.devices.map { it.id }.toSet()
        require(groupIds.size == snapshot.groups.size && groupIds.none { it <= 0 }) { "分组 ID 重复或无效" }
        require(nodeIds.size == snapshot.proxyNodes.size && nodeIds.none { it <= 0 }) { "代理节点 ID 重复或无效" }
        require(deviceIds.size == snapshot.devices.size && deviceIds.none { it <= 0 }) { "设备 ID 重复或无效" }

        snapshot.groups.forEach { require(it.name.isNotBlank()) { "分组名称不能为空" } }
        snapshot.proxyNodes.forEach { node ->
            require(node.name.isNotBlank() && node.address.isNotBlank() && node.key.isNotBlank()) { "代理节点配置不完整" }
            require(node.port in 1..65535) { "代理端口范围应为 1-65535" }
            require(node.autoTestIntervalSeconds >= ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS) {
                "代理自动测试间隔不能少于 ${ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS} 秒"
            }
        }
        snapshot.devices.forEach { device ->
            require(device.name.isNotBlank()) { "设备名称不能为空" }
            require(MAC_PATTERN.matches(device.macAddress)) { "设备 ${device.name} 的 MAC 地址无效" }
            require(isIpv4(device.broadcastAddress)) { "设备 ${device.name} 的广播地址无效" }
            require(device.port in 1..65535) { "设备 ${device.name} 的 UDP 端口无效" }
            require(device.groupId != null && groupIds.contains(device.groupId)) { "设备 ${device.name} 引用了不存在的分组" }
            require(device.proxyNodeId == null || device.proxyNodeId in nodeIds) { "设备 ${device.name} 引用了不存在的代理节点" }
        }
        val tileIndexes = snapshot.tileBindings.map { it.tileIndex }
        val tileDevices = snapshot.tileBindings.map { it.deviceId }
        require(tileIndexes.toSet().size == tileIndexes.size && tileDevices.toSet().size == tileDevices.size) { "磁贴绑定存在重复" }
        snapshot.tileBindings.forEach { binding ->
            require(binding.tileIndex in 1..TileRegistry.MAX_TILES) { "磁贴编号无效" }
            require(binding.deviceId in deviceIds) { "磁贴引用了不存在的设备" }
        }
        snapshot.androidProxy?.let { proxy ->
            require(proxy.port in 1..65535 && proxy.key.isNotBlank()) { "本机代理配置无效" }
        }
        require(snapshot.proxySettings.serverUrl.isBlank() || snapshot.proxySettings.key.isNotBlank()) {
            "旧版代理配置缺少 Key"
        }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.trim().split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && number != null && number in 0..255
        }
    }

    private fun JSONObject.putNullable(name: String, value: Long?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.requiredString(name: String): String {
        require(has(name) && !isNull(name) && get(name) is String) { "字段 $name 必须是字符串" }
        return getString(name)
    }

    private fun JSONObject.requiredNonBlank(name: String, label: String): String =
        requiredString(name).trim().also { require(it.isNotBlank()) { "$label 不能为空" } }

    private fun JSONObject.requiredInt(name: String): Int {
        require(has(name) && !isNull(name) && get(name) is Number) { "字段 $name 必须是整数" }
        val number = get(name) as Number
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) { "字段 $name 必须是整数" }
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "字段 $name 超出范围" }
        return value.toInt()
    }

    private fun JSONObject.optionalInt(name: String, defaultValue: Int): Int =
        if (!has(name)) defaultValue else requiredInt(name)

    private fun JSONObject.requiredLong(name: String): Long {
        require(has(name) && !isNull(name) && get(name) is Number) { "字段 $name 必须是整数" }
        val number = get(name) as Number
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) { "字段 $name 必须是整数" }
        return value
    }

    private fun JSONObject.requiredPositiveId(name: String, label: String): Long =
        requiredLong(name).also { require(it > 0) { "$label ID 无效" } }

    private fun JSONObject.requiredPort(name: String, label: String): Int =
        requiredInt(name).also { require(it in 1..65535) { "$label 范围应为 1-65535" } }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        require(has(name) && !isNull(name) && get(name) is Boolean) { "字段 $name 必须是布尔值" }
        return getBoolean(name)
    }

    private fun JSONObject.nullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else requiredLong(name)

    private fun JSONObject.requiredArray(name: String): JSONArray {
        require(has(name) && get(name) is JSONArray) { "字段 $name 必须是数组" }
        return getJSONArray(name)
    }

    private fun JSONObject.requiredObject(name: String): JSONObject {
        require(has(name) && !isNull(name) && get(name) is JSONObject) { "字段 $name 必须是对象" }
        return getJSONObject(name)
    }

    private inline fun <T> JSONArray.mapObjects(label: String, transform: (JSONObject) -> T): List<T> {
        require(length() <= MAX_ITEMS) { "$label 条目过多" }
        return List(length()) { index ->
            require(get(index) is JSONObject) { "$label 第 ${index + 1} 项格式无效" }
            transform(getJSONObject(index))
        }
    }
}
