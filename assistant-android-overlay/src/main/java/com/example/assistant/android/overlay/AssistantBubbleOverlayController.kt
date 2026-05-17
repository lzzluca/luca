package com.example.assistant.android.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

object AssistantBubbleOverlayController {

    enum class BubblePresetPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }

    private var bubbleView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun isShown(): Boolean = bubbleView != null

    fun toggle(service: AccessibilityService) {
        if (isShown()) hide() else show(service)
    }

    fun show(service: AccessibilityService) {
        if (bubbleView != null) return

        val wm = service.getSystemService(WindowManager::class.java)
        val size = (64 * service.resources.displayMetrics.density).toInt()
        val store = BubblePositionStore(service)
        val saved = store.load()

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: ((service.resources.displayMetrics.widthPixels - size) / 2)
            y = saved?.second ?: (service.resources.displayMetrics.heightPixels - size - 120)
        }

        val bubble = TextView(service).apply {
            text = "L"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F51B5"))
            }
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = layoutParams ?: return false
                return when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = lp.x
                        startY = lp.y
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val nx = (startX + (event.rawX - downRawX)).toInt()
                        val ny = (startY + (event.rawY - downRawY)).toInt()
                        val maxX = max(0, service.resources.displayMetrics.widthPixels - v.width)
                        val maxY = max(0, service.resources.displayMetrics.heightPixels - v.height)
                        lp.x = min(max(0, nx), maxX)
                        lp.y = min(max(0, ny), maxY)
                        windowManager?.updateViewLayout(v, lp)
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        store.save(lp.x.toFloat(), lp.y.toFloat())
                        true
                    }

                    else -> false
                }
            }
        })

        wm.addView(bubble, params)
        bubbleView = bubble
        windowManager = wm
        layoutParams = params
    }

    fun hide() {
        val view = bubbleView ?: return
        windowManager?.removeView(view)
        bubbleView = null
        windowManager = null
        layoutParams = null
    }

    fun moveToPreset(service: AccessibilityService, preset: BubblePresetPosition) {
        val view = bubbleView ?: return
        val lp = layoutParams ?: return
        val metrics = service.resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - view.width)
        val maxY = max(0, metrics.heightPixels - view.height)

        val margin = (16 * metrics.density).toInt()
        val targetX = when (preset) {
            BubblePresetPosition.TOP_LEFT,
            BubblePresetPosition.BOTTOM_LEFT -> margin
            BubblePresetPosition.TOP_RIGHT,
            BubblePresetPosition.BOTTOM_RIGHT -> max(0, maxX - margin)
            BubblePresetPosition.CENTER -> maxX / 2
        }
        val targetY = when (preset) {
            BubblePresetPosition.TOP_LEFT,
            BubblePresetPosition.TOP_RIGHT -> margin
            BubblePresetPosition.BOTTOM_LEFT,
            BubblePresetPosition.BOTTOM_RIGHT -> max(0, maxY - margin)
            BubblePresetPosition.CENTER -> maxY / 2
        }

        lp.x = min(max(0, targetX), maxX)
        lp.y = min(max(0, targetY), maxY)
        windowManager?.updateViewLayout(view, lp)
        BubblePositionStore(service).save(lp.x.toFloat(), lp.y.toFloat())
    }
}
