@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.wolquicktile.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.entity.GroupEntity
import com.example.wolquicktile.data.entity.ProxyNodeEntity
import com.example.wolquicktile.service.TileRegistry
import com.example.wolquicktile.service.AndroidProxyServiceController
import com.example.wolquicktile.service.AndroidProxyServiceStatus
import com.example.wolquicktile.service.AndroidProxyConfig
import com.example.wolquicktile.service.ProxyConfigFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

private enum class MainScreen { Devices, Settings }

private val PanelShape = RoundedCornerShape(8.dp)
private const val PROJECT_URL = "https://github.com/sumy8023/Wol-On-Lan"

@Composable
fun WolApp(viewModel: WolViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(MainScreen.Devices) }
    var deviceEditor by remember { mutableStateOf<DeviceEntity?>(null) }
    var groupEditor by remember { mutableStateOf<GroupEntity?>(null) }
    var deleteDeviceTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var moveDeviceTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var addTileTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    val localContext = LocalContext.current
    var androidProxyStatus by remember { mutableStateOf(AndroidProxyServiceController.status(localContext)) }

    LaunchedEffect(androidProxyStatus.enabled, androidProxyStatus.port) {
        if (androidProxyStatus.enabled) {
            repeat(6) {
                delay(500)
                androidProxyStatus = AndroidProxyServiceController.status(localContext)
                if (androidProxyStatus.running || androidProxyStatus.lastError.isNotBlank()) return@LaunchedEffect
            }
        }
    }
    var proxyNodeEditor by remember { mutableStateOf<ProxyNodeEntity?>(null) }
    var deleteProxyNodeTarget by remember { mutableStateOf<ProxyNodeEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    BackHandler(enabled = screen == MainScreen.Settings) {
        screen = MainScreen.Devices
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                ) { snackbarData ->
                    AppSnackbar(message = snackbarData.visuals.message)
                }
            }
        },
        topBar = {
            WolTopBar(
                screen = screen,
                onSettings = { screen = MainScreen.Settings },
                onBack = { screen = MainScreen.Devices }
            )
        },
        floatingActionButton = {
            if (screen == MainScreen.Devices) {
                FloatingActionButton(
                    onClick = { deviceEditor = DeviceEntity(name = "", macAddress = "") },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加设备")
                }
            }
        }
    ) { padding ->
        when (screen) {
            MainScreen.Devices -> DevicesScreen(
                uiState = uiState,
                contentPadding = padding,
                onWake = viewModel::wake,
                onEdit = { deviceEditor = it },
                onDelete = { deleteDeviceTarget = it },
                onCreateTile = { addTileTarget = it },
                onCreateShortcut = viewModel::createShortcut,
                onMoveGroup = { moveDeviceTarget = it }
            )

            MainScreen.Settings -> SettingsScreen(
                uiState = uiState,
                contentPadding = padding,
                onAddProxyNode = {
                    viewModel.clearProxyNodeDraftTest()
                    proxyNodeEditor = ProxyNodeEntity(name = "", address = "", key = "")
                },
                onEditProxyNode = {
                    viewModel.clearProxyNodeDraftTest()
                    proxyNodeEditor = it
                },
                onDeleteProxyNode = { deleteProxyNodeTarget = it },
                onToggleProxyNode = viewModel::toggleProxyNode,
                onTestProxyNode = viewModel::testProxyNode,
                androidProxyStatus = androidProxyStatus,
                onSaveAndroidProxy = { port, key, enabled ->
                    viewModel.saveAndroidProxy(port, key, enabled)
                    androidProxyStatus = AndroidProxyServiceController.status(localContext)
                },
                onImportAndroidProxyConfig = { content ->
                    viewModel.importAndroidProxyConfig(content).also {
                        androidProxyStatus = AndroidProxyServiceController.status(localContext)
                    }
                },
                onImportFailed = viewModel::reportAndroidProxyConfigImportFailure,
                onExportAndroidProxyConfig = viewModel::exportAndroidProxyConfig,
                onExportFinished = viewModel::reportAndroidProxyConfigExport,
                onBuildAppConfigExport = viewModel::exportAppConfig,
                onImportAppConfig = { content ->
                    viewModel.importAppConfig(content) {
                        androidProxyStatus = AndroidProxyServiceController.status(localContext)
                    }
                },
                onAppConfigImportFailed = viewModel::reportAppConfigImportFailure,
                onAppConfigExportFinished = viewModel::reportAppConfigExport,
                onAddGroup = { groupEditor = GroupEntity(name = "") },
                onEditGroup = { groupEditor = it },
                onDeleteGroup = { deleteGroupTarget = it },
                onReorderGroups = viewModel::reorderGroups
            )
        }
    }

    deviceEditor?.let { existing ->
        DeviceEditorDialog(
            existing = existing,
            groups = uiState.groups,
            proxyNodes = uiState.proxyNodes,
            proxyNodeConnections = uiState.proxyNodeConnections,
            globalProxyReady = uiState.proxySettings.isConfigured && uiState.proxyConnection.isSuccess,
            onDismiss = { deviceEditor = null },
            onTestProxyNode = viewModel::testProxyNode,
            onSave = { name, mac, broadcast, port, groupId, useProxyWake, proxyAlsoLocalWake, proxyNodeId ->
                viewModel.saveDevice(
                    existing.takeIf { it.id != 0L },
                    name,
                    mac,
                    broadcast,
                    port,
                    groupId,
                    useProxyWake,
                    proxyAlsoLocalWake,
                    proxyNodeId
                ) {
                    deviceEditor = null
                }
            }
        )
    }

    proxyNodeEditor?.let { existing ->
        ProxyNodeEditorDialog(
            existing = existing,
            connection = uiState.proxyNodeDraftConnection,
            onDismiss = {
                viewModel.clearProxyNodeDraftTest()
                proxyNodeEditor = null
            },
            onTest = viewModel::testProxyNodeDraft,
            onSave = { name, address, port, key, autoTestInterval ->
                viewModel.saveProxyNode(
                    existing.takeIf { it.id != 0L },
                    name,
                    address,
                    port,
                    key,
                    autoTestInterval
                )
                proxyNodeEditor = null
            }
        )
    }

    deleteProxyNodeTarget?.let { node ->
        ConfirmDeleteDialog(
            title = "删除代理节点",
            message = "确定删除 ${node.name} 吗？已绑定该节点的设备将无法通过此节点唤醒。",
            confirmText = "删除",
            onDismiss = { deleteProxyNodeTarget = null },
            onConfirm = {
                viewModel.deleteProxyNode(node)
                deleteProxyNodeTarget = null
            }
        )
    }

    groupEditor?.let { existing ->
        GroupEditorDialog(
            existing = existing,
            onDismiss = { groupEditor = null },
            onSave = { name ->
                viewModel.saveGroup(existing.takeIf { it.id != 0L }, name)
                groupEditor = null
            }
        )
    }

    deleteDeviceTarget?.let { device ->
        ConfirmDeleteDialog(
            title = "删除设备",
            message = "确定删除 ${device.name} 吗？对应磁贴绑定也会一起清理。",
            confirmText = "删除",
            onDismiss = { deleteDeviceTarget = null },
            onConfirm = {
                viewModel.deleteDevice(device)
                deleteDeviceTarget = null
            }
        )
    }

    deleteGroupTarget?.let { group ->
        ConfirmDeleteDialog(
            title = "删除分组",
            message = "删除 ${group.name} 后，组内设备会自动移动到其他分组。至少会保留一个分组。",
            confirmText = "删除",
            onDismiss = { deleteGroupTarget = null },
            onConfirm = {
                viewModel.deleteGroup(group)
                deleteGroupTarget = null
            }
        )
    }

    moveDeviceTarget?.let { device ->
        MoveGroupDialog(
            device = device,
            groups = uiState.groups,
            onDismiss = { moveDeviceTarget = null },
            onSelect = {
                viewModel.moveDeviceToGroup(device, it)
                moveDeviceTarget = null
            }
        )
    }

    addTileTarget?.let { device ->
        val alreadyBound = uiState.tileBindings.any { it.deviceId == device.id }
        ConfirmAddTileDialog(
            device = device,
            currentCount = uiState.tileBindings.size,
            maxCount = TileRegistry.MAX_TILES,
            canAdd = alreadyBound || uiState.tileBindings.size < TileRegistry.MAX_TILES,
            onDismiss = { addTileTarget = null },
            onConfirm = {
                viewModel.createTile(device)
                addTileTarget = null
            }
        )
    }
}

@Composable
private fun AppSnackbar(message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.widthIn(max = 360.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun WolTopBar(
    screen: MainScreen,
    onSettings: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (screen) {
                MainScreen.Devices -> {
                    IconButton(
                        onClick = onSettings,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WOL 网络唤醒",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "局域网设备与快捷唤醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                MainScreen.Settings -> {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    uiState: WolUiState,
    contentPadding: PaddingValues,
    onWake: (DeviceEntity) -> Unit,
    onEdit: (DeviceEntity) -> Unit,
    onDelete: (DeviceEntity) -> Unit,
    onCreateTile: (DeviceEntity) -> Unit,
    onCreateShortcut: (DeviceEntity) -> Unit,
    onMoveGroup: (DeviceEntity) -> Unit
) {
    val groupedDevices = uiState.devices.groupBy { it.groupId }
    val proxyNodeNames = uiState.proxyNodes.associate { it.id to it.name }
    var collapsedGroupKeys by rememberSaveable { mutableStateOf(emptyList<Long>()) }

    fun isExpanded(groupId: Long): Boolean = groupId !in collapsedGroupKeys
    fun toggleGroup(groupId: Long) {
        collapsedGroupKeys = if (groupId in collapsedGroupKeys) {
            collapsedGroupKeys - groupId
        } else {
            collapsedGroupKeys + groupId
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OverviewPanel(deviceCount = uiState.devices.size, groupCount = uiState.groups.size)
        }

        if (uiState.groups.isEmpty()) {
            item { EmptyState("正在准备默认分组") }
        } else {
            uiState.groups.forEach { group ->
                val devices = groupedDevices[group.id].orEmpty()
                item(key = "group-${group.id}") {
                    GroupSection(
                        title = group.name,
                        devices = devices,
                        proxyNodeNames = proxyNodeNames,
                        expanded = isExpanded(group.id),
                        wakeCooldowns = uiState.wakeCooldowns,
                        onToggle = { toggleGroup(group.id) },
                        onWake = onWake,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onCreateTile = onCreateTile,
                        onCreateShortcut = onCreateShortcut,
                        onMoveGroup = onMoveGroup
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewPanel(deviceCount: Int, groupCount: Int) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "快速唤醒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "应用、磁贴和桌面快捷方式共用设备配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill(label = "设备", value = deviceCount.toString(), modifier = Modifier.weight(1f))
                MetricPill(label = "分组", value = groupCount.toString(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupSection(
    title: String,
    devices: List<DeviceEntity>,
    proxyNodeNames: Map<Long, String>,
    expanded: Boolean,
    wakeCooldowns: Map<Long, Int>,
    onToggle: () -> Unit,
    onWake: (DeviceEntity) -> Unit,
    onEdit: (DeviceEntity) -> Unit,
    onDelete: (DeviceEntity) -> Unit,
    onCreateTile: (DeviceEntity) -> Unit,
    onCreateShortcut: (DeviceEntity) -> Unit,
    onMoveGroup: (DeviceEntity) -> Unit
) {
    val headerColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
        label = "groupHeaderColor"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = PanelShape,
            color = headerColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .clickable(onClick = onToggle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                CountBadge("${devices.size}")
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (devices.isEmpty()) {
                EmptyState("暂无设备")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { device ->
                        DeviceCard(
                            device = device,
                            proxyNodeName = device.proxyNodeId?.let(proxyNodeNames::get),
                            cooldownSeconds = wakeCooldowns[device.id] ?: 0,
                            onWake = onWake,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onCreateTile = onCreateTile,
                            onCreateShortcut = onCreateShortcut,
                            onMoveGroup = onMoveGroup
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountBadge(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DeviceCard(
    device: DeviceEntity,
    proxyNodeName: String?,
    cooldownSeconds: Int,
    onWake: (DeviceEntity) -> Unit,
    onEdit: (DeviceEntity) -> Unit,
    onDelete: (DeviceEntity) -> Unit,
    onCreateTile: (DeviceEntity) -> Unit,
    onCreateShortcut: (DeviceEntity) -> Unit,
    onMoveGroup: (DeviceEntity) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val broadcastLabel = displayAddress(device.broadcastAddress)
    val modeLabel = when {
        device.useProxyWake && device.proxyAlsoLocalWake -> "代理+局域网"
        device.useProxyWake -> "代理唤醒"
        else -> "局域网唤醒"
    }

    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            FilledIconButton(
                onClick = { onWake(device) },
                enabled = cooldownSeconds <= 0,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (cooldownSeconds > 0) {
                    Text("${cooldownSeconds}s", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = "立即唤醒")
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = deviceCardTitle(device.name, proxyNodeName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.macAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$modeLabel · $broadcastLabel · UDP ${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "设备操作")
                }
                StyledDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    StyledDropdownMenuItem(
                        text = "编辑",
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit(device)
                        }
                    )
                    StyledDropdownMenuItem(
                        text = "删除",
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        danger = true,
                        onClick = {
                            menuExpanded = false
                            onDelete(device)
                        }
                    )
                    StyledDropdownMenuItem(
                        text = "添加磁贴",
                        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCreateTile(device)
                        }
                    )
                    StyledDropdownMenuItem(
                        text = "创建快捷方式",
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCreateShortcut(device)
                        }
                    )
                    StyledDropdownMenuItem(
                        text = "移动分组",
                        icon = { Icon(Icons.Default.Group, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMoveGroup(device)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = PanelShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            content()
        }
    }
}

@Composable
private fun StyledDropdownMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        danger -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (selected) 0.75f else 0.34f)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        },
        colors = MenuDefaults.itemColors(
            textColor = contentColor,
            leadingIconColor = contentColor
        ),
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(PanelShape)
            .background(containerColor)
    )
}

@Composable
private fun SettingsScreen(
    uiState: WolUiState,
    contentPadding: PaddingValues,
    onAddProxyNode: () -> Unit,
    onEditProxyNode: (ProxyNodeEntity) -> Unit,
    onDeleteProxyNode: (ProxyNodeEntity) -> Unit,
    onToggleProxyNode: (ProxyNodeEntity) -> Unit,
    onTestProxyNode: (ProxyNodeEntity) -> Unit,
    androidProxyStatus: AndroidProxyServiceStatus,
    onSaveAndroidProxy: (String, String, Boolean) -> Unit,
    onImportAndroidProxyConfig: (String) -> AndroidProxyConfig?,
    onImportFailed: (String) -> Unit,
    onExportAndroidProxyConfig: (String, String, ProxyConfigFormat) -> String?,
    onExportFinished: (Boolean, String?) -> Unit,
    onBuildAppConfigExport: () -> String?,
    onImportAppConfig: (String) -> Unit,
    onAppConfigImportFailed: (String) -> Unit,
    onAppConfigExportFinished: (Boolean, String?) -> Unit,
    onAddGroup: () -> Unit,
    onEditGroup: (GroupEntity) -> Unit,
    onDeleteGroup: (GroupEntity) -> Unit,
    onReorderGroups: (List<GroupEntity>) -> Unit
) {
    val groupCounts = uiState.devices.groupingBy { it.groupId }.eachCount()
    var groupsExpanded by rememberSaveable { mutableStateOf(false) }
    var proxyNodesExpanded by rememberSaveable { mutableStateOf(false) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ProxyNodesPanel(
                nodes = uiState.proxyNodes,
                connections = uiState.proxyNodeConnections,
                expanded = proxyNodesExpanded,
                onToggleExpanded = { proxyNodesExpanded = !proxyNodesExpanded },
                onAdd = onAddProxyNode,
                onEdit = onEditProxyNode,
                onDelete = onDeleteProxyNode,
                onToggleNode = onToggleProxyNode,
                onTest = onTestProxyNode,
            )
        }

        item {
            SettingsPanel(
                title = "分组管理",
                subtitle = "${uiState.groups.size} 个分组",
                icon = { Icon(Icons.Default.Group, contentDescription = null) },
                expanded = groupsExpanded,
                onToggle = { groupsExpanded = !groupsExpanded },
                trailing = {
                    IconButton(onClick = onAddGroup) {
                        Icon(Icons.Default.Add, contentDescription = "新增分组")
                    }
                }
            ) {
                if (uiState.groups.isEmpty()) {
                    EmptyState("正在准备默认分组")
                } else {
                    ReorderableGroupList(
                        groups = uiState.groups,
                        groupCounts = groupCounts,
                        canDelete = uiState.groups.size > 1,
                        onEditGroup = onEditGroup,
                        onDeleteGroup = onDeleteGroup,
                        onReorderGroups = onReorderGroups
                    )
                }
            }
        }

        item {
            AndroidProxyPanel(
                status = androidProxyStatus,
                onSave = onSaveAndroidProxy,
                onImportConfig = onImportAndroidProxyConfig,
                onImportFailed = onImportFailed,
                onBuildExport = onExportAndroidProxyConfig,
                onExportFinished = onExportFinished
            )
        }

        item {
            AppConfigBackupPanel(
                onBuildExport = onBuildAppConfigExport,
                onImport = onImportAppConfig,
                onImportFailed = onAppConfigImportFailed,
                onExportFinished = onAppConfigExportFinished
            )
        }

        item {
            SettingsPanel(
                title = "关于应用",
                subtitle = "作者与软件介绍",
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                expanded = aboutExpanded,
                onToggle = { aboutExpanded = !aboutExpanded }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("软件作者：Sumy", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "软件介绍：保存局域网设备信息，并通过应用、桌面快捷方式或控制中心磁贴发送网络唤醒包。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("项目地址", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(0.85f),
                            onClick = { uriHandler.openUri(PROJECT_URL) }
                        ) {
                            Text(PROJECT_URL, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(PROJECT_URL))
                                Toast.makeText(context, "项目地址已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制项目地址")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyNodesPanel(
    nodes: List<ProxyNodeEntity>,
    connections: Map<Long, ProxyConnectionState>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ProxyNodeEntity) -> Unit,
    onDelete: (ProxyNodeEntity) -> Unit,
    onToggleNode: (ProxyNodeEntity) -> Unit,
    onTest: (ProxyNodeEntity) -> Unit,
) {
    SettingsPanel(
        title = "代理节点",
        subtitle = if (nodes.isEmpty()) "还没有代理节点" else "${nodes.size} 个节点，设备可分别绑定",
        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
        expanded = expanded,
        onToggle = onToggleExpanded,
        trailing = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "新增代理节点")
            }
        }
    ) {
        if (nodes.isEmpty()) {
            EmptyState("点击右侧加号新增代理节点")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                nodes.forEach { node ->
                    ProxyNodeRow(
                        node = node,
                        connection = connections[node.id] ?: ProxyConnectionState(),
                        onEdit = { onEdit(node) },
                        onDelete = { onDelete(node) },
                        onToggle = { onToggleNode(node) },
                        onTest = { onTest(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(
    node: ProxyNodeEntity,
    connection: ProxyConnectionState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
) {
    val statusColor = proxyConnectionStatusColor(connection, enabled = node.enabled)
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        node.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${node.address}:${node.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (node.enabled) "启用" else "停用",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (node.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(checked = node.enabled, onCheckedChange = { onToggle() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "KEY ****${node.key.takeLast(4)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    connection.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onTest, enabled = node.enabled && !connection.isTesting) {
                    Text(if (connection.isTesting) "测试中" else "测试连接")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑代理节点")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除代理节点")
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeEditorDialog(
    existing: ProxyNodeEntity,
    connection: ProxyConnectionState,
    onDismiss: () -> Unit,
    onTest: (ProxyNodeEntity) -> Unit,
    onSave: (
        name: String,
        address: String,
        port: String,
        key: String,
        autoTestInterval: String
    ) -> Unit
) {
    val isNew = existing.id == 0L
    var name by rememberSaveable(existing.id) { mutableStateOf(existing.name) }
    var address by rememberSaveable(existing.id) { mutableStateOf(existing.address) }
    var port by rememberSaveable(existing.id) { mutableStateOf(existing.port.toString()) }
    var key by rememberSaveable(existing.id) { mutableStateOf(existing.key) }
    var autoTestInterval by rememberSaveable(existing.id) {
        mutableStateOf(existing.autoTestIntervalSeconds.toString())
    }
    val nameError = if (name.trim().isBlank()) "请输入代理名称" else null
    val addressError = if (address.trim().isBlank()) "请输入代理地址" else null
    val portError = validatePortOptional(port)?.replace("UDP 端口", "代理端口")
    val keyError = if (key.trim().isBlank()) "KEY 不能为空" else null
    val autoTestIntervalError = when {
        autoTestInterval.isBlank() -> "请输入自动测试间隔"
        autoTestInterval.toIntOrNull() == null -> "自动测试间隔必须是整数"
        autoTestInterval.toInt() < ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS ->
            "自动测试间隔不能少于 ${ProxyNodeEntity.MIN_AUTO_TEST_INTERVAL_SECONDS} 秒"
        else -> null
    }
    val canSave = listOf(nameError, addressError, portError, keyError, autoTestIntervalError)
        .all { it == null }
    val canTest = addressError == null && portError == null && port.isNotBlank() && keyError == null
    val draftNode = existing.copy(
        name = name,
        address = address,
        port = port.toIntOrNull() ?: existing.port,
        key = key,
        autoTestIntervalSeconds = autoTestInterval.toIntOrNull()
            ?: ProxyNodeEntity.DEFAULT_AUTO_TEST_INTERVAL_SECONDS
    )
    val draftFingerprint = listOf(
        address.trim().trimEnd('/'),
        draftNode.port,
        key.trim(),
        existing.enabled
    ).joinToString("|")
    val visibleConnection = connection.takeIf { it.testedFingerprint == draftFingerprint }
        ?: ProxyConnectionState()

    ModalShell(
        title = if (isNew) "新增代理节点" else "编辑代理节点",
        subtitle = "设备启用代理唤醒时绑定一个节点",
        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            TextButton(
                onClick = { onTest(draftNode) },
                enabled = canTest && !visibleConnection.isTesting
            ) {
                Text(if (visibleConnection.isTesting) "测试中" else "测试连接")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = { onSave(name, address, port, key, autoTestInterval) },
                enabled = canSave
            ) { Text("保存") }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            LabeledTextField("代理名称", name, { name = it }, error = nameError)
            LabeledTextField("代理地址", address, { address = it }, error = addressError)
            LabeledTextField(
                "端口",
                port,
                { port = it.filter(Char::isDigit) },
                keyboardType = KeyboardType.Number,
                error = portError
            )
            LabeledTextField("连接 KEY", key, { key = it }, error = keyError)
            LabeledTextField(
                "自动测试间隔（秒）",
                autoTestInterval,
                { autoTestInterval = it.filter(Char::isDigit) },
                keyboardType = KeyboardType.Number,
                error = autoTestIntervalError
            )
            if (visibleConnection.label != "未配置") {
                Text(
                    visibleConnection.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = proxyConnectionStatusColor(
                        visibleConnection,
                        enabled = draftNode.enabled
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun proxyConnectionStatusColor(
    connection: ProxyConnectionState,
    enabled: Boolean
): Color = when {
    !enabled || connection.isTesting || connection.label == "未配置" ||
        connection.label == "节点已停用" ->
        MaterialTheme.colorScheme.onSurfaceVariant
    connection.isSuccess -> Color(0xFF18864B)
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun AppConfigBackupPanel(
    onBuildExport: () -> String?,
    onImport: (String) -> Unit,
    onImportFailed: (String) -> Unit,
    onExportFinished: (Boolean, String?) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
            require(bytes.size <= 2 * 1024 * 1024) { "配置文件不能超过 2 MB" }
            String(bytes, Charsets.UTF_8)
        }
        result.onSuccess(onImport).onFailure { error ->
            onImportFailed(error.message ?: "无法读取文件")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            pendingExport = null
            return@rememberLauncherForActivityResult
        }
        val content = pendingExport
        if (content == null) {
            onExportFinished(false, "没有可导出的配置")
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }
        pendingExport = null
        onExportFinished(result.isSuccess, result.exceptionOrNull()?.message)
    }

    SettingsPanel(
        title = "应用配置",
        subtitle = "设备、分组与代理设置",
        icon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("导入配置")
            }
            Button(
                onClick = {
                    onBuildExport()?.let { content ->
                        pendingExport = content
                        exportLauncher.launch("wol-android-config.json")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("导出配置")
            }
        }
    }
}

@Composable
private fun AndroidProxyPanel(
    status: AndroidProxyServiceStatus,
    onSave: (String, String, Boolean) -> Unit,
    onImportConfig: (String) -> AndroidProxyConfig?,
    onImportFailed: (String) -> Unit,
    onBuildExport: (String, String, ProxyConfigFormat) -> String?,
    onExportFinished: (Boolean, String?) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var port by rememberSaveable(status.port) { mutableStateOf(status.port.toString()) }
    var key by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable(status.enabled) { mutableStateOf(status.enabled) }
    var showOptimizationChooser by remember { mutableStateOf(false) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    val appContext = LocalContext.current
    val storedConfig = remember { AndroidProxyServiceController.loadConfig(appContext) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取文件")
        }.getOrElse { error ->
            onImportFailed("无法读取文件：${error.message ?: "未知错误"}")
            return@rememberLauncherForActivityResult
        }
        onImportConfig(content)?.let { imported ->
            port = imported.port.toString()
            key = imported.key
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            pendingExportContent = null
            return@rememberLauncherForActivityResult
        }
        val content = pendingExportContent
        if (content == null) {
            onExportFinished(false, "没有可导出的配置")
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(content)
            } ?: error("无法写入文件")
        }
        pendingExportContent = null
        onExportFinished(result.isSuccess, result.exceptionOrNull()?.message)
    }

    fun launchExport(format: ProxyConfigFormat) {
        val content = onBuildExport(port, key, format) ?: return
        pendingExportContent = content
        val fileName = when (format) {
            ProxyConfigFormat.YAML -> "wol-config.yml"
            ProxyConfigFormat.JSON -> "wol-config.json"
        }
        exportLauncher.launch(fileName)
    }
    LaunchedEffect(storedConfig) {
        if (key.isBlank()) key = storedConfig?.key.orEmpty()
        if (port.isBlank()) port = (storedConfig?.port ?: 14250).toString()
    }
    SettingsPanel(
        title = "本机代理",
        subtitle = when {
            status.running -> "运行中 · 端口 ${status.port}"
            status.lastError.isNotBlank() -> "启动失败：${status.lastError}"
            status.enabled -> "已开启，等待服务启动"
            else -> "未开启"
        },
        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            "开启后本机可作为代理节点，同时保留原有设备管理和局域网唤醒功能。Android 可能需要允许后台运行、关闭电池优化并开启自启动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LabeledTextField(
            "本机代理端口",
            port,
            { port = it.filter(Char::isDigit) },
            KeyboardType.Number,
            validatePortOptional(port)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LabeledTextField("本机代理Key", key, { key = it })
            }
            TextButton(onClick = { key = AndroidProxyServiceController.generateKey() }) {
                Text("随机生成")
            }
        }
        SwitchOptionRow(
            title = "开启本机代理服务",
            subtitle = if (status.running) "前台服务运行中，通知栏会常驻提示" else "关闭不会影响原有唤醒功能",
            checked = enabled,
            onCheckedChange = {
                onSave(port, key, it)
                val valid = port.toIntOrNull()?.let { value -> value in 1..65535 } == true && key.isNotBlank()
                if (!it || valid) {
                    enabled = it
                    if (it) showOptimizationChooser = true
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showOptimizationChooser = true }) { Text("后台优化") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/json", "application/x-yaml", "text/yaml", "text/plain")
                    )
                }
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("导入")
            }
            Box {
                TextButton(onClick = { exportMenuExpanded = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导出")
                }
                StyledDropdownMenu(
                    expanded = exportMenuExpanded,
                    onDismissRequest = { exportMenuExpanded = false }
                ) {
                    StyledDropdownMenuItem(
                        text = "YAML 配置",
                        icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            exportMenuExpanded = false
                            launchExport(ProxyConfigFormat.YAML)
                        }
                    )
                    StyledDropdownMenuItem(
                        text = "JSON 配置",
                        icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            exportMenuExpanded = false
                            launchExport(ProxyConfigFormat.JSON)
                        }
                    )
                }
            }
        }
        Button(
            onClick = { onSave(port, key, enabled) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
    if (showOptimizationChooser) {
        ModalShell(
            title = "后台优化",
            subtitle = "选择要打开的系统设置",
            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
            onDismiss = { showOptimizationChooser = false },
            actions = {
                TextButton(onClick = { showOptimizationChooser = false }) { Text("关闭") }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "请根据手机系统设置后台运行权限。前台常驻通知会降低代理服务被系统终止的概率。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    showOptimizationChooser = false
                    runCatching {
                        appContext.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("电池优化") }
                Button(onClick = {
                    showOptimizationChooser = false
                    runCatching {
                        appContext.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${appContext.packageName}")
                        })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("后台/自启动") }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .clickable(onClick = onToggle)
                    .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Box(modifier = Modifier.padding(9.dp)) {
                        icon()
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ReorderableGroupList(
    groups: List<GroupEntity>,
    groupCounts: Map<Long?, Int>,
    canDelete: Boolean,
    onEditGroup: (GroupEntity) -> Unit,
    onDeleteGroup: (GroupEntity) -> Unit,
    onReorderGroups: (List<GroupEntity>) -> Unit
) {
    var orderedGroups by remember(groups) { mutableStateOf(groups) }
    var draggingGroupId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val itemHeight = 64.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedGroups.forEach { group ->
            key(group.id) {
                val dragging = draggingGroupId == group.id
                val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "groupElevation")
                val scale by animateFloatAsState(if (dragging) 1.02f else 1f, label = "groupScale")

                Surface(
                    shape = PanelShape,
                    color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    shadowElevation = elevation,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .pointerInput(group.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingGroupId = group.id
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    if (draggingGroupId != null) {
                                        onReorderGroups(orderedGroups)
                                    }
                                    draggingGroupId = null
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    if (draggingGroupId != null) {
                                        onReorderGroups(orderedGroups)
                                    }
                                    draggingGroupId = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    var currentIndex = orderedGroups.indexOfFirst { it.id == group.id }
                                    while (dragOffset > itemHeightPx / 2 && currentIndex < orderedGroups.lastIndex) {
                                        orderedGroups = orderedGroups.moveItem(currentIndex, currentIndex + 1)
                                        dragOffset -= itemHeightPx
                                        currentIndex += 1
                                    }
                                    while (dragOffset < -itemHeightPx / 2 && currentIndex > 0) {
                                        orderedGroups = orderedGroups.moveItem(currentIndex, currentIndex - 1)
                                        dragOffset += itemHeightPx
                                        currentIndex -= 1
                                    }
                                }
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${groupCounts[group.id] ?: 0} 台设备",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEditGroup(group) }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑分组")
                        }
                        IconButton(onClick = { onDeleteGroup(group) }, enabled = canDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "删除分组")
                        }
                    }
                }
            }
        }
    }
}

internal fun deviceCardTitle(deviceName: String, proxyNodeName: String?): String {
    return proxyNodeName?.takeIf(String::isNotBlank)?.let { "$deviceName · $it" } ?: deviceName
}

@Composable
private fun DeviceEditorDialog(
    existing: DeviceEntity,
    groups: List<GroupEntity>,
    proxyNodes: List<ProxyNodeEntity>,
    proxyNodeConnections: Map<Long, ProxyConnectionState>,
    globalProxyReady: Boolean,
    onDismiss: () -> Unit,
    onTestProxyNode: (ProxyNodeEntity) -> Unit,
    onSave: (
        name: String,
        mac: String,
        broadcast: String,
        port: String,
        groupId: Long?,
        useProxyWake: Boolean,
        proxyAlsoLocalWake: Boolean,
        proxyNodeId: Long?
    ) -> Unit
) {
    val isNew = existing.id == 0L
    val firstGroupId = groups.firstOrNull()?.id
    var name by rememberSaveable(existing.id) { mutableStateOf(existing.name) }
    var mac by rememberSaveable(existing.id) { mutableStateOf(existing.macAddress) }
    var broadcast by rememberSaveable(existing.id) {
        mutableStateOf(if (isNew || existing.broadcastAddress == "255.255.255.255") "" else existing.broadcastAddress)
    }
    var port by rememberSaveable(existing.id) { mutableStateOf(if (isNew) "" else existing.port.toString()) }
    var groupId by rememberSaveable(existing.id) { mutableStateOf(existing.groupId ?: firstGroupId) }
    var useProxyWake by rememberSaveable(existing.id) { mutableStateOf(existing.useProxyWake) }
    var proxyAlsoLocalWake by rememberSaveable(existing.id) { mutableStateOf(existing.proxyAlsoLocalWake) }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    var proxyMenuExpanded by remember { mutableStateOf(false) }
    var proxyNodeId by rememberSaveable(existing.id) { mutableStateOf(existing.proxyNodeId) }
    val nameError = if (name.trim().isEmpty()) "请输入设备名称" else null
    val macError = validateMac(mac)
    val broadcastError = validateIpv4Optional(broadcast)
    val portError = validatePortOptional(port)
    val selectedProxyNode = proxyNodes.firstOrNull { it.id == proxyNodeId }
    val selectedProxyState = selectedProxyNode?.let { proxyNodeConnections[it.id] }
    val selectedProxyReady = selectedProxyNode != null &&
        selectedProxyNode.enabled && selectedProxyState?.isSuccess == true
    // Keep the old global endpoint usable for already-saved devices, while requiring
    // every newly-created proxy device to bind a concrete node.
    val legacyProxyReady = !isNew && existing.useProxyWake && existing.proxyNodeId == null && proxyNodeId == null && globalProxyReady
    val selectedProxyStatusLabel = when {
        selectedProxyNode == null && legacyProxyReady -> "连接成功"
        selectedProxyNode == null -> "未选择"
        !selectedProxyNode.enabled -> "节点停用"
        selectedProxyState?.isTesting == true -> "连接中"
        selectedProxyState?.isSuccess == true -> "连接成功"
        selectedProxyState?.label?.startsWith("连接失败") == true -> "连接失败"
        else -> "未测试"
    }
    val selectedProxyStatusColor = when {
        selectedProxyReady || (selectedProxyNode == null && legacyProxyReady) -> Color(0xFF18864B)
        selectedProxyState?.isTesting == true -> MaterialTheme.colorScheme.secondary
        selectedProxyState?.label?.startsWith("连接失败") == true -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val canSave = listOf(nameError, macError, broadcastError, portError).all { it == null } &&
        groupId != null && (!useProxyWake || selectedProxyReady || legacyProxyReady)

    LaunchedEffect(firstGroupId) {
        if (groupId == null && firstGroupId != null) {
            groupId = firstGroupId
        }
    }

    ModalShell(
        title = if (isNew) "添加设备" else "编辑设备",
        subtitle = if (isNew) "填写设备名称、MAC 和可选网络参数" else "修改设备配置后保存",
        icon = { Icon(if (isNew) Icons.Default.Add else Icons.Default.Edit, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            if (useProxyWake) {
                TextButton(
                    onClick = { selectedProxyNode?.let(onTestProxyNode) },
                    enabled = selectedProxyNode?.enabled == true && selectedProxyState?.isTesting != true
                ) {
                    Text(if (selectedProxyState?.isTesting == true) "测试中" else "测试连接")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                ),
                onClick = { onSave(name, mac, broadcast, port, groupId, useProxyWake, proxyAlsoLocalWake, proxyNodeId) }
            ) {
                Text("保存")
            }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LabeledTextField(
                title = "设备名称",
                value = name,
                onValueChange = { name = it },
                error = nameError
            )
            LabeledTextField(
                title = "MAC 地址",
                value = mac,
                onValueChange = { mac = it },
                error = macError
            )
            LabeledTextField(
                title = "IP地址/广播地址(可选)",
                value = broadcast,
                onValueChange = { broadcast = it },
                error = broadcastError
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LabeledTextField(
                        title = "UDP 端口(可选)",
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        keyboardType = KeyboardType.Number,
                        error = portError
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "设备分组",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Box {
                        Button(
                            onClick = { groupMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shape = PanelShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text(
                                groups.firstOrNull { it.id == groupId }?.name ?: "准备中",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                        StyledDropdownMenu(expanded = groupMenuExpanded, onDismissRequest = { groupMenuExpanded = false }) {
                            groups.forEach { group ->
                                StyledDropdownMenuItem(
                                    text = group.name,
                                    icon = {
                                        Icon(
                                            if (group.id == groupId) Icons.Default.Check else Icons.Default.Group,
                                            contentDescription = null
                                        )
                                    },
                                    selected = group.id == groupId,
                                    onClick = {
                                        groupId = group.id
                                        groupMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SwitchOptionRow(
                title = "通过代理唤醒",
                subtitle = when {
                    proxyNodes.any { it.enabled } -> "为此设备绑定一个已测试的代理节点"
                    globalProxyReady -> "未选择节点时使用旧版全局代理（兼容）"
                    else -> "请先在设置中新增并测试代理节点"
                },
                checked = useProxyWake,
                enabled = proxyNodes.any { it.enabled } || legacyProxyReady || useProxyWake,
                onCheckedChange = {
                    if (!it || proxyNodes.any { node -> node.enabled } || legacyProxyReady) {
                        useProxyWake = it
                        if (!it) {
                            proxyAlsoLocalWake = false
                            proxyNodeId = null
                        }
                    }
                }
            )

            AnimatedVisibility(
                visible = useProxyWake,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchOptionRow(
                        title = "同时通过局域网唤醒",
                        subtitle = "开启后会代理和本机局域网各发送一次唤醒包",
                        checked = proxyAlsoLocalWake,
                        enabled = true,
                        onCheckedChange = { proxyAlsoLocalWake = it }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { proxyMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = PanelShape,
                                border = BorderStroke(1.dp, if (selectedProxyReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.error.copy(alpha = 0.62f)),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text(
                                    selectedProxyNode?.name ?: if (legacyProxyReady) "旧版全局代理" else "选择代理节点",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                            }
                            StyledDropdownMenu(expanded = proxyMenuExpanded, onDismissRequest = { proxyMenuExpanded = false }) {
                                proxyNodes.filter { it.enabled }.forEach { node ->
                                    StyledDropdownMenuItem(
                                        text = node.name,
                                        icon = {
                                            Icon(
                                                if (node.id == proxyNodeId) Icons.Default.Check else Icons.Default.VpnKey,
                                                contentDescription = null
                                            )
                                        },
                                        selected = node.id == proxyNodeId,
                                        onClick = {
                                            proxyNodeId = node.id
                                            proxyMenuExpanded = false
                                        }
                                    )
                                }
                                if (globalProxyReady && !isNew && existing.useProxyWake && existing.proxyNodeId == null) {
                                    StyledDropdownMenuItem(
                                        text = "旧版全局代理（兼容）",
                                        icon = {
                                            Icon(
                                                if (proxyNodeId == null) Icons.Default.Check else Icons.Default.Settings,
                                                contentDescription = null
                                            )
                                        },
                                        selected = proxyNodeId == null,
                                        onClick = {
                                            proxyNodeId = null
                                            proxyMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            text = selectedProxyStatusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = selectedProxyStatusColor,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.widthIn(min = 64.dp, max = 88.dp)
                        )
                    }
                    if (selectedProxyNode == null && !legacyProxyReady) {
                        Text(
                            "请选择一个已启用的代理节点并测试连接后保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.56f }
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun LabeledTextField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null
) {
    val borderColor = if (error == null) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = borderColor,
            modifier = Modifier.padding(start = 4.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clip(PanelShape)
                        .border(1.dp, borderColor, PanelShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    innerTextField()
                }
            }
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun GroupEditorDialog(
    existing: GroupEntity,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit
) {
    var name by rememberSaveable(existing.id) { mutableStateOf(existing.name) }

    ModalShell(
        title = if (existing.id == 0L) "新增分组" else "编辑分组",
        subtitle = "分组用于归类设备，支持长按拖动排序",
        icon = { Icon(Icons.Default.Group, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onSave(name) }, enabled = name.trim().isNotEmpty()) { Text("保存") }
        }
    ) {
        LabeledTextField(
            title = "分组名称",
            value = name,
            onValueChange = { name = it }
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalShell(
        title = title,
        subtitle = message,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(confirmText)
            }
        }
    ) {}
}

@Composable
private fun ConfirmAddTileDialog(
    device: DeviceEntity,
    currentCount: Int,
    maxCount: Int,
    canAdd: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val message = "确定要添加磁贴吗？最大${maxCount}个，当前已添加${currentCount}个，添加后会在状态栏控制中心生成快捷入口。"

    ModalShell(
        title = "添加磁贴",
        subtitle = device.name,
        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = onConfirm, enabled = canAdd) {
                Text("确定添加")
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!canAdd) {
                Text(
                    text = "当前磁贴数量已达上限，请先删除设备或清理已有磁贴绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MoveGroupDialog(
    device: DeviceEntity,
    groups: List<GroupEntity>,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    ModalShell(
        title = "移动分组",
        subtitle = device.name,
        icon = { Icon(Icons.Default.Group, contentDescription = null) },
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (groups.isEmpty()) {
                EmptyState("正在准备默认分组")
            } else {
                groups.forEach { group ->
                    Surface(
                        shape = PanelShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PanelShape)
                            .clickable { onSelect(group.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "点击移动到该分组",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalShell(
    title: String,
    subtitle: String? = null,
    icon: (@Composable (() -> Unit))? = null,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = PanelShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Box(modifier = Modifier.padding(10.dp)) {
                                icon()
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                )
                content()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

private fun displayAddress(address: String): String {
    return if (address.isBlank() || address == "255.255.255.255") {
        "默认广播"
    } else {
        address
    }
}

private fun validateMac(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return "请输入 MAC 地址"
    val valid = Regex("^(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})|[0-9A-Fa-f]{12})$").matches(text)
    return if (valid) null else "MAC 格式应为 AA:BB:CC:DD:EE:FF 或 AABBCCDDEEFF"
}

private fun validateIpv4Optional(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val parts = text.split(".")
    val valid = parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
    }
    return if (valid) null else "IP地址/广播地址必须是 0-255 的四段 IPv4"
}

private fun validatePortOptional(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val port = text.toIntOrNull()
    return if (port != null && port in 1..65535) null else "UDP 端口范围应为 1-65535"
}

private fun List<GroupEntity>.moveItem(from: Int, to: Int): List<GroupEntity> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply {
        val item = removeAt(from)
        add(to, item)
    }
}
