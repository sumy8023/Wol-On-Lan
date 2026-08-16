package com.example.wolquicktile.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.wolquicktile.R
import com.example.wolquicktile.WolQuickTileApp
import com.example.wolquicktile.domain.wol.DeviceWakeDispatcher
import com.example.wolquicktile.domain.wol.WakeDispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseWolTileService : TileService() {
    abstract val tileIndex: Int

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val device = withContext(Dispatchers.IO) { repository().repository.getTileDevice(tileIndex) }
            if (device == null) {
                showTileMessage("未绑定设备")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                val node = device.proxyNodeId?.let { repository().proxyNodeDao.get(it) }
                when {
                    device.proxyNodeId != null && node == null ->
                        WakeDispatchResult(false, "所选代理节点不存在，请重新编辑设备")
                    node != null && !node.enabled ->
                        WakeDispatchResult(false, "所选代理节点已停用，请先启用")
                    node != null -> DeviceWakeDispatcher.wake(device, node)
                    else -> DeviceWakeDispatcher.wake(
                        device,
                        repository().proxySettingsRepository.getSettings()
                    )
                }
            }
            showTileMessage(if (result.success) "已发送唤醒包" else result.message)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun repository() = applicationContext as WolQuickTileApp

    private fun updateTile() {
        scope.launch {
            val device = withContext(Dispatchers.IO) { repository().repository.getTileDevice(tileIndex) }
            val tile = qsTile ?: return@launch
            tile.state = Tile.STATE_INACTIVE
            tile.label = device?.name ?: "WOL ${tileIndex.toString().padStart(2, '0')}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = device?.let { displayWakeMode(it) } ?: "未绑定设备"
            }
            tile.icon = Icon.createWithResource(this@BaseWolTileService, R.drawable.ic_tile)
            tile.updateTile()
        }
    }

    private suspend fun showTileMessage(message: String) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = message
        } else {
            tile.label = message
        }
        tile.icon = Icon.createWithResource(this@BaseWolTileService, R.drawable.ic_tile)
        tile.updateTile()
        delay(3_000)
        updateTile()
    }

    private fun displayAddress(address: String): String {
        return if (address.isBlank() || address == "255.255.255.255") {
            "默认广播"
        } else {
            address
        }
    }

    private fun displayWakeMode(device: com.example.wolquicktile.data.entity.DeviceEntity): String {
        return when {
            device.useProxyWake && device.proxyAlsoLocalWake -> "代理+局域网"
            device.useProxyWake -> "代理唤醒"
            else -> "${displayAddress(device.broadcastAddress)}:${device.port}"
        }
    }
}
