# WOL 代理服务 Docker 部署

此目录与已构建的 `WOL-Proxy-Docker-Image.tar` 配套使用，不需要在 NAS 上重新构建镜像。

## 配置

`data/config.yml` 中的 `key` 默认留空。Docker 首次启动会使用安全随机数生成 24 位代理连接 KEY，写回 `/config/config.yml`，并且只在本次控制台显示一次。请将该 KEY 填入 APK 的代理节点。也可在启动前手动填写 `key`，或设置环境变量 `WOL_PROXY_KEY`，已配置的值不会被覆盖。

`admin_path` 可以保持为空，首次启动时服务会自动生成；管理令牌只在控制台显示一次，不会写入 YAML，旁边的受限文件 `config.yml.admin-token` 只保存 PBKDF2 verifier，不保存令牌明文。忘记令牌时，在 YAML 中临时添加 `admin_token: ""` 并重启即可轮换。

## 导入并启动

```bash
docker load -i WOL-Proxy-Docker-Image.tar
docker compose up -d
```

Compose 已预设 `network_mode: host`，并把项目目录下的 `data` 文件夹挂载到容器的 `/config`。在飞牛 NAS 导入 Compose 项目时，网络模式和存储挂载会自动带入，通常不需要再填写端口映射。即使挂载的目录完全为空，新版镜像也会自动创建可用配置，不会因缺少 KEY 陷入重启循环。

如果使用飞牛的“从镜像创建容器”界面，请手动选择“主机/Host”网络，并添加存储映射：主机路径选择飞牛上的共享文件夹（例如 `.../docker/wol-proxy/data`），容器路径填写 `/config`，权限选择读写。镜像本身无法预先写死飞牛的绝对主机路径，因为每台 NAS 的卷路径不同。

存储映射的容器路径必须是目录 `/config`，不要把主机目录填到 `/config/config.yml`；后者会让 `config.yml` 变成目录，配置将无法读写。

请注意：`network_mode: host` 是广播唤醒的关键配置。不要只使用
`-p 14250:14250/tcp` 或只映射 UDP 9；端口映射只负责进入容器的流量，不能把
容器发出的 UDP 广播送入宿主机所在局域网。若必须使用 bridge 网络，需要自行配置
可达的局域网广播/路由，且 Web 端口必须和 YAML 的 `listen` 端口一一对应。
修改 `listen` 端口后，host 网络会直接监听新端口；bridge 部署则必须同步修改
`-p 新端口:新端口/tcp`，否则管理后台无法访问。

查看中文日志：

```bash
docker compose logs -f
```

日志中会显示完整 Web 管理后台地址；只有首次生成或明确将 `admin_token` 置空时才显示一次新令牌。管理后台与代理接口共用端口，例如 `http://代理地址:14250/aBcDeFGh/`；可在手机或电脑浏览器中编辑配置、查看日志并按日期筛选。管理入口支持 4-64 位大小写字母，管理令牌支持 6-256 位可见字符。

Web 后台支持 YAML/JSON 一键导入导出。配置保存和导入会立即应用；监听端口修改会先绑定新端口再热切换，绑定失败时旧监听继续工作。配置审计日志会列出具体变更字段，代理 KEY 和管理令牌只记录长度，不记录明文。

Compose 会把 `data` 目录挂载到容器的 `/config`，以便 YAML、备份、日志和管理令牌 verifier 一起持久化；配置文件必须可写，否则 Web 保存无法写回。管理后台按交付要求使用 HTTP，不内置 HTTPS；公网部署时应通过防火墙限制来源或只允许可信 VPN 访问。
