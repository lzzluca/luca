package com.example.assistant.gemma

import com.example.assistant.core.model.CompactScreenMap

data class GemmaCompactScreenMapPromptInput(
    val userRequest: String,
    val interactionLanguage: String?,
    val map: CompactScreenMap
)

object GemmaCompactScreenMapPromptBuilder {
    fun build(input: GemmaCompactScreenMapPromptInput): String {
        val normalizedLanguage = normalizeLanguage(input.interactionLanguage)
        val visible = input.map.visibleText.joinToString("\n") { "${it.id} ${it.label}" }
        val interactive = input.map.interactive.joinToString("\n") { "${it.id} ${it.label}" }
        val ocr = input.map.ocr.joinToString("\n") { "${it.id} ${it.label}" }

        return """
            You are Luca, helping a non-technical user with the current phone screen.

            Return JSON only in this exact shape:
            {"say":"...", "target_id":null, "confidence":0.0}

            Rules:
            - Replace "..." with the actual answer for the user.
            - If no target applies, use target_id:null.
            - Prefer interactive IDs (E*) for actions.
            - Use text/OCR IDs (T*/O*) when action target is textual and no interactive ID is suitable.
            - Give one step at a time.
            - Do not claim to have clicked.
            - Do not output coordinates.

            Interaction language: $normalizedLanguage
            Write the "say" field in this language.

            User request: "${input.userRequest}"
            App: ${input.map.appPackage ?: "unknown"}
            Screen: ${input.map.screenTitle ?: "unknown"}

            Visible text IDs:
            $visible

            Interactive element IDs:
            $interactive

            OCR IDs:
            $ocr
        """.trimIndent()
    }

    private fun normalizeLanguage(language: String?): String {
        return when (language?.trim()?.lowercase()) {
            "it", "it-it" -> "Italian"
            "en", "en-us" -> "English"
            else -> "English"
        }
    }
}

