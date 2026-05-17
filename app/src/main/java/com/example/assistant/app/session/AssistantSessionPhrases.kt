package com.example.assistant.app.session

data class AssistantSessionPhrases(
    val greeting: String,
    val processingScreen: String,
    val screenshotUnavailable: String,
    val closing: String,
    val clarificationFallback: String,
    val done: String,
    val engineUnavailable: String,
    val hearingRetry: String,
    val cantdothat: String
) {
    companion object {
        private const val LANGUAGE_EN_US = "en-US"
        private const val LANGUAGE_IT_IT = "it-IT"

        fun forLanguage(interactionLanguage: String?): AssistantSessionPhrases {
            return when (normalize(interactionLanguage)) {
                LANGUAGE_IT_IT -> italian()
                else -> english()
            }
        }

        fun normalize(interactionLanguage: String?): String {
            return when (interactionLanguage?.trim()?.lowercase()) {
                "it", "it-it" -> LANGUAGE_IT_IT
                "en", "en-us" -> LANGUAGE_EN_US
                else -> LANGUAGE_EN_US
            }
        }

        private fun english() = AssistantSessionPhrases(
            greeting = "Hi, I'm Luca. How can I help you?",
            processingScreen = "I'm checking the screen...",
            screenshotUnavailable = "I can't see the screen right now.",
            closing = "Ok, talk to you soon!",
            clarificationFallback = "Can you clarify what you need?",
            done = "Done.",
            engineUnavailable = "Sorry, I can't help right now.",
            hearingRetry = "I couldn't hear that. Please try again.",
            cantdothat = "Sorry, I can't do that right now."
        )

        private fun italian() = AssistantSessionPhrases(
            greeting = "Ciao, sono Luca. Come posso aiutarti?",
            processingScreen = "Sto controllando lo schermo...",
            screenshotUnavailable = "Non riesco a vedere lo schermo in questo momento.",
            closing = "Va bene, a presto!",
            clarificationFallback = "Puoi chiarire di cosa hai bisogno?",
            done = "Fatto.",
            engineUnavailable = "Mi dispiace, non posso aiutare in questo momento.",
            hearingRetry = "Non sono riuscito a sentire. Per favore riprova.",
            cantdothat = "Mi dispiace, non posso farlo in questo momento."
        )
    }
}
