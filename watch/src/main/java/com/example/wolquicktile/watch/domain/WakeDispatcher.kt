package com.example.wolquicktile.watch.domain

import com.example.wolquicktile.watch.data.WatchDevice
import com.example.wolquicktile.watch.data.WatchProxyNode

data class WakeDispatchResult(
    val success: Boolean,
    val message: String
)

/** Chooses local UDP wake, proxy wake, or both according to the device entry. */
object WakeDispatcher {
    fun wake(device: WatchDevice, node: WatchProxyNode? = null): WakeDispatchResult {
        if (!device.useProxyWake) return wakeLocal(device)

        val results = mutableListOf<WakeDispatchResult>()
        if (node == null || !node.isConfigured || !node.enabled) {
            results += WakeDispatchResult(false, "请先配置并启用代理节点")
        } else {
            val proxyResult = ProxyWakeClient.wake(node, device)
            results += if (proxyResult.isSuccess) {
                WakeDispatchResult(true, "已通过代理发送唤醒包：${device.name}")
            } else {
                WakeDispatchResult(false, proxyResult.exceptionOrNull()?.message ?: "代理发送失败")
            }
        }

        if (device.proxyAlsoLocalWake) results += wakeLocal(device)

        val successCount = results.count { it.success }
        return when {
            successCount == results.size && device.proxyAlsoLocalWake ->
                WakeDispatchResult(true, "已发送代理和局域网唤醒包：${device.name}")
            successCount == results.size -> results.first()
            successCount > 0 -> WakeDispatchResult(true, "部分发送成功：${results.joinToString("；") { it.message }}")
            else -> WakeDispatchResult(false, results.joinToString("；") { it.message })
        }
    }

    private fun wakeLocal(device: WatchDevice): WakeDispatchResult = runCatching {
        WakeOnLanSender.wake(device)
    }.fold(
        onSuccess = { WakeDispatchResult(true, "已发送局域网唤醒包：${device.name}") },
        onFailure = { WakeDispatchResult(false, "局域网发送失败：${it.message ?: "未知错误"}") }
    )
}
