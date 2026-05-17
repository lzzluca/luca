package com.example.assistant.android.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.view.KeyEvent
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.assistant.android.accessibility.model.CurrentAppInfo
import com.example.assistant.android.accessibility.model.UiTreeSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

class LucaAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "luca_prefs"
        private const val KEY_TRIGGER_BUTTON = "trigger_button"
        private const val TRIGGER_BUTTON_VOLUME_UP = "volume_up"
        private const val TRIGGER_BUTTON_VOLUME_DOWN = "volume_down"

        @Volatile
        private var activeInstance: LucaAccessibilityService? = null

        private val _latestUiTree = MutableStateFlow<UiTreeSnapshot?>(null)
        private val _currentAppInfo = MutableStateFlow<CurrentAppInfo?>(null)
        private val _lastTriggerLongPressMs = MutableStateFlow<Long?>(null)

        val latestUiTree: StateFlow<UiTreeSnapshot?> = _latestUiTree.asStateFlow()
        val currentAppInfo: StateFlow<CurrentAppInfo?> = _currentAppInfo.asStateFlow()
        val lastTriggerLongPressMs: StateFlow<Long?> = _lastTriggerLongPressMs.asStateFlow()

        fun getActiveInstance(): LucaAccessibilityService? = activeInstance
    }

    private var triggerDownStartedAtMs: Long? = null
    private var consumedLongPress: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        _currentAppInfo.value = CurrentAppInfo(
            packageName = event.packageName?.toString(),
            className = event.className?.toString()
        )

        _latestUiTree.value = UiTreeSnapshot(
            available = rootInActiveWindow != null,
            rootClassName = rootInActiveWindow?.className?.toString()
        )
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        val selectedTrigger = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_TRIGGER_BUTTON, TRIGGER_BUTTON_VOLUME_UP)
            ?: TRIGGER_BUTTON_VOLUME_UP
        val expectedKeyCode = if (selectedTrigger == TRIGGER_BUTTON_VOLUME_DOWN) {
            KeyEvent.KEYCODE_VOLUME_DOWN
        } else {
            KeyEvent.KEYCODE_VOLUME_UP
        }
        if (event.keyCode != expectedKeyCode) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (triggerDownStartedAtMs == null) {
                    triggerDownStartedAtMs = System.currentTimeMillis()
                    consumedLongPress = false
                }
                false
            }

            KeyEvent.ACTION_UP -> {
                val downAt = triggerDownStartedAtMs
                triggerDownStartedAtMs = null
                if (downAt != null && (System.currentTimeMillis() - downAt) >= 700) {
                    consumedLongPress = true
                    _lastTriggerLongPressMs.value = System.currentTimeMillis()
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    override fun onInterrupt() {
        // No-op for M1 base service behavior.
    }

    suspend fun captureCurrentScreenBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()
                                val copiedBitmap = wrappedBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                wrappedBitmap?.recycle()
                                if (cont.isActive) cont.resume(copiedBitmap)
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
