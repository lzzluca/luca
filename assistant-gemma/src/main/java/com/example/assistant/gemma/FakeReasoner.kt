package com.example.assistant.gemma

import com.example.assistant.core.engine.Reasoner
import com.example.assistant.core.engine.ReasonerInput
import com.example.assistant.core.engine.ReasonerOutput
import com.example.assistant.core.model.NormalizedBox

class FakeReasoner : Reasoner {
    override suspend fun reason(input: ReasonerInput): ReasonerOutput {
        return ReasonerOutput(
            summary = "Fake screen summary for testing",
            spokenText = "Press the dot I'm pointing at. Is there anything else I can help you with?",
            rationale = "Fake rationale",
            targetNodeId = null,
            targetBounds = null,
            targetNormalizedBox = NormalizedBox(
                top = 800,
                left = 350,
                bottom = 900,
                right = 650
            ),
            targetLabel = "fake target",
            targetConfidence = 0.8f,
            toolRequest = null,
            answerConfidence = 0.8f,
            visualConfidence = 0.8f
        )
    }
}
