package com.example.wolquicktile.service

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.example.wolquicktile.WolQuickTileApp
import com.example.wolquicktile.domain.wol.DeviceWakeDispatcher
import com.example.wolquicktile.domain.wol.WakeDispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WakeShortcutActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, 0L)
        if (deviceId <= 0L) {
            showMessage("快捷方式无效")
            finish()
            return
        }

        scope.launch {
            val app = applicationContext as WolQuickTileApp
            val device = withContext(Dispatchers.IO) { app.repository.getDevice(deviceId) }
            if (device == null) {
                showMessage("设备不存在")
                finish()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                val node = device.proxyNodeId?.let { app.proxyNodeDao.get(it) }
                when {
                    device.proxyNodeId != null && node == null ->
                        WakeDispatchResult(false, "所选代理节点不存在，请重新编辑设备")
                    node != null && !node.enabled ->
                        WakeDispatchResult(false, "所选代理节点已停用，请先启用")
                    node != null -> DeviceWakeDispatcher.wake(device, node)
                    else -> DeviceWakeDispatcher.wake(device, app.proxySettingsRepository.getSettings())
                }
            }
            showMessage(result.message)
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_WAKE_DEVICE = "com.example.wolquicktile.action.WAKE_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
