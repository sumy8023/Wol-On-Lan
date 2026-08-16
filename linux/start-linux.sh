#!/usr/bin/env sh
set -eu

umask 027

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
LIB_FILE="$SCRIPT_DIR/linux-installer-lib.sh"

if [ -f "$LIB_FILE" ]; then
  # Source-tree and legacy package compatibility.
  # shellcheck disable=SC1090,SC1091
  . "$LIB_FILE"
else
  # Release archives embed the parser helpers after the installer body so the
  # helper is not exposed as a separate user-facing script.
  wol_embedded_lib=$(mktemp "${TMPDIR:-/tmp}/wol-installer-lib.XXXXXX")
  trap 'rm -f "$wol_embedded_lib"' EXIT HUP INT TERM
  sed -n '/^__WOL_EMBEDDED_LIB_BEGIN__$/,/^__WOL_EMBEDDED_LIB_END__$/ { /^__WOL_EMBEDDED_LIB_BEGIN__$/d; /^__WOL_EMBEDDED_LIB_END__$/d; p; }' "$0" >"$wol_embedded_lib"
  if [ ! -s "$wol_embedded_lib" ]; then
    echo "错误：安装包不完整，缺少内置安装程序组件。" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  . "$wol_embedded_lib"
  rm -f "$wol_embedded_lib"
  trap - EXIT HUP INT TERM
fi

SERVICE_NAME="wol-proxy.service"
SERVICE_USER="wol-proxy"
INSTALL_DIR="/opt/wol-proxy"
DATA_DIR="/var/lib/wol-proxy"
CONFIG_FILE="$DATA_DIR/config.yml"
TOKEN_SIDECAR="$CONFIG_FILE.admin-token"
UNIT_FILE="/etc/systemd/system/$SERVICE_NAME"
CONFIG_SOURCE=${WOL_PROXY_CONFIG:-}
AUTO_INSTALL_JAVA=${WOL_PROXY_AUTO_INSTALL_JAVA:-1}
REQUESTED_JAVA_HOME=${JAVA_HOME:-}

case "$AUTO_INSTALL_JAVA" in
  0|1) ;;
  *) echo "错误：WOL_PROXY_AUTO_INSTALL_JAVA 只能是 0 或 1。" >&2; exit 2 ;;
esac

usage() {
  cat <<'EOF'
WOL Proxy Linux 安装程序

用法：
  ./start-linux.sh [--config /path/to/config.yml] [--java-home /path/to/jdk] [--no-install-java]

选项：
  --config FILE       首次安装时导入指定配置；已有配置不会被覆盖
  --java-home DIR     使用指定的 Java 17 或更高版本
  --no-install-java   Java 版本不足时只显示安装命令，不自动安装
  -h, --help          显示帮助

重复执行会升级程序、保留 /var/lib/wol-proxy 中的配置和日志，并重启服务。
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --config)
      [ "$#" -ge 2 ] || { echo "错误：--config 后必须填写文件路径。" >&2; exit 2; }
      CONFIG_SOURCE=$2
      shift 2
      ;;
    --no-install-java)
      AUTO_INSTALL_JAVA=0
      shift
      ;;
    --java-home)
      [ "$#" -ge 2 ] || { echo "错误：--java-home 后必须填写目录。" >&2; exit 2; }
      REQUESTED_JAVA_HOME=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "错误：未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ -n "$CONFIG_SOURCE" ] && [ ! -f "$CONFIG_SOURCE" ]; then
  echo "错误：指定的配置文件不存在：$CONFIG_SOURCE" >&2
  exit 1
fi

if [ ! -f "$SCRIPT_DIR/lib/wol-proxy.jar" ] || [ ! -f "$SCRIPT_DIR/bin/wol-proxy" ] || [ ! -f "$SCRIPT_DIR/wol" ]; then
  echo "错误：安装包不完整，缺少程序文件或 wol 管理命令。" >&2
  echo "请从 WOL-Proxy-Linux.tar 解压后的目录运行此脚本。" >&2
  exit 1
fi

become_root() {
  if [ "$(id -u)" -eq 0 ]; then
    return 0
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    echo "错误：安装 systemd 服务需要管理员权限。" >&2
    echo "请先安装 sudo，或使用 root 执行：sudo ./start-linux.sh" >&2
    exit 1
  fi
  echo "安装 WOL Proxy 需要写入 /opt、/var/lib 和 /etc/systemd/system。"
  echo "接下来 sudo 可能要求输入当前用户密码。"
  set --
  [ -z "$CONFIG_SOURCE" ] || set -- "$@" --config "$CONFIG_SOURCE"
  [ -z "$REQUESTED_JAVA_HOME" ] || set -- "$@" --java-home "$REQUESTED_JAVA_HOME"
  [ "$AUTO_INSTALL_JAVA" != "0" ] || set -- "$@" --no-install-java
  exec sudo sh "$SCRIPT_DIR/start-linux.sh" "$@"
}

java_install_hint() {
  case "$1" in
    apt-get) printf '%s\n' "apt-get update && apt-get install -y openjdk-17-jre-headless" ;;
    apt) printf '%s\n' "apt update && apt install -y openjdk-17-jre-headless" ;;
    dnf) printf '%s\n' "dnf install -y java-17-openjdk-headless" ;;
    yum) printf '%s\n' "yum install -y java-17-openjdk-headless" ;;
    pacman) printf '%s\n' "pacman -Sy --noconfirm --needed jre17-openjdk-headless" ;;
    zypper) printf '%s\n' "zypper --non-interactive install java-17-openjdk-headless" ;;
  esac
}

install_java17() {
  wol_manager=$(wol_detect_package_manager || true)
  if [ -z "$wol_manager" ]; then
    echo "错误：未找到 apt、dnf、yum、pacman 或 zypper。" >&2
    echo "请手动安装 Java 17 或更高版本，然后重新运行安装程序。" >&2
    exit 1
  fi
  wol_hint=$(java_install_hint "$wol_manager")
  if [ "$AUTO_INSTALL_JAVA" = "0" ]; then
    echo "错误：需要 Java 17 或更高版本。请执行：" >&2
    echo "  sudo sh -c '$wol_hint'" >&2
    exit 1
  fi
  echo "未检测到 Java 17 或更高版本，将使用 $wol_manager 自动安装："
  echo "  $wol_hint"
  case "$wol_manager" in
    apt-get)
      DEBIAN_FRONTEND=noninteractive apt-get update
      DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jre-headless
      ;;
    apt)
      DEBIAN_FRONTEND=noninteractive apt update
      DEBIAN_FRONTEND=noninteractive apt install -y openjdk-17-jre-headless
      ;;
    dnf) dnf install -y java-17-openjdk-headless ;;
    yum) yum install -y java-17-openjdk-headless ;;
    pacman) pacman -Sy --noconfirm --needed jre17-openjdk-headless ;;
    zypper) zypper --non-interactive install java-17-openjdk-headless ;;
  esac
}

ensure_service_user() {
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v useradd >/dev/null 2>&1; then
    echo "错误：系统缺少 useradd，无法创建低权限服务用户。" >&2
    exit 1
  fi
  wol_nologin=$(command -v nologin 2>/dev/null || true)
  [ -n "$wol_nologin" ] || wol_nologin=/usr/sbin/nologin
  useradd --system --user-group --home-dir "$DATA_DIR" --shell "$wol_nologin" "$SERVICE_USER"
}

write_systemd_unit() {
  wol_java_home=$1
  case "$wol_java_home" in
    *'"'*|*'%'*)
      echo "错误：Java 安装路径包含 systemd 不支持的字符：$wol_java_home" >&2
      exit 1
      ;;
  esac
  wol_group=$(id -gn "$SERVICE_USER")
  wol_unit_tmp=$(mktemp "${TMPDIR:-/tmp}/wol-proxy.service.XXXXXX")
  trap 'rm -f "$wol_unit_tmp"' EXIT
  trap 'rm -f "$wol_unit_tmp"; exit 1' HUP INT TERM
  cat >"$wol_unit_tmp" <<EOF
[Unit]
Description=WOL Proxy Service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$wol_group
WorkingDirectory=$DATA_DIR
Environment="HOME=$DATA_DIR"
Environment="JAVA_HOME=$wol_java_home"
Environment="JAVA_OPTS=-Duser.home=$DATA_DIR -Dfile.encoding=UTF-8"
ExecStart=$INSTALL_DIR/bin/wol-proxy --config $CONFIG_FILE
Restart=on-failure
RestartSec=3s
TimeoutStopSec=15s
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$DATA_DIR
StandardOutput=journal
StandardError=journal
SyslogIdentifier=wol-proxy

[Install]
WantedBy=multi-user.target
EOF
  install -m 0644 "$wol_unit_tmp" "$UNIT_FILE"
  rm -f "$wol_unit_tmp"
  trap - EXIT HUP INT TERM
}

current_invocation_logs() {
  wol_invocation_id=$1
  [ -n "$wol_invocation_id" ] || return 0
  command -v journalctl >/dev/null 2>&1 || return 0
  journalctl --no-pager -o cat "_SYSTEMD_INVOCATION_ID=$wol_invocation_id" 2>/dev/null || true
}

wait_for_service() {
  wol_attempt=0
  while [ "$wol_attempt" -lt 30 ]; do
    if systemctl is-active --quiet "$SERVICE_NAME"; then
      return 0
    fi
    if systemctl is-failed --quiet "$SERVICE_NAME"; then
      return 1
    fi
    wol_attempt=$((wol_attempt + 1))
    sleep 1
  done
  return 1
}

config_admin_url() {
  wol_listen=$1
  wol_path=$2
  wol_port=${wol_listen##*:}
  case "$wol_port" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ -n "$wol_path" ] || return 1
  printf 'http://127.0.0.1:%s/%s/\n' "$wol_port" "$wol_path"
}

become_root

if [ -n "$REQUESTED_JAVA_HOME" ]; then
  JAVA_HOME=$REQUESTED_JAVA_HOME
  export JAVA_HOME
else
  unset JAVA_HOME || true
fi

if ! command -v systemctl >/dev/null 2>&1; then
  echo "错误：当前系统没有 systemctl。本安装包要求使用 systemd。" >&2
  exit 1
fi
if [ ! -d /run/systemd/system ]; then
  echo "错误：systemd 当前未作为系统服务管理器运行。" >&2
  echo "容器环境请改用 pack/WOL-Proxy-Docker.tar 镜像包。" >&2
  exit 1
fi

JAVA_BIN=$(wol_find_java17 || true)
if [ -z "$JAVA_BIN" ]; then
  install_java17
  JAVA_BIN=$(wol_find_java17 || true)
fi
if [ -z "$JAVA_BIN" ]; then
  echo "错误：安装后仍未找到可用的 Java 17。请检查 java -version 和 JAVA_HOME。" >&2
  exit 1
fi
if command -v readlink >/dev/null 2>&1; then
  wol_resolved_java=$(readlink -f "$JAVA_BIN" 2>/dev/null || true)
  [ -n "$wol_resolved_java" ] && JAVA_BIN=$wol_resolved_java
fi
JAVA_HOME_DIR=$(CDPATH='' cd -- "$(dirname -- "$JAVA_BIN")/.." && pwd)
JAVA_MAJOR=$(wol_java_major "$JAVA_BIN")
echo "使用 Java $JAVA_MAJOR：$JAVA_BIN"

case "$INSTALL_DIR:$DATA_DIR:$UNIT_FILE" in
  /opt/wol-proxy:/var/lib/wol-proxy:/etc/systemd/system/wol-proxy.service) ;;
  *) echo "错误：安装目标路径校验失败。" >&2; exit 1 ;;
esac

if [ -L /usr/local/bin ] || { [ -e /usr/local/bin ] && [ ! -d /usr/local/bin ]; }; then
  echo "错误：/usr/local/bin 不是可用目录。" >&2
  exit 1
fi
if [ -L /usr/local/bin/wol ]; then
  echo "错误：/usr/local/bin/wol 不能是符号链接。" >&2
  exit 1
fi

if [ -L "$INSTALL_DIR" ] || [ -L "$DATA_DIR" ]; then
  echo "错误：安装目录或数据目录不能是符号链接。" >&2
  exit 1
fi

ensure_service_user
install -d -m 0755 "$INSTALL_DIR"
install -d -m 0750 "$DATA_DIR"
chown "$SERVICE_USER:$(id -gn "$SERVICE_USER")" "$DATA_DIR"

if [ -L "$CONFIG_FILE" ]; then
  echo "错误：配置文件不能是符号链接：$CONFIG_FILE" >&2
  exit 1
fi
if [ -L "$TOKEN_SIDECAR" ]; then
  echo "错误：管理令牌文件不能是符号链接：$TOKEN_SIDECAR" >&2
  exit 1
fi
if [ -e "$TOKEN_SIDECAR" ] && [ ! -f "$TOKEN_SIDECAR" ]; then
  echo "错误：管理令牌路径必须是普通文件：$TOKEN_SIDECAR" >&2
  exit 1
fi

if [ ! -f "$CONFIG_FILE" ]; then
  wol_token_source=''
  if [ -n "$CONFIG_SOURCE" ]; then
    wol_token_source="${CONFIG_SOURCE}.admin-token"
    if [ -L "$wol_token_source" ]; then
      echo "错误：待导入的管理令牌文件不能是符号链接：$wol_token_source" >&2
      exit 1
    fi
    install -m 0640 "$CONFIG_SOURCE" "$CONFIG_FILE"
    echo "已导入初始配置：$CONFIG_SOURCE"
  elif [ -f "$SCRIPT_DIR/config.yml" ]; then
    wol_token_source="$SCRIPT_DIR/config.yml.admin-token"
    if [ -L "$wol_token_source" ]; then
      echo "错误：安装包目录中的管理令牌文件不能是符号链接：$wol_token_source" >&2
      exit 1
    fi
    install -m 0640 "$SCRIPT_DIR/config.yml" "$CONFIG_FILE"
    echo "已导入安装包目录中的 config.yml。"
  elif [ -f "$SCRIPT_DIR/config.example.yml" ]; then
    install -m 0640 "$SCRIPT_DIR/config.example.yml" "$CONFIG_FILE"
    echo "已创建默认配置：$CONFIG_FILE"
  else
    echo "错误：没有可用于首次安装的配置文件。" >&2
    exit 1
  fi
  if [ -n "$wol_token_source" ] && [ -f "$wol_token_source" ]; then
    install -m 0600 "$wol_token_source" "$TOKEN_SIDECAR"
    echo "已导入配套管理令牌文件：$wol_token_source"
  fi
else
  echo "保留现有配置：$CONFIG_FILE"
fi
chown "$SERVICE_USER:$(id -gn "$SERVICE_USER")" "$CONFIG_FILE"
chmod 0640 "$CONFIG_FILE"
if [ -f "$TOKEN_SIDECAR" ]; then
  chown "$SERVICE_USER:$(id -gn "$SERVICE_USER")" "$TOKEN_SIDECAR"
  chmod 0600 "$TOKEN_SIDECAR"
fi

# Stage the new binaries before stopping the old service. This keeps a copy or
# permission failure from taking a working installation offline.
wol_stage=$(mktemp -d /opt/wol-proxy.install.XXXXXX)
cleanup_stage() {
  case "${wol_stage:-}" in
    /opt/wol-proxy.install.*) rm -rf "${wol_stage:?}" ;;
  esac
}
trap cleanup_stage EXIT
trap 'cleanup_stage; exit 1' HUP INT TERM
install -d -m 0755 "$wol_stage/bin" "$wol_stage/lib"
cp -R "$SCRIPT_DIR/bin/." "$wol_stage/bin/"
cp -R "$SCRIPT_DIR/lib/." "$wol_stage/lib/"
install -m 0755 "$SCRIPT_DIR/wol" "$wol_stage/wol"
chmod 0755 "$wol_stage/bin/wol-proxy"
find "$wol_stage/lib" -type f -exec chmod 0644 {} \;

if systemctl is-active --quiet "$SERVICE_NAME"; then
  echo "正在停止旧版服务以便安全升级..."
  systemctl stop "$SERVICE_NAME"
fi

rm -rf "${INSTALL_DIR:?}/bin" "${INSTALL_DIR:?}/lib"
rm -f "$INSTALL_DIR/start-linux.sh" "$INSTALL_DIR/linux-installer-lib.sh" "$INSTALL_DIR/uninstall-linux.sh"
mv "$wol_stage/bin" "$INSTALL_DIR/bin"
mv "$wol_stage/lib" "$INSTALL_DIR/lib"
mv "$wol_stage/wol" "$INSTALL_DIR/wol"
cleanup_stage
trap - EXIT HUP INT TERM

install -d -m 0755 /usr/local/bin
install -m 0755 "$INSTALL_DIR/wol" /usr/local/bin/wol
[ ! -f "$SCRIPT_DIR/README.md" ] || install -m 0644 "$SCRIPT_DIR/README.md" "$INSTALL_DIR/README.md"
[ ! -f "$SCRIPT_DIR/config.example.yml" ] || install -m 0644 "$SCRIPT_DIR/config.example.yml" "$INSTALL_DIR/config.example.yml"

if wol_yaml_has_key "$CONFIG_FILE" admin_token; then
  wol_existing_admin_token=$(wol_yaml_scalar_value "$CONFIG_FILE" admin_token 2>/dev/null || true)
  if wol_admin_token_is_valid "$wol_existing_admin_token"; then
    # A legacy plaintext token is migrated without printing it again.
    EXPECT_NEW_TOKEN=0
  else
    # An explicitly blank or invalid field asks the service to generate a new token.
    EXPECT_NEW_TOKEN=1
  fi
elif [ -f "$TOKEN_SIDECAR" ]; then
  if wol_admin_token_sidecar_is_usable "$TOKEN_SIDECAR"; then
    EXPECT_NEW_TOKEN=0
  else
    EXPECT_NEW_TOKEN=1
  fi
else
  EXPECT_NEW_TOKEN=1
fi

write_systemd_unit "$JAVA_HOME_DIR"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
systemctl restart "$SERVICE_NAME"

if ! wait_for_service; then
  echo "错误：WOL Proxy 服务未能启动。" >&2
  echo "请执行以下命令查看日志（管理令牌行请勿发送给他人）：" >&2
  echo "  sudo journalctl -u $SERVICE_NAME -n 50 --no-pager" >&2
  exit 1
fi

wol_invocation_id=$(systemctl show "$SERVICE_NAME" -p InvocationID --value 2>/dev/null || true)
wol_logs=''
wol_admin_url=''
wol_first_token=''
wol_attempt=0
while [ "$wol_attempt" -lt 15 ]; do
  wol_logs=$(current_invocation_logs "$wol_invocation_id")
  wol_admin_url=$(wol_extract_admin_url "$wol_logs" || true)
  if [ "$EXPECT_NEW_TOKEN" = "1" ]; then
    wol_first_token=$(wol_extract_first_token "$wol_logs" || true)
  fi
  if [ -n "$wol_admin_url" ] && { [ "$EXPECT_NEW_TOKEN" = "0" ] || [ -n "$wol_first_token" ]; }; then
    break
  fi
  wol_attempt=$((wol_attempt + 1))
  sleep 1
done

wol_listen=$(wol_yaml_scalar_value "$CONFIG_FILE" listen 2>/dev/null || true)
wol_admin_path=$(wol_yaml_scalar_value "$CONFIG_FILE" admin_path 2>/dev/null || true)
if [ -z "$wol_admin_url" ]; then
  wol_admin_url=$(config_admin_url "$wol_listen" "$wol_admin_path" || true)
fi
wol_host_ip=$(wol_first_reachable_ipv4 || true)
[ -n "$wol_host_ip" ] || wol_host_ip=127.0.0.1
wol_admin_url=$(wol_admin_url_for_ipv4 "$wol_admin_url" "$wol_host_ip" "$wol_listen" || true)

echo
echo "WOL Proxy 已安装为 systemd 服务，并已设置开机自启。"
echo "服务状态：$(systemctl is-active "$SERVICE_NAME")"
[ -z "$wol_admin_url" ] || echo "Web 管理后台：$wol_admin_url"
echo "配置文件：$CONFIG_FILE"
echo "运行日志：$DATA_DIR/wol-proxy.log"
echo "服务日志：sudo journalctl -u $SERVICE_NAME -f"
echo "管理命令：sudo wol（停止、重启、重置管理员密钥、重置配置或卸载）"
if [ "$EXPECT_NEW_TOKEN" = "1" ]; then
  if [ -n "$wol_first_token" ]; then
    echo "首次生成的管理令牌：$wol_first_token"
    echo "请立即妥善保存；以后重启或升级不会再次显示旧令牌。"
  else
    echo "警告：本次未能取得首次管理令牌，请检查本次启动的 systemd 日志。" >&2
  fi
fi

wol_proxy_key=$(wol_yaml_scalar_value "$CONFIG_FILE" key 2>/dev/null || true)
if [ "$wol_proxy_key" = "change-this-key" ]; then
  echo "提醒：代理连接 Key 仍为示例值，请进入 Web 后台立即修改。"
fi

exit 0
