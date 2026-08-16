/*
 * The page is intentionally dependency-free so it can be served directly by
 * the Java HttpServer and copied unchanged into Linux, Windows, or Docker
 * distributions. The server mounts these relative endpoints below the random
 * management path:
 *   GET  ./api/status
 *   GET  ./api/config
 *   POST ./api/config
 *   GET  ./api/config/export?format=yaml|json
 *   POST ./api/config/import?format=yaml|json
 *   GET  ./api/logs?from=&to=&event=&q=&offset=&limit=
 * Every request sends the management token in X-WOL-Admin-Token.
 */
(function () {
  "use strict";

  const apiBaseFromMeta = document.querySelector('meta[name="wol-admin-api"]');
  const API_ROOT = String(
    window.WOL_ADMIN_API_BASE || (apiBaseFromMeta && apiBaseFromMeta.content) || "./api"
  ).replace(/\/$/, "");
  const DEFAULT_LOG_PAGE_SIZE = 50;
  const LOG_PAGE_SIZES = [50, 80, 100, 150, 200];
  const STORAGE_TOKEN = "wol-admin-token";
  const PATH_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  const PATH_DIGITS = "23456789";
  const PATH_SPECIALS = "._~-!$&()*+,-;=@";
  const PATH_ALPHABET = PATH_LETTERS + PATH_DIGITS + PATH_SPECIALS;
  const ADMIN_PATH_PATTERN = /^[A-Za-z]{4,64}$/;
  const ADMIN_TOKEN_PATTERN = /^[\x21-\x7E]{6,256}$/;

  const state = {
    config: null,
    status: null,
    logs: [],
    logPage: 0,
    logPageSize: DEFAULT_LOG_PAGE_SIZE,
    logHasMore: false,
    logsLoaded: false,
    logsLoading: false,
    logTotal: null,
    autoRefreshTimer: null,
    toastTimer: null,
    loading: false,
    authenticated: false,
    token: sessionStorage.getItem(STORAGE_TOKEN) || "",
    keyConfigured: false,
    adminTokenConfigured: false,
    adminPath: ""
  };

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));

  class ApiError extends Error {
    constructor(message, status, body) {
      super(message);
      this.name = "ApiError";
      this.status = status;
      this.body = body;
    }
  }

  function apiUrl(path) {
    return API_ROOT + "/" + String(path).replace(/^\/+/, "");
  }

  async function request(path, options) {
    const init = Object.assign({ credentials: "same-origin" }, options || {});
    const responseType = init.responseType || "auto";
    delete init.responseType;
    const headers = new Headers(init.headers || {});
    if (!headers.has("Accept")) headers.set("Accept", "application/json");
    if (init.body && typeof init.body !== "string") {
      init.body = JSON.stringify(init.body);
    }
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json;charset=UTF-8");
    if (state.token) headers.set("X-WOL-Admin-Token", state.token);
    init.headers = headers;

    let response;
    try {
      response = await fetch(apiUrl(path), init);
    } catch (error) {
      throw new ApiError("无法连接管理接口", 0, { cause: error });
    }

    const text = await response.text();
    let body = responseType === "text" && response.ok ? text : null;
    if (text && body === null) {
      try { body = JSON.parse(text); } catch (_) { body = text; }
    }
    if (!response.ok) {
      const detail = body && typeof body === "object"
        ? (body.message || apiErrorCodeMessage(body.error) || body.detail)
        : "";
      const message = detail || httpErrorMessage(response.status);
      throw new ApiError(message, response.status, body);
    }
    if (body && typeof body === "object" && body.token) {
      state.token = String(body.token);
      sessionStorage.setItem(STORAGE_TOKEN, state.token);
    }
    state.authenticated = true;
    return body;
  }

  function httpErrorMessage(status) {
    if (status === 401 || status === 403) return "管理认证失败，请检查密码";
    if (status === 404) return "管理接口不存在";
    if (status === 409) return "配置正在被其他操作占用";
    if (status >= 500) return "代理服务暂时无法处理请求";
    return "请求失败（HTTP " + status + "）";
  }

  function apiErrorCodeMessage(code) {
    return ({
      admin_auth_required: "管理令牌无效或未填写",
      too_many_attempts: "认证尝试过于频繁，请稍后再试",
      invalid_config: "配置内容无效",
      config_save_failed: "配置文件保存失败",
      config_reload_failed: "配置文件重新读取失败",
      invalid_date: "日期格式无效",
      invalid_date_range: "开始日期不能晚于结束日期",
      payload_too_large: "提交内容过大",
      method_not_allowed: "请求方式不受支持",
      not_found: "管理接口不存在"
    })[String(code || "")] || "";
  }

  function text(value, fallback) {
    if (value === null || value === undefined || value === "") return fallback === undefined ? "--" : fallback;
    return String(value);
  }

  function first(source, keys, fallback) {
    if (!source || typeof source !== "object") return fallback;
    for (const key of keys) {
      if (source[key] !== undefined && source[key] !== null) return source[key];
    }
    return fallback;
  }

  function unwrap(payload, names) {
    if (!payload || typeof payload !== "object") return payload;
    for (const name of names) {
      if (payload[name] && typeof payload[name] === "object") return payload[name];
    }
    return payload;
  }

  function normalizeStatus(payload) {
    const value = unwrap(payload, ["status", "data"]);
    const uptime = first(value, ["uptime_seconds", "uptimeSeconds", "uptime"], null);
    const overrides = first(value, ["environment_overrides", "environmentOverrides", "overrides"], []);
    return {
      ok: first(value, ["ok", "online", "running"], true) !== false,
      version: text(first(value, ["version", "build"], "--")),
      listen: text(first(value, ["active_listen", "activeListen", "listen", "listen_address", "listenAddress"], "--")),
      uptime: uptime,
      requests: first(value, ["wake_requests", "wakeRequests", "requests", "request_count"], null),
      configPath: text(first(value, ["config_path", "configPath"], "--")),
      logPath: text(first(value, ["log_path", "logPath"], "--")),
      adminPath: text(first(value, ["admin_path", "adminPath", "management_path", "managementPath"], "--")),
      configuredListen: text(first(value, ["configured_listen", "configuredListen"], "--")),
      restartRequired: Boolean(first(value, ["restart_required", "restartRequired"], false)),
      overrides: Array.isArray(overrides) ? overrides : String(overrides || "").split(",").map(v => v.trim()).filter(Boolean),
      message: text(first(value, ["message", "state_message", "stateMessage"], ""), "")
    };
  }

  function normalizeListenPort(value) {
    const raw = String(value === null || value === undefined ? "" : value).trim();
    if (/^\d+$/.test(raw)) return raw;
    const match = raw.match(/:(\d+)$/);
    return match ? match[1] : raw;
  }

  function normalizeConfig(payload) {
    const value = unwrap(payload, ["config", "data"]);
    const nestedAdmin = value && typeof value.admin === "object" ? value.admin : {};
    const rawMacs = first(value, ["allow_macs", "allowMacs", "macs", "allowlist"], []);
    const macs = Array.isArray(rawMacs)
      ? rawMacs.map(v => String(v).trim()).filter(Boolean)
      : String(rawMacs || "").split(/[\n,]/).map(v => v.trim()).filter(Boolean);
    const key = first(value, ["key", "proxy_key", "proxyKey"], "");
    const keyConfigured = first(value, ["key_configured", "keyConfigured", "has_key", "hasKey"], null);
    const adminPath = first(value, ["admin_path", "adminPath", "management_path", "managementPath"],
      first(nestedAdmin, ["path", "entry"], ""));
    const adminToken = first(value, ["admin_token", "adminToken", "management_token", "managementToken"],
      first(nestedAdmin, ["token"], ""));
    const adminTokenConfigured = first(value,
      ["admin_token_configured", "adminTokenConfigured", "has_admin_token", "hasAdminToken"], null);
    const rawListen = first(value, ["listen", "listen_address", "listenAddress"], ":14250");
    return {
      listen: normalizeListenPort(rawListen),
      key: text(key, ""),
      defaultBroadcast: text(first(value, ["default_broadcast", "defaultBroadcast", "broadcast"], "255.255.255.255"), "255.255.255.255"),
      defaultPort: numberOr(first(value, ["default_port", "defaultPort", "port"], 9), 9),
      cooldownSeconds: numberOr(first(value, ["cooldown_seconds", "cooldownSeconds", "cooldown"], 5), 5),
      allowMacs: macs,
      adminPath: text(adminPath, ""),
      adminToken: text(adminToken, ""),
      adminTokenConfigured: adminTokenConfigured === null
        ? String(adminToken || "").trim() !== ""
        : Boolean(adminTokenConfigured),
      keyConfigured: keyConfigured === null ? String(key || "").trim() !== "" : Boolean(keyConfigured),
      dockerEnvironment: Boolean(first(value, ["docker_environment", "dockerEnvironment"], false))
    };
  }

  function numberOr(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function formatUptime(value) {
    if (value === null || value === undefined || value === "") return "--";
    const seconds = Math.max(0, Math.floor(Number(value)));
    if (!Number.isFinite(seconds)) return text(value);
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    if (days) return days + "天 " + hours + "小时";
    if (hours) return hours + "小时 " + minutes + "分";
    if (minutes) return minutes + "分 " + secs + "秒";
    return secs + "秒";
  }

  function setText(selector, value) {
    const node = $(selector);
    if (node) node.textContent = text(value);
  }

  function setStatusChip(node, label, kind) {
    if (!node) return;
    node.textContent = label;
    node.classList.remove("status-ok", "status-warn", "status-error", "status-neutral");
    node.classList.add(kind ? "status-" + kind : "status-neutral");
  }

  function renderStatus(status) {
    state.status = status;
    const isOk = status.ok;
    setStatusChip($("#header-status"), isOk ? "运行中" : "异常", isOk ? "ok" : "error");
    setText("#metric-state", isOk ? "运行中" : "异常");
    setText("#metric-state-detail", status.restartRequired ? "监听配置未能应用" : (status.message || (isOk ? "服务已就绪" : "请查看日志")));
    setText("#metric-version", status.version);
    setText("#metric-uptime", formatUptime(status.uptime));
    setText("#metric-requests", status.requests === null ? "--" : status.requests);
    const activePort = normalizeListenPort(status.listen);
    const configuredPort = normalizeListenPort(status.configuredListen);
    setText("#info-listen", status.restartRequired && configuredPort !== "--"
      ? activePort + "（配置值 " + configuredPort + " 未应用）"
      : activePort);
    setText("#info-config-path", status.configPath);
    setText("#info-log-path", status.logPath);
    setText("#info-admin-path", status.adminPath === "--" ? "--" : "/" + String(status.adminPath).replace(/^\/+/, ""));
    setText("#info-overrides", status.overrides.length ? status.overrides.join(", ") : "无");
    const notice = $("#overview-notice");
    if (notice) {
      notice.classList.remove("notice-neutral", "notice-ok", "notice-warn", "notice-error");
      notice.classList.add(status.restartRequired ? "notice-warn" : (isOk ? "notice-ok" : "notice-error"));
      notice.textContent = status.restartRequired
         ? "监听配置未能应用，当前监听仍可用，请检查端口占用后重新保存。"
        : (isOk ? "代理服务运行正常。" : (status.message || "代理服务状态异常，请查看运行日志。"));
    }
  }

  function fillConfig(config) {
    state.config = config;
    state.keyConfigured = config.keyConfigured;
    setValue("#config-listen", config.listen);
    const dockerListenHelp = $("#docker-listen-help");
    if (dockerListenHelp) dockerListenHelp.hidden = !config.dockerEnvironment;
    setValue("#config-key", config.key);
    const keyInput = $("#config-key");
    if (keyInput) {
      keyInput.required = !config.keyConfigured;
      keyInput.placeholder = config.keyConfigured && !config.key ? "已配置，留空保持不变" : "请输入连接 Key";
    }
    setValue("#config-broadcast", config.defaultBroadcast);
    setValue("#config-port", config.defaultPort);
    setValue("#config-cooldown", config.cooldownSeconds);
    setValue("#config-macs", config.allowMacs.join("\n"));
    setValue("#config-admin-path", String(config.adminPath || "").replace(/^\/+/, ""));
    setValue("#config-admin-token", "");
    state.adminTokenConfigured = Boolean(config.adminTokenConfigured || config.adminToken);
    const adminTokenInput = $("#config-admin-token");
    if (adminTokenInput) {
      adminTokenInput.required = !state.adminTokenConfigured;
      adminTokenInput.placeholder = state.adminTokenConfigured
        ? "已安全存储，留空保持不变"
        : "请输入新管理令牌";
    }
    state.adminPath = String(config.adminPath || "").replace(/^\/+/, "");
    setStatusChip($("#config-state"), "已读取", "ok");
  }

  function setValue(selector, value) {
    const node = $(selector);
    if (node) node.value = value === null || value === undefined ? "" : String(value);
  }

  function readConfigForm() {
    const key = $("#config-key").value.trim();
    const path = $("#config-admin-path").value.trim().replace(/^\/+/, "");
    const macs = $("#config-macs").value.split(/[\n,]/).map(v => v.trim()).filter(Boolean);
    const payload = {
      listen: Number(normalizeListenPort($("#config-listen").value)),
      default_broadcast: $("#config-broadcast").value.trim(),
      default_port: Number($("#config-port").value),
      cooldown_seconds: Number($("#config-cooldown").value),
      allow_macs: macs,
      admin_path: path
    };
    if (key) payload.key = key;
    else if (state.keyConfigured) payload.key_unchanged = true;
    const adminToken = $("#config-admin-token").value.trim();
    if (adminToken) payload.admin_token = adminToken;
    return payload;
  }

  function validateConfig(payload) {
    const errors = [];
    const listenPort = Number(normalizeListenPort(payload.listen));
    if (!Number.isInteger(listenPort) || listenPort < 1 || listenPort > 65535) {
      errors.push(["#config-listen", "监听端口范围必须是 1-65535"]);
    }
    const port = Number(payload.default_port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) errors.push(["#config-port", "端口范围必须是 1-65535"]);
    const cooldown = Number(payload.cooldown_seconds);
    if (!Number.isInteger(cooldown) || cooldown < 0 || cooldown > 86400) errors.push(["#config-cooldown", "冷却时间必须是 0-86400 秒"]);
    if (!isIpv4(String(payload.default_broadcast || ""))) errors.push(["#config-broadcast", "请输入有效的 IPv4 地址"]);
    if (!payload.key && !state.keyConfigured) errors.push(["#config-key", "请输入代理连接 Key"]);
    for (const mac of payload.allow_macs) {
      if (!/^(([0-9a-f]{2}[:-]){5}[0-9a-f]{2}|[0-9a-f]{12})$/i.test(mac)) {
        errors.push(["#config-macs", "MAC 白名单中存在无效地址：" + mac]);
        break;
      }
    }
    if (!ADMIN_PATH_PATTERN.test(payload.admin_path || "")) errors.push(["#config-admin-path", "管理入口须为 4-64 位大小写字母"]);
    if (payload.admin_token && !ADMIN_TOKEN_PATTERN.test(payload.admin_token)) {
      errors.push(["#config-admin-token", "管理令牌须为 6-256 位可见字符，不能包含空格"]);
    } else if (!payload.admin_token && !state.adminTokenConfigured) {
      errors.push(["#config-admin-token", "请输入管理令牌"]);
    }
    return errors;
  }

  function isIpv4(value) {
    const parts = value.trim().split(".");
    return parts.length === 4 && parts.every(part => /^\d{1,3}$/.test(part) && Number(part) >= 0 && Number(part) <= 255);
  }

  function clearInvalidFields() {
    $$("[aria-invalid='true']").forEach(node => node.removeAttribute("aria-invalid"));
  }

  function showValidation(errors) {
    clearInvalidFields();
    for (const [selector] of errors) {
      const input = $(selector);
      if (input) input.setAttribute("aria-invalid", "true");
    }
    const message = $("#config-form-message");
    if (message) {
      message.textContent = errors[0] ? errors[0][1] : "";
      message.className = "form-message" + (errors.length ? " is-error" : "");
    }
    const firstInput = errors[0] && $(errors[0][0]);
    if (firstInput) firstInput.focus({ preventScroll: false });
  }

  function clearFormMessage() {
    const message = $("#config-form-message");
    if (message) { message.textContent = ""; message.className = "form-message"; }
    clearInvalidFields();
  }

  function classifyEvent(event, detail) {
    const value = (String(event || "") + " " + String(detail || "")).toLowerCase();
    if (/(失败|拒绝|错误|失敗|failure|error|reject|denied)/.test(value)) return value.includes("拒绝") || value.includes("reject") || value.includes("denied") ? "reject" : "failure";
    if (/(启动|停止|配置|服务|start|stop|config|service)/.test(value)) return "service";
    if (/(成功|已发送|success|ok|正常)/.test(value)) return "success";
    return "info";
  }

  function normalizeLogItem(item) {
    if (typeof item === "string") return parseLogLine(item);
    const value = item && typeof item === "object" ? item : {};
    const raw = first(value, ["raw", "line", "text"], "");
    const event = first(value, ["event", "type", "level"], "运行信息");
    const detail = first(value, ["detail", "message", "msg"], raw || "");
    const timestamp = first(value, ["timestamp", "time", "datetime", "date"], "");
    return {
      timestamp: text(timestamp, "--"),
      event: text(event, "运行信息"),
      detail: text(detail, ""),
      kind: classifyEvent(event, detail)
    };
  }

  function parseLogLine(line) {
    const match = String(line).match(/^\[([^\]]+)\]\[([^\]]+)\](.*)$/);
    if (!match) return { timestamp: "--", event: "运行信息", detail: String(line), kind: classifyEvent("", line) };
    return { timestamp: match[1], event: match[2], detail: match[3] || "", kind: classifyEvent(match[2], match[3]) };
  }

  function normalizeLogs(payload) {
    if (typeof payload === "string") {
      return { items: payload.split(/\r?\n/).filter(Boolean).map(normalizeLogItem), hasMore: false, total: null };
    }
    const value = unwrap(payload, ["logs", "data"]);
    const rawItems = Array.isArray(value) ? value : first(value, ["items", "entries", "lines", "records"], []);
    // The server already returns each page newest-first. Preserve that order so
    // subsequent pages can be appended without interleaving older entries.
    const items = Array.isArray(rawItems) ? rawItems.map(normalizeLogItem) : [];
    return {
      items,
      hasMore: Boolean(first(payload, ["has_more", "hasMore", "next"], first(value, ["has_more", "hasMore", "next"], false))),
      total: first(payload, ["total", "count"], first(value, ["total", "count"], null))
    };
  }

  function renderLogs() {
    const list = $("#logs-list");
    if (!list) return;
    if (!state.logs.length) {
      list.innerHTML = "<div class=\"empty-state\">当前筛选范围内没有日志。</div>";
    } else {
      list.replaceChildren(...state.logs.map(item => {
        const row = document.createElement("article");
        row.className = "log-row";
        const time = document.createElement("time");
        time.className = "log-time";
        time.textContent = item.timestamp;
        const event = document.createElement("span");
        event.className = "log-event is-" + item.kind;
        event.textContent = item.event;
        const detail = document.createElement("span");
        detail.className = "log-detail";
        detail.textContent = item.detail;
        row.append(time, event, detail);
        return row;
      }));
    }
    const shown = state.logs.length;
    const total = state.logTotal === null || state.logTotal === undefined ? NaN : Number(state.logTotal);
    const hasKnownTotal = Number.isFinite(total) && total >= 0;
    const totalPages = hasKnownTotal
      ? Math.max(1, Math.ceil(total / state.logPageSize))
      : state.logHasMore ? state.logPage + 2 : state.logPage + 1;
    const pageNumber = state.logPage + 1;
    const summary = state.logsLoaded
      ? (hasKnownTotal
        ? "共 " + total + " 条，第 " + pageNumber + "/" + totalPages + " 页，本页 " + shown + " 条"
        : "第 " + pageNumber + " 页，本页 " + shown + " 条")
      : "未加载日志";
    setText("#logs-summary", summary);
    setText("#logs-page-status", "第 " + pageNumber + " / " + totalPages + " 页");
    const previous = $("#logs-prev");
    const next = $("#logs-next");
    if (previous) previous.disabled = state.logsLoading || state.logPage <= 0;
    if (next) next.disabled = state.logsLoading || !state.logHasMore;
  }

  function logQueryParams() {
    const params = new URLSearchParams();
    const from = $("#logs-from").value;
    const to = $("#logs-to").value;
    const event = $("#logs-event").value;
    const query = $("#logs-query").value.trim();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    if (event) params.set("event", event);
    if (query) params.set("q", query);
    params.set("offset", String(state.logPage * state.logPageSize));
    params.set("limit", String(state.logPageSize));
    return params;
  }

  async function loadLogs(reset) {
    if (reset) {
      state.logPage = 0;
      state.logHasMore = false;
      state.logTotal = null;
      state.logs = [];
      state.logsLoaded = false;
      renderLogs();
    }
    if (state.logsLoading) return;
    state.logsLoading = true;
    renderLogs();
    const list = $("#logs-list");
    if (!state.logs.length && list) list.innerHTML = "<div class=\"empty-state\">正在读取日志。</div>";
    try {
      const payload = await request("logs?" + logQueryParams().toString(), { method: "GET" });
      const result = normalizeLogs(payload);
      state.logs = result.items;
      state.logHasMore = Boolean(result.hasMore);
      state.logTotal = result.total;
      state.logsLoaded = true;
    } catch (error) {
      if (handleAuthError(error)) return;
      state.logsLoaded = true;
      if (list) list.innerHTML = "<div class=\"empty-state\">" + escapeHtml(error.message || "日志读取失败") + "</div>";
      setText("#logs-summary", "日志读取失败");
    } finally {
      state.logsLoading = false;
      renderLogs();
    }
  }

  function changeLogPage(delta) {
    if (state.logsLoading) return;
    const nextPage = state.logPage + delta;
    if (nextPage < 0 || (delta > 0 && !state.logHasMore)) return;
    state.logPage = nextPage;
    loadLogs(false);
  }

  function changeLogPageSize(value) {
    const pageSize = Number(value);
    if (!LOG_PAGE_SIZES.includes(pageSize)) return;
    state.logPageSize = pageSize;
    loadLogs(true);
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" }[char]));
  }

  async function loadConfig() {
    try {
      const payload = await request("config", { method: "GET" });
      fillConfig(normalizeConfig(payload));
    } catch (error) {
      if (handleAuthError(error)) return;
      setStatusChip($("#config-state"), "读取失败", "error");
      showFormMessage(error.message, true);
    }
  }

  async function reloadConfigFromYaml() {
    clearFormMessage();
    const button = $("#reload-config");
    const previousPath = state.adminPath;
    if (button) { button.disabled = true; button.textContent = "读取中…"; }
    try {
      const payload = await request("reload", { method: "POST" });
      const config = normalizeConfig(payload);
      fillConfig(config);
      if (config.adminToken && config.adminToken !== state.token) {
        state.token = config.adminToken;
        sessionStorage.setItem(STORAGE_TOKEN, state.token);
      }
      showFormMessage("已从 YAML 重新读取配置。", false, true);
      showToast("已重新读取配置");
      const newPath = config.adminPath || previousPath;
      if (shouldNavigateAfterConfig(payload, newPath, previousPath)) {
        showToast("监听或管理入口已更新，正在打开新地址");
        window.setTimeout(() => navigateToRuntime(newPath, payload.runtime), 800);
      } else {
        await loadStatus();
      }
    } catch (error) {
      if (!handleAuthError(error)) showFormMessage(error.message || "重新读取失败", true);
    } finally {
      if (button) { button.disabled = false; button.textContent = "重新读取"; }
    }
  }

  async function loadStatus() {
    try {
      const payload = await request("status", { method: "GET" });
      renderStatus(normalizeStatus(payload));
      return true;
    } catch (error) {
      if (handleAuthError(error)) return false;
      renderStatus({ ok: false, version: "--", listen: "--", uptime: null, requests: null, configPath: "--", logPath: "--", adminPath: "--", overrides: [], message: error.message });
      return false;
    }
  }

  async function refreshAll() {
    if (state.loading) return;
    state.loading = true;
    try {
      const activePanel = $(".view-panel.is-active");
      await loadStatus();
      if (activePanel && activePanel.dataset.panel === "config") await loadConfig();
      if (activePanel && activePanel.dataset.panel === "logs") await loadLogs(true);
    } finally {
      state.loading = false;
    }
  }

  async function saveConfig(event) {
    event.preventDefault();
    clearFormMessage();
    const payload = readConfigForm();
    const errors = validateConfig(payload);
    if (errors.length) { showValidation(errors); return; }
    const button = $("#save-config");
    if (button) { button.disabled = true; button.textContent = "保存中…"; }
    showFormMessage("正在保存并应用…", false);
    const previousAdminPath = state.adminPath;
    try {
      let response;
      try {
        response = await request("config", { method: "POST", body: payload });
      } catch (error) {
        if (error.status !== 405) throw error;
        response = await request("config", { method: "PUT", body: payload });
      }
      const result = response && typeof response === "object" ? response : {};
      const returnedConfig = result.config || result.data;
      if (returnedConfig && typeof returnedConfig === "object") fillConfig(normalizeConfig(returnedConfig));
      else {
        state.keyConfigured = true;
        state.config = Object.assign(state.config || {}, payload, { keyConfigured: true });
        if (payload.key) state.config.key = payload.key;
      }
      if (payload.admin_token && payload.admin_token !== state.token) {
        state.token = payload.admin_token;
        state.adminTokenConfigured = true;
        sessionStorage.setItem(STORAGE_TOKEN, state.token);
      }
      const successMessage = result.message || "配置已保存并立即生效";
      showFormMessage(successMessage, false, true);
      showToast("配置已保存并应用");
      const returnedPath = extractAdminPath(result);
      const newPath = (returnedPath || payload.admin_path || "").replace(/^\/+/, "");
      if (shouldNavigateAfterConfig(result, newPath, previousAdminPath)) {
        showToast("监听或管理入口已更新，正在打开新地址");
        window.setTimeout(() => navigateToRuntime(newPath, result.runtime), 800);
      } else {
        await loadStatus();
      }
      state.adminPath = newPath || state.adminPath;
    } catch (error) {
      if (!handleAuthError(error)) showFormMessage(error.message || "保存失败", true);
    } finally {
      if (button) { button.disabled = false; button.textContent = "保存并应用"; }
    }
  }

  function extractAdminPath(payload) {
    if (!payload || typeof payload !== "object") return "";
    const nested = payload.config && typeof payload.config === "object" ? payload.config : {};
    return String(first(payload, ["admin_path", "adminPath", "management_path", "managementPath", "redirect_path", "redirectPath"], first(nested, ["admin_path", "adminPath", "path"], "")) || "").replace(/^\/+/, "");
  }

  function shouldNavigateAfterConfig(payload, path, previousPath) {
    const runtime = payload && typeof payload === "object" ? payload.runtime : null;
    const activeListen = runtime && first(runtime, ["active_listen", "activeListen", "listen"], "");
    const activePort = normalizeListenPort(activeListen);
    const currentPort = window.location.port || (window.location.protocol === "https:" ? "443" : "80");
    return Boolean(path && previousPath && path !== previousPath) || Boolean(activePort && activePort !== currentPort);
  }

  function navigateToRuntime(path, runtime) {
    const clean = String(path || "").replace(/^\/+|\/+$/g, "");
    if (!clean) return;
    const target = new URL(window.location.href);
    const activeListen = runtime && first(runtime, ["active_listen", "activeListen", "listen"], "");
    const activePort = normalizeListenPort(activeListen);
    if (/^\d+$/.test(activePort)) target.port = activePort;
    target.pathname = "/" + encodeURIComponent(clean) + "/";
    target.search = "";
    target.hash = "";
    window.location.assign(target.toString());
  }

  function showFormMessage(message, isError, isOk) {
    const node = $("#config-form-message");
    if (!node) return;
    node.textContent = message || "";
    node.className = "form-message" + (isError ? " is-error" : isOk ? " is-ok" : "");
  }

  function showToast(message) {
    const toast = $("#toast");
    if (!toast) return;
    window.clearTimeout(state.toastTimer);
    toast.textContent = message;
    toast.classList.add("is-visible");
    state.toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 2800);
  }

  function handleAuthError(error) {
    if (!error || (error.status !== 401 && error.status !== 403)) return false;
    state.authenticated = false;
    state.token = "";
    sessionStorage.removeItem(STORAGE_TOKEN);
    showLogin(error.status === 403 ? "管理令牌无效" : "请先登录管理后台");
    return true;
  }

  function showLogin(message) {
    $("#loading-view").classList.add("hidden");
    $("#app-view").classList.add("hidden");
    $("#login-view").classList.remove("hidden");
    $("#logout-button").classList.add("hidden");
    const node = $("#login-message");
    if (node) { node.textContent = message || ""; node.className = "form-message" + (message ? " is-error" : ""); }
    window.setTimeout(() => $("#login-password")?.focus(), 0);
  }

  function showApp() {
    $("#loading-view").classList.add("hidden");
    $("#login-view").classList.add("hidden");
    $("#app-view").classList.remove("hidden");
    $("#logout-button").classList.remove("hidden");
  }

  async function login(event) {
    event.preventDefault();
    const input = $("#login-password");
    const button = $("#login-form button[type='submit']");
    const message = $("#login-message");
    if (!input.value.trim()) { if (message) { message.textContent = "请输入管理令牌"; message.className = "form-message is-error"; } return; }
    if (button) { button.disabled = true; button.textContent = "验证中…"; }
    const suppliedToken = input.value.trim();
    try {
      state.token = suppliedToken;
      sessionStorage.setItem(STORAGE_TOKEN, state.token);
      const statusPayload = await request("status", { method: "GET" });
      input.value = "";
      state.authenticated = true;
      showApp();
      renderStatus(normalizeStatus(statusPayload));
      await loadConfig();
      await loadLogs(true);
      showToast("登录成功");
    } catch (error) {
      state.token = "";
      sessionStorage.removeItem(STORAGE_TOKEN);
      if (message) { message.textContent = error.message || "登录失败"; message.className = "form-message is-error"; }
    } finally {
      if (button) { button.disabled = false; button.textContent = "进入管理后台"; }
    }
  }

  function logout() {
    state.token = "";
    state.authenticated = false;
    sessionStorage.removeItem(STORAGE_TOKEN);
    showLogin("");
  }

  function switchView(name) {
    const target = name || "overview";
    $$(".tab-button").forEach(button => button.classList.toggle("is-active", button.dataset.view === target));
    $$(".view-panel").forEach(panel => {
      const active = panel.dataset.panel === target;
      panel.classList.toggle("is-active", active);
      panel.hidden = !active;
    });
    if (target === "config" && !state.config) loadConfig();
    if (target === "logs" && !state.logsLoaded) loadLogs(true);
    if (window.history && window.history.replaceState) window.history.replaceState(null, "", "#" + target);
  }

  function toggleKeyVisibility() {
    const input = $("#config-key");
    const button = $("#toggle-key");
    if (!input || !button) return;
    const visible = input.type === "text";
    input.type = visible ? "password" : "text";
    button.textContent = visible ? "显示" : "隐藏";
    button.setAttribute("aria-label", (visible ? "显示" : "隐藏") + "代理连接 Key");
  }

  function secureRandomIndexes(length) {
    const values = new Uint32Array(length);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(values);
    else for (let index = 0; index < values.length; index++) values[index] = Math.floor(Math.random() * 0xffffffff);
    return Array.from(values);
  }

  function randomAdminPath() {
    const values = secureRandomIndexes(8);
    const chars = [
      PATH_LETTERS[values[0] % PATH_LETTERS.length],
      PATH_DIGITS[values[1] % PATH_DIGITS.length],
      PATH_SPECIALS[values[2] % PATH_SPECIALS.length]
    ];
    for (let index = 3; index < values.length; index++) chars.push(PATH_ALPHABET[values[index] % PATH_ALPHABET.length]);
    for (let index = chars.length - 1; index > 0; index--) {
      const swap = values[index] % (index + 1);
      [chars[index], chars[swap]] = [chars[swap], chars[index]];
    }
    return chars.join("");
  }

  function randomAdminToken() {
    const values = secureRandomIndexes(24);
    const chars = [
      PATH_LETTERS[values[0] % PATH_LETTERS.length],
      PATH_DIGITS[values[1] % PATH_DIGITS.length],
      PATH_SPECIALS[values[2] % PATH_SPECIALS.length]
    ];
    for (let index = 3; index < values.length; index++) chars.push(PATH_ALPHABET[values[index] % PATH_ALPHABET.length]);
    for (let index = chars.length - 1; index > 0; index--) {
      const swap = values[index] % (index + 1);
      [chars[index], chars[swap]] = [chars[swap], chars[index]];
    }
    return chars.join("");
  }

  async function exportConfig() {
    const button = $("#export-config");
    const format = $("#config-transfer-format").value === "json" ? "json" : "yaml";
    if (button) button.disabled = true;
    try {
      const isJson = format === "json";
      const content = await request("config/export?format=" + format, {
        method: "GET",
        headers: { Accept: isJson ? "application/json" : "application/yaml" },
        responseType: "text"
      });
      const blob = new Blob([content || ""], { type: (isJson ? "application/json" : "application/yaml") + ";charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "wol-proxy-config-" + localDateString(new Date()) + (isJson ? ".json" : ".yml");
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      showToast("配置已导出");
    } catch (error) {
      if (!handleAuthError(error)) showFormMessage(error.message || "导出失败", true);
    } finally {
      if (button) button.disabled = false;
    }
  }

  async function importConfigFile(file) {
    if (!file) return;
    const button = $("#import-config");
    if (button) button.disabled = true;
    clearFormMessage();
    const previousPath = state.adminPath;
    try {
      if (file.size > 64 * 1024) throw new Error("配置文件不能超过 64 KB");
      const content = (await file.text()).replace(/^\uFEFF/, "");
      const format = detectConfigFileFormat(file, content);
      setValue("#config-transfer-format", format);
      const result = await request("config/import?format=" + format, {
        method: "POST",
        headers: { "Content-Type": format === "json" ? "application/json;charset=UTF-8" : "application/yaml;charset=UTF-8" },
        body: content
      });
      const config = normalizeConfig(result);
      fillConfig(config);
      if (result && result.admin_token_changed) {
        state.token = "";
        state.authenticated = false;
        sessionStorage.removeItem(STORAGE_TOKEN);
        showLogin("配置已导入；管理令牌已变更，请使用导入文件中的新令牌登录");
        return;
      }
      const newPath = config.adminPath || previousPath;
      showFormMessage(result.message || "配置已导入并立即应用", false, true);
      showToast("配置已导入并应用");
      if (shouldNavigateAfterConfig(result, newPath, previousPath)) {
        window.setTimeout(() => navigateToRuntime(newPath, result.runtime), 800);
      } else {
        await loadStatus();
      }
    } catch (error) {
      if (!handleAuthError(error)) showFormMessage(error.message || "导入失败", true);
    } finally {
      if (button) button.disabled = false;
      $("#import-config-file").value = "";
    }
  }

  function detectConfigFileFormat(file, content) {
    const name = String(file && file.name || "").toLowerCase();
    const type = String(file && file.type || "").toLowerCase();
    if (name.endsWith(".json") || type.includes("json")) return "json";
    if (/\.ya?ml$/.test(name) || type.includes("yaml") || type.includes("yml")) return "yaml";
    return String(content || "").trimStart().startsWith("{") ? "json" : "yaml";
  }

  function resetLogDates() {
    const today = localDateString(new Date());
    setValue("#logs-from", today);
    setValue("#logs-to", today);
  }

  function clearLogDates() {
    setValue("#logs-from", "");
    setValue("#logs-to", "");
  }

  function localDateString(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return year + "-" + month + "-" + day;
  }

  function exportLogs() {
    if (!state.logs.length) { showToast("当前没有可导出的日志"); return; }
    const content = state.logs.map(item => "[" + item.timestamp + "][" + item.event + "]" + item.detail).join("\n") + "\n";
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "wol-proxy-logs-page-" + (state.logPage + 1) + "-" + localDateString(new Date()) + ".log";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function setAutoRefresh(enabled) {
    window.clearInterval(state.autoRefreshTimer);
    state.autoRefreshTimer = null;
    if (enabled) state.autoRefreshTimer = window.setInterval(() => {
      if (state.authenticated && $("[data-panel='logs']")?.classList.contains("is-active")) loadLogs(true);
    }, 15000);
  }

  function bindEvents() {
    $$(".tab-button").forEach(button => button.addEventListener("click", () => switchView(button.dataset.view)));
    $("#header-refresh").addEventListener("click", refreshAll);
    $("#overview-refresh").addEventListener("click", loadStatus);
    $("#logout-button").addEventListener("click", logout);
    $("#login-form").addEventListener("submit", login);
    $("#config-form").addEventListener("submit", saveConfig);
    $("#import-config").addEventListener("click", () => $("#import-config-file").click());
    $("#import-config-file").addEventListener("change", event => importConfigFile(event.target.files && event.target.files[0]));
    $("#export-config").addEventListener("click", exportConfig);
    $("#reload-config").addEventListener("click", reloadConfigFromYaml);
    $("#toggle-key").addEventListener("click", toggleKeyVisibility);
    $("#generate-admin-path").addEventListener("click", () => setValue("#config-admin-path", randomAdminPath()));
    $("#generate-admin-token").addEventListener("click", () => setValue("#config-admin-token", randomAdminToken()));
    $("#logs-filter").addEventListener("submit", event => { event.preventDefault(); loadLogs(true); });
    $("#logs-refresh").addEventListener("click", () => loadLogs(true));
    $("#logs-prev").addEventListener("click", () => changeLogPage(-1));
    $("#logs-next").addEventListener("click", () => changeLogPage(1));
    $("#logs-page-size").addEventListener("change", event => changeLogPageSize(event.target.value));
    $("#logs-clear").addEventListener("click", () => { clearLogDates(); setValue("#logs-event", ""); setValue("#logs-query", ""); loadLogs(true); });
    $("#logs-export").addEventListener("click", exportLogs);
    $("#logs-auto-refresh").addEventListener("change", event => setAutoRefresh(event.target.checked));
  }

  async function bootstrap() {
    bindEvents();
    resetLogDates();
    setAutoRefresh(Boolean($("#logs-auto-refresh")?.checked));
    const hash = window.location.hash.slice(1);
    if (["overview", "config", "logs"].includes(hash)) switchView(hash);
    try {
      const statusPayload = await request("status", { method: "GET" });
      state.authenticated = true;
      showApp();
      renderStatus(normalizeStatus(statusPayload));
      await Promise.all([loadConfig(), loadLogs(true)]);
    } catch (error) {
      if (error.status === 401 || error.status === 403) {
        state.token = "";
        sessionStorage.removeItem(STORAGE_TOKEN);
        showLogin("请先登录管理后台");
      }
      else {
        showLogin(error.message || "无法连接管理服务");
        const node = $("#login-message");
        if (node) node.className = "form-message is-error";
      }
    }
  }

  document.addEventListener("DOMContentLoaded", bootstrap);
})();
