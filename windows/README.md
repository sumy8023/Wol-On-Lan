# Windows 代理服务

本目录保存 Windows 便携启动器源码和打包脚本：

- `launcher/`：单文件 EXE 启动器源码和图标生成脚本。
- `build.ps1`：Windows 便携 EXE 打包入口。

在项目根目录执行：

```powershell
.\windows\build.ps1
```

默认输出为 `pack/WOL-Proxy-Windows.exe`。可以通过
`-OutputFile <路径>` 指定其他输出位置。

构建使用 `_tools/` 内的 JDK 和 Gradle，并要求系统 `PATH` 中存在 .NET 10 SDK。
