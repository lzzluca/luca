package com.example.assistant.core.engine

import com.example.assistant.core.model.UserQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleQuestionIntentClassifierTest {

    private val classifier = SimpleQuestionIntentClassifier()

    @Test
    fun classify_screenQuestion() {
        val intent = classifier.classify(UserQuestion("Where is the login button?", 1L))
        assertEquals(QuestionIntent.ScreenQuestion, intent)
    }

    @Test
    fun classify_assistantControl() {
        val intent = classifier.classify(UserQuestion("Speak Italian", 1L))
        assertEquals(QuestionIntent.AssistantControl, intent)
    }

    @Test
    fun classify_generalHelp() {
        val intent = classifier.classify(UserQuestion("What can you do?", 1L))
        assertEquals(QuestionIntent.GeneralHelp, intent)
    }

    @Test
    fun classify_unknown() {
        val intent = classifier.classify(UserQuestion("abracadabra", 1L))
        assertEquals(QuestionIntent.Unknown, intent)
    }
}

