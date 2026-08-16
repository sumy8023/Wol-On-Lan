package com.example.wolproxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Main {
    private static final String VERSION = "1.0.2";
    private static final String WINDOWS_CONFIG_FILE_NAME = "wol-config.yml";
    private static final String RANDOM_LETTERS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String RANDOM_DIGITS = "23456789";
    /** Special characters remain available for management tokens, not paths. */
    private static final String RANDOM_SPECIALS = "._~!$&'()*+,;=:@-";
    private static final int ADMIN_PATH_LENGTH = 8;
    private static final int ADMIN_TOKEN_LENGTH = 24;
    private static final int PROXY_KEY_LENGTH = 24;
    private static final int MIN_ADMIN_PATH_LENGTH = 4;
    private static final int MAX_ADMIN_PATH_LENGTH = 64;
    private static final int MIN_ADMIN_TOKEN_LENGTH = 6;
    private static final int MAX_ADMIN_TOKEN_LENGTH = 256;
    private static final int MAX_PROXY_BODY_BYTES = 16 * 1024;
    private static final int MAX_ADMIN_BODY_BYTES = 64 * 1024;
    private static final int MAX_LOG_RESULTS = 5000;
    private static final int MAX_FAILED_AUTH_PER_MINUTE = 10;
    private static final String ADMIN_TOKEN_VERIFIER_SCHEME = "pbkdf2-sha256";
    private static final String ADMIN_TOKEN_VERIFIER_VERSION = "v1";
    private static final int ADMIN_TOKEN_PBKDF2_ITERATIONS = 210_000;
    private static final int ADMIN_TOKEN_SALT_BYTES = 16;
    private static final int ADMIN_TOKEN_HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ZoneOffset LOG_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Path LOG_FILE = Path.of(System.getProperty("user.home", "."))
            .resolve("wol-proxy.log")
            .toAbsolutePath()
            .normalize();
    private static final boolean DOCKER_ENVIRONMENT = detectDockerEnvironment();
    private static final Pattern MAC_PATTERN = Pattern.compile("^(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})|[0-9A-Fa-f]{12})$");
    /** Management entry is deliberately easy to type: letters only. */
    private static final Pattern ADMIN_PATH_PATTERN = Pattern.compile("^[A-Za-z]{"
            + MIN_ADMIN_PATH_LENGTH + "," + MAX_ADMIN_PATH_LENGTH + "}$");
    /** Visible ASCII characters are safe for a request header and YAML/JSON escaping. */
    private static final Pattern ADMIN_TOKEN_PATTERN = Pattern.compile("^[\\x21-\\x7E]{"
            + MIN_ADMIN_TOKEN_LENGTH + "," + MAX_ADMIN_TOKEN_LENGTH + "}$");
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]\\[([^]]+)](.*)$");
    private static final Pattern JSON_STRING_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern JSON_INT_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");

    private final Config config;
    private volatile String activeListen;
    private volatile HttpServer server;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private final Object serverLifecycleLock = new Object();
    private final Object configUpdateLock = new Object();
    private final Instant startedAt = Instant.now();
    private final AtomicLong wakeRequestCount = new AtomicLong();
    private final ConcurrentHashMap<String, Long> nonceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastWakeMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthFailures> adminAuthFailures = new ConcurrentHashMap<>();

    private Main(Config config) {
        this.config = config;
        this.activeListen = config.listen;
    }

    public static void main(String[] args) {
        configureConsoleEncoding();
        try {
            start(args);
        } catch (Exception e) {
            logError("代理服务启动失败：%s", startupErrorMessage(e));
            System.exit(1);
        }
    }

    private static void start(String[] args) throws Exception {
        Config config = Config.load(args);
        String generatedProxyKey = config.prepareProxyKeyForDockerStartup();
        AdminAccessPreparation adminAccess = config.prepareAdminAccessForStartup();
        if (generatedProxyKey != null || adminAccess.persistenceRequired) {
            config.save();
            config.configFileLoaded = true;
            if (adminAccess.legacyTokenMigrated) {
                log("已将旧版明文管理令牌迁移为 PBKDF2 verifier：%s", config.adminTokenPath());
            } else {
                log("已生成或更新 Web 管理配置并保存到：%s", config.configPath);
            }
        }
        printGeneratedProxyKey(generatedProxyKey);
        printGeneratedAdminToken(adminAccess.generatedToken);
        if (config.configFileLoaded) {
            log("已读取配置文件：%s", config.configPath);
        } else {
            log("未找到配置文件：%s；当前仅使用环境变量或命令行参数", config.configPath);
        }
        if (config.key.isBlank()) {
            logError("错误：必须配置 key。请在配置文件中填写 key，或设置环境变量 WOL_PROXY_KEY。");
            System.exit(2);
        }

        Main app = new Main(config);
        InetSocketAddress listenAddress = app.startInitialServer();
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));

        log("WOL 代理服务 %s 已启动，监听地址=%s，冷却时间=%s秒，MAC白名单数量=%d，日志文件=%s",
                VERSION,
                config.listen,
                config.cooldownSeconds,
                config.allowMacs.size(),
                LOG_FILE);
        log("Web 管理后台地址：%s", adminBrowserUrl(config, listenAddress));
    }

    private InetSocketAddress startInitialServer() throws IOException {
        InetSocketAddress address = parseListen(config.listen);
        HttpServer initialServer = createServer(address);
        initialServer.start();
        synchronized (serverLifecycleLock) {
            server = initialServer;
            activeListen = config.listen;
        }
        return address;
    }

    private HttpServer createServer(InetSocketAddress address) throws IOException {
        HttpServer candidate = HttpServer.create(address, 0);
        candidate.createContext("/", this::handleRequest);
        candidate.setExecutor(requestExecutor);
        return candidate;
    }

    private ListenerSwap prepareListenerSwap(String listen) throws IOException {
        InetSocketAddress address = parseListen(listen);
        HttpServer replacement = createServer(address);
        try {
            replacement.start();
        } catch (RuntimeException error) {
            replacement.stop(0);
            throw error;
        }
        return new ListenerSwap(server, replacement, listen);
    }

    private void commitListenerSwap(ListenerSwap swap) {
        synchronized (serverLifecycleLock) {
            server = swap.replacement;
            activeListen = swap.listen;
        }
    }

    private static void rollbackListenerSwap(ListenerSwap swap) {
        if (swap != null) swap.replacement.stop(0);
    }

    private void retireListener(ListenerSwap swap) {
        if (swap != null && swap.previous != null && swap.previous != swap.replacement) {
            try {
                requestExecutor.execute(() -> {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    swap.previous.stop(1);
                });
            } catch (RuntimeException rejected) {
                // Shutdown may race with a final config response; stopping is best effort.
                swap.previous.stop(0);
            }
        }
    }

    private void shutdown() {
        HttpServer current;
        synchronized (serverLifecycleLock) {
            current = server;
            server = null;
        }
        if (current != null) current.stop(1);
        requestExecutor.shutdown();
        log("WOL 代理服务已停止");
    }

    private static void printGeneratedAdminToken(String generatedToken) {
        if (generatedToken == null) return;
        System.out.printf(Locale.ROOT, "首次生成的管理令牌：%s%n", generatedToken);
        System.out.println("请妥善保管后台地址和管理令牌；管理令牌不会写入运行日志。");
    }

    private static void printGeneratedProxyKey(String generatedKey) {
        if (generatedKey == null) return;
        System.out.printf(Locale.ROOT, "首次生成的代理连接 KEY：%s%n", generatedKey);
        System.out.println("请将此 KEY 填入 APK 的代理节点；它已保存到配置文件，后续重启不会重复显示。");
    }

    private static void configureConsoleEncoding() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                new ProcessBuilder("cmd.exe", "/d", "/c", "chcp 65001 >nul 2>&1")
                        .inheritIO()
                        .start()
                        .waitFor();
            } catch (Exception ignored) {
            }
        }
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String startupErrorMessage(Exception error) {
        if (error instanceof BindException) {
            return "监听端口已被占用，请修改 listen 或关闭占用该端口的程序。";
        }
        if (error instanceof NumberFormatException) {
            return "监听端口格式不正确，请检查 listen 配置。";
        }
        String message = error.getMessage();
        if (message != null && (message.startsWith("监听地址格式不正确")
                || message.startsWith("监听端口必须在"))) {
            return message;
        }
        if (error instanceof IOException) {
            if (DOCKER_ENVIRONMENT) {
                return "无法读取或写入 /config/config.yml。请将宿主机配置目录以读写方式挂载到容器 /config，"
                        + "不要把目录直接挂载到 /config/config.yml。";
            }
            return "读取配置文件或启动网络服务失败，请检查文件路径、端口和访问权限。";
        }
        return "未知错误，请检查配置文件。";
    }

    private static String listenerRestartErrorMessage(Throwable error) {
        if (error instanceof BindException || error.getCause() instanceof BindException) {
            return "目标监听端口已被占用";
        }
        if (error instanceof IllegalArgumentException && error.getMessage() != null) {
            return safeLogText(error.getMessage());
        }
        return "无法绑定目标监听地址或端口";
    }

    private static String describeConfigChanges(Config before, Config after) {
        List<String> changes = new ArrayList<>();
        addConfigChange(changes, "监听地址", before.listen, after.listen, false);
        addConfigChange(changes, "代理连接 Key", before.key, after.key, true);
        addConfigChange(changes, "默认广播地址", before.defaultBroadcast, after.defaultBroadcast, false);
        addConfigChange(changes, "默认 UDP 端口", before.defaultPort, after.defaultPort, false);
        addConfigChange(changes, "冷却时间（秒）", before.cooldownSeconds, after.cooldownSeconds, false);
        addConfigChange(changes, "MAC 白名单", before.allowMacs, after.allowMacs, false);
        addConfigChange(changes, "管理入口", before.adminPath, after.adminPath, false);
        if (!Objects.equals(before.adminTokenVerifier, after.adminTokenVerifier)) {
            changes.add("管理令牌=<已设置>-><已设置>");
        }
        return changes.isEmpty() ? "无" : String.join("；", changes);
    }

    private static void addConfigChange(List<String> changes, String field, Object before, Object after, boolean sensitive) {
        if (Objects.equals(before, after)) return;
        changes.add(field + "=" + auditValue(before, sensitive) + "->" + auditValue(after, sensitive));
    }

    private static String auditValue(Object value, boolean sensitive) {
        if (sensitive) {
            String text = value == null ? "" : String.valueOf(value);
            return text.isBlank() ? "<未设置>" : "<已设置:" + text.length() + "位>";
        }
        if (value instanceof Set<?> values) {
            return safeLogText(values.toString());
        }
        return safeLogText(String.valueOf(value));
    }

    private static String safeLogText(String value) {
        if (value == null || value.isBlank()) return "未知";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private static String sendErrorMessage(Exception error) {
        if (error instanceof SecurityException) {
            return "系统安全策略阻止发送 UDP 广播包";
        }
        return "系统未能发送 UDP 广播包，请检查网络接口和广播地址";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean detectDockerEnvironment() {
        if (Boolean.parseBoolean(System.getProperty("wol.proxy.docker", "false"))) return true;
        if (Files.exists(Path.of("/.dockerenv"))) return true;
        String container = System.getenv("container");
        if (container != null && container.toLowerCase(Locale.ROOT).contains("docker")) return true;
        Path cgroup = Path.of("/proc/1/cgroup");
        if (!Files.isRegularFile(cgroup)) return false;
        try {
            String value = Files.readString(cgroup, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return value.contains("/docker/") || value.contains("/docker-") || value.contains("containerd");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path userHomeDirectory() {
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    private static Path defaultConfigPath() {
        return isWindows()
                ? userHomeDirectory().resolve("wol").resolve(WINDOWS_CONFIG_FILE_NAME)
                : Path.of("config.yml").toAbsolutePath().normalize();
    }

    private static Path packagedAppDirectory() {
        String javaHomeText = System.getProperty("java.home", "").trim();
        if (!javaHomeText.isBlank()) {
            Path javaHome = Path.of(javaHomeText).toAbsolutePath().normalize();
            Path directory = javaHome.getParent();
            if (directory != null && Files.isDirectory(directory.resolve("app"))) {
                return directory;
            }
        }

        String packagedAppPath = System.getProperty("jpackage.app-path", "").trim();
        if (!packagedAppPath.isBlank()) {
            Path executablePath = Path.of(packagedAppPath).toAbsolutePath().normalize();
            Path directory = executablePath.getParent();
            if (directory != null && Files.isDirectory(directory.resolve("app"))) {
                return directory;
            }
        }
        return null;
    }

    private static Path findWindowsConfigSource() {
        Path userHome = userHomeDirectory();
        Path legacyWolConfig = userHome.resolve(WINDOWS_CONFIG_FILE_NAME);
        if (Files.exists(legacyWolConfig)) return legacyWolConfig;
        Path legacyConfig = userHome.resolve("config.yml");
        if (Files.exists(legacyConfig)) return legacyConfig;

        Path packageDirectory = packagedAppDirectory();
        if (packageDirectory != null) {
            for (String fileName : List.of("wol-config.example.yml", "config.example.yml", "config.yml")) {
                Path candidate = packageDirectory.resolve(fileName);
                if (Files.exists(candidate)) return candidate;
                Path appCandidate = packageDirectory.resolve("app").resolve(fileName);
                if (Files.exists(appCandidate)) return appCandidate;
            }
        }
        return null;
    }

    /**
     * All HTTP traffic shares one context so that a newly configured admin path
     * takes effect without having to register another HttpServer context.
     */
    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank()) path = "/";

        if ("/api/health".equals(path)) {
            handleHealth(exchange);
            return;
        }
        if ("/api/wake".equals(path)) {
            handleWake(exchange);
            return;
        }

        String adminBase = "/" + config.adminPath;
        if (adminBase.equals(path)) {
            exchange.getResponseHeaders().set("Location", adminBase + "/");
            applySecurityHeaders(exchange);
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
            return;
        }
        if (path.startsWith(adminBase + "/")) {
            handleAdmin(exchange, path.substring(adminBase.length()));
            return;
        }

        sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
    }

    private void handleAdmin(HttpExchange exchange, String relativePath) throws IOException {
        if (relativePath.isEmpty() || "/".equals(relativePath)) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!sendAdminResource(exchange, "index.html", "text/html; charset=utf-8")) {
                sendHtml(exchange, 200, adminPageHtml());
            }
            return;
        }

        if ("/app.css".equals(relativePath) || "/app.js".equals(relativePath)) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            String resourceName = relativePath.substring(1);
            String contentType = resourceName.endsWith(".css")
                    ? "text/css; charset=utf-8"
                    : "application/javascript; charset=utf-8";
            if (!sendAdminResource(exchange, resourceName, contentType)) {
                sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
            }
            return;
        }

        if (!relativePath.startsWith("/api/")) {
            sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
            return;
        }

        // Login is deliberately the only admin API that does not require the
        // token in a request header; it accepts the startup token once and
        // returns it for the browser's session storage.
        if ("/api/login".equals(relativePath)) {
            handleAdminLogin(exchange);
            return;
        }
        if ("/api/logout".equals(relativePath)) {
            if (!requireAdminAuth(exchange)) return;
            handleAdminLogout(exchange);
            return;
        }

        if (!requireAdminAuth(exchange)) return;

        switch (relativePath) {
            case "/api/config" -> handleAdminConfig(exchange);
            case "/api/config/export" -> handleAdminConfigExport(exchange);
            case "/api/config/import" -> handleAdminConfigImport(exchange);
            case "/api/status" -> handleAdminStatus(exchange);
            case "/api/logs" -> handleAdminLogs(exchange);
            case "/api/reload" -> handleAdminReload(exchange);
            case "/api/regenerate-access" -> handleRegenerateAccess(exchange);
            default -> sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
        }
    }

    private void handleAdminLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_ADMIN_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        String json = new String(body, StandardCharsets.UTF_8);
        String supplied = firstNonBlank(jsonString(json, "password"), jsonString(json, "token"));
        if (supplied == null) supplied = jsonString(json, "admin_token");
        if (!checkAdminToken(exchange, supplied)) return;
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private void handleAdminLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private boolean requireAdminAuth(HttpExchange exchange) throws IOException {
        String supplied = firstHeader(exchange.getRequestHeaders(), "X-WOL-Admin-Token");
        if (supplied == null || supplied.isBlank()) {
            String authorization = firstHeader(exchange.getRequestHeaders(), "Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                supplied = authorization.substring(7).trim();
            } else if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
                supplied = basicPassword(authorization.substring(6).trim());
            }
        }

        return checkAdminToken(exchange, supplied);
    }

    private boolean checkAdminToken(HttpExchange exchange, String supplied) throws IOException {
        String requestSource = source(exchange);
        AuthFailures failures = adminAuthFailures.computeIfAbsent(requestSource, ignored -> new AuthFailures());
        if (failures.blocked()) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            sendJson(exchange, 429, "{\"ok\":false,\"error\":\"too_many_attempts\"}");
            return false;
        }

        if (supplied == null || supplied.isBlank()
                || !verifyAdminToken(supplied, config.adminTokenVerifier)) {
            failures.recordFailure();
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendJson(exchange, 401, "{\"ok\":false,\"error\":\"admin_auth_required\"}");
            return false;
        }
        failures.reset();
        return true;
    }

    private static String basicPassword(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            return separator >= 0 ? decoded.substring(separator + 1) : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void handleAdminStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        long uptime = Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
        String json = "{\"ok\":true,\"version\":\"" + VERSION + "\",\"uptime_seconds\":" + uptime
                + ",\"wake_requests\":" + wakeRequestCount.get() + ",\"listen\":\""
                + jsonEscape(activeListen) + "\",\"active_listen\":\"" + jsonEscape(activeListen)
                + "\",\"configured_listen\":\""
                + jsonEscape(config.listen) + "\",\"restart_required\":" + (!activeListen.equals(config.listen))
                + ",\"docker_environment\":" + DOCKER_ENVIRONMENT
                + ",\"config_path\":\"" + jsonEscape(config.configPath.toString()) + "\",\"log_path\":\""
                + jsonEscape(LOG_FILE.toString()) + "\",\"admin_path\":\"" + jsonEscape(config.adminPath)
                + "\",\"environment_overrides\":" + jsonStringArray(config.environmentOverrides) + "}";
        sendJson(exchange, 200, json);
    }

    private void handleAdminConfig(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            sendJson(exchange, 200, configJson());
            return;
        }
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }

        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_ADMIN_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        String bodyText = new String(body, StandardCharsets.UTF_8).trim();
        if (bodyText.isBlank()) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"empty_body\"}");
            return;
        }

        ListenerSwap listenerSwap = null;
        Config previous;
        Config candidate;
        String changes;
        synchronized (configUpdateLock) {
            previous = config.copy();
            candidate = previous.copy();
            try {
                candidate.applyJson(bodyText);
                candidate.validate();
            } catch (IllegalArgumentException error) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_config\",\"message\":\""
                        + jsonEscape(error.getMessage() == null ? "配置内容无效" : error.getMessage()) + "\"}");
                return;
            }

            changes = describeConfigChanges(previous, candidate);
            if (!previous.listen.equals(candidate.listen)) {
                try {
                    listenerSwap = prepareListenerSwap(candidate.listen);
                } catch (IOException | RuntimeException error) {
                    logError("Web 管理后台切换监听失败：来源=%s，变更=%s，原因=%s",
                            source(exchange), changes, listenerRestartErrorMessage(error));
                    sendJson(exchange, 409, "{\"ok\":false,\"error\":\"listener_restart_failed\",\"message\":\""
                            + jsonEscape("监听端口切换失败，旧监听仍正常工作：" + listenerRestartErrorMessage(error))
                            + "\",\"active_listen\":\"" + jsonEscape(activeListen) + "\"}");
                    return;
                }
            }

            try {
                candidate.save();
                candidate.configFileLoaded = true;
            } catch (IOException error) {
                rollbackListenerSwap(listenerSwap);
                logError("Web 管理后台保存配置失败：来源=%s，变更=%s，原因=%s",
                        source(exchange), changes, safeLogText(error.getMessage()));
                sendJson(exchange, 500, "{\"ok\":false,\"error\":\"config_save_failed\",\"message\":\""
                        + jsonEscape(error.getMessage() == null ? "无法写入配置文件" : error.getMessage()) + "\"}");
                return;
            }

            config.apply(candidate);
            if (listenerSwap != null) commitListenerSwap(listenerSwap);
        }
        log("Web 管理后台已保存配置：来源=%s，变更=%s，监听热切换=%s",
                source(exchange), changes, listenerSwap != null ? "成功" : "无需切换");
        try {
            sendJson(exchange, 200, configJsonWithMessage(listenerSwap != null
                    ? "配置已保存并立即生效；监听端口已热切换"
                    : "配置已保存并立即生效",
                    !Objects.equals(previous.adminTokenVerifier, candidate.adminTokenVerifier)));
        } finally {
            retireListener(listenerSwap);
        }
    }

    private ConfigApplyResult saveCandidateConfig(Config candidate) throws IOException, ListenerRestartException {
        synchronized (configUpdateLock) {
            Config previous = config.copy();
            String changes = describeConfigChanges(previous, candidate);
            ListenerSwap listenerSwap = null;
            if (!previous.listen.equals(candidate.listen)) {
                try {
                    listenerSwap = prepareListenerSwap(candidate.listen);
                } catch (IOException | RuntimeException error) {
                    throw new ListenerRestartException(listenerRestartErrorMessage(error), error);
                }
            }
            try {
                candidate.save();
                candidate.configFileLoaded = true;
            } catch (IOException error) {
                rollbackListenerSwap(listenerSwap);
                throw error;
            }
            config.apply(candidate);
            if (listenerSwap != null) commitListenerSwap(listenerSwap);
            return new ConfigApplyResult(previous, candidate.copy(), changes, listenerSwap);
        }
    }

    private static final class ConfigApplyResult {
        final Config previous;
        final Config applied;
        final String changes;
        final ListenerSwap listenerSwap;

        private ConfigApplyResult(Config previous, Config applied, String changes, ListenerSwap listenerSwap) {
            this.previous = previous;
            this.applied = applied;
            this.changes = changes;
            this.listenerSwap = listenerSwap;
        }
    }

    private static final class ListenerRestartException extends IOException {
        final String reason;

        private ListenerRestartException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }
    }

    private void handleAdminConfigExport(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        String format = configDocumentFormat(exchange, "yaml");
        if (format == null) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"unsupported_config_format\",\"message\":\"格式只支持 yaml 或 json\"}");
            return;
        }
        Config snapshot;
        synchronized (configUpdateLock) {
            snapshot = config.copy();
        }
        if ("json".equals(format)) {
            sendDownload(exchange, "application/json; charset=utf-8", "wol-proxy-config.json",
                    configDocumentJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } else {
            sendDownload(exchange, "application/yaml; charset=utf-8", "wol-proxy-config.yml",
                    snapshot.toYaml().getBytes(StandardCharsets.UTF_8));
        }
        log("Web 管理后台已导出配置：来源=%s，格式=%s", source(exchange), format);
    }

    private void handleAdminConfigImport(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "POST, PUT");
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_ADMIN_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        String document = new String(body, StandardCharsets.UTF_8).trim();
        if (document.isBlank()) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"empty_body\"}");
            return;
        }
        String format = configDocumentFormat(exchange, detectConfigDocumentFormat(exchange, document));
        if (format == null) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"unsupported_config_format\",\"message\":\"格式只支持 yaml 或 json\"}");
            return;
        }

        ConfigApplyResult result;
        try {
            synchronized (configUpdateLock) {
                Config candidate = config.copy();
                if ("json".equals(format)) candidate.applyJson(document);
                else Config.applyYamlText(candidate, document);
                if ("yaml".equals(format)) candidate.applyPendingAdminTokenForAuthenticatedUpdate();
                candidate.validate();
                result = saveCandidateConfig(candidate);
            }
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_config\",\"message\":\""
                    + jsonEscape(error.getMessage() == null ? "导入配置内容无效" : error.getMessage()) + "\"}");
            return;
        } catch (ListenerRestartException error) {
            logError("Web 管理后台导入配置切换监听失败：来源=%s，格式=%s，原因=%s",
                    source(exchange), format, error.reason);
            sendJson(exchange, 409, "{\"ok\":false,\"error\":\"listener_restart_failed\",\"message\":\""
                    + jsonEscape("导入配置未生效，旧监听仍正常工作：" + error.reason)
                    + "\",\"active_listen\":\"" + jsonEscape(activeListen) + "\"}");
            return;
        } catch (IOException error) {
            logError("Web 管理后台导入配置保存失败：来源=%s，格式=%s，原因=%s",
                    source(exchange), format, safeLogText(error.getMessage()));
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"config_save_failed\",\"message\":\""
                    + jsonEscape(error.getMessage() == null ? "无法保存导入配置" : error.getMessage()) + "\"}");
            return;
        }

        log("Web 管理后台已导入配置：来源=%s，格式=%s，变更=%s，监听热切换=%s",
                source(exchange), format, result.changes,
                result.listenerSwap != null ? "成功" : "无需切换");
        try {
            sendJson(exchange, 200, configJsonWithMessage(result.listenerSwap != null
                    ? "配置已导入并立即生效；监听端口已热切换"
                    : "配置已导入并立即生效",
                    !Objects.equals(result.previous.adminTokenVerifier, result.applied.adminTokenVerifier)));
        } finally {
            retireListener(result.listenerSwap);
        }
    }

    private static String configDocumentFormat(HttpExchange exchange, String fallback) {
        String requested = parseQuery(exchange.getRequestURI().getRawQuery()).get("format");
        String format = requested == null || requested.isBlank() ? fallback : requested;
        if (format == null) return null;
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if ("yml".equals(normalized)) normalized = "yaml";
        return "yaml".equals(normalized) || "json".equals(normalized) ? normalized : null;
    }

    private static String detectConfigDocumentFormat(HttpExchange exchange, String document) {
        String contentType = firstHeader(exchange.getRequestHeaders(), "Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) return "json";
        if (contentType != null && (contentType.toLowerCase(Locale.ROOT).contains("yaml")
                || contentType.toLowerCase(Locale.ROOT).contains("yml"))) return "yaml";
        return document.stripLeading().startsWith("{") ? "json" : "yaml";
    }

    private static String configDocumentJson(Config value) {
        StringBuilder json = new StringBuilder("{");
        appendJsonField(json, "listen", value.listen).append(',');
        appendJsonField(json, "key", value.key).append(',');
        appendJsonField(json, "default_broadcast", value.defaultBroadcast).append(',');
        json.append("\"default_port\":").append(value.defaultPort).append(',');
        json.append("\"cooldown_seconds\":").append(value.cooldownSeconds).append(',');
        json.append("\"allow_macs\":").append(jsonStringArray(value.allowMacs)).append(',');
        appendJsonField(json, "admin_path", value.adminPath);
        return json.append('}').toString();
    }

    private void handleAdminReload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        ListenerSwap listenerSwap = null;
        String changes;
        try {
            synchronized (configUpdateLock) {
                Config previous = config.copy();
                Config loaded = Config.loadFromPath(config.configPath);
                loaded.ensureAdminAccessFrom(config);
                loaded.validate();
                changes = describeConfigChanges(previous, loaded);
                if (!previous.listen.equals(loaded.listen)) {
                    try {
                        listenerSwap = prepareListenerSwap(loaded.listen);
                    } catch (IOException | RuntimeException error) {
                        throw new ListenerRestartException(listenerRestartErrorMessage(error), error);
                    }
                }
                config.apply(loaded);
                if (listenerSwap != null) commitListenerSwap(listenerSwap);
            }
        } catch (ListenerRestartException error) {
            rollbackListenerSwap(listenerSwap);
            logError("Web 管理后台重新加载监听失败：来源=%s，原因=%s",
                    source(exchange), error.reason);
            sendJson(exchange, 409, "{\"ok\":false,\"error\":\"listener_restart_failed\",\"message\":\""
                    + jsonEscape("YAML 已读取，但新监听启动失败，旧配置和监听保持不变：" + error.reason)
                    + "\",\"active_listen\":\"" + jsonEscape(activeListen) + "\"}");
            return;
        } catch (Exception error) {
            rollbackListenerSwap(listenerSwap);
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"config_reload_failed\",\"message\":\""
                    + jsonEscape(error.getMessage() == null ? "无法重新加载配置" : error.getMessage()) + "\"}");
            return;
        }

        log("Web 管理后台已重新加载配置：来源=%s，变更=%s，监听热切换=%s",
                source(exchange), changes, listenerSwap != null ? "成功" : "无需切换");
        try {
            sendJson(exchange, 200, configJsonWithMessage(listenerSwap != null
                    ? "配置已从 YAML 重新加载；监听端口已热切换"
                    : "配置已从 YAML 重新加载并立即生效"));
        } finally {
            retireListener(listenerSwap);
        }
    }

    private void handleRegenerateAccess(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_ADMIN_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        String json = new String(body, StandardCharsets.UTF_8).trim();
        String requestedToken = jsonString(json, "admin_token");
        if (requestedToken == null) requestedToken = jsonString(json, "admin_password");
        if (!isValidAdminToken(requestedToken)) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_config\",\"message\":\""
                    + jsonEscape("重新生成时必须提交 6-256 位新管理令牌；服务不会在 API 响应中返回令牌")
                    + "\"}");
            return;
        }
        String requestedPath = jsonString(json, "admin_path");
        try {
            String changes;
            synchronized (configUpdateLock) {
                Config previous = config.copy();
                Config candidate = previous.copy();
                candidate.adminPath = requestedPath == null || requestedPath.isBlank()
                        ? generateAdminPath()
                        : requestedPath.trim();
                candidate.setAdminTokenFromPlaintext(requestedToken.trim());
                candidate.validate();
                candidate.save();
                candidate.configFileLoaded = true;
                changes = describeConfigChanges(previous, candidate);
                config.apply(candidate);
            }
            log("Web 管理入口和令牌已重新生成：来源=%s，变更=%s", source(exchange), changes);
            sendJson(exchange, 200, "{\"ok\":true,\"admin_path\":\"" + jsonEscape(config.adminPath)
                    + "\",\"admin_token_configured\":true}");
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_config\",\"message\":\""
                    + jsonEscape(error.getMessage() == null ? "管理入口或令牌无效" : error.getMessage()) + "\"}");
        } catch (IOException error) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"config_save_failed\"}");
        }
    }

    private String configJson() {
        return configJsonWithMessage(null);
    }

    private String configJsonWithMessage(String message) {
        return configJsonWithMessage(message, false);
    }

    private String configJsonWithMessage(String message, boolean adminTokenChanged) {
        StringBuilder json = new StringBuilder("{\"ok\":true,\"config\":{");
        appendJsonField(json, "listen", config.listen).append(',');
        appendJsonField(json, "key", config.key).append(',');
        appendJsonField(json, "default_broadcast", config.defaultBroadcast).append(',');
        json.append("\"default_port\":").append(config.defaultPort).append(',');
        json.append("\"cooldown_seconds\":").append(config.cooldownSeconds).append(',');
        json.append("\"allow_macs\":[");
        boolean first = true;
        for (String mac : config.allowMacs) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(mac)).append('\"');
        }
        json.append("],");
        appendJsonField(json, "admin_path", config.adminPath).append(',');
        json.append("\"admin_token_configured\":").append(isValidAdminTokenVerifier(config.adminTokenVerifier)).append(',');
        json.append("\"docker_environment\":").append(DOCKER_ENVIRONMENT);
        json.append("},\"runtime\":{\"active_listen\":\"")
                .append(jsonEscape(activeListen)).append("\",\"restart_required\":")
                .append(!activeListen.equals(config.listen)).append("},\"config_path\":\"")
                .append(jsonEscape(config.configPath.toString())).append("\"");
        json.append(",\"environment_overrides\":").append(jsonStringArray(config.environmentOverrides));
        if (message != null) {
            json.append(",\"message\":\"").append(jsonEscape(message)).append('\"');
        }
        if (adminTokenChanged) json.append(",\"admin_token_changed\":true");
        return json.append('}').toString();
    }

    private static StringBuilder appendJsonField(StringBuilder json, String name, String value) {
        return json.append('\"').append(jsonEscape(name)).append("\":\"").append(jsonEscape(value == null ? "" : value)).append('\"');
    }

    private static String jsonStringArray(Set<String> values) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(jsonEscape(value)).append('\"');
        }
        return json.append(']').toString();
    }

    private void handleAdminLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        LocalDate from;
        LocalDate to;
        try {
            String date = query.get("date");
            from = parseOptionalDate(date != null ? date : firstNonBlank(query.get("from"), query.get("start_date")));
            to = parseOptionalDate(date != null ? date : firstNonBlank(query.get("to"), query.get("end_date")));
        } catch (DateTimeParseException error) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_date\",\"message\":\"日期格式应为 YYYY-MM-DD\"}");
            return;
        }
        if (from != null && to != null && from.isAfter(to)) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_date_range\"}");
            return;
        }
        int limit = parseLimit(query.get("limit"));
        int offset = parseOffset(query.get("offset"));
        String eventFilter = query.getOrDefault("event", "").trim();
        String textFilter = firstNonBlank(query.get("q"), query.get("keyword"));
        if (textFilter == null) textFilter = "";

        int retainedLimit = Math.min(MAX_LOG_RESULTS, offset + limit);
        ArrayDeque<LogEntry> latestEntries = new ArrayDeque<>();
        int total = 0;
        if (Files.exists(LOG_FILE)) {
            try (BufferedReader reader = Files.newBufferedReader(LOG_FILE, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LogEntry entry = LogEntry.parse(line);
                    if (entry == null) continue;
                    LocalDate entryDate = entry.date();
                    if (from != null && entryDate.isBefore(from)) continue;
                    if (to != null && entryDate.isAfter(to)) continue;
                    if (!matchesEventFilter(entry, eventFilter)) continue;
                    if (!textFilter.isBlank()
                            && !entry.line.toLowerCase(Locale.ROOT).contains(textFilter.toLowerCase(Locale.ROOT))) continue;
                    total++;
                    if (retainedLimit > 0) {
                        latestEntries.addLast(entry);
                        while (latestEntries.size() > retainedLimit) latestEntries.removeFirst();
                    }
                }
            }
        }

        List<LogEntry> retained = new ArrayList<>(latestEntries);
        List<LogEntry> entries = new ArrayList<>();
        for (int index = retained.size() - 1 - offset; index >= 0 && entries.size() < limit; index--) {
            entries.add(retained.get(index));
        }
        boolean hasMore = offset + entries.size() < total
                && offset + entries.size() < MAX_LOG_RESULTS;
        StringBuilder json = new StringBuilder("{\"ok\":true,\"total\":").append(total)
                .append(",\"offset\":").append(offset)
                .append(",\"returned\":").append(entries.size())
                .append(",\"has_more\":").append(hasMore).append(",\"logs\":[");
        boolean first = true;
        for (LogEntry entry : entries) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"timestamp\":\"").append(jsonEscape(entry.timestamp)).append("\",\"event\":\"")
                    .append(jsonEscape(entry.event)).append("\",\"message\":\"").append(jsonEscape(entry.message))
                    .append("\",\"line\":\"").append(jsonEscape(entry.line)).append("\"}");
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private static LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value.trim(), LOG_DATE_FORMAT);
    }

    private static int parseLimit(String value) {
        if (value == null || value.isBlank()) return 50;
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(1, Math.min(MAX_LOG_RESULTS, parsed));
        } catch (NumberFormatException ignored) {
            return 50;
        }
    }

    private static int parseOffset(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Math.min(MAX_LOG_RESULTS, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean matchesEventFilter(LogEntry entry, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String normalized = filter.trim().toLowerCase(Locale.ROOT);
        String value = (entry.event + " " + entry.message).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "success" -> value.contains("成功") || value.contains("已发送")
                    || value.contains("success") || value.contains("正常");
            case "failure" -> value.contains("失败") || value.contains("错误")
                    || value.contains("failure") || value.contains("error");
            case "reject" -> value.contains("拒绝") || value.contains("reject")
                    || value.contains("denied");
            case "service" -> value.contains("服务") || value.contains("启动")
                    || value.contains("停止") || value.contains("配置") || value.contains("service");
            default -> value.contains(normalized);
        };
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : rawQuery.split("&")) {
            if (item.isBlank()) continue;
            int separator = item.indexOf('=');
            String rawKey = separator >= 0 ? item.substring(0, separator) : item;
            String rawValue = separator >= 0 ? item.substring(separator + 1) : "";
            try {
                result.put(URLDecoder.decode(rawKey, StandardCharsets.UTF_8), URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed query pairs; the remaining filters are still useful.
            }
        }
        return result;
    }

    private static byte[] readLimitedBody(HttpExchange exchange, int maximum) throws IOException, PayloadTooLargeException {
        long contentLength = exchange.getRequestHeaders().getFirst("Content-Length") == null
                ? -1
                : parseLongHeader(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (contentLength > maximum) throw new PayloadTooLargeException();
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        int total = 0;
        try (var input = exchange.getRequestBody()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (total > maximum - read) throw new PayloadTooLargeException();
                output.write(buffer, 0, read);
                total += read;
            }
        }
        return output.toByteArray();
    }

    private static long parseLongHeader(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static final class PayloadTooLargeException extends Exception {
    }

    private static final class ListenerSwap {
        final HttpServer previous;
        final HttpServer replacement;
        final String listen;

        private ListenerSwap(HttpServer previous, HttpServer replacement, String listen) {
            this.previous = previous;
            this.replacement = replacement;
            this.listen = listen;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        String requestSource = source(exchange);
        String requestMethod = exchange.getRequestMethod();
        log("收到连接测试请求：方法=%s，来源=%s", requestMethod, requestSource);
        if (!"GET".equalsIgnoreCase(requestMethod)) {
            log("连接测试失败：原因=请求方法不允许，方法=%s，来源=%s", requestMethod, requestSource);
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_PROXY_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            log("连接测试失败：原因=请求体过大，来源=%s", requestSource);
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        AuthResult auth = verifyAuth(exchange.getRequestHeaders(), body);
        if (!auth.ok) {
            log("连接测试失败：原因=%s，来源=%s", authErrorMessage(auth.error), requestSource);
            sendJson(exchange, auth.status, "{\"ok\":false,\"error\":\"" + jsonEscape(auth.error) + "\"}");
            return;
        }
        log("连接测试成功：来源=%s", requestSource);
        sendJson(exchange, 200, "{\"ok\":true,\"name\":\"WOL Proxy\",\"version\":\"" + VERSION
                + "\",\"cooldown_seconds\":" + config.cooldownSeconds + "}");
    }

    private void handleWake(HttpExchange exchange) throws IOException {
        String requestSource = source(exchange);
        String requestMethod = exchange.getRequestMethod();
        log("收到唤醒请求：方法=%s，来源=%s", requestMethod, requestSource);
        if (!"POST".equalsIgnoreCase(requestMethod)) {
            log("唤醒已拒绝：原因=请求方法不允许，方法=%s，来源=%s", requestMethod, requestSource);
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        wakeRequestCount.incrementAndGet();
        byte[] body;
        try {
            body = readLimitedBody(exchange, MAX_PROXY_BODY_BYTES);
        } catch (PayloadTooLargeException error) {
            log("唤醒已拒绝：原因=请求体过大，来源=%s", requestSource);
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"payload_too_large\"}");
            return;
        }
        AuthResult auth = verifyAuth(exchange.getRequestHeaders(), body);
        if (!auth.ok) {
            log("唤醒已拒绝：原因=%s，来源=%s", authErrorMessage(auth.error), requestSource);
            sendJson(exchange, auth.status, "{\"ok\":false,\"error\":\"" + jsonEscape(auth.error) + "\"}");
            return;
        }

        String bodyText = new String(body, StandardCharsets.UTF_8);
        String macText = jsonString(bodyText, "mac");
        String addressText = jsonString(bodyText, "address");
        Integer portValue = jsonInt(bodyText, "port");

        if (macText == null || !MAC_PATTERN.matcher(macText.trim()).matches()) {
            log("唤醒已拒绝：原因=MAC地址格式错误，MAC=%s，来源=%s",
                    macText == null ? "未提供" : macText,
                    requestSource);
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_mac\"}");
            return;
        }

        String mac = normalizeMac(macText);
        if (!config.allowMacs.isEmpty() && !config.allowMacs.contains(mac)) {
            log("唤醒已拒绝：原因=MAC不在白名单，MAC=%s，来源=%s", mac, requestSource);
            sendJson(exchange, 403, "{\"ok\":false,\"error\":\"mac_not_allowed\"}");
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = config.cooldownSeconds * 1000L;
        Long last = lastWakeMillis.get(mac);
        if (last != null && now - last < cooldownMillis) {
            long retryAfter = Math.max(1, (cooldownMillis - (now - last) + 999) / 1000);
            log("唤醒已拒绝：原因=冷却中，MAC=%s，剩余=%d秒，来源=%s", mac, retryAfter, requestSource);
            sendJson(exchange, 429, "{\"ok\":false,\"error\":\"cooldown\",\"retry_after\":" + retryAfter + "}");
            return;
        }

        int port = portValue == null ? config.defaultPort : portValue;
        if (port < 1 || port > 65535) {
            log("唤醒已拒绝：原因=UDP端口无效，端口=%d，MAC=%s，来源=%s", port, mac, requestSource);
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_port\"}");
            return;
        }

        String inputAddress = addressText == null ? "" : addressText.trim();
        String target = resolveBroadcastTarget(inputAddress.isBlank() ? config.defaultBroadcast : inputAddress);
        if (target == null) {
            log("唤醒已拒绝：原因=IP地址或广播地址无效，地址=%s，MAC=%s，来源=%s",
                    inputAddress.isBlank() ? config.defaultBroadcast : inputAddress,
                    mac,
                    requestSource);
            sendJson(exchange, 400, "{\"ok\":false,\"error\":\"invalid_address\"}");
            return;
        }

        lastWakeMillis.put(mac, now);
        try {
            sendWakePacket(mac, target, port);
            log("唤醒已发送：MAC=%s，目标地址=%s，端口=%d，来源=%s", mac, target, port, requestSource);
            sendJson(exchange, 200, "{\"ok\":true,\"target\":\"" + target + "\",\"port\":" + port + "}");
        } catch (Exception e) {
            log("唤醒发送失败：MAC=%s，原因=%s，来源=%s", mac, sendErrorMessage(e), requestSource);
            sendJson(exchange, 500, "{\"ok\":false,\"error\":\"send_failed\"}");
        }
    }

    private AuthResult verifyAuth(Headers headers, byte[] body) {
        cleanupNonces();

        String timestampText = firstHeader(headers, "X-WOL-Timestamp");
        String nonce = firstHeader(headers, "X-WOL-Nonce");
        String signature = firstHeader(headers, "X-WOL-Signature");
        if (timestampText == null || nonce == null || signature == null) {
            return AuthResult.fail(401, "missing_signature_headers");
        }
        if (nonce.isBlank() || nonce.length() > 128) {
            return AuthResult.fail(401, "invalid_nonce");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText);
        } catch (NumberFormatException e) {
            return AuthResult.fail(401, "invalid_timestamp");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > 60) {
            return AuthResult.fail(401, "timestamp_expired");
        }

        String payload = timestampText + "\n" + nonce + "\n" + new String(body, StandardCharsets.UTF_8);
        String expected = hmacSha256Hex(config.key, payload);
        if (!constantEquals(expected, signature.toLowerCase(Locale.ROOT))) {
            return AuthResult.fail(401, "bad_signature");
        }
        if (nonceCache.putIfAbsent(nonce, now) != null) {
            return AuthResult.fail(401, "nonce_replayed");
        }
        return AuthResult.OK;
    }

    private void cleanupNonces() {
        long expireBefore = Instant.now().getEpochSecond() - 120;
        nonceCache.entrySet().removeIf(entry -> entry.getValue() < expireBefore);
    }

    private static String firstHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }

    private static String hmacSha256Hex(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(String.format("%02x", value & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成请求签名", e);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void sendWakePacket(String normalizedMac, String target, int port) throws IOException {
        byte[] macBytes = parseMac(normalizedMac);
        byte[] packetBytes = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) {
            packetBytes[i] = (byte) 0xff;
        }
        for (int i = 0; i < 16; i++) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * macBytes.length, macBytes.length);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket packet = new DatagramPacket(
                    packetBytes,
                    packetBytes.length,
                    InetAddress.getByName(target),
                    port
            );
            socket.send(packet);
        }
    }

    private static byte[] parseMac(String normalizedMac) {
        String clean = normalizedMac.replace(":", "");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String normalizeMac(String value) {
        if (value == null || !MAC_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("MAC 地址格式无效：" + (value == null ? "未提供" : value));
        }
        String clean = value.trim().replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            parts.add(clean.substring(i * 2, i * 2 + 2));
        }
        return String.join(":", parts);
    }

    private static String resolveBroadcastTarget(String address) {
        String value = address.trim();
        int[] octets = parseIpv4(value);
        if (octets == null) return null;
        if ("255.255.255.255".equals(value)) return value;
        return octets[0] + "." + octets[1] + "." + octets[2] + ".255";
    }

    private static int[] parseIpv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isBlank()) return null;
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) return null;
        }
        return octets;
    }

    private static String jsonString(String json, String name) {
        Matcher matcher = Pattern.compile(String.format(JSON_STRING_TEMPLATE.pattern(), Pattern.quote(name))).matcher(json);
        return matcher.find() ? unescapeJson(matcher.group(1)) : null;
    }

    private static boolean hasJsonProperty(String json, String name) {
        return Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:").matcher(json).find();
    }

    private static List<String> jsonStringArray(String json, String name) {
        Matcher property = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\[").matcher(json);
        if (!property.find()) return null;

        List<String> values = new ArrayList<>();
        int index = property.end();
        while (true) {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
            if (index >= json.length()) throw new IllegalArgumentException(name + " 数组未结束");
            if (json.charAt(index) == ']') return values;
            if (json.charAt(index) != '"') throw new IllegalArgumentException(name + " 必须是字符串数组");
            index++;

            StringBuilder encoded = new StringBuilder();
            boolean closed = false;
            while (index < json.length()) {
                char current = json.charAt(index++);
                if (current == '"') {
                    closed = true;
                    break;
                }
                if (current == '\\') {
                    if (index >= json.length()) throw new IllegalArgumentException(name + " 包含无效转义");
                    encoded.append(current).append(json.charAt(index++));
                } else {
                    encoded.append(current);
                }
            }
            if (!closed) throw new IllegalArgumentException(name + " 数组未结束");
            values.add(unescapeJson(encoded.toString()));

            while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
            if (index >= json.length()) throw new IllegalArgumentException(name + " 数组未结束");
            char separator = json.charAt(index++);
            if (separator == ']') return values;
            if (separator != ',') throw new IllegalArgumentException(name + " 数组格式无效");
        }
    }

    private static Integer jsonInt(String json, String name) {
        Matcher matcher = Pattern.compile(String.format(JSON_INT_TEMPLATE.pattern(), Pattern.quote(name))).matcher(json);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unescapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                result.append(current);
                continue;
            }
            if (++index >= value.length()) throw new IllegalArgumentException("JSON 字符串包含无效转义");
            char escaped = value.charAt(index);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) throw new IllegalArgumentException("JSON Unicode 转义无效");
                    String hex = value.substring(index + 1, index + 5);
                    try {
                        result.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("JSON Unicode 转义无效");
                    }
                    index += 4;
                }
                default -> throw new IllegalArgumentException("JSON 字符串包含无效转义");
            }
        }
        return result.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    else result.append(current);
                }
            }
        }
        return result.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendDownload(HttpExchange exchange, String contentType, String fileName, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean sendAdminResource(HttpExchange exchange, String name, String contentType) throws IOException {
        byte[] bytes;
        try (InputStream input = Main.class.getResourceAsStream("/admin/" + name)) {
            if (input == null) return false;
            bytes = input.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; "
                        + "img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return true;
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void applySecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("Pragma", "no-cache");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    /** A dependency-free, responsive management page for Windows, Linux and Docker. */
    private static String adminPageHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
                  <meta name="color-scheme" content="light dark">
                  <title>WOL Proxy 管理后台</title>
                  <style>
                    :root { color-scheme: light dark; --bg:#f3f6fa; --panel:#fff; --text:#172033; --muted:#667085; --line:#dce3ed; --primary:#1464d2; --danger:#b42318; }
                    @media (prefers-color-scheme: dark) { :root { --bg:#111827; --panel:#1f2937; --text:#f3f4f6; --muted:#aab4c4; --line:#3b4758; --primary:#76a9fa; --danger:#f97066; } }
                    * { box-sizing:border-box; }
                    body { margin:0; background:var(--bg); color:var(--text); font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
                    main { width:min(980px,100%); margin:0 auto; padding:18px 14px 48px; }
                    header { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:16px; }
                    h1 { margin:0; font-size:23px; letter-spacing:0; } h2 { margin:0 0 12px; font-size:18px; }
                    .sub { color:var(--muted); font-size:13px; margin-top:3px; overflow-wrap:anywhere; }
                    .panel { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:16px; margin:12px 0; box-shadow:0 1px 2px #0000000d; }
                    .grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
                    label { display:flex; flex-direction:column; gap:5px; color:var(--muted); font-size:13px; }
                    input,textarea,button,select { font:inherit; } input,textarea,select { width:100%; min-height:40px; padding:8px 10px; color:var(--text); background:transparent; border:1px solid var(--line); border-radius:6px; }
                    textarea { min-height:78px; resize:vertical; } input:focus,textarea:focus,select:focus { outline:2px solid color-mix(in srgb,var(--primary) 30%,transparent); border-color:var(--primary); }
                    .actions { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:14px; }
                    button { min-height:40px; border:1px solid var(--line); border-radius:6px; padding:7px 13px; color:var(--text); background:transparent; cursor:pointer; }
                    button.primary { color:#fff; background:var(--primary); border-color:var(--primary); } button.danger { color:var(--danger); }
                    button:disabled { opacity:.55; cursor:not-allowed; } .status { min-height:24px; color:var(--muted); margin:8px 0; overflow-wrap:anywhere; }
                    .status.ok { color:#087443; } .status.error { color:var(--danger); }
                    .log-tools { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; align-items:end; }
                    .log-wrap { overflow:auto; border:1px solid var(--line); border-radius:6px; margin-top:12px; max-height:55vh; }
                    table { border-collapse:collapse; width:100%; min-width:650px; } th,td { padding:8px 9px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; } th { color:var(--muted); font-weight:600; position:sticky; top:0; background:var(--panel); } td.time { white-space:nowrap; color:var(--muted); } td.event { white-space:nowrap; }
                    .hidden { display:none !important; } .docker-warning { color:var(--danger); font-weight:700; } .token-row { display:flex; gap:8px; align-items:flex-end; } .token-row label { flex:1; }
                    @media (max-width:640px) { main { padding:12px 10px 36px; } header { display:block; } header button { margin-top:10px; } .grid,.log-tools { grid-template-columns:1fr; } .panel { padding:13px; } h1 { font-size:21px; } }
                  </style>
                </head>
                <body>
                  <main>
                    <header><div><h1>WOL Proxy 管理后台</h1><div id="endpoint" class="sub"></div></div><button id="logout">清除令牌</button></header>
                    <section id="login" class="panel">
                      <h2>管理认证</h2><div class="sub">请输入启动时显示的管理令牌。令牌只保存在当前浏览器。</div>
                      <div class="token-row"><label>管理令牌<input id="token" type="password" minlength="6" maxlength="256" autocomplete="off" placeholder="输入管理令牌"></label><button id="loginBtn" class="primary">进入</button></div>
                      <div id="loginStatus" class="status"></div>
                    </section>
                    <div id="app" class="hidden">
                      <section class="panel"><h2>代理配置</h2>
                        <div class="grid">
                          <label>监听端口<input id="listen" type="number" min="1" max="65535" placeholder="14250"><span id="dockerHint" class="sub docker-warning hidden">当前运行在 Docker 中。修改端口后须同步修改 TCP 端口映射；WOL 广播请使用 host 网络，请注意！</span></label>
                          <label>代理连接 Key<input id="key" type="password" autocomplete="off"></label>
                          <label>默认广播地址<input id="broadcast" placeholder="255.255.255.255"></label>
                          <label>默认 UDP 端口<input id="port" type="number" min="1" max="65535"></label>
                          <label>冷却时间（秒）<input id="cooldown" type="number" min="0" max="86400"></label>
                          <label>管理入口<input id="adminPath" minlength="4" maxlength="64" pattern="[A-Za-z]{4,64}" required></label>
                          <label>管理令牌<input id="adminToken" type="password" minlength="6" maxlength="256" pattern="[\\x21-\\x7E]{6,256}" autocomplete="new-password" placeholder="已安全存储，留空保持"></label>
                        </div>
                          <label style="margin-top:12px">MAC 白名单（每行一个；留空表示允许全部）<textarea id="allowMacs" placeholder="AA:BB:CC:DD:EE:FF"></textarea></label>
                        <div class="actions">
                          <select id="configFormat" aria-label="配置导入导出格式" style="width:auto"><option value="yaml" selected>YAML</option><option value="json">JSON</option></select>
                          <input id="importFile" class="hidden" type="file" accept=".yml,.yaml,.json,text/yaml,text/x-yaml,application/yaml,application/x-yaml,application/json">
                          <button id="importConfig">导入配置</button><button id="exportConfig">导出配置</button>
                          <button id="save" class="primary">保存配置</button><button id="reload">从 YAML 重新加载</button><button id="regenerate" class="danger">重新生成入口和令牌</button>
                        </div>
                        <div id="configStatus" class="status"></div>
                      </section>
                      <section class="panel"><h2>运行状态</h2><div id="runtime" class="status">加载中...</div><button id="refreshStatus">刷新状态</button></section>
                      <section class="panel"><h2>运行日志</h2>
                        <div class="log-tools">
                          <label>指定日期<input id="date" type="date"></label><label>开始日期<input id="from" type="date"></label><label>结束日期<input id="to" type="date"></label><label>关键词<input id="query" placeholder="按内容筛选"></label><label>每页条目<select id="pageSize"><option value="50" selected>50</option><option value="80">80</option><option value="100">100</option><option value="150">150</option><option value="200">200</option></select></label>
                        </div>
                        <div class="actions"><button id="loadLogs" class="primary">查询日志</button><label style="flex-direction:row;align-items:center"><input id="autoLogs" type="checkbox" checked style="width:auto;min-height:auto">自动刷新</label><span id="logStatus" class="status"></span></div>
                        <div class="log-wrap"><table><thead><tr><th>时间</th><th>事件</th><th>内容</th></tr></thead><tbody id="logs"></tbody></table></div>
                        <div class="actions"><button id="prev" disabled>上一页</button><span id="pageStatus" class="status">第 1 / 1 页</span><button id="next" disabled>下一页</button></div>
                      </section>
                    </div>
                  </main>
                  <script>
                    (() => {
                      const base = location.pathname.replace(/\\/$/, '');
                      const $ = id => document.getElementById(id);
                      const saved = localStorage.getItem('wolProxyAdminToken') || '';
                      $('endpoint').textContent = location.origin + base + '/';
                      $('token').value = saved;
                      const ADMIN_PATH_RE=/^[A-Za-z]{4,64}$/;
                      const ADMIN_TOKEN_RE=/^[\\x21-\\x7E]{6,256}$/;
                      const setStatus = (node, text, ok=false) => { node.textContent = text || ''; node.className = 'status' + (ok ? ' ok' : text ? ' error' : ''); };
                      async function api(path, options={}) {
                        const requestOptions=Object.assign({},options);
                        const responseType=requestOptions.responseType||'json'; delete requestOptions.responseType;
                        const headers=new Headers(requestOptions.headers||{});
                        if(!headers.has('Accept')) headers.set('Accept','application/json');
                        const token = localStorage.getItem('wolProxyAdminToken') || '';
                        if(token) headers.set('X-WOL-Admin-Token',token);
                        if(requestOptions.body&&!headers.has('Content-Type')) headers.set('Content-Type','application/json');
                        const response=await fetch(base+path,Object.assign({},requestOptions,{headers}));
                        const responseText=await response.text();
                        let data=responseType==='text'&&response.ok?responseText:null;
                        if(data===null&&responseText){try{data=JSON.parse(responseText);}catch(_){data=responseText;}}
                        if (!response.ok) { const error = new Error(data && (data.message || data.error) || ('HTTP ' + response.status)); error.status = response.status; throw error; }
                        return data;
                      }
                      function showApp(show) { $('app').classList.toggle('hidden', !show); $('login').classList.toggle('hidden', show); }
                      function listenPort(value) { const raw=String(value||'').trim(); if (/^\\d+$/.test(raw)) return raw; const match=raw.match(/:(\\d+)$/); return match ? match[1] : raw; }
                      function listenValue(value) { return Number(listenPort(value)); }
                      let currentAdminPath='';
                      function fill(c) { $('listen').value=listenPort(c.listen); $('key').value=c.key||''; $('broadcast').value=c.default_broadcast||''; $('port').value=c.default_port??9; $('cooldown').value=c.cooldown_seconds??5; $('adminPath').value=c.admin_path||''; $('adminToken').value=''; $('adminToken').dataset.configured=String(Boolean(c.admin_token_configured)); $('allowMacs').value=(c.allow_macs||[]).join('\\n'); $('dockerHint').classList.toggle('hidden',!c.docker_environment); currentAdminPath=String(c.admin_path||'').replace(/^\\/+|\\/+$/g,''); }
                      function rememberToken(value){if(value){localStorage.setItem('wolProxyAdminToken',value);}}
                      function navigateAfterApply(data,previousPath){
                        const c=data&&data.config||{}; const runtime=data&&data.runtime||{};
                        const newPath=String(c.admin_path||data&&data.admin_path||$('adminPath').value||previousPath||'').replace(/^\\/+|\\/+$/g,'');
                        const activePort=listenPort(runtime.active_listen||runtime.activeListen||data&&data.active_listen||'');
                        const currentPort=location.port||(location.protocol==='https:'?'443':'80');
                        const changed=Boolean(newPath&&previousPath&&newPath!==previousPath)||Boolean(/^\\d+$/.test(activePort)&&activePort!==currentPort);
                        currentAdminPath=newPath||currentAdminPath; if(!changed||!newPath)return false;
                        const target=new URL(location.href); if(/^\\d+$/.test(activePort))target.port=activePort;
                        target.pathname='/'+encodeURIComponent(newPath)+'/'; target.search=''; target.hash='';
                        setTimeout(()=>location.assign(target.toString()),800); return true;
                      }
                      function validateConfig(body){
                        if(!ADMIN_PATH_RE.test(body.admin_path||''))throw new Error('管理入口须为 4-64 位大小写字母');
                        if(body.admin_token&&!ADMIN_TOKEN_RE.test(body.admin_token))throw new Error('管理令牌须为 6-256 位可见字符，不能包含空格');
                        if(!body.admin_token&&$('adminToken').dataset.configured!=='true')throw new Error('请输入管理令牌');
                      }
                      function randomCredential(length){const alphabet="abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ",values=new Uint32Array(length);crypto.getRandomValues(values);return Array.from(values,v=>alphabet[v%alphabet.length]).join('');}
                      function detectFormat(file,content){const name=String(file&&file.name||'').toLowerCase(),type=String(file&&file.type||'').toLowerCase();if(name.endsWith('.json')||type.includes('json'))return'json';if(/\\.ya?ml$/.test(name)||type.includes('yaml')||type.includes('yml'))return'yaml';return String(content||'').trimStart().startsWith('{')?'json':'yaml';}
                      async function loadConfig() { try { const data=await api('/api/config'); fill(data.config); showApp(true); setStatus($('loginStatus'),''); await loadStatus(); } catch (e) { showApp(false); if (e.status===401) setStatus($('loginStatus'),'令牌无效或未填写'); else setStatus($('loginStatus'),e.message); } }
                      async function loadStatus() { try { const d=await api('/api/status'); $('runtime').textContent='版本 '+d.version+'；运行 '+d.uptime_seconds+' 秒；当前监听 '+d.active_listen+(d.restart_required?'；监听配置未能应用':'')+'；配置：'+d.config_path+'；日志：'+d.log_path; } catch(e) { $('runtime').textContent=e.message; } }
                      let logPage=0, logPageSize=50, logHasMore=false;
                      async function loadLogs(reset=true) {
                        if (reset) logPage=0;
                        const params=new URLSearchParams();
                        if($('date').value) params.set('date',$('date').value); else { if($('from').value) params.set('from',$('from').value); if($('to').value) params.set('to',$('to').value); }
                        if($('query').value) params.set('q',$('query').value);
                        params.set('offset',String(logPage*logPageSize)); params.set('limit',String(logPageSize));
                        try {
                          const d=await api('/api/logs?'+params); $('logs').innerHTML='';
                          for(const row of d.logs||[]) { const tr=document.createElement('tr'); for(const value of [row.timestamp,row.event,row.message]) { const td=document.createElement('td'); td.textContent=value; if(value===row.timestamp) td.className='time'; if(value===row.event) td.className='event'; tr.appendChild(td); } $('logs').appendChild(tr); }
                          logHasMore=Boolean(d.has_more); const totalPages=Math.max(1,Math.ceil(Number(d.total||0)/logPageSize));
                          $('prev').disabled=logPage===0; $('next').disabled=!logHasMore; $('pageStatus').textContent='第 '+(logPage+1)+' / '+totalPages+' 页';
                          $('logStatus').className='status ok'; $('logStatus').textContent='共匹配 '+d.total+' 条，本页 '+d.returned+' 条';
                        } catch(e) { setStatus($('logStatus'),e.message); }
                      }
                      $('loginBtn').onclick=async()=>{ const value=$('token').value.trim(); if(!value){setStatus($('loginStatus'),'请输入管理令牌');return;} localStorage.setItem('wolProxyAdminToken',value); await loadConfig(); };
                      $('logout').onclick=()=>{localStorage.removeItem('wolProxyAdminToken');showApp(false);$('token').value='';};
                      $('save').onclick=async()=>{
                        const allow=$('allowMacs').value.split(/\\r?\\n|,/).map(x=>x.trim()).filter(Boolean);
                        const body={listen:listenValue($('listen').value),key:$('key').value,default_broadcast:$('broadcast').value.trim(),default_port:Number($('port').value),cooldown_seconds:Number($('cooldown').value),allow_macs:allow,admin_path:$('adminPath').value.trim()};const submittedToken=$('adminToken').value.trim();if(submittedToken)body.admin_token=submittedToken;
                        const previousPath=currentAdminPath;
                        try{validateConfig(body);const d=await api('/api/config',{method:'PUT',body:JSON.stringify(body)});if(submittedToken)rememberToken(submittedToken);fill(d.config);setStatus($('configStatus'),d.message||'已保存',true);navigateAfterApply(d,previousPath);}catch(e){setStatus($('configStatus'),e.message);}
                      };
                      $('reload').onclick=async()=>{const previousPath=currentAdminPath;try{const d=await api('/api/reload',{method:'POST'});fill(d.config);setStatus($('configStatus'),d.message||'已重新加载',true);navigateAfterApply(d,previousPath);}catch(e){setStatus($('configStatus'),e.message);}};
                      $('exportConfig').onclick=async()=>{
                        const button=$('exportConfig'),format=$('configFormat').value==='json'?'json':'yaml';button.disabled=true;
                        try{const isJson=format==='json';const content=await api('/api/config/export?format='+format,{headers:{Accept:isJson?'application/json':'application/yaml'},responseType:'text'});const url=URL.createObjectURL(new Blob([content||''],{type:isJson?'application/json':'application/yaml'}));const link=document.createElement('a');link.href=url;link.download='wol-proxy-config.'+(isJson?'json':'yml');document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),0);setStatus($('configStatus'),'配置已导出',true);}catch(e){setStatus($('configStatus'),e.message);}finally{button.disabled=false;}
                      };
                      $('importConfig').onclick=()=>$('importFile').click();
                      $('importFile').onchange=async()=>{
                        const file=$('importFile').files&&$('importFile').files[0];if(!file)return;const button=$('importConfig'),previousPath=currentAdminPath;button.disabled=true;
                        try{if(file.size>64*1024)throw new Error('配置文件不能超过 64 KB');let content=await file.text();if(content.charCodeAt(0)===0xfeff)content=content.slice(1);const format=detectFormat(file,content);$('configFormat').value=format;const d=await api('/api/config/import?format='+format,{method:'POST',headers:{'Content-Type':format==='json'?'application/json;charset=UTF-8':'application/yaml;charset=UTF-8'},body:content});fill(d.config);if(d.admin_token_changed){localStorage.removeItem('wolProxyAdminToken');showApp(false);setStatus($('loginStatus'),'管理令牌已变更，请使用导入文件中的新令牌登录');return;}setStatus($('configStatus'),d.message||'配置已导入',true);navigateAfterApply(d,previousPath);}catch(e){setStatus($('configStatus'),e.message);}finally{button.disabled=false;$('importFile').value='';}
                      };
                      $('regenerate').onclick=async()=>{if(!confirm('重新生成后旧入口和令牌会立即失效，继续吗？'))return;const previousPath=currentAdminPath,newToken=randomCredential(24),newPath=randomCredential(8);try{const d=await api('/api/regenerate-access',{method:'POST',body:JSON.stringify({admin_path:newPath,admin_token:newToken})});$('adminPath').value=d.admin_path||newPath;$('adminToken').value=newToken;rememberToken(newToken);setStatus($('configStatus'),'入口和令牌已重新生成，请立即妥善保管新令牌',true);navigateAfterApply(d,previousPath);}catch(e){setStatus($('configStatus'),e.message);}};
                      $('refreshStatus').onclick=loadStatus; $('loadLogs').onclick=()=>loadLogs(true);
                      $('prev').onclick=()=>{if(logPage>0){logPage--;loadLogs(false);}};
                      $('next').onclick=()=>{if(logHasMore){logPage++;loadLogs(false);}};
                      $('pageSize').onchange=()=>{logPageSize=Number($('pageSize').value)||50;loadLogs(true);};
                      let autoLogTimer=setInterval(()=>{if($('autoLogs').checked&&localStorage.getItem('wolProxyAdminToken'))loadLogs(true);},15000);$('autoLogs').onchange=()=>{if($('autoLogs').checked)loadLogs(true);};
                      loadConfig();
                    })();
                  </script>
                </body></html>
                """;
    }

    private static String generateAdminPath() {
        return randomLettersString(ADMIN_PATH_LENGTH);
    }

    private static String generateAdminToken() {
        return randomMixedString(ADMIN_TOKEN_LENGTH);
    }

    private static String generateProxyKey() {
        return randomString(RANDOM_LETTERS + RANDOM_DIGITS, PROXY_KEY_LENGTH);
    }

    private static boolean isValidAdminToken(String value) {
        return value != null && ADMIN_TOKEN_PATTERN.matcher(value).matches();
    }

    private static boolean hasAdminTokenVerifierPrefix(String value) {
        return value != null && value.strip().startsWith(ADMIN_TOKEN_VERIFIER_SCHEME + "$");
    }

    private static String createAdminTokenVerifier(String token) {
        if (!isValidAdminToken(token)) {
            throw new IllegalArgumentException("admin_token 长度必须为 6-256 位，且只能使用可见的 ASCII 字母、数字或特殊符号");
        }
        byte[] salt = new byte[ADMIN_TOKEN_SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = deriveAdminTokenHash(token, salt, ADMIN_TOKEN_PBKDF2_ITERATIONS, ADMIN_TOKEN_HASH_BYTES);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return ADMIN_TOKEN_VERIFIER_SCHEME + "$" + ADMIN_TOKEN_VERIFIER_VERSION + "$"
                + ADMIN_TOKEN_PBKDF2_ITERATIONS + "$" + encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(hash);
    }

    private static boolean isValidAdminTokenVerifier(String value) {
        return parseAdminTokenVerifier(value) != null;
    }

    private static boolean verifyAdminToken(String token, String encodedVerifier) {
        if (!isValidAdminToken(token)) return false;
        AdminTokenVerifier verifier = parseAdminTokenVerifier(encodedVerifier);
        if (verifier == null) return false;
        byte[] actual = deriveAdminTokenHash(token, verifier.salt, verifier.iterations, verifier.hash.length);
        try {
            return MessageDigest.isEqual(verifier.hash, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static AdminTokenVerifier parseAdminTokenVerifier(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.strip().split("\\$", -1);
        if (parts.length != 5
                || !ADMIN_TOKEN_VERIFIER_SCHEME.equals(parts[0])
                || !ADMIN_TOKEN_VERIFIER_VERSION.equals(parts[1])) return null;
        try {
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < 100_000 || iterations > 1_000_000) return null;
            if (parts[3].isBlank() || parts[4].isBlank()
                    || !parts[3].matches("[A-Za-z0-9_-]+")
                    || !parts[4].matches("[A-Za-z0-9_-]+")) return null;
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[3]);
            byte[] hash = decoder.decode(parts[4]);
            if (salt.length < 16 || salt.length > 64 || hash.length < 32 || hash.length > 64) return null;
            return new AdminTokenVerifier(iterations, salt, hash);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static byte[] deriveAdminTokenHash(String token, byte[] salt, int iterations, int hashBytes) {
        char[] password = token.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, hashBytes * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("当前 Java 运行时不支持 PBKDF2WithHmacSHA256", error);
        } finally {
            keySpec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    private static final class AdminTokenVerifier {
        final int iterations;
        final byte[] salt;
        final byte[] hash;

        private AdminTokenVerifier(int iterations, byte[] salt, byte[] hash) {
            this.iterations = iterations;
            this.salt = salt;
            this.hash = hash;
        }
    }

    private static String randomMixedString(int length) {
        String alphabet = RANDOM_LETTERS + RANDOM_DIGITS + RANDOM_SPECIALS;
        char[] value = new char[length];
        // Ensure generated credentials demonstrate all three supported classes.
        value[0] = randomChar(RANDOM_LETTERS);
        value[1] = randomChar(RANDOM_DIGITS);
        value[2] = randomChar(RANDOM_SPECIALS);
        for (int index = 3; index < length; index++) value[index] = randomChar(alphabet);
        for (int index = length - 1; index > 0; index--) {
            int swap = SECURE_RANDOM.nextInt(index + 1);
            char current = value[index];
            value[index] = value[swap];
            value[swap] = current;
        }
        return new String(value);
    }

    private static String randomLettersString(int length) {
        return randomString(RANDOM_LETTERS, length);
    }

    private static String randomString(String alphabet, int length) {
        char[] value = new char[length];
        for (int index = 0; index < length; index++) value[index] = randomChar(alphabet);
        return new String(value);
    }

    private static char randomChar(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }

    private static InetSocketAddress parseListen(String listen) {
        String value = normalizeListenValue(listen);
        if (value.startsWith(":")) {
            return new InetSocketAddress(requireListenPort(value.substring(1), listen));
        }

        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1) {
            throw new IllegalArgumentException("监听地址格式不正确：" + listen);
        }
        String host = value.substring(0, split);
        int port = requireListenPort(value.substring(split + 1), listen);
        return new InetSocketAddress(host, port);
    }

    private static String normalizeListenValue(String listen) {
        String value = listen == null ? "" : listen.trim();
        if (value.matches("\\d+")) return ":" + value;
        return value;
    }

    private static String replaceListenPort(String listen, int port) {
        requireListenPort(String.valueOf(port), String.valueOf(port));
        String current = normalizeListenValue(listen);
        int split = current.lastIndexOf(':');
        String host = split >= 0 ? current.substring(0, split) : "";
        return host + ":" + port;
    }

    private static String adminBrowserUrl(Config config, InetSocketAddress listenAddress) {
        String host = listenAddress.getHostString();
        if (listenAddress.getAddress() != null && listenAddress.getAddress().isAnyLocalAddress()) {
            host = discoverLocalBrowserHost();
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + listenAddress.getPort() + "/" + config.adminPath + "/";
    }

    private static String discoverLocalBrowserHost() {
        try (DatagramSocket routeProbe = new DatagramSocket()) {
            routeProbe.connect(InetAddress.getByName("1.1.1.1"), 53);
            InetAddress localAddress = routeProbe.getLocalAddress();
            if (isUsableBrowserAddress(localAddress)) return localAddress.getHostAddress();
        } catch (IOException | RuntimeException ignored) {
        }

        String ipv6Fallback = null;
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                var addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!isUsableBrowserAddress(address)) continue;
                    if (address instanceof Inet4Address) return address.getHostAddress();
                    if (ipv6Fallback == null) ipv6Fallback = address.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return ipv6Fallback == null ? "127.0.0.1" : ipv6Fallback;
    }

    private static boolean isUsableBrowserAddress(InetAddress address) {
        return address != null
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress();
    }

    private static int requireListenPort(String value, String listen) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("监听端口必须在 1-65535 之间：" + listen);
        }
    }

    private static String source(HttpExchange exchange) {
        InetSocketAddress address = exchange.getRemoteAddress();
        return address == null ? "未知" : address.getAddress().getHostAddress();
    }

    private static String authErrorMessage(String error) {
        return switch (error) {
            case "missing_signature_headers" -> "缺少认证请求头";
            case "invalid_nonce" -> "随机请求标识无效";
            case "invalid_timestamp" -> "请求时间戳无效";
            case "timestamp_expired" -> "请求时间已过期，请检查两端系统时间";
            case "nonce_replayed" -> "检测到重复请求";
            case "bad_signature" -> "KEY不匹配或请求签名错误";
            default -> "认证信息无效";
        };
    }

    private static void log(String pattern, Object... args) {
        String line = formatLogLine(pattern, args);
        System.out.println(line);
        appendLog(line);
    }

    private static void logError(String pattern, Object... args) {
        String line = formatLogLine(pattern, args);
        System.err.println(line);
        appendLog(line);
    }

    private static String formatLogLine(String pattern, Object... args) {
        String message = String.format(Locale.ROOT, pattern, args);
        int separator = message.indexOf('：');
        String event;
        String detail;
        if (separator > 0) {
            event = message.substring(0, separator);
            detail = message.substring(separator + 1);
        } else if (message.contains("已启动")) {
            event = "服务启动";
            detail = message;
        } else if (message.contains("已停止")) {
            event = "服务停止";
            detail = message;
        } else {
            event = "运行信息";
            detail = message;
        }
        return "[" + LocalDateTime.now(LOG_OFFSET).format(LOG_TIME_FORMAT) + "][" + event + "]" + detail;
    }

    private static synchronized void appendLog(String line) {
        try {
            Path parent = LOG_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                    LOG_FILE,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    private static final class AuthResult {
        static final AuthResult OK = new AuthResult(true, 200, "");
        final boolean ok;
        final int status;
        final String error;

        private AuthResult(boolean ok, int status, String error) {
            this.ok = ok;
            this.status = status;
            this.error = error;
        }

        static AuthResult fail(int status, String error) {
            return new AuthResult(false, status, error);
        }
    }

    private static final class AuthFailures {
        private long windowStarted = 0;
        private int count = 0;
        private long blockedUntil = 0;

        synchronized boolean blocked() {
            long now = System.currentTimeMillis();
            return blockedUntil > now;
        }

        synchronized void recordFailure() {
            long now = System.currentTimeMillis();
            if (now - windowStarted >= 60_000L) {
                windowStarted = now;
                count = 0;
            }
            count++;
            if (count >= MAX_FAILED_AUTH_PER_MINUTE) {
                blockedUntil = now + 60_000L;
            }
        }

        synchronized void reset() {
            count = 0;
            blockedUntil = 0;
            windowStarted = System.currentTimeMillis();
        }
    }

    private static final class LogEntry {
        final String timestamp;
        final String event;
        final String message;
        final String line;

        private LogEntry(String timestamp, String event, String message, String line) {
            this.timestamp = timestamp;
            this.event = event;
            this.message = message;
            this.line = line;
        }

        static LogEntry parse(String line) {
            Matcher matcher = LOG_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) return null;
            try {
                LocalDateTime.parse(matcher.group(1), LOG_TIME_FORMAT);
            } catch (DateTimeParseException ignored) {
                return null;
            }
            return new LogEntry(matcher.group(1), matcher.group(2), matcher.group(3), line);
        }

        LocalDate date() {
            return LocalDate.parse(timestamp.substring(0, 10), LOG_DATE_FORMAT);
        }
    }

    private static final class AdminAccessPreparation {
        final boolean persistenceRequired;
        final boolean legacyTokenMigrated;
        final String generatedToken;

        private AdminAccessPreparation(boolean persistenceRequired, boolean legacyTokenMigrated,
                                       String generatedToken) {
            this.persistenceRequired = persistenceRequired;
            this.legacyTokenMigrated = legacyTokenMigrated;
            this.generatedToken = generatedToken;
        }
    }

    private static final class Config {
        volatile String listen = ":14250";
        volatile String key = "";
        volatile String defaultBroadcast = "255.255.255.255";
        volatile int defaultPort = 9;
        volatile int cooldownSeconds = 5;
        volatile Set<String> allowMacs = new LinkedHashSet<>();
        volatile Set<String> environmentOverrides = new LinkedHashSet<>();
        volatile String adminPath = "";
        /** Persisted credential material. The plaintext token is never retained here. */
        volatile String adminTokenVerifier = "";
        /** Plaintext supplied by a YAML document, held only until startup/import migration. */
        String pendingAdminToken = "";
        Path configPath = Path.of("config.yml").toAbsolutePath().normalize();
        boolean configFileLoaded = false;
        boolean adminTokenFieldPresent = false;

        static Config load(String[] args) throws IOException {
            Map<String, String> cli = parseArgs(args);
            boolean explicitConfig = cli.containsKey("config");
            Path requestedConfigPath = explicitConfig
                    ? Path.of(cli.get("config"))
                    : defaultConfigPath();
            Path configPath = resolveConfigPath(requestedConfigPath);
            Config config = new Config();
            config.configPath = configPath;
            if (!Files.exists(configPath) && !explicitConfig && isWindows()) {
                Path source = findWindowsConfigSource();
                if (source != null) {
                    Path parent = configPath.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(source, configPath, StandardCopyOption.REPLACE_EXISTING);
                    Path sourceToken = source.resolveSibling(source.getFileName() + ".admin-token");
                    Path targetToken = configPath.resolveSibling(configPath.getFileName() + ".admin-token");
                    if (!Files.isSymbolicLink(sourceToken) && Files.isRegularFile(sourceToken)) {
                        Files.copy(sourceToken, targetToken, StandardCopyOption.REPLACE_EXISTING);
                        restrictSecretFile(targetToken);
                    }
                    log("已创建配置文件：%s", configPath);
                }
            }
            if (Files.exists(configPath)) {
                if (!Files.isRegularFile(configPath)) {
                    throw new IOException("配置路径不是普通文件：" + configPath);
                }
                applyConfigFile(config, configPath);
                config.configFileLoaded = true;
            }
            applyEnv(config);
            if (cli.containsKey("listen")) config.listen = normalizeListenValue(cli.get("listen"));
            if (cli.containsKey("key")) config.key = cli.get("key");
            return config;
        }

        static Config loadFromPath(Path path) throws IOException {
            Config config = new Config();
            config.configPath = path.toAbsolutePath().normalize();
            if (Files.exists(config.configPath)) {
                applyConfigFile(config, config.configPath);
                config.configFileLoaded = true;
            }
            applyEnv(config);
            config.listen = normalizeListenValue(config.listen);
            return config;
        }

        String prepareProxyKeyForDockerStartup() {
            if (!DOCKER_ENVIRONMENT || (key != null && !key.isBlank())) return null;
            key = generateProxyKey();
            return key;
        }

        AdminAccessPreparation prepareAdminAccessForStartup() throws IOException {
            boolean persistenceRequired = false;
            boolean legacyTokenMigrated = false;
            String generatedToken = null;
            if (!ADMIN_PATH_PATTERN.matcher(adminPath == null ? "" : adminPath).matches()) {
                adminPath = generateAdminPath();
                persistenceRequired = true;
            }

            if (adminTokenFieldPresent) {
                String supplied = pendingAdminToken == null ? "" : pendingAdminToken.strip();
                if (supplied.isBlank()) {
                    generatedToken = generateAdminToken();
                    adminTokenVerifier = createAdminTokenVerifier(generatedToken);
                    persistenceRequired = true;
                } else if (isValidAdminTokenVerifier(supplied)) {
                    adminTokenVerifier = supplied;
                    persistenceRequired = true;
                } else if (hasAdminTokenVerifierPrefix(supplied)) {
                    throw new IllegalArgumentException("admin_token verifier 格式无效");
                } else if (isValidAdminToken(supplied)) {
                    adminTokenVerifier = createAdminTokenVerifier(supplied);
                    legacyTokenMigrated = true;
                    persistenceRequired = true;
                } else {
                    throw new IllegalArgumentException("admin_token 必须是有效令牌或 PBKDF2 verifier；如需轮换请使用空值");
                }
            } else {
                String stored = readAdminTokenSidecar();
                if (isValidAdminTokenVerifier(stored)) {
                    adminTokenVerifier = stored.strip();
                } else if (hasAdminTokenVerifierPrefix(stored)) {
                    generatedToken = generateAdminToken();
                    adminTokenVerifier = createAdminTokenVerifier(generatedToken);
                    persistenceRequired = true;
                } else if (isValidAdminToken(stored)) {
                    adminTokenVerifier = createAdminTokenVerifier(stored.strip());
                    legacyTokenMigrated = true;
                    persistenceRequired = true;
                } else {
                    generatedToken = generateAdminToken();
                    adminTokenVerifier = createAdminTokenVerifier(generatedToken);
                    persistenceRequired = true;
                }
            }
            pendingAdminToken = "";
            adminTokenFieldPresent = false;
            return new AdminAccessPreparation(persistenceRequired, legacyTokenMigrated, generatedToken);
        }

        private String readAdminTokenSidecar() throws IOException {
            Path path = adminTokenPath();
            if (!Files.exists(path)) return null;
            if (Files.isSymbolicLink(path)) {
                throw new IOException("管理令牌 sidecar 不能是符号链接");
            }
            if (!Files.isRegularFile(path)) return null;
            restrictSecretFile(path);
            String stored = Files.readString(path, StandardCharsets.UTF_8).strip();
            return stored.isBlank() ? null : stored;
        }

        Path adminTokenPath() {
            Path target = configPath.toAbsolutePath().normalize();
            return target.resolveSibling(target.getFileName() + ".admin-token");
        }

        void ensureAdminAccessFrom(Config fallback) {
            if (!ADMIN_PATH_PATTERN.matcher(adminPath == null ? "" : adminPath).matches()) {
                adminPath = fallback.adminPath;
            }
            // YAML reload never rotates credentials. Token changes use the
            // authenticated save/import endpoints or take effect next startup.
            adminTokenVerifier = fallback.adminTokenVerifier;
            pendingAdminToken = "";
            adminTokenFieldPresent = false;
            if (!isValidAdminTokenVerifier(adminTokenVerifier)) {
                throw new IllegalArgumentException("YAML 未配置有效的管理令牌 verifier");
            }
        }

        Config copy() {
            Config copy = new Config();
            copy.listen = listen;
            copy.key = key;
            copy.defaultBroadcast = defaultBroadcast;
            copy.defaultPort = defaultPort;
            copy.cooldownSeconds = cooldownSeconds;
            copy.allowMacs = new LinkedHashSet<>(allowMacs);
            copy.environmentOverrides = new LinkedHashSet<>(environmentOverrides);
            copy.adminPath = adminPath;
            copy.adminTokenVerifier = adminTokenVerifier;
            copy.configPath = configPath;
            copy.configFileLoaded = configFileLoaded;
            copy.adminTokenFieldPresent = false;
            copy.pendingAdminToken = "";
            return copy;
        }

        synchronized void apply(Config other) {
            listen = normalizeListenValue(other.listen);
            key = other.key;
            defaultBroadcast = other.defaultBroadcast;
            defaultPort = other.defaultPort;
            cooldownSeconds = other.cooldownSeconds;
            allowMacs = new LinkedHashSet<>(other.allowMacs);
            environmentOverrides = new LinkedHashSet<>(other.environmentOverrides);
            adminPath = other.adminPath;
            adminTokenVerifier = other.adminTokenVerifier;
            configFileLoaded = other.configFileLoaded;
            adminTokenFieldPresent = false;
            pendingAdminToken = "";
        }

        void applyJson(String json) {
            String trimmed = json == null ? "" : json.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new IllegalArgumentException("请求内容必须是 JSON 对象");
            }
            String value;
            value = jsonString(json, "listen");
            if (value != null) {
                listen = normalizeListenValue(value);
            } else {
                Integer listenPort = jsonInt(json, "listen");
                if (listenPort != null) listen = replaceListenPort(listen, listenPort);
                else if (hasJsonProperty(json, "listen")) throw new IllegalArgumentException("监听端口必须是整数，或监听地址必须是字符串");
            }
            value = jsonString(json, "key");
            if (value != null) key = value;
            else if (hasJsonProperty(json, "key")) throw new IllegalArgumentException("key 必须是字符串");
            value = jsonString(json, "default_broadcast");
            if (value != null) defaultBroadcast = value.trim();
            else if (hasJsonProperty(json, "default_broadcast")) throw new IllegalArgumentException("default_broadcast 必须是字符串");
            Integer integer = jsonInt(json, "default_port");
            if (integer != null) defaultPort = integer;
            else if (hasJsonProperty(json, "default_port")) throw new IllegalArgumentException("default_port 必须是整数");
            integer = jsonInt(json, "cooldown_seconds");
            if (integer != null) cooldownSeconds = integer;
            else if (hasJsonProperty(json, "cooldown_seconds")) throw new IllegalArgumentException("cooldown_seconds 必须是整数");
            value = jsonString(json, "admin_path");
            if (value != null) adminPath = value.trim();
            else if (hasJsonProperty(json, "admin_path")) throw new IllegalArgumentException("admin_path 必须是字符串");
            value = jsonString(json, "admin_token");
            if (value != null && !value.isBlank()) setAdminTokenFromPlaintext(value.trim());
            else if (hasJsonProperty(json, "admin_token")) throw new IllegalArgumentException("admin_token 不能为空");
            value = jsonString(json, "admin_password");
            if (value != null && !value.isBlank()) setAdminTokenFromPlaintext(value.trim());
            else if (hasJsonProperty(json, "admin_password")) throw new IllegalArgumentException("admin_password 不能为空");

            List<String> macs = jsonStringArray(json, "allow_macs");
            if (macs != null) {
                allowMacs = new LinkedHashSet<>();
                for (String mac : macs) {
                    if (mac == null || mac.isBlank()) continue;
                    allowMacs.add(normalizeMac(mac));
                }
            } else {
                value = jsonString(json, "allow_macs");
                if (value != null) {
                    allowMacs = new LinkedHashSet<>();
                    for (String mac : value.split("[,\\n\\r]+")) {
                        if (!mac.isBlank()) allowMacs.add(normalizeMac(mac.trim()));
                    }
                } else if (hasJsonProperty(json, "allow_macs")) {
                    throw new IllegalArgumentException("allow_macs 必须是字符串数组");
                }
            }
        }

        /**
         * Apply a plaintext credential from an authenticated API request. Keep
         * the existing verifier when the supplied token is unchanged so that a
         * no-op save does not rotate its random salt.
         */
        void setAdminTokenFromPlaintext(String value) {
            if (hasAdminTokenVerifierPrefix(value) || !isValidAdminToken(value)) {
                throw new IllegalArgumentException("admin_token 长度必须为 6-256 位，且只能使用可见的 ASCII 字母、数字或特殊符号");
            }
            if (!verifyAdminToken(value, adminTokenVerifier)) {
                adminTokenVerifier = createAdminTokenVerifier(value);
            }
            pendingAdminToken = "";
            adminTokenFieldPresent = false;
        }

        /**
         * YAML imports are authenticated API updates. Unlike a normal reload,
         * an explicit token field is meaningful here and must be migrated or
         * rejected before validation. Empty YAML values are deliberately not a
         * rotation mechanism for an already authenticated request.
         */
        void applyPendingAdminTokenForAuthenticatedUpdate() {
            if (!adminTokenFieldPresent) {
                pendingAdminToken = "";
                return;
            }
            String supplied = pendingAdminToken == null ? "" : pendingAdminToken.strip();
            if (supplied.isBlank()) {
                throw new IllegalArgumentException("admin_token 不能为空");
            }
            if (hasAdminTokenVerifierPrefix(supplied) && !isValidAdminTokenVerifier(supplied)) {
                throw new IllegalArgumentException("admin_token verifier 格式无效");
            }
            if (isValidAdminTokenVerifier(supplied)) {
                adminTokenVerifier = supplied;
            } else {
                setAdminTokenFromPlaintext(supplied);
            }
            pendingAdminToken = "";
            adminTokenFieldPresent = false;
        }

        void validate() {
            listen = normalizeListenValue(listen);
            parseListen(listen);
            if (key == null || key.isBlank() || key.length() > 512) {
                throw new IllegalArgumentException("key 不能为空且不能超过 512 个字符");
            }
            if (parseIpv4(defaultBroadcast) == null) {
                throw new IllegalArgumentException("default_broadcast 必须是有效 IPv4 地址");
            }
            if (defaultPort < 1 || defaultPort > 65535) {
                throw new IllegalArgumentException("default_port 必须在 1-65535 之间");
            }
            if (cooldownSeconds < 0 || cooldownSeconds > 86400) {
                throw new IllegalArgumentException("cooldown_seconds 必须在 0-86400 之间");
            }
            if (!ADMIN_PATH_PATTERN.matcher(adminPath == null ? "" : adminPath).matches()) {
                throw new IllegalArgumentException("admin_path 长度必须为 4-64 位，且只能使用大小写字母");
            }
            if ("api".equalsIgnoreCase(adminPath)) {
                throw new IllegalArgumentException("admin_path 不能使用保留入口");
            }
            if (!isValidAdminTokenVerifier(adminTokenVerifier)) {
                throw new IllegalArgumentException("管理令牌 verifier 无效，请删除 admin_token 后重启重新生成");
            }
            Set<String> normalizedMacs = new LinkedHashSet<>();
            for (String mac : allowMacs) normalizedMacs.add(normalizeMac(mac));
            allowMacs = normalizedMacs;
        }

        synchronized void save() throws IOException {
            Path target = configPath.toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path secretTarget = adminTokenPath();
            Path backupTarget = target.resolveSibling(target.getFileName() + ".bak");
            if (Files.isSymbolicLink(secretTarget)) {
                throw new IOException("管理令牌 sidecar 不能是符号链接");
            }
            boolean targetExisted = Files.exists(target);
            boolean secretExisted = Files.exists(secretTarget);
            boolean backupExisted = Files.exists(backupTarget);
            byte[] previousConfig = targetExisted ? Files.readAllBytes(target) : null;
            byte[] previousSecret = secretExisted ? Files.readAllBytes(secretTarget) : null;
            byte[] previousBackup = backupExisted ? Files.readAllBytes(backupTarget) : null;
            try {
                saveYaml(target, parent);
                writeSecretFile(secretTarget, (adminTokenVerifier + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                IOException rollbackFailure = restoreAfterFailedSave(
                        target, targetExisted, previousConfig,
                        secretTarget, secretExisted, previousSecret,
                        backupTarget, backupExisted, previousBackup);
                if (rollbackFailure != null) error.addSuppressed(rollbackFailure);
                throw error;
            }
        }

        private void saveYaml(Path target, Path parent) throws IOException {
            Path temporary = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    target.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, toYaml(), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                Path backup = target.resolveSibling(target.getFileName() + ".bak");
                if (Files.exists(target)) {
                    Files.writeString(backup,
                            scrubAdminTokenFields(Files.readString(target, StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                } else if (Files.exists(backup)) {
                    Files.writeString(backup,
                            scrubAdminTokenFields(Files.readString(backup, StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    replaceOrWriteMountedFile(temporary, target);
                } catch (IOException moveError) {
                    writeMountedFileFallback(temporary, target, moveError);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private static IOException restoreAfterFailedSave(Path target, boolean targetExisted, byte[] previousConfig,
                                                           Path secretTarget, boolean secretExisted,
                                                           byte[] previousSecret, Path backupTarget,
                                                           boolean backupExisted, byte[] previousBackup) {
            IOException failure = null;
            try {
                restoreFile(target, targetExisted, previousConfig, false);
            } catch (IOException error) {
                failure = error;
            }
            try {
                restoreFile(secretTarget, secretExisted, previousSecret, true);
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            try {
                restoreFile(backupTarget, backupExisted, previousBackup, false);
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            return failure;
        }

        private static void restoreFile(Path path, boolean existed, byte[] content, boolean secret) throws IOException {
            if (!existed) {
                Files.deleteIfExists(path);
                return;
            }
            if (secret) {
                writeSecretFile(path, content);
            } else {
                Files.write(path, content, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        }

        private static void writeSecretFile(Path target, byte[] content) throws IOException {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    target.getFileName().toString(), ".tmp");
            try {
                restrictSecretFile(temporary);
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictSecretFile(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private static void restrictSecretFile(Path path) throws IOException {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                return;
            }

            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                throw new IOException("当前文件系统不支持管理令牌 sidecar 权限保护");
            }
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (dos != null) dos.setHidden(true);
        }

        private static String scrubAdminTokenFields(String yaml) {
            StringBuilder sanitized = new StringBuilder(yaml.length());
            for (String line : yaml.split("\\R", -1)) {
                String content = stripComment(line).trim();
                int separator = content.indexOf(':');
                String key = separator > 0 ? content.substring(0, separator).trim() : "";
                if ("admin_token".equals(key) || "admin_password".equals(key)) continue;
                sanitized.append(line).append('\n');
            }
            return sanitized.toString();
        }

        private static void replaceOrWriteMountedFile(Path temporary, Path target) throws IOException {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                writeMountedFileFallback(temporary, target, moveError);
            }
        }

        private static void writeMountedFileFallback(Path temporary, Path target, IOException moveError) throws IOException {
            try {
                Files.writeString(target, Files.readString(temporary, StandardCharsets.UTF_8), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException writeError) {
                writeError.addSuppressed(moveError);
                throw writeError;
            }
        }

        private String toYaml() {
            StringBuilder yaml = new StringBuilder();
            yaml.append("# WOL 代理服务配置（由 Web 管理后台生成）\n")
                    .append("# 代理服务监听地址和端口；Web 管理页面只填写数字端口，例如 14250。\n")
                    .append("listen: ").append(yamlQuote(listen)).append('\n')
                    .append("# APK 连接代理服务时使用的 KEY，请使用不易猜测的随机字符串。\n")
                    .append("key: ").append(yamlQuote(key)).append('\n')
                    .append("# APK 未填写设备地址时使用的默认 IPv4 广播地址。\n")
                    .append("default_broadcast: ").append(yamlQuote(defaultBroadcast)).append('\n')
                    .append("# APK 未填写 UDP 端口时使用的默认端口，WOL 通常使用 9。\n")
                    .append("default_port: ").append(defaultPort).append('\n')
                    .append("# 同一 MAC 两次唤醒之间的最短间隔，单位为秒。\n")
                    .append("cooldown_seconds: ").append(cooldownSeconds).append('\n')
                    .append("# 允许唤醒的 MAC 白名单；空数组表示允许全部。\n")
                    .append("allow_macs:");
            if (allowMacs.isEmpty()) {
                yaml.append(" []\n");
            } else {
                yaml.append('\n');
                for (String mac : allowMacs) yaml.append("  - ").append(yamlQuote(mac)).append('\n');
            }
            yaml.append("# Web 管理入口（4-64 位大小写字母；首次启动自动生成 8 位路径）\n")
                    .append("admin_path: ").append(yamlQuote(adminPath)).append('\n')
                    .append("# 管理令牌只以 PBKDF2 verifier 存储在同目录受限 sidecar，不写入主配置。\n")
                    .append("# 如需下次启动重新生成，可手动添加一行：admin_token: \"\"\n");
            return yaml.toString();
        }

        private static String yamlQuote(String value) {
            String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
            return "\"" + safe + "\"";
        }

        private static Path resolveConfigPath(Path requestedPath) {
            Path workingDirectoryPath = requestedPath.toAbsolutePath().normalize();
            if (requestedPath.isAbsolute() || Files.exists(workingDirectoryPath)) {
                return workingDirectoryPath;
            }

            Path packagedDirectory = packagedAppDirectory();
            if (packagedDirectory != null) {
                Path besidePackagedRuntime = packagedDirectory.resolve(requestedPath).normalize();
                if (Files.exists(besidePackagedRuntime)) {
                    return besidePackagedRuntime;
                }
            }

            String packagedAppPath = System.getProperty("jpackage.app-path", "").trim();
            if (!packagedAppPath.isBlank()) {
                Path executablePath = Path.of(packagedAppPath).toAbsolutePath().normalize();
                Path executableDirectory = executablePath.getParent();
                if (executableDirectory != null) {
                    Path besideExecutable = executableDirectory.resolve(requestedPath).normalize();
                    if (Files.exists(besideExecutable)) {
                        return besideExecutable;
                    }
                }
            }
            return workingDirectoryPath;
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) continue;
                String name = arg.substring(2);
                String value = "true";
                int equals = name.indexOf('=');
                if (equals >= 0) {
                    value = name.substring(equals + 1);
                    name = name.substring(0, equals);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                values.put(name, value);
            }
            return values;
        }

        private static void applyConfigFile(Config config, Path path) throws IOException {
            applyConfigLines(config, Files.readAllLines(path, StandardCharsets.UTF_8), false);
        }

        private static void applyYamlText(Config config, String yaml) {
            applyConfigLines(config, List.of(yaml.split("\\R", -1)), true);
        }

        private static void applyConfigLines(Config config, List<String> lines, boolean strict) {
            String listKey = null;
            boolean recognizedProperty = false;
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String rawLine = lines.get(lineIndex);
                String lineWithoutComment = stripComment(rawLine);
                String line = lineWithoutComment.trim();
                if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;
                if ("---".equals(line) || "...".equals(line)) continue;

                if (line.startsWith("-") && "allow_macs".equals(listKey)) {
                    String item = stripQuotes(line.substring(1).trim());
                    if (!item.isBlank()) {
                        config.allowMacs.add(normalizeMac(item));
                    }
                    continue;
                }

                listKey = null;
                int split = line.indexOf(':');
                if (split <= 0) {
                    if (strict) throw invalidYamlLine(lineIndex, "缺少字段名或冒号");
                    continue;
                }

                String key = line.substring(0, split).trim();
                String value = stripQuotes(line.substring(split + 1).trim());
                switch (key) {
                    case "listen" -> {
                        recognizedProperty = true;
                        config.listen = normalizeListenValue(value);
                    }
                    case "key" -> {
                        recognizedProperty = true;
                        config.key = value;
                    }
                    case "default_broadcast" -> {
                        recognizedProperty = true;
                        config.defaultBroadcast = value;
                    }
                    case "default_port" -> {
                        recognizedProperty = true;
                        config.defaultPort = strict
                                ? parseYamlInt(value, lineIndex, "default_port")
                                : parseInt(value, config.defaultPort);
                    }
                    case "cooldown_seconds" -> {
                        recognizedProperty = true;
                        config.cooldownSeconds = strict
                                ? parseYamlInt(value, lineIndex, "cooldown_seconds")
                                : parseInt(value, config.cooldownSeconds);
                    }
                    case "admin_path" -> {
                        recognizedProperty = true;
                        config.adminPath = value;
                    }
                    case "admin_token" -> {
                        recognizedProperty = true;
                        config.pendingAdminToken = value;
                        config.adminTokenFieldPresent = true;
                    }
                    case "admin_password" -> {
                        recognizedProperty = true;
                        config.pendingAdminToken = value;
                        config.adminTokenFieldPresent = true;
                    }
                    case "allow_macs" -> {
                        recognizedProperty = true;
                        config.allowMacs.clear();
                        if ("[]".equals(value) || value.isBlank()) {
                            listKey = "allow_macs";
                        } else if (value.startsWith("[") && value.endsWith("]")) {
                            String inside = value.substring(1, value.length() - 1).trim();
                            if (!inside.isBlank()) {
                                for (String item : inside.split(",")) {
                                    config.allowMacs.add(normalizeMac(stripQuotes(item.trim())));
                                }
                            }
                        } else if (strict) {
                            throw invalidYamlLine(lineIndex, "allow_macs 必须是 YAML 列表");
                        }
                    }
                    default -> {
                        if (strict) throw invalidYamlLine(lineIndex, "不支持的字段 " + key);
                    }
                }
            }
            if (strict && !recognizedProperty) {
                throw new IllegalArgumentException("YAML 中没有可导入的配置字段");
            }
        }

        private static int parseYamlInt(String value, int lineIndex, String field) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException error) {
                throw invalidYamlLine(lineIndex, field + " 必须是整数");
            }
        }

        private static IllegalArgumentException invalidYamlLine(int lineIndex, String message) {
            return new IllegalArgumentException("YAML 第 " + (lineIndex + 1) + " 行无效：" + message);
        }

        private static String stripComment(String value) {
            boolean inSingleQuotes = false;
            boolean inDoubleQuotes = false;
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current == '\\' && inDoubleQuotes && !escaped) {
                    escaped = true;
                    continue;
                }
                if (current == '"' && !inSingleQuotes && !escaped) {
                    inDoubleQuotes = !inDoubleQuotes;
                } else if (current == '\'' && !inDoubleQuotes) {
                    inSingleQuotes = !inSingleQuotes;
                } else if (current == '#' && !inSingleQuotes && !inDoubleQuotes) {
                    return value.substring(0, i);
                }
                escaped = false;
            }
            return value;
        }

        private static String stripQuotes(String value) {
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return unescapeJson(value.substring(1, value.length() - 1));
            }
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1).replace("''", "'");
            }
            return value;
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static void applyEnv(Config config) {
            config.environmentOverrides.clear();
            String listen = System.getenv("WOL_PROXY_LISTEN");
            String key = System.getenv("WOL_PROXY_KEY");
            String broadcast = System.getenv("WOL_PROXY_DEFAULT_BROADCAST");
            String port = System.getenv("WOL_PROXY_DEFAULT_PORT");
            String cooldown = System.getenv("WOL_PROXY_COOLDOWN_SECONDS");
            String allowMacs = System.getenv("WOL_PROXY_ALLOW_MACS");

            if (listen != null && !listen.isBlank()) { config.listen = normalizeListenValue(listen); config.environmentOverrides.add("WOL_PROXY_LISTEN"); }
            if (key != null && !key.isBlank()) { config.key = key.trim(); config.environmentOverrides.add("WOL_PROXY_KEY"); }
            if (broadcast != null && !broadcast.isBlank()) { config.defaultBroadcast = broadcast.trim(); config.environmentOverrides.add("WOL_PROXY_DEFAULT_BROADCAST"); }
            if (port != null && !port.isBlank()) { config.defaultPort = parseInt(port, config.defaultPort); config.environmentOverrides.add("WOL_PROXY_DEFAULT_PORT"); }
            if (cooldown != null && !cooldown.isBlank()) { config.cooldownSeconds = parseInt(cooldown, config.cooldownSeconds); config.environmentOverrides.add("WOL_PROXY_COOLDOWN_SECONDS"); }
            if (allowMacs != null && !allowMacs.isBlank()) {
                config.environmentOverrides.add("WOL_PROXY_ALLOW_MACS");
                config.allowMacs.clear();
                for (String item : allowMacs.split(",")) {
                    String mac = item.trim();
                    if (!mac.isBlank()) config.allowMacs.add(normalizeMac(mac));
                }
            }
        }
    }
}
