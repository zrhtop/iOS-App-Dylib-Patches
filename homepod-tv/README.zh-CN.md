# HomePod TV Mouse (Android TV) v1.5.0-tv.3

本项目基于 [M-Pennn/HomePod-Plus v1.5](https://github.com/M-Pennn/HomePod-Plus/releases/tag/v1.5)，保留上游 AirPlay 2 发送、音频捕获、双 HomePod、音量、延迟及后台服务，增加 Android TV 专用界面与方向键虚拟鼠标。

## 下载已验证的 APK

- [GitHub Actions 成功构建 #39377876872](https://github.com/zrhtop/iOS-App-Dylib-Patches/actions/runs/39377876872)
- Artifact: `HomePodPlus-TV-v1.5.0-tv.3-Mouse-Verified-APK`
- APK: ZIP 内的 `app-debug.apk`
- SHA-256: `3ed375132f680000fd4c3d23e842baea8d88654b5011b5413b59d6626eb240fc`

这次进行了 **独立应用标识** 修复，安装后会新增一个应用，不会替代此前 APK：

| 项目 | 新版本 |
| --- | --- |
| 应用显示名称 | **HomePod TV Mouse** |
| 包名 | `com.homepodplus.tv.remote` |
| 版本 | `1.5.0-tv.3` |
| 最低系统 | Android 10 / API 29 |
| 启动界面 | `com.homepodplus.app.TvMainActivity` |
| 应用图标 | 蓝绿色鼠标箭头 |
| 界面识别标记 | 右上角常驻 `TV MOUSE v3` |

旧版 `HomePod +` 和新版 `HomePod TV Mouse` 会同时出现在电视应用列表。**请务必打开新版 HomePod TV Mouse，而不是旧版 HomePod +。**

## 遥控操作

- 方向键上下左右：移动应用内白色鼠标指针
- 长按方向键：连续移动并逐渐加速
- OK / 确定键：在指针位置模拟左键单击
- 指针靠近页面上下边缘：滚动内容
- 频道/翻页键：翻页（电视遥控器支持时）
- Back/Home、音量：保留系统默认操作

添加 HomePod IP 地址的应用内弹窗也实现了虚拟指针。Android 系统权限申请、系统音频投屏授权弹窗属于系统窗口，不受应用内部鼠标控制，需要使用系统自身的 D-pad 焦点操作。

## GitHub 检查结果

- `assembleDebug` 编译通过
- APK ZIP 结构检查通过
- APK v2 签名验证通过
- `aapt dump badging` 验证普通启动和 LEANBACK 电视启动都指向 `TvMainActivity`
- 验证没有保留旧手机界面的启动入口
- 验证 `TvRemoteMouse`、`TvMainActivity` 和独立的电视鼠标图标均存在于编译 APK
- 下载产物的 SHA-256 与 GitHub Actions 一致

**还需要实机验证：** 这些检查验证了构建内容，但不代表不同厂商电视的遥控事件、触摸模拟、音频投送或后台运行已经经过真实电视测试。

如果安装后仍看不到白色鼠标箭头，先确认顶部标题为 `HomePod TV Mouse | v1.5.0-tv.3` 且右上角出现 `TV MOUSE v3`；如果看不到，请拍摄电视显示和应用信息页以确认实际打开的 APK。

## 系统限制

Android 原生应用不能无条件捕获 Netflix 等 DRM 音频、HDMI 输入和禁止捕获的应用音频。Android 可撤销 MediaProjection 授权，网络断线恢复不能绕过系统重新授权。部分遥控音量键由 HDMI-CEC 或系统接管。

