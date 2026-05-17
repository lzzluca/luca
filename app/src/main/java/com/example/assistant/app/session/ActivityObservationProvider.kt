package com.example.assistant.app.session

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.assistant.android.accessibility.service.LucaAccessibilityService
import com.example.assistant.android.capture.DebugScreenshotCapturer
import com.example.assistant.android.overlay.AssistantBubbleOverlayController
import com.example.assistant.core.engine.ObservationMode
import com.example.assistant.core.engine.ObservationProvider
import com.example.assistant.core.engine.ObservationRequest
import com.example.assistant.core.engine.ScreenMapBuilder
import com.example.assistant.core.model.OcrTextFragment
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.ScreenshotFrame
import com.example.assistant.core.model.UiNode
import com.example.assistant.core.model.UiTreeSnapshot
import java.io.ByteArrayOutputStream

class ActivityObservationProvider(
    private val activity: Activity,
    private val capturer: DebugScreenshotCapturer,
    private val screenMapBuilder: ScreenMapBuilder = ScreenMapBuilder()
) : ObservationProvider {

    private companion object {
        const val TAG = "ActivityObservationProvider"
    }

    override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
        val service = LucaAccessibilityService.getActiveInstance()
        val uiTree = service?.rootInActiveWindow?.let { mapUiTree(it) }
        val ocr = emptyList<OcrTextFragment>()

        val screenshot = when (request.mode) {
            ObservationMode.SCREENSHOT -> captureScreenshotFrame()

            ObservationMode.SCREEN_MAP_FIRST -> {
                if (uiTree == null || uiTree.flattened.isEmpty()) captureScreenshotFrame() else null
            }

            ObservationMode.NONE -> null
        }

        val screenMapBuildStartedAt = System.currentTimeMillis()
        val screenMapBuildResult = if (request.mode == ObservationMode.SCREEN_MAP_FIRST) {
            screenMapBuilder.build(
                packageName = activity.packageName,
                screenTitle = activity.title?.toString(),
                uiTree = uiTree,
                ocr = ocr
            )
        } else {
            null
        }
        val screenMapBuildMs = if (screenMapBuildResult != null) {
            System.currentTimeMillis() - screenMapBuildStartedAt
        } else {
            null
        }

        // TODO(m3-ocr): wire real ML Kit Text Recognition v2 extraction for full M3 OCR support.
        // Current M3 foundation keeps OCR scaffolded and uses an empty OCR list until extractor wiring is complete and tested.

        return ScreenObservation(
            packageName = activity.packageName,
            screenTitle = activity.title?.toString(),
            screenshot = screenshot,
            uiTree = uiTree,
            ocrText = ocr,
            screenMap = screenMapBuildResult?.map,
            screenMapBuildMs = screenMapBuildMs,
            compactScreenMapPromptCharCount = null,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun captureScreenshotFrame(): ScreenshotFrame? {
        val service = LucaAccessibilityService.getActiveInstance()
        AssistantBubbleOverlayController.hide()
        // The first considers coordinates including status bar and navigation bar, as the reasoner is expected to do the same. The second don't include status bar and navigation bar: for now we exclude it to keep things simpler
        val bitmap = service?.captureCurrentScreenBitmap() // ?: capturer.captureActivityWindow(activity)
        if (service != null) {
            AssistantBubbleOverlayController.show(service)
        }

        return bitmap?.toJpegFrame()
    }

    private fun Bitmap.toJpegFrame(): ScreenshotFrame {
        val sourceWidth = width
        val sourceHeight = height

        // Scale down large screenshots to avoid OOM and to reduce the amount of data sent to the reasoner, while keeping a good quality for recognition
        val maxDimension = 1280
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val ratio = width.toFloat() / height.toFloat()
            val newW = if (width >= height) maxDimension else (maxDimension * ratio).toInt()
            val newH = if (width >= height) (maxDimension / ratio).toInt() else maxDimension
            Bitmap.createScaledBitmap(this, newW, newH, true)
        } else this

        val bytes = ByteArrayOutputStream().use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }

        val frameWidth = width
        val frameHeight = height

        // Debug code to check out the generated screenshots during development
        // val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "debug_screenshot.png")
        // file.writeBytes(bytes)
        // Log.d("DEBUG_IMAGE", "Screenshot salvato in Download: ${file.absolutePath}")

        return ScreenshotFrame(
            width = frameWidth,
            height = frameHeight,
            mimeType = "image/jpeg",
            bytes = bytes,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }

    private fun mapUiTree(root: AccessibilityNodeInfo): UiTreeSnapshot {
        val flat = mutableListOf<UiNode>()
        var nextId = 1
        fun map(node: AccessibilityNodeInfo?): UiNode? {
            node ?: return null
            val id = "node_${nextId++}"
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val children = buildList {
                for (i in 0 until node.childCount) {
                    add(map(node.getChild(i)) ?: continue)
                }
            }
            val mapped = UiNode(
                id = id,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                packageName = node.packageName?.toString(),
                bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                focused = node.isFocused,
                selected = node.isSelected,
                checkable = node.isCheckable,
                checked = node.isChecked,
                children = children
            )
            flat += mapped
            return mapped
        }

        val mappedRoot = map(root)
        return UiTreeSnapshot(root = mappedRoot, flattened = flat)
    }
}
