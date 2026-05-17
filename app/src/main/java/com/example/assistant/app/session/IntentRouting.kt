package com.example.assistant.app.session

import android.os.SystemClock
import com.example.assistant.app.TRIGGER_BUTTON_VOLUME_DOWN
import com.example.assistant.app.TRIGGER_BUTTON_VOLUME_UP
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.gemma.TextOnlyRouteModelLifecycleDiagnostics
import com.example.assistant.gemma.TextOnlyRouterInitializationException
import com.example.assistant.gemma.TextOnlyRouteModel
import com.example.assistant.gemma.TextOnlyRouteModelDiagnostics
import com.example.assistant.gemma.TextOnlyRouteModelTimingDiagnostics
import com.example.assistant.gemma.GemmaIntentRouterPromptBuilder
import com.example.assistant.gemma.GemmaIntentRouterPromptInput
import java.io.PrintWriter
import java.io.StringWriter
import org.json.JSONObject

data class IntentRouterInput(
    val transcript: String,
    val interactionLanguage: String?,
    val currentAssistantSettings: AssistantSettingsSnapshot?,
    val previousAssistantSummary: String? = null
)

data class AssistantSettingsSnapshot(
    val interactionLanguage: String?,
    val triggerButton: String?
)

data class IntentRouterConfig(
    val localToolMinConfidence: Float = 0.6f,
    val generalAnswerMinConfidence: Float = 0.7f
)

enum class ScreenContextMode {
    SCREENSHOT,
    SCREEN_MAP_FIRST
}

enum class ScreenContextPurpose {
    UI_NAVIGATION,
    MESSAGE_SAFETY,
    SCREEN_EXPLANATION,
    IMAGE_UNDERSTANDING,
    FORM_HELP,
    GENERAL_SCREEN_HELP
}

sealed interface IntentRoute {
    data class LocalTool(
        val toolName: String,
        val arguments: Map<String, String>,
        val spokenConfirmation: String?,
        val confidence: Float
    ) : IntentRoute

    data class ScreenContext(
        val mode: ScreenContextMode,
        val purpose: ScreenContextPurpose,
        val confidence: Float
    ) : IntentRoute

    data class GeneralAnswer(
        val spokenText: String,
        val confidence: Float
    ) : IntentRoute

    data class Clarification(
        val spokenText: String,
        val confidence: Float
    ) : IntentRoute
}

object IntentRouteValidator {
    private val allowedPreScreenTools = setOf(
        AssistantTools.UPDATE_INTERACTION_LANGUAGE,
        AssistantTools.MOVE_OVERLAY,
        AssistantTools.UPDATE_TRIGGER_BUTTON,
        AssistantTools.OPEN_PLAY_STORE_AND_SEARCH
    )

    fun validateOrClarify(
        route: IntentRoute,
        config: IntentRouterConfig
    ): IntentRoute {
        val normalized = when (route) {
            is IntentRoute.LocalTool -> route.copy(confidence = clamp01(route.confidence))
            is IntentRoute.ScreenContext -> route.copy(confidence = clamp01(route.confidence))

            is IntentRoute.GeneralAnswer -> route.copy(confidence = clamp01(route.confidence))
            is IntentRoute.Clarification -> route.copy(confidence = clamp01(route.confidence))
        }

        return when (normalized) {
            is IntentRoute.LocalTool -> validateLocalToolOrClarification(normalized, config)
            is IntentRoute.GeneralAnswer -> {
                if (normalized.spokenText.isBlank()) clarification("Can you rephrase that?")
                else if (normalized.confidence < config.generalAnswerMinConfidence) clarification("Can you clarify what you need?")
                else normalized
            }

            is IntentRoute.ScreenContext -> normalized
            is IntentRoute.Clarification -> {
                if (normalized.spokenText.isBlank()) clarification("Can you clarify what you need?") else normalized
            }
        }
    }

    private fun validateLocalToolOrClarification(
        route: IntentRoute.LocalTool,
        config: IntentRouterConfig
    ): IntentRoute {
        if (route.confidence < config.localToolMinConfidence) {
            return clarification("Can you confirm what you want me to change?")
        }
        if (route.toolName !in allowedPreScreenTools) {
            return clarification("I need a clearer request before I change settings.")
        }

        val args = when (route.toolName) {
            AssistantTools.OPEN_PLAY_STORE_AND_SEARCH -> route.arguments.mapValues { it.value.trim() }
            else -> route.arguments.mapValues { it.value.trim().lowercase() }
        }
        val valid = when (route.toolName) {
            AssistantTools.UPDATE_INTERACTION_LANGUAGE -> args["language"] in setOf("it", "en", "it-it", "en-us")
            AssistantTools.MOVE_OVERLAY -> args["position"] in setOf("top_left", "top_right", "bottom_left", "bottom_right", "center")
            AssistantTools.UPDATE_TRIGGER_BUTTON -> args["button"] in setOf(TRIGGER_BUTTON_VOLUME_UP, TRIGGER_BUTTON_VOLUME_DOWN)
            AssistantTools.OPEN_PLAY_STORE_AND_SEARCH -> args["app_name"].isNullOrBlank().not()
            else -> false
        }
        if (!valid) {
            return clarification("Can you repeat that with a supported option?")
        }

        return route.copy(arguments = args)
    }

    private fun clarification(text: String): IntentRoute.Clarification =
        IntentRoute.Clarification(spokenText = text, confidence = 1f)

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)
}

interface IntentRouter {
    suspend fun route(input: IntentRouterInput): IntentRoute
}

interface IntentRouterDiagnostics {
    val routerName: String
    val latestPrompt: String?
    val latestRawOutput: String?
    val latestParsedRoute: IntentRoute?
    val latestDegradationReason: String?
    val latestException: String?
    val latestModelDiagnostics: String?
    val latestModelInitializationStatus: String?
    val latestModelInitializationFailure: String?
    val latestPromptBuildMs: Long?
    val latestModelInitMs: Long?
    val latestConversationCreateMs: Long?
    val latestInferenceMs: Long?
    val latestParseMs: Long?
    val latestValidationMs: Long?
    val latestPromptCharCount: Int?
    val latestRawOutputCharCount: Int?
    val latestGenerationConfigMaxOutputTokens: Int?
    val latestUsedCachedEngine: Boolean?
    val latestUsedCachedConversation: Boolean?
}

class DefaultScreenContextIntentRouter : IntentRouter {
    override suspend fun route(input: IntentRouterInput): IntentRoute {
        val t = input.transcript.trim().lowercase()
        if (t == "speak italian") {
            return IntentRoute.LocalTool(
                toolName = AssistantTools.UPDATE_INTERACTION_LANGUAGE,
                arguments = mapOf("language" to "it"),
                spokenConfirmation = null,
                confidence = 1f
            )
        }
        return IntentRoute.ScreenContext(
            mode = ScreenContextMode.SCREENSHOT,
            purpose = ScreenContextPurpose.GENERAL_SCREEN_HELP,
            confidence = 1f
        )
    }
}

class GemmaIntentRouter(
    private val model: TextOnlyRouteModel,
    private val parser: IntentRouteJsonParser = IntentRouteJsonParser()
) : IntentRouter, IntentRouterDiagnostics {
    @Volatile
    override var latestPrompt: String? = null
        private set

    @Volatile
    override var latestRawOutput: String? = null
        private set

    @Volatile
    override var latestParsedRoute: IntentRoute? = null
        private set

    @Volatile
    override var latestDegradationReason: String? = null
        private set

    @Volatile
    override var latestException: String? = null
        private set

    @Volatile
    override var latestModelDiagnostics: String? = null
        private set

    @Volatile
    override var latestModelInitializationStatus: String? = null
        private set

    @Volatile
    override var latestModelInitializationFailure: String? = null
        private set

    @Volatile
    override var latestPromptBuildMs: Long? = null
        private set

    @Volatile
    override var latestModelInitMs: Long? = null
        private set

    @Volatile
    override var latestConversationCreateMs: Long? = null
        private set

    @Volatile
    override var latestInferenceMs: Long? = null
        private set

    @Volatile
    override var latestParseMs: Long? = null
        private set

    @Volatile
    override var latestValidationMs: Long? = null
        private set

    @Volatile
    override var latestPromptCharCount: Int? = null
        private set

    @Volatile
    override var latestRawOutputCharCount: Int? = null
        private set

    @Volatile
    override var latestGenerationConfigMaxOutputTokens: Int? = null
        private set

    @Volatile
    override var latestUsedCachedEngine: Boolean? = null
        private set

    @Volatile
    override var latestUsedCachedConversation: Boolean? = null
        private set

    override val routerName: String = "GemmaIntentRouter"

    override suspend fun route(input: IntentRouterInput): IntentRoute {
        latestPrompt = null
        latestRawOutput = null
        latestParsedRoute = null
        latestDegradationReason = null
        latestException = null
        latestModelDiagnostics = null
        latestModelInitializationStatus = null
        latestModelInitializationFailure = null
        latestPromptBuildMs = null
        latestModelInitMs = null
        latestConversationCreateMs = null
        latestInferenceMs = null
        latestParseMs = null
        latestValidationMs = null
        latestPromptCharCount = null
        latestRawOutputCharCount = null
        latestGenerationConfigMaxOutputTokens = null
        latestUsedCachedEngine = null
        latestUsedCachedConversation = null
        return try {
            val promptBuildStarted = SystemClock.elapsedRealtime()
            val prompt = buildPrompt(input)
            latestPromptBuildMs = SystemClock.elapsedRealtime() - promptBuildStarted
            latestPrompt = prompt
            latestPromptCharCount = prompt.length
            val inferenceStarted = SystemClock.elapsedRealtime()
            val raw = model.generateRouteJson(prompt)
            latestInferenceMs = SystemClock.elapsedRealtime() - inferenceStarted
            latestModelDiagnostics = (model as? TextOnlyRouteModelDiagnostics)?.latestInvocationDiagnostics
            latestModelInitializationStatus = (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationStatus
            latestModelInitializationFailure = (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationFailureDiagnostics
            latestModelInitMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestModelInitMs
            latestConversationCreateMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestConversationCreateMs
            latestGenerationConfigMaxOutputTokens = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestGenerationConfigMaxOutputTokens
            latestUsedCachedEngine = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedEngine
            latestUsedCachedConversation = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedConversation
            latestRawOutput = raw
            latestRawOutputCharCount = raw.length
            val parseStarted = SystemClock.elapsedRealtime()
            val parsed = parser.parse(raw)
            latestParseMs = SystemClock.elapsedRealtime() - parseStarted
            latestParsedRoute = parsed
            latestDegradationReason = parser.latestDegradationReason
            parsed
        } catch (e: TextOnlyRouterInitializationException) {
            latestModelDiagnostics = (model as? TextOnlyRouteModelDiagnostics)?.latestInvocationDiagnostics
            latestModelInitializationStatus = (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationStatus
            latestModelInitializationFailure = e.diagnostics
                ?: (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationFailureDiagnostics
            latestModelInitMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestModelInitMs
            latestConversationCreateMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestConversationCreateMs
            latestGenerationConfigMaxOutputTokens = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestGenerationConfigMaxOutputTokens
            latestUsedCachedEngine = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedEngine
            latestUsedCachedConversation = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedConversation
            latestException = buildThrowableDetail(e)
            latestDegradationReason = "text_router_unavailable_initialization_failed"
            IntentRoute.Clarification(
                spokenText = "Text router unavailable: initialization failed.",
                confidence = 1f
            )
        } catch (t: Throwable) {
            latestModelDiagnostics = (model as? TextOnlyRouteModelDiagnostics)?.latestInvocationDiagnostics
            latestModelInitializationStatus = (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationStatus
            latestModelInitializationFailure = (model as? TextOnlyRouteModelLifecycleDiagnostics)?.initializationFailureDiagnostics
            latestModelInitMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestModelInitMs
            latestConversationCreateMs = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestConversationCreateMs
            latestGenerationConfigMaxOutputTokens = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestGenerationConfigMaxOutputTokens
            latestUsedCachedEngine = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedEngine
            latestUsedCachedConversation = (model as? TextOnlyRouteModelTimingDiagnostics)?.latestUsedCachedConversation
            latestException = buildThrowableDetail(t)
            latestDegradationReason = "router_exception"
            IntentRoute.Clarification("Can you clarify what you need?", confidence = 1f)
        }
    }

    private fun buildThrowableDetail(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        pw.flush()
        return buildString {
            appendLine("exception_class=${t::class.java.name}")
            appendLine("exception_message=${t.message ?: "no_message"}")
            appendLine("cause_class=${t.cause?.javaClass?.name ?: "null"}")
            appendLine("cause_message=${t.cause?.message ?: "no_message"}")
            appendLine("full_stack_trace=${sw}")
        }
    }

    private fun buildPrompt(input: IntentRouterInput): String {
        return GemmaIntentRouterPromptBuilder.build(
            GemmaIntentRouterPromptInput(
                transcript = input.transcript,
                interactionLanguage = input.interactionLanguage,
                triggerButton = input.currentAssistantSettings?.triggerButton,
                previousAssistantSummary = input.previousAssistantSummary
            )
        )
    }
}

class IntentRouteJsonParser(
    private val config: IntentRouterConfig = IntentRouterConfig()
) {
    @Volatile
    var latestDegradationReason: String? = null
        private set

    @Volatile
    var compactScreenMissingModeDefaulted: Boolean = false
        private set

    @Volatile
    var compactScreenDefaultMode: ScreenContextMode? = null
        private set

    @Volatile
    var compactScreenDefaultPurpose: ScreenContextPurpose? = null
        private set

    fun parse(raw: String): IntentRoute {
        latestDegradationReason = null
        compactScreenMissingModeDefaulted = false
        compactScreenDefaultMode = null
        compactScreenDefaultPurpose = null
        val json = parseObject(raw) ?: return degrade("parser_invalid_json", IntentRoute.Clarification("Can you rephrase that?", confidence = 1f))
        if (json.has("r")) return parseCompact(json)
        val route = json.safeString("route").trim().lowercase()
        val confidence = json.optDouble("confidence", 0.0).toFloat()
        return when (route) {
            "local_tool" -> {
                val toolName = normalizeToolName(json.safeString("tool_name").trim())
                val args = mutableMapOf<String, String>()
                val argsObj = json.optJSONObject("arguments")
                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        args[key] = argsObj.safeString(key).trim()
                    }
                }
                val normalizedArgs = normalizeArgs(toolName, args)
                IntentRouteValidator.validateOrClarify(
                    IntentRoute.LocalTool(
                        toolName = toolName,
                        arguments = normalizedArgs,
                        spokenConfirmation = json.safeString("spoken_text").takeIf { it.isNotBlank() },
                        confidence = confidence
                    ),
                    config
                ).also { if (it is IntentRoute.Clarification) latestDegradationReason = "validator_local_tool_rejected" }
            }

            "screen_context" -> {
                val context = json.optJSONObject("screen_context")
                val mode = when (context?.safeString("mode")?.trim()?.lowercase()) {
                    "screenshot" -> ScreenContextMode.SCREENSHOT
                    "screen_map_first" -> ScreenContextMode.SCREEN_MAP_FIRST
                    else -> null
                }
                val purpose = when (context?.safeString("purpose")?.trim()?.lowercase()) {
                    "ui_navigation" -> ScreenContextPurpose.UI_NAVIGATION
                    "message_safety" -> ScreenContextPurpose.MESSAGE_SAFETY
                    "screen_explanation" -> ScreenContextPurpose.SCREEN_EXPLANATION
                    "image_understanding" -> ScreenContextPurpose.IMAGE_UNDERSTANDING
                    "form_help" -> ScreenContextPurpose.FORM_HELP
                    "general_screen_help" -> ScreenContextPurpose.GENERAL_SCREEN_HELP
                    else -> null
                }
                if (mode == null || purpose == null) {
                    degrade("parser_invalid_screen_context_fields", IntentRoute.Clarification("Can you clarify what you need?", confidence = 1f))
                } else {
                    IntentRouteValidator.validateOrClarify(
                        IntentRoute.ScreenContext(mode = mode, purpose = purpose, confidence = confidence),
                        config
                    )
                }
            }

            "general_answer" -> IntentRouteValidator.validateOrClarify(
                    IntentRoute.GeneralAnswer(
                    spokenText = json.safeString("spoken_text"),
                    confidence = confidence
                ),
                config
            )

            "clarification" -> IntentRouteValidator.validateOrClarify(
                IntentRoute.Clarification(
                    spokenText = json.safeString("spoken_text"),
                    confidence = confidence
                ),
                config
            )

            else -> degrade("parser_unknown_route:$route", IntentRoute.Clarification("Can you rephrase that?", confidence = 1f))
        }
    }

    private fun parseCompact(json: JSONObject): IntentRoute {
        val route = json.safeString("r").trim().lowercase()
        return when (route) {
            "tool" -> {
                val toolType = json.safeString("t").trim().lowercase()
                val a = json.optJSONObject("a")
                val routeOut = when (toolType) {
                    "language" -> IntentRoute.LocalTool(
                        toolName = AssistantTools.UPDATE_INTERACTION_LANGUAGE,
                        arguments = mapOf("language" to a?.safeString("l").orEmpty()),
                        spokenConfirmation = null,
                        confidence = 1f
                    )
                    "move" -> return degrade(
                        "parser_compact_move_tool_rejected",
                        IntentRoute.Clarification("Can you rephrase that?", confidence = 1f)
                    )
                    "trigger" -> IntentRoute.LocalTool(
                        toolName = AssistantTools.UPDATE_TRIGGER_BUTTON,
                        arguments = mapOf("button" to a?.safeString("b").orEmpty()),
                        spokenConfirmation = null,
                        confidence = 1f
                    )
                    "app" -> IntentRoute.LocalTool(
                        toolName = AssistantTools.OPEN_PLAY_STORE_AND_SEARCH,
                        arguments = mapOf("app_name" to a?.safeString("name").orEmpty()),
                        spokenConfirmation = null,
                        confidence = 1f
                    )
                    else -> return degrade("parser_compact_unknown_tool:$toolType", IntentRoute.Clarification("Can you rephrase that?", confidence = 1f))
                }
                IntentRouteValidator.validateOrClarify(routeOut, config).also {
                    if (it is IntentRoute.Clarification) latestDegradationReason = "validator_local_tool_rejected"
                }
            }

            "screen" -> {
                val rawMode = json.safeString("m").trim().lowercase()
                val isModeMissing = rawMode.isBlank()
                val defaultMode = ScreenContextMode.SCREEN_MAP_FIRST
                val defaultPurpose = ScreenContextPurpose.GENERAL_SCREEN_HELP
                compactScreenMissingModeDefaulted = isModeMissing
                compactScreenDefaultMode = defaultMode
                compactScreenDefaultPurpose = defaultPurpose

                val mode = when (rawMode) {
                    "auto", "ui_navigation", "message_safety", "image" -> ScreenContextMode.SCREEN_MAP_FIRST
                    else -> if (isModeMissing) defaultMode else null
                }
                val purpose = when (rawMode) {
                    "auto" -> ScreenContextPurpose.GENERAL_SCREEN_HELP
                    "ui_navigation" -> ScreenContextPurpose.UI_NAVIGATION
                    "message_safety" -> ScreenContextPurpose.MESSAGE_SAFETY
                    "image" -> ScreenContextPurpose.IMAGE_UNDERSTANDING
                    else -> if (isModeMissing) defaultPurpose else null
                }
                if (mode == null || purpose == null) {
                    degrade("parser_compact_invalid_screen_mode", IntentRoute.Clarification("Can you clarify what you need?", confidence = 1f))
                } else {
                    IntentRouteValidator.validateOrClarify(
                        IntentRoute.ScreenContext(mode = mode, purpose = purpose, confidence = 1f),
                        config
                    )
                }
            }

            "answer" -> IntentRouteValidator.validateOrClarify(
                IntentRoute.GeneralAnswer(spokenText = json.safeString("text"), confidence = 1f),
                config
            )

            "clarify" -> IntentRouteValidator.validateOrClarify(
                IntentRoute.Clarification(spokenText = json.safeString("text"), confidence = 1f),
                config
            )

            else -> degrade("parser_compact_unknown_route:$route", IntentRoute.Clarification("Can you rephrase that?", confidence = 1f))
        }
    }

    private fun degrade(reason: String, route: IntentRoute): IntentRoute {
        latestDegradationReason = appendCompactScreenDiagnostics(reason)
        return IntentRouteValidator.validateOrClarify(route, config)
    }

    private fun appendCompactScreenDiagnostics(reason: String): String {
        val defaultModeName = compactScreenDefaultMode?.name ?: "null"
        val defaultPurposeName = compactScreenDefaultPurpose?.name ?: "null"
        return "$reason;parser_compact_screen_missing_mode_defaulted=$compactScreenMissingModeDefaulted;parser_compact_screen_default_mode=$defaultModeName;parser_compact_screen_default_purpose=$defaultPurposeName"
    }

    private fun normalizeToolName(value: String): String {
        val v = value.trim().lowercase()
        return when (v) {
            "change_language", "set_language" -> AssistantTools.UPDATE_INTERACTION_LANGUAGE
            "move_bubble" -> AssistantTools.MOVE_OVERLAY
            else -> v
        }
    }

    private fun normalizeArgs(toolName: String, args: Map<String, String>): Map<String, String> {
        if (
            toolName == AssistantTools.OPEN_PLAY_STORE_AND_SEARCH
        ) {
            return args.mapValues { it.value.trim() }
        }
        val lowered = args.mapValues { it.value.trim().lowercase() }.toMutableMap()
        when (toolName) {
            AssistantTools.UPDATE_INTERACTION_LANGUAGE -> {
                val lang = lowered["language"]
                lowered["language"] = when (lang) {
                    "italian", "it", "it-it" -> "it"
                    "english", "en", "en-us" -> "en"
                    else -> lang ?: ""
                }
            }
            AssistantTools.MOVE_OVERLAY -> {
                val position = lowered["position"]
                lowered["position"] = when (position) {
                    "top right", "upper right", "top-right", "right top" -> "top_right"
                    "top left", "upper left", "top-left", "left top" -> "top_left"
                    "bottom right", "lower right", "bottom-right", "right bottom" -> "bottom_right"
                    "bottom left", "lower left", "bottom-left", "left bottom" -> "bottom_left"
                    else -> position ?: ""
                }
            }
        }
        return lowered
    }

    private fun parseObject(raw: String): JSONObject? {
        return try {
            JSONObject(raw.trim())
        } catch (_: Throwable) {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    JSONObject(raw.substring(start, end + 1))
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }
    }

    private fun JSONObject.safeString(key: String): String =
        try {
            optString(key, "") ?: ""
        } catch (_: Throwable) {
            ""
        }
}
