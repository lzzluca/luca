package com.example.assistant.core.model

data class UserQuestion(
    val text: String,
    val timestampMs: Long,
    val interactionLanguage: String? = null,
    val conversationHistory: List<ConversationTurn> = emptyList()
)

data class ConversationTurn(
    val role: ConversationRole,
    val text: String,
    val timestampMs: Long
)

enum class ConversationRole {
    USER,
    ASSISTANT
}

data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class NormalizedBox(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int
)

data class ScreenshotFrame(
    val width: Int,
    val height: Int,
    val mimeType: String,
    val bytes: ByteArray,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null
)

data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val bounds: RectBounds,
    val clickable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val children: List<UiNode>
)

data class UiTreeSnapshot(
    val root: UiNode?,
    val flattened: List<UiNode>
)

data class ScreenObservation(
    val packageName: String?,
    val screenTitle: String?,
    val screenshot: ScreenshotFrame?,
    val uiTree: UiTreeSnapshot?,
    val ocrText: List<OcrTextFragment> = emptyList(),
    val screenMap: CompactScreenMap? = null,
    val screenMapBuildMs: Long? = null,
    val compactScreenMapPromptCharCount: Int? = null,
    val capturedAtMs: Long
)

data class OcrTextFragment(
    val text: String,
    val bounds: RectBounds
)

data class CompactScreenMap(
    val appPackage: String?,
    val screenTitle: String?,
    val visibleText: List<CompactScreenMapItem>,
    val interactive: List<CompactScreenMapItem>,
    val ocr: List<CompactScreenMapItem>,
    val idRegistry: Map<String, CompactScreenMapRegistryEntry>
)

data class CompactScreenMapItem(
    val id: String,
    val label: String
)

data class CompactScreenMapRegistryEntry(
    val source: TargetSource,
    val bounds: RectBounds
)

enum class TargetSource {
    SCREENSHOT,
    UI_TREE,
    OCR,
    SCREEN_MAP,
    INFERRED
}

object AssistantTools {
    const val UPDATE_TRIGGER_BUTTON = "update_trigger_button"
    const val UPDATE_INTERACTION_LANGUAGE = "update_interaction_language"
    const val OPEN_PLAY_STORE_AND_SEARCH = "open_play_store_and_search"
    const val MOVE_OVERLAY = "move_overlay"

    val allowed = setOf(
        UPDATE_TRIGGER_BUTTON,
        UPDATE_INTERACTION_LANGUAGE,
        OPEN_PLAY_STORE_AND_SEARCH,
        MOVE_OVERLAY
    )
}

data class ToolRequest(
    val name: String,
    val arguments: Map<String, String>
)

data class GuidanceTarget(
    val source: TargetSource,
    val nodeId: String?,
    val bounds: RectBounds?,
    val normalizedBox: NormalizedBox?,
    val label: String?,
    val targetConfidence: Float?
)

data class GuidanceResult(
    val summary: String,
    val spokenText: String,
    val rationale: String?,
    val target: GuidanceTarget?,
    val toolRequest: ToolRequest?,
    val answerConfidence: Float?,
    val visualConfidence: Float?
)
