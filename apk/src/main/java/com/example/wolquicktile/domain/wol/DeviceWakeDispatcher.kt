package com.example.wolquicktile.domain.wol

import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.preferences.ProxySettings
import com.example.wolquicktile.data.entity.ProxyNodeEntity
import com.example.wolquicktile.data.preferences.ProxySettingsRepository
import com.example.wolquicktile.domain.proxy.ProxyWakeClient

data class WakeDispatchResult(
    val success: Boolean,
    val message: String
)

object DeviceWakeDispatcher {
    fun wake(device: DeviceEntity, node: ProxyNodeEntity?): WakeDispatchResult {
        val settings = node?.let { proxyNode ->
            val raw = proxyNode.address.trim().trimEnd('/')
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val endpoint = runCatching {
                if (java.net.URI(withScheme).port > 0) raw else "$raw:${proxyNode.port}"
            }.getOrElse { "$raw:${proxyNode.port}" }
            ProxySettings(ProxySettingsRepository.normalizeServerUrl(endpoint), proxyNode.key)
        }
            ?: ProxySettings()
        return wake(device, settings)
    }
    fun wake(device: DeviceEntity, proxySettings: ProxySettings): WakeDispatchResult {
        if (!device.useProxyWake) {
            return wakeLocal(device)
        }

        val results = mutableListOf<WakeDispatchResult>()
        if (proxySettings.isConfigured) {
            val proxyResult = ProxyWakeClient.wake(proxySettings, device)
            results += if (proxyResult.isSuccess) {
                WakeDispatchResult(true, "代理已发送")
            } else {
                WakeDispatchResult(false, proxyResult.exceptionOrNull()?.message ?: "代理发送失败")
            }
        } else {
            results += WakeDispatchResult(false, "请先配置代理服务器")
        }

        if (device.proxyAlsoLocalWake) {
            results += wakeLocal(device)
        }

        val successes = results.filter { it.success }
        return when {
            successes.size == results.size && device.proxyAlsoLocalWake -> WakeDispatchResult(true, "已发送代理和局域网唤醒包：${device.name}")
            successes.size == results.size -> WakeDispatchResult(true, "已通过代理发送唤醒包：${device.name}")
            successes.isNotEmpty() -> WakeDispatchResult(true, "部分发送成功：${results.joinToString("；") { it.message }}")
            else -> WakeDispatchResult(false, results.joinToString("；") { it.message })
        }
    }

    private fun wakeLocal(device: DeviceEntity): WakeDispatchResult {
        return runCatching {
            WakeOnLanSender.wake(device)
        }.fold(
            onSuccess = { WakeDispatchResult(true, "已发送局域网唤醒包：${device.name}") },
            onFailure = { WakeDispatchResult(false, "局域网发送失败：${it.message ?: "未知错误"}") }
        )
    }
}
