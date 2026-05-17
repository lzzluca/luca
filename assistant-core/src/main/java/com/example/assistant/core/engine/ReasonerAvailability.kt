package com.example.assistant.core.engine

interface ReasonerAvailabilityChecker {
    suspend fun check(): ReasonerAvailability
}

sealed interface ReasonerAvailability {
    data object Available : ReasonerAvailability
    data class Unavailable(
        val userMessage: String,
        val technicalReason: String?
    ) : ReasonerAvailability
}

