package com.example.assistant.app.session

import android.os.SystemClock
import android.util.Log
import com.example.assistant.android.speech.SpeechOutput
import com.example.assistant.core.engine.AssistantEngine
import com.example.assistant.core.engine.ObservationMode
import com.example.assistant.core.engine.ObservationRequest
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.ConversationRole
import com.example.assistant.core.model.ConversationTurn
import com.example.assistant.core.model.ToolRequest
import com.example.assistant.core.model.UserQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultAssistantSessionController(
    private val assistantEngine: AssistantEngine,
    private val speechOutput: SpeechOutput,
    private val intentRouter: IntentRouter = DefaultScreenContextIntentRouter(),
    private val intentRouterConfig: IntentRouterConfig = IntentRouterConfig(),
    private val assistantSettingsProvider: () -> AssistantSettingsSnapshot? = { null },
    private val onStartListening: () -> Unit,
    private val onStopListening: () -> Unit,
    private val onShowTarget: (com.example.assistant.core.model.RectBounds?) -> Unit,
    private val onClearTarget: () -> Unit,
    private val onDebugResult: (String) -> Unit = {},
    private val onDebugError: (String) -> Unit = {},
    private val onDebugTool: (String) -> Unit = {},
    private val toolExecutor: ToolExecutor? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) : AssistantSessionController {

    private companion object {
        const val TAG = "DefaultAssistantSessionController"
        private val SPOKEN_PLACEHOLDER_TOKENS = setOf("short answer", "...", "fatto", "done", "ok", "okay")
        private const val MAX_CONVERSATION_TURNS = 12
    }

    private val _state = MutableStateFlow<AssistantUiState>(AssistantUiState.Dismissed)
    override val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var isInFollowupWindow: Boolean = false
    private val conversationTurns = mutableListOf<ConversationTurn>()
    private val telemetry = SessionTelemetry(onDebugResult)

    private data class TurnLatencyTrace(
        var turnStartedAt: Long = 0L,
        var sttFinalAt: Long? = null,
        var sttFinalizationDurationMs: Long? = null,
        var intentRouterStartedAt: Long? = null,
        var intentRouterFinishedAt: Long? = null,
        var selectedIntentRoute: String? = null,
        var localToolStartedAt: Long? = null,
        var localToolFinishedAt: Long? = null,
        var screenshotStartedAt: Long? = null,
        var screenshotFinishedAt: Long? = null,
        var screenReasonerStartedAt: Long? = null,
        var screenReasonerFinishedAt: Long? = null,
        var ttsStartedAt: Long? = null,
        var ttsFinishedAt: Long? = null,
        var turnFinishedAt: Long? = null
    ) {
        fun markTtsStartedIfNeeded() {
            if (ttsStartedAt == null) ttsStartedAt = SystemClock.elapsedRealtime()
        }

        fun markTtsFinished() {
            ttsFinishedAt = SystemClock.elapsedRealtime()
        }

        fun finishNow() {
            turnFinishedAt = SystemClock.elapsedRealtime()
        }

        fun emit(
            telemetry: SessionTelemetry,
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
            telemetry.emitTurnLatencySummary(
                trace = TurnLatencySnapshot(
                    turnStartedAt = turnStartedAt,
                    sttFinalAt = sttFinalAt,
                    sttFinalizationDurationMs = sttFinalizationDurationMs,
                    intentRouterStartedAt = intentRouterStartedAt,
                    intentRouterFinishedAt = intentRouterFinishedAt,
                    selectedIntentRoute = selectedIntentRoute,
                    localToolStartedAt = localToolStartedAt,
                    localToolFinishedAt = localToolFinishedAt,
                    screenshotStartedAt = screenshotStartedAt,
                    screenshotFinishedAt = screenshotFinishedAt,
                    screenReasonerStartedAt = screenReasonerStartedAt,
                    screenReasonerFinishedAt = screenReasonerFinishedAt,
                    ttsStartedAt = ttsStartedAt,
                    ttsFinishedAt = ttsFinishedAt,
                    turnFinishedAt = turnFinishedAt
                ),
                activeIntentRouter = activeIntentRouter,
                preScreenFallbackRouterUsed = preScreenFallbackRouterUsed,
                preScreenFinalBranch = preScreenFinalBranch,
                assistantEngineCalled = assistantEngineCalled,
                observationProviderCalled = observationProviderCalled,
                screenshotObservationRequested = screenshotObservationRequested,
                targetHighlightEmitted = targetHighlightEmitted,
                routerPromptBuildMs = routerPromptBuildMs,
                routerPromptCharCount = routerPromptCharCount,
                routerModelInitMs = routerModelInitMs,
                routerConversationCreateMs = routerConversationCreateMs,
                routerInferenceMs = routerInferenceMs,
                routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                routerParseMs = routerParseMs,
                routerValidationMs = routerValidationMs,
                routerRawOutputCharCount = routerRawOutputCharCount,
                routerUsedCachedEngine = routerUsedCachedEngine,
                routerUsedCachedConversation = routerUsedCachedConversation,
                routerModelInitializationStatus = routerModelInitializationStatus,
                routerModelInitializationFailure = routerModelInitializationFailure
            )
        }
    }

    private fun activeInteractionLanguage(): String? = assistantSettingsProvider()?.interactionLanguage

    private fun activeSessionPhrases(): AssistantSessionPhrases =
        AssistantSessionPhrases.forLanguage(activeInteractionLanguage())

    private fun isPlaceholderSpokenText(value: String?): Boolean {
        val normalized = value
            ?.trim()
            ?.trim('.', '!', '?', ',', ';', ':', '"', '\'', '…')
            ?.lowercase()
            .orEmpty()
        return normalized.isBlank() || normalized in SPOKEN_PLACEHOLDER_TOKENS
    }

    private fun localizedScreenFallback(interactionLanguage: String?): String {
        return when (AssistantSessionPhrases.normalize(interactionLanguage)) {
            "it-IT" -> "Non sono riuscito a capire bene questa schermata."
            else -> "I couldn't understand this screen clearly."
        }
    }

    private fun localizedMessageSafetyNoTextFallback(interactionLanguage: String?): String {
        return when (AssistantSessionPhrases.normalize(interactionLanguage)) {
            "it-IT" -> "Non riesco a leggere o valutare chiaramente questo messaggio."
            else -> "I can't clearly read or evaluate this message."
        }
    }

    private data class FinalSpeechBoundaryDecision(
        val spokenText: String,
        val spokenSource: String,
        val guardTriggered: Boolean,
        val repeatDetected: Boolean
    )

    private fun applyFinalSpeechBoundaryGuard(
        transcript: String?,
        candidateSpokenText: String,
        candidateSource: String,
        interactionLanguage: String?
    ): FinalSpeechBoundaryDecision {
        val repeatDetected = transcript?.let { doesAnswerRepeatUserQuestion(candidateSpokenText, it) } == true
        if (!repeatDetected) {
            return FinalSpeechBoundaryDecision(
                spokenText = candidateSpokenText,
                spokenSource = candidateSource,
                guardTriggered = false,
                repeatDetected = false
            )
        }
        return FinalSpeechBoundaryDecision(
            spokenText = localizedMessageSafetyNoTextFallback(interactionLanguage),
            spokenSource = "last_mile_repeat_guard_fallback",
            guardTriggered = true,
            repeatDetected = true
        )
    }

    private fun emitFinalSpeechTrace(
        transcript: String?,
        preScreenFinalBranch: String,
        source: String,
        guardTriggered: Boolean,
        repeatDetected: Boolean,
        spokenText: String
    ) {
        telemetry.emitFinalSpeechTrace(
            transcript = transcript,
            preScreenFinalBranch = preScreenFinalBranch,
            source = source,
            guardTriggered = guardTriggered,
            repeatDetected = repeatDetected,
            spokenText = spokenText
        )
    }

    private fun normalizeForSimilarity(value: String): String =
        value
            .lowercase()
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun doesAnswerRepeatUserQuestion(answer: String, question: String): Boolean {
        val a = normalizeForSimilarity(answer)
        val q = normalizeForSimilarity(question)
        if (a.isBlank() || q.isBlank()) return false
        if (a == q) return true
        return a.contains(q) || q.contains(a)
    }

    private fun extractMessageSafetyCandidatesFromPreview(preview: String?): List<String> {
        val value = preview ?: return emptyList()
        if (value.equals("none", ignoreCase = true)) return emptyList()
        return value
            .split('|')
            .map { it.trim() }
            .map { it.substringAfter(':', missingDelimiterValue = it).trim() }
            .filter { it.isNotBlank() && it.length > 2 }
            .filterNot { isPlaceholderSpokenText(it) }
    }

    private data class ActivationTtsReapplyDiagnostics(
        val requestedTtsLocale: String,
        val reapplySucceeded: Boolean,
        val activeTtsLocaleAfterActivation: String?,
        val activeTtsVoiceAfterActivation: String?,
        val failureReason: String?
    )

    private fun normalizeInteractionLanguageTag(interactionLanguage: String?): String =
        AssistantSessionPhrases.normalize(interactionLanguage)

    private fun reapplyTtsLocaleOnActivation(interactionLanguage: String?): ActivationTtsReapplyDiagnostics {
        val requestedTtsLocale = normalizeInteractionLanguageTag(interactionLanguage)
        val result = speechOutput.setLanguage(requestedTtsLocale)
        return ActivationTtsReapplyDiagnostics(
            requestedTtsLocale = result.requestedTtsLocale,
            reapplySucceeded = result.success,
            activeTtsLocaleAfterActivation = result.activeTtsLocale,
            activeTtsVoiceAfterActivation = result.activeTtsVoice,
            failureReason = result.failureReason
        )
    }

    private fun debugSessionPhrase(phraseKey: String, phrase: String, interactionLanguage: String?) {
        onDebugResult(
            "session_phrase language=${AssistantSessionPhrases.normalize(interactionLanguage)} key=$phraseKey spoken=$phrase"
        )
    }

    private fun appendConversationTurn(role: ConversationRole, text: String, timestampMs: Long = System.currentTimeMillis()) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        conversationTurns += ConversationTurn(
            role = role,
            text = normalized,
            timestampMs = timestampMs
        )
        if (conversationTurns.size > MAX_CONVERSATION_TURNS) {
            val dropCount = conversationTurns.size - MAX_CONVERSATION_TURNS
            repeat(dropCount) { conversationTurns.removeAt(0) }
        }
    }

    private fun clearConversationHistory() {
        conversationTurns.clear()
    }

    override fun activate() {
        scope.launch {
            mutex.withLock {
                clearConversationHistory()
                val interactionLanguage = activeInteractionLanguage()
                val ttsReapply = reapplyTtsLocaleOnActivation(interactionLanguage)
                val phrases = AssistantSessionPhrases.forLanguage(interactionLanguage)

                onDebugResult("persistedInteractionLanguageOnActivation=$interactionLanguage")
                onDebugResult("ttsLocaleReappliedOnActivation=${ttsReapply.reapplySucceeded}")
                onDebugResult("requestedTtsLocaleOnActivation=${ttsReapply.requestedTtsLocale}")
                onDebugResult("activeTtsLocaleAfterActivation=${ttsReapply.activeTtsLocaleAfterActivation ?: "unknown"}")
                onDebugResult("activeTtsVoiceAfterActivation=${ttsReapply.activeTtsVoiceAfterActivation ?: "unknown"}")
                ttsReapply.failureReason?.let { onDebugResult("ttsReapplyFailureReason=$it") }

                _state.value = AssistantUiState.Greeting
                onClearTarget()
                onStopListening()
                debugSessionPhrase("greeting", phrases.greeting, interactionLanguage)
                val boundary = applyFinalSpeechBoundaryGuard(
                    transcript = null,
                    candidateSpokenText = phrases.greeting,
                    candidateSource = "session_phrase",
                    interactionLanguage = interactionLanguage
                )
                emitFinalSpeechTrace(
                    transcript = null,
                    preScreenFinalBranch = "activation",
                    source = boundary.spokenSource,
                    guardTriggered = boundary.guardTriggered,
                    repeatDetected = boundary.repeatDetected,
                    spokenText = boundary.spokenText
                )
                appendConversationTurn(ConversationRole.ASSISTANT, boundary.spokenText)
                speechOutput.speak(
                    text = boundary.spokenText,
                    interrupt = true,
                    onDone = {
                        _state.value = AssistantUiState.Listening
                        isInFollowupWindow = false
                        onStartListening()
                    },
                    onError = {
                        _state.value = AssistantUiState.Error("Speech output unavailable")
                        onDebugError("Speech output unavailable")
                    }
                )
            }
        }
    }

    override fun onSpeechRecognized(text: String) {
        scope.launch {
            mutex.withLock {
                val trace = TurnLatencyTrace(turnStartedAt = SystemClock.elapsedRealtime()).apply {
                    sttFinalAt = turnStartedAt
                }
                var preScreenFinalBranch = "unknown"
                var assistantEngineCalled = false
                var observationProviderCalled = false
                var screenshotObservationRequested = false
                var targetHighlightEmitted = false
                var routerPromptBuildMs: Long? = null
                var routerModelInitMs: Long? = null
                var routerConversationCreateMs: Long? = null
                var routerInferenceMs: Long? = null
                var routerParseMs: Long? = null
                var routerValidationMs: Long? = null
                var routerPromptCharCount: Int? = null
                var routerRawOutputCharCount: Int? = null
                var routerGenerationConfigMaxOutputTokens: Int? = null
                var routerUsedCachedEngine: Boolean? = null
                var routerUsedCachedConversation: Boolean? = null
                var routerModelInitializationStatus: String? = null
                var routerModelInitializationFailure: String? = null
                var observationStrategy: String? = null
                var progressCueSpoken = false
                onStopListening()
                _state.value = AssistantUiState.Processing
                speechOutput.stop()
                onDebugResult("stt_transcript=$text")
                appendConversationTurn(ConversationRole.USER, text)
                onDebugResult("assistant_engine_called=false")
                onDebugResult("observation_provider_called=false")
                onDebugResult("screenshot_observation_requested=false")
                onDebugResult("target_highlight_emitted=false")

                var intentRouterException: Throwable? = null
                val activeInteractionLanguage = activeInteractionLanguage()
                val phrases = AssistantSessionPhrases.forLanguage(activeInteractionLanguage)
                trace.intentRouterStartedAt = SystemClock.elapsedRealtime()
                val preScreenRouteRaw = try {
                    intentRouter.route(
                        IntentRouterInput(
                            transcript = text,
                            interactionLanguage = activeInteractionLanguage,
                            currentAssistantSettings = assistantSettingsProvider(),
                            previousAssistantSummary = null
                        )
                    )
                } catch (t: Throwable) {
                    intentRouterException = t
                    onDebugError("Pre-screen router failure: ${t.message ?: t::class.java.simpleName}")
                    IntentRoute.Clarification(phrases.clarificationFallback, confidence = 1f)
                }
                trace.intentRouterFinishedAt = SystemClock.elapsedRealtime()
                val validationStartedAt = SystemClock.elapsedRealtime()
                val preScreenRoute = IntentRouteValidator.validateOrClarify(preScreenRouteRaw, intentRouterConfig)
                val preScreenRouteForcedForUiQuestion =
                    preScreenRoute is IntentRoute.LocalTool && isUiNavigationQuestion(text)
                val effectiveIntentRoute =
                    if (preScreenRouteForcedForUiQuestion) {
                        onDebugResult("pre_screen_degradation_reason=forced_screen_context_ui_question")
                        IntentRoute.ScreenContext(
                            mode = ScreenContextMode.SCREEN_MAP_FIRST,
                            purpose = ScreenContextPurpose.UI_NAVIGATION,
                            confidence = 1f
                        )
                    } else {
                        preScreenRoute
                    }
                routerValidationMs = SystemClock.elapsedRealtime() - validationStartedAt
                val preScreenRouteChangedByValidator = effectiveIntentRoute != preScreenRouteRaw
                val routerRaw = (intentRouter as? IntentRouterDiagnostics)?.latestRawOutput
                val routerPrompt = (intentRouter as? IntentRouterDiagnostics)?.latestPrompt
                val routerParsed = (intentRouter as? IntentRouterDiagnostics)?.latestParsedRoute
                val routerDegradation = (intentRouter as? IntentRouterDiagnostics)?.latestDegradationReason
                val routerException = (intentRouter as? IntentRouterDiagnostics)?.latestException
                val routerModelDiagnostics = (intentRouter as? IntentRouterDiagnostics)?.latestModelDiagnostics
                routerPromptBuildMs = (intentRouter as? IntentRouterDiagnostics)?.latestPromptBuildMs
                routerModelInitMs = (intentRouter as? IntentRouterDiagnostics)?.latestModelInitMs
                routerConversationCreateMs = (intentRouter as? IntentRouterDiagnostics)?.latestConversationCreateMs
                routerInferenceMs = (intentRouter as? IntentRouterDiagnostics)?.latestInferenceMs
                routerParseMs = (intentRouter as? IntentRouterDiagnostics)?.latestParseMs
                routerPromptCharCount = (intentRouter as? IntentRouterDiagnostics)?.latestPromptCharCount
                routerRawOutputCharCount = (intentRouter as? IntentRouterDiagnostics)?.latestRawOutputCharCount
                routerGenerationConfigMaxOutputTokens = (intentRouter as? IntentRouterDiagnostics)?.latestGenerationConfigMaxOutputTokens
                routerUsedCachedEngine = (intentRouter as? IntentRouterDiagnostics)?.latestUsedCachedEngine
                routerUsedCachedConversation = (intentRouter as? IntentRouterDiagnostics)?.latestUsedCachedConversation
                routerModelInitializationStatus = (intentRouter as? IntentRouterDiagnostics)?.latestModelInitializationStatus
                routerModelInitializationFailure = (intentRouter as? IntentRouterDiagnostics)?.latestModelInitializationFailure
                onDebugResult("activeIntentRouter=${intentRouter::class.java.simpleName}")
                onDebugResult("preScreenFallbackRouterUsed=${intentRouter !is IntentRouterDiagnostics}")
                onDebugResult("pre_screen_router=${intentRouter::class.java.simpleName}")
                onDebugResult("pre_screen_fallback_router_used=${intentRouter !is IntentRouterDiagnostics}")
                if (routerPrompt != null) {
                    onDebugResult("latestPrompt=$routerPrompt")
                    onDebugResult("pre_screen_prompt=$routerPrompt")
                }
                if (routerRaw != null) {
                    onDebugResult("latestRawOutput=$routerRaw")
                    onDebugResult("pre_screen_raw=$routerRaw")
                }
                if (routerParsed != null) {
                    onDebugResult("latestParsedRoute=$routerParsed")
                    onDebugResult("pre_screen_parsed=$routerParsed")
                }
                if (routerDegradation != null) {
                    onDebugResult("latestDegradationReason=$routerDegradation")
                    onDebugResult("pre_screen_degradation_reason=$routerDegradation")
                }
                if (routerException != null) {
                    onDebugResult("pre_screen_exception=${routerException}")
                }
                if (routerModelDiagnostics != null) {
                    onDebugResult("pre_screen_model_diagnostics=${routerModelDiagnostics}")
                }
                if (intentRouterException != null) {
                    onDebugResult("pre_screen_exception=${intentRouterException}")
                }
                if (preScreenRouteChangedByValidator) {
                    onDebugResult("latestDegradationReason=validator_route_changed")
                    onDebugResult("pre_screen_degradation_reason=validator_route_changed")
                }
                onDebugResult("latestParsedRouteValidated=$effectiveIntentRoute")
                onDebugResult("pre_screen_route=$effectiveIntentRoute")
                trace.selectedIntentRoute = effectiveIntentRoute::class.java.simpleName

                var finalSpokenText = ""
                var finalSpokenTextSource = "fallback"
                var toolRequestPresent = false
                var toolExecuted = false
                var toolName: String? = null
                var toolSpokenText: String? = null
                var reasonerSpokenText: String? = null
                var observationRequestMode: String? = null
                var screenMapPresent = false
                var compactPromptUsed = false
                var richPromptUsed = false
                var compactReasonerRawOutput: String? = null
                var compactReasonerParsedOutput: String? = null
                var compactOutputDegradationReason: String? = null
                var compactPromptCharCount: String? = null
                var compactScreenMapVisibleTextCount: String? = null
                var compactScreenMapInteractiveCount: String? = null
                var compactScreenMapOcrCount: String? = null
                var compactScreenMapPreview: String? = null
                var messageSafetyTextAvailable: Boolean? = null
                var messageSafetyVisibleTextCandidates: List<String> = emptyList()
                var messageSafetyGuardTriggered = false
                var answerRepeatsUserQuestion = false

                when (effectiveIntentRoute) {
                    is IntentRoute.LocalTool -> {
                        preScreenFinalBranch = "local_tool"
                        onDebugResult("pre_screen_final_branch=local_tool")
                        val isAppTool = effectiveIntentRoute.toolName == AssistantTools.OPEN_PLAY_STORE_AND_SEARCH
                        toolName = effectiveIntentRoute.toolName
                        toolRequestPresent = true
                        if (
                            isAppTool
                        ) {
                            onDebugResult("selected_app_tool=${effectiveIntentRoute.toolName}")
                            onDebugResult("app_name=${effectiveIntentRoute.arguments["app_name"].orEmpty()}")
                        }
                        val toolRequest = ToolRequest(effectiveIntentRoute.toolName, effectiveIntentRoute.arguments)
                        trace.localToolStartedAt = SystemClock.elapsedRealtime()
                        val toolOutcome = executeToolIfAllowed(toolRequest)
                        trace.localToolFinishedAt = SystemClock.elapsedRealtime()
                        toolExecuted = true
                        if (
                            isAppTool
                        ) {
                            val msg = toolOutcome?.debugMessage.orEmpty()
                            parseDebugToken(msg, "installed_app_resolver_result")?.let {
                                onDebugResult("installed_app_resolver_result=$it")
                            }
                            parseDebugToken(msg, "final_app_route")?.let {
                                onDebugResult("final_app_route=$it")
                            }
                        }
                        val spokenCandidate = toolOutcome?.spokenTextOverride
                            ?: effectiveIntentRoute.spokenConfirmation
                            ?: phrases.done
                        val toolSpokenTextOverride = toolOutcome?.spokenTextOverride
                        toolSpokenText = toolSpokenTextOverride
                        finalSpokenText = spokenCandidate
                        finalSpokenTextSource = when {
                            toolOutcome?.spokenTextOverride != null -> "tool"
                            effectiveIntentRoute.spokenConfirmation != null -> "session_phrase"
                            else -> "fallback"
                        }
                        val sessionClosingPhrase = phrases.closing
                        onDebugResult("toolSpokenTextOverride=${toolSpokenTextOverride.orEmpty()}")
                        onDebugResult("finalSpokenText=$spokenCandidate")
                        onDebugResult("sessionClosingPhrase=$sessionClosingPhrase")
                        onDebugResult("sessionClosingPhraseAppended=false")
                        onShowTarget(null)
                        _state.value = AssistantUiState.Responding(
                            spokenText = finalSpokenText,
                            targetBounds = null
                        )
                        val finalBoundary = applyFinalSpeechBoundaryGuard(
                            transcript = text,
                            candidateSpokenText = finalSpokenText,
                            candidateSource = finalSpokenTextSource,
                            interactionLanguage = activeInteractionLanguage
                        )
                        finalSpokenText = finalBoundary.spokenText
                        finalSpokenTextSource = finalBoundary.spokenSource
                        emitFinalSpeechTrace(
                            transcript = text,
                            preScreenFinalBranch = preScreenFinalBranch,
                            source = finalSpokenTextSource,
                            guardTriggered = finalBoundary.guardTriggered,
                            repeatDetected = finalBoundary.repeatDetected,
                            spokenText = finalSpokenText
                        )
                        appendConversationTurn(ConversationRole.ASSISTANT, finalSpokenText)
                        trace.markTtsStartedIfNeeded()
                        speechOutput.speak(
                            text = finalSpokenText,
                            interrupt = true,
                            onDone = {
                                trace.markTtsFinished()
                                if (isAppTool) {
                                    _state.value = AssistantUiState.Dismissed
                                    isInFollowupWindow = false
                                    onClearTarget()
                                    return@speak
                                }
                                scope.launch localToolFollowupLaunch@{
                                    onStartListening()
                                    isInFollowupWindow = true
                                    _state.value = AssistantUiState.Listening
                                    delay(20_000)
                                    if (!isInFollowupWindow) return@localToolFollowupLaunch
                                    onStopListening()
                                    debugSessionPhrase("closing", phrases.closing, activeInteractionLanguage)
                                    onDebugResult("sessionClosingPhraseAppended=true")
                                    val closingBoundary = applyFinalSpeechBoundaryGuard(
                                        transcript = null,
                                        candidateSpokenText = phrases.closing,
                                        candidateSource = "session_phrase",
                                        interactionLanguage = activeInteractionLanguage
                                    )
                                    emitFinalSpeechTrace(
                                        transcript = null,
                                        preScreenFinalBranch = "session_followup_close",
                                        source = closingBoundary.spokenSource,
                                        guardTriggered = closingBoundary.guardTriggered,
                                        repeatDetected = closingBoundary.repeatDetected,
                                        spokenText = closingBoundary.spokenText
                                    )
                                    appendConversationTurn(ConversationRole.ASSISTANT, closingBoundary.spokenText)
                                    speechOutput.speak(
                                        text = closingBoundary.spokenText,
                                        interrupt = true,
                                        onDone = {
                                            _state.value = AssistantUiState.Dismissed
                                            isInFollowupWindow = false
                                            clearConversationHistory()
                                        }
                                    )
                                    onClearTarget()
                                }
                            },
                            onError = {
                                trace.markTtsFinished()
                                _state.value = AssistantUiState.Error("Speech output failed")
                                onDebugError("Speech output failed")
                            }
                        )
                        trace.finishNow()
                        trace.emit(
                            telemetry = telemetry,
                            activeIntentRouter = intentRouter::class.java.simpleName,
                            preScreenFallbackRouterUsed = intentRouter !is IntentRouterDiagnostics,
                            preScreenFinalBranch = preScreenFinalBranch,
                            assistantEngineCalled = assistantEngineCalled,
                            observationProviderCalled = observationProviderCalled,
                            screenshotObservationRequested = screenshotObservationRequested,
                            targetHighlightEmitted = targetHighlightEmitted,
                            routerPromptBuildMs = routerPromptBuildMs,
                            routerPromptCharCount = routerPromptCharCount,
                            routerModelInitMs = routerModelInitMs,
                            routerConversationCreateMs = routerConversationCreateMs,
                            routerInferenceMs = routerInferenceMs,
                            routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                            routerParseMs = routerParseMs,
                            routerValidationMs = routerValidationMs,
                            routerRawOutputCharCount = routerRawOutputCharCount,
                            routerUsedCachedEngine = routerUsedCachedEngine,
                            routerUsedCachedConversation = routerUsedCachedConversation,
                            routerModelInitializationStatus = routerModelInitializationStatus,
                            routerModelInitializationFailure = routerModelInitializationFailure
                        )
                        emitPerTurnRoutingSpokenTrace(
                            transcript = text,
                            preScreenRawOutput = routerRaw,
                            preScreenParsedRoute = routerParsed,
                            preScreenFinalBranch = preScreenFinalBranch,
                            selectedIntentRoute = trace.selectedIntentRoute,
                            assistantEngineCalled = assistantEngineCalled,
                            observationRequestMode = observationRequestMode,
                            observationStrategy = observationStrategy,
                            screenMapPresent = screenMapPresent,
                            compactPromptUsed = compactPromptUsed,
                            richPromptUsed = richPromptUsed,
                            compactReasonerRawOutput = compactReasonerRawOutput,
                            compactReasonerParsedOutput = compactReasonerParsedOutput,
                            compactOutputDegradationReason = compactOutputDegradationReason,
                            toolRequestPresent = toolRequestPresent,
                            toolExecuted = toolExecuted,
                            toolName = toolName,
                            toolSpokenText = toolSpokenText,
                            reasonerSpokenText = reasonerSpokenText,
                            finalSpokenText = finalSpokenText,
                            finalSpokenTextSource = finalSpokenTextSource
                        )
                        onDebugResult("pre_screen_local_tool_returned_before_engine=true")
                        return@withLock
                    }

                    is IntentRoute.GeneralAnswer -> {
                        preScreenFinalBranch = "general_answer"
                        onDebugResult("pre_screen_final_branch=general_answer")
                        onShowTarget(null)
                        _state.value = AssistantUiState.Responding(
                            spokenText = effectiveIntentRoute.spokenText,
                            targetBounds = null
                        )
                        val finalBoundary = applyFinalSpeechBoundaryGuard(
                            transcript = text,
                            candidateSpokenText = effectiveIntentRoute.spokenText,
                            candidateSource = "fallback",
                            interactionLanguage = activeInteractionLanguage
                        )
                        finalSpokenText = finalBoundary.spokenText
                        finalSpokenTextSource = finalBoundary.spokenSource
                        emitFinalSpeechTrace(
                            transcript = text,
                            preScreenFinalBranch = preScreenFinalBranch,
                            source = finalSpokenTextSource,
                            guardTriggered = finalBoundary.guardTriggered,
                            repeatDetected = finalBoundary.repeatDetected,
                            spokenText = finalSpokenText
                        )
                        appendConversationTurn(ConversationRole.ASSISTANT, finalSpokenText)
                        trace.markTtsStartedIfNeeded()
                        speechOutput.speak(
                            text = finalSpokenText,
                            interrupt = true
                        )
                        trace.finishNow()
                        trace.emit(
                            telemetry = telemetry,
                            activeIntentRouter = intentRouter::class.java.simpleName,
                            preScreenFallbackRouterUsed = intentRouter !is IntentRouterDiagnostics,
                            preScreenFinalBranch = preScreenFinalBranch,
                            assistantEngineCalled = assistantEngineCalled,
                            observationProviderCalled = observationProviderCalled,
                            screenshotObservationRequested = screenshotObservationRequested,
                            targetHighlightEmitted = targetHighlightEmitted,
                            routerPromptBuildMs = routerPromptBuildMs,
                            routerPromptCharCount = routerPromptCharCount,
                            routerModelInitMs = routerModelInitMs,
                            routerConversationCreateMs = routerConversationCreateMs,
                            routerInferenceMs = routerInferenceMs,
                            routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                            routerParseMs = routerParseMs,
                            routerValidationMs = routerValidationMs,
                            routerRawOutputCharCount = routerRawOutputCharCount,
                            routerUsedCachedEngine = routerUsedCachedEngine,
                            routerUsedCachedConversation = routerUsedCachedConversation,
                            routerModelInitializationStatus = routerModelInitializationStatus,
                            routerModelInitializationFailure = routerModelInitializationFailure
                        )
                        reasonerSpokenText = effectiveIntentRoute.spokenText
                        emitPerTurnRoutingSpokenTrace(
                            transcript = text,
                            preScreenRawOutput = routerRaw,
                            preScreenParsedRoute = routerParsed,
                            preScreenFinalBranch = preScreenFinalBranch,
                            selectedIntentRoute = trace.selectedIntentRoute,
                            assistantEngineCalled = assistantEngineCalled,
                            observationRequestMode = observationRequestMode,
                            observationStrategy = observationStrategy,
                            screenMapPresent = screenMapPresent,
                            compactPromptUsed = compactPromptUsed,
                            richPromptUsed = richPromptUsed,
                            compactReasonerRawOutput = compactReasonerRawOutput,
                            compactReasonerParsedOutput = compactReasonerParsedOutput,
                            compactOutputDegradationReason = compactOutputDegradationReason,
                            toolRequestPresent = toolRequestPresent,
                            toolExecuted = toolExecuted,
                            toolName = toolName,
                            toolSpokenText = toolSpokenText,
                            reasonerSpokenText = reasonerSpokenText,
                            finalSpokenText = finalSpokenText,
                            finalSpokenTextSource = finalSpokenTextSource
                        )
                        onDebugResult("pre_screen_general_answer_returned_before_engine=true")
                        return@withLock
                    }

                    is IntentRoute.Clarification -> {
                        preScreenFinalBranch = "clarification"
                        onDebugResult("pre_screen_final_branch=clarification")
                        onShowTarget(null)
                        _state.value = AssistantUiState.Responding(
                            spokenText = effectiveIntentRoute.spokenText,
                            targetBounds = null
                        )
                        val finalBoundary = applyFinalSpeechBoundaryGuard(
                            transcript = text,
                            candidateSpokenText = effectiveIntentRoute.spokenText,
                            candidateSource = "fallback",
                            interactionLanguage = activeInteractionLanguage
                        )
                        finalSpokenText = finalBoundary.spokenText
                        finalSpokenTextSource = finalBoundary.spokenSource
                        emitFinalSpeechTrace(
                            transcript = text,
                            preScreenFinalBranch = preScreenFinalBranch,
                            source = finalSpokenTextSource,
                            guardTriggered = finalBoundary.guardTriggered,
                            repeatDetected = finalBoundary.repeatDetected,
                            spokenText = finalSpokenText
                        )
                        appendConversationTurn(ConversationRole.ASSISTANT, finalSpokenText)
                        trace.markTtsStartedIfNeeded()
                        speechOutput.speak(
                            text = finalSpokenText,
                            interrupt = true,
                            onDone = {
                                trace.markTtsFinished()
                                _state.value = AssistantUiState.Listening
                                onStartListening()
                            }
                        )
                        trace.finishNow()
                        trace.emit(
                            telemetry = telemetry,
                            activeIntentRouter = intentRouter::class.java.simpleName,
                            preScreenFallbackRouterUsed = intentRouter !is IntentRouterDiagnostics,
                            preScreenFinalBranch = preScreenFinalBranch,
                            assistantEngineCalled = assistantEngineCalled,
                            observationProviderCalled = observationProviderCalled,
                            screenshotObservationRequested = screenshotObservationRequested,
                            targetHighlightEmitted = targetHighlightEmitted,
                            routerPromptBuildMs = routerPromptBuildMs,
                            routerPromptCharCount = routerPromptCharCount,
                            routerModelInitMs = routerModelInitMs,
                            routerConversationCreateMs = routerConversationCreateMs,
                            routerInferenceMs = routerInferenceMs,
                            routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                            routerParseMs = routerParseMs,
                            routerValidationMs = routerValidationMs,
                            routerRawOutputCharCount = routerRawOutputCharCount,
                            routerUsedCachedEngine = routerUsedCachedEngine,
                            routerUsedCachedConversation = routerUsedCachedConversation,
                            routerModelInitializationStatus = routerModelInitializationStatus,
                            routerModelInitializationFailure = routerModelInitializationFailure
                        )
                        reasonerSpokenText = effectiveIntentRoute.spokenText
                        emitPerTurnRoutingSpokenTrace(
                            transcript = text,
                            preScreenRawOutput = routerRaw,
                            preScreenParsedRoute = routerParsed,
                            preScreenFinalBranch = preScreenFinalBranch,
                            selectedIntentRoute = trace.selectedIntentRoute,
                            assistantEngineCalled = assistantEngineCalled,
                            observationRequestMode = observationRequestMode,
                            observationStrategy = observationStrategy,
                            screenMapPresent = screenMapPresent,
                            compactPromptUsed = compactPromptUsed,
                            richPromptUsed = richPromptUsed,
                            compactReasonerRawOutput = compactReasonerRawOutput,
                            compactReasonerParsedOutput = compactReasonerParsedOutput,
                            compactOutputDegradationReason = compactOutputDegradationReason,
                            toolRequestPresent = toolRequestPresent,
                            toolExecuted = toolExecuted,
                            toolName = toolName,
                            toolSpokenText = toolSpokenText,
                            reasonerSpokenText = reasonerSpokenText,
                            finalSpokenText = finalSpokenText,
                            finalSpokenTextSource = finalSpokenTextSource
                        )
                        onDebugResult("pre_screen_clarification_returned_before_engine=true")
                        return@withLock
                    }

                    is IntentRoute.ScreenContext -> {
                        preScreenFinalBranch = "screen_context"
                        observationStrategy = effectiveIntentRoute.mode.name.lowercase()
                        onDebugResult("pre_screen_final_branch=screen_context")
                        onDebugResult("pre_screen_screen_context mode=${effectiveIntentRoute.mode} purpose=${effectiveIntentRoute.purpose}")
                        debugSessionPhrase("processing_screen", phrases.processingScreen, activeInteractionLanguage)
                        progressCueSpoken = true
                        onDebugResult("progress_cue_spoken=true")
                        onDebugResult("observation_strategy=$observationStrategy")
                        trace.markTtsStartedIfNeeded()
                        val processingBoundary = applyFinalSpeechBoundaryGuard(
                            transcript = null,
                            candidateSpokenText = phrases.processingScreen,
                            candidateSource = "session_phrase",
                            interactionLanguage = activeInteractionLanguage
                        )
                        emitFinalSpeechTrace(
                            transcript = null,
                            preScreenFinalBranch = preScreenFinalBranch,
                            source = processingBoundary.spokenSource,
                            guardTriggered = processingBoundary.guardTriggered,
                            repeatDetected = processingBoundary.repeatDetected,
                            spokenText = processingBoundary.spokenText
                        )
                        speechOutput.speak(
                            text = processingBoundary.spokenText,
                            interrupt = true
                        )
                    }
                }

                assistantEngineCalled = true
                onDebugResult("assistant_engine_called=true")
                val screenObservationRequest = mapIntentRouteToObservationRequest(effectiveIntentRoute)
                observationRequestMode = screenObservationRequest.mode.name
                val result = try {
                    trace.screenshotStartedAt = SystemClock.elapsedRealtime()
                    trace.screenReasonerStartedAt = trace.screenshotStartedAt
                    assistantEngine.answerQuestion(
                        UserQuestion(
                            text = text,
                            timestampMs = System.currentTimeMillis(),
                            interactionLanguage = activeInteractionLanguage,
                            conversationHistory = conversationTurns.toList()
                        ),
                        screenObservationRequest
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "assistantEngine.answerQuestion failed", t)
                    onDebugError("Assistant engine failure: ${t.message ?: t::class.java.simpleName}")
                    _state.value = AssistantUiState.Error("Assistant is temporarily unavailable")
                    debugSessionPhrase("engine_unavailable", phrases.engineUnavailable, activeInteractionLanguage)
                    trace.markTtsStartedIfNeeded()
                    val unavailableBoundary = applyFinalSpeechBoundaryGuard(
                        transcript = text,
                        candidateSpokenText = phrases.engineUnavailable,
                        candidateSource = "session_phrase",
                        interactionLanguage = activeInteractionLanguage
                    )
                    emitFinalSpeechTrace(
                        transcript = text,
                        preScreenFinalBranch = preScreenFinalBranch,
                        source = unavailableBoundary.spokenSource,
                        guardTriggered = unavailableBoundary.guardTriggered,
                        repeatDetected = unavailableBoundary.repeatDetected,
                        spokenText = unavailableBoundary.spokenText
                    )
                    appendConversationTurn(ConversationRole.ASSISTANT, unavailableBoundary.spokenText)
                    speechOutput.speak(
                        text = unavailableBoundary.spokenText,
                        interrupt = true
                    )
                    trace.finishNow()
                    trace.emit(
                        telemetry = telemetry,
                        activeIntentRouter = intentRouter::class.java.simpleName,
                        preScreenFallbackRouterUsed = intentRouter !is IntentRouterDiagnostics,
                        preScreenFinalBranch = preScreenFinalBranch,
                        assistantEngineCalled = assistantEngineCalled,
                        observationProviderCalled = observationProviderCalled,
                        screenshotObservationRequested = screenshotObservationRequested,
                        targetHighlightEmitted = targetHighlightEmitted,
                        routerPromptBuildMs = routerPromptBuildMs,
                        routerPromptCharCount = routerPromptCharCount,
                        routerModelInitMs = routerModelInitMs,
                        routerConversationCreateMs = routerConversationCreateMs,
                        routerInferenceMs = routerInferenceMs,
                        routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                        routerParseMs = routerParseMs,
                        routerValidationMs = routerValidationMs,
                        routerRawOutputCharCount = routerRawOutputCharCount,
                        routerUsedCachedEngine = routerUsedCachedEngine,
                        routerUsedCachedConversation = routerUsedCachedConversation,
                        routerModelInitializationStatus = routerModelInitializationStatus,
                        routerModelInitializationFailure = routerModelInitializationFailure
                    )
                    return@withLock
                }
                trace.screenshotFinishedAt = SystemClock.elapsedRealtime()
                trace.screenReasonerFinishedAt = trace.screenshotFinishedAt

                var targetToShow = result.target?.takeIf { (it.targetConfidence ?: 0f) >= 0.5f }?.bounds
                val usedScreenshotObservation = result.rationale?.contains("observation_mode=SCREENSHOT", ignoreCase = true) == true
                screenMapPresent = result.rationale?.contains("screen_map_present=true", ignoreCase = true) == true
                compactPromptUsed = result.rationale?.contains("compact_prompt_includes_interaction_language=", ignoreCase = true) == true
                richPromptUsed = result.rationale?.contains("compact_reasoner_raw_output=", ignoreCase = true) != true
                compactReasonerRawOutput = parseRationaleToken(result.rationale, "compact_reasoner_raw_output")
                compactReasonerParsedOutput = parseRationaleToken(result.rationale, "compact_reasoner_parsed_output")
                compactOutputDegradationReason = parseRationaleToken(result.rationale, "compact_output_degradation_reason")
                compactPromptCharCount = parseRationaleToken(result.rationale, "compact_prompt_char_count")
                compactScreenMapVisibleTextCount = parseRationaleToken(result.rationale, "compact_screen_map_visible_text_count")
                compactScreenMapInteractiveCount = parseRationaleToken(result.rationale, "compact_screen_map_interactive_count")
                compactScreenMapOcrCount = parseRationaleToken(result.rationale, "compact_screen_map_ocr_count")
                compactScreenMapPreview = parseRationaleToken(result.rationale, "compact_screen_map_preview")
                val fallbackToScreenshotOrVision =
                    (observationStrategy == "screen_map_first") && usedScreenshotObservation
                observationProviderCalled = true
                screenshotObservationRequested = usedScreenshotObservation
                targetHighlightEmitted = targetToShow != null
                onDebugResult("observation_provider_called=true")
                onDebugResult("screenshot_observation_requested=$usedScreenshotObservation")
                onDebugResult("target_highlight_emitted=${targetToShow != null}")
                onDebugResult("fallback_to_screenshot_or_vision=$fallbackToScreenshotOrVision")
                Log.d(
                    TAG,
                    "targetSelected confidence=${result.target?.targetConfidence} bounds=${result.target?.bounds} normalized=${result.target?.normalizedBox} shown=$targetToShow"
                )
                val toolOutcome = executeScreenReasonerToolIfAllowed(result.toolRequest)
                toolRequestPresent = result.toolRequest != null
                toolExecuted = false
                toolName = result.toolRequest?.name
                toolSpokenText = toolOutcome?.spokenTextOverride
                val rawReasonerSpokenText = result.spokenText
                var effectiveReasonerSpokenText = rawReasonerSpokenText
                if (isPlaceholderSpokenText(rawReasonerSpokenText)) {
                    effectiveReasonerSpokenText = localizedScreenFallback(activeInteractionLanguage)
                    compactOutputDegradationReason = listOfNotNull(
                        compactOutputDegradationReason,
                        "placeholder_spoken_text_runtime"
                    ).joinToString(",")
                    targetToShow = null
                }

                reasonerSpokenText = effectiveReasonerSpokenText
                finalSpokenText = toolOutcome?.spokenTextOverride ?: effectiveReasonerSpokenText
                finalSpokenTextSource = if (toolOutcome?.spokenTextOverride != null) {
                    "tool"
                } else if (effectiveReasonerSpokenText != rawReasonerSpokenText) {
                    "screen_fallback_sanitized"
                } else {
                    "rich_reasoner"
                }

                if (isPlaceholderSpokenText(finalSpokenText)) {
                    finalSpokenText = localizedScreenFallback(activeInteractionLanguage)
                    finalSpokenTextSource = "screen_fallback_sanitized"
                    targetToShow = null
                    compactOutputDegradationReason = listOfNotNull(
                        compactOutputDegradationReason,
                        "placeholder_final_spoken_text_runtime"
                    ).joinToString(",")
                }

                val screenContextRoute = effectiveIntentRoute as? IntentRoute.ScreenContext
                val isMessageSafety = screenContextRoute?.purpose == ScreenContextPurpose.MESSAGE_SAFETY
                if (isMessageSafety) {
                    val visibleCount = compactScreenMapVisibleTextCount?.toIntOrNull() ?: 0
                    val ocrCount = compactScreenMapOcrCount?.toIntOrNull() ?: 0
                    messageSafetyVisibleTextCandidates = extractMessageSafetyCandidatesFromPreview(compactScreenMapPreview)
                    messageSafetyTextAvailable = (visibleCount + ocrCount) > 0 && messageSafetyVisibleTextCandidates.isNotEmpty()
                    answerRepeatsUserQuestion = doesAnswerRepeatUserQuestion(finalSpokenText, text)

                    if (messageSafetyTextAvailable != true || answerRepeatsUserQuestion) {
                        messageSafetyGuardTriggered = true
                        finalSpokenText = localizedMessageSafetyNoTextFallback(activeInteractionLanguage)
                        finalSpokenTextSource = "message_safety_guard_fallback"
                        targetToShow = null
                        compactOutputDegradationReason = listOfNotNull(
                            compactOutputDegradationReason,
                            if (messageSafetyTextAvailable != true) "message_safety_no_visible_text" else null,
                            if (answerRepeatsUserQuestion) "answer_repeats_user_question" else null
                        ).joinToString(",")
                    }
                }

                onDebugResult("message_safety_text_available=${messageSafetyTextAvailable ?: false}")
                onDebugResult("message_safety_visible_text_candidates=$messageSafetyVisibleTextCandidates")
                onDebugResult("message_safety_guard_triggered=$messageSafetyGuardTriggered")
                onDebugResult("answer_repeats_user_question=$answerRepeatsUserQuestion")

                val finalBoundary = applyFinalSpeechBoundaryGuard(
                    transcript = text,
                    candidateSpokenText = finalSpokenText,
                    candidateSource = finalSpokenTextSource,
                    interactionLanguage = activeInteractionLanguage
                )
                finalSpokenText = finalBoundary.spokenText
                finalSpokenTextSource = finalBoundary.spokenSource
                if (finalBoundary.guardTriggered) {
                    targetToShow = null
                }
                onShowTarget(targetToShow)
                _state.value = AssistantUiState.Responding(
                    spokenText = finalSpokenText,
                    targetBounds = targetToShow
                )
                onDebugResult(result.toString())

                emitFinalSpeechTrace(
                    transcript = text,
                    preScreenFinalBranch = preScreenFinalBranch,
                    source = finalSpokenTextSource,
                    guardTriggered = finalBoundary.guardTriggered,
                    repeatDetected = finalBoundary.repeatDetected,
                    spokenText = finalSpokenText
                )
                appendConversationTurn(ConversationRole.ASSISTANT, finalSpokenText)
                trace.markTtsStartedIfNeeded()
                speechOutput.speak(
                    text = finalSpokenText,
                    interrupt = true,
                    onDone = {
                        trace.markTtsFinished()
                        onStartListening()
                        isInFollowupWindow = false
                        _state.value = AssistantUiState.Listening
                    },
                    onError = {
                        trace.markTtsFinished()
                        Log.e(TAG, "speechOutput.speak failed while responding")
                        _state.value = AssistantUiState.Error("Speech output failed")
                        onDebugError("Speech output failed")
                    }
                )
                trace.finishNow()
                trace.emit(
                    telemetry = telemetry,
                    activeIntentRouter = intentRouter::class.java.simpleName,
                    preScreenFallbackRouterUsed = intentRouter !is IntentRouterDiagnostics,
                    preScreenFinalBranch = preScreenFinalBranch,
                    assistantEngineCalled = assistantEngineCalled,
                    observationProviderCalled = observationProviderCalled,
                    screenshotObservationRequested = screenshotObservationRequested,
                    targetHighlightEmitted = targetHighlightEmitted,
                    routerPromptBuildMs = routerPromptBuildMs,
                    routerPromptCharCount = routerPromptCharCount,
                    routerModelInitMs = routerModelInitMs,
                    routerConversationCreateMs = routerConversationCreateMs,
                    routerInferenceMs = routerInferenceMs,
                    routerGenerationConfigMaxOutputTokens = routerGenerationConfigMaxOutputTokens,
                    routerParseMs = routerParseMs,
                    routerValidationMs = routerValidationMs,
                    routerRawOutputCharCount = routerRawOutputCharCount,
                    routerUsedCachedEngine = routerUsedCachedEngine,
                    routerUsedCachedConversation = routerUsedCachedConversation,
                    routerModelInitializationStatus = routerModelInitializationStatus,
                    routerModelInitializationFailure = routerModelInitializationFailure
                )
                emitPerTurnRoutingSpokenTrace(
                    transcript = text,
                    preScreenRawOutput = routerRaw,
                    preScreenParsedRoute = routerParsed,
                    preScreenFinalBranch = preScreenFinalBranch,
                    selectedIntentRoute = trace.selectedIntentRoute,
                    assistantEngineCalled = assistantEngineCalled,
                    observationRequestMode = observationRequestMode,
                    observationStrategy = observationStrategy,
                    screenMapPresent = screenMapPresent,
                    compactPromptUsed = compactPromptUsed,
                    richPromptUsed = richPromptUsed,
                    compactReasonerRawOutput = compactReasonerRawOutput,
                    compactReasonerParsedOutput = compactReasonerParsedOutput,
                    compactOutputDegradationReason = compactOutputDegradationReason,
                    toolRequestPresent = toolRequestPresent,
                    toolExecuted = toolExecuted,
                    toolName = toolName,
                    toolSpokenText = toolSpokenText,
                    reasonerSpokenText = reasonerSpokenText,
                    finalSpokenText = finalSpokenText,
                    finalSpokenTextSource = finalSpokenTextSource
                )
                onDebugResult(
                    "message_safety_trace interactionLanguage=${AssistantSessionPhrases.normalize(activeInteractionLanguage)} " +
                        "compact_prompt_char_count=${compactPromptCharCount ?: "null"} " +
                        "compact_screen_map_visible_text_count=${compactScreenMapVisibleTextCount ?: "null"} " +
                        "compact_screen_map_interactive_count=${compactScreenMapInteractiveCount ?: "null"} " +
                        "compact_screen_map_ocr_count=${compactScreenMapOcrCount ?: "null"} " +
                        "compact_screen_map_preview=${compactScreenMapPreview ?: "null"}"
                )
            }
        }
    }

    private fun isUiNavigationQuestion(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("where do i press") || normalized.startsWith("where should i press")
    }

    private fun parseRationaleToken(rationale: String?, key: String): String? {
        val source = rationale ?: return null
        val marker = "$key="
        val start = source.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = source.indexOf(';', from).let { if (it < 0) source.length else it }
        return source.substring(from, end).trim().takeIf { it.isNotBlank() }
    }

    private fun emitPerTurnRoutingSpokenTrace(
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
        telemetry.emitPerTurnRoutingSpokenTrace(
            transcript = transcript,
            preScreenRawOutput = preScreenRawOutput,
            preScreenParsedRoute = preScreenParsedRoute,
            preScreenFinalBranch = preScreenFinalBranch,
            selectedIntentRoute = selectedIntentRoute,
            assistantEngineCalled = assistantEngineCalled,
            observationRequestMode = observationRequestMode,
            observationStrategy = observationStrategy,
            screenMapPresent = screenMapPresent,
            compactPromptUsed = compactPromptUsed,
            richPromptUsed = richPromptUsed,
            compactReasonerRawOutput = compactReasonerRawOutput,
            compactReasonerParsedOutput = compactReasonerParsedOutput,
            compactOutputDegradationReason = compactOutputDegradationReason,
            toolRequestPresent = toolRequestPresent,
            toolExecuted = toolExecuted,
            toolName = toolName,
            toolSpokenText = toolSpokenText,
            reasonerSpokenText = reasonerSpokenText,
            finalSpokenText = finalSpokenText,
            finalSpokenTextSource = finalSpokenTextSource
        )
    }

    private suspend fun executeToolIfAllowed(request: ToolRequest?): ToolExecutionResult? {
        val toolRequest = request?.takeIf { it.name in AssistantTools.allowed }
        return if (toolRequest != null && toolExecutor != null) {
            try {
                val executed = toolExecutor.execute(toolRequest)
                onDebugTool("request=$toolRequest result=${executed.debugMessage}")
                executed
            } catch (t: Throwable) {
                onDebugTool("request=$toolRequest result=tool_failed:${t.message}")
                null
            }
        } else if (request != null && toolRequest == null) {
            onDebugTool("request=$request result=tool_rejected:not_allowed")
            null
        } else {
            null
        }
    }

    private suspend fun executeScreenReasonerToolIfAllowed(request: ToolRequest?): ToolExecutionResult? {
        if (request != null) {
            onDebugTool("request=$request result=tool_rejected:screen_reasoner_tool_disabled")
        }
        return null
    }

    private fun parseDebugToken(debugMessage: String, key: String): String? {
        val marker = "$key="
        val start = debugMessage.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = debugMessage.indexOf(' ', from).let { if (it < 0) debugMessage.length else it }
        return debugMessage.substring(from, end).takeIf { it.isNotBlank() }
    }

    private fun mapIntentRouteToObservationRequest(route: IntentRoute): ObservationRequest {
        val screenRoute = route as? IntentRoute.ScreenContext
            ?: return ObservationRequest(
                mode = ObservationMode.SCREENSHOT,
                reason = "m2b_screen_context_default"
            )

        val mode = when (screenRoute.mode) {
            ScreenContextMode.SCREENSHOT -> ObservationMode.SCREENSHOT
            ScreenContextMode.SCREEN_MAP_FIRST -> ObservationMode.SCREEN_MAP_FIRST
        }
        return ObservationRequest(
            mode = mode,
            reason = "m2b_route:${screenRoute.purpose.name.lowercase()}"
        )
    }

    override fun onSpeechRecognitionError(message: String, isSilence: Boolean) {
        scope.launch {
            mutex.withLock {
                if (isSilence && isInFollowupWindow) {
                    val interactionLanguage = activeInteractionLanguage()
                    val phrases = AssistantSessionPhrases.forLanguage(interactionLanguage)
                    onStopListening()
                    debugSessionPhrase("closing", phrases.closing, interactionLanguage)
                    val closingBoundary = applyFinalSpeechBoundaryGuard(
                        transcript = null,
                        candidateSpokenText = phrases.closing,
                        candidateSource = "session_phrase",
                        interactionLanguage = interactionLanguage
                    )
                    emitFinalSpeechTrace(
                        transcript = null,
                        preScreenFinalBranch = "speech_recognition_silence_close",
                        source = closingBoundary.spokenSource,
                        guardTriggered = closingBoundary.guardTriggered,
                        repeatDetected = closingBoundary.repeatDetected,
                        spokenText = closingBoundary.spokenText
                    )
                    appendConversationTurn(ConversationRole.ASSISTANT, closingBoundary.spokenText)
                    speechOutput.speak(
                        text = closingBoundary.spokenText,
                        interrupt = true,
                        onDone = {
                            _state.value = AssistantUiState.Dismissed
                            isInFollowupWindow = false
                            clearConversationHistory()
                        },
                        onError = {
                            _state.value = AssistantUiState.Dismissed
                            isInFollowupWindow = false
                            clearConversationHistory()
                        }
                    )
                    onClearTarget()
                    return@withLock
                }

                if (!isSilence) {
                    val interactionLanguage = activeInteractionLanguage()
                    val phrases = AssistantSessionPhrases.forLanguage(interactionLanguage)
                    Log.w(TAG, "Speech recognizer error: $message")
                    onDebugError("Speech recognizer failure: $message")
                    debugSessionPhrase("hearing_retry", phrases.hearingRetry, interactionLanguage)
                    val retryBoundary = applyFinalSpeechBoundaryGuard(
                        transcript = null,
                        candidateSpokenText = phrases.hearingRetry,
                        candidateSource = "session_phrase",
                        interactionLanguage = interactionLanguage
                    )
                    emitFinalSpeechTrace(
                        transcript = null,
                        preScreenFinalBranch = "speech_recognition_error_retry",
                        source = retryBoundary.spokenSource,
                        guardTriggered = retryBoundary.guardTriggered,
                        repeatDetected = retryBoundary.repeatDetected,
                        spokenText = retryBoundary.spokenText
                    )
                    appendConversationTurn(ConversationRole.ASSISTANT, retryBoundary.spokenText)
                    speechOutput.speak(
                        text = retryBoundary.spokenText,
                        interrupt = true,
                        onDone = {
                            _state.value = AssistantUiState.Listening
                            onStartListening()
                        },
                        onError = {
                            _state.value = AssistantUiState.Error("Speech recognizer failed")
                        }
                    )
                }
            }
        }
    }

    override fun onTtsFinished() {
        _state.value = AssistantUiState.Dismissed
        clearConversationHistory()
    }

    override fun stopAndDismiss() {
        onStopListening()
        speechOutput.stop()
        onClearTarget()
        isInFollowupWindow = false
        _state.value = AssistantUiState.Dismissed
        clearConversationHistory()
    }
}
