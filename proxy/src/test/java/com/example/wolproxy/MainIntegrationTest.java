package com.example.wolproxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Starts the same Java entry point used by the distributions and exercises the
 * HTTP contract. This catches resource-packaging and first-run config issues
 * that a unit test of private helpers would miss.
 */
public class MainIntegrationTest {
    private static final String PROXY_KEY = "integration-test-proxy-key-20260815";
    private static final Pattern ADMIN_PATH = Pattern.compile("(?m)^admin_path:\\s+\"([A-Za-z]{4,64})\"\\s*$");
    private static final Pattern GENERATED_ADMIN_TOKEN = Pattern.compile("(?m)^首次生成的管理令牌：([\\x21-\\x7E]{6,256})$");
    private static final Pattern GENERATED_PROXY_KEY = Pattern.compile("(?m)^首次生成的代理连接 KEY：([A-Za-z0-9]{24})$");
    private static final Pattern ADMIN_TOKEN_VERIFIER = Pattern.compile("^pbkdf2-sha256\\$v1\\$(\\d+)\\$([A-Za-z0-9_-]+)\\$([A-Za-z0-9_-]+)$");
    private static final Pattern ADMIN_TOKEN_FIELD = Pattern.compile("(?m)^\\s*admin_(?:token|password)\\s*:");
    private static final Pattern ADMIN_TOKEN_JSON_FIELD = Pattern.compile("\"admin_(?:token|password)\"\\s*:");

    private Path temporaryDirectory;
    private Process process;
    private HttpClient client;
    private Path homeDirectory;
    private Path configFile;
    private Path adminTokenFile;
    private Path processOutput;
    private int port;
    private String adminPath;
    private String adminToken;
    private String baseUrl;

    @Before
    public void startProxy() throws Exception {
        temporaryDirectory = Files.createTempDirectory("wol-proxy-integration-");
        homeDirectory = Files.createDirectories(temporaryDirectory.resolve("home"));
        port = freePort();
        configFile = temporaryDirectory.resolve("config.yml");
        adminTokenFile = configFile.resolveSibling("config.yml.admin-token");
        Files.writeString(configFile, """
                listen: ":%d"
                key: "%s"
                default_broadcast: "255.255.255.255"
                default_port: 9
                cooldown_seconds: 5
                allow_macs: []
                admin_path: ""
                admin_token: ""
                """.formatted(port, PROXY_KEY), StandardCharsets.UTF_8);

        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        startProcess("first-start.log");
        String saved = Files.readString(configFile, StandardCharsets.UTF_8);
        adminPath = requiredMatch(ADMIN_PATH, saved, "admin_path");
        waitUntilOutputContains("首次生成的管理令牌：");
        adminToken = requiredMatch(GENERATED_ADMIN_TOKEN,
                Files.readString(processOutput, StandardCharsets.UTF_8), "首次生成的管理令牌");
        String verifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertValidAdminTokenVerifier(verifier);
        assertFalse("sidecar 不得保存管理令牌明文", verifier.contains(adminToken));
        assertRestrictedTokenSidecar();
        assertEquals("首次启动应生成 8 位管理入口", 8, adminPath.length());
        assertTrue(adminPath.matches("[A-Za-z]{8}"));
        waitUntilOutputContains("首次生成的管理令牌：" + adminToken);
        assertEquals("首次生成的管理令牌应只输出一次", 1,
                countOccurrences(Files.readString(processOutput, StandardCharsets.UTF_8), adminToken));
        assertNoTokenFieldOrValue(saved, adminToken);
        assertFalse(Files.readString(homeDirectory.resolve("wol-proxy.log"), StandardCharsets.UTF_8)
                .contains(adminToken));
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
    }

    @After
    public void stopProxy() throws Exception {
        stopRunningProcess();
        if (temporaryDirectory != null) deleteTree(temporaryDirectory);
    }

    @Test
    public void servesAdminResourcesAuthenticatesAndSavesYaml() throws Exception {
        waitUntilOutputContains(":" + port + "/" + adminPath + "/");
        String startupOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertTrue(startupOutput.contains("[Web 管理后台地址]http://"));
        assertFalse(startupOutput.contains("http://0.0.0.0:"));

        HttpResponse<String> page = request(baseUrl + "/", "GET", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("WOL 代理管理"));
        assertTrue(page.body().contains("监听端口"));
        assertTrue(page.body().contains("value=\"80\""));
        assertTrue(page.body().contains("id=\"logs-prev\""));
        assertTrue(page.body().contains("id=\"logs-auto-refresh\" type=\"checkbox\" checked"));
        assertTrue(page.body().contains("id=\"import-config\""));
        assertTrue(page.body().contains("id=\"export-config\""));
        assertTrue(page.body().contains("id=\"docker-listen-help\""));
        assertTrue(page.body().contains("当前运行在 Docker 中。修改端口后须同步修改 TCP 端口映射；WOL 广播请使用 host 网络，请注意！"));

        HttpResponse<String> css = request(baseUrl + "/app.css", "GET", null, null);
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertTrue(css.body().contains("--blue"));
        assertTrue(css.body().contains(".docker-warning"));

        HttpResponse<String> script = request(baseUrl + "/app.js", "GET", null, null);
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("content-type").orElse("").contains("javascript"));
        assertFalse(script.body().contains("rawItems.map(normalizeLogItem).reverse()"));
        assertTrue(script.body().contains("listen: Number(normalizeListenPort"));

        HttpResponse<String> unauthorized = request(baseUrl + "/api/config", "GET", null, null);
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> login = request(baseUrl + "/api/login", "POST",
                "{\"password\":\"" + adminToken + "\"}", null);
        assertEquals("登录响应=" + login.body(), 200, login.statusCode());
        assertEquals("{\"ok\":true}", login.body());
        assertDoesNotExposeAdminToken(login.body(), adminToken);

        HttpResponse<String> config = request(baseUrl + "/api/config", "GET", null, adminToken);
        assertEquals(200, config.statusCode());
        assertTrue(config.body().contains(PROXY_KEY));
        assertTrue(config.body().contains("\"admin_token_configured\":true"));
        assertTrue(config.body().contains("\"docker_environment\":"));
        assertDoesNotExposeAdminToken(config.body(), adminToken);

        HttpResponse<String> saved = request(baseUrl + "/api/config", "PUT",
                "{\"cooldown_seconds\":7}", adminToken);
        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("配置已保存"));
        assertDoesNotExposeAdminToken(saved.body(), adminToken);

        String current = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(current.contains("cooldown_seconds: 7"));
        assertNoTokenFieldOrValue(current, adminToken);
        assertTrue(Files.exists(configFile.resolveSibling("config.yml.bak")));
        String backup = Files.readString(configFile.resolveSibling("config.yml.bak"), StandardCharsets.UTF_8);
        assertTrue(backup.contains("cooldown_seconds: 5"));
        assertNoTokenFieldOrValue(backup, adminToken);

        HttpResponse<String> invalidMac = request(baseUrl + "/api/config", "PUT",
                "{\"allow_macs\":[\"bad-mac\"]}", adminToken);
        assertEquals(400, invalidMac.statusCode());
        assertTrue(invalidMac.body().contains("invalid_config"));

        HttpResponse<String> invalidListen = request(baseUrl + "/api/config", "PUT",
                "{\"listen\":\":0\"}", adminToken);
        assertEquals(400, invalidListen.statusCode());
        assertTrue(invalidListen.body().contains("1-65535"));

        int oldPort = port;
        int numericPort = freePort();
        HttpResponse<String> numericListen = request(baseUrl + "/api/config", "PUT",
                "{\"listen\":" + numericPort + "}", adminToken);
        assertEquals(200, numericListen.statusCode());
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8)
                .contains("listen: \":" + numericPort + "\""));
        assertTrue(numericListen.body().contains("监听端口已热切换"));
        port = numericPort;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        waitUntilListening(port);
        waitUntilUnavailable(oldPort);

        int hostPort = freePort();
        HttpResponse<String> hostListen = request(baseUrl + "/api/config", "PUT",
                "{\"listen\":\"127.0.0.1:" + hostPort + "\"}", adminToken);
        assertEquals(200, hostListen.statusCode());
        port = hostPort;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        waitUntilListening(port);

        int finalPort = freePort();
        HttpResponse<String> hostPortOnly = request(baseUrl + "/api/config", "PUT",
                "{\"listen\":" + finalPort + "}", adminToken);
        assertEquals(200, hostPortOnly.statusCode());
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8)
                .contains("listen: \"127.0.0.1:" + finalPort + "\""));
        port = finalPort;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        waitUntilListening(port);

        HttpResponse<String> exported = request(baseUrl + "/api/config/export", "GET", null, adminToken);
        assertEquals(200, exported.statusCode());
        assertTrue(exported.headers().firstValue("content-disposition").orElse("").contains("wol-proxy-config.yml"));
        assertTrue(exported.body().contains("cooldown_seconds: 7"));
        assertNoTokenFieldOrValue(exported.body(), adminToken);

        String importedYaml = exported.body().replace("cooldown_seconds: 7", "cooldown_seconds: 9");
        HttpResponse<String> imported = requestDocument(baseUrl + "/api/config/import", importedYaml,
                "application/x-yaml", adminToken);
        assertEquals(200, imported.statusCode());
        assertTrue(imported.body().contains("配置已导入并立即生效"));
        assertDoesNotExposeAdminToken(imported.body(), adminToken);
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8).contains("cooldown_seconds: 9"));

        String auditLog = Files.readString(temporaryDirectory.resolve("home").resolve("wol-proxy.log"),
                StandardCharsets.UTF_8);
        assertTrue(auditLog.contains("冷却时间（秒）=5->7"));
        assertTrue(auditLog.contains("监听热切换=成功"));
        assertTrue(auditLog.contains("冷却时间（秒）=7->9"));
    }

    @Test
    public void rejectsOccupiedListenerAndKeepsOldRuntimeForSaveAndReload() throws Exception {
        String originalConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();
            HttpResponse<String> failedSave = request(baseUrl + "/api/config", "PUT",
                    "{\"listen\":" + occupiedPort + "}", adminToken);
            assertEquals(409, failedSave.statusCode());
            assertTrue(failedSave.body().contains("listener_restart_failed"));
            assertTrue(failedSave.body().contains("\"active_listen\":\":" + port + "\""));
            assertEquals(originalConfig, Files.readString(configFile, StandardCharsets.UTF_8));

            HttpResponse<String> stillActive = request(baseUrl + "/api/config", "GET", null, adminToken);
            assertEquals(200, stillActive.statusCode());
            assertTrue(stillActive.body().contains("\"listen\":\":" + port + "\""));

            String occupiedConfig = originalConfig.replaceFirst(
                    "(?m)^listen:\\s*.*$", Matcher.quoteReplacement("listen: \":" + occupiedPort + "\""));
            Files.writeString(configFile, occupiedConfig, StandardCharsets.UTF_8);
            HttpResponse<String> failedReload = request(baseUrl + "/api/reload", "POST", null, adminToken);
            assertEquals(409, failedReload.statusCode());
            assertTrue(failedReload.body().contains("listener_restart_failed"));

            HttpResponse<String> activeAfterReload = request(baseUrl + "/api/status", "GET", null, adminToken);
            assertEquals(200, activeAfterReload.statusCode());
            assertTrue(activeAfterReload.body().contains("\"active_listen\":\":" + port + "\""));
        } finally {
            Files.writeString(configFile, originalConfig, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void changesAndRegeneratesAdminCredentialsWithoutReturningOrLoggingThem() throws Exception {
        String startupToken = adminToken;
        String changedKey = "audit-secret-proxy-key-20260815";
        String changedToken = "Ab!234";
        String changedPath = "AbCd";
        HttpResponse<String> changed = request(baseUrl + "/api/config", "PUT",
                "{\"key\":\"" + changedKey + "\",\"admin_path\":\"" + changedPath
                         + "\",\"admin_token\":\"" + changedToken + "\"}", adminToken);
        assertEquals(200, changed.statusCode());
        assertTrue(changed.body().contains("\"admin_token_changed\":true"));
        assertDoesNotExposeAdminToken(changed.body(), startupToken);
        assertDoesNotExposeAdminToken(changed.body(), changedToken);
        String changedVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertValidAdminTokenVerifier(changedVerifier);
        assertFalse(changedVerifier.contains(changedToken));
        assertNoTokenFieldOrValue(Files.readString(configFile, StandardCharsets.UTF_8), changedToken);

        adminPath = changedPath;
        adminToken = changedToken;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        HttpResponse<String> current = request(baseUrl + "/api/config", "GET", null, adminToken);
        assertEquals(200, current.statusCode());
        assertTrue(current.body().contains("\"admin_path\":\"AbCd\""));
        assertDoesNotExposeAdminToken(current.body(), adminToken);

        String[] invalidPathBodies = {
                "{\"admin_path\":\"abc\"}",
                "{\"admin_path\":\"abc1\"}",
                "{\"admin_path\":\"bad/path\"}",
                "{\"admin_path\":\"bad?path\"}",
                "{\"admin_path\":\"bad#path\"}",
                "{\"admin_path\":\"bad\\npath\"}"
        };
        for (String body : invalidPathBodies) {
            HttpResponse<String> rejected = request(baseUrl + "/api/config", "PUT", body, adminToken);
            assertEquals("非法管理入口应被拒绝：" + body, 400, rejected.statusCode());
            assertTrue(rejected.body().contains("invalid_config"));
        }
        HttpResponse<String> shortToken = request(baseUrl + "/api/config", "PUT",
                "{\"admin_token\":\"12345\"}", adminToken);
        assertEquals(400, shortToken.statusCode());

        String oldToken = adminToken;
        String regeneratedToken = "Rg!45678";
        String regeneratedPath = "BcDe";
        HttpResponse<String> regenerated = request(baseUrl + "/api/regenerate-access", "POST",
                "{\"admin_path\":\"" + regeneratedPath + "\",\"admin_token\":\""
                        + regeneratedToken + "\"}", oldToken);
        assertEquals(200, regenerated.statusCode());
        assertTrue(regenerated.body().contains("\"admin_path\":\"" + regeneratedPath + "\""));
        assertTrue(regenerated.body().contains("\"admin_token_configured\":true"));
        assertDoesNotExposeAdminToken(regenerated.body(), oldToken);
        assertDoesNotExposeAdminToken(regenerated.body(), regeneratedToken);

        adminPath = regeneratedPath;
        adminToken = regeneratedToken;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        assertEquals(401, request(baseUrl + "/api/config", "GET", null, oldToken).statusCode());
        assertEquals(200, request(baseUrl + "/api/config", "GET", null, adminToken).statusCode());
        String regeneratedVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertValidAdminTokenVerifier(regeneratedVerifier);
        assertFalse(regeneratedVerifier.contains(adminToken));
        assertNoTokenFieldOrValue(Files.readString(configFile, StandardCharsets.UTF_8), adminToken);

        String auditLog = Files.readString(homeDirectory.resolve("wol-proxy.log"), StandardCharsets.UTF_8);
        assertTrue(auditLog, auditLog.contains("代理连接 Key=<已设置:"));
        assertTrue(auditLog, auditLog.contains("管理令牌=<已设置>"));
        assertTrue(auditLog, auditLog.contains("管理入口="));
        assertFalse(auditLog.contains(changedKey));
        assertFalse(auditLog.contains(changedToken));
        assertFalse(auditLog.contains(regeneratedToken));
        assertFalse(auditLog.contains(startupToken));
    }

    @Test
    public void exportsAndImportsJsonAndYamlIncludingListenerHotSwitch() throws Exception {
        HttpResponse<String> yamlExport = request(
                baseUrl + "/api/config/export?format=yml", "GET", null, adminToken);
        assertEquals(200, yamlExport.statusCode());
        assertTrue(yamlExport.headers().firstValue("content-type").orElse("").contains("yaml"));
        assertTrue(yamlExport.headers().firstValue("content-disposition").orElse("")
                .contains("wol-proxy-config.yml"));
        assertEquals("no-store", yamlExport.headers().firstValue("cache-control").orElse(""));
        assertTrue(yamlExport.body().contains("key: \"" + PROXY_KEY + "\""));
        assertNoTokenFieldOrValue(yamlExport.body(), adminToken);

        HttpResponse<String> jsonExport = request(
                baseUrl + "/api/config/export?format=json", "GET", null, adminToken);
        assertEquals(200, jsonExport.statusCode());
        assertTrue(jsonExport.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(jsonExport.headers().firstValue("content-disposition").orElse("")
                .contains("wol-proxy-config.json"));
        assertTrue(jsonExport.body().contains("\"key\":\"" + PROXY_KEY + "\""));
        assertDoesNotExposeAdminToken(jsonExport.body(), adminToken);

        HttpResponse<String> jsonImport = requestDocument(baseUrl + "/api/config/import?format=json",
                "{\"cooldown_seconds\":11,\"default_port\":7}", "application/json", adminToken);
        assertEquals(200, jsonImport.statusCode());
        assertDoesNotExposeAdminToken(jsonImport.body(), adminToken);
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8).contains("cooldown_seconds: 11"));

        HttpResponse<String> invalidJson = requestDocument(baseUrl + "/api/config/import?format=json",
                "{\"default_port\":\"nine\"}", "application/json", adminToken);
        assertEquals(400, invalidJson.statusCode());
        assertTrue(invalidJson.body().contains("invalid_config"));

        HttpResponse<String> invalidYaml = requestDocument(baseUrl + "/api/config/import?format=yaml",
                "default_port: nope\n", "application/x-yaml", adminToken);
        assertEquals(400, invalidYaml.statusCode());
        assertTrue(invalidYaml.body().contains("invalid_config"));

        int oldPort = port;
        int importedPort = freePort();
        HttpResponse<String> switched = requestDocument(baseUrl + "/api/config/import?format=yaml",
                "listen: \":" + importedPort + "\"\ncooldown_seconds: 13\n",
                "application/x-yaml", adminToken);
        assertEquals(200, switched.statusCode());
        assertTrue(switched.body().trim().endsWith("}"));
        assertTrue(switched.body().contains("监听端口已热切换"));
        assertDoesNotExposeAdminToken(switched.body(), adminToken);

        port = importedPort;
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        waitUntilListening(port);
        waitUntilUnavailable(oldPort);
        HttpResponse<String> importedConfig = request(baseUrl + "/api/config", "GET", null, adminToken);
        assertEquals(200, importedConfig.statusCode());
        assertTrue(importedConfig.body().contains("\"cooldown_seconds\":13"));
    }

    @Test
    public void persistsTokenAcrossRestartAndRotatesItOnlyForExplicitBlankYaml() throws Exception {
        String originalToken = adminToken;
        String originalVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();

        stopRunningProcess();
        startProcess("normal-restart.log");
        assertEquals(originalVerifier, Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip());
        String restartOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertFalse(restartOutput.contains(originalToken));
        assertFalse(restartOutput.contains("首次生成的管理令牌："));
        assertEquals(200, request(baseUrl + "/api/config", "GET", null, originalToken).statusCode());

        stopRunningProcess();
        Files.writeString(configFile, System.lineSeparator() + "admin_token: \"\"" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        startProcess("blank-token-rotation.log");

        waitUntilOutputContains("首次生成的管理令牌：");
        String rotatedToken = requiredMatch(GENERATED_ADMIN_TOKEN,
                Files.readString(processOutput, StandardCharsets.UTF_8), "轮换后的管理令牌");
        String rotatedVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertRestrictedTokenSidecar();
        assertValidAdminTokenVerifier(rotatedVerifier);
        assertFalse("显式空值必须轮换管理令牌", originalVerifier.equals(rotatedVerifier));
        assertFalse("sidecar 不得保存轮换后的明文令牌", rotatedVerifier.contains(rotatedToken));
        waitUntilOutputContains("首次生成的管理令牌：" + rotatedToken);
        String rotationOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(rotationOutput, rotatedToken));
        assertFalse(rotationOutput.contains(originalToken));

        String scrubbedYaml = Files.readString(configFile, StandardCharsets.UTF_8);
        String scrubbedBackup = Files.readString(configFile.resolveSibling("config.yml.bak"), StandardCharsets.UTF_8);
        assertNoTokenFieldOrValue(scrubbedYaml, rotatedToken);
        assertNoTokenFieldOrValue(scrubbedBackup, originalToken);
        assertNoTokenFieldOrValue(scrubbedBackup, rotatedToken);
        assertEquals(401, request(baseUrl + "/api/config", "GET", null, originalToken).statusCode());
        assertEquals(200, request(baseUrl + "/api/config", "GET", null, rotatedToken).statusCode());

        String runtimeLog = Files.readString(homeDirectory.resolve("wol-proxy.log"), StandardCharsets.UTF_8);
        assertFalse(runtimeLog.contains(originalToken));
        assertFalse(runtimeLog.contains(rotatedToken));
        adminToken = rotatedToken;
    }

    @Test
    public void dockerFirstRunRepairsEmptyMountedConfigAndPrintsProxyKeyOnlyOnce() throws Exception {
        stopRunningProcess();
        String originalVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        String brokenConfig = Files.readString(configFile, StandardCharsets.UTF_8)
                .replace("key: \"" + PROXY_KEY + "\"", "key: \"\"");
        assertTrue("测试配置必须包含空 KEY", brokenConfig.contains("key: \"\""));
        Files.writeString(configFile, brokenConfig, StandardCharsets.UTF_8);

        startDockerProcess("docker-empty-config.log");
        waitUntilOutputContains("首次生成的代理连接 KEY：");
        String firstOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        String generatedKey = requiredMatch(GENERATED_PROXY_KEY, firstOutput, "Docker 代理连接 KEY");
        assertEquals(1, countOccurrences(firstOutput, generatedKey));
        assertTrue("服务必须保持运行，不得因空 KEY 陷入重启", process.isAlive());
        assertFalse("修复空 KEY 时不得轮换或重复显示管理令牌", firstOutput.contains("首次生成的管理令牌："));
        assertEquals("修复空 KEY 时必须保留管理令牌 verifier", originalVerifier,
                Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip());

        String generatedConfig = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(generatedConfig.contains("key: \"" + generatedKey + "\""));
        assertFalse(Files.readString(homeDirectory.resolve("wol-proxy.log"), StandardCharsets.UTF_8)
                .contains(generatedKey));

        stopRunningProcess();
        startDockerProcess("docker-normal-restart.log");
        String restartOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertFalse("正常重启不得再次显示代理 KEY", restartOutput.contains("首次生成的代理连接 KEY："));
        assertFalse("正常重启不得再次显示管理令牌", restartOutput.contains("首次生成的管理令牌："));
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8)
                .contains("key: \"" + generatedKey + "\""));
        assertEquals(originalVerifier, Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip());
    }

    @Test
    public void migratesLegacyYamlTokenWithoutPrintingOrRetainingPlaintext() throws Exception {
        stopRunningProcess();
        Files.deleteIfExists(adminTokenFile);
        String legacyToken = "Legacy!Token_2026";
        adminPath = "LegacyA";
        Files.writeString(configFile, """
                listen: ":%d"
                key: "%s"
                default_broadcast: "255.255.255.255"
                default_port: 9
                cooldown_seconds: 5
                allow_macs: []
                admin_path: "%s"
                admin_token: "%s"
                """.formatted(port, PROXY_KEY, adminPath, legacyToken), StandardCharsets.UTF_8);

        startProcess("legacy-token-migration.log");
        baseUrl = "http://127.0.0.1:" + port + "/" + adminPath;
        String migratedVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertValidAdminTokenVerifier(migratedVerifier);
        assertFalse("迁移后的 sidecar 不得保存旧令牌", migratedVerifier.contains(legacyToken));
        assertRestrictedTokenSidecar();
        assertNoTokenFieldOrValue(Files.readString(configFile, StandardCharsets.UTF_8), legacyToken);
        assertNoTokenFieldOrValue(
                Files.readString(configFile.resolveSibling("config.yml.bak"), StandardCharsets.UTF_8), legacyToken);

        String migrationOutput = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertFalse(migrationOutput.contains(legacyToken));
        assertFalse(migrationOutput.contains("首次生成的管理令牌："));
        HttpResponse<String> login = request(baseUrl + "/api/login", "POST",
                "{\"password\":\"" + legacyToken + "\"}", null);
        assertEquals(200, login.statusCode());
        assertDoesNotExposeAdminToken(login.body(), legacyToken);
        HttpResponse<String> config = request(baseUrl + "/api/config", "GET", null, legacyToken);
        assertEquals(200, config.statusCode());
        assertDoesNotExposeAdminToken(config.body(), legacyToken);
        assertFalse(Files.readString(homeDirectory.resolve("wol-proxy.log"), StandardCharsets.UTF_8)
                .contains(legacyToken));
        adminToken = legacyToken;
    }

    @Test
    public void migratesWindowsConfigAndTokenSidecarIntoWolDirectory() throws Exception {
        stopRunningProcess();
        port = freePort();
        String legacyToken = "LegacyWindows!2026";
        String legacyPath = "LegacyWin";
        Path legacyConfig = homeDirectory.resolve("wol-config.yml");
        Path legacySidecar = homeDirectory.resolve("wol-config.yml.admin-token");
        Files.writeString(legacyConfig, """
                listen: ":%d"
                key: "%s"
                default_broadcast: "255.255.255.255"
                default_port: 9
                cooldown_seconds: 5
                allow_macs: []
                admin_path: "%s"
                """.formatted(port, PROXY_KEY, legacyPath), StandardCharsets.UTF_8);
        Files.writeString(legacySidecar, legacyToken + System.lineSeparator(), StandardCharsets.UTF_8);

        processOutput = temporaryDirectory.resolve("windows-config-migration.log");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(),
                "-Duser.home=" + homeDirectory,
                "-Dos.name=Windows",
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName());
        builder.redirectErrorStream(true).redirectOutput(processOutput.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("WOL_PROXY_"));
        process = builder.start();
        waitUntilListening();

        Path migratedConfig = homeDirectory.resolve("wol").resolve("wol-config.yml");
        Path migratedSidecar = migratedConfig.resolveSibling("wol-config.yml.admin-token");
        assertTrue(Files.isRegularFile(migratedConfig));
        assertTrue(Files.isRegularFile(migratedSidecar));
        assertValidAdminTokenVerifier(Files.readString(migratedSidecar, StandardCharsets.UTF_8).strip());
        String output = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertFalse(output.contains(legacyToken));
        assertFalse(output.contains("首次生成的管理令牌："));

        String migratedBaseUrl = "http://127.0.0.1:" + port + "/" + legacyPath;
        HttpResponse<String> login = request(migratedBaseUrl + "/api/login", "POST",
                "{\"password\":\"" + legacyToken + "\"}", null);
        assertEquals(200, login.statusCode());
    }

    @Test
    public void migratesLegacyPlaintextSidecarWithoutPrintingIt() throws Exception {
        stopRunningProcess();
        String legacyToken = "LegacySidecar!2026";
        Files.writeString(adminTokenFile, legacyToken + System.lineSeparator(), StandardCharsets.UTF_8);

        startProcess("legacy-sidecar-migration.log");
        String migratedVerifier = Files.readString(adminTokenFile, StandardCharsets.UTF_8).strip();
        assertValidAdminTokenVerifier(migratedVerifier);
        assertFalse(migratedVerifier.contains(legacyToken));
        String output = Files.readString(processOutput, StandardCharsets.UTF_8);
        assertFalse(output.contains(legacyToken));
        assertFalse(output.contains("首次生成的管理令牌："));
        assertEquals(200, request(baseUrl + "/api/config", "GET", null, legacyToken).statusCode());
        adminToken = legacyToken;
    }

    @Test
    public void acceptsColonAndHyphenMacsForSignedWakeAndDateFilteredLogs() throws Exception {
        HttpResponse<String> health = signedRequest("/api/health", "GET", "");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"name\":\"WOL Proxy\""));
        assertTrue(health.body().contains("\"cooldown_seconds\":5"));

        HttpResponse<String> whitelist = request(baseUrl + "/api/config", "PUT",
                "{\"allow_macs\":[\"aa-bb-cc-dd-ee-ff\",\"11:22:33:44:55:66\"]}", adminToken);
        assertEquals(200, whitelist.statusCode());
        assertTrue(whitelist.body().contains("AA:BB:CC:DD:EE:FF"));
        assertTrue(whitelist.body().contains("11:22:33:44:55:66"));
        String normalizedYaml = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(normalizedYaml.contains("AA:BB:CC:DD:EE:FF"));
        assertFalse(normalizedYaml.contains("aa-bb-cc-dd-ee-ff"));

        HttpResponse<String> hyphenWake = signedRequest("/api/wake", "POST",
                "{\"mac\":\"AA-BB-CC-DD-EE-FF\",\"address\":\"127.0.0.1\",\"port\":9}");
        assertEquals("连字符 MAC 唤醒响应=" + hyphenWake.body(), 200, hyphenWake.statusCode());
        HttpResponse<String> colonWake = signedRequest("/api/wake", "POST",
                "{\"mac\":\"11:22:33:44:55:66\",\"address\":\"127.0.0.1\",\"port\":9}");
        assertEquals("冒号 MAC 唤醒响应=" + colonWake.body(), 200, colonWake.statusCode());
        HttpResponse<String> deniedWake = signedRequest("/api/wake", "POST",
                "{\"mac\":\"22-33-44-55-66-77\",\"address\":\"127.0.0.1\",\"port\":9}");
        assertEquals(403, deniedWake.statusCode());
        assertTrue(deniedWake.body().contains("mac_not_allowed"));

        String invalidWake = "{\"mac\":\"not-a-mac\",\"address\":\"127.0.0.1\",\"port\":9}";
        HttpResponse<String> wake = signedRequest("/api/wake", "POST", invalidWake);
        assertEquals(400, wake.statusCode());
        assertTrue(wake.body().contains("invalid_mac"));

        HttpResponse<String> today = request(baseUrl + "/api/logs?date="
                        + LocalDate.now(ZoneOffset.ofHours(8)) + "&limit=50",
                "GET", null, adminToken);
        assertEquals(200, today.statusCode());
        assertTrue(today.body().contains("连接测试"));
        assertTrue(today.body().contains("\"returned\":"));

        HttpResponse<String> defaultPage = request(baseUrl + "/api/logs?date="
                        + LocalDate.now(ZoneOffset.ofHours(8)),
                "GET", null, adminToken);
        assertEquals(200, defaultPage.statusCode());
        Matcher returned = Pattern.compile("\\\"returned\\\":(\\d+)").matcher(defaultPage.body());
        assertTrue(returned.find());
        assertTrue(Integer.parseInt(returned.group(1)) <= 50);

        HttpResponse<String> old = request(baseUrl + "/api/logs?date=2000-01-01&limit=50",
                "GET", null, adminToken);
        assertEquals(200, old.statusCode());
        assertTrue(old.body().contains("\"returned\":0"));

        Path logFile = temporaryDirectory.resolve("home").resolve("wol-proxy.log");
        Files.writeString(logFile,
                "[2026-99-99 99:99:99][损坏日志]这行不应中断日志查询" + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        HttpResponse<String> malformed = request(baseUrl + "/api/logs?limit=50",
                "GET", null, adminToken);
        assertEquals(200, malformed.statusCode());
        assertFalse(malformed.body().contains("损坏日志"));
    }

    private void startProcess(String outputFileName) throws Exception {
        processOutput = temporaryDirectory.resolve(outputFileName);
        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(),
                "-Duser.home=" + homeDirectory,
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--config", configFile.toString());
        builder.redirectErrorStream(true).redirectOutput(processOutput.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("WOL_PROXY_"));
        process = builder.start();
        waitUntilListening();
    }

    private void startDockerProcess(String outputFileName) throws Exception {
        processOutput = temporaryDirectory.resolve(outputFileName);
        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(),
                "-Duser.home=" + homeDirectory,
                "-Dwol.proxy.docker=true",
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--config", configFile.toString(), "--listen", ":" + port);
        builder.redirectErrorStream(true).redirectOutput(processOutput.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("WOL_PROXY_"));
        process = builder.start();
        waitUntilListening();
    }

    private void stopRunningProcess() throws Exception {
        if (process == null) return;
        Process running = process;
        process = null;
        running.destroy();
        if (!running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            running.destroyForcibly();
            running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void assertNoTokenFieldOrValue(String document, String token) {
        assertFalse("配置文档不能包含管理令牌字段：\n" + document,
                ADMIN_TOKEN_FIELD.matcher(document).find());
        assertFalse("配置文档不能包含管理令牌明文", document.contains(token));
    }

    private static void assertDoesNotExposeAdminToken(String response, String token) {
        assertFalse("API 响应不能包含管理令牌字段：" + response,
                ADMIN_TOKEN_JSON_FIELD.matcher(response).find());
        assertFalse("API 响应不能包含管理令牌明文", response.contains(token));
    }

    private static void assertValidAdminTokenVerifier(String value) {
        Matcher matcher = ADMIN_TOKEN_VERIFIER.matcher(value == null ? "" : value);
        assertTrue("sidecar 必须保存版本化 PBKDF2 verifier", matcher.matches());
        int iterations = Integer.parseInt(matcher.group(1));
        assertTrue("PBKDF2 迭代次数超出允许范围", iterations >= 100_000 && iterations <= 1_000_000);
        byte[] salt = Base64.getUrlDecoder().decode(matcher.group(2));
        byte[] hash = Base64.getUrlDecoder().decode(matcher.group(3));
        assertTrue("PBKDF2 salt 长度无效", salt.length >= 16 && salt.length <= 64);
        assertTrue("PBKDF2 hash 长度无效", hash.length >= 32 && hash.length <= 64);
    }

    private void assertRestrictedTokenSidecar() throws IOException {
        assertEquals("sidecar 必须只包含一行令牌", 1,
                Files.readAllLines(adminTokenFile, StandardCharsets.UTF_8).size());
        PosixFileAttributeView posix = Files.getFileAttributeView(adminTokenFile, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posix.readAttributes().permissions());
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(adminTokenFile, AclFileAttributeView.class);
        assertTrue("当前文件系统必须支持 sidecar ACL", acl != null);
        assertFalse("sidecar ACL 不能为空", acl.getAcl().isEmpty());
        for (var entry : acl.getAcl()) {
            assertEquals("sidecar ACL 只能授权文件所有者", acl.getOwner(), entry.principal());
            assertEquals(AclEntryType.ALLOW, entry.type());
        }
        DosFileAttributeView dos = Files.getFileAttributeView(adminTokenFile, DosFileAttributeView.class);
        if (dos != null) assertTrue("Windows sidecar 应设置隐藏属性", dos.readAttributes().isHidden());
    }

    private static int countOccurrences(String value, String expected) {
        int count = 0;
        int from = 0;
        while (!expected.isEmpty()) {
            int found = value.indexOf(expected, from);
            if (found < 0) break;
            count++;
            from = found + expected.length();
        }
        return count;
    }

    private HttpResponse<String> signedRequest(String path, String method, String body) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString();
        String signature = hmac(timestamp + "\n" + nonce + "\n" + body);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3))
                .header("X-WOL-Timestamp", timestamp)
                .header("X-WOL-Nonce", nonce)
                .header("X-WOL-Signature", signature);
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.ofString(body));
        else request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> request(String url, String method, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3));
        if (token != null) request.header("X-WOL-Admin-Token", token);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> requestDocument(String url, String body, String contentType, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("X-WOL-Admin-Token", token)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PROXY_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void waitUntilListening() throws Exception {
        waitUntilListening(port);
    }

    private void waitUntilListening(int targetPort) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = request("http://127.0.0.1:" + targetPort + "/not-found", "GET", null, null);
                if (response.statusCode() == 404) return;
            } catch (Exception ignored) {
                // The child process may still be binding its listener.
            }
            Thread.sleep(100L);
        }
        fail("代理服务未在 15 秒内监听端口");
    }

    private void waitUntilUnavailable(int targetPort) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                request("http://127.0.0.1:" + targetPort + "/not-found", "GET", null, null);
            } catch (Exception expected) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("旧监听端口未在热切换后停止：" + targetPort);
    }

    private void waitUntilOutputContains(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(processOutput)
                    && Files.readString(processOutput, StandardCharsets.UTF_8).contains(expected)) return;
            Thread.sleep(50L);
        }
        fail("控制台未输出完整 Web 管理地址：" + expected);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private static String requiredMatch(Pattern pattern, String text, String name) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) fail("配置没有生成 " + name + ":\n" + text);
        return matcher.group(1);
    }

    private static void deleteTree(Path root) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 20 && Files.exists(root); attempt++) {
            try (var stream = Files.walk(root)) {
                var paths = stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList();
                for (Path path : paths) Files.deleteIfExists(path);
                return;
            } catch (IOException error) {
                lastFailure = error;
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("清理集成测试临时目录时被中断", interrupted);
                }
            }
        }
        if (Files.exists(root) && lastFailure != null) throw lastFailure;
    }
}
