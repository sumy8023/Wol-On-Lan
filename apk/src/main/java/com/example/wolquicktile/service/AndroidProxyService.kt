package com.example.wolquicktile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.example.wolquicktile.MainActivity
import com.example.wolquicktile.R
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Runs the Android device as a WOL proxy node.
 *
 * The service intentionally implements the same small HTTP protocol as the
 * desktop proxy.  It is independent from the app's normal WOL client, so
 * starting/stopping this service does not change local device management.
 */
class AndroidProxyService : Service() {
    private var server: AndroidProxyHttpServer? = null
    private var activeConfig: AndroidProxyConfig? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AndroidProxyServiceController.markDisabled(this)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> startWith(intent)
            null -> {
                // START_STICKY restarts arrive with a null intent.  Do not
                // resurrect a service that the user explicitly disabled.
                if (AndroidProxyServiceController.isEnabled(this)) {
                    startWith(null)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> startWith(intent)
        }
        // Keep a user-enabled proxy alive after an incidental process kill.
        // An explicit stop writes enabled=false and returns START_NOT_STICKY.
        return START_STICKY
    }

    private fun startWith(intent: Intent?) {
        val configured = AndroidProxyServiceController.configFromIntent(this, intent)
        if (configured == null) {
            AndroidProxyServiceController.markFailure(this, "请先配置代理端口和 KEY")
            stopSelf()
            return
        }

        // Android requires startForeground() shortly after a foreground service
        // is launched.  Put up the persistent notification before binding the
        // listening socket so a bind error can be reported without a crash.
        try {
            startForegroundCompat(buildNotification(configured, "正在启动代理服务"))
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "无法启动前台代理服务"
            AndroidProxyServiceController.markFailure(this, message)
            stopSelf()
            return
        }

        if (activeConfig == configured && server?.isRunning == true) {
            updateNotification(configured, "代理服务运行中")
            AndroidProxyServiceController.markRunning(this, configured)
            return
        }

        server?.stop()
        val newServer = AndroidProxyHttpServer(configured) {
            AndroidProxyServiceController.markRequest(this)
        }
        try {
            newServer.start()
            server = newServer
            activeConfig = configured
            AndroidProxyServiceController.markRunning(this, configured)
            updateNotification(configured, "代理服务运行中")
        } catch (error: Throwable) {
            newServer.stop()
            server = null
            activeConfig = null
            val message = error.message?.takeIf { it.isNotBlank() } ?: "无法监听代理端口"
            AndroidProxyServiceController.markFailure(this, message)
            updateNotification(configured, "代理服务启动失败：$message")
            stopSelf()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        activeConfig = null
        AndroidProxyServiceController.markStopped(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(config: AndroidProxyConfig, status: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(config, status))
    }

    private fun buildNotification(config: AndroidProxyConfig, status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AndroidProxyService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_PROXY,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.proxy_service_notification_title))
            .setContentText("${config.port} · $status")
            .setSubText("WOL 代理")
            .setContentIntent(launchPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    getString(R.string.proxy_service_stop),
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.proxy_service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.proxy_service_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.wolquicktile.action.START_ANDROID_PROXY"
        const val ACTION_STOP = "com.example.wolquicktile.action.STOP_ANDROID_PROXY"
        const val ACTION_STATUS_CHANGED = "com.example.wolquicktile.action.ANDROID_PROXY_STATUS"
        const val EXTRA_PORT = "proxy_port"
        const val EXTRA_KEY = "proxy_key"

        private const val NOTIFICATION_ID = 14250
        private const val NOTIFICATION_CHANNEL_ID = "wol_proxy_service"
        private const val REQUEST_OPEN_APP = 14251
        private const val REQUEST_STOP_PROXY = 14252
    }
}

data class AndroidProxyConfig(
    val port: Int,
    val key: String
) {
    init {
        require(port in 1..65535) { "代理端口范围应为 1-65535" }
        require(key.isNotBlank()) { "代理 KEY 不能为空" }
    }
}

data class AndroidProxyServiceStatus(
    val enabled: Boolean,
    val running: Boolean,
    val port: Int,
    val lastError: String,
    val lastRequestAt: Long
)

/** Persistent configuration and lifecycle entry points used by Compose UI. */
object AndroidProxyServiceController {
    private const val PREFS_NAME = "android_proxy_service"
    private const val LEGACY_PREFS_NAME = "android_proxy_settings"
    private const val PREF_PORT = "listen_port"
    private const val PREF_KEY = "listen_key"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_RUNNING = "running"
    private const val PREF_LAST_ERROR = "last_error"
    private const val PREF_LAST_REQUEST = "last_request_at"

    fun start(context: Context, port: Int, key: String) {
        val cleanKey = key.trim()
        val config = runCatching { AndroidProxyConfig(port, cleanKey) }.getOrElse { return }
        prefs(context).edit()
            .putInt(PREF_PORT, config.port)
            .putString(PREF_KEY, config.key)
            .putBoolean(PREF_ENABLED, true)
            .putString(PREF_LAST_ERROR, "")
            .apply()
        legacyPrefs(context).edit()
            .putInt("port", config.port)
            .putString("key", config.key)
            .putBoolean("enabled", true)
            .apply()
        val intent = Intent(context, AndroidProxyService::class.java).apply {
            action = AndroidProxyService.ACTION_START
            putExtra(AndroidProxyService.EXTRA_PORT, config.port)
            putExtra(AndroidProxyService.EXTRA_KEY, config.key)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** Starts the last saved configuration, useful after process recreation. */
    fun start(context: Context) {
        val config = loadConfig(context) ?: return
        start(context, config.port, config.key)
    }

    fun stop(context: Context) {
        markDisabled(context)
        context.stopService(Intent(context, AndroidProxyService::class.java))
    }

    fun saveConfig(context: Context, port: Int, key: String, enabled: Boolean = false): Boolean {
        val result = runCatching { AndroidProxyConfig(port, key.trim()) }.getOrNull() ?: return false
        prefs(context).edit()
            .putInt(PREF_PORT, result.port)
            .putString(PREF_KEY, result.key)
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
        // Keep the small legacy repository in sync for older screens/builds
        // that may still read it while an APK is upgraded in place.
        legacyPrefs(context).edit()
            .putInt("port", result.port)
            .putString("key", result.key)
            .putBoolean("enabled", enabled)
            .apply()
        return true
    }

    fun clearConfig(context: Context) {
        context.stopService(Intent(context, AndroidProxyService::class.java))
        prefs(context).edit()
            .remove(PREF_PORT)
            .remove(PREF_KEY)
            .remove(PREF_ENABLED)
            .remove(PREF_RUNNING)
            .remove(PREF_LAST_ERROR)
            .remove(PREF_LAST_REQUEST)
            .apply()
        legacyPrefs(context).edit()
            .remove("port")
            .remove("key")
            .remove("enabled")
            .apply()
    }

    fun loadConfig(context: Context): AndroidProxyConfig? {
        val values = prefs(context)
        val legacy = legacyPrefs(context)
        val port = values.getInt(PREF_PORT, legacy.getInt("port", DEFAULT_PORT))
        val key = values.getString(PREF_KEY, null) ?: legacy.getString("key", "").orEmpty()
        return runCatching { AndroidProxyConfig(port, key) }.getOrNull()
    }

    fun isEnabled(context: Context): Boolean {
        val values = prefs(context)
        return if (values.contains(PREF_ENABLED)) {
            values.getBoolean(PREF_ENABLED, false)
        } else {
            legacyPrefs(context).getBoolean("enabled", false)
        }
    }

    fun status(context: Context): AndroidProxyServiceStatus {
        val values = prefs(context)
        val legacy = legacyPrefs(context)
        return AndroidProxyServiceStatus(
            enabled = if (values.contains(PREF_ENABLED)) values.getBoolean(PREF_ENABLED, false)
            else legacy.getBoolean("enabled", false),
            running = values.getBoolean(PREF_RUNNING, false),
            port = values.getInt(PREF_PORT, legacy.getInt("port", DEFAULT_PORT)),
            lastError = values.getString(PREF_LAST_ERROR, "").orEmpty(),
            lastRequestAt = values.getLong(PREF_LAST_REQUEST, 0L)
        )
    }

    fun generateKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString(8) {
            repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    internal fun configFromIntent(context: Context, intent: Intent?): AndroidProxyConfig? {
        val legacy = legacyPrefs(context)
        val port = intent?.takeIf { it.hasExtra(AndroidProxyService.EXTRA_PORT) }
            ?.getIntExtra(AndroidProxyService.EXTRA_PORT, -1)
            ?: prefs(context).getInt(PREF_PORT, legacy.getInt("port", DEFAULT_PORT))
        val key = intent?.getStringExtra(AndroidProxyService.EXTRA_KEY)
            ?: prefs(context).getString(PREF_KEY, null)
            ?: legacy.getString("key", "").orEmpty()
        return runCatching { AndroidProxyConfig(port, key.trim()) }.getOrNull()
    }

    internal fun markRunning(context: Context, config: AndroidProxyConfig) {
        prefs(context).edit()
            .putInt(PREF_PORT, config.port)
            .putString(PREF_KEY, config.key)
            .putBoolean(PREF_ENABLED, true)
            .putBoolean(PREF_RUNNING, true)
            .putString(PREF_LAST_ERROR, "")
            .apply()
        legacyPrefs(context).edit().putBoolean("enabled", true).apply()
        broadcast(context, running = true, error = "")
    }

    internal fun markRequest(context: Context) {
        prefs(context).edit().putLong(PREF_LAST_REQUEST, System.currentTimeMillis()).apply()
    }

    internal fun markFailure(context: Context, error: String) {
        prefs(context).edit()
            .putBoolean(PREF_RUNNING, false)
            .putString(PREF_LAST_ERROR, error)
            .apply()
        broadcast(context, running = false, error = error)
    }

    internal fun markStopped(context: Context) {
        prefs(context).edit().putBoolean(PREF_RUNNING, false).apply()
        broadcast(context, running = false, error = prefs(context).getString(PREF_LAST_ERROR, "").orEmpty())
    }

    internal fun markDisabled(context: Context) {
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, false)
            .putBoolean(PREF_RUNNING, false)
            .apply()
        legacyPrefs(context).edit().putBoolean("enabled", false).apply()
        broadcast(context, running = false, error = "")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun legacyPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private fun broadcast(context: Context, running: Boolean, error: String) {
        val intent = Intent(AndroidProxyService.ACTION_STATUS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra("running", running)
            putExtra("error", error)
        }
        context.sendBroadcast(intent)
    }

    const val DEFAULT_PORT = 14250
}

/** Small HTTP/1.1 server implementing the desktop WOL proxy wire protocol. */
internal class AndroidProxyHttpServer(
    private val config: AndroidProxyConfig,
    private val onRequest: () -> Unit
) {
    private fun jsonError(error: String): String = "{\"ok\":false,\"error\":\"$error\"}"
    private val acceptExecutor = Executors.newSingleThreadExecutor(NamedThreadFactory("wol-proxy-accept"))
    private val clientExecutor = Executors.newCachedThreadPool(NamedThreadFactory("wol-proxy-client"))
    private val nonceCache = ConcurrentHashMap<String, Long>()
    private val lastWakeMillis = ConcurrentHashMap<String, Long>()
    private val lifecycleLock = Any()
    @Volatile
    private var running = false
    @Volatile
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean
        get() = running

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", config.port), 64)
            serverSocket = socket
            running = true
            acceptExecutor.execute(::acceptLoop)
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (!running && serverSocket == null) return
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptExecutor.shutdownNow()
            clientExecutor.shutdownNow()
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                client.soTimeout = REQUEST_TIMEOUT_MS
                clientExecutor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (running) continue
            } catch (_: IOException) {
                if (running) continue
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3) {
                writeResponse(output, 400, jsonError("bad_request"))
                return
            }
            val method = requestParts[0].uppercase(Locale.ROOT)
            val path = requestParts[1].substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: run {
                    writeResponse(output, 400, jsonError("bad_request"))
                    return
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    writeResponse(output, 400, jsonError("bad_request"))
                    return
                }
                val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim()
                headers.putIfAbsent(name, value)
                if (headers.size > MAX_HEADERS) {
                    writeResponse(output, 431, jsonError("too_many_headers"))
                    return
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeResponse(output, 413, jsonError("body_too_large"))
                return
            }
            val body = readBody(input, contentLength.toInt()) ?: run {
                writeResponse(output, 400, jsonError("bad_request"))
                return
            }

            val response = route(method, path, headers, body)
            writeResponse(output, response.status, response.body, response.headers)
            onRequest()
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray
    ): HttpResponse {
        return when (path) {
            "/api/health" -> {
                if (method != "GET") HttpResponse(405, jsonError("method_not_allowed"))
                else authenticate(headers, body)?.let { HttpResponse(it.status, jsonError(it.error)) }
                    ?: HttpResponse(
                        200,
                        "{\"ok\":true,\"name\":\"WOL Proxy\",\"version\":\"$PROTOCOL_VERSION\",\"cooldown_seconds\":$COOLDOWN_SECONDS}"
                    )
            }

            "/api/wake" -> {
                if (method != "POST") HttpResponse(405, jsonError("method_not_allowed"))
                else authenticate(headers, body)?.let { HttpResponse(it.status, jsonError(it.error)) }
                    ?: handleWake(body)
            }

            else -> HttpResponse(404, jsonError("not_found"))
        }
    }

    private fun authenticate(headers: Map<String, String>, body: ByteArray): AuthFailure? {
        cleanupNonces()
        val timestampText = headers["x-wol-timestamp"]
        val nonce = headers["x-wol-nonce"]
        val signature = headers["x-wol-signature"]
        if (timestampText.isNullOrBlank() || nonce.isNullOrBlank() || signature.isNullOrBlank()) {
            return AuthFailure(401, "missing_signature_headers")
        }
        if (nonce.length > MAX_NONCE_LENGTH) return AuthFailure(401, "invalid_nonce")
        val timestamp = timestampText.toLongOrNull() ?: return AuthFailure(401, "invalid_timestamp")
        val now = Instant.now().epochSecond
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_TOLERANCE_SECONDS) {
            return AuthFailure(401, "timestamp_expired")
        }
        if (nonceCache.putIfAbsent(nonce, now) != null) return AuthFailure(401, "nonce_replayed")

        val payload = timestampText + "\n" + nonce + "\n" + String(body, StandardCharsets.UTF_8)
        val expected = hmacSha256Hex(config.key, payload)
        if (!MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                signature.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII)
            )
        ) {
            return AuthFailure(401, "bad_signature")
        }
        return null
    }

    private fun handleWake(body: ByteArray): HttpResponse {
        val json = try {
            JSONObject(String(body, StandardCharsets.UTF_8))
        } catch (_: JSONException) {
            return HttpResponse(400, jsonError("bad_request"))
        }
        val macText = json.optString("mac", "").trim()
        if (!MAC_PATTERN.matches(macText)) return HttpResponse(400, jsonError("invalid_mac"))
        val mac = normalizeMac(macText)

        val port = if (json.has("port")) json.optInt("port", -1) else DEFAULT_WOL_PORT
        if (port !in 1..65535) return HttpResponse(400, jsonError("invalid_port"))
        val requestedAddress = json.optString("address", "").trim()
        val target = resolveBroadcastTarget(requestedAddress.ifBlank { DEFAULT_BROADCAST })
            ?: return HttpResponse(400, jsonError("invalid_address"))

        val now = System.currentTimeMillis()
        synchronized(lastWakeMillis) {
            val last = lastWakeMillis[mac]
            if (last != null && now - last < COOLDOWN_MS) {
                val retryAfter = ((COOLDOWN_MS - (now - last) + 999L) / 1000L).coerceAtLeast(1L)
                return HttpResponse(
                    429,
                    "{\"ok\":false,\"error\":\"cooldown\",\"retry_after\":$retryAfter}"
                )
            }
            return try {
                sendWakePacket(mac, target, port)
                lastWakeMillis[mac] = now
                HttpResponse(200, "{\"ok\":true,\"target\":\"$target\",\"port\":$port}")
            } catch (_: Throwable) {
                HttpResponse(500, jsonError("send_failed"))
            }
        }
    }

    private fun cleanupNonces() {
        val expireBefore = Instant.now().epochSecond - NONCE_RETENTION_SECONDS
        nonceCache.entries.removeIf { it.value < expireBefore }
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = STATUS_REASONS[status] ?: ""
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        if (bytes.size() > MAX_LINE_BYTES) return null
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readBody(input: BufferedInputStream, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return body
    }

    private fun sendWakePacket(normalizedMac: String, target: String, port: Int) {
        val macBytes = parseMac(normalizedMac)
        val packetBytes = ByteArray(6 + 16 * macBytes.size)
        java.util.Arrays.fill(packetBytes, 0, 6, 0xff.toByte())
        repeat(16) { index ->
            macBytes.copyInto(packetBytes, destinationOffset = 6 + index * macBytes.size)
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName(target), port))
        }
    }

    private fun hmacSha256Hex(key: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun parseMac(value: String): ByteArray {
        val clean = value.replace(":", "").replace("-", "")
        return ByteArray(6) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun normalizeMac(value: String): String =
        value.replace(":", "").replace("-", "").uppercase(Locale.ROOT)

    private fun resolveBroadcastTarget(value: String): String? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        if (value == DEFAULT_BROADCAST) return value
        return "${octets[0]}.${octets[1]}.${octets[2]}.255"
    }

    private data class AuthFailure(val status: Int, val error: String)
    private data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private val count = AtomicInteger()
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${count.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    companion object {
        private const val PROTOCOL_VERSION = "1.0.2"
        private const val DEFAULT_BROADCAST = "255.255.255.255"
        private const val DEFAULT_WOL_PORT = 9
        private const val COOLDOWN_SECONDS = 5
        private const val COOLDOWN_MS = COOLDOWN_SECONDS * 1_000L
        private const val TIMESTAMP_TOLERANCE_SECONDS = 60L
        private const val NONCE_RETENTION_SECONDS = 120L
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val MAX_LINE_BYTES = 8_192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 64 * 1024L
        private const val MAX_NONCE_LENGTH = 128
        private val MAC_PATTERN = Regex("^(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})|[0-9A-Fa-f]{12})$")
        private val STATUS_REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            405 to "Method Not Allowed",
            413 to "Payload Too Large",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error"
        )
    }
}
