package com.example.wolquicktile.watch.domain

import com.example.wolquicktile.watch.data.WatchDevice
import com.example.wolquicktile.watch.data.WatchProxyNode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

data class ProxyHealth(
    val version: String?,
    val cooldownSeconds: Int
)

/** Client for the signed /api/health and /api/wake endpoints. */
object ProxyWakeClient {
    private const val SERVICE_NAME = "WOL Proxy"
    const val LEGACY_COOLDOWN_SECONDS = 5

    fun testConnection(node: WatchProxyNode): Result<ProxyHealth> {
        if (!node.isConfigured || !node.enabled) {
            return Result.failure(IllegalStateException("请先配置可用的代理节点"))
        }
        return request(node, "GET", "/api/health", "")
            .mapCatching(::parseHealthResponse)
    }

    fun wake(node: WatchProxyNode, device: WatchDevice): Result<String> {
        if (!node.isConfigured || !node.enabled) {
            return Result.failure(IllegalStateException("代理节点未配置或已停用"))
        }
        val body = buildJsonBody(device)
        return request(node, "POST", "/api/wake", body).mapCatching { response ->
            if (extractJsonBoolean(response, "ok") != true) {
                throw IllegalStateException("代理服务未确认唤醒请求")
            }
            "代理已发送唤醒包"
        }
    }

    internal fun parseHealthResponse(response: String): ProxyHealth {
        if (extractJsonBoolean(response, "ok") != true ||
            extractJsonString(response, "name") != SERVICE_NAME
        ) {
            throw IllegalStateException("目标地址不是 WOL 代理服务")
        }
        val cooldown = extractJsonInt(response, "cooldown_seconds") ?: LEGACY_COOLDOWN_SECONDS
        if (cooldown !in 0..86_400) {
            throw IllegalStateException("代理返回的冷却时间无效")
        }
        return ProxyHealth(extractJsonString(response, "version"), cooldown)
    }

    private fun request(node: WatchProxyNode, method: String, path: String, body: String): Result<String> {
        return runCatching {
            val url = URL(endpoint(node) + path)
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString()
            val signature = sign(node.key, "$timestamp\n$nonce\n$body")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                useCaches = false
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
                    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
                val status = connection.responseCode
                val response = readResponse(connection, status)
                if (status !in 200..299) {
                    val error = extractJsonString(response, "error") ?: "http_$status"
                    val retryAfter = extractJsonInt(response, "retry_after")
                    throw IllegalStateException(errorMessage(error, retryAfter))
                }
                response
            } finally {
                connection.disconnect()
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(localizeFailure(it)) }
        )
    }

    internal fun endpoint(node: WatchProxyNode): String {
        val raw = node.address.trim().trimEnd('/')
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(withScheme) }
            .getOrElse { throw MalformedURLException("代理服务器地址格式不正确") }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw MalformedURLException("代理服务器地址仅支持 HTTP 或 HTTPS")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw MalformedURLException("代理服务器地址不支持查询参数或片段")
        }
        val hasPort = uri.port in 1..65535
        if (hasPort) return withScheme

        // Insert the configured port before a URL path/query so both
        // "host/path" and "https://host/path" remain valid endpoints.
        val match = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)?([^/?#]+)(.*)$").matchEntire(withScheme)
            ?: return "$withScheme:${node.port}"
        val scheme = match.groupValues[1]
        val authority = match.groupValues[2]
        val suffix = match.groupValues[3]
        return "$scheme$authority:${node.port}$suffix"
    }

    private fun buildJsonBody(device: WatchDevice): String = buildString {
        append('{')
        append("\"mac\":\"").append(jsonEscape(device.macAddress)).append("\",")
        append("\"address\":\"").append(jsonEscape(device.broadcastAddress)).append("\",")
        append("\"port\":").append(device.port)
        append('}')
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
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

    private fun errorMessage(error: String, retryAfter: Int?): String = when (error) {
        "bad_signature", "http_401" -> "代理认证失败，请检查 KEY"
        "timestamp_expired" -> "代理认证失败，请检查手表与服务器时间"
        "missing_signature_headers", "invalid_nonce", "invalid_timestamp", "nonce_replayed" -> "代理认证失败"
        "mac_not_allowed" -> "该 MAC 地址不在代理白名单中"
        "cooldown" -> "代理冷却中，请 ${retryAfter ?: LEGACY_COOLDOWN_SECONDS} 秒后再试"
        "invalid_mac" -> "代理拒绝：MAC 格式错误"
        "invalid_address" -> "代理拒绝：广播地址错误"
        "invalid_port" -> "代理拒绝：端口错误"
        "send_failed" -> "代理发送失败"
        "http_404" -> "代理接口不存在，请检查代理地址"
        else -> "代理请求失败：$error"
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
        val regex = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun extractJsonInt(json: String, name: String): Int? {
        val regex = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractJsonBoolean(json: String, name: String): Boolean? {
        val regex = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        return when (regex.find(json)?.groupValues?.getOrNull(1)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
