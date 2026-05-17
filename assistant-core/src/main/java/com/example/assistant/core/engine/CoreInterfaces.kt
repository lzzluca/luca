package com.example.assistant.core.engine

import com.example.assistant.core.model.GuidanceResult
import com.example.assistant.core.model.NormalizedBox
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ConversationTurn
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.ToolRequest
import com.example.assistant.core.model.UiTreeSnapshot
import com.example.assistant.core.model.UserQuestion

interface AssistantEngine {
    suspend fun answerQuestion(question: UserQuestion): GuidanceResult

    suspend fun answerQuestion(
        question: UserQuestion,
        observationRequest: ObservationRequest
    ): GuidanceResult = answerQuestion(question)
}

interface ObservationProvider {
    suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation
}

enum class ObservationMode {
    NONE,
    SCREENSHOT,
    SCREEN_MAP_FIRST
}

data class ObservationRequest(
    val mode: ObservationMode,
    val reason: String
)

sealed interface QuestionIntent {
    data object ScreenQuestion : QuestionIntent
    data object AssistantControl : QuestionIntent
    data object GeneralHelp : QuestionIntent
    data object Unknown : QuestionIntent
}

interface QuestionIntentClassifier {
    fun classify(question: UserQuestion): QuestionIntent
}

interface ObservationPlanner {
    fun plan(question: UserQuestion, latestUiTree: UiTreeSnapshot?): ObservationRequest
}

interface Reasoner {
    suspend fun reason(input: ReasonerInput): ReasonerOutput
}

data class ReasonerInput(
    val question: String,
    val observation: ScreenObservation,
    val instructions: String,
    val interactionLanguage: String? = null,
    val conversationHistory: List<ConversationTurn> = emptyList()
)

data class ReasonerOutput(
    val summary: String,
    val spokenText: String,
    val rationale: String?,
    val targetNodeId: String?,
    val targetBounds: RectBounds?,
    val targetNormalizedBox: NormalizedBox?,
    val targetLabel: String?,
    val targetConfidence: Float?,
    val toolRequest: ToolRequest?,
    val answerConfidence: Float?,
    val visualConfidence: Float?,
    val compactTargetId: String? = null,
    val compactConfidence: Float? = null,
    val compactRawOutput: String? = null,
    val compactParsedOutput: String? = null,
    val compactOutputDegradationReason: String? = null,
    val compactPromptIncludesInteractionLanguage: Boolean? = null,
    val compactPromptCharCount: Int? = null
)
