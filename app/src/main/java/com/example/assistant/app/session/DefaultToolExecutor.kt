package com.example.assistant.app.session

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.assistant.android.accessibility.apps.InstalledAppProvider
import com.example.assistant.android.accessibility.apps.PackageManagerInstalledAppProvider
import com.example.assistant.android.overlay.AssistantBubbleOverlayController
import com.example.assistant.android.speech.SpeechLanguageUpdateResult
import com.example.assistant.app.Prefs
import com.example.assistant.app.TRIGGER_BUTTON_VOLUME_DOWN
import com.example.assistant.app.TRIGGER_BUTTON_VOLUME_UP
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.ToolRequest

class DefaultToolExecutor(
    private val prefs: Prefs,
    private val speechOutputLanguageSetter: (String) -> SpeechLanguageUpdateResult,
    context: Context? = null,
    private val appToolRouting: AppToolRouting? = context?.let { AppToolRouting(PackageManagerInstalledAppProvider(it)) }
) : ToolExecutor {

    private fun phrases(): AssistantSessionPhrases = AssistantSessionPhrases.forLanguage(prefs.getInteractionLanguage())

    override suspend fun execute(request: ToolRequest): ToolExecutionResult {
        var currentlang = prefs.getInteractionLanguage()
        val service = com.example.assistant.android.accessibility.service.LucaAccessibilityService.getActiveInstance()
            ?: return ToolExecutionResult(
                success = false,
                spokenTextOverride = AssistantSessionPhrases.forLanguage(currentlang).cantdothat,
                debugMessage = "tool_failed:no_accessibility_service"
            )

        return when (request.name) {
            AssistantTools.MOVE_OVERLAY -> moveOverlay(service, request)
            AssistantTools.UPDATE_INTERACTION_LANGUAGE -> updateLanguage(request)
            AssistantTools.UPDATE_TRIGGER_BUTTON -> updateTrigger(request)
            AssistantTools.OPEN_PLAY_STORE_AND_SEARCH -> openPlayStore(request)
            else -> ToolExecutionResult(
                success = false,
                spokenTextOverride = AssistantSessionPhrases.forLanguage(currentlang).cantdothat,
                debugMessage = "tool_rejected:unknown_tool:${request.name}"
            )
        }
    }

    private fun moveOverlay(service: AccessibilityService, request: ToolRequest): ToolExecutionResult {
        val position = request.arguments["position"]?.lowercase()
        val preset = when (position) {
            "top_left" -> AssistantBubbleOverlayController.BubblePresetPosition.TOP_LEFT
            "top_right" -> AssistantBubbleOverlayController.BubblePresetPosition.TOP_RIGHT
            "bottom_left" -> AssistantBubbleOverlayController.BubblePresetPosition.BOTTOM_LEFT
            "bottom_right" -> AssistantBubbleOverlayController.BubblePresetPosition.BOTTOM_RIGHT
            "center" -> AssistantBubbleOverlayController.BubblePresetPosition.CENTER
            else -> null
        } ?: return ToolExecutionResult(
            success = false,
            spokenTextOverride = moveOverlayInvalidArgsText(),
            debugMessage = "tool_invalid_args:move_overlay position=$position"
        )

        AssistantBubbleOverlayController.moveToPreset(service, preset)
        return ToolExecutionResult(true, debugMessage = "tool_ok:move_overlay position=$position")
    }

    private fun updateLanguage(request: ToolRequest): ToolExecutionResult {
        val requestedTag = request.arguments["language"]?.trim().orEmpty()
        val normalizedTag = normalizeInteractionLanguageTag(requestedTag)
        val requestedTtsLocale = normalizedTtsLocaleForLanguageTag(normalizedTag)
        if (requestedTag.isBlank()) {
            return ToolExecutionResult(false, languageMissingText(), "tool_invalid_args:update_interaction_language")
        }
        prefs.setInteractionLanguage(normalizedTag)
        val persistedTag = prefs.getInteractionLanguage()
        val languageResult = speechOutputLanguageSetter(normalizedTag)
        val spokenConfirmationText = confirmationTextForLanguage(persistedTag)
        val spokenConfirmationLanguage = persistedTag
        val diagnostics = buildString {
            append("requestedInteractionLanguage=")
            append(requestedTag)
            append(" persistedInteractionLanguage=")
            append(persistedTag)
            append(" requestedTtsLocale=")
            append(requestedTtsLocale)
            append(" ttsSetLanguageResult=")
            append(languageResult.ttsSetLanguageResult)
            append(" activeTtsLocale=")
            append(languageResult.activeTtsLocale ?: "unknown")
            append(" activeTtsVoice=")
            append(languageResult.activeTtsVoice ?: "unknown")
            append(" ttsLanguageChangeSucceeded=")
            append(languageResult.success)
            append(" spokenConfirmationLanguage=")
            append(spokenConfirmationLanguage)
            append(" spokenConfirmationText=")
            append(spokenConfirmationText)
            if (!languageResult.failureReason.isNullOrBlank()) {
                append(" failureReason=")
                append(languageResult.failureReason)
            }
        }
        return if (languageResult.success) {
            ToolExecutionResult(
                success = true,
                spokenTextOverride = spokenConfirmationText,
                debugMessage = "tool_ok:update_interaction_language $diagnostics"
            )
        } else {
            val failureMessage = if (normalizedTag.startsWith("it", ignoreCase = true)) {
                italianVoiceMissingText()
            } else {
                requestedVoiceMissingText()
            }
            ToolExecutionResult(
                success = false,
                spokenTextOverride = failureMessage,
                debugMessage = "tool_failed:update_interaction_language $diagnostics"
            )
        }
    }

    private fun normalizeInteractionLanguageTag(languageTag: String): String {
        val trimmed = languageTag.trim()
        return when (trimmed.lowercase()) {
            "it", "it-it" -> "it-IT"
            "en", "en-us" -> "en-US"
            else -> trimmed
        }
    }

    private fun normalizedTtsLocaleForLanguageTag(languageTag: String): String {
        return when (languageTag.lowercase()) {
            "it", "it-it" -> "it-IT"
            "en", "en-us" -> "en-US"
            else -> languageTag
        }
    }

    private fun confirmationTextForLanguage(languageTag: String): String {
        return when (languageTag.lowercase()) {
            "it", "it-it" -> "Certo, parlerò in italiano."
            else -> "Sure, I’ll speak English."
        }
    }

    private fun appSearchSpokenText(appName: String): String {
        val languageTag = prefs.getInteractionLanguage()
        return when (languageTag.lowercase()) {
            "it", "it-it" -> "Cerco $appName nel Play Store. Se è già installata, premi Apri. Altrimenti, premi Installa."
            else -> "I’ll search for $appName in the Play Store. If it’s installed, press Open. Otherwise, press Install."
        }
    }

    private fun updateTrigger(request: ToolRequest): ToolExecutionResult {
        val button = request.arguments["button"]?.trim()?.lowercase()
        val safe = when (button) {
            TRIGGER_BUTTON_VOLUME_UP -> TRIGGER_BUTTON_VOLUME_UP
            TRIGGER_BUTTON_VOLUME_DOWN -> TRIGGER_BUTTON_VOLUME_DOWN
            else -> null
        } ?: return ToolExecutionResult(
            false,
            triggerInvalidArgsText(),
            "tool_invalid_args:update_trigger_button=$button"
        )
        prefs.setTriggerButton(safe)
        return ToolExecutionResult(true, debugMessage = "tool_ok:update_trigger_button=$safe")
    }

    private fun openPlayStore(request: ToolRequest): ToolExecutionResult {
        val appName = request.arguments["app_name"]?.takeIf { it.isNotBlank() } ?: "YouTube"
        val uri = Uri.parse("market://search?q=${Uri.encode(appName)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val routing = appToolRouting
        if (routing != null) {
            val decision = routing.routeByAppName(appName)
            if (decision.request == null) {
                val fallback = when (decision.fallbackReason) {
                    FallbackReason.APP_NAME_MISSING -> appNameMissingText()
                    FallbackReason.PLAY_STORE_UNAVAILABLE -> playStoreUnavailableText(appName)
                    null -> playStoreOpenFailedText()
                }
                return ToolExecutionResult(
                    success = false,
                    spokenTextOverride = fallback,
                    debugMessage = "tool_failed:open_play_store_and_search app=$appName selected_app_tool=fallback installed_app_resolver_result=missing_play_store_unavailable final_app_route=fallback"
                )
            }
        }

        return try {
            val service = com.example.assistant.android.accessibility.service.LucaAccessibilityService.getActiveInstance()
                ?: return ToolExecutionResult(false, storeUnavailableNowText(), "tool_failed:no_service_play_store")
            service.startActivity(intent)
            ToolExecutionResult(
                success = true,
                spokenTextOverride = appSearchSpokenText(appName),
                debugMessage = "tool_ok:open_play_store_and_search app=$appName selected_app_tool=open_play_store_and_search installed_app_resolver_result=missing final_app_route=play_store"
            )
        } catch (_: Throwable) {
            ToolExecutionResult(
                success = false,
                spokenTextOverride = playStoreOpenFailedText(),
                debugMessage = "tool_failed:open_play_store_and_search app=$appName"
            )
        }
    }

    private fun moveOverlayInvalidArgsText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Posso spostarmi in alto a sinistra, in alto a destra, in basso a sinistra, in basso a destra oppure al centro."
        else -> "I can move to top left, top right, bottom left, bottom right, or center."
    }

    private fun languageMissingText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Dimmi prima una lingua."
        else -> "I need a language first."
    }

    private fun italianVoiceMissingText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "La voce italiana non è installata su questo dispositivo."
        else -> "Italian voice is not installed on this device."
    }

    private fun requestedVoiceMissingText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "La voce richiesta non è installata su questo dispositivo."
        else -> "The requested voice is not installed on this device."
    }

    private fun triggerInvalidArgsText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Puoi scegliere volume su o volume giù."
        else -> "You can choose volume up or volume down."
    }

    private fun appNameMissingText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Dimmi prima il nome dell'app."
        else -> "I need the app name first."
    }

    private fun playStoreUnavailableText(appName: String): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Non riesco ad aprire il Play Store su questo dispositivo, quindi non posso verificare se $appName è installata."
        else -> "I couldn't open Play Store on this device, so I can't check whether $appName is installed."
    }

    private fun storeUnavailableNowText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Non riesco ad aprire il negozio in questo momento."
        else -> "I can't open the store right now."
    }

    private fun playStoreOpenFailedText(): String = when (AssistantSessionPhrases.normalize(prefs.getInteractionLanguage())) {
        "it-IT" -> "Non riesco ad aprire il Play Store su questo dispositivo."
        else -> "I couldn't open the Play Store on this device."
    }
}
