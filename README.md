# WOL 网络唤醒

项目包含 Android WOL 客户端，以及 Windows、Linux、Docker 三种代理服务交付形式。

## 项目目录

- `apk/`：Android 客户端源码和测试。
- `proxy/`：Windows、Linux、Docker 共用的 Java 代理服务核心。
- `windows/`：Windows 启动器和便携 EXE 打包脚本。
- `docker/`：Docker 镜像脚本、Host 网络 Compose 和持久化配置模板。
- `linux/`：Linux 安装脚本、`wol` 维护菜单、内部 helper 和 shell 测试。
- `scripts/`：跨平台统一打包入口。
- `pack/`：直接存放四个平台的最终成品，不建立子目录。
- `_tools/`：项目自带的构建工具链。

根目录只保留 Gradle 工程配置和项目文档，不放平台打包脚本。

## 统一打包

在 Windows PowerShell 中运行：

```powershell
.\scripts\build-packages.ps1
```

脚本会构建、校验并整理四个平台的正式产物：

- `pack/WOL-网络唤醒.apk`
- `pack/WOL-Proxy-Windows.exe`
- `pack/WOL-Proxy-Docker-Image.tar`
- `pack/WOL-Proxy-Linux-sh.tar`

`pack/` 只保留以上四个可直接使用的成品，不放 Compose、配置模板、说明或子文件夹。项目不再生成 `WOL-Proxy-Docker-Build-Files.zip`。

## 配置

Docker 源码部署模板保存在 `docker/`，不进入 `pack/`。运行镜像时将可写的宿主机目录挂载到 `/config`。空目录或空 `key` 在 Docker 首次启动时会自动生成 24 位代理连接 KEY，写入配置并只在本次控制台显示；后续可在 Web 后台修改。`admin_path` 可以保持为空，首次启动时服务会自动生成；管理令牌不会写入 YAML，旁边的 `.admin-token` 文件只保存 PBKDF2 verifier。

## 导入 Docker 镜像

直接导入 `pack` 中的镜像成品：

```bash
docker load -i pack/WOL-Proxy-Docker-Image.tar
```

飞牛 NAS 创建容器时请选择 Host 网络，并将可写目录挂载到 `/config`。源码目录中的 `docker/docker-compose.yml` 可作为部署参考。不要只映射 `14250/tcp`，也不要尝试靠映射 UDP 9 解决广播问题：端口映射处理的是进入容器的流量，WOL 魔术包是容器发出的 UDP 广播。

查看中文日志：

```bash
docker compose logs -f
```

日志中会显示完整 Web 管理后台地址；只有首次生成或明确轮换时才显示新管理令牌。管理后台与代理接口共用端口，例如 `http://代理地址:14250/aBcDeFGh/`；可在手机或电脑浏览器中编辑配置、查看日志并按日期筛选。管理入口支持 4-64 位大小写字母，管理令牌支持 6-256 位可见字符。

Web 后台支持 YAML/JSON 一键导入导出。配置保存和导入会立即应用；监听端口修改会先绑定新端口再热切换，绑定失败时旧监听继续工作。配置审计日志会列出具体变更字段，代理 KEY 和管理令牌只记录长度，不记录明文。

`data` 目录必须保持可写，YAML、备份、日志和管理令牌 verifier 都会持久化在其中。管理后台按交付要求使用 HTTP，不内置 HTTPS；公网部署时应通过防火墙限制来源或只允许可信 VPN 访问。
