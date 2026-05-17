package com.example.assistant.app.session

import com.example.assistant.core.model.RectBounds

sealed interface AssistantUiState {
    data object Dismissed : AssistantUiState
    data object Greeting : AssistantUiState
    data object Listening : AssistantUiState
    data object Processing : AssistantUiState
    data class Responding(
        val spokenText: String,
        val targetBounds: RectBounds?
    ) : AssistantUiState

    data class Error(val message: String) : AssistantUiState
}
