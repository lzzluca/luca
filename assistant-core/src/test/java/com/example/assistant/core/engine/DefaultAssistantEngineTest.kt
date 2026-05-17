package com.example.assistant.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.core.model.NormalizedBox
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.ScreenshotFrame
import com.example.assistant.core.model.ToolRequest
import com.example.assistant.core.model.ConversationRole
import com.example.assistant.core.model.ConversationTurn
import com.example.assistant.core.model.UserQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultAssistantEngineTest {

    @Test
    fun assistantControlObservationModeNone_doesNotCallObservationProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var providerCalled = false
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.NONE, "assistant_control")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                providerCalled = true
                throw IllegalStateException("ObservationProvider should not be called for NONE")
            }
        }
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput = baseOutput()
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        val result = runBlockingAnswer(engine)
        assertEquals(false, providerCalled)
        assertTrue(result.rationale?.contains("observation_mode=NONE") == true)
    }

    @Test
    fun generalHelpObservationModeNone_doesNotCallObservationProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var providerCalled = false
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.NONE, "general_help")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                providerCalled = true
                throw IllegalStateException("ObservationProvider should not be called for NONE")
            }
        }
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput = baseOutput()
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        runBlockingAnswer(engine)
        assertEquals(false, providerCalled)
    }

    private fun newEngine(reasonerOutput: ReasonerOutput): DefaultAssistantEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.NONE, "test")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                return ScreenObservation(
                    packageName = "pkg",
                    screenTitle = "title",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 1L
                )
            }
        }
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput = reasonerOutput
        }
        return DefaultAssistantEngine(context, planner, observationProvider, reasoner)
    }

    @Test
    fun resolveBounds_normalizedBox_convertsToPixels() {
        val engine = newEngine(baseOutput())
        val result = invokeResolveBounds(
            engine = engine,
            direct = null,
            normalized = NormalizedBox(top = 100, left = 200, bottom = 300, right = 400),
            sourceWidth = 1000,
            sourceHeight = 2000
        )
        assertNotNull(result)
        assertEquals(200, result?.left)
        assertEquals(200, result?.top)
        assertEquals(400, result?.right)
        assertEquals(600, result?.bottom)
    }

    @Test
    fun resolveBounds_invalidNormalizedBox_returnsNull() {
        val engine = newEngine(baseOutput())
        val result = invokeResolveBounds(
            engine = engine,
            direct = null,
            normalized = NormalizedBox(top = 700, left = 700, bottom = 600, right = 600),
            sourceWidth = 1000,
            sourceHeight = 2000
        )
        assertNull(result)
    }

    @Test
    fun resolveBounds_oversizedTarget_rejected() {
        val engine = newEngine(baseOutput())
        val result = invokeResolveBounds(
            engine = engine,
            direct = null,
            normalized = NormalizedBox(top = 0, left = 0, bottom = 1000, right = 1000),
            sourceWidth = 1000,
            sourceHeight = 2000
        )
        assertNull(result)
    }

    @Test
    fun answerQuestion_toolRequestPropagated_andConfidenceClamped() {
        val output = baseOutput().copy(
            toolRequest = ToolRequest("move_overlay", mapOf("position" to "top_right")),
            answerConfidence = 9f,
            visualConfidence = -2f
        )
        val engine = newEngine(output)
        val result = runBlockingAnswer(engine)
        assertNotNull(result.toolRequest)
        assertEquals("move_overlay", result.toolRequest?.name)
        assertEquals(1f, result.answerConfidence)
        assertEquals(0f, result.visualConfidence)
    }

    @Test
    fun screenshotRequested_butUnavailable_returnsSafeResult_withoutTargetOrTool() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.SCREENSHOT, "screen_question")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                return ScreenObservation(
                    packageName = "pkg",
                    screenTitle = "title",
                    screenshot = null,
                    uiTree = null,
                    capturedAtMs = 1L
                )
            }
        }
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                assertNull(input.observation.screenshot)
                return ReasonerOutput(
                    summary = "Sorry, I can't see your screen right now.",
                    spokenText = "Sorry, I can't see your screen right now.",
                    rationale = "no_screenshot",
                    targetNodeId = null,
                    targetBounds = null,
                    targetNormalizedBox = null,
                    targetLabel = null,
                    targetConfidence = 0f,
                    toolRequest = null,
                    answerConfidence = 0.3f,
                    visualConfidence = 0f
                )
            }
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        val result = runBlockingAnswer(engine)
        assertFalse(result.spokenText.isBlank())
        assertNull(result.target)
        assertNull(result.toolRequest)
        assertTrue(result.rationale?.contains("observation_mode=SCREENSHOT") == true)
        assertTrue(result.rationale?.contains("no_screenshot") == true)
    }

    @Test
    fun interactionLanguage_isPropagatedIntoReasonerInstructions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.NONE, "assistant_control")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                throw IllegalStateException("ObservationProvider should not be called for NONE")
            }
        }
        var capturedInstructions: String? = null
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                capturedInstructions = input.instructions
                return baseOutput()
            }
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        kotlinx.coroutines.runBlocking {
            engine.answerQuestion(UserQuestion("ciao", 1L, interactionLanguage = "it-IT"))
        }

        assertEquals("Answer spoken_text in interaction_language=it-IT.", capturedInstructions)
    }

    @Test
    fun explicitObservationRequest_bypassesPlanner_andUsesProvidedMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var plannerCalled = false
        var providerCalled = false
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                plannerCalled = true
                return ObservationRequest(ObservationMode.NONE, "legacy")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                providerCalled = true
                return ScreenObservation(
                    packageName = "pkg",
                    screenTitle = "title",
                    screenshot = ScreenshotFrame(
                        width = 100,
                        height = 100,
                        mimeType = "image/png",
                        bytes = byteArrayOf(1),
                        sourceWidth = 100,
                        sourceHeight = 100
                    ),
                    uiTree = null,
                    capturedAtMs = 1L
                )
            }
        }
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput = baseOutput()
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        kotlinx.coroutines.runBlocking {
            engine.answerQuestion(
                UserQuestion("where is the button", 1L),
                ObservationRequest(ObservationMode.SCREENSHOT, "m2b_route:ui_navigation")
            )
        }

        assertFalse(plannerCalled)
        assertTrue(providerCalled)
    }

    @Test
    fun conversationHistory_isPropagatedToReasonerInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val planner = object : ObservationPlanner {
            override fun plan(question: com.example.assistant.core.model.UserQuestion, latestUiTree: com.example.assistant.core.model.UiTreeSnapshot?): ObservationRequest {
                return ObservationRequest(ObservationMode.NONE, "assistant_control")
            }
        }
        val observationProvider = object : ObservationProvider {
            override suspend fun getCurrentObservation(request: ObservationRequest): ScreenObservation {
                throw IllegalStateException("ObservationProvider should not be called for NONE")
            }
        }
        var capturedHistory: List<ConversationTurn>? = null
        val reasoner = object : Reasoner {
            override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                capturedHistory = input.conversationHistory
                return baseOutput()
            }
        }

        val engine = DefaultAssistantEngine(context, planner, observationProvider, reasoner)
        val history = listOf(
            ConversationTurn(ConversationRole.USER, "describe the screen", 1L),
            ConversationTurn(ConversationRole.ASSISTANT, "Do you need anything specific?", 2L),
            ConversationTurn(ConversationRole.USER, "where should I tap?", 3L)
        )

        kotlinx.coroutines.runBlocking {
            engine.answerQuestion(
                UserQuestion(
                    text = "where should I tap?",
                    timestampMs = 4L,
                    conversationHistory = history
                )
            )
        }

        assertEquals(history, capturedHistory)
    }

    private fun baseOutput(): ReasonerOutput = ReasonerOutput(
        summary = "s",
        spokenText = "t",
        rationale = null,
        targetNodeId = null,
        targetBounds = null,
        targetNormalizedBox = null,
        targetLabel = null,
        targetConfidence = 0.8f,
        toolRequest = null,
        answerConfidence = 0.8f,
        visualConfidence = 0.8f
    )

    private fun runBlockingAnswer(engine: DefaultAssistantEngine) =
        kotlinx.coroutines.runBlocking {
            engine.answerQuestion(UserQuestion("hi", 1L))
        }

    @Suppress("UNCHECKED_CAST")
    private fun invokeResolveBounds(
        engine: DefaultAssistantEngine,
        direct: RectBounds?,
        normalized: NormalizedBox?,
        sourceWidth: Int?,
        sourceHeight: Int?
    ): RectBounds? {
        val m = DefaultAssistantEngine::class.java.getDeclaredMethod(
            "resolveBounds",
            RectBounds::class.java,
            NormalizedBox::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType
        )
        m.isAccessible = true
        return m.invoke(engine, direct, normalized, sourceWidth, sourceHeight) as RectBounds?
    }
}
