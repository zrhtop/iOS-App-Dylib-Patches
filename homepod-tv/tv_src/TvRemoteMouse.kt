package com.homepodplus.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.hypot
import kotlin.math.max

/**
 * D-pad mouse for this application's own Window only.
 *
 * The overlay merely draws a pointer and never intercepts a touch. D-pad events
 * arrive from Activity.dispatchKeyEvent (or Dialog.setOnKeyListener), and OK
 * produces standard touch DOWN/UP events through Window.superDispatchTouchEvent.
 *
 * No accessibility service, SYSTEM_ALERT_WINDOW or privileged input injection.
 * System MediaProjection/permission dialogs remain under normal Android control.
 */
internal class TvRemoteMouse(
    private val window: Window,
    private val scroll: ScrollView? = null
) {
    private val root = window.decorView as FrameLayout
    private val density = root.resources.displayMetrics.density
    private val layer = PointerLayer(root.context)
    private val held = HashSet<Int>()
    private var active = false
    private var destroyed = false
    private var scheduled = false
    private var holdStartedAt = 0L
    private var lastFrameAt = 0L
    private var x = 0f
    private var y = 0f
    private var positioned = false

    private val animate = object : Runnable {
        override fun run() {
            scheduled = false
            if (!active || destroyed || held.isEmpty()) return

            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastFrameAt).coerceIn(1L, 45L) / 1000f
            lastFrameAt = now
            val duration = now - holdStartedAt
            val pxPerSecond = density * when {
                duration > 1000L -> 1150f
                duration > 450L -> 800f
                else -> 380f
            }
            val dx = ((if (KeyEvent.KEYCODE_DPAD_RIGHT in held) 1 else 0) -
                (if (KeyEvent.KEYCODE_DPAD_LEFT in held) 1 else 0)).toFloat()
            val dy = ((if (KeyEvent.KEYCODE_DPAD_DOWN in held) 1 else 0) -
                (if (KeyEvent.KEYCODE_DPAD_UP in held) 1 else 0)).toFloat()
            val normal = max(1f, hypot(dx, dy))
            val step = pxPerSecond * elapsed
            if (dx != 0f || dy != 0f) {
                move((dx / normal) * step, (dy / normal) * step)
                autoScroll(dy, step)
            }
            startFrame()
        }
    }

    fun attach() {
        if (destroyed || layer.parent != null) return
        layer.isClickable = false
        layer.isFocusable = false
        layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layer.post {
            if (!destroyed && !positioned) {
                // Begin at the current focused control where possible, rather
                // than making TV users traverse the whole display on first use.
                val focused = root.findFocus()
                val screenPoint = IntArray(2)
                val rootPoint = IntArray(2)
                focused?.getLocationOnScreen(screenPoint)
                root.getLocationOnScreen(rootPoint)
                x = if (focused != null && focused.width > 0) {
                    (screenPoint[0] - rootPoint[0] + focused.width / 2f)
                } else layer.width * .5f
                y = if (focused != null && focused.height > 0) {
                    (screenPoint[1] - rootPoint[1] + focused.height / 2f)
                } else layer.height * .45f
                positioned = true
                move(0f, 0f)
            }
        }
    }

    fun resume() {
        if (destroyed) return
        active = true
        layer.visibility = View.VISIBLE
    }

    fun pause() {
        active = false
        held.clear()
        layer.removeCallbacks(animate)
        scheduled = false
        layer.visibility = View.INVISIBLE
    }

    fun close() {
        if (destroyed) return
        pause()
        destroyed = true
        (layer.parent as? ViewGroup)?.removeView(layer)
    }

    /**
     * Consume only cursor keys. Other keys, especially Back, Volume, Home and
     * keyboard typing, continue through Android's normal dispatcher.
     */
    fun handle(event: KeyEvent): Boolean {
        if (!active || destroyed) return false
        val key = event.keyCode
        if (isArrow(key)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (held.add(key)) {
                        holdStartedAt = SystemClock.uptimeMillis()
                        lastFrameAt = holdStartedAt
                        val nudge = 18f * density
                        move(
                            when (key) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> -nudge
                                KeyEvent.KEYCODE_DPAD_RIGHT -> nudge
                                else -> 0f
                            },
                            when (key) {
                                KeyEvent.KEYCODE_DPAD_UP -> -nudge
                                KeyEvent.KEYCODE_DPAD_DOWN -> nudge
                                else -> 0f
                            }
                        )
                        // Immediate feedback for short presses, then smooth
                        // continuous movement for long-press/held buttons.
                        autoScroll(if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1f else
                            if (key == KeyEvent.KEYCODE_DPAD_UP) -1f else 0f, nudge)
                        startFrame()
                    }
                }
                KeyEvent.ACTION_UP -> held.remove(key)
            }
            return true
        }

        if (key == KeyEvent.KEYCODE_DPAD_CENTER ||
            key == KeyEvent.KEYCODE_ENTER ||
            key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            key == KeyEvent.KEYCODE_BUTTON_A
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                click()
            }
            return true
        }

        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP ||
            key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val distance = (scroll?.height ?: layer.height) * .65f
                scroll?.scrollBy(0, (if (key == KeyEvent.KEYCODE_CHANNEL_UP ||
                    key == KeyEvent.KEYCODE_PAGE_UP) -distance else distance).toInt())
            }
            return true
        }
        return false
    }

    private fun isArrow(key: Int) = key == KeyEvent.KEYCODE_DPAD_UP ||
        key == KeyEvent.KEYCODE_DPAD_DOWN ||
        key == KeyEvent.KEYCODE_DPAD_LEFT ||
        key == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun startFrame() {
        if (!scheduled && active && held.isNotEmpty() && !destroyed) {
            scheduled = true
            layer.postOnAnimation(animate)
        }
    }

    private fun move(dx: Float, dy: Float) {
        if (!positioned || layer.width <= 0 || layer.height <= 0) return
        val margin = 15f * density
        x = (x + dx).coerceIn(margin, max(margin, layer.width - margin))
        y = (y + dy).coerceIn(margin, max(margin, layer.height - margin))
        layer.cursorX = x
        layer.cursorY = y
        layer.invalidate()
    }

    private fun autoScroll(direction: Float, step: Float) {
        val s = scroll ?: return
        if (direction == 0f || layer.height <= 0) return
        val edgeTop = layer.height * .19f
        val edgeBottom = layer.height * .78f
        if ((direction < 0f && y < edgeTop) ||
            (direction > 0f && y > edgeBottom)
        ) {
            // Keep the cursor fixed near the viewport edge while content
            // follows it. ScrollView clamps automatically at content ends.
            s.scrollBy(0, (direction * max(12f * density, step * 1.4f)).toInt())
        }
    }

    private fun click() {
        if (!positioned || destroyed || !active) return
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(at, at + 65, MotionEvent.ACTION_UP, x, y, 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            window.superDispatchTouchEvent(down)
            window.superDispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        layer.flashUntil = SystemClock.uptimeMillis() + 160L
        layer.invalidate()
        layer.postDelayed({ if (!destroyed) layer.invalidate() }, 180L)
    }

    private class PointerLayer(context: android.content.Context) : View(context) {
        var cursorX = 0f
        var cursorY = 0f
        var flashUntil = 0L

        private val d = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 27, 42)
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * d
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 65, 175, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f * d
        }
        private val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(1f * d, 27f * d)
            lineTo(8f * d, 21f * d)
            lineTo(15f * d, 36f * d)
            lineTo(20f * d, 33f * d)
            lineTo(13f * d, 18f * d)
            lineTo(23f * d, 17f * d)
            close()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.translate(cursorX, cursorY)
            if (SystemClock.uptimeMillis() < flashUntil) {
                canvas.drawCircle(0f, 0f, 24f * d, halo)
            }
            canvas.drawPath(arrow, stroke)
            canvas.drawPath(arrow, fill)
            canvas.restore()
        }

        // This overlay is visual only; it cannot steal synthetic clicks.
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }
}
