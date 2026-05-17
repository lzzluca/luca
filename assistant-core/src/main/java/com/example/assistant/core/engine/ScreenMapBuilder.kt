package com.example.assistant.core.engine

import com.example.assistant.core.model.CompactScreenMap
import com.example.assistant.core.model.CompactScreenMapItem
import com.example.assistant.core.model.CompactScreenMapRegistryEntry
import com.example.assistant.core.model.OcrTextFragment
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.TargetSource
import com.example.assistant.core.model.UiNode
import com.example.assistant.core.model.UiTreeSnapshot

class ScreenMapBuilder {
    data class BuildResult(
        val map: CompactScreenMap
    )

    fun build(
        packageName: String?,
        screenTitle: String?,
        uiTree: UiTreeSnapshot?,
        ocr: List<OcrTextFragment>
    ): BuildResult {
        val textItems = mutableListOf<CompactScreenMapItem>()
        val interactiveItems = mutableListOf<CompactScreenMapItem>()
        val ocrItems = mutableListOf<CompactScreenMapItem>()
        val registry = linkedMapOf<String, CompactScreenMapRegistryEntry>()

        var e = 1
        var t = 1
        var o = 1

        val seenText = linkedSetOf<String>()
        val seenInteractive = linkedSetOf<String>()

        uiTree?.flattened.orEmpty().forEach { node ->
            val nodeText = bestNodeText(node)
            if (!nodeText.isNullOrBlank() && !isContainerNoise(node)) {
                val normalized = normalizeText(nodeText)
                if (seenText.add(normalized)) {
                    val id = "T${t++}"
                    textItems += CompactScreenMapItem(id, nodeText.trim())
                    registry[id] = CompactScreenMapRegistryEntry(TargetSource.UI_TREE, node.bounds)
                }
            }
            if (node.clickable && node.enabled && !isContainerNoise(node)) {
                val label = nodeText?.trim().takeUnless { it.isNullOrBlank() }
                    ?: node.contentDescription?.trim().takeUnless { it.isNullOrBlank() }
                    ?: node.className?.substringAfterLast('.')?.trim().orEmpty()
                if (label.isNotBlank()) {
                    val normalized = normalizeText(label)
                    if (seenInteractive.add(normalized)) {
                        val id = "E${e++}"
                        interactiveItems += CompactScreenMapItem(id, label)
                        registry[id] = CompactScreenMapRegistryEntry(TargetSource.UI_TREE, node.bounds)
                    }
                }
            }
        }

        val mergedText = (textItems.map { normalizeText(it.label) } + interactiveItems.map { normalizeText(it.label) }).toSet()
        ocr.forEach { frag ->
            val cleaned = frag.text.trim()
            if (cleaned.isBlank()) return@forEach
            val normalized = normalizeText(cleaned)
            if (normalized in mergedText) return@forEach
            if (ocrItems.any { normalizeText(it.label) == normalized }) return@forEach
            val id = "O${o++}"
            ocrItems += CompactScreenMapItem(id, cleaned)
            registry[id] = CompactScreenMapRegistryEntry(TargetSource.OCR, frag.bounds)
        }

        val map = CompactScreenMap(
            appPackage = packageName,
            screenTitle = screenTitle,
            visibleText = textItems,
            interactive = interactiveItems,
            ocr = ocrItems,
            idRegistry = registry
        )

        return BuildResult(map = map)
    }

    private fun bestNodeText(node: UiNode): String? =
        node.text?.takeUnless { it.isBlank() } ?: node.contentDescription?.takeUnless { it.isBlank() }

    private fun isContainerNoise(node: UiNode): Boolean {
        val cls = node.className?.substringAfterLast('.')?.lowercase().orEmpty()
        if (cls.contains("layout") || cls.contains("group") || cls.contains("container") || cls == "view") {
            return node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()
        }
        return false
    }

    private fun normalizeText(value: String): String = value.trim().lowercase().replace("\\s+".toRegex(), " ")
}
