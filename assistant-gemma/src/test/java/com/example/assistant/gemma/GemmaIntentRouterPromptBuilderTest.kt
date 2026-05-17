package com.example.assistant.gemma

import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaIntentRouterPromptBuilderTest {

    @Test
    fun build_compactPromptAndSchemaRemainUnchanged() {
        val prompt = GemmaIntentRouterPromptBuilder.build(
            GemmaIntentRouterPromptInput(
                transcript = "describe what's on the screen",
                interactionLanguage = "en-US",
                triggerButton = "volume_up",
                previousAssistantSummary = "last route was screen"
            )
        )

        assertTrue(prompt.contains("Router for a transcript-only phone accessibility assistant. JSON only. One object only."))
        assertTrue(prompt.contains("{\"r\":\"tool\",\"t\":\"language\",\"a\":{\"l\":\"it|en\"}}"))
        assertTrue(prompt.contains("{\"r\":\"tool\",\"t\":\"trigger\",\"a\":{\"b\":\"volume_up|volume_down\"}}"))
        assertTrue(prompt.contains("{\"r\":\"tool\",\"t\":\"app\",\"a\":{\"name\":\"App Name\"}}"))
        assertTrue(prompt.contains("{\"r\":\"screen\"}"))
        assertTrue(prompt.contains("{\"r\":\"answer\",\"text\":\"...\"}"))
        assertTrue(prompt.contains("\"find YouTube\""))
        assertTrue(prompt.contains("\"cerca YouTube\""))
        assertTrue(prompt.contains("\"apri Spotify\""))
        assertTrue(prompt.contains("- interaction_language: en-US"))
        assertTrue(prompt.contains("- trigger_button: volume_up"))
        assertTrue(prompt.contains("- previous_summary: last route was screen"))
        assertTrue(prompt.contains("Transcript: describe what's on the screen"))
        assertTrue(!prompt.contains("\"t\":\"move\""))
        assertTrue(!prompt.contains("move to the top right"))
        assertTrue(!prompt.contains("top_right"))
    }
}
