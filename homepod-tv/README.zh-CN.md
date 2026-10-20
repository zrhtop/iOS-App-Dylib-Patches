# HomePod 电视遥控 v1.5.0-tv.4（简体中文版）

在 [HomePod Plus v1.5](https://github.com/M-Pennn/HomePod-Plus/releases/tag/v1.5) 基础上移植的 Android TV 应用，保留 AirPlay 2 投送引擎、双音箱、音量、延迟、ALAC、弱网增强和前台后台投送服务。

## 直接安装

- [GitHub Actions 成功构建 #39379719134](https://github.com/zrhtop/iOS-App-Dylib-Patches/actions/runs/39379719134)
- 构建产物：`HomePod-TV-v1.5.0-tv.4-CN-NSD-Fix-APK`，解压后安装 `app-debug.apk`
- 应用名称：**HomePod 电视遥控**
- 包名：`com.homepodplus.tv.remote`（与 v1.5.0-tv.3 相同）
- 版本：`1.5.0-tv.4`；版本号：154
- 最低 Android 版本：Android 10 / API 29

APK SHA-256：

```text
126e6785f1916c78bcb3a95e76a3f9e018967be64f6442421335229c8c452758
```

GitHub Actions 已完成 Kotlin 编译、APK ZIP 完整性校验、v2 签名校验、Android TV 启动入口检查，并验证编译产物包含 `TvRemoteMouse`、`TvMainActivity`、`nsdResolveQueue` 与 `resolveNextNsd`。

## 已修改内容

### NSD 解析失败：错误码 3

Android `NsdManager.FAILURE_ALREADY_ACTIVE` 的值是 `3`，表示此前的解析请求尚在执行。上游代码在多次 `onServiceFound` 后并发调用 `resolveService`，可能在部分 Android TV 固件上出现大量此错误。

此版加入：

- NSD 单一活动解析请求与队列，其余服务依次等待
- 按服务名和服务类型去重，避免重复排队
- 对错误码 3 限次延迟重试，并暂缓新解析任务
- 出错时继续使用项目既有原始 mDNS 发现，不删除手动添加 IP 的功能
- 断开扫描时清理待处理队列，防止老任务遗留

**注意：** 这一改动解决了应用自身同时发起多项解析造成的冲突，但无法保证某些 Android TV 固件完全没有 NSD 兼容问题。已通过编译校验，尚未在你的电视和 HomePod 上验证自动发现成功。

### 简体中文界面

主界面、设备搜索、主副音箱设置、声道、延迟、安全提示、手动编辑音箱、音量、网络增强、状态和日志入口已转为简体中文。Android 系统权限对话框仍然由系统语言控制，AirPlay 原始技术日志中的协议名保留。

### 遥控鼠标

保留 v3 已可工作的内部指针：

- 方向键上下左右移动白色指针
- 长按方向键连续移动并加速
- OK / 确认键模拟应用内鼠标左键
- 页面边缘自动滚动
- 返回键、Home 键仍为系统功能
- 右上角标记“遥控鼠标 v4”

无需悬浮窗或无障碍权限；仅控制本应用界面，不控制其他应用或系统权限弹窗。

## 安装升级注意

各次 GitHub Actions Debug APK 可能使用不同的调试签名。若从 v1.5.0-tv.3 覆盖安装时出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 安装失败，请先记下手动配置的 HomePod 地址，然后卸载 `HomePod TV Mouse` 旧版再安装此版；卸载会清空旧版保存的设置。最早的 `HomePod +` 测试版属于另一包名，可独立保留或删除。

## 排查发现问题

如果仍找不到音箱，确保 Android TV 和 HomePod 位于可互访的同一个局域网或可转发 mDNS 的网络，关闭路由器 AP 隔离或访客网络隔离（如果存在）。可在路由器上查到 HomePod IPv4 地址后点击“手动添加 IP 地址”。注意实际投送端口不一定总是 7000，若手动连接失败请记录日志。

## Android 系统限制

受 DRM 保护的 App 音频、HDMI 输入以及禁止 AudioPlaybackCapture 的应用声音无法保证被捕获。若系统撤销 MediaProjection 授权，需要用户再次允许。GitHub 构建成功不等于真实 HomePod 的长期稳定性测试已完成。

