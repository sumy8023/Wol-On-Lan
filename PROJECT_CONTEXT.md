# WOL 网络唤醒项目说明

## 项目定位

这是一个 Android Kotlin 应用，用于保存局域网设备并发送 Wake-on-LAN 魔术包。应用支持三种唤醒入口：

- 应用内设备卡片左侧的电源按钮
- Android 控制中心快捷磁贴
- 单个设备的桌面快捷方式

应用名称为 `WOL 网络唤醒`，作者信息为 `Sumy`。
当前 APK 与代理服务版本均为 `1.0.2`。

## Android 11 手表客户端

`watch/` 是独立的普通 Android 11 圆形手表 APK，包名为
`com.example.wolquicktile.watch`，不覆盖手机端 `:app`。手表端只保存少量设备和代理节点，支持局域网 WOL、桌面代理 HMAC 请求、代理节点测试，以及一个标准 Android Quick Settings 磁贴。

手表端不运行本机代理服务，不包含手机端分组、备份和 50 个磁贴。磁贴绑定手表端当前选中的设备；目标手表必须提供第三方 Quick Settings Tile 入口，磁贴是否可添加需要在实机验证。

手表构建命令：

```powershell
.\_tools\gradle-8.9\bin\gradle.bat :watch:assembleDebug --no-daemon
```

输出为 `watch/build/outputs/apk/debug/watch-debug.apk`，统一打包后复制为 `pack/WOL-Watch.apk`。

## 当前关键需求

- 默认分组名称是 `默认分组`，这是数据库真实分组，不是 UI 假分组。
- 分组至少保留一个，删除最后一个分组时必须阻止。
- 分组支持收纳/展开；设置页里的“分组设置”默认收纳。
- 设置页是独立页面，不是悬浮弹窗；关于应用放在设置页底部。
- 设置页顺序为“代理节点、分组管理、本机代理、关于应用”；代理节点面板默认收纳。
- 设置页使用“代理节点”管理多个代理；设备开启代理唤醒后只能绑定一个已启用且测试成功的节点。
- 设置页另有“本机代理”，配置端口和 KEY 后可将 APK 作为代理服务端运行。
- 分组设置支持长按拖动排序，拖动释放后保存排序。
- 添加/编辑设备时，设备名称和 MAC 地址必填；IP地址/广播地址、UDP 端口可选。
- MAC 地址支持 `AA:BB:CC:DD:EE:FF`、`AA-BB-CC-DD-EE-FF`、`AABBCCDDEEFF`。
- IP 地址为空时发送默认广播 `255.255.255.255`，但不写回输入框。
- 如果用户填写设备 IP，例如 `192.168.9.14`，发送时临时构造 `192.168.9.255`，不改变用户保存的 IP。
- UDP 端口为空时默认使用 `9`。
- 点击设备卡片本身不能唤醒，只有左侧电源按钮或快捷入口才发送唤醒包。
- 添加磁贴前要二次确认，提示最大数量和当前已添加数量。
- 当前快捷磁贴上限为 `50` 个，磁贴标签为 `WOL 01` 到 `WOL 50`。
- 删除设备时需要清理对应磁贴绑定，并禁用对应磁贴组件，避免留下空磁贴。
- 下拉控制中心点击磁贴后不使用 Toast，改为在磁贴副标题短暂显示“已发送唤醒包”。
- 应用图标和快捷方式图标统一为电源开关风格。
- 整体 UI 走现代蓝色风格，避免原生默认样式；弹窗、菜单、下拉选择都要保持统一视觉。

## 重要文件

- 主界面与弹窗：`apk/src/main/java/com/example/wolquicktile/ui/screen/WolApp.kt`
- ViewModel：`apk/src/main/java/com/example/wolquicktile/ui/screen/WolViewModel.kt`
- 数据仓库：`apk/src/main/java/com/example/wolquicktile/repository/DeviceRepository.kt`
- WOL 发送逻辑：`apk/src/main/java/com/example/wolquicktile/domain/wol/WakeOnLanSender.kt`
- 磁贴服务基类：`apk/src/main/java/com/example/wolquicktile/service/BaseWolTileService.kt`
- 磁贴注册上限与组件名：`apk/src/main/java/com/example/wolquicktile/service/TileRegistry.kt`
- 21-50 号磁贴类：`apk/src/main/java/com/example/wolquicktile/service/WolTileServices21To50.kt`
- Manifest 静态磁贴服务声明：`apk/src/main/AndroidManifest.xml`
- 主题配色：`apk/src/main/java/com/example/wolquicktile/ui/theme/Theme.kt`
- 应用名称：`apk/src/main/res/values/strings.xml`

## 打包命令

在项目根目录执行：

```powershell
$env:JAVA_HOME=(Resolve-Path .\_tools\jdk-17.0.20+8).Path
$env:ANDROID_HOME=(Resolve-Path .\_tools\android-sdk).Path
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
.\_tools\gradle-8.9\bin\gradle.bat assembleDebug
Copy-Item -LiteralPath apk\build\outputs\apk\debug\app-debug.apk -Destination .\pack\WOL-网络唤醒.apk -Force
.\_tools\android-sdk\build-tools\35.0.0\apksigner.bat verify --verbose .\pack\WOL-网络唤醒.apk
```

输出 APK：`pack/WOL-网络唤醒.apk`。当前内部构建变体仍是 `debug`，但正式交付文件名不包含 `-debug`。

## 代理唤醒扩展

当前新增了外网代理唤醒模式：APK 请求内网代理服务，代理服务再在局域网内发送 WOL 魔术包。设备仍由 APK 管理，代理服务不保存设备列表。

APK 侧行为：

- 设置页代理节点支持名称、地址、端口、KEY、启用/停用、测试连接、编辑、删除和重新生成 KEY。
- 每个代理节点独立保存自动测试间隔，默认 30 秒、最少 5 秒且不设业务上限；启用节点会立即测试并按该间隔持续复测，同一节点的自动与手动测试不会并发。
- 代理节点面板默认收纳；节点列表提供测试连接、编辑和删除，新增/编辑弹窗的测试按钮位于左下操作区。
- 每个代理节点独立显示“未配置 / 正在连接 / 连接成功 / 连接失败：原因”等连接状态。
- 健康检查必须同时收到 `ok=true` 和服务标识 `name=WOL Proxy`，不能只凭 HTTP 2xx 判定成功。
- KEY 错误时服务返回 HTTP 401，APK 显示“代理认证失败，请检查 KEY”。
- 设备添加/编辑页开启“通过代理唤醒”后必须选择一个代理节点；该节点测试连接成功后才能保存。
- 已开启代理唤醒的旧设备不会在应用自动复测期间被静默改回关闭。
- 应用底部提示使用自定义白色背景、底部居中、内容自适应宽度的圆角提示框，不使用默认深色 Snackbar 样式。
- 代理访问不强制 HTTPS，APK 允许 HTTP 明文访问；安全由 HMAC-SHA256 签名、时间戳和 nonce 防重放保证。
- 设备添加/编辑页新增“通过代理唤醒”开关；开启后可再打开“同时通过局域网唤醒”。
- 点击应用内设备唤醒按钮后：代理唤醒会先读取代理健康接口的 `cooldown_seconds` 并按该值倒计时；纯局域网唤醒固定冷却 3 秒。
- 控制中心磁贴和桌面快捷方式也使用设备的代理唤醒配置。
- Room 数据库版本当前为 `4`；v2->v3 新增 `proxy_nodes` 表和设备的 `proxyNodeId` 字段，v3->v4 为代理节点新增自动测试间隔并将旧数据默认设为 30 秒。
- Android APK 可同时作为原有 WOL 客户端和代理服务端。代理端使用前台 Service、常驻通知、HMAC 验证、时间戳及 nonce 防重放，并支持开机恢复。
- 本机代理的 KEY 支持在输入框右侧使用“随机生成”生成 8 位随机字符；编辑节点测试使用当前未保存输入，状态显示在 KEY 下方，测试按钮位于弹窗左下操作区。
- 本机代理支持与桌面代理互通的 YAML/JSON 导入导出；设置页“应用配置”支持设备、分组、代理节点（含自动测试间隔）、磁贴绑定、旧版代理设置和本机代理设置的完整 JSON 备份与事务导入，旧备份缺少间隔时按 30 秒导入。
- 开启本机代理时会提示用户自行设置电池优化、后台运行和厂商自启动权限；关闭代理不会影响原有唤醒功能。

代理服务行为：

- 公共代理源码目录：`proxy/`
- 健康检查：`GET /api/health`
- 唤醒设备：`POST /api/wake`
- 请求头：`X-WOL-Timestamp`、`X-WOL-Nonce`、`X-WOL-Signature`
- 签名：`hex(HMAC-SHA256(KEY, timestamp + "\n" + nonce + "\n" + rawBody))`
- 同一 MAC 在 `cooldown_seconds` 内只允许唤醒一次，默认 5 秒。
- 代理服务默认监听端口为 `14250`，新生成的配置使用 `listen: ":14250"`；已有用户配置不会被强制覆盖。
- `allow_macs` 为空时允许所有 MAC；填写后只允许白名单 MAC。
- 收到设备 IP，例如 `192.168.9.14`，发送时临时构造成 `192.168.9.255`，不改变 APK 保存的设备地址。
- 控制台会记录连接测试、认证失败、唤醒请求、白名单拒绝、参数错误、冷却限制和发送结果。
- Windows 单文件 EXE 默认读取 `%USERPROFILE%\wol\wol-config.yml`；首次运行会从 EXE 内置的 `wol-config.example.yml` 创建该文件，并在启动日志中打印最终配置路径。
- Windows 启动时会把控制台切换为 UTF-8，启动异常只输出中文原因，不打印英文堆栈。
- Windows 默认配置文件为 `%USERPROFILE%\wol\wol-config.yml`，日志文件为 `%USERPROFILE%\wol-proxy.log`；首次运行自动创建，旧的 `%USERPROFILE%\wol-config.yml` 或 `%USERPROFILE%\config.yml` 会自动迁移到 `wol` 文件夹。
- Windows 交付形式为便携单文件 EXE；运行时文件在后台静默缓存，用户不需要安装或管理运行目录，只需管理用户目录里的配置文件。
- Web 管理后台和代理接口共用 `listen` 端口；`admin_path` 允许 4-64 位大小写字母，首次启动自动生成 8 位字母入口；`admin_token` 允许 6-256 位可见字符，独立令牌才是实际认证凭据。
- 服务启动时在控制台输出完整可点击的 Web 管理地址；监听所有网卡时使用 `http://127.0.0.1:端口/入口/` 作为本机访问地址。
- Web 后台支持编辑 YAML 的全部现有字段、YAML/JSON 一键导入导出、生成 `.bak` 后原子保存、从磁盘重新加载，以及查看、按日期/事件/关键词筛选并分页导出运行日志；日志默认每页 50 条，可选 80/100/150/200 条。
- Web 页面必须适配手机；配置页使用“监听端口”数字输入（例如 `14250`），默认配置保存为 `:14250`，已有配置指定主机/IP 时只更新端口并保留监听地址；端口变更绑定成功后热切换，失败时保留旧监听和旧配置；修改管理入口、令牌或端口后页面自动切换到新地址和凭据。
- Web 后台不内置 HTTPS；公网部署须说明 HTTP 明文风险，并建议通过防火墙来源限制或可信 VPN 访问。
- Docker 的宿主机配置目录必须以读写方式挂载到容器 `/config`，不能把目录挂载到 `/config/config.yml`；单文件挂载点不能被原子替换时，服务会回退为原文件截断写入。
- Docker 空目录或已有配置的 `key` 为空时，首次启动自动生成 24 位代理连接 KEY 并写回 YAML，仅在本次控制台显示；正常重启不再显示。已配置的 KEY 或 `WOL_PROXY_KEY` 不得被覆盖。

代理服务关键文件：

- `proxy/src/main/java/com/example/wolproxy/Main.java`
- `proxy/config.example.yml`
- `proxy/README.md`
- `windows/launcher/`
- `windows/build.ps1`
- `docker/build-image.ps1`
- `docker/docker-compose.yml`
- `linux/start-linux.sh`
- `linux/wol`

APK 代理相关关键文件：

- `apk/src/main/java/com/example/wolquicktile/domain/wol/DeviceWakeDispatcher.kt`
- `apk/src/main/java/com/example/wolquicktile/domain/proxy/ProxyWakeClient.kt`
- `apk/src/main/java/com/example/wolquicktile/data/preferences/ProxySettingsRepository.kt`

代理服务打包：

```powershell
.\scripts\build-packages.ps1
```

代理服务输出：

- `pack/WOL-Proxy-Windows.exe`：Windows 便携单文件启动 EXE；双击直接启动代理服务，不需要安装向导或手动解压，配置文件使用当前用户目录 `wol` 文件夹中的 `wol-config.yml`，日志使用 `wol-proxy.log`。EXE 内部会静默缓存 Java 运行时，文件图标必须与 APK 桌面图标保持一致。
- `pack/WOL-Proxy-Linux-sh.tar`：Linux 分发包，根目录提供 `start-linux.sh`；不再同时保留内容相同的 ZIP。
- `pack/WOL-Proxy-Docker-Image.tar`：已使用 crane 无 Docker 守护进程构建的 `linux/amd64` Docker load 兼容镜像包。
- `pack/WOL-网络唤醒.apk`：Android APK，版本 `1.0.2`；由 `debug` 变体构建并使用调试证书签名，交付文件名不带 `-debug`。
- 本地可运行目录：`proxy/build/install/wol-proxy`
- Windows 便携启动器源码：`windows/launcher/`
- Windows 便携 EXE 打包脚本：`windows/build.ps1`

## pack 交付目录约束

最终打包产物直接放在项目根目录的 `pack/` 文件夹，不建立任何子目录。该目录只允许以下四个成品：

- `pack/WOL-网络唤醒.apk`
- `pack/WOL-Proxy-Windows.exe`
- `pack/WOL-Proxy-Docker-Image.tar`
- `pack/WOL-Proxy-Linux-sh.tar`

Docker 的 Compose、配置模板和说明只保留在源码目录 `docker/`，不得复制到 `pack/`。

不得生成 `WOL-Proxy-Docker-Build-Files.zip`，也不得再次保留 `WOL-Proxy-Linux-sh.zip`、`WOL-Proxy-Windows-Exe.zip`、`~WOL-Proxy-Windows.DDF`、`WOL-网络唤醒-debug.apk`、jpackage 安装器或其他临时打包文件。Windows 用户只使用便携单文件 `WOL-Proxy-Windows.exe`。

代理服务要求：

- `config.example.yml`、`wol-config.example.yml` 和 Windows 包内的配置模板必须使用中文注释。
- 每一个配置项都要有中文说明。
- 代理服务控制台输出必须使用中文日志，例如启动、唤醒成功、白名单拒绝、冷却拒绝、发送失败等。
