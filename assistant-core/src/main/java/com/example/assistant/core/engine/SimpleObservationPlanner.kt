package com.example.assistant.core.engine

import com.example.assistant.core.model.UiTreeSnapshot
import com.example.assistant.core.model.UserQuestion

/**
 * M1 legacy/fallback observation planner.
 *
 * After M2B, the pre-screen router owns transcript routing and screen-context decisions.
 * Keep this planner only for backward compatibility paths.
 */
class SimpleObservationPlanner(
    private val classifier: QuestionIntentClassifier = SimpleQuestionIntentClassifier()
) : ObservationPlanner {

    override fun plan(question: UserQuestion, latestUiTree: UiTreeSnapshot?): ObservationRequest {
        return when (classifier.classify(question)) {
            QuestionIntent.ScreenQuestion,
            QuestionIntent.Unknown -> ObservationRequest(
                mode = ObservationMode.SCREENSHOT,
                reason = "question appears to require screen context"
            )

            QuestionIntent.AssistantControl,
            QuestionIntent.GeneralHelp -> ObservationRequest(
                mode = ObservationMode.NONE,
                reason = "assistant control/general help; no screen observation needed"
            )
        }
    }
}
