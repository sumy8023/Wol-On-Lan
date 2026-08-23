# Android 11 手表客户端

这是面向普通 Android 11 圆形手表的精简 WOL 客户端，使用独立包名
`com.example.wolquicktile.watch`，不会覆盖手机端 APK。

## 功能

- 保存设备名称、MAC、广播地址和 UDP 端口。
- 直接通过手表所在局域网发送 WOL 魔术包。
- 配置多个远程代理节点，使用与桌面代理兼容的 HMAC-SHA256 协议。
- 代理节点健康检查、启用/停用和设备绑定。
- 注册一个标准 Android Quick Settings 磁贴，唤醒当前选中的设备。

手表端不运行代理服务，也不包含手机端的分组、备份和大量快捷磁贴功能。
代理 KEY 仅保存在手表本地，手表 APK 不参与系统备份；重新安装后需要重新配置节点。

## 构建

```powershell
$env:JAVA_HOME=(Resolve-Path .\_tools\jdk-17.0.20+8).Path
$env:ANDROID_HOME=(Resolve-Path .\_tools\android-sdk).Path
.\_tools\gradle-8.9\bin\gradle.bat :watch:assembleDebug --no-daemon
```

输出文件：`watch/build/outputs/apk/debug/watch-debug.apk`。

统一打包脚本会复制为 `pack/WOL-Watch.apk`。

## 磁贴限制

磁贴使用 Android 11 标准 `TileService`，不是 Wear OS Tiles API。目标手表的系统必须提供第三方快捷设置磁贴入口；如果厂商系统没有该入口，APK 无法强制添加磁贴。

磁贴当前绑定手表端选中的设备。点击磁贴后由磁贴服务发送一次局域网或代理唤醒请求，网络权限和代理地址必须在手表 APK 内提前配置。
