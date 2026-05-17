package com.example.assistant.app.session

import android.app.Activity
import android.util.Log
import com.example.assistant.android.capture.DebugScreenshotCapturer
import com.example.assistant.android.speech.AndroidSpeechOutput
import com.example.assistant.app.Prefs
import com.example.assistant.core.engine.DefaultAssistantEngine
import com.example.assistant.core.engine.Reasoner
import com.example.assistant.core.engine.SimpleObservationPlanner
import com.example.assistant.core.model.RectBounds
import com.example.assistant.gemma.FakeReasoner
import com.example.assistant.gemma.GemmaConfig
import com.example.assistant.gemma.GemmaReasoner
import com.example.assistant.gemma.GemmaTextOnlyRouterModel
import com.example.assistant.gemma.TextOnlyRouteModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DebugSessionBootstrap(
    activity: Activity,
    private val isDebugBuild: Boolean,
    private val onStartListening: () -> Unit,
    private val onStopListening: () -> Unit,
    private val onShowTarget: (RectBounds?) -> Unit,
    private val onClearTarget: () -> Unit,
    private val onDebugResult: (String) -> Unit,
    private val onDebugError: (String) -> Unit,
    private val gemmaReasonerFactory: (GemmaConfig) -> GemmaReasoner = { GemmaReasoner(it) },
    private val fakeReasonerFactory: () -> Reasoner = { FakeReasoner() },
    private val textOnlyRouterModelFactory: (GemmaConfig) -> TextOnlyRouteModel = { GemmaTextOnlyRouterModel(it) }
) {
    private companion object {
        const val TAG = "DebugSessionBootstrap"
    }

    private val speech = AndroidSpeechOutput(activity)
    private val prefs = Prefs(activity)
    private val observationProvider = ActivityObservationProvider(activity, DebugScreenshotCapturer())
    // M1 legacy/fallback planner only.
    // After M2B, screen-context routing is owned by the pre-screen router and
    // DefaultAssistantSessionController passes explicit ObservationRequest to the engine.
    private val planner = SimpleObservationPlanner()
    private val toolExecutor = DefaultToolExecutor(
        prefs = prefs,
        speechOutputLanguageSetter = { tag -> speech.setLanguage(tag) },
        context = activity
    )
    // TODO: THIS WON'T WORK ON OTHER DEVICES, NEED TO FIGURE OUT A GOOD WAY TO CONFIGURE THIS FOR TESTING WITHOUT HARD-CODING A PATH
    val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
    val modelCacheDirPath = "${activity.cacheDir.absolutePath}/gemma_xnnpack_cache"
    private val gemmaReasoner = gemmaReasonerFactory(
        GemmaConfig(
            modelPath = modelPath,
            cacheDirPath = modelCacheDirPath,
            maxOutputTokens = 4096
        )
    )
    val activeIntentRouter: String
    val preScreenFallbackRouterUsed: Boolean
    private val intentRouter: IntentRouter
    private val typedRouterTestMutex = Mutex()

    init {
        val persistedInteractionLanguage = prefs.getInteractionLanguage()
        val activationLanguageResult = speech.setLanguage(persistedInteractionLanguage)
        onDebugResult("persistedInteractionLanguageOnActivation=$persistedInteractionLanguage")
        onDebugResult("ttsLocaleAppliedOnActivation=${activationLanguageResult.activeTtsLocale ?: "unknown"}")
        onDebugResult("activeTtsVoice=${activationLanguageResult.activeTtsVoice ?: "unknown"}")

        val configured = try {
            GemmaIntentRouter(
                model = textOnlyRouterModelFactory(
                    GemmaConfig(
                        modelPath = modelPath,
                        cacheDirPath = modelCacheDirPath,
                        maxOutputTokens = 64
                    )
                )
            )
        } catch (_: Throwable) {
            null
        }
        intentRouter = configured ?: DefaultScreenContextIntentRouter()
        activeIntentRouter = when (intentRouter) {
            is IntentRouterDiagnostics -> intentRouter.routerName
            else -> intentRouter::class.java.simpleName
        }
        preScreenFallbackRouterUsed = configured == null
        onDebugResult("pre_screen_router=$activeIntentRouter fallback_router_used=$preScreenFallbackRouterUsed")
    }

    val modelFileExists: Boolean
    val structuredOutputEnabled: Boolean
    val liteRtInitialized: Boolean
    val multimodalImageInputEnabled: Boolean
    val selectedBackend: String
    val latestRawModelOutput: String
    val latestGemmaError: String?
    val activeReasoner: String

    private val selectedReasoner: Reasoner?
    private val releaseBlockMessage: String?

    init {
        modelFileExists = gemmaReasoner.isModelFilePresent()
        structuredOutputEnabled = gemmaReasoner.isStructuredOutputEnabledNow()
        liteRtInitialized = gemmaReasoner.isLiteRtDependencyPresent()
        multimodalImageInputEnabled = gemmaReasoner.isMultimodalImageInputEnabled()
        selectedBackend = gemmaReasoner.selectedBackendName()

        val preflightFailure = if (isDebugBuild) {
            gemmaReasoner.checkDebugHardRequirementsWithoutInit()
        } else {
            gemmaReasoner.checkReleaseHardRequirementsWithoutInit()
        }

        Log.i(
            TAG,
            "Gemma preflight: debug=$isDebugBuild, modelFileExists=$modelFileExists, " +
                "liteRtDependencyPresent=$liteRtInitialized, multimodalImageInputEnabled=$multimodalImageInputEnabled, " +
                "preflightFailure=${preflightFailure?.technicalReason ?: "none"}"
        )

        if (preflightFailure == null) {
            selectedReasoner = gemmaReasoner
            activeReasoner = "GemmaReasoner"
            latestGemmaError = null
            latestRawModelOutput = gemmaReasoner.latestRawModelOutput() ?: "n/a"
            releaseBlockMessage = null
            Log.i(TAG, "Selected reasoner=GemmaReasoner")
        } else if (isDebugBuild) {
            selectedReasoner = fakeReasonerFactory()
            activeReasoner = "FakeReasoner"
            latestGemmaError = preflightFailure.technicalReason
            latestRawModelOutput = gemmaReasoner.latestRawModelOutput() ?: "n/a"
            releaseBlockMessage = null
            Log.w(TAG, "Selected reasoner=FakeReasoner due to preflight failure: ${preflightFailure.technicalReason}")
        } else {
            selectedReasoner = null
            activeReasoner = "Unavailable"
            latestGemmaError = preflightFailure.technicalReason
            latestRawModelOutput = gemmaReasoner.latestRawModelOutput() ?: "n/a"
            releaseBlockMessage = preflightFailure.userMessage
            Log.w(TAG, "Selected reasoner=Unavailable (release) due to preflight failure: ${preflightFailure.technicalReason}")
        }
    }

    val controller = if (selectedReasoner != null) {
        DefaultAssistantSessionController(
            assistantEngine = DefaultAssistantEngine(
                context = activity,
                planner = planner,
                observationProvider = observationProvider,
                reasoner = selectedReasoner
            ),
            speechOutput = speech,
            intentRouter = intentRouter,
            assistantSettingsProvider = {
                AssistantSettingsSnapshot(
                    interactionLanguage = prefs.getInteractionLanguage(),
                    triggerButton = prefs.getTriggerButton()
                )
            },
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onShowTarget = onShowTarget,
            onClearTarget = onClearTarget,
            onDebugResult = onDebugResult,
            onDebugError = onDebugError,
            onDebugTool = onDebugResult,
            toolExecutor = toolExecutor
        )
    } else {
        UnavailableSessionController(
            message = releaseBlockMessage ?: "AI unavailable",
            onDebugError = onDebugError
        )
    }

    fun close() {
        gemmaReasoner.close()
        speech.shutdown()
    }

    suspend fun runTypedRouterDiagnostics(transcript: String): List<String> {
        return typedRouterTestMutex.withLock {
            val lines = mutableListOf<String>()
            val trimmed = transcript.trim()
            lines += "typed_router_test_input=$trimmed"
            lines += "activeIntentRouter=$activeIntentRouter"
            lines += "preScreenFallbackRouterUsed=$preScreenFallbackRouterUsed"

            val routeRaw = try {
                intentRouter.route(
                    IntentRouterInput(
                        transcript = trimmed,
                        interactionLanguage = prefs.getInteractionLanguage(),
                        currentAssistantSettings = AssistantSettingsSnapshot(
                            interactionLanguage = prefs.getInteractionLanguage(),
                            triggerButton = prefs.getTriggerButton()
                        ),
                        previousAssistantSummary = null
                    )
                )
            } catch (t: Throwable) {
                lines += "pre_screen_exception=${t::class.java.simpleName}: ${t.message ?: "no_message"}"
                IntentRoute.Clarification("Can you clarify what you need?", confidence = 1f)
            }

            val routeValidated = IntentRouteValidator.validateOrClarify(routeRaw, IntentRouterConfig())
            val diagnostics = intentRouter as? IntentRouterDiagnostics

            diagnostics?.latestPrompt?.let {
                lines += "latestPrompt=$it"
            }
            diagnostics?.latestRawOutput?.let {
                lines += "latestRawOutput=$it"
            }
            diagnostics?.latestParsedRoute?.let {
                lines += "latestParsedRoute=$it"
            }
            diagnostics?.latestDegradationReason?.let {
                lines += "latestDegradationReason=$it"
            }
            diagnostics?.latestException?.let {
                lines += "pre_screen_exception=$it"
            }
            diagnostics?.latestModelInitializationStatus?.let {
                lines += "pre_screen_model_initialization_status=$it"
            }
            diagnostics?.latestModelInitializationFailure?.let {
                lines += "pre_screen_model_initialization_failure=$it"
            }
            diagnostics?.latestModelDiagnostics?.let {
                lines += "pre_screen_model_diagnostics=$it"
            }
            val finalBranch = when (routeValidated) {
                is IntentRoute.LocalTool -> "local_tool"
                is IntentRoute.ScreenContext -> "screen_context"
                is IntentRoute.GeneralAnswer -> "general_answer"
                is IntentRoute.Clarification -> "clarification"
            }
            lines += "pre_screen_final_branch=$finalBranch"
            lines += "pre_screen_route=$routeValidated"
            lines
        }
    }

    suspend fun runGemmaTextRouterDiagnostics(): List<String> {
        return typedRouterTestMutex.withLock {
            val lines = mutableListOf<String>()
            lines += "gemma_text_router_self_test_started=true"
            lines += "activeIntentRouter=$activeIntentRouter"
            lines += "preScreenFallbackRouterUsed=$preScreenFallbackRouterUsed"

            val diagnostics = intentRouter as? IntentRouterDiagnostics
            val expected = """{"route":"screen_context","tool_name":null,"arguments":{},"screen_context":{"mode":"screenshot","purpose":"screen_explanation"},"spoken_text":null,"confidence":0.9}"""

            val routeRaw = try {
                intentRouter.route(
                    IntentRouterInput(
                        transcript = "Return exactly this JSON: $expected",
                        interactionLanguage = prefs.getInteractionLanguage(),
                        currentAssistantSettings = AssistantSettingsSnapshot(
                            interactionLanguage = prefs.getInteractionLanguage(),
                            triggerButton = prefs.getTriggerButton()
                        ),
                        previousAssistantSummary = null
                    )
                )
            } catch (t: Throwable) {
                lines += "pre_screen_exception=${t::class.java.name}: ${t.message ?: "no_message"}"
                IntentRoute.Clarification("Can you clarify what you need?", confidence = 1f)
            }

            diagnostics?.latestPrompt?.let { lines += "latestPrompt=$it" }
            diagnostics?.latestRawOutput?.let { lines += "latestRawOutput=$it" }
            diagnostics?.latestParsedRoute?.let { lines += "latestParsedRoute=$it" }
            diagnostics?.latestDegradationReason?.let { lines += "latestDegradationReason=$it" }
            diagnostics?.latestException?.let { lines += "pre_screen_exception=$it" }
            diagnostics?.latestModelInitializationStatus?.let {
                lines += "pre_screen_model_initialization_status=$it"
            }
            diagnostics?.latestModelInitializationFailure?.let {
                lines += "pre_screen_model_initialization_failure=$it"
            }
            diagnostics?.latestModelDiagnostics?.let { lines += "pre_screen_model_diagnostics=$it" }

            val routeValidated = IntentRouteValidator.validateOrClarify(routeRaw, IntentRouterConfig())
            val finalBranch = when (routeValidated) {
                is IntentRoute.LocalTool -> "local_tool"
                is IntentRoute.ScreenContext -> "screen_context"
                is IntentRoute.GeneralAnswer -> "general_answer"
                is IntentRoute.Clarification -> "clarification"
            }
            lines += "pre_screen_final_branch=$finalBranch"
            lines += "pre_screen_route=$routeValidated"
            lines += "gemma_text_router_self_test_finished=true"
            lines
        }
    }
}

private class UnavailableSessionController(
    private val message: String,
    private val onDebugError: (String) -> Unit
) : AssistantSessionController {
    private val _state = MutableStateFlow<AssistantUiState>(AssistantUiState.Dismissed)
    override val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    override fun activate() {
        _state.value = AssistantUiState.Error(message)
        onDebugError(message)
    }

    override fun onSpeechRecognized(text: String) = Unit

    override fun onSpeechRecognitionError(message: String, isSilence: Boolean) = Unit

    override fun onTtsFinished() = Unit

    override fun stopAndDismiss() {
        _state.value = AssistantUiState.Dismissed
    }
}
