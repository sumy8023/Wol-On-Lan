package com.example.wolquicktile.domain.proxy

import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.preferences.ProxySettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

data class ProxyHealth(
    val version: String?,
    val cooldownSeconds: Int
)

object ProxyWakeClient {
    private const val SERVICE_NAME = "WOL Proxy"
    const val LEGACY_COOLDOWN_SECONDS = 5

    fun testConnection(settings: ProxySettings): Result<String> {
        return fetchHealth(settings).map { "连接成功" }
    }

    /**
     * Reads runtime policy from the authenticated health endpoint. Older proxy
     * builds did not expose cooldown_seconds, so they retain the historical
     * five-second client cooldown.
     */
    fun fetchHealth(settings: ProxySettings): Result<ProxyHealth> {
        if (!settings.isConfigured) return Result.failure(IllegalStateException("请先填写代理地址和 KEY"))
        return request(settings, "GET", "/api/health", "")
            .mapCatching(::parseHealthResponse)
    }

    fun wake(settings: ProxySettings, device: DeviceEntity): Result<String> {
        if (!settings.isConfigured) return Result.failure(IllegalStateException("请先配置代理服务器"))
        val body = buildJsonBody(device)
        return request(settings, "POST", "/api/wake", body)
            .mapCatching { response ->
                if (extractJsonBoolean(response, "ok") != true) {
                    throw IllegalStateException("代理服务未确认唤醒请求")
                }
                "代理已发送"
            }
    }

    internal fun isExpectedHealthResponse(response: String): Boolean {
        return extractJsonBoolean(response, "ok") == true &&
            extractJsonString(response, "name") == SERVICE_NAME
    }

    internal fun parseHealthResponse(response: String): ProxyHealth {
        if (!isExpectedHealthResponse(response)) {
            throw IllegalStateException("目标地址不是 WOL 代理服务")
        }
        val cooldownSeconds = extractJsonInt(response, "cooldown_seconds")
            ?: LEGACY_COOLDOWN_SECONDS
        if (cooldownSeconds !in 0..86_400) {
            throw IllegalStateException("代理返回的冷却时间无效")
        }
        return ProxyHealth(
            version = extractJsonString(response, "version"),
            cooldownSeconds = cooldownSeconds
        )
    }

    private fun request(settings: ProxySettings, method: String, path: String, body: String): Result<String> {
        val result = runCatching {
            val url = URL(settings.serverUrl.trimEnd('/') + path)
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString()
            val signature = sign(settings.key, "$timestamp\n$nonce\n$body")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("X-WOL-Timestamp", timestamp)
                setRequestProperty("X-WOL-Nonce", nonce)
                setRequestProperty("X-WOL-Signature", signature)
                setRequestProperty("Accept", "application/json")
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            try {
                if (method == "POST") {
                    connection.outputStream.use { output ->
                        output.write(body.toByteArray(StandardCharsets.UTF_8))
                    }
                }

                val responseCode = connection.responseCode
                val responseText = readResponse(connection, responseCode)

                if (responseCode in 200..299) {
                    responseText
                } else {
                    val error = extractJsonString(responseText, "error") ?: "http_$responseCode"
                    val retryAfter = extractJsonInt(responseText, "retry_after")
                    throw IllegalStateException(errorMessage(error, retryAfter))
                }
            } finally {
                connection.disconnect()
            }
        }
        return result.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(localizeFailure(it)) }
        )
    }

    private fun buildJsonBody(device: DeviceEntity): String {
        return buildString {
            append("{")
            append("\"mac\":\"").append(jsonEscape(device.macAddress)).append("\",")
            append("\"address\":\"").append(jsonEscape(device.broadcastAddress)).append("\",")
            append("\"port\":").append(device.port)
            append("}")
        }
    }

    private fun readResponse(connection: HttpURLConnection, responseCode: Int): String {
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
        }
    }

    private fun sign(key: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun errorMessage(error: String, retryAfter: Int?): String {
        return when (error) {
            "bad_signature" -> "代理认证失败，请检查 KEY"
            "timestamp_expired" -> "代理认证失败，请检查手机与服务器时间"
            "missing_signature_headers", "invalid_nonce", "invalid_timestamp", "nonce_replayed" -> "代理认证失败"
            "mac_not_allowed" -> "该 MAC 地址不在代理白名单中"
            "cooldown" -> "代理冷却中，请 ${retryAfter ?: 5} 秒后再试"
            "invalid_mac" -> "代理拒绝：MAC 格式错误"
            "invalid_address" -> "代理拒绝：IP地址/广播地址错误"
            "invalid_port" -> "代理拒绝：端口错误"
            "send_failed" -> "代理发送失败"
            "http_401" -> "代理请求被拒绝，请检查 KEY"
            "http_404" -> "代理接口不存在，请检查代理地址"
            else -> "代理请求失败：$error"
        }
    }

    private fun localizeFailure(error: Throwable): Throwable {
        if (error is IllegalStateException) return error
        val message = when (error) {
            is SocketTimeoutException -> "连接代理服务器超时"
            is ConnectException -> "无法连接代理服务器"
            is UnknownHostException -> "无法解析代理服务器地址"
            is MalformedURLException -> "代理服务器地址格式不正确"
            is SSLException -> "代理服务器 HTTPS 连接失败"
            else -> "连接代理服务器失败${error.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
        }
        return IllegalStateException(message, error)
    }

    private fun extractJsonString(json: String, name: String): String? {
        val regex = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")
    }

    private fun extractJsonInt(json: String, name: String): Int? {
        val regex = Regex("\"${Regex.escape(name)}\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractJsonBoolean(json: String, name: String): Boolean? {
        val regex = Regex("\"${Regex.escape(name)}\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        return regex.find(json)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
