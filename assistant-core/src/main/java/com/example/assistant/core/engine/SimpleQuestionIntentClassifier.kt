package com.example.assistant.core.engine

import com.example.assistant.core.model.UserQuestion

/**
 * M1 legacy/fallback classifier.
 *
 * After M2B, transcript routing ownership belongs to the pre-screen router.
 * This classifier remains only for backward compatibility paths.
 */
class SimpleQuestionIntentClassifier : QuestionIntentClassifier {
    override fun classify(question: UserQuestion): QuestionIntent {
        val text = question.text.lowercase().trim()

        val screenKeywords = listOf(
            "what is on this screen",
            "where do i press",
            "where is",
            "is this safe",
            "what should i do here",
            "login button",
            "this screen"
        )
        if (screenKeywords.any { text.contains(it) }) return QuestionIntent.ScreenQuestion

        val controlKeywords = listOf(
            "repeat",
            "stop",
            "speak",
            "move to",
            "move your bubble"
        )
        if (controlKeywords.any { text.contains(it) }) return QuestionIntent.AssistantControl

        val helpKeywords = listOf(
            "what can you do",
            "help",
            "thank you"
        )
        if (helpKeywords.any { text.contains(it) }) return QuestionIntent.GeneralHelp

        return QuestionIntent.Unknown
    }
}
