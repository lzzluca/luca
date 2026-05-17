package com.example.assistant.app.session

import com.example.assistant.core.model.ToolRequest

data class ToolExecutionResult(
    val success: Boolean,
    val spokenTextOverride: String? = null,
    val debugMessage: String
)

interface ToolExecutor {
    suspend fun execute(request: ToolRequest): ToolExecutionResult
}

