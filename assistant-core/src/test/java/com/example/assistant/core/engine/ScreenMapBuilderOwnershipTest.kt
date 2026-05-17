package com.example.assistant.core.engine

import com.example.assistant.core.model.OcrTextFragment
import com.example.assistant.core.model.RectBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScreenMapBuilderOwnershipTest {

    @Test
    fun screenMapBuilder_doesNotOwnGemmaPromptText() {
        val hasBuildPromptMethod = ScreenMapBuilder::class.java.declaredMethods.any { it.name == "buildPrompt" }
        assertFalse(hasBuildPromptMethod)
    }

    @Test
    fun screenMapBuilder_buildsDataOnly() {
        val builder = ScreenMapBuilder()
        val result = builder.build(
            packageName = "com.example",
            screenTitle = "Home",
            uiTree = null,
            ocr = listOf(OcrTextFragment("Cerca", RectBounds(1, 2, 3, 4)))
        )
        assertNotNull(result.map)
    }
}

