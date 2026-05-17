package com.example.assistant.core.engine

import com.example.assistant.core.model.GuidanceTarget
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.TargetSource

internal data class CompactTargetResolution(
    val target: GuidanceTarget?,
    val compactTargetId: String?,
    val compactConfidence: Float?,
    val targetIdKnown: Boolean,
    val compactVisibleTextCount: Int,
    val compactInteractiveCount: Int,
    val compactOcrCount: Int,
    val compactPreview: String
)

internal object EngineDebugSupport {

    private const val TARGET_CONFIDENCE_THRESHOLD = 0.5f

    fun resolveCompactTarget(
        observation: ScreenObservation,
        reasoned: ReasonerOutput,
        clamp01: (Float?) -> Float?
    ): CompactTargetResolution {
        val compactRegistry = observation.screenMap?.idRegistry.orEmpty()
        val compactVisibleTextCount = observation.screenMap?.visibleText?.size ?: 0
        val compactInteractiveCount = observation.screenMap?.interactive?.size ?: 0
        val compactOcrCount = observation.screenMap?.ocr?.size ?: 0
        val compactPreview = buildList {
            addAll(observation.screenMap?.visibleText.orEmpty().map { "${it.id}:${it.label}" })
            addAll(observation.screenMap?.ocr.orEmpty().map { "${it.id}:${it.label}" })
        }.take(5).joinToString(" | ").ifBlank { "none" }

        val compactTargetId = reasoned.compactTargetId
        val compactConfidence = clamp01(reasoned.compactConfidence)
        val targetIdKnown = compactTargetId != null && compactRegistry.containsKey(compactTargetId)
        val compactEntry = if (targetIdKnown) compactRegistry[compactTargetId] else null
        val compactAllowed =
            compactTargetId != null && targetIdKnown && (compactConfidence ?: 0f) >= TARGET_CONFIDENCE_THRESHOLD

        val compactTarget = if (compactAllowed && compactEntry != null) {
            GuidanceTarget(
                source = compactEntry.source,
                nodeId = compactTargetId,
                bounds = compactEntry.bounds,
                normalizedBox = null,
                label = compactTargetId,
                targetConfidence = compactConfidence
            )
        } else {
            null
        }

        return CompactTargetResolution(
            target = compactTarget,
            compactTargetId = compactTargetId,
            compactConfidence = compactConfidence,
            targetIdKnown = targetIdKnown,
            compactVisibleTextCount = compactVisibleTextCount,
            compactInteractiveCount = compactInteractiveCount,
            compactOcrCount = compactOcrCount,
            compactPreview = compactPreview
        )
    }

    fun buildRationale(
        request: ObservationRequest,
        reasoned: ReasonerOutput,
        compact: CompactTargetResolution,
        fallbackTarget: GuidanceTarget?
    ): String = buildString {
        append("observation_mode=${request.mode}")
        append("; compact_target_id=${compact.compactTargetId ?: "null"}")
        append("; compact_target_id_known=${compact.targetIdKnown}")
        append("; compact_target_id_confidence=${compact.compactConfidence ?: 0f}")
        append("; compact_target_bounds_resolved=${compact.target != null || fallbackTarget?.bounds != null}")
        append("; compact_prompt_includes_interaction_language=${reasoned.compactPromptIncludesInteractionLanguage ?: false}")
        append("; compact_prompt_char_count=${reasoned.compactPromptCharCount ?: 0}")
        append("; compact_screen_map_visible_text_count=${compact.compactVisibleTextCount}")
        append("; compact_screen_map_interactive_count=${compact.compactInteractiveCount}")
        append("; compact_screen_map_ocr_count=${compact.compactOcrCount}")
        append("; compact_screen_map_preview=${compact.compactPreview}")
        append("; compact_reasoner_raw_output=${reasoned.compactRawOutput ?: "null"}")
        append("; compact_reasoner_parsed_output=${reasoned.compactParsedOutput ?: "null"}")
        append("; compact_output_degradation_reason=${reasoned.compactOutputDegradationReason ?: "null"}")
        if (!reasoned.rationale.isNullOrBlank()) {
            append("; ")
            append(reasoned.rationale)
        }
    }
}
