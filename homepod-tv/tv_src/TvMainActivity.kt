package com.homepodplus.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.homepodplus.app.airplay.AirplayDevice
import com.homepodplus.app.airplay.RaopSender.LatencyMode
import kotlinx.coroutines.launch

/**
 * Android TV remote-first UI over the original HomePod Plus controller and engine.
 * Every action is implemented by focusable Android Views, so D-pad and OK work
 * without a touch screen or an external mouse.
 *
 * The activity owns only user consent and controls. The audio session lives in
 * CastService, and continues after HOME or BACK removes this activity.
 */
class TvMainActivity : ComponentActivity() {
    private val bg = Color.rgb(13, 20, 36)
    private val panel = Color.rgb(25, 36, 58)
    private val idleButton = Color.rgb(45, 64, 95)
    private val focusButton = Color.rgb(35, 120, 222)
    private val accent = Color.rgb(132, 201, 255)
    private val white = Color.rgb(244, 248, 255)
    private val soft = Color.rgb(185, 203, 224)

    private var pendingDevice: AirplayDevice? = null
    private var tvRemoteMouse: TvRemoteMouse? = null
    private var mainScroll: ScrollView? = null
    private var pendingDual = false
    private lateinit var deviceList: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var offsetLabel: TextView
    private lateinit var defaultsLabel: TextView
    private lateinit var logsLabel: TextView
    private lateinit var dualButton: Button
    private lateinit var stopButton: Button
    private lateinit var testToneButton: Button
    private lateinit var robustButton: Button
    private lateinit var alacButton: Button
    private lateinit var channelButton: Button
    private lateinit var leftButton: Button
    private lateinit var offsetModeButton: Button
    private lateinit var hwVolumeButton: Button
    private lateinit var notificationButton: Button
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private val latencyButtons = LinkedHashMap<LatencyMode, Button>()
    private var previousDeviceSignature = ""

    private val recordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) askForMediaProjection()
        else notice("需要先允许录制音频，才能将电视声音投送到 HomePod。")
    }

    private val mediaProjectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val device = pendingDevice
        if (device != null && result.resultCode == RESULT_OK && result.data != null) {
            val command = Intent(this, CastService::class.java)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CastService.EXTRA_DATA, result.data)
                .putExtra(CastService.EXTRA_HOST, device.host)
                .putExtra(CastService.EXTRA_PORT, device.port)
                .putExtra(CastService.EXTRA_NAME, device.name)
                .putExtra(CastService.EXTRA_IS_DUAL, pendingDual)
            ContextCompat.startForegroundService(this, command)
        } else if (device != null) {
            notice("尚未允许系统音频捕获，投送没有启动。")
        }
        pendingDevice = null
        pendingDual = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        CastController.loadManual(applicationContext)
        askForOptionalPermissions()
        buildScreen()
        tvRemoteMouse = TvRemoteMouse(window, mainScroll).also {
            it.attach()
            it.resume()
        }
        lifecycleScope.launch {
            CastController.state.collect { state -> refresh(state) }
        }
    }

    override fun onStart() {
        super.onStart()
        tvRemoteMouse?.resume()
        CastController.startDiscovery(applicationContext)
    }

    override fun onStop() {
        tvRemoteMouse?.pause()
        // Do not leave the multicast scanner consuming CPU/network resources
        // while another Android TV app is producing captured audio.
        CastController.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        tvRemoteMouse?.close()
        tvRemoteMouse = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The visual pointer consumes only D-pad, OK and page/channel keys.
        // Back, Home, volume and keyboard entry retain Android semantics.
        if (tvRemoteMouse?.handle(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (CastController.state.value.streaming) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                changeVolume(5f)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                changeVolume(-5f)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun askForOptionalPermissions() {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permissions += Manifest.permission.POST_NOTIFICATIONS
        // ACCESS_LOCAL_NETWORK only exists on some modern Android builds.
        val localNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
        val exists = try {
            packageManager.getPermissionInfo(localNetwork, 0)
            true
        } catch (_: Exception) { false }
        if (exists && ContextCompat.checkSelfPermission(this, localNetwork) != PackageManager.PERMISSION_GRANTED) {
            permissions += localNetwork
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 103)
    }

    private fun beginCast(device: AirplayDevice, dual: Boolean) {
        if (CastController.state.value.connecting || CastController.state.value.streaming) {
            notice("请先停止当前投送，再连接其他音箱。")
            return
        }
        pendingDevice = device
        pendingDual = dual
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askForMediaProjection()
        } else {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askForMediaProjection() {
        if (pendingDevice == null) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionRequest.launch(manager.createScreenCaptureIntent())
    }

    private fun startDual() {
        val config = CastController.state.value.dualConfig
        if (!config.isConfigured) {
            notice("请先设置主音箱和副音箱。")
            return
        }
        CastController.setVolume(config.defaultVolume)
        beginCast(AirplayDevice(config.speaker1Name, config.speaker1Host, config.speaker1Port), true)
    }

    private fun latencyName(mode: LatencyMode): String = when (mode) {
        LatencyMode.SAFE -> "安全（2 秒）"
        LatencyMode.STABLE -> "稳定（1.5 秒）"
        LatencyMode.LOW -> "低延迟（0.25 秒）"
        LatencyMode.ULTRA -> "超低延迟（0.1 秒）"
    }

    private fun changeVolume(diff: Float) {
        CastController.setVolume(CastController.state.value.volume + diff)
    }

    private fun mutateConfig(change: (DualConfig) -> DualConfig) {
        CastController.updateDualConfig(change(CastController.state.value.dualConfig))
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            isSmoothScrollingEnabled = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(55), dp(32), dp(55), dp(60))
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        mainScroll = scroll
        setContentView(scroll)

        body.addView(text("HomePod 电视遥控版  |  v1.5.0-tv.4", 33f, white, true))
        body.addView(text("遥控鼠标已启用：方向键移动白色光标，长按可加速，确认键相当于鼠标左键。右上角会显示「遥控鼠标 v4」。", 19f, accent, true))
        body.addView(text("返回键和 Home 键保持原有作用。离开界面后，音频投送仍由前台服务运行。", 16f, soft))

        val stream = section(body, "音频投送")
        statusLabel = text("未连接", 23f, accent, true)
        stream.addView(statusLabel)
        dualButton = action("连接双音箱") { startDual() }
        stopButton = action("停止投送") { CastController.stop(this) }
        actionRow(stream, dualButton, stopButton)
        volumeLabel = text("HomePod 音量：50%", 21f, white)
        stream.addView(volumeLabel)
        actionRow(stream,
            action("音量 -5%") { changeVolume(-5f) },
            action("音量 +5%") { changeVolume(5f) }
        )
        testToneButton = action("440 Hz 测试音") { CastController.toggleTestTone(this) }
        actionRow(stream, testToneButton, action("重新搜索音箱") {
            CastController.stopDiscovery()
            CastController.startDiscovery(this)
        })

        val devices = section(body, "已发现和已保存的 AirPlay 音箱")
        devices.addView(text("可以直接连接音箱，也可以设为主音箱或副音箱，或手动输入 IP 地址。", 16f, soft))
        actionRow(devices, action("手动添加 IP 地址") { editSpeakerDialog(null) })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices.addView(deviceList)

        val config = section(body, "双音箱设置")
        actionRow(config,
            action("编辑主音箱名称 / IP / 端口") { editSpeakerDialog(1) },
            action("编辑副音箱名称 / IP / 端口") { editSpeakerDialog(2) }
        )
        primaryButton = action("主音箱：未设置") { editSpeakerDialog(1) }
        secondaryButton = action("副音箱：未设置") { editSpeakerDialog(2) }
        actionRow(config, primaryButton, secondaryButton)

        channelButton = action("声道模式：完整立体声") {
            mutateConfig { it.copy(channelMode = if (it.channelMode == ChannelMode.STEREO) ChannelMode.SPLIT else ChannelMode.STEREO) }
        }
        leftButton = action("左声道：主音箱") {
            mutateConfig {
                it.copy(leftSpeaker = if (it.leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) LeftSpeakerAssignment.SPEAKER2_LEFT else LeftSpeakerAssignment.SPEAKER1_LEFT)
            }
        }
        actionRow(config, channelButton, leftButton)

        offsetModeButton = action("音量联动：固定差值") {
            mutateConfig { it.copy(offsetMode = if (it.offsetMode == VolumeOffsetMode.FIXED) VolumeOffsetMode.RATIO else VolumeOffsetMode.FIXED) }
        }
        actionRow(config, offsetModeButton)
        offsetLabel = text("副音箱音量偏移：-10%", 19f, white)
        config.addView(offsetLabel)
        actionRow(config,
            action("偏移 -5") { mutateConfig { it.copy(offsetValue = (it.offsetValue - 5f).coerceIn(-100f, 100f)) } },
            action("偏移 +5") { mutateConfig { it.copy(offsetValue = (it.offsetValue + 5f).coerceIn(-100f, 100f)) } }
        )
        defaultsLabel = text("双音箱默认音量：50%", 19f, white)
        config.addView(defaultsLabel)
        actionRow(config,
            action("默认音量 -5%") { mutateConfig { it.copy(defaultVolume = (it.defaultVolume - 5f).coerceIn(0f, 100f)) } },
            action("默认音量 +5%") { mutateConfig { it.copy(defaultVolume = (it.defaultVolume + 5f).coerceIn(0f, 100f)) } }
        )

        val network = section(body, "音频与网络稳定性")
        network.addView(text("如有卡顿，建议使用「安全 2 秒」或「稳定 1.5 秒」延迟模式。", 17f, soft))
        val latencyChoices = LatencyMode.entries.map { mode ->
            val button = action(latencyName(mode)) { CastController.setLatencyMode(mode) }
            latencyButtons[mode] = button
            button
        }
        actionRow(network, *latencyChoices.toTypedArray())
        robustButton = action("弱网增强：关") {
            CastController.setRobustMode(!CastController.state.value.robustMode)
        }
        alacButton = action("ALAC 压缩：开") {
            CastController.setAlac(!CastController.state.value.alacOn)
        }
        actionRow(network, robustButton, alacButton)
        hwVolumeButton = action("遥控器音量联动：开") {
            mutateConfig { it.copy(hardwareVolControl = !it.hardwareVolControl) }
        }
        notificationButton = action("常驻通知控制：开") {
            mutateConfig { it.copy(showPersistentNotification = !it.showPersistentNotification) }
        }
        actionRow(network, hwVolumeButton, notificationButton)
        network.addView(text("有些电视的音量键会由 HDMI-CEC 或电视系统接管。如果不能直接调整 HomePod 音量，请使用上方的音量按钮。某些电视无法显示原版手机通知栏和快捷设置入口。", 15f, soft))

        val logging = section(body, "连接日志与诊断")
        actionRow(logging, action("清空日志") { CastController.clearLogs() })
        logsLabel = text("暂无日志", 16f, soft)
        logging.addView(logsLabel)

        body.addView(text("系统限制：一般 Android TV 应用无法捕获受 DRM 保护的声音或 HDMI 输入。若系统终止音频捕获授权，需要再次手动允许。", 15f, soft))
        dualButton.requestFocus()
        refresh(CastController.state.value)
    }

    private fun refresh(state: CastController.UiState) {
        if (!::statusLabel.isInitialized) return
        statusLabel.text = when {
            state.streaming -> "正在投送  |  ${state.status}"
            state.connecting -> "连接中  |  ${state.status}"
            else -> "未连接  |  ${state.status}"
        }
        volumeLabel.text = "HomePod 音量：${state.volume.toInt()}%"
        dualButton.isEnabled = state.dualConfig.isConfigured && !state.connecting && !state.streaming
        stopButton.isEnabled = state.connecting || state.streaming
        testToneButton.text = if (state.testToneOn) "关闭测试音" else "440 Hz 测试音"
        robustButton.text = "弱网增强：${if (state.robustMode) "开" else "关"}"
        alacButton.text = "ALAC 压缩：${if (state.alacOn) "开" else "关"}"
        channelButton.text = "声道模式：${if (state.dualConfig.channelMode == ChannelMode.SPLIT) "左右声道分离" else "完整立体声"}"
        leftButton.text = "左声道：${if (state.dualConfig.leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) "主音箱" else "副音箱"}"
        offsetModeButton.text = "音量联动：${if (state.dualConfig.offsetMode == VolumeOffsetMode.FIXED) "固定差值" else "相对比例"}"
        offsetLabel.text = "副音箱音量偏移：${state.dualConfig.offsetValue.toInt()}%"
        defaultsLabel.text = "双音箱默认音量：${state.dualConfig.defaultVolume.toInt()}%"
        hwVolumeButton.text = "遥控器音量联动：${if (state.dualConfig.hardwareVolControl) "开" else "关"}"
        notificationButton.text = "常驻通知控制：${if (state.dualConfig.showPersistentNotification) "开" else "关"}"
        primaryButton.text = "主音箱：${state.dualConfig.speaker1Name} (${state.dualConfig.speaker1Host.ifBlank { "未设置" }})"
        secondaryButton.text = "副音箱：${state.dualConfig.speaker2Name} (${state.dualConfig.speaker2Host.ifBlank { "未设置" }})"
        for ((mode, button) in latencyButtons) {
            button.text = "${latencyName(mode)}${if (mode == state.latencyMode) "（当前）" else ""}"
        }
        logsLabel.text = state.logs.takeLast(12).joinToString("\n").ifBlank { "暂无日志" }
        refreshDeviceList(state.devices)
    }

    private fun refreshDeviceList(devices: List<AirplayDevice>) {
        val signature = devices.joinToString("|") { "${it.name}#${it.host}#${it.port}#${it.type}" }
        if (signature == previousDeviceSignature) return
        previousDeviceSignature = signature
        val rememberedFocus = deviceList.findFocus()?.tag as? String
        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(text("暂未发现音箱。可点击「重新搜索音箱」，或手动输入 IP 地址。", 17f, soft))
        }
        for (device in devices) {
            deviceList.addView(text("${device.name}   ${device.host}:${device.port}", 20f, white, true))
            val controls = ArrayList<Button>()
            val key = "${device.host}:${device.port}"
            controls += action("连接") { beginCast(device, false) }.apply { tag = "$key:connect" }
            controls += action("设为主音箱") {
                mutateConfig { it.copy(speaker1Name = device.name, speaker1Host = device.host, speaker1Port = device.port) }
            }.apply { tag = "$key:primary" }
            controls += action("设为副音箱") {
                mutateConfig { it.copy(speaker2Name = device.name, speaker2Host = device.host, speaker2Port = device.port) }
            }.apply { tag = "$key:secondary" }
            if (device.type == "_manual") {
                controls += action("移除") { CastController.removeManual(device) }.apply { tag = "$key:remove" }
            }
            actionRow(deviceList, *controls.toTypedArray())
        }
        if (rememberedFocus != null) {
            deviceList.findViewWithTag<View>(rememberedFocus)?.requestFocus()
        }
    }

    /** slot null adds manual IP; 1 edits primary; 2 edits secondary. */
    private fun editSpeakerDialog(slot: Int?) {
        val cfg = CastController.state.value.dualConfig
        val currentName = when (slot) { 1 -> cfg.speaker1Name; 2 -> cfg.speaker2Name; else -> "HomePod" }
        val currentHost = when (slot) { 1 -> cfg.speaker1Host; 2 -> cfg.speaker2Host; else -> "" }
        val currentPort = when (slot) { 1 -> cfg.speaker1Port; 2 -> cfg.speaker2Port; else -> 7000 }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        fun field(hint: String, value: String, number: Boolean = false): EditText {
            return EditText(this).apply {
                setSingleLine(true)
                this.hint = hint
                setText(value)
                selectAll()
                textSize = 20f
                inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                form.addView(this, LinearLayout.LayoutParams(-1, dp(68)))
            }
        }
        val name = field("音箱名称", currentName)
        val host = field("IPv4 地址或主机名", currentHost)
        val port = field("端口", currentPort.toString(), true)
        AlertDialog.Builder(this)
            .setTitle(if (slot == null) "添加音箱" else "编辑${if (slot == 1) "主" else "副"}音箱")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val parsedHost = host.text.toString().trim()
                        val parsedPort = port.text.toString().toIntOrNull()
                        val parsedName = name.text.toString().trim().ifEmpty { "HomePod" }
                        if (parsedHost.isBlank() || parsedPort == null || parsedPort !in 1..65535) {
                            notice("请输入有效的 IP / 主机名，端口范围为 1 至 65535。")
                            return@setOnClickListener
                        }
                        when (slot) {
                            1 -> mutateConfig { it.copy(speaker1Name = parsedName, speaker1Host = parsedHost, speaker1Port = parsedPort) }
                            2 -> mutateConfig { it.copy(speaker2Name = parsedName, speaker2Host = parsedHost, speaker2Port = parsedPort) }
                            else -> CastController.addManual(AirplayDevice(parsedName, parsedHost, parsedPort, type = "_manual"))
                        }
                        dismiss()
                    }
                    name.requestFocus()
                }
                show()
                // Dialogs own separate Android windows. Provide the same
                // D-pad mouse here so Save, Cancel and EditText are clickable.
                val dialogPointer = window?.let { TvRemoteMouse(it) }
                dialogPointer?.attach()
                dialogPointer?.resume()
                setOnKeyListener { _, _, keyEvent ->
                    dialogPointer?.handle(keyEvent) == true
                }
                setOnDismissListener { dialogPointer?.close() }
            }
    }

    private fun notice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        CastController.addLog("电视界面：$message")
    }

    private fun section(body: LinearLayout, title: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = shape(panel, dp(16).toFloat())
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) }
        body.addView(container, params)
        val h = text(title, 24f, accent, true)
        h.setPadding(0, 0, 0, dp(12))
        container.addView(h)
        return container
    }

    private fun text(value: String, size: Float, color: Int, strong: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (strong) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(8))
        }
    }

    private fun action(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(white)
            isFocusable = true
            isFocusableInTouchMode = true
            background = shape(idleButton, dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minHeight = dp(54)
            setOnFocusChangeListener { view, focused ->
                view.background = shape(if (focused) focusButton else idleButton, dp(12).toFloat())
                view.scaleX = if (focused) 1.02f else 1f
                view.scaleY = if (focused) 1.02f else 1f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun actionRow(parent: LinearLayout, vararg actions: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        for (button in actions) {
            val lp = LinearLayout.LayoutParams(0, dp(59), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            row.addView(button, lp)
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun shape(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
