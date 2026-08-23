# WOL ON LAN

WOL ON LAN 是一套跨平台 Wake-on-LAN（网络唤醒）工具，包含 Android 客户端和部署在目标局域网内的代理服务。Android 客户端负责设备管理、连接测试和唤醒请求，并内置适合轻量场景的简易代理模式；独立代理服务负责在局域网中长期稳定地接收请求并发送 UDP 魔术包。

项目支持 Android 内置简易代理，以及 Windows、Linux 和 Docker 三种独立代理交付方式，适合家庭网络、办公室、NAS 和远程访问场景。轻量或临时使用可以直接启用 Android 本机代理；需要长期运行、开机自启或集中管理时，建议部署独立代理。

## 功能概览

- Android 客户端：管理设备、分组、MAC 地址和多个代理节点，并可启用本机简易代理服务。
- 代理服务：接收签名请求，在局域网中发送 Wake-on-LAN 魔术包。
- 安全控制：KEY 鉴权、时间戳、nonce 防重放、MAC 白名单和唤醒冷却时间。
- Web 管理后台：配置编辑、YAML/JSON 导入导出、运行状态和日志查询。
- 多平台部署：Windows 便携 EXE、Linux systemd 安装包、Docker 镜像。

## 项目目录

```text
.
├── apk/       Android 手机客户端源码、资源和单元测试
├── watch/     Android 11 圆形手表精简客户端和磁贴
├── proxy/     Windows/Linux/Docker 共用的 Java 代理核心、Web 后台和测试
├── windows/   Windows 便携启动器源码与 EXE 打包脚本
├── linux/     Linux 安装脚本、wol 维护命令和 shell 测试
├── docker/    Docker 镜像构建脚本、Compose 示例和部署说明
├── scripts/   跨平台统一构建入口
├── build.gradle.kts
├── settings.gradle.kts
└── PROJECT_CONTEXT.md  当前实现约束和交付背景
```

`local.properties`、`_tools/` 和 `pack/` 是本地配置、构建工具链和发行产物目录，不纳入源码仓库。正式成品发布在 [GitHub Releases](https://github.com/sumy8023/Wol-On-Lan/releases)。手表客户端单独输出为 `WOL-Watch.apk`，不会覆盖手机端 APK。

## 快速开始

### 1. 运行代理

代理必须运行在目标设备所在的局域网中。首次运行会生成配置、管理入口和凭证。将控制台显示的代理 KEY 填入 Android 客户端的代理节点设置，然后测试连接。

### 2. 添加设备

在 Android 客户端中填写设备名称和 MAC 地址。普通设备 IP 会在代理端按所在网段转换为广播地址；也可以直接填写明确的广播地址。确认代理连接成功后，启用“通过代理唤醒”。

### 3. 发送唤醒

选择设备并执行唤醒。代理会校验签名、时间戳、nonce、MAC 白名单和冷却时间，然后向局域网发送 UDP 魔术包。

## 代理部署

### Windows 便携 EXE

下载 Release 中的 `WOL-Proxy-Windows.exe`，直接运行即可，不需要安装 Java 或解压运行目录。首次启动会在当前用户目录创建：

```text
C:\Users\当前用户名\wol\wol-config.yml
C:\Users\当前用户名\wol\wol-config.yml.admin-token
C:\Users\当前用户名\wol-proxy.log
```

控制台只在首次生成或明确轮换时显示管理令牌。使用 Web 管理入口修改配置，或使用 `--config <路径>` 指定配置文件。Windows 防火墙需要允许代理监听端口的 TCP 访问，并确保代理主机可以向局域网广播 UDP 9（部分设备使用 UDP 7）。

### Linux systemd

下载 `WOL-Proxy-Linux.tar`，在目标 Linux 主机执行：

```bash
tar -xf WOL-Proxy-Linux.tar
cd wol-proxy
chmod +x start-linux.sh
./start-linux.sh
```

安装器会检查 Java 17、安装低权限 `wol-proxy` 用户和 systemd 服务，程序安装到 `/opt/wol-proxy`，配置与日志保存到 `/var/lib/wol-proxy`。维护命令：

```bash
wol              # 交互式维护菜单
wol restart
wol reset-admin-key
wol reset-config
wol uninstall
```

升级时再次运行新版 `start-linux.sh`。默认卸载保留配置和日志；确认不再需要数据时才使用 `wol uninstall --purge`。

### Docker / NAS

下载 `WOL-Proxy-Docker.tar` 并导入镜像：

```bash
docker load -i WOL-Proxy-Docker.tar
mkdir -p data
docker run -d --name wol-proxy --network host --restart unless-stopped \
  -v "$PWD/data:/config:rw" wol-proxy:1.0.2
```

也可以使用 `docker/docker-compose.yml`：

```bash
cd docker
docker compose up -d
docker compose logs -f
```

NAS 创建容器时必须选择 Host 网络，并把可写目录挂载到 `/config`。不要只映射 TCP 14250 或 UDP 9：端口映射只处理进入容器的流量，WOL 魔术包是容器发往宿主机所在局域网的广播。`/config` 不可写会导致配置、日志和凭证无法持久化。

## 配置说明

复制 `proxy/config.example.yml` 为 `config.yml`，至少修改 `key`：

```yaml
listen: ":14250"
key: "请替换为长随机字符串"
default_broadcast: "255.255.255.255"
default_port: 9
cooldown_seconds: 5
allow_macs: []
admin_path: ""
```

- `listen`：监听地址和端口，默认 `:14250`。
- `key`：Android 与代理之间的连接密钥，必须自行生成并保密。
- `default_broadcast` / `default_port`：设备未填写地址或端口时的默认值。
- `cooldown_seconds`：同一个 MAC 的最短唤醒间隔。
- `allow_macs`：MAC 白名单；空数组表示不限制。
- `admin_path`：Web 管理入口，留空会自动生成 4-64 位字母路径。

管理令牌与代理 KEY 相互独立。令牌不写入 YAML，只保存为同目录的 PBKDF2 verifier sidecar。Web 后台按要求使用 HTTP，不内置 HTTPS；公网部署必须限制防火墙来源或通过可信 VPN 访问。

## 二次开发

### 环境要求

- JDK 17 或更高版本
- Android SDK、Android Build Tools 35.0.0
- Gradle 8.9（或使用项目提供的工具链）
- Android Studio（开发 APK 时推荐）
- Windows EXE 需要 .NET 10 SDK
- Docker 镜像构建需要 Docker Engine

不要提交 `local.properties`。在本机配置 Android SDK 路径即可；项目通过 `settings.gradle.kts` 管理 `app`、`watch` 和 `wol-proxy` 模块。

### 编译与测试

```powershell
.\gradlew.bat test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :wol-proxy:test
.\gradlew.bat :watch:test
```

Linux 安装脚本的纯 shell 测试：

```bash
./gradlew :wol-proxy:testLinuxInstaller
```

Android 手机源码位于 `apk/src/main`，Android 11 手表源码位于 `watch/src/main`，代理核心位于 `proxy/src/main`。修改协议、签名或配置字段时，应同时更新客户端、代理测试和 `proxy/config.example.yml`。

## 统一打包

在 Windows PowerShell 中运行：

```powershell
.\scripts\build-packages.ps1
```

脚本会构建、校验并清理 `pack/`，最终只生成：

```text
pack/WOL-Android.apk
pack/WOL-Watch.apk
pack/WOL-Proxy-Windows.exe
pack/WOL-Proxy-Linux.tar
pack/WOL-Proxy-Docker.tar
```

脚本默认从 `_tools/` 查找 JDK、Android SDK 和 Gradle。若使用系统工具链，请检查 `JAVA_HOME`、`ANDROID_HOME`、Gradle 和 `PATH`，并确认 APK 签名校验工具可用。构建完成后应核对 APK 签名、Docker 镜像标签和五个文件的大小，再上传到 GitHub Release。

## 部署注意事项

1. 代理必须位于目标设备所在的二层局域网，或具备能够发送广播的网络路由。
2. 不要公开配置文件中的代理 KEY、管理令牌或 `.admin-token` 文件。
3. 首次生成的 KEY 和管理令牌只在当次启动日志显示，请立即保存到密码管理器。
4. 公网访问优先使用 VPN、访问控制列表或防火墙白名单，不要仅依赖随机管理入口。
5. 设备无法唤醒时，先检查 BIOS/UEFI、网卡驱动、电源状态、MAC 地址和交换机是否允许 WOL。
6. Docker 必须使用可写 `/config` 挂载；Linux 升级前应备份 `/var/lib/wol-proxy`。
7. 发行包来自 GitHub Releases，源码仓库不包含本地工具链和构建产物。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。

## 免责声明

本项目代码均由 AI 辅助开发，未经完整的专业安全审计或在所有目标环境中充分验证。因使用本项目产生的漏洞、安全性问题、数据丢失、服务中断或其他损失，由使用者自行评估并承担责任。本项目仅供学习和交流使用。
