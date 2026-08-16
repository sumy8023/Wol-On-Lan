package com.example.wolquicktile.service

import org.json.JSONObject

enum class ProxyConfigFormat {
    YAML,
    JSON
}

/**
 * Exchanges the Android proxy's portable fields with the desktop proxy config.
 * Desktop-only fields are accepted and ignored on import, and emitted with
 * conservative defaults on export so the resulting file is directly usable.
 */
object ProxyConfigTransfer {
    fun parse(text: String): Result<AndroidProxyConfig> = runCatching {
        val content = text.removePrefix("\uFEFF").trim()
        require(content.isNotBlank()) { "配置文件为空" }
        val values = if (content.startsWith("{")) parseJson(content) else parseYaml(content)
        val listen = values["listen"] ?: values["listen_port"] ?: values["port"]
            ?: throw IllegalArgumentException("配置中缺少 listen")
        val key = (values["key"] ?: values["listen_key"]).orEmpty().trim()
        require(key.isNotBlank()) { "配置中缺少 key" }
        AndroidProxyConfig(parseListenPort(listen), key)
    }

    fun serialize(config: AndroidProxyConfig, format: ProxyConfigFormat): String {
        return when (format) {
            ProxyConfigFormat.YAML -> toYaml(config)
            ProxyConfigFormat.JSON -> toJson(config)
        }
    }

    private fun parseJson(content: String): Map<String, String> {
        val root = JSONObject(content)
        val source = root.optJSONObject("config") ?: root
        return buildMap {
            listOf("listen", "listen_port", "port", "key", "listen_key").forEach { name ->
                if (!source.has(name) || source.isNull(name)) return@forEach
                put(name, source.get(name).toString())
            }
        }
    }

    private fun parseYaml(content: String): Map<String, String> {
        return buildMap {
            content.lineSequence().forEach { sourceLine ->
                val line = stripYamlComment(sourceLine).trim()
                if (line.isBlank() || line.startsWith("-")) return@forEach
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val name = line.substring(0, separator).trim()
                if (name !in SUPPORTED_FIELDS) return@forEach
                put(name, decodeYamlScalar(line.substring(separator + 1).trim()))
            }
        }
    }

    private fun parseListenPort(value: String): Int {
        val text = value.trim()
        text.toIntOrNull()?.let { port ->
            require(port in 1..65535) { "监听端口范围应为 1-65535" }
            return port
        }
        val withoutScheme = text.substringAfter("://", text)
        val portText = when {
            withoutScheme.startsWith("[") && withoutScheme.contains("]:") -> withoutScheme.substringAfterLast(":")
            withoutScheme.contains(":") -> withoutScheme.substringAfterLast(":")
            else -> withoutScheme
        }
        val port = portText.trim().toIntOrNull()
            ?: throw IllegalArgumentException("listen 必须包含有效端口")
        require(port in 1..65535) { "监听端口范围应为 1-65535" }
        return port
    }

    private fun stripYamlComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && quote == '"') {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"' || char == '\'') {
                quote = when {
                    quote == null -> char
                    quote == char -> null
                    else -> quote
                }
            } else if (char == '#' && quote == null) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun decodeYamlScalar(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return JSONObject("{\"value\":$value}").getString("value")
        }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.lastIndex).replace("''", "'")
        }
        return value
    }

    private fun toYaml(config: AndroidProxyConfig): String = buildString {
        appendLine("# WOL 代理服务配置（由 Android APK 导出）")
        appendLine("listen: ${yamlQuote(":${config.port}")}")
        appendLine("key: ${yamlQuote(config.key)}")
        appendLine("default_broadcast: \"255.255.255.255\"")
        appendLine("default_port: 9")
        appendLine("cooldown_seconds: 5")
        appendLine("allow_macs: []")
    }

    private fun toJson(config: AndroidProxyConfig): String {
        return JSONObject()
            .put("listen", ":${config.port}")
            .put("key", config.key)
            .put("default_broadcast", "255.255.255.255")
            .put("default_port", 9)
            .put("cooldown_seconds", 5)
            .put("allow_macs", org.json.JSONArray())
            .toString(2) + "\n"
    }

    private fun yamlQuote(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private val SUPPORTED_FIELDS = setOf("listen", "listen_port", "port", "key", "listen_key")
}
