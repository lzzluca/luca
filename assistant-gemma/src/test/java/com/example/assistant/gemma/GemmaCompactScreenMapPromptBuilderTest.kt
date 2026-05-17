package com.example.assistant.gemma

import com.example.assistant.core.model.CompactScreenMap
import com.example.assistant.core.model.CompactScreenMapItem
import com.example.assistant.core.model.CompactScreenMapRegistryEntry
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.TargetSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaCompactScreenMapPromptBuilderTest {

    private fun sampleMap(): CompactScreenMap {
        return CompactScreenMap(
            appPackage = "com.example.app",
            screenTitle = "Home",
            visibleText = listOf(CompactScreenMapItem("T1", "Cerca")),
            interactive = listOf(CompactScreenMapItem("E1", "Pulsante Cerca")),
            ocr = listOf(CompactScreenMapItem("O1", "Testo OCR")),
            idRegistry = mapOf(
                "E1" to CompactScreenMapRegistryEntry(TargetSource.UI_TREE, RectBounds(1, 2, 3, 4))
            )
        )
    }

    @Test
    fun build_includesInteractionLanguage_andItalianInstruction() {
        val prompt = GemmaCompactScreenMapPromptBuilder.build(
            GemmaCompactScreenMapPromptInput(
                userRequest = "dove premo per cercare?",
                interactionLanguage = "it-IT",
                map = sampleMap()
            )
        )

        assertTrue(prompt.contains("Interaction language: Italian"))
        assertTrue(prompt.contains("Write the \"say\" field in this language."))
    }

    @Test
    fun build_containsRequiredCompactSections_andNoCoordinatesOrUiTreeDump() {
        val prompt = GemmaCompactScreenMapPromptBuilder.build(
            GemmaCompactScreenMapPromptInput(
                userRequest = "where do I press to search?",
                interactionLanguage = "en-US",
                map = sampleMap()
            )
        )

        assertTrue(prompt.contains("User request:"))
        assertTrue(prompt.contains("App:"))
        assertTrue(prompt.contains("Screen:"))
        assertTrue(prompt.contains("Visible text IDs:"))
        assertTrue(prompt.contains("Interactive element IDs:"))
        assertTrue(prompt.contains("OCR IDs:"))
        assertFalse(prompt.contains("[1, 2, 3, 4]"))
        assertFalse(prompt.contains("raw ui tree" , ignoreCase = true))
        assertFalse(prompt.contains("tool_request"))
    }
}
