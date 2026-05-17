package com.example.assistant.android.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.assistant.core.model.RectBounds

// Utility class to show a highlighted box or dot on the screen, used for debugging and visualization of the reasoner's targets.
// 
class FocusHighlighter {

    private var dotView: View? = null
    private var windowManager: WindowManager? = null
    private var animator: ValueAnimator? = null

    fun showBox(service: AccessibilityService, bounds: RectBounds) {
        clear()

        val wm = service.getSystemService(WindowManager::class.java)

        val boxView = View(service).apply {
            // Disegniamo un rettangolo con bordo rosso e interno semitrasparente
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(5, Color.RED) // Bordo rosso
                setColor(Color.parseColor("#33FF0000")) // Rosso al 20% di opacità
                cornerRadius = 10f
            }
        }

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        wm.addView(boxView, params)
        dotView = boxView
        windowManager = wm
    }

    fun showDot(service: AccessibilityService, centerX: Int, centerY: Int, sizeDp: Float = 18f) {
        clear()

        val wm = service.getSystemService(WindowManager::class.java)
        val sizePx = (sizeDp * service.resources.displayMetrics.density).toInt()

        val dot = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3D00"))
            }
            alpha = 1f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX - (sizePx / 2)
            y = centerY - (sizePx / 2)
            
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        wm.addView(dot, params)
        dotView = dot
        windowManager = wm

        animator = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    fun clear() {
        animator?.cancel()
        animator = null
        dotView?.let { windowManager?.removeView(it) }
        dotView = null
        windowManager = null
    }
}
