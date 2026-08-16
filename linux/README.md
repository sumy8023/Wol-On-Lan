# Linux 代理服务

本目录只保存 Linux 安装与维护相关文件：

- `start-linux.sh`：纯安装/升级入口。
- `wol`：安装后提供停止、重启、重置管理员密钥、重置配置和卸载菜单。
- `linux-installer-lib.sh`：构建时嵌入安装脚本的内部解析组件。
- `tests/`：无需 root 或 systemd 的 shell 测试。

正式安装包由项目根目录执行以下命令生成：

```powershell
.\scripts\build-packages.ps1
```

输出文件为 `pack/WOL-Proxy-Linux.tar`。解压后运行：

```sh
chmod +x start-linux.sh
./start-linux.sh
```

安装完成后直接运行 `wol` 打开维护菜单。
