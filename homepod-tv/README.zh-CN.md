# HomePod Plus Android TV 测试版

源代码：[HomePod Plus v1.5](https://github.com/M-Pennn/HomePod-Plus/releases/tag/v1.5)

[Android TV 移植代码](https://github.com/zrhtop/iOS-App-Dylib-Patches/tree/homepod-plus-androidtv) | [已成功的构建记录](https://github.com/zrhtop/iOS-App-Dylib-Patches/actions/runs/39375162264)

**版本**：`1.5.0-tv.1`  **包名**：`com.homepodplus.tv`  **最低 Android 版本**：10（API 29）

## 安装

在 GitHub Actions 下载 `HomePodPlus-TV-v1.5-debug-APK`，解压 ZIP 后得到 `app-debug.apk`，并用 Android TV 文件管理器安装，或者运行：

```bash
adb install -r app-debug.apk
```

安装后从电视启动器打开 HomePod +。使用遥控器方向键选择，确认键操作。需要用户同意录音权限和系统投屏授权，才能捕获允许被捕获的应用音频。

## 主要功能

- 局域网扫描 HomePod、手动添加 IP 和端口
- 单台 HomePod 连接，双 HomePod 同步发送，声道分离或完整立体声
- 双音箱音量比率与差值、默认音量、HomePod 音量调整
- SAFE、STABLE、LOW、ULTRA 四档延迟，网络冗余模式，ALAC 编码设置
- 440 Hz 测试音、连接日志、前台通知控制
- 切换至其他 Android TV 应用后继续使用前台服务转发允许被捕获的系统音频
- 网络闪断后重试 AirPlay 连接，尽力保留已授权的音频捕获会话

## 重要限制

1. 普通 Android TV App **无法无条件捕获 Netflix 等 DRM 音频、HDMI 输入、禁止捕获的其他应用声音**。这属于 Android 系统限制。
2. Android TV 可能终止后台服务或撤销 MediaProjection 授权。此时用户必须再次批准授权，不能静默恢复。
3. 部分电视的遥控音量键会被系统或 HDMI-CEC 接管；必要时返回本 App 调整 HomePod 音量。
4. 这是供实机测试的 Debug APK；已在 GitHub Actions 成功编译、完整性检测、APK v2 签名检测，但**没有经过真实 HomePod 长时间播放验证**。
5. 保留原项目的版权和第三方音频代码许可证义务。

## APK 完整性校验

GitHub Actions 构建 #39375162264

`app-debug.apk` SHA-256：

```
887a2f8e6d56b0c5f160c5175eb8d733071425bc80abe02dcc11880b4433f4d0
```
