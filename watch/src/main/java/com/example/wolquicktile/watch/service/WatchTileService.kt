package com.example.wolquicktile.watch.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.wolquicktile.watch.R
import com.example.wolquicktile.watch.WatchApp
import com.example.wolquicktile.watch.domain.ProxyWakeClient
import com.example.wolquicktile.watch.domain.WakeDispatchResult
import com.example.wolquicktile.watch.domain.WakeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One standard Android Quick Settings tile for the currently selected device. */
class WatchTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeInFlight = false
    private var serviceDestroyed = false

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
        if (wakeInFlight) return
        wakeInFlight = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val repository = (applicationContext as WatchApp).preferencesRepository
                    val device = repository.getSelectedDevice()
                    if (device == null) {
                        WakeDispatchResult(false, "未配置设备")
                    } else {
                        val now = System.currentTimeMillis()
                        val cooldownUntil = repository.getWakeCooldownUntil(device.id)
                        if (cooldownUntil > now) {
                            val remaining = ((cooldownUntil - now + 999L) / 1_000L).toInt()
                            WakeDispatchResult(false, "请 ${remaining} 秒后再试")
                        } else {
                            val node = if (device.proxyNodeId != null) {
                                repository.getProxyNodes().firstOrNull { it.id == device.proxyNodeId }
                            } else {
                                repository.getSelectedProxyNode()
                            }
                            val cooldownSeconds = if (device.useProxyWake && node?.isConfigured == true && node.enabled) {
                                val health = ProxyWakeClient.testConnection(node)
                                if (health.isFailure && !device.proxyAlsoLocalWake) {
                                    return@withContext WakeDispatchResult(
                                        false,
                                        health.exceptionOrNull()?.message ?: "连接代理服务器失败"
                                    )
                                }
                                health.getOrNull()?.cooldownSeconds
                                    ?: ProxyWakeClient.LEGACY_COOLDOWN_SECONDS
                            } else {
                                LOCAL_WAKE_COOLDOWN_SECONDS
                            }
                            val wakeResult = WakeDispatcher.wake(device, node)
                            if (wakeResult.success) {
                                repository.setWakeCooldownUntil(
                                    device.id,
                                    System.currentTimeMillis() + cooldownSeconds * 1_000L
                                )
                            }
                            wakeResult
                        }
                    }
                }
                showResult(result)
            } finally {
                wakeInFlight = false
                if (serviceDestroyed) scope.cancel()
            }
        }
    }

    override fun onDestroy() {
        serviceDestroyed = true
        if (!wakeInFlight) scope.cancel()
        super.onDestroy()
    }

    private fun updateTile() {
        val repository = (applicationContext as WatchApp).preferencesRepository
        val device = repository.getSelectedDevice()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = device?.name ?: getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (device == null) "未配置设备" else if (device.useProxyWake) "代理唤醒" else "局域网唤醒"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    private suspend fun showResult(result: WakeDispatchResult) {
        val tile = qsTile ?: return
        tile.state = if (result.success) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = result.message
        } else {
            tile.label = result.message
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
        delay(3_000)
        updateTile()
    }

    private companion object {
        const val LOCAL_WAKE_COOLDOWN_SECONDS = 3
    }
}
