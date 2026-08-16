# Android 客户端

本目录是 Android APK 的完整 Gradle 模块，包含界面、设备与代理节点管理、局域网/代理唤醒、本机代理服务和单元测试。

在项目根目录统一构建：

```powershell
.\scripts\build-packages.ps1
```

正式 APK 输出到 `pack/WOL-网络唤醒.apk`。
