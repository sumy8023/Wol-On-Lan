# WOL 代理服务

WOL 代理服务运行在需要唤醒设备所在的内网里。APK 在外网访问这个服务，服务收到请求后在局域网内发送 Wake-on-LAN 魔术包。

## 配置文件

复制 `config.example.yml` 为 `config.yml`，至少要修改 `key`：

```yaml
# 代理服务监听地址和端口。
# 写成 ":14250" 表示监听本机所有网卡的 14250 端口；也可以写成 "0.0.0.0:14250"。
listen: ":14250"

# APK 连接代理服务时填写的 KEY。
# 请务必改成自己的长随机字符串，不要继续使用示例值。
key: "change-this-key"

# 当 APK 设备里的 IP地址/广播地址为空时，代理服务使用的默认广播地址。
# 一般保持 "255.255.255.255" 即可。
default_broadcast: "255.255.255.255"

# 当 APK 设备里的 UDP 端口为空时，代理服务使用的默认端口。
# WOL 常用端口是 9，也有少数设备使用 7。
default_port: 9

# 同一个 MAC 地址的服务端冷却时间，单位是秒。
# 默认 5 秒内同一个 MAC 只能唤醒一次，防止重复点击或接口被频繁调用。
cooldown_seconds: 5

# MAC 白名单。
# 写成 [] 表示允许 APK 请求唤醒任意 MAC；如果填写列表，则只允许列表里的 MAC 被唤醒。
allow_macs: []

# Web 管理入口首次启动时自动生成并写回配置文件。
admin_path: ""

# 管理令牌不写入 YAML。需要在下次启动时强制轮换令牌，可临时添加：
# admin_token: ""
```

字段说明：

- `listen`：代理服务监听地址和端口，默认 `:14250`。Web 管理页面中的“监听端口”只填写数字，例如 `14250`；默认配置会保存为 `:14250`，已有配置指定了主机或 IP 时只替换端口并保留原监听地址。
- `key`：APK 连接代理服务时填写的 KEY，必须和这里一致。
- `default_broadcast`：APK 设备地址为空时使用的默认广播地址。
- `default_port`：APK 设备端口为空时使用的默认 UDP 端口。
- `cooldown_seconds`：同一个 MAC 的服务端冷却时间，默认 5 秒。
- `allow_macs`：MAC 白名单。为空表示允许所有 MAC；填写后只允许列表内 MAC。
- `admin_path`：Web 管理后台入口，长度 4-64 位，只能使用大小写字母；留空时首次启动自动生成 8 位字母入口。
- Web 管理令牌：长度 6-256 位，可使用字母、数字和可见特殊字符，与 APK 连接使用的 `key` 相互独立。令牌不写入 YAML；配置文件旁的 `<配置文件名>.admin-token` 受限 sidecar 保存 PBKDF2 verifier（`pbkdf2-sha256$v1$iterations$base64url-salt$base64url-hash`），不会保存或回显明文。仅将 `admin_token: ""` 作为一次性轮换指令手动加入 YAML；服务会在下次启动生成新令牌并自动移除该字段。

## Web 管理后台

Web 管理后台与代理接口共用 `listen` 配置的端口，不会另外开放端口。服务首次启动后会生成只包含大小写字母的 8 位管理入口及独立管理令牌。入口写入 YAML；令牌写入同目录的受限 sidecar，并仅在生成它的这次启动中打印到控制台：

```text
[2026-08-16 09:00:00][Web 管理后台地址]http://127.0.0.1:14250/aBcDeFGh/
首次生成的管理令牌：随机管理令牌
```

控制台中的完整地址可以直接点击或复制到浏览器打开。监听地址为 `:14250` 或 `0.0.0.0:14250` 时，服务会优先显示默认路由对应的本机 IPv4 地址；如果系统无法发现网卡地址才回退到 `127.0.0.1`。输入管理令牌后可以：

- 查看运行状态和实际配置、日志文件路径。
- 编辑并保存监听端口、代理 KEY、默认广播地址、UDP 端口、冷却时间、MAC 白名单、管理入口和管理令牌。管理令牌更新后只写入 sidecar。
- 一键导入或导出 YAML/JSON 配置，并在字段、格式和监听端口完整校验后应用。导出文件包含代理连接 KEY，但不会包含管理令牌；仍应按敏感配置文件妥善保管。
- 从磁盘重新读取 YAML；每次保存前自动生成同目录 `.bak` 备份，并使用临时文件替换，避免只写入半份配置。
- 查看运行日志，按开始日期、结束日期、事件类型和关键词筛选；日志默认每页 50 条，可切换为 80、100、150、200 条，并支持上一页/下一页和导出当前页。

所有保存或导入的配置都会立即生效。监听端口修改时服务会先绑定新端口，成功后热切换并关闭旧监听；新端口被占用时保存失败，旧配置和旧监听保持可用。修改管理入口或监听端口后页面会自动跳转到新地址。管理令牌由浏览器生成或由用户输入，服务端响应不会回传明文；配置审计日志对代理 KEY 和管理令牌只记录是否设置及长度。

正常重启会根据 sidecar verifier 继续使用原令牌，控制台不会重复显示。令牌遗失时，在 YAML 中添加一行 `admin_token: ""` 并重启；新令牌只会从该次 systemd invocation 日志输出一次，随后 YAML 和 `.bak` 备份中的令牌字段都会被清除。旧版 YAML 中已有的 `admin_token` 或 `admin_password` 会自动迁移到 sidecar，迁移过程不会把明文写入日志。

页面已针对手机浏览器适配。项目按要求不内置 HTTPS，因此管理令牌在 HTTP 网络中不是加密传输。不要只依赖随机入口直接暴露管理后台；公网使用时至少应限制防火墙来源，或通过可信 VPN 访问。随机入口用于降低扫描暴露，真正的访问校验由独立管理令牌完成。

环境变量也可以覆盖配置：

- `WOL_PROXY_LISTEN`
- `WOL_PROXY_KEY`
- `WOL_PROXY_DEFAULT_BROADCAST`
- `WOL_PROXY_DEFAULT_PORT`
- `WOL_PROXY_COOLDOWN_SECONDS`
- `WOL_PROXY_ALLOW_MACS`，多个 MAC 用英文逗号分隔

## Windows 使用

Windows 正式交付文件为项目根目录下的 `pack/WOL-Proxy-Windows.exe`。这是便携单文件启动器，双击后会直接启动代理服务，不会打开安装向导，也不需要手动解压运行目录。

EXE 内置代理服务和 Java 运行时，首次启动时会在系统临时目录静默准备运行文件；这只是单文件运行所需的内部缓存，用户不需要管理它。

首次运行后，配置文件会自动生成到当前 Windows 用户目录下的 `wol` 文件夹：

```text
C:\Users\当前用户名\wol\wol-config.yml
```

首次启动的控制台会显示 Web 管理入口和本次生成的管理令牌。`C:\Users\当前用户名\wol\wol-config.yml.admin-token` 只保存 PBKDF2 verifier，不保存令牌明文，正常重启不再显示令牌。可以直接进入 Web 后台修改 `key` 并保存（立即生效），也可以手动编辑配置后重启。旧版本放在用户目录下的 `wol-config.yml` 或 `config.yml` 会自动迁移到 `wol` 文件夹。

日志文件固定写入：

```text
C:\Users\当前用户名\wol-proxy.log
```

也可以使用 `--config` 指定其他配置文件。服务启动时会打印实际读取的配置路径和日志路径。

不要使用历史文件 `WOL-Proxy-Windows-Exe.zip` 或 jpackage 安装器；它们不是当前 Windows 交付版本。

## Linux 使用

Linux 正式交付文件为 `pack/WOL-Proxy-Linux-sh.tar`，不再同时提供内容相同的 ZIP。

压缩包只提供 `start-linux.sh` 安装入口、无扩展名的 `wol` 管理命令、代理程序文件、示例配置和 README。安装解析 helper 已内置到安装入口，不再作为独立用户入口分发；旧版 `uninstall-linux.sh` 也不会进入新包。

安装包面向使用 systemd 的 Linux 发行版。解压后进入目录并运行安装入口：

```bash
tar -xf WOL-Proxy-Linux-sh.tar
cd wol-proxy
./start-linux.sh
```

如果文件经过不保留 Unix 权限的工具中转，也可以直接执行 `sh start-linux.sh`。安装程序会清楚提示 sudo 授权，并自动完成以下工作：

- 检查 Java 主版本；Java 17 或更高版本可直接使用。
- Java 版本不足时识别 `apt/apt-get`、`dnf`、`yum`、`pacman` 或 `zypper`，自动安装对应的 Java 17 无界面运行时。使用 `--no-install-java` 可以只显示安装命令而不执行。
- 将程序安装到 `/opt/wol-proxy`，以独立的 `wol-proxy` 低权限用户运行。
- 将可写配置和日志放在 `/var/lib/wol-proxy`，安装为 `wol-proxy.service`，设置开机自启并立即启动。终端关闭不会停止服务。
- 从本次 systemd invocation 日志读取后台入口，将通配监听地址替换为本机可达 IPv4 后输出。仅在本次首次生成管理令牌时从同一 invocation 日志显示令牌；升级或重启不会从 sidecar 回读或重新泄露旧令牌。

首次安装会从包内示例创建 `/var/lib/wol-proxy/config.yml`，管理令牌 verifier 单独保存在仅服务用户可读写的 `/var/lib/wol-proxy/config.yml.admin-token`。也可以导入已有配置；旧版单行明文 sidecar 会在服务首次启动时迁移为 verifier：

```bash
./start-linux.sh --config /path/to/config.yml
```

如果 Java 17 安装在自定义目录，可以使用 `--java-home /path/to/jdk`；普通用户运行时该路径会随 sudo 安全传给安装进程。

兼容旧方式 `WOL_PROXY_CONFIG=/path/to/config.yml ./start-linux.sh`。指定配置只用于首次安装；如果旁边存在同名的 `config.yml.admin-token`，安装程序会一并导入并设置为 `0600`。如果目标配置已经存在，安装和升级都不会覆盖它。重复执行新版安装包中的 `start-linux.sh` 即可升级程序并重启服务。

安装完成后可以直接输入 `wol` 打开维护菜单。菜单提供停止服务、重启服务、重置管理员密钥、重置配置和卸载服务；也支持把动作作为参数传入，适合脚本调用：

```bash
wol
wol stop
wol restart
wol reset-admin-key
wol reset-config
wol uninstall
```

忘记 Web 管理员密钥时，运行 `wol reset-admin-key`，按提示输入 `RESET-KEY` 确认。命令会先把当前 `config.yml` 和密钥 verifier sidecar 备份到 `/var/lib/wol-proxy/backups`，再重启服务，并且只从本次 systemd invocation 日志读取、显示一次新密钥和可访问的后台地址；不会读取或回显旧密钥。脚本调用可以使用 `wol reset-admin-key --yes` 明确跳过交互确认。

需要恢复安装包默认配置时，运行 `wol reset-config`，并按提示输入 `RESET-CONFIG`。此操作会重置监听端口、代理 Key、白名单、管理入口和管理员密钥，因此必须显式确认。当前配置和 verifier sidecar 会先写入仅 root 可读的备份目录；新配置或本次令牌提取失败时会自动回滚。恢复成功后请立即保存新管理员密钥，并在 Web 后台修改示例代理 Key `change-this-key`。脚本调用可使用 `wol reset-config --yes`。

安装包中的 `start-linux.sh` 只负责安装或升级服务。安装完成后维护入口会安装到 `/usr/local/bin/wol`，程序目录内也会保留一份。卸载时默认保留 `/var/lib/wol-proxy/config.yml`、`config.yml.admin-token` 和 `wol-proxy.log`，以后重装会继续使用：

```bash
wol uninstall
```

确认不再需要任何配置和日志时，使用 `wol uninstall --purge` 完全删除用户数据和服务用户。此操作不可恢复：

```bash
wol uninstall --purge
```

安装脚本的版本解析、包管理器选择、IPv4 与首次令牌提取逻辑可以在 Linux/macOS 的 POSIX shell 下独立测试，不需要 root 或 systemd：

```bash
./gradlew :wol-proxy:testLinuxInstaller
```

## Docker 使用

Docker 正式交付文件只有 `pack/WOL-Proxy-Docker-Image.tar`，不再生成 Docker 构建文件 ZIP，也不在 `pack` 放置 Compose、配置或子目录。直接导入镜像：

```bash
docker load -i pack/WOL-Proxy-Docker-Image.tar
```

运行时应使用 host 网络，保证 UDP 广播能发到真实局域网。`docker/data/config.yml` 的 `key` 可以留空；首次启动或检测到已挂载配置中的 `key` 为空时，镜像会生成 24 位随机 KEY，写入 `/config/config.yml` 并只在本次控制台显示一次。把该 KEY 填入 APK 的代理节点即可；也可以预先填写 `key` 或设置 `WOL_PROXY_KEY`。

```bash
cd docker
docker run -d \
  --name wol-proxy \
  --network host \
  --restart unless-stopped \
  -v ./data:/config:rw \
  wol-proxy:1.0.2
```

仅映射 `14250/tcp` 不能保证唤醒，额外映射 UDP 9 也不能解决：Docker 端口映射处理的是进入容器的流量，WOL 魔术包则是容器发向局域网的 UDP 广播。默认 Compose 已启用 `network_mode: host`。如果自行改为 bridge 网络，需要配置可达的广播路由；Web 后台修改监听端口后还必须同步修改 TCP 端口映射。

Compose 包内的 `data` 目录挂载必须保持可写，否则 Web 管理后台只能查看，无法保存 YAML；同样也无法保存首次自动生成的 KEY。YAML、`.bak` 备份、自动生成的 KEY 和管理令牌 sidecar 会一起持久化；删除并重建容器前请保留该目录，否则会重新生成凭证。启动后执行 `docker logs wol-proxy` 可查看本次首次生成的 KEY、管理入口和管理令牌；正常重启不会再次输出这些凭证。

源码目录中的 `docker/docker-compose.yml` 可用于启动已导入的镜像，但不会复制到 `pack`：

```bash
cd docker
docker compose up -d
```

## APK 填写

在 APK 设置页的“代理节点”里按节点分别填写：

- 节点名称、代理地址（例如 `http://公网IP:14250`）、端口和连接 KEY（配置文件里的 `key`）。
- 每个设备只能绑定一个已启用且测试成功的节点；不同网络环境的设备可以绑定不同节点。

保存节点后 APK 会立即自动测试连接；也可以随时点击“测试连接”重新检查。应用重新启动后会自动复测已保存的代理配置。只有状态显示“连接成功”时，设备添加/编辑页才允许打开“通过代理唤醒”。

APK 每次通过代理唤醒前会从代理健康接口读取当前 `cooldown_seconds` 并按该值倒计时；只使用局域网唤醒时固定冷却 3 秒。设置页“本机代理”支持一键导入桌面代理的 YAML/JSON 配置，也可以导出可供 Windows、Linux 或 Docker 代理直接使用的 YAML/JSON 配置；导入不会移除 APK 原有设备管理和唤醒功能。

设置页“应用配置”还提供完整 JSON 备份：一次性导出或整体导入设备、分组、代理节点、磁贴绑定、旧版代理设置和本机代理设置。导入会先完整校验文件及所有 ID 引用，再在单个数据库事务中替换；格式错误、端口无效或存在悬空引用时不会改动现有数据。

连接状态只会显示以下几类结果：

- `未配置`
- `正在连接`
- `连接成功`
- `连接失败：具体原因`

错误 KEY 会显示“代理认证失败，请检查 KEY”，不会再被识别成连接成功。健康检查还会验证服务返回的专属标识，普通网页即使返回 HTTP 200 也不会通过测试。

## 控制台排查

代理服务会记录每一次连接测试和唤醒请求，包括认证失败、MAC 白名单拒绝、参数错误、冷却限制和发送结果。日志格式为 UTC+8：

```text
[2026-08-14 09:41:32][唤醒已发送]MAC=74:D4:35:56:79:0A，目标地址=192.168.9.255，端口=9，来源=120.32.227.205
```

- APK 测试连接后，控制台完全没有出现“收到连接测试请求”：请求没有到达代理，请检查 APK 中的公网地址、端口映射和 Windows 防火墙。
- 出现“KEY不匹配或请求签名错误”：APK 与 `config.yml` 的 `key` 不一致；在 Web 后台修改保存后会立即生效。
- 出现“连接测试成功”但点击设备后没有“收到唤醒请求”：请确认设备已开启“通过代理唤醒”，并安装本次打包的新版 APK。
- 出现“唤醒已发送”但设备没有开机：代理请求链路正常，应继续检查目标设备 BIOS、网卡 WOL 设置、MAC 地址及代理主机所在子网。

## 接口协议

接口：

- `GET /api/health`
- `POST /api/wake`

每个请求都要带以下请求头：

- `X-WOL-Timestamp`：Unix 秒级时间戳
- `X-WOL-Nonce`：随机一次性字符串
- `X-WOL-Signature`：请求签名

签名算法：

```text
hex(HMAC-SHA256(KEY, timestamp + "\n" + nonce + "\n" + rawBody))
```

服务端会校验：

- 时间戳 60 秒内有效
- nonce 不能重复使用
- 签名必须正确
- 如果配置了 `allow_macs`，只允许白名单 MAC
- 同一个 MAC 在冷却时间内不能重复唤醒

唤醒请求体：

```json
{
  "mac": "AA:BB:CC:DD:EE:FF",
  "address": "192.168.9.14",
  "port": 9
}
```

如果 `address` 是普通设备 IP，例如 `192.168.9.14`，代理服务发送时会临时构造成 `192.168.9.255`，不会修改 APK 中保存的地址。
