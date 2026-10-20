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
        else notice("Audio capture permission is required to send TV audio to HomePod.")
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
            notice("Projection was not approved. Audio capture has not started.")
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
            notice("Stop the current stream before starting another.")
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
            notice("Select both primary and secondary speakers first.")
            return
        }
        CastController.setVolume(config.defaultVolume)
        beginCast(AirplayDevice(config.speaker1Name, config.speaker1Host, config.speaker1Port), true)
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

        body.addView(text("HomePod +   |   Android TV", 33f, white, true))
        body.addView(text("Use D-pad to move, OK to select, Back/Home to leave. Casting stays in the foreground service.", 17f, soft))

        val stream = section(body, "Casting")
        statusLabel = text("Idle", 23f, accent, true)
        stream.addView(statusLabel)
        dualButton = action("Start dual speakers") { startDual() }
        stopButton = action("Stop casting") { CastController.stop(this) }
        actionRow(stream, dualButton, stopButton)
        volumeLabel = text("HomePod volume: 50%", 21f, white)
        stream.addView(volumeLabel)
        actionRow(stream,
            action("Volume -5%") { changeVolume(-5f) },
            action("Volume +5%") { changeVolume(5f) }
        )
        testToneButton = action("440 Hz test tone") { CastController.toggleTestTone(this) }
        actionRow(stream, testToneButton, action("Refresh discovery") {
            CastController.stopDiscovery()
            CastController.startDiscovery(this)
        })

        val devices = section(body, "Discovered and saved AirPlay speakers")
        devices.addView(text("Connect a speaker, assign it as primary or secondary, or add an IP manually.", 16f, soft))
        actionRow(devices, action("Add speaker by IP") { editSpeakerDialog(null) })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devices.addView(deviceList)

        val config = section(body, "Dual-speaker configuration")
        actionRow(config,
            action("Edit primary name / IP / port") { editSpeakerDialog(1) },
            action("Edit secondary name / IP / port") { editSpeakerDialog(2) }
        )
        primaryButton = action("Primary: not selected") { editSpeakerDialog(1) }
        secondaryButton = action("Secondary: not selected") { editSpeakerDialog(2) }
        actionRow(config, primaryButton, secondaryButton)

        channelButton = action("Output: stereo") {
            mutateConfig { it.copy(channelMode = if (it.channelMode == ChannelMode.STEREO) ChannelMode.SPLIT else ChannelMode.STEREO) }
        }
        leftButton = action("Left channel: primary") {
            mutateConfig {
                it.copy(leftSpeaker = if (it.leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) LeftSpeakerAssignment.SPEAKER2_LEFT else LeftSpeakerAssignment.SPEAKER1_LEFT)
            }
        }
        actionRow(config, channelButton, leftButton)

        offsetModeButton = action("Volume follow: fixed difference") {
            mutateConfig { it.copy(offsetMode = if (it.offsetMode == VolumeOffsetMode.FIXED) VolumeOffsetMode.RATIO else VolumeOffsetMode.FIXED) }
        }
        actionRow(config, offsetModeButton)
        offsetLabel = text("Secondary volume adjustment: -10%", 19f, white)
        config.addView(offsetLabel)
        actionRow(config,
            action("Offset -5") { mutateConfig { it.copy(offsetValue = (it.offsetValue - 5f).coerceIn(-100f, 100f)) } },
            action("Offset +5") { mutateConfig { it.copy(offsetValue = (it.offsetValue + 5f).coerceIn(-100f, 100f)) } }
        )
        defaultsLabel = text("Default volume on dual connect: 50%", 19f, white)
        config.addView(defaultsLabel)
        actionRow(config,
            action("Default -5%") { mutateConfig { it.copy(defaultVolume = (it.defaultVolume - 5f).coerceIn(0f, 100f)) } },
            action("Default +5%") { mutateConfig { it.copy(defaultVolume = (it.defaultVolume + 5f).coerceIn(0f, 100f)) } }
        )

        val network = section(body, "Audio and network stability")
        network.addView(text("Prefer SAFE (2s) or STABLE (1.5s) if HomePod audio skips.", 17f, soft))
        val latencyChoices = LatencyMode.entries.map { mode ->
            val button = action(mode.name) { CastController.setLatencyMode(mode) }
            latencyButtons[mode] = button
            button
        }
        actionRow(network, *latencyChoices.toTypedArray())
        robustButton = action("Robust network mode: OFF") {
            CastController.setRobustMode(!CastController.state.value.robustMode)
        }
        alacButton = action("ALAC compression: ON") {
            CastController.setAlac(!CastController.state.value.alacOn)
        }
        actionRow(network, robustButton, alacButton)
        hwVolumeButton = action("Remote/physical volume follow: ON") {
            mutateConfig { it.copy(hardwareVolControl = !it.hardwareVolControl) }
        }
        notificationButton = action("Persistent controls: ON") {
            mutateConfig { it.copy(showPersistentNotification = !it.showPersistentNotification) }
        }
        actionRow(network, hwVolumeButton, notificationButton)
        network.addView(text("TV remote volume buttons may be intercepted by CEC/system. Use the Volume +/- buttons above in that case. The original mobile notification and quick-settings tile remain in the app but TV launchers do not expose them consistently.", 15f, soft))

        val logging = section(body, "Connection diagnostics")
        actionRow(logging, action("Clear on-screen logs") { CastController.clearLogs() })
        logsLabel = text("No log messages", 16f, soft)
        logging.addView(logsLabel)

        body.addView(text("Limits: protected/DRM app audio and HDMI input cannot be captured by a normal Android TV app. If the system ends the media-projection permission, reconnecting requires you to approve capture again.", 15f, soft))
        dualButton.requestFocus()
        refresh(CastController.state.value)
    }

    private fun refresh(state: CastController.UiState) {
        if (!::statusLabel.isInitialized) return
        statusLabel.text = when {
            state.streaming -> "Streaming  |  ${state.status}"
            state.connecting -> "Connecting  |  ${state.status}"
            else -> "Disconnected  |  ${state.status}"
        }
        volumeLabel.text = "HomePod volume: ${state.volume.toInt()}%"
        dualButton.isEnabled = state.dualConfig.isConfigured && !state.connecting && !state.streaming
        stopButton.isEnabled = state.connecting || state.streaming
        testToneButton.text = if (state.testToneOn) "Stop test tone" else "440 Hz test tone"
        robustButton.text = "Robust network mode: ${if (state.robustMode) "ON" else "OFF"}"
        alacButton.text = "ALAC compression: ${if (state.alacOn) "ON" else "OFF"}"
        channelButton.text = "Output: ${if (state.dualConfig.channelMode == ChannelMode.SPLIT) "split L / R" else "full stereo"}"
        leftButton.text = "Left channel: ${if (state.dualConfig.leftSpeaker == LeftSpeakerAssignment.SPEAKER1_LEFT) "primary" else "secondary"}"
        offsetModeButton.text = "Volume follow: ${if (state.dualConfig.offsetMode == VolumeOffsetMode.FIXED) "fixed difference" else "relative ratio"}"
        offsetLabel.text = "Secondary volume adjustment: ${state.dualConfig.offsetValue.toInt()}%"
        defaultsLabel.text = "Default volume on dual connect: ${state.dualConfig.defaultVolume.toInt()}%"
        hwVolumeButton.text = "Physical volume follow: ${if (state.dualConfig.hardwareVolControl) "ON" else "OFF"}"
        notificationButton.text = "Persistent controls: ${if (state.dualConfig.showPersistentNotification) "ON" else "OFF"}"
        primaryButton.text = "Primary: ${state.dualConfig.speaker1Name} (${state.dualConfig.speaker1Host.ifBlank { "not selected" }})"
        secondaryButton.text = "Secondary: ${state.dualConfig.speaker2Name} (${state.dualConfig.speaker2Host.ifBlank { "not selected" }})"
        for ((mode, button) in latencyButtons) {
            button.text = "${mode.name}${if (mode == state.latencyMode) " [ACTIVE]" else ""}"
        }
        logsLabel.text = state.logs.takeLast(12).joinToString("\n").ifBlank { "No log messages" }
        refreshDeviceList(state.devices)
    }

    private fun refreshDeviceList(devices: List<AirplayDevice>) {
        val signature = devices.joinToString("|") { "${it.name}#${it.host}#${it.port}#${it.type}" }
        if (signature == previousDeviceSignature) return
        previousDeviceSignature = signature
        val rememberedFocus = deviceList.findFocus()?.tag as? String
        deviceList.removeAllViews()
        if (devices.isEmpty()) {
            deviceList.addView(text("No speakers found yet. Try Refresh discovery or Add speaker by IP.", 17f, soft))
        }
        for (device in devices) {
            deviceList.addView(text("${device.name}   ${device.host}:${device.port}", 20f, white, true))
            val controls = ArrayList<Button>()
            val key = "${device.host}:${device.port}"
            controls += action("Connect") { beginCast(device, false) }.apply { tag = "$key:connect" }
            controls += action("Set primary") {
                mutateConfig { it.copy(speaker1Name = device.name, speaker1Host = device.host, speaker1Port = device.port) }
            }.apply { tag = "$key:primary" }
            controls += action("Set secondary") {
                mutateConfig { it.copy(speaker2Name = device.name, speaker2Host = device.host, speaker2Port = device.port) }
            }.apply { tag = "$key:secondary" }
            if (device.type == "_manual") {
                controls += action("Remove") { CastController.removeManual(device) }.apply { tag = "$key:remove" }
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
        val name = field("Speaker name", currentName)
        val host = field("IPv4 or hostname", currentHost)
        val port = field("Port", currentPort.toString(), true)
        AlertDialog.Builder(this)
            .setTitle(if (slot == null) "Add speaker" else "Edit ${if (slot == 1) "primary" else "secondary"} speaker")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val parsedHost = host.text.toString().trim()
                        val parsedPort = port.text.toString().toIntOrNull()
                        val parsedName = name.text.toString().trim().ifEmpty { "HomePod" }
                        if (parsedHost.isBlank() || parsedPort == null || parsedPort !in 1..65535) {
                            notice("Enter a valid host and a port between 1 and 65535.")
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
        CastController.addLog("TV UI: $message")
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
