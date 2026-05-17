package com.example.assistant.app.session

import kotlinx.coroutines.flow.StateFlow

interface AssistantSessionController {
    val state: StateFlow<AssistantUiState>
    fun activate()
    fun onSpeechRecognized(text: String)
    fun onSpeechRecognitionError(message: String, isSilence: Boolean)
    fun onTtsFinished()
    fun stopAndDismiss()
}
