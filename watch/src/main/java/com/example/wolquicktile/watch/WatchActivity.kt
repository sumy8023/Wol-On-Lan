package com.example.wolquicktile.watch

import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wolquicktile.watch.data.WatchDevice
import com.example.wolquicktile.watch.data.WatchProxyNode
import com.example.wolquicktile.watch.domain.ProxyWakeClient
import com.example.wolquicktile.watch.domain.WakeDispatcher
import com.example.wolquicktile.watch.ui.WatchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WatchPage {
    Devices,
    Proxies,
    DeviceEditor,
    ProxyEditor,
    About
}

private data class WatchMessage(
    val text: String = "",
    val success: Boolean = false
)

private const val LOCAL_WAKE_COOLDOWN_SECONDS = 3

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchTheme { WatchRoot() } }
    }
}

@Composable
private fun WatchRoot() {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as WatchApp).preferencesRepository
    }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(WatchPage.Devices) }
    var devices by remember { mutableStateOf(repository.getDevices()) }
    var proxyNodes by remember { mutableStateOf(repository.getProxyNodes()) }
    var selectedDeviceId by remember { mutableStateOf(repository.getSelectedDevice()?.id) }
    var selectedProxyId by remember { mutableStateOf(repository.getSelectedProxyNode()?.id) }
    var editingDevice by remember { mutableStateOf<WatchDevice?>(null) }
    var editingProxy by remember { mutableStateOf<WatchProxyNode?>(null) }
    var wakeDeviceId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf(WatchMessage()) }
    var cooldowns by remember {
        val currentTime = System.currentTimeMillis()
        mutableStateOf(
            repository.getDevices().mapNotNull { device ->
                repository.getWakeCooldownUntil(device.id)
                    .takeIf { it > currentTime }
                    ?.let { device.id to it }
            }.toMap()
        )
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var proxyStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(cooldowns) {
        while (cooldowns.values.any { it > System.currentTimeMillis() }) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    fun refreshConfig() {
        devices = repository.getDevices()
        proxyNodes = repository.getProxyNodes()
        selectedDeviceId = repository.getSelectedDevice()?.id
        selectedProxyId = repository.getSelectedProxyNode()?.id
    }

    fun saveDevice(device: WatchDevice) {
        repository.upsertDevice(device)
        refreshConfig()
        page = WatchPage.Devices
        message = WatchMessage("设备已保存", success = true)
    }

    fun deleteDevice(device: WatchDevice) {
        repository.removeDevice(device.id)
        refreshConfig()
        message = WatchMessage("设备已删除", success = true)
    }

    fun saveProxy(node: WatchProxyNode) {
        repository.upsertProxyNode(node)
        refreshConfig()
        page = WatchPage.Proxies
        message = WatchMessage("代理节点已保存", success = true)
    }

    fun deleteProxy(node: WatchProxyNode) {
        repository.removeProxyNode(node.id)
        devices.filter { it.proxyNodeId == node.id }.forEach { device ->
            repository.upsertDevice(device.copy(proxyNodeId = null, useProxyWake = false, proxyAlsoLocalWake = false))
        }
        refreshConfig()
        message = WatchMessage("代理节点已删除", success = true)
    }

    fun testProxy(node: WatchProxyNode) {
        proxyStatuses = proxyStatuses - node.id
        scope.launch {
            val result = withContext(Dispatchers.IO) { ProxyWakeClient.testConnection(node) }
            result.onSuccess {
                proxyStatuses = proxyStatuses + (node.id to "连接成功")
            }.onFailure { error ->
                val status = if (error.message?.contains("超时") == true) "连接超时" else "连接失败"
                proxyStatuses = proxyStatuses + (node.id to status)
            }
        }
    }

    fun wake(device: WatchDevice) {
        val cooldownUntil = maxOf(cooldowns[device.id] ?: 0L, repository.getWakeCooldownUntil(device.id))
        val remaining = (cooldownUntil - now + 999L).div(1_000L).toInt()
        if (remaining > 0) {
            message = WatchMessage("请 ${remaining} 秒后再试")
            return
        }
        if (wakeDeviceId != null) return
        val node = device.proxyNodeId?.let { id -> proxyNodes.firstOrNull { it.id == id } }
            ?: repository.getSelectedProxyNode()
        wakeDeviceId = device.id
        message = WatchMessage()
        scope.launch {
            val cooldownSeconds = if (device.useProxyWake && node?.isConfigured == true && node.enabled) {
                val health = withContext(Dispatchers.IO) { ProxyWakeClient.testConnection(node) }
                if (health.isFailure && !device.proxyAlsoLocalWake) {
                    wakeDeviceId = null
                    message = WatchMessage(health.exceptionOrNull()?.message ?: "连接代理服务器失败")
                    return@launch
                }
                health.getOrNull()?.cooldownSeconds ?: ProxyWakeClient.LEGACY_COOLDOWN_SECONDS
            } else {
                LOCAL_WAKE_COOLDOWN_SECONDS
            }
            val result = withContext(Dispatchers.IO) { WakeDispatcher.wake(device, node) }
            wakeDeviceId = null
            message = WatchMessage(result.message, result.success)
            if (result.success) {
                val cooldownUntil = System.currentTimeMillis() + cooldownSeconds * 1_000L
                cooldowns = cooldowns + (device.id to cooldownUntil)
                repository.setWakeCooldownUntil(device.id, cooldownUntil)
            }
            delay(3_500)
            if (message.text == result.message) message = WatchMessage()
        }
    }

    BackHandler(enabled = page != WatchPage.Devices) {
        page = when (page) {
            WatchPage.DeviceEditor, WatchPage.Proxies -> WatchPage.Devices
            WatchPage.ProxyEditor -> WatchPage.Proxies
            WatchPage.About -> WatchPage.Devices
            WatchPage.Devices -> WatchPage.Devices
        }
    }

    when (page) {
        WatchPage.Devices -> DeviceListPage(
            devices = devices,
            proxyNodes = proxyNodes,
            selectedDeviceId = selectedDeviceId,
            busyDeviceId = wakeDeviceId,
            cooldowns = cooldowns,
            now = now,
            message = message,
            onSelect = {
                selectedDeviceId = it.id
                repository.setSelectedDevice(it.id)
            },
            onWake = ::wake,
            onAdd = {
                editingDevice = WatchDevice(name = "", macAddress = "")
                page = WatchPage.DeviceEditor
            },
            onEdit = {
                editingDevice = it
                page = WatchPage.DeviceEditor
            },
            onDelete = ::deleteDevice,
            onProxy = { page = WatchPage.Proxies },
            onAbout = { page = WatchPage.About },
            onBack = { if (context is Activity) context.finish() }
        )

        WatchPage.Proxies -> ProxyListPage(
            nodes = proxyNodes,
            selectedId = selectedProxyId,
            statuses = proxyStatuses,
            onSelect = {
                selectedProxyId = it.id
                repository.setSelectedProxyNode(it.id)
                message = WatchMessage("已选择 ${it.name}", success = true)
            },
            onAdd = {
                editingProxy = WatchProxyNode(address = "", key = "")
                page = WatchPage.ProxyEditor
            },
            onEdit = {
                editingProxy = it
                page = WatchPage.ProxyEditor
            },
            onDelete = ::deleteProxy,
            onTest = ::testProxy,
            onBack = { page = WatchPage.Devices }
        )

        WatchPage.DeviceEditor -> editingDevice?.let { existing ->
            DeviceEditorPage(
                existing = existing,
                proxyNodes = proxyNodes,
                onBack = { page = WatchPage.Devices },
                onSave = ::saveDevice
            )
        }

        WatchPage.ProxyEditor -> editingProxy?.let { existing ->
            ProxyEditorPage(
                existing = existing,
                onBack = { page = WatchPage.Proxies },
                onTest = ::testProxy,
                onSave = ::saveProxy,
                testResult = proxyStatuses[existing.id]
            )
        }

        WatchPage.About -> AboutPage(onBack = { page = WatchPage.Devices })
    }
}

@Composable
private fun DeviceListPage(
    devices: List<WatchDevice>,
    proxyNodes: List<WatchProxyNode>,
    selectedDeviceId: String?,
    busyDeviceId: String?,
    cooldowns: Map<String, Long>,
    now: Long,
    message: WatchMessage,
    onSelect: (WatchDevice) -> Unit,
    onWake: (WatchDevice) -> Unit,
    onAdd: () -> Unit,
    onEdit: (WatchDevice) -> Unit,
    onDelete: (WatchDevice) -> Unit,
    onProxy: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit
) {
    WatchPageShell(
        title = "WOL",
        onBack = onBack,
        onTitleClick = onAbout,
        trailing = {
            WatchHeaderIconButton(onClick = onProxy) {
                Icon(Icons.Default.VpnKey, contentDescription = "代理节点")
            }
        }
    ) {
        if (devices.isEmpty()) {
            EmptyWatchState(
                title = "还没有设备",
                subtitle = "添加一台设备后即可唤醒",
                actionText = "添加设备",
                onAction = onAdd
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.id }) { device ->
                    val remaining = ((cooldowns[device.id] ?: 0L) - now + 999L).div(1_000L).toInt().coerceAtLeast(0)
                    WatchDeviceRow(
                        device = device,
                        proxyNode = device.proxyNodeId?.let { id -> proxyNodes.firstOrNull { it.id == id } },
                        selected = selectedDeviceId == device.id,
                        busy = busyDeviceId == device.id,
                        cooldownSeconds = remaining,
                        onSelect = { onSelect(device) },
                        onWake = { onWake(device) },
                        onEdit = { onEdit(device) },
                        onDelete = { onDelete(device) }
                    )
                }
            }
            if (message.text.isNotBlank()) {
                StatusText(message)
            }
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }
        }
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val projectUrl = "https://github.com/sumy8023/Wol-On-Lan"
    WatchPageShell(title = "关于软件", onBack = onBack, compactTitle = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.heightIn(min = 8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(58.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text("WOL唤醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("版本 1.0.2", style = MaterialTheme.typography.bodySmall)
                Text("开发者 Sumy", style = MaterialTheme.typography.bodySmall)
            }
            Text("项目地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "https://github.com/\nsumy8023/\nWol-On-Lan",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(projectUrl) },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                lineHeight = 13.sp
            )
            // Leave enough scroll range to move the URL into the watch's
            // circular center instead of clipping it at the lower edge.
            Spacer(Modifier.heightIn(min = 72.dp))
        }
    }
}

@Composable
private fun WatchDeviceRow(
    device: WatchDevice,
    proxyNode: WatchProxyNode?,
    selected: Boolean,
    busy: Boolean,
    cooldownSeconds: Int,
    onSelect: () -> Unit,
    onWake: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember(device.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                val mode = when {
                    device.useProxyWake && device.proxyAlsoLocalWake -> "代理 + 局域网"
                    device.useProxyWake -> "代理：${proxyNode?.name ?: "未配置"}"
                    else -> "局域网 · ${device.port}"
                }
                Text(mode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(
                onClick = onWake,
                enabled = !busy && cooldownSeconds == 0,
                modifier = Modifier.size(46.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (busy) {
                    Text("…", fontWeight = FontWeight.Bold)
                } else if (cooldownSeconds > 0) {
                    Text(cooldownSeconds.toString(), fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "唤醒")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyListPage(
    nodes: List<WatchProxyNode>,
    selectedId: String?,
    statuses: Map<String, String>,
    onSelect: (WatchProxyNode) -> Unit,
    onAdd: () -> Unit,
    onEdit: (WatchProxyNode) -> Unit,
    onDelete: (WatchProxyNode) -> Unit,
    onTest: (WatchProxyNode) -> Unit,
    onBack: () -> Unit
) {
    WatchPageShell(
        title = "代理节点",
        onBack = onBack,
        trailing = {
            WatchHeaderIconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "添加代理") }
        }
    ) {
        if (nodes.isEmpty()) {
            EmptyWatchState(
                title = "还没有代理",
                subtitle = "添加公网代理后可远程唤醒",
                actionText = "添加代理",
                onAction = onAdd
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(nodes, key = { it.id }) { node ->
                    ProxyNodeRow(
                        node = node,
                        selected = node.id == selectedId,
                        status = statuses[node.id],
                        onSelect = { onSelect(node) },
                        onEdit = { onEdit(node) },
                        onDelete = { onDelete(node) },
                        onTest = { onTest(node) }
                    )
                }
            }
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加代理节点")
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(
    node: WatchProxyNode,
    selected: Boolean,
    status: String?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    var menuOpen by remember(node.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    status ?: if (node.enabled) "未测试" else "已停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == "连接失败" || status == "连接超时") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onTest, enabled = node.enabled) { Icon(Icons.Default.Refresh, contentDescription = "测试连接") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceEditorPage(
    existing: WatchDevice,
    proxyNodes: List<WatchProxyNode>,
    onBack: () -> Unit,
    onSave: (WatchDevice) -> Unit
) {
    var name by remember(existing.id) { mutableStateOf(existing.name) }
    var mac by remember(existing.id) { mutableStateOf(existing.macAddress) }
    var address by remember(existing.id) { mutableStateOf(existing.broadcastAddress.takeUnless { it == "255.255.255.255" }.orEmpty()) }
    var port by remember(existing.id) { mutableStateOf(if (existing.id.isBlank()) "9" else existing.port.toString()) }
    var useProxy by remember(existing.id) { mutableStateOf(existing.useProxyWake) }
    var alsoLocal by remember(existing.id) { mutableStateOf(existing.proxyAlsoLocalWake) }
    var proxyNodeId by remember(existing.id) { mutableStateOf(existing.proxyNodeId ?: proxyNodes.firstOrNull()?.id) }
    var error by remember(existing.id) { mutableStateOf<String?>(null) }

    WatchPageShell(title = if (existing.name.isBlank()) "添加设备" else "编辑设备", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            WatchField("名称", name, { name = it }, KeyboardType.Text)
            WatchField("MAC", mac, { mac = it }, KeyboardType.Ascii)
            WatchField("广播地址（可选）", address, { address = it }, KeyboardType.Decimal)
            WatchField("端口", port, { port = it.filter(Char::isDigit) }, KeyboardType.Number)
            ToggleRow("通过代理唤醒", useProxy) { useProxy = it }
            if (useProxy) {
                ToggleRow("同时局域网唤醒", alsoLocal) { alsoLocal = it }
                ProxySelector(proxyNodes, proxyNodeId) { proxyNodeId = it }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.heightIn(min = 2.dp))
            val buildDevice: () -> WatchDevice? = {
                val cleanName = name.trim()
                val cleanMac = mac.trim()
                val cleanAddress = address.trim()
                val cleanPort = port.toIntOrNull()
                error = when {
                    cleanName.isBlank() -> "请输入设备名称"
                    !MAC_REGEX.matches(cleanMac) -> "MAC 地址格式不正确"
                    cleanAddress.isNotBlank() && !isIpv4(cleanAddress) -> "广播地址格式不正确"
                    cleanPort !in 1..65535 -> "端口范围应为 1-65535"
                    useProxy && proxyNodes.none { it.id == proxyNodeId && it.enabled && it.isConfigured } -> "请选择已启用的代理节点"
                    else -> null
                }
                if (error != null) null else existing.copy(
                    name = cleanName,
                    macAddress = cleanMac.uppercase(),
                    broadcastAddress = cleanAddress.ifBlank { "255.255.255.255" },
                    port = cleanPort ?: 9,
                    useProxyWake = useProxy,
                    proxyAlsoLocalWake = useProxy && alsoLocal,
                    proxyNodeId = if (useProxy) proxyNodeId else null
                )
            }
            Button(
                onClick = { buildDevice()?.let(onSave) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("保存") }
        }
    }
}

@Composable
private fun ProxyEditorPage(
    existing: WatchProxyNode,
    onBack: () -> Unit,
    onTest: (WatchProxyNode) -> Unit,
    onSave: (WatchProxyNode) -> Unit,
    testResult: String?
) {
    var name by remember(existing.id) { mutableStateOf(existing.name) }
    var address by remember(existing.id) { mutableStateOf(existing.address) }
    var port by remember(existing.id) { mutableStateOf(existing.port.toString()) }
    var key by remember(existing.id) { mutableStateOf(existing.key) }
    var enabled by remember(existing.id) { mutableStateOf(existing.enabled) }
    var showKey by rememberSaveable(existing.id) { mutableStateOf(false) }
    var error by remember(existing.id) { mutableStateOf<String?>(null) }
    val draft = existing.copy(name = name, address = address, port = port.toIntOrNull() ?: -1, key = key, enabled = enabled)

    WatchPageShell(title = if (existing.name.isBlank()) "添加代理" else "编辑代理", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            WatchField("名称", name, { name = it }, KeyboardType.Text)
            WatchField("地址", address, { address = it }, KeyboardType.Uri)
            WatchField("端口", port, { port = it.filter(Char::isDigit) }, KeyboardType.Number)
            WatchField(
                title = "KEY",
                value = key,
                onValueChange = { key = it },
                keyboardType = KeyboardType.Password,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.Close else Icons.Default.VpnKey, contentDescription = "显示 KEY")
                    }
                }
            )
            ToggleRow("启用节点", enabled) { enabled = it }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onTest(draft) }, enabled = draft.isConfigured && enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测试连接")
                }
                testResult?.let { result ->
                    Text(
                        result,
                        modifier = Modifier.weight(1f),
                        color = if (result == "连接成功") Color(0xFF16864B) else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val cleanPort = port.toIntOrNull()
                    error = when {
                        name.trim().isBlank() -> "请输入代理名称"
                        address.trim().isBlank() -> "请输入代理地址"
                        cleanPort !in 1..65535 -> "端口范围应为 1-65535"
                        key.trim().isBlank() -> "请输入代理 KEY"
                        else -> null
                    }
                    if (error == null) onSave(draft.copy(name = name.trim(), address = address.trim().trimEnd('/'), port = cleanPort ?: 14250, key = key.trim()))
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("保存") }
        }
    }
}

@Composable
private fun WatchPageShell(
    title: String,
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    compactTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        WatchHeaderIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Text(
                        title,
                        style = if (compactTitle) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (trailing != null) {
                        trailing()
                    } else if (onBack != null) {
                        Spacer(Modifier.size(40.dp))
                    }
                }
                content()
            }
        )
    }
}

@Composable
private fun WatchHeaderIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Material IconButton reserves 48dp, which is wider than the safe area of
    // a 1.5-inch circular display. Keep the hit target explicit and centered.
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun EmptyWatchState(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(62.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.heightIn(min = 10.dp))
        Button(onClick = onAction) { Text(actionText) }
    }
}

@Composable
private fun StatusText(message: WatchMessage) {
    Text(
        text = message.text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        color = if (message.success) Color(0xFF16864B) else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun WatchField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(title) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProxySelector(nodes: List<WatchProxyNode>, selectedId: String?, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = nodes.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("代理节点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "选择代理节点", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                nodes.filter { it.enabled && it.isConfigured }.forEach { node ->
                    DropdownMenuItem(
                        text = { Text(node.name) },
                        leadingIcon = { if (node.id == selectedId) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = { onSelected(node.id); expanded = false }
                    )
                }
            }
        }
    }
}

private val MAC_REGEX = Regex("^(([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})$")

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { it.isNotBlank() && it.toIntOrNull()?.let { number -> number in 0..255 } == true }
}
