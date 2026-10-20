#!/usr/bin/env python3
"""Apply the Android TV overlay to the official HomePod Plus v1.5 source.

Upstream: https://github.com/M-Pennn/HomePod-Plus
Overlay license: Apache-2.0 (keep upstream LICENSE and NOTICE/credits).
This script deliberately rejects an unknown source layout instead of silently
creating a broken APK when the upstream implementation changes.
"""
import argparse
from pathlib import Path
import shutil
import sys


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 anchor, found {count}: {old[:80]!r}")
    return value.replace(old, new, 1)


def patch_manifest(content: str) -> str:
    content = replace_once(content,
        '    <uses-permission android:name="android.permission.INTERNET" />',
        '    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
        '    <uses-feature android:name="android.hardware.microphone" android:required="false" />\n'
        '    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n'
        '    <uses-feature android:name="android.software.leanback" android:required="false" />\n'
        '    <uses-permission android:name="android.permission.INTERNET" />',
        'manifest permissions and TV features')
    # Fully-qualified names allow the TV fork to use a separate package id.
    content = replace_once(content,
        '''        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>''',
        '''        <!-- Keep the original mobile activity internal for compatibility,
             but never offer it to a launcher on a television. -->
        <activity
            android:name="com.homepodplus.app.MainActivity"
            android:exported="false"
            android:launchMode="singleTop" />''',
        'remove mobile launcher so TV cursor is the only entry')
    content = content.replace('android:name=".CastService"', 'android:name="com.homepodplus.app.CastService"')
    content = content.replace('android:name=".HomePodTileService"', 'android:name="com.homepodplus.app.HomePodTileService"')
    content = replace_once(content,
        '        android:label="@string/app_name"',
        '        android:label="HomePod 电视遥控"\n        android:banner="@drawable/tv_banner"',
        'TV banner')
    content = replace_once(content,
        '        android:icon="@mipmap/ic_launcher"',
        '        android:icon="@drawable/tv_mouse_icon"',
        'distinct cursor-app icon')
    content = replace_once(content,
        '        <service\n            android:name="com.homepodplus.app.CastService"',
        '''        <activity
            android:name="com.homepodplus.app.TvMainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name="com.homepodplus.app.CastService"''',
        'TV launcher activity')
    return content


def patch_gradle(content: str) -> str:
    content = replace_once(content,
        'applicationId "com.homepodplus.app"',
        'applicationId "com.homepodplus.tv.remote"',
        'standalone Android TV application ID')
    content = replace_once(content, 'versionName "1.5.0"',
        'versionName "1.5.0-tv.4"', 'TV release version')
    content = replace_once(content, 'versionCode 150',
        'versionCode 154', 'TV cursor release version code')
    return content


def patch_controller(content: str) -> str:
    content = replace_once(content,
        '                _state.update { it.copy(devices = devices.sortedBy { d -> d.name }) }',
        '''                // Preserve user-added addresses when the mDNS list refreshes.
                _state.update { current ->
                    val manual = current.devices.filter { it.type == "_manual" }
                    current.copy(devices = (manual + devices)
                        .distinctBy { d -> d.host to d.port }
                        .sortedBy { d -> d.name })
                }''',
        'preserve manual devices')
    content = replace_once(content,
        '    fun addManual(device: AirplayDevice) {\n        _state.update {',
        '    fun addManual(device: AirplayDevice) {\n'
        '        val manual = device.copy(type = "_manual")\n'
        '        _state.update {',
        'mark manual devices')
    content = replace_once(content,
        'devices = (it.devices + device).distinctBy { d -> d.host to d.port },',
        'devices = (it.devices + manual).distinctBy { d -> d.host to d.port },',
        'save manual devices')
    content = replace_once(content,
        '            .filter { it.type == "_manual" || it.txt.isEmpty() }',
        '            .filter { it.type == "_manual" }',
        'persist only explicitly manually added addresses')
    content = replace_once(content,
        'val dev = AirplayDevice(name = name, host = host, port = port, type = "_airplay._tcp.local")',
        'val dev = AirplayDevice(name = name, host = host, port = port, type = "_manual")',
        'restore manual devices')
    content = replace_once(content,
        '    fun setTestTone(on: Boolean) {',
        '''    /** A network drop keeps the original MediaProjection session alive. */
    fun setReconnecting(message: String) {
        _state.update { it.copy(connecting = true, streaming = false, status = message) }
    }

    fun setTestTone(on: Boolean) {''',
        'reconnect UI state')
    return content


def patch_service(content: str) -> str:
    content = replace_once(content,
        '    private var stopping = false',
        '    @Volatile private var stopping = false',
        'service stop visibility')
    content = replace_once(content,
        '    private var projection: MediaProjection? = null',
        '    @Volatile private var projection: MediaProjection? = null',
        'projection visibility')
    content = replace_once(content,
        '    private var audioMgr: AudioManager? = null',
        '    private var audioMgr: AudioManager? = null\n    @Volatile private var isTelevision = false',
        'TV runtime state')
    content = replace_once(content,
        '                        if (hwEnabled) {\n                            if (idx != volLast) {',
        '                        if (isTelevision) {\n                            // Mirror volume changes without muting Android TV playback.\n                            if (hwEnabled && idx != volLast) {\n                                val steps = idx - volLast\n                                CastController.setVolume(CastController.state.value.volume + steps * 5f)\n                            }\n                            volLast = idx\n                        } else if (hwEnabled) {\n                            if (idx != volLast) {',
        'TV-safe system-volume poller')
    content = replace_once(content,
        '        unmuteSpeaker()\n        stopTestTone()',
        '        uiHandler.removeCallbacks(volPoller)\n        unmuteSpeaker()\n        stopTestTone()',
        'stop TV system-volume poller')
    content = replace_once(content,
        '    private var sessionGeneration = 0',
        '''    @Volatile private var sessionGeneration = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectAttempts = intArrayOf(0, 0)
    private val reconnectQueued = booleanArrayOf(false, false)''',
        'reconnect handler')
    content = replace_once(content,
        '        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)',
        '''        reconnectAttempts.fill(0)
        reconnectQueued.fill(false)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)''',
        'reset retry counters')
    content = replace_once(content,
        '''        // 投送启动：自动静音手机外放，开启物理音量键联动
        muteSpeaker()''',
        '''        // Android TV must not force system media volume to zero. It may
        // silence TV HDMI audio and even affect playback capture on some ROMs.
        isTelevision = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTelevision) {
            CastController.addLog("Android TV: preserving system audio volume during capture")
            audioMgr = getSystemService(AUDIO_SERVICE) as AudioManager
            volLast = audioMgr!!.getStreamVolume(AudioManager.STREAM_MUSIC)
            uiHandler.removeCallbacks(volPoller)
            uiHandler.postDelayed(volPoller, 100)
        } else {
            muteSpeaker()
        }''',
        'TV-safe system volume')
    content = replace_once(content,
        '''                        if (isPrimary) {
                            s1Started = true
                        } else {
                            s2Started = true
                        }''',
        '''                        if (isPrimary) {
                            synchronized(reconnectQueued) { reconnectAttempts[0] = 0; reconnectQueued[0] = false }
                            s1Started = true
                        } else {
                            synchronized(reconnectQueued) { reconnectAttempts[1] = 0; reconnectQueued[1] = false }
                            s2Started = true
                        }''',
        'successful reconnect')
    content = replace_once(content,
        '''                    override fun onStopped(reason: String?) {
                        if (gen != sessionGeneration) return
                        CastController.addLog("[${device.name}] 已停止: $reason")
                        if (isPrimary) {
                            s1Started = false
                        } else {
                            s2Started = false
                        }
                        if (isPrimary || sender2 == null) {
                            CastController.resetConnection(reason)
                            cleanup()
                            stopSelf()
                        } else {
                            uiHandler.post { updateLiveNotification() }
                        }
                    }''',
        '''                    override fun onStopped(reason: String?) {
                        if (gen != sessionGeneration || stopping) return
                        CastController.addLog("[${device.name}] 连接已断开：$reason")
                        if (isPrimary) s1Started = false else s2Started = false
                        // Retain the authorized MediaProjection, AudioRecord,
                        // wake lock and other speaker while reconnecting.
                        // Android 14+ forbids reusing the consent Intent to
                        // recreate a projection for every network glitch.
                        if (projection != null) {
                            scheduleReconnect(isPrimary, reason ?: "AirPlay session closed")
                        } else if (isPrimary || sender2 == null) {
                            CastController.resetConnection(reason)
                            cleanup()
                            stopSelf()
                        } else {
                            uiHandler.post { updateLiveNotification() }
                        }
                    }''',
        'recover disconnected AirPlay sessions')
    content = replace_once(content,
        '    private fun stopCasting() {',
        '''    /** Reconnect only RAOP while keeping the ongoing capture permission. */
    private fun scheduleReconnect(primary: Boolean, reason: String) {
        if (stopping || projection == null) return
        val index = if (primary) 0 else 1
        val count = synchronized(reconnectQueued) {
            if (reconnectQueued[index]) return
            reconnectQueued[index] = true
            reconnectAttempts[index] = (reconnectAttempts[index] + 1).coerceAtMost(1000000)
            reconnectAttempts[index]
        }
        val delayMs = when {
            count == 1 -> 1500L
            count == 2 -> 3000L
            count == 3 -> 6000L
            count == 4 -> 12000L
            else -> 30000L
        }
        val label = if (primary) "主音箱" else "副音箱"
        CastController.setReconnecting("$label 网络中断，正在第 $count 次重连")
        CastController.addLog("$label 断开：$reason，${delayMs} 毫秒后重试")
        uiHandler.post { updateLiveNotification() }
        val generation = sessionGeneration
        reconnectHandler.postDelayed({
            synchronized(reconnectQueued) { reconnectQueued[index] = false }
            if (stopping || generation != sessionGeneration || projection == null) return@postDelayed
            val target = if (primary) sender1 else sender2
            if (target != null) {
                CastController.addLog("正在重连 $label（第 $count 次）")
                target.start()
            }
        }, delayMs)
    }

    private fun stopCasting() {''',
        'reconnect scheduler')
    content = replace_once(content,
        '''        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }''',
        '''        cleanup()
        CastController.resetConnection("已手动停止投送")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }''',
        'reset state after manual stop')
    content = replace_once(content,
        '''    private fun cleanup() {
        pauseMediaPlayback()''',
        '''    private fun cleanup() {
        sessionGeneration++
        reconnectHandler.removeCallbacksAndMessages(null)
        synchronized(reconnectQueued) { reconnectQueued.fill(false) }
        pauseMediaPlayback()''',
        'cancel reconnect on cleanup')
    content = replace_once(content,
        '                acquire(6L * 60 * 60 * 1000)',
        '                acquire() // held only while the foreground casting session is active',
        'no fixed six-hour wake lock timeout')
    notification_intent = '        val openIntent = Intent(this, MainActivity::class.java)'
    if content.count(notification_intent) != 2:
        raise RuntimeError('Expected two upstream notification intents')
    content = content.replace(notification_intent, '        val openIntent = tvAwareLaunchIntent()')
    content = replace_once(content,
        '    private fun buildConnectingNotification(): Notification {',
        '''    private fun tvAwareLaunchIntent(): Intent {
        val isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        return Intent(this, if (isTv) TvMainActivity::class.java else MainActivity::class.java)
    }

    private fun buildConnectingNotification(): Notification {''',
        'launch correct activity from service notification')
    return content


def patch_discovery(content: str) -> str:
    old = """        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("airbridge-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }"""
    new = """        // Ethernet-only Android TV boxes may lack a Wi-Fi service.
        // Raw multicast/NSD can still function without the Wi-Fi lock.
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("airbridge-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            log("Wi-Fi multicast lock unavailable: ${e.message}")
        }"""
    return replace_once(content, old, new, 'Ethernet-only discovery')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', default='.', help='Path to HomePod-Plus upstream source checkout')
    args = parser.parse_args()
    project = Path(args.project).resolve()
    here = Path(__file__).resolve().parent
    root = project / 'app' / 'src' / 'main'
    manifest = root / 'AndroidManifest.xml'
    controller = root / 'java' / 'com' / 'homepodplus' / 'app' / 'CastController.kt'
    service = root / 'java' / 'com' / 'homepodplus' / 'app' / 'CastService.kt'
    discovery = root / 'java' / 'com' / 'homepodplus' / 'app' / 'airplay' / 'MdnsDiscoverer.kt'
    gradle = project / 'app' / 'build.gradle'
    dest_tv = root / 'java' / 'com' / 'homepodplus' / 'app' / 'TvMainActivity.kt'
    dest_mouse = root / 'java' / 'com' / 'homepodplus' / 'app' / 'TvRemoteMouse.kt'
    for file in (manifest, controller, service, discovery, gradle):
        if not file.is_file():
            sys.exit(f'Missing upstream source file: {file}')
    # Prepare in memory, so a failed anchor does not leave a half-patched tree.
    updates = {
        manifest: patch_manifest(manifest.read_text(encoding='utf-8-sig')),
        controller: patch_controller(controller.read_text(encoding='utf-8-sig')),
        service: patch_service(service.read_text(encoding='utf-8-sig')),
        discovery: patch_discovery(discovery.read_text(encoding='utf-8-sig')),
        gradle: patch_gradle(gradle.read_text(encoding='utf-8-sig')),
    }
    if dest_tv.exists() or dest_mouse.exists():
        sys.exit('TV source already exists. Use a clean v1.5 checkout.')
    for file, content in updates.items():
        file.write_text(content, encoding='utf-8')
    shutil.copy2(here / 'tv_src' / 'TvMainActivity.kt', dest_tv)
    shutil.copy2(here / 'tv_src' / 'TvRemoteMouse.kt', dest_mouse)
    images = root / 'res' / 'drawable-nodpi'
    images.mkdir(parents=True, exist_ok=True)
    # Generate a compact TV launcher banner at build time.
    from PIL import Image, ImageDraw
    banner = Image.new('RGB', (640, 360), (13, 20, 36))
    draw = ImageDraw.Draw(banner)
    draw.rounded_rectangle((40, 55, 600, 305), radius=30, fill=(27, 48, 82), outline=(61, 154, 229), width=4)
    draw.text((105, 145), "HomePod TV CN v4", fill=(235, 246, 255), stroke_width=0)
    banner.save(images / 'tv_banner.png', optimize=True)
    # A visually distinct icon proves which package TV users are opening.
    icon = Image.new('RGBA', (256, 256), (10, 34, 58, 255))
    icon_draw = ImageDraw.Draw(icon)
    icon_draw.rounded_rectangle((12, 12, 244, 244), radius=55,
        fill=(5, 112, 150, 255), outline=(135, 239, 255, 255), width=9)
    icon_draw.polygon([(65, 42), (77, 206), (118, 160),
                       (145, 225), (173, 211), (146, 147), (208, 143)],
        fill=(255, 255, 255, 255))
    icon_draw.line([(65, 42), (77, 206), (118, 160), (145, 225),
                    (173, 211), (146, 147), (208, 143), (65, 42)],
        fill=(6, 31, 55, 255), width=6, joint='curve')
    icon.save(images / 'tv_mouse_icon.png', optimize=True)
    print('Android TV source patch applied. Build with ./gradlew :app:assembleDebug')
    print('APK: app/build/outputs/apk/debug/app-debug.apk')


if __name__ == '__main__':
    main()
