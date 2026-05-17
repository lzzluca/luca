package com.example.assistant.core.engine

import com.example.assistant.core.model.UserQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleObservationPlannerTest {

    private val planner = SimpleObservationPlanner()

    @Test
    fun plan_screenQuestion_usesScreenshot() {
        val request = planner.plan(
            UserQuestion("What is on this screen?", System.currentTimeMillis()),
            latestUiTree = null
        )
        assertEquals(ObservationMode.SCREENSHOT, request.mode)
    }

    @Test
    fun plan_assistantControl_usesNone() {
        val request = planner.plan(
            UserQuestion("Move to the top right", System.currentTimeMillis()),
            latestUiTree = null
        )
        assertEquals(ObservationMode.NONE, request.mode)
    }

    @Test
    fun plan_generalHelp_usesNone() {
        val request = planner.plan(
            UserQuestion("What can you do?", System.currentTimeMillis()),
            latestUiTree = null
        )
        assertEquals(ObservationMode.NONE, request.mode)
    }

    @Test
    fun plan_unknown_defaultsToScreenshot() {
        val request = planner.plan(
            UserQuestion("blabla random utterance", System.currentTimeMillis()),
            latestUiTree = null
        )
        assertEquals(ObservationMode.SCREENSHOT, request.mode)
    }
}

