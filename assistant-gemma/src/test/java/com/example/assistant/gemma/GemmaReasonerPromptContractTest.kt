package com.example.assistant.gemma

import com.example.assistant.core.engine.ReasonerInput
import com.example.assistant.core.model.ConversationRole
import com.example.assistant.core.model.ConversationTurn
import com.example.assistant.core.model.ScreenObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaReasonerPromptContractTest {

    private val testConfig = GemmaConfig(modelPath = "/tmp/gemma.bin")

    @Test
    fun buildPrompt_doesNotAdvertiseLocalTools_andForcesNullToolRequest() {
        val reasoner = GemmaReasoner(config = testConfig)
        val method = GemmaReasoner::class.java.getDeclaredMethod("buildPrompt", ReasonerInput::class.java)
        method.isAccessible = true
        val prompt = method.invoke(
            reasoner,
            ReasonerInput(
                question = "where do I press?",
                observation = ScreenObservation(
                    packageName = "com.example",
                    screenTitle = "Home",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 0L
                ),
                instructions = "Answer in en-US."
            )
        ) as String

        assertTrue(prompt.contains("\"tool_request\": null"))
        assertTrue(prompt.contains("tool_request must always be null"))
        assertFalse(prompt.contains("open_app_launcher_and_search"))
        assertFalse(prompt.contains("open_play_store_and_search"))
        assertFalse(prompt.contains("move_overlay"))
        assertFalse(prompt.contains("update_interaction_language"))
        assertFalse(prompt.contains("update_trigger_button"))
        assertFalse(prompt.contains("Is there anything else I can help you with?"))
        assertTrue(prompt.contains("Do not append session follow-up or closing phrases"))

        val instructionsCount = "Answer in en-US.".toRegex(RegexOption.LITERAL).findAll(prompt).count()
        assertEquals(1, instructionsCount)
    }

    @Test
    fun reason_noScreenshot_localizesFallback_forItalian() = runBlocking {
        val reasoner = GemmaReasoner(config = testConfig)
        val output = reasoner.reason(
            ReasonerInput(
                question = "cosa vedi?",
                observation = ScreenObservation(
                    packageName = "com.example",
                    screenTitle = "Home",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 0L
                ),
                instructions = "Answer in interaction_language=it-IT.",
                interactionLanguage = "it-IT"
            )
        )

        assertEquals("Non riesco a vedere lo schermo in questo momento.", output.spokenText)
        assertNull(output.toolRequest)
    }

    @Test
    fun reason_noScreenshot_localizesFallback_forEnglish() = runBlocking {
        val reasoner = GemmaReasoner(config = testConfig)
        val output = reasoner.reason(
            ReasonerInput(
                question = "what do you see?",
                observation = ScreenObservation(
                    packageName = "com.example",
                    screenTitle = "Home",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 0L
                ),
                instructions = "Answer in interaction_language=en-US.",
                interactionLanguage = "en-US"
            )
        )

        assertEquals("I can't see the screen right now.", output.spokenText)
        assertNull(output.toolRequest)
    }

    @Test
    fun buildPrompt_includesConversationHistory() {
        val reasoner = GemmaReasoner(config = testConfig)
        val method = GemmaReasoner::class.java.getDeclaredMethod("buildPrompt", ReasonerInput::class.java)
        method.isAccessible = true
        val prompt = method.invoke(
            reasoner,
            ReasonerInput(
                question = "where do I press?",
                observation = ScreenObservation(
                    packageName = "com.example",
                    screenTitle = "Home",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 0L
                ),
                instructions = "Answer in en-US.",
                conversationHistory = listOf(
                    ConversationTurn(ConversationRole.USER, "describe this screen", 1L),
                    ConversationTurn(ConversationRole.ASSISTANT, "Do you need something specific?", 2L),
                    ConversationTurn(ConversationRole.USER, "where should I tap", 3L)
                )
            )
        ) as String

        assertTrue(prompt.contains("- conversation_history:"))
        assertTrue(prompt.contains("user: describe this screen"))
        assertTrue(prompt.contains("assistant: Do you need something specific?"))
        assertTrue(prompt.contains("user: where should I tap"))
    }
}
