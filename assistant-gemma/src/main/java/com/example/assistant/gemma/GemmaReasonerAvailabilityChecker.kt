package com.example.assistant.gemma

import com.example.assistant.core.engine.ReasonerAvailability
import com.example.assistant.core.engine.ReasonerAvailabilityChecker

class GemmaReasonerAvailabilityChecker(
    private val reasoner: GemmaReasoner,
    private val requireStructuredOutput: Boolean
) : ReasonerAvailabilityChecker {

    override suspend fun check(): ReasonerAvailability {
        return reasoner.checkAvailability(requireStructuredOutput = requireStructuredOutput)
    }
}

