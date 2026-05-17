package com.example.assistant.core.engine

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.assistant.core.model.GuidanceResult
import com.example.assistant.core.model.GuidanceTarget
import com.example.assistant.core.model.NormalizedBox
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.TargetSource
import com.example.assistant.core.model.ToolRequest
import com.example.assistant.core.model.UserQuestion

class DefaultAssistantEngine(
    private val context: Context,
    private val planner: ObservationPlanner,
    private val observationProvider: ObservationProvider,
    private val reasoner: Reasoner
) : AssistantEngine {

    private companion object {
        const val TAG = "DefaultAssistantEngine"
        const val MAX_TARGET_AREA_RATIO = 0.65f
    }

    override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
        val request = planner.plan(question, latestUiTree = null)
        return answerQuestion(question, request)
    }

    override suspend fun answerQuestion(
        question: UserQuestion,
        observationRequest: ObservationRequest
    ): GuidanceResult {
        val request = observationRequest
        val observation = if (request.mode == ObservationMode.NONE) {
            ScreenObservation(
                packageName = null,
                screenTitle = null,
                screenshot = null,
                uiTree = null,
                capturedAtMs = System.currentTimeMillis()
            )
        } else {
            observationProvider.getCurrentObservation(request)
        }
        val reasoned = reasoner.reason(
            ReasonerInput(
                question = question.text,
                observation = observation,
                instructions = buildReasonerInstructions(question.interactionLanguage),
                interactionLanguage = question.interactionLanguage,
                conversationHistory = question.conversationHistory
            )
        )

        // These metrics include status bar and navigation bar in the rect bounds, as we expect the reasoner to do
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val realWidth = metrics.widthPixels
        val realHeight = metrics.heightPixels

        val resolvedBounds = resolveBounds(
            direct = reasoned.targetBounds,
            normalized = reasoned.targetNormalizedBox,
            sourceWidth = realWidth,
            sourceHeight = realHeight
        )

        val target = if (resolvedBounds != null || reasoned.targetNodeId != null || reasoned.targetNormalizedBox != null) {
            GuidanceTarget(
                source = if (reasoned.targetNodeId != null) TargetSource.UI_TREE else TargetSource.SCREENSHOT,
                nodeId = reasoned.targetNodeId,
                bounds = resolvedBounds,
                normalizedBox = reasoned.targetNormalizedBox,
                label = reasoned.targetLabel,
                targetConfidence = clamp01(reasoned.targetConfidence)
            )
        } else {
            null
        }

        val compact = EngineDebugSupport.resolveCompactTarget(
            observation = observation,
            reasoned = reasoned,
            clamp01 = ::clamp01
        )

        return GuidanceResult(
            summary = reasoned.summary,
            spokenText = reasoned.spokenText,
            rationale = EngineDebugSupport.buildRationale(
                request = request,
                reasoned = reasoned,
                compact = compact,
                fallbackTarget = target
            ),
            target = compact.target ?: target,
            toolRequest = validateToolRequest(reasoned.toolRequest),
            answerConfidence = clamp01(reasoned.answerConfidence),
            visualConfidence = clamp01(reasoned.visualConfidence)
        )
    }

    private fun buildReasonerInstructions(interactionLanguage: String?): String {
        val normalizedLanguage = when (interactionLanguage?.trim()?.lowercase()) {
            "it", "it-it" -> "it-IT"
            "en", "en-us" -> "en-US"
            else -> "en-US"
        }
        return "Answer spoken_text in interaction_language=$normalizedLanguage."
    }

    private fun validateToolRequest(request: ToolRequest?): ToolRequest? {
        request ?: return null
        if (request.name.isBlank()) return null
        return request
    }

    private fun resolveBounds(
        direct: RectBounds?,
        normalized: NormalizedBox?,
        sourceWidth: Int?,
        sourceHeight: Int?
    ): RectBounds? {
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) return null

        if (direct != null && direct.right > direct.left && direct.bottom > direct.top) {
            return direct
        }

        if (normalized == null) return null

        val left = (normalized.left / 1000f * sourceWidth).toInt().coerceIn(0, sourceWidth)
        val top = (normalized.top / 1000f * sourceHeight).toInt().coerceIn(0, sourceHeight)
        val right = (normalized.right / 1000f * sourceWidth).toInt().coerceIn(0, sourceWidth)
        val bottom = (normalized.bottom / 1000f * sourceHeight).toInt().coerceIn(0, sourceHeight)

        // The box must have positive dimensions
        if (right <= left || bottom <= top) return null

        val mapped = RectBounds(left, top, right, bottom)

        if (!isPlausibleTargetBounds(mapped, sourceWidth, sourceHeight)) {
            Log.w(TAG, "resolveBounds dropped target as implausible: $mapped")
            return null
        }

        return mapped
    }

    private fun isPlausibleTargetBounds(
        rect: RectBounds,
        sourceWidth: Int?,
        sourceHeight: Int?
    ): Boolean {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return false

        val refWidth = sourceWidth ?: rect.right
        val refHeight = sourceHeight ?: rect.bottom
        if (refWidth <= 0 || refHeight <= 0) return true

        val coversAlmostEntireScreen =
            rect.left <= 0 && rect.top <= 0 &&
                rect.right >= (refWidth * 0.98f).toInt() &&
                rect.bottom >= (refHeight * 0.98f).toInt()
        if (coversAlmostEntireScreen) return false

        val areaRatio = (width.toFloat() * height.toFloat()) / (refWidth.toFloat() * refHeight.toFloat())
        if (areaRatio > MAX_TARGET_AREA_RATIO) {
            Log.w(TAG, "Rejecting oversized target area ratio=$areaRatio rect=$rect ref=${refWidth}x${refHeight}")
            return false
        }

        return true
    }

    private fun mapFrameRectToSourceRect(
        rect: RectBounds,
        frameWidth: Int?,
        frameHeight: Int?,
        sourceWidth: Int?,
        sourceHeight: Int?
    ): RectBounds {
        if (frameWidth == null || frameHeight == null || sourceWidth == null || sourceHeight == null) {
            return rect
        }
        if (frameWidth <= 0 || frameHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return rect
        }

        if (frameWidth == sourceWidth && frameHeight == sourceHeight) {
            return rect
        }

        val mappedLeft = (rect.left.toFloat() / frameWidth * sourceWidth).toInt().coerceIn(0, sourceWidth)
        val mappedTop = (rect.top.toFloat() / frameHeight * sourceHeight).toInt().coerceIn(0, sourceHeight)
        val mappedRight = (rect.right.toFloat() / frameWidth * sourceWidth).toInt().coerceIn(0, sourceWidth)
        val mappedBottom = (rect.bottom.toFloat() / frameHeight * sourceHeight).toInt().coerceIn(0, sourceHeight)

        if (mappedRight <= mappedLeft || mappedBottom <= mappedTop) {
            return rect
        }

        return RectBounds(mappedLeft, mappedTop, mappedRight, mappedBottom)
    }

    private fun clamp01(v: Float?): Float? {
        v ?: return null
        return v.coerceIn(0f, 1f)
    }
}
