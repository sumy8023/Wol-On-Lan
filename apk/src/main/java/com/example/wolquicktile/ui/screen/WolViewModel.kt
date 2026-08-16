package com.example.wolquicktile.ui.screen

import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.wolquicktile.R
import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.entity.GroupEntity
import com.example.wolquicktile.data.entity.TileBindingEntity
import com.example.wolquicktile.data.entity.ProxyNodeEntity
import com.example.wolquicktile.data.preferences.ProxySettings
import com.example.wolquicktile.data.preferences.ProxySettingsRepository
import com.example.wolquicktile.domain.proxy.ProxyWakeClient
import com.example.wolquicktile.domain.wol.DeviceWakeDispatcher
import com.example.wolquicktile.domain.wol.WakeOnLanSender
import com.example.wolquicktile.repository.DeviceRepository
import com.example.wolquicktile.service.TileRegistry
import com.example.wolquicktile.service.WakeShortcutActivity
import com.example.wolquicktile.service.AndroidProxyService
import com.example.wolquicktile.service.AndroidProxyServiceController
import com.example.wolquicktile.service.AndroidProxyConfig
import com.example.wolquicktile.service.AndroidProxyBackup
import com.example.wolquicktile.service.AndroidProxyServiceStatus
import com.example.wolquicktile.service.AppConfigBackup
import com.example.wolquicktile.service.AppConfigSnapshot
import com.example.wolquicktile.service.ProxyConfigFormat
import com.example.wolquicktile.service.ProxyConfigTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOCAL_WAKE_COOLDOWN_SECONDS = 3

data class WolUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    val tileBindings: List<TileBindingEntity> = emptyList(),
    val proxySettings: ProxySettings = ProxySettings(),
    val proxyNodes: List<ProxyNodeEntity> = emptyList(),
    /** Connection status is tracked per node so testing one endpoint never changes another. */
    val proxyNodeConnections: Map<Long, ProxyConnectionState> = emptyMap(),
    val proxyNodeDraftConnection: ProxyConnectionState = ProxyConnectionState(),
    val proxyConnection: ProxyConnectionState = ProxyConnectionState(),
    val wakeCooldowns: Map<Long, Int> = emptyMap()
)

data class ProxyConnectionState(
    val label: String = "未配置",
    val isTesting: Boolean = false,
    val isSuccess: Boolean = false,
    val cooldownSeconds: Int? = null,
    /** Fingerprint of the endpoint that produced a successful result. */
    val testedFingerprint: String? = null
)

private data class WolDataState(
    val devices: List<DeviceEntity>,
    val groups: List<GroupEntity>,
    val tileBindings: List<TileBindingEntity>,
    val proxySettings: ProxySettings
    ,val proxyNodes: List<ProxyNodeEntity>
)

private data class ProxyNodeMonitor(
    val signature: String,
    val job: Job
)

class WolViewModel(
    private val repository: DeviceRepository,
    private val proxySettingsRepository: ProxySettingsRepository,
    private val appContext: Context
) : ViewModel() {
    private val proxyNodeDao = (appContext.applicationContext as com.example.wolquicktile.WolQuickTileApp).proxyNodeDao
    private val _proxyConnection = MutableStateFlow(ProxyConnectionState())
    private val _proxyNodeConnections = MutableStateFlow<Map<Long, ProxyConnectionState>>(emptyMap())
    private val _proxyNodeDraftConnection = MutableStateFlow(ProxyConnectionState())
    private val _wakeCooldowns = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private var proxyTestJob: Job? = null
    private val proxyNodeMonitors = mutableMapOf<Long, ProxyNodeMonitor>()
    private val proxyNodeTestMutexes = mutableMapOf<Long, Mutex>()
    private val wakeInFlight = mutableSetOf<Long>()

    private val dataState = combine(
        repository.observeDevices(),
        repository.observeGroups(),
        repository.observeTileBindings(),
        proxyNodeDao.observeAll(),
        proxySettingsRepository.settings
    ) { devices, groups, bindings, proxyNodes, proxySettings ->
        WolDataState(
            devices = devices,
            groups = groups,
            tileBindings = bindings,
            proxySettings = proxySettings,
            proxyNodes = proxyNodes
        )
    }.onStart {
        withContext(Dispatchers.IO) {
            repository.ensureDefaultGroup()
        }
    }

    val uiState: StateFlow<WolUiState> = combine(
        dataState,
        _proxyConnection,
        _proxyNodeConnections,
        _proxyNodeDraftConnection,
        _wakeCooldowns
    ) { data, proxyConnection, proxyNodeConnections, proxyNodeDraftConnection, wakeCooldowns ->
        WolUiState(
            devices = data.devices,
            groups = data.groups,
            tileBindings = data.tileBindings,
            proxySettings = data.proxySettings,
            proxyNodes = data.proxyNodes,
            proxyNodeConnections = proxyNodeConnections,
            proxyNodeDraftConnection = proxyNodeDraftConnection,
            proxyConnection = if (data.proxySettings.isConfigured) proxyConnection else ProxyConnectionState(),
            wakeCooldowns = wakeCooldowns
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WolUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    init {
        val savedSettings = proxySettingsRepository.getSettings()
        if (savedSettings.isConfigured) {
            startProxyTest(savedSettings, showResultMessage = false)
        }
        viewModelScope.launch {
            proxyNodeDao.observeAll().collect { nodes ->
                reconcileProxyNodeMonitors(nodes)
            }
        }
    }

    fun saveDevice(
        original: DeviceEntity?,
        name: String,
        macAddress: String,
        broadcastAddress: String,
        portText: String,
        groupId: Long?,
        useProxyWake: Boolean,
        proxyAlsoLocalWake: Boolean,
        proxyNodeId: Long? = null,
        onSaved: () -> Unit
    ) {
        val cleanName = name.trim()
        val cleanMac = macAddress.trim()
        val cleanBroadcast = normalizeAddressForStorage(broadcastAddress.trim())
        val port = portText.trim().ifBlank { "9" }.toIntOrNull()

        when {
            cleanName.isBlank() -> emitMessage("请输入设备名称")
            !WakeOnLanSender.isValidMac(cleanMac) -> emitMessage("MAC 地址格式不正确")
            cleanBroadcast == null -> emitMessage("IP地址/广播地址格式不正确")
            port == null || port !in 1..65535 -> emitMessage("端口范围应为 1-65535")
            else -> viewModelScope.launch {
                if (useProxyWake) {
                    val node = proxyNodeId?.let { proxyNodeDao.get(it) }
                    when {
                        proxyNodeId != null && node == null -> {
                            _messages.emit("所选代理节点不存在，请重新选择")
                            return@launch
                        }
                        node != null && !node.enabled -> {
                            _messages.emit("所选代理节点已停用，请先启用")
                            return@launch
                        }
                        node != null && !isProxyNodeReady(node) -> {
                            _messages.emit("请先测试所选代理节点，连接成功后才能保存")
                            return@launch
                        }
                        node == null && (original == null || original.useProxyWake != true || original.proxyNodeId != null) -> {
                            _messages.emit("请选择一个代理节点后再保存")
                            return@launch
                        }
                        node == null && !canUseProxyWake() -> {
                            _messages.emit(proxyUnavailableMessage())
                            return@launch
                        }
                    }
                }
                val device = (original ?: DeviceEntity(name = "", macAddress = "")).copy(
                    name = cleanName,
                    macAddress = cleanMac.uppercase(),
                    broadcastAddress = cleanBroadcast!!,
                    port = port!!,
                    groupId = groupId,
                    useProxyWake = useProxyWake,
                    proxyAlsoLocalWake = useProxyWake && proxyAlsoLocalWake,
                    proxyNodeId = if (useProxyWake) proxyNodeId else null
                )
                repository.saveDevice(device)
                _messages.emit("设备已保存")
                onSaved()
            }
        }
    }

    fun deleteDevice(device: DeviceEntity) {
        viewModelScope.launch {
            val tileIndex = repository.getTileIndexForDevice(device.id)
            repository.deleteDevice(device)
            tileIndex?.let(::disableTileComponent)
            _messages.emit("设备已删除")
        }
    }

    fun moveDeviceToGroup(device: DeviceEntity, groupId: Long?) {
        viewModelScope.launch {
            repository.saveDevice(device.copy(groupId = groupId))
            _messages.emit("已移动分组")
        }
    }

    fun saveGroup(original: GroupEntity?, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            emitMessage("请输入分组名称")
            return
        }

        viewModelScope.launch {
            val group = (original ?: GroupEntity(name = "")).copy(name = cleanName)
            repository.saveGroup(group)
            _messages.emit("分组已保存")
        }
    }

    fun saveProxyNode(
        original: ProxyNodeEntity?,
        name: String,
        address: String,
        portText: String,
        key: String,
        autoTestIntervalText: String
    ) {
        val port = portText.trim().toIntOrNull()
        val autoTestIntervalSeconds = autoTestIntervalText.trim().toIntOrNull()
        if (name.trim().isBlank() || address.trim().isBlank() || key.trim().isBlank() || port !in 1..65535) {
            emitMessage("请完整填写代理名称、地址、端口和 KEY")
            return
        }
        if (autoTestIntervalSeconds == null ||
            autoTestIntervalSeconds < ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS
        ) {
            emitMessage("自动测试间隔不能少于 ${ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS} 秒")
            return
        }
        val testedDraft = _proxyNodeDraftConnection.value
        viewModelScope.launch(Dispatchers.IO) {
            val validPort = port ?: 14250
            val node = (original ?: ProxyNodeEntity(name = "", address = "", key = "")).copy(
                name = name.trim(),
                address = address.trim().trimEnd('/'),
                port = validPort,
                key = key.trim(),
                autoTestIntervalSeconds = autoTestIntervalSeconds
            )
            val savedId = if (node.id == 0L) proxyNodeDao.insert(node) else {
                proxyNodeDao.update(node)
                node.id
            }
            if (testedDraft.isSuccess && testedDraft.testedFingerprint == proxyNodeFingerprint(node)) {
                _proxyNodeConnections.value = _proxyNodeConnections.value +
                    (savedId to testedDraft.copy(label = "连接成功", isTesting = false))
            } else {
                invalidateProxyNodeTest(savedId)
            }
            _proxyNodeDraftConnection.value = ProxyConnectionState()
            _messages.emit("代理节点已保存")
        }
    }

    fun deleteProxyNode(node: ProxyNodeEntity) {
        viewModelScope.launch {
            proxyNodeMonitors.remove(node.id)?.job?.cancel()
            invalidateProxyNodeTest(node.id)
            withContext(Dispatchers.IO) { proxyNodeDao.delete(node) }
            proxyNodeTestMutexes.remove(node.id)
            _messages.emit("代理节点已删除")
        }
    }

    fun toggleProxyNode(node: ProxyNodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = node.copy(enabled = !node.enabled)
            proxyNodeDao.update(updated)
            invalidateProxyNodeTest(node.id)
            _messages.emit(if (updated.enabled) "代理节点已启用" else "代理节点已停用")
        }
    }

    /** Tests a saved node and exposes its status independently of the legacy global proxy. */
    fun testProxyNode(node: ProxyNodeEntity) {
        if (!node.enabled) {
            _proxyNodeConnections.value = _proxyNodeConnections.value +
                (node.id to ProxyConnectionState(label = "节点已停用"))
            emitMessage("请先启用代理节点")
            return
        }
        viewModelScope.launch {
            performProxyNodeTest(node, showResultMessage = true)
        }
    }

    fun clearProxyNodeDraftTest() {
        _proxyNodeDraftConnection.value = ProxyConnectionState()
    }

    fun testProxyNodeDraft(node: ProxyNodeEntity) {
        val fingerprint = proxyNodeFingerprint(node)
        _proxyNodeDraftConnection.value = ProxyConnectionState(
            label = "正在连接",
            isTesting = true,
            testedFingerprint = fingerprint
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProxyWakeClient.fetchHealth(proxySettingsForNode(node))
            }
            if (_proxyNodeDraftConnection.value.testedFingerprint != fingerprint) return@launch
            result.onSuccess {
                _proxyNodeDraftConnection.value = ProxyConnectionState(
                    label = "连接成功",
                    isSuccess = true,
                    cooldownSeconds = it.cooldownSeconds,
                    testedFingerprint = fingerprint
                )
                _messages.emit("代理连接成功")
            }.onFailure {
                val reason = it.message?.takeIf(String::isNotBlank) ?: "未知错误"
                _proxyNodeDraftConnection.value = ProxyConnectionState(
                    label = "连接失败：$reason",
                    testedFingerprint = fingerprint
                )
                _messages.emit("代理连接失败：$reason")
            }
        }
    }

    private fun reconcileProxyNodeMonitors(nodes: List<ProxyNodeEntity>) {
        val nodesById = nodes.associateBy { it.id }
        proxyNodeMonitors.keys.toList().forEach { nodeId ->
            val node = nodesById[nodeId]
            if (node == null || !node.enabled) {
                proxyNodeMonitors.remove(nodeId)?.job?.cancel()
                if (node == null) {
                    invalidateProxyNodeTest(nodeId)
                    proxyNodeTestMutexes.remove(nodeId)
                } else {
                    _proxyNodeConnections.value = _proxyNodeConnections.value +
                        (nodeId to ProxyConnectionState(label = "节点已停用"))
                }
            }
        }

        nodes.filter(ProxyNodeEntity::enabled).forEach { node ->
            val signature = proxyNodeMonitorSignature(node)
            val existing = proxyNodeMonitors[node.id]
            if (existing?.signature == signature && existing.job.isActive) return@forEach

            existing?.job?.cancel()
            val job = viewModelScope.launch {
                while (true) {
                    performProxyNodeTest(node, showResultMessage = false)
                    delay(node.autoTestIntervalSeconds * 1_000L)
                }
            }
            proxyNodeMonitors[node.id] = ProxyNodeMonitor(signature, job)
        }

        nodes.filterNot(ProxyNodeEntity::enabled).forEach { node ->
            _proxyNodeConnections.value = _proxyNodeConnections.value +
                (node.id to ProxyConnectionState(label = "节点已停用"))
        }
    }

    private suspend fun performProxyNodeTest(node: ProxyNodeEntity, showResultMessage: Boolean) {
        val mutex = proxyNodeTestMutexes.getOrPut(node.id) { Mutex() }
        mutex.withLock {
            _proxyNodeConnections.value = _proxyNodeConnections.value +
                (node.id to ProxyConnectionState(label = "正在连接", isTesting = true))
            val result = withContext(Dispatchers.IO) {
                ProxyWakeClient.fetchHealth(proxySettingsForNode(node))
            }
            val current = withContext(Dispatchers.IO) { proxyNodeDao.get(node.id) }
            if (current == null || !current.enabled || proxyNodeFingerprint(current) != proxyNodeFingerprint(node)) {
                return@withLock
            }

            result.onSuccess {
                _proxyNodeConnections.value = _proxyNodeConnections.value +
                    (node.id to ProxyConnectionState(
                        label = "连接成功",
                        isSuccess = true,
                        cooldownSeconds = it.cooldownSeconds,
                        testedFingerprint = proxyNodeFingerprint(node)
                    ))
                if (showResultMessage) _messages.emit("代理节点“${node.name}”连接成功")
            }.onFailure {
                val reason = it.message?.takeIf(String::isNotBlank) ?: "未知错误"
                _proxyNodeConnections.value = _proxyNodeConnections.value +
                    (node.id to ProxyConnectionState(label = "连接失败：$reason"))
                if (showResultMessage) _messages.emit("代理节点“${node.name}”连接失败：$reason")
            }
        }
    }

    fun androidProxySettings(): AndroidProxyServiceStatus = AndroidProxyServiceController.status(appContext)

    fun saveAndroidProxy(portText: String, key: String, enabled: Boolean) {
        val port = portText.trim().toIntOrNull()
        if (!enabled) {
            if (port != null && port in 1..65535 && key.trim().isNotBlank()) {
                AndroidProxyServiceController.saveConfig(appContext, port, key, enabled = false)
            }
            AndroidProxyServiceController.stop(appContext)
            emitMessage("本机代理服务已停止")
            return
        }
        if (port == null || port !in 1..65535 || key.trim().isBlank()) {
            emitMessage("请输入有效端口和代理 KEY")
            return
        }
        if (!AndroidProxyServiceController.saveConfig(appContext, port, key, enabled)) {
            emitMessage("本机代理配置无效")
            return
        }
        AndroidProxyServiceController.start(appContext, port, key)
        emitMessage("本机代理服务已启动")
    }

    fun importAndroidProxyConfig(content: String): AndroidProxyConfig? {
        val config = ProxyConfigTransfer.parse(content).getOrElse { error ->
            emitMessage("导入失败：${error.message ?: "配置格式无效"}")
            return null
        }
        val enabled = AndroidProxyServiceController.isEnabled(appContext)
        if (!AndroidProxyServiceController.saveConfig(appContext, config.port, config.key, enabled)) {
            emitMessage("导入失败：代理配置无效")
            return null
        }
        if (enabled) AndroidProxyServiceController.start(appContext, config.port, config.key)
        emitMessage(if (enabled) "配置已导入并重新启动本机代理" else "代理配置已导入")
        return config
    }

    fun exportAndroidProxyConfig(
        portText: String,
        key: String,
        format: ProxyConfigFormat
    ): String? {
        val port = portText.trim().toIntOrNull()
        val config = runCatching { AndroidProxyConfig(port ?: -1, key.trim()) }.getOrElse { error ->
            emitMessage("无法导出：${error.message ?: "代理配置无效"}")
            return null
        }
        return ProxyConfigTransfer.serialize(config, format)
    }

    fun reportAndroidProxyConfigExport(success: Boolean, reason: String? = null) {
        emitMessage(if (success) "代理配置已导出" else "导出失败：${reason ?: "无法写入文件"}")
    }

    fun reportAndroidProxyConfigImportFailure(reason: String) {
        emitMessage("导入失败：$reason")
    }

    fun exportAppConfig(): String? {
        return runCatching {
            val state = uiState.value
            val localConfig = AndroidProxyServiceController.loadConfig(appContext)
            AppConfigBackup.serialize(
                AppConfigSnapshot(
                    groups = state.groups,
                    devices = state.devices,
                    proxyNodes = state.proxyNodes,
                    tileBindings = state.tileBindings,
                    proxySettings = state.proxySettings,
                    androidProxy = localConfig?.let {
                        AndroidProxyBackup(
                            port = it.port,
                            key = it.key,
                            enabled = AndroidProxyServiceController.isEnabled(appContext)
                        )
                    }
                )
            )
        }.getOrElse { error ->
            emitMessage("导出失败：${error.message ?: "配置尚未准备完成"}")
            null
        }
    }

    fun importAppConfig(content: String, onImported: () -> Unit = {}) {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                AppConfigBackup.parse(content)
            }.getOrElse { error ->
                _messages.emit("导入失败：${error.message ?: "配置格式无效"}")
                return@launch
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = appContext.applicationContext as com.example.wolquicktile.WolQuickTileApp
                    app.database.withTransaction {
                        app.database.tileBindingDao().deleteAll()
                        app.database.deviceDao().deleteAll()
                        app.database.groupDao().deleteAll()
                        app.database.proxyNodeDao().deleteAll()
                        snapshot.groups.forEach { app.database.groupDao().insert(it) }
                        snapshot.proxyNodes.forEach { app.database.proxyNodeDao().insert(it) }
                        snapshot.devices.forEach { app.database.deviceDao().insert(it) }
                        snapshot.tileBindings.forEach { app.database.tileBindingDao().upsert(it) }
                    }
                    proxySettingsRepository.save(snapshot.proxySettings.serverUrl, snapshot.proxySettings.key)
                }
            }
            result.exceptionOrNull()?.let { error ->
                _messages.emit("导入失败：${error.message ?: "无法写入配置"}")
                return@launch
            }

            snapshot.androidProxy?.let { proxy ->
                AndroidProxyServiceController.saveConfig(appContext, proxy.port, proxy.key, proxy.enabled)
                if (proxy.enabled) AndroidProxyServiceController.start(appContext, proxy.port, proxy.key)
                else AndroidProxyServiceController.stop(appContext)
            } ?: AndroidProxyServiceController.clearConfig(appContext)

            proxyTestJob?.cancel()
            proxyNodeMonitors.values.forEach { it.job.cancel() }
            proxyNodeMonitors.clear()
            proxyNodeTestMutexes.clear()
            wakeInFlight.clear()
            _wakeCooldowns.value = emptyMap()
            _proxyConnection.value = ProxyConnectionState()
            _proxyNodeConnections.value = emptyMap()
            _proxyNodeDraftConnection.value = ProxyConnectionState()
            if (snapshot.proxySettings.isConfigured) {
                startProxyTest(snapshot.proxySettings, showResultMessage = false)
            }
            val boundTiles = snapshot.tileBindings.map { it.tileIndex }.toSet()
            for (tileIndex in 1..TileRegistry.MAX_TILES) {
                if (tileIndex in boundTiles) enableTileComponent(tileIndex)
                else disableTileComponent(tileIndex)
            }
            onImported()
            _messages.emit("应用配置已导入")
        }
    }

    fun reportAppConfigImportFailure(reason: String) {
        emitMessage("导入失败：$reason")
    }

    fun reportAppConfigExport(success: Boolean, reason: String? = null) {
        emitMessage(if (success) "应用配置已导出" else "导出失败：${reason ?: "无法写入文件"}")
    }

    fun deleteGroup(group: GroupEntity) {
        viewModelScope.launch {
            val deleted = repository.deleteGroup(group)
            if (deleted) {
                _messages.emit("分组已删除，设备已转移")
            } else {
                _messages.emit("至少保留一个分组")
            }
        }
    }

    fun reorderGroups(groups: List<GroupEntity>) {
        viewModelScope.launch {
            repository.updateGroupOrder(groups)
        }
    }

    fun wake(device: DeviceEntity) {
        val remaining = _wakeCooldowns.value[device.id] ?: 0
        if (remaining > 0) {
            emitMessage("请 ${remaining} 秒后再试")
            return
        }

        val proxySettings = proxySettingsRepository.getSettings()
        val selectedNode = device.proxyNodeId?.let { nodeId ->
            uiState.value.proxyNodes.firstOrNull { it.id == nodeId }
        }
        if (device.useProxyWake) {
            when {
                device.proxyNodeId != null && selectedNode == null -> {
                    emitMessage("所选代理节点不存在，请重新编辑设备")
                    return
                }
                selectedNode != null && !selectedNode.enabled -> {
                    emitMessage("所选代理节点已停用，请先启用")
                    return
                }
                selectedNode != null && !isProxyNodeReady(selectedNode) -> {
                    emitMessage("代理节点未通过连接测试，请先测试后再唤醒")
                    return
                }
                selectedNode == null && !canUseProxyWake() -> {
                    emitMessage(proxyUnavailableMessage())
                    return
                }
            }
        }

        if (!wakeInFlight.add(device.id)) {
            emitMessage("正在发送唤醒请求，请稍候")
            return
        }
        viewModelScope.launch {
            try {
                val node = withContext(Dispatchers.IO) {
                    device.proxyNodeId?.let { proxyNodeDao.get(it) }
                }
                if (device.proxyNodeId != null && node == null) {
                    _messages.emit("所选代理节点不存在，请重新编辑设备")
                    return@launch
                }
                if (node != null && !node.enabled) {
                    _messages.emit("所选代理节点已停用，请先启用")
                    return@launch
                }

                val cooldownSeconds = if (device.useProxyWake) {
                    val settings = node?.let(::proxySettingsForNode) ?: proxySettings
                    val health = withContext(Dispatchers.IO) {
                        ProxyWakeClient.fetchHealth(settings)
                    }.getOrElse { error ->
                        val reason = error.message?.takeIf { it.isNotBlank() } ?: "连接代理失败"
                        if (node != null) {
                            _proxyNodeConnections.value = _proxyNodeConnections.value +
                                (node.id to ProxyConnectionState(label = "连接失败：$reason"))
                        } else {
                            _proxyConnection.value = ProxyConnectionState(label = "连接失败：$reason")
                        }
                        _messages.emit(reason)
                        return@launch
                    }
                    if (node != null) {
                        _proxyNodeConnections.value = _proxyNodeConnections.value +
                            (node.id to ProxyConnectionState(
                                label = "连接成功",
                                isSuccess = true,
                                cooldownSeconds = health.cooldownSeconds,
                                testedFingerprint = proxyNodeFingerprint(node)
                            ))
                    } else {
                        _proxyConnection.value = ProxyConnectionState(
                            label = "连接成功",
                            isSuccess = true,
                            cooldownSeconds = health.cooldownSeconds
                        )
                    }
                    health.cooldownSeconds
                } else {
                    LOCAL_WAKE_COOLDOWN_SECONDS
                }
                startWakeCooldown(device.id, cooldownSeconds)

                val result = withContext(Dispatchers.IO) {
                    when {
                        node != null -> DeviceWakeDispatcher.wake(device, node)
                        else -> DeviceWakeDispatcher.wake(device, proxySettings)
                    }
                }
                _messages.emit(result.message)
            } finally {
                wakeInFlight.remove(device.id)
            }
        }
    }

    fun saveProxySettings(serverUrl: String, key: String) {
        val normalizedUrl = ProxySettingsRepository.normalizeServerUrl(serverUrl)
        val cleanKey = key.trim()
        val settings = ProxySettings(normalizedUrl, cleanKey)

        if (!settings.isConfigured) {
            emitMessage("请完整填写代理地址和 KEY")
            return
        }

        proxySettingsRepository.save(normalizedUrl, cleanKey)
        emitMessage("代理设置已保存，正在测试连接")
        startProxyTest(settings, showResultMessage = true)
    }

    fun clearProxySettings() {
        proxyTestJob?.cancel()
        proxySettingsRepository.save("", "")
        _proxyConnection.value = ProxyConnectionState()
        emitMessage("代理设置已清除")
    }

    fun testProxyConnection(serverUrl: String, key: String) {
        val normalizedUrl = ProxySettingsRepository.normalizeServerUrl(serverUrl)
        val cleanKey = key.trim()
        val settings = ProxySettings(normalizedUrl, cleanKey)
        if (!settings.isConfigured) {
            _proxyConnection.value = ProxyConnectionState()
            emitMessage("请填写代理地址和 KEY")
            return
        }

        proxySettingsRepository.save(normalizedUrl, cleanKey)
        startProxyTest(settings, showResultMessage = true)
    }

    fun createTile(device: DeviceEntity) {
        viewModelScope.launch {
            val tileIndex = repository.bindDeviceToFirstFreeTile(device.id)
            if (tileIndex == null) {
                _messages.emit("最多支持 ${TileRegistry.MAX_TILES} 个快捷磁贴")
                return@launch
            }
            enableTileComponent(tileIndex)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestSystemTile(tileIndex, device)
            } else {
                _messages.emit("已绑定磁贴 WOL ${tileIndex.toString().padStart(2, '0')}，请在控制中心手动添加")
            }
        }
    }

    fun createShortcut(device: DeviceEntity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            emitMessage("当前系统不支持桌面快捷方式")
            return
        }

        val manager = appContext.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            emitMessage("当前桌面不支持创建快捷方式")
            return
        }

        val shortcutIntent = Intent(appContext, WakeShortcutActivity::class.java).apply {
            action = WakeShortcutActivity.ACTION_WAKE_DEVICE
            putExtra(WakeShortcutActivity.EXTRA_DEVICE_ID, device.id)
        }
        val shortcut = ShortcutInfo.Builder(appContext, "wake-device-${device.id}")
            .setShortLabel(device.name)
            .setLongLabel("唤醒 ${device.name}")
            .setIcon(Icon.createWithResource(appContext, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()

        manager.requestPinShortcut(shortcut, null)
        emitMessage("已请求创建桌面快捷方式")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestSystemTile(tileIndex: Int, device: DeviceEntity) {
        val manager = appContext.getSystemService(StatusBarManager::class.java) ?: run {
            viewModelScope.launch { _messages.emit("系统服务不可用") }
            return
        }
        val component = TileRegistry.componentName(appContext, tileIndex)
        val icon = Icon.createWithResource(appContext, R.drawable.ic_tile)
        manager.requestAddTileService(
            component,
            device.name,
            icon,
            appContext.mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加控制中心磁贴"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "该磁贴已在控制中心"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "系统未添加磁贴"
                else -> "磁贴请求未完成：$result"
            }
            viewModelScope.launch { _messages.emit(message) }
        }
    }

    private fun requestTileRefresh(tileIndex: Int) {
        runCatching {
            TileService.requestListeningState(appContext, TileRegistry.componentName(appContext, tileIndex))
        }
    }

    private fun enableTileComponent(tileIndex: Int) {
        setTileComponentState(tileIndex, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        requestTileRefresh(tileIndex)
    }

    private fun disableTileComponent(tileIndex: Int) {
        setTileComponentState(tileIndex, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    private fun setTileComponentState(tileIndex: Int, state: Int) {
        runCatching {
            appContext.packageManager.setComponentEnabledSetting(
                TileRegistry.componentName(appContext, tileIndex),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun emitMessage(message: String) {
        viewModelScope.launch {
            _messages.emit(message)
        }
    }

    private fun startWakeCooldown(deviceId: Long, cooldownSeconds: Int) {
        if (cooldownSeconds <= 0) {
            _wakeCooldowns.value = _wakeCooldowns.value - deviceId
            return
        }
        viewModelScope.launch {
            for (remaining in cooldownSeconds downTo 1) {
                _wakeCooldowns.value = _wakeCooldowns.value + (deviceId to remaining)
                delay(1_000)
            }
            _wakeCooldowns.value = _wakeCooldowns.value - deviceId
        }
    }

    private fun canUseProxyWake(): Boolean {
        return proxySettingsRepository.getSettings().isConfigured && _proxyConnection.value.isSuccess
    }

    private fun isProxyNodeReady(node: ProxyNodeEntity): Boolean {
        val state = _proxyNodeConnections.value[node.id] ?: return false
        return node.enabled && state.isSuccess && state.testedFingerprint == proxyNodeFingerprint(node)
    }

    private fun proxySettingsForNode(node: ProxyNodeEntity): ProxySettings {
        val rawAddress = node.address.trim().trimEnd('/')
        val withScheme = if (rawAddress.contains("://")) rawAddress else "http://$rawAddress"
        val endpoint = runCatching {
            val uri = java.net.URI(withScheme)
            if (uri.port > 0) rawAddress else "$rawAddress:${node.port}"
        }.getOrElse { "$rawAddress:${node.port}" }
        return ProxySettings(ProxySettingsRepository.normalizeServerUrl(endpoint), node.key.trim())
    }

    private fun proxyNodeFingerprint(node: ProxyNodeEntity): String {
        return listOf(node.address.trim().trimEnd('/'), node.port, node.key.trim(), node.enabled)
            .joinToString("|")
    }

    private fun proxyNodeMonitorSignature(node: ProxyNodeEntity): String {
        return "${proxyNodeFingerprint(node)}|${node.autoTestIntervalSeconds}"
    }

    private fun invalidateProxyNodeTest(nodeId: Long) {
        if (nodeId == 0L) return
        _proxyNodeConnections.value = _proxyNodeConnections.value - nodeId
    }

    private fun startProxyTest(settings: ProxySettings, showResultMessage: Boolean) {
        proxyTestJob?.cancel()
        _proxyConnection.value = ProxyConnectionState(label = "正在连接", isTesting = true)
        proxyTestJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProxyWakeClient.fetchHealth(settings)
            }
            if (proxySettingsRepository.getSettings() != settings) return@launch

            result.onSuccess {
                _proxyConnection.value = ProxyConnectionState(
                    label = "连接成功",
                    isSuccess = true,
                    cooldownSeconds = it.cooldownSeconds
                )
                if (showResultMessage) _messages.emit("代理连接成功")
            }.onFailure {
                val reason = it.message?.takeIf(String::isNotBlank) ?: "未知错误"
                val label = "连接失败：$reason"
                _proxyConnection.value = ProxyConnectionState(label = label)
                if (showResultMessage) _messages.emit(label)
            }
        }
    }

    private fun proxyUnavailableMessage(): String {
        val settings = proxySettingsRepository.getSettings()
        return when {
            !settings.isConfigured -> "请先在设置中配置代理服务器"
            _proxyConnection.value.isTesting -> "代理服务器正在连接，请稍后再试"
            else -> "代理服务器连接失败，请先在设置中重新测试"
        }
    }

    private fun normalizeAddressForStorage(value: String): String? {
        if (value.isBlank()) return ""
        parseIpv4(value) ?: return null
        return value
    }

    private fun parseIpv4(value: String): List<Int>? {
        val parts = value.split(".")
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.all { it in 0..255 } }
    }
}

class WolViewModelFactory(
    private val repository: DeviceRepository,
    private val proxySettingsRepository: ProxySettingsRepository,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WolViewModel(repository, proxySettingsRepository, appContext) as T
    }
}
