package com.example.assistant.app.session

data class TurnLatencySnapshot(
    val turnStartedAt: Long,
    val sttFinalAt: Long?,
    val sttFinalizationDurationMs: Long?,
    val intentRouterStartedAt: Long?,
    val intentRouterFinishedAt: Long?,
    val selectedIntentRoute: String?,
    val localToolStartedAt: Long?,
    val localToolFinishedAt: Long?,
    val screenshotStartedAt: Long?,
    val screenshotFinishedAt: Long?,
    val screenReasonerStartedAt: Long?,
    val screenReasonerFinishedAt: Long?,
    val ttsStartedAt: Long?,
    val ttsFinishedAt: Long?,
    val turnFinishedAt: Long?
)

class SessionTelemetry(
    private val onDebugResult: (String) -> Unit
) {
    private fun duration(start: Long?, end: Long?): Long? = if (start != null && end != null) end - start else null

    fun emitTurnLatencySummary(
        trace: TurnLatencySnapshot,
        activeIntentRouter: String,
        preScreenFallbackRouterUsed: Boolean,
        preScreenFinalBranch: String,
        assistantEngineCalled: Boolean,
        observationProviderCalled: Boolean,
        screenshotObservationRequested: Boolean,
        targetHighlightEmitted: Boolean,
        routerPromptBuildMs: Long?,
        routerPromptCharCount: Int? = null,
        routerModelInitMs: Long?,
        routerConversationCreateMs: Long?,
        routerInferenceMs: Long?,
        routerGenerationConfigMaxOutputTokens: Int? = null,
        routerParseMs: Long?,
        routerValidationMs: Long?,
        routerRawOutputCharCount: Int? = null,
        routerUsedCachedEngine: Boolean?,
        routerUsedCachedConversation: Boolean?,
        routerModelInitializationStatus: String?,
        routerModelInitializationFailure: String?
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val finalTurnFinishedAt = trace.turnFinishedAt ?: now
        val intentRouterDuration = duration(trace.intentRouterStartedAt, trace.intentRouterFinishedAt)
        val localToolDuration = duration(trace.localToolStartedAt, trace.localToolFinishedAt)
        val screenshotDuration = duration(trace.screenshotStartedAt, trace.screenshotFinishedAt)
        val screenReasonerDuration = duration(trace.screenReasonerStartedAt, trace.screenReasonerFinishedAt)
        val timeToFirstSpokenFeedback = duration(trace.turnStartedAt, trace.ttsStartedAt)
        val turnTotal = finalTurnFinishedAt - trace.turnStartedAt

        onDebugResult(
            "turn_latency_summary " +
                "turn_started_at=${trace.turnStartedAt} " +
                "stt_final_at=${trace.sttFinalAt ?: "null"} " +
                "stt_finalization_duration_ms=${trace.sttFinalizationDurationMs ?: "null"} " +
                "pre_screen_router_started_at=${trace.intentRouterStartedAt ?: "null"} " +
                "pre_screen_router_finished_at=${trace.intentRouterFinishedAt ?: "null"} " +
                "pre_screen_router_duration_ms=${intentRouterDuration ?: "null"} " +
                "selected_pre_screen_route=${trace.selectedIntentRoute ?: "null"} " +
                "local_tool_started_at=${trace.localToolStartedAt ?: "null"} " +
                "local_tool_finished_at=${trace.localToolFinishedAt ?: "null"} " +
                "local_tool_duration_ms=${localToolDuration ?: "null"} " +
                "screenshot_started_at=${trace.screenshotStartedAt ?: "null"} " +
                "screenshot_finished_at=${trace.screenshotFinishedAt ?: "null"} " +
                "screenshot_duration_ms=${screenshotDuration ?: "null"} " +
                "screen_reasoner_started_at=${trace.screenReasonerStartedAt ?: "null"} " +
                "screen_reasoner_finished_at=${trace.screenReasonerFinishedAt ?: "null"} " +
                "screen_reasoner_duration_ms=${screenReasonerDuration ?: "null"} " +
                "tts_started_at=${trace.ttsStartedAt ?: "null"} " +
                "tts_finished_at=${trace.ttsFinishedAt ?: "null"} " +
                "time_to_first_spoken_feedback_ms=${timeToFirstSpokenFeedback ?: "null"} " +
                "turn_total_ms=$turnTotal"
        )

        onDebugResult(
            "text_only_router_latency " +
                "router_prompt_build_ms=${routerPromptBuildMs ?: "null"} " +
                "router_prompt_char_count=${routerPromptCharCount ?: "null"} " +
                "router_model_init_ms=${routerModelInitMs ?: "null"} " +
                "router_conversation_create_ms=${routerConversationCreateMs ?: "null"} " +
                "router_inference_ms=${routerInferenceMs ?: "null"} " +
                "router_generation_config_max_output_tokens=${routerGenerationConfigMaxOutputTokens ?: "null"} " +
                "router_parse_ms=${routerParseMs ?: "null"} " +
                "router_validation_ms=${routerValidationMs ?: "null"} " +
                "router_raw_output_char_count=${routerRawOutputCharCount ?: "null"} " +
                "router_used_cached_engine=${routerUsedCachedEngine ?: "null"} " +
                "router_used_cached_conversation=${routerUsedCachedConversation ?: "null"} " +
                "router_model_initialization_status=${routerModelInitializationStatus ?: "null"} " +
                "router_model_initialization_failure=${routerModelInitializationFailure ?: "null"}"
        )

        onDebugResult(
            "turn_routing_summary " +
                "activeIntentRouter=$activeIntentRouter " +
                "preScreenFallbackRouterUsed=$preScreenFallbackRouterUsed " +
                "pre_screen_final_branch=$preScreenFinalBranch " +
                "assistant_engine_called=$assistantEngineCalled " +
                "observation_provider_called=$observationProviderCalled " +
                "screenshot_observation_requested=$screenshotObservationRequested " +
                "target_highlight_emitted=$targetHighlightEmitted"
        )
    }

    fun emitFinalSpeechTrace(
        transcript: String?,
        preScreenFinalBranch: String,
        source: String,
        guardTriggered: Boolean,
        repeatDetected: Boolean,
        spokenText: String
    ) {
        onDebugResult(
            "FINAL_SPEECH_TRACE " +
                "transcript=${transcript ?: "null"} " +
                "pre_screen_final_branch=$preScreenFinalBranch " +
                "final_spoken_text_source=$source " +
                "last_mile_guard_triggered=$guardTriggered " +
                "answer_repeats_user_question=$repeatDetected " +
                "final_spoken_text=$spokenText"
        )
    }

    fun emitPerTurnRoutingSpokenTrace(
        transcript: String,
        preScreenRawOutput: String?,
        preScreenParsedRoute: IntentRoute?,
        preScreenFinalBranch: String,
        selectedIntentRoute: String?,
        assistantEngineCalled: Boolean,
        observationRequestMode: String?,
        observationStrategy: String?,
        screenMapPresent: Boolean,
        compactPromptUsed: Boolean,
        richPromptUsed: Boolean,
        compactReasonerRawOutput: String?,
        compactReasonerParsedOutput: String?,
        compactOutputDegradationReason: String?,
        toolRequestPresent: Boolean,
        toolExecuted: Boolean,
        toolName: String?,
        toolSpokenText: String?,
        reasonerSpokenText: String?,
        finalSpokenText: String,
        finalSpokenTextSource: String
    ) {
        onDebugResult(
            "per_turn_routing_spoken_trace " +
                "transcript=$transcript " +
                "pre_screen_raw_output=${preScreenRawOutput ?: "null"} " +
                "pre_screen_parsed_route=${preScreenParsedRoute ?: "null"} " +
                "pre_screen_final_branch=$preScreenFinalBranch " +
                "selected_pre_screen_route=${selectedIntentRoute ?: "null"} " +
                "assistant_engine_called=$assistantEngineCalled " +
                "observation_request_mode=${observationRequestMode ?: "null"} " +
                "observation_strategy=${observationStrategy ?: "null"} " +
                "screen_map_present=$screenMapPresent " +
                "compact_prompt_used=$compactPromptUsed " +
                "rich_prompt_used=$richPromptUsed " +
                "compact_reasoner_raw_output=${compactReasonerRawOutput ?: "null"} " +
                "compact_reasoner_parsed_output=${compactReasonerParsedOutput ?: "null"} " +
                "compact_output_degradation_reason=${compactOutputDegradationReason ?: "null"} " +
                "tool_request_present=$toolRequestPresent " +
                "tool_executed=$toolExecuted " +
                "tool_name=${toolName ?: "null"} " +
                "tool_spoken_text=${toolSpokenText ?: "null"} " +
                "reasoner_spoken_text=${reasonerSpokenText ?: "null"} " +
                "final_spoken_text=$finalSpokenText " +
                "final_spoken_text_source=$finalSpokenTextSource"
        )
    }
}
