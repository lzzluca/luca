package com.example.assistant.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.android.speech.SpeechOutput
import com.example.assistant.android.speech.SpeechLanguageUpdateResult
import com.example.assistant.app.Prefs
import com.example.assistant.core.engine.DefaultAssistantEngine
import com.example.assistant.core.engine.ObservationMode
import com.example.assistant.core.engine.ObservationProvider
import com.example.assistant.core.engine.Reasoner
import com.example.assistant.core.engine.ReasonerInput
import com.example.assistant.core.engine.ReasonerOutput
import com.example.assistant.core.engine.SimpleObservationPlanner
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ScreenObservation
import com.example.assistant.core.model.ScreenshotFrame
import com.example.assistant.core.model.ToolRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultAssistantSessionControllerRealFlowRegressionTest {

    @Test
    fun speakItalian_variants_followRealFlow_withoutScreenshotOrHighlight_andExecuteLanguageTool() = runTest {
        val inputs = listOf("speak Italian", "Speak Italian", "speak italian", "SPEAK ITALIAN")
        for (text in inputs) {
            val env = newEnv(this)
            env.controller.onSpeechRecognized(text)
            advanceUntilIdle()

            assertTrue(env.reasoner.observationModes.isEmpty())
            assertEquals(0, env.observationProvider.calls)
            assertEquals(0, env.reasoner.calls)
            assertTrue(env.reasoner.screenshotPresence.isEmpty())

            assertEquals(1, env.toolExecutor.calls)
            val request = env.toolExecutor.requests.single()
            assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, request.name)
            assertEquals("it", request.arguments["language"])

            assertEquals("it", env.prefs.getInteractionLanguage())
            assertEquals(listOf("it"), env.speech.languageSetterCalls)
            assertTrue(env.highlighter.nonNullTargets.isEmpty())
        }
    }

    @Test
    fun moveOverlay_variants_followRealFlow_doNotExecuteMoveTool() = runTest {
        val inputs = mapOf(
            "move to the top left" to "top_left",
            "move to the top right" to "top_right",
            "move to the bottom left" to "bottom_left",
            "move to the bottom right" to "bottom_right",
            "move to the center" to "center"
        )

        for ((text, expectedPosition) in inputs) {
            val env = newEnv(this)
            env.controller.onSpeechRecognized(text)
            advanceUntilIdle()

            assertEquals(0, env.toolExecutor.calls)
            assertTrue(env.toolExecutor.requests.isEmpty())
            assertTrue(env.highlighter.nonNullTargets.isEmpty())
        }
    }

    @Test
    fun screenQuestion_controls_stillUseScreenshotPath() = runTest {
        val inputs = listOf(
            "what is on this screen?",
            "where do I press?",
            "what is the weather widget showing?",
            "where is the login button?"
        )
        for (text in inputs) {
            val env = newEnv(this)
            env.controller.onSpeechRecognized(text)
            advanceUntilIdle()

            assertEquals(1, env.observationProvider.calls)
            assertEquals("SCREENSHOT", env.reasoner.observationModes.single().name)
            assertTrue(env.reasoner.screenshotPresence.single())
            assertTrue(env.reasoner.calls >= 1)
            assertEquals(0, env.toolExecutor.calls)
        }
    }

    private fun newEnv(scope: TestScope): TestEnv {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val observationProvider = RecordingObservationProvider()
        val reasoner = RoutingReasoner()
        val engine = DefaultAssistantEngine(
            context = context,
            planner = SimpleObservationPlanner(),
            observationProvider = observationProvider,
            reasoner = reasoner
        )
        val speech = RecordingSpeechOutput()
        val toolExecutor = RecordingToolExecutor(prefs = prefs, speech = speech)
        val highlighter = RecordingHighlighter()

        val controller = DefaultAssistantSessionController(
            assistantEngine = engine,
            speechOutput = speech,
            onStartListening = {},
            onStopListening = {},
            onShowTarget = highlighter::onShow,
            onClearTarget = highlighter::onClear,
            toolExecutor = toolExecutor,
            scope = scope
        )

        return TestEnv(controller, observationProvider, reasoner, toolExecutor, highlighter, speech, prefs)
    }

    private data class TestEnv(
        val controller: DefaultAssistantSessionController,
        val observationProvider: RecordingObservationProvider,
        val reasoner: RoutingReasoner,
        val toolExecutor: RecordingToolExecutor,
        val highlighter: RecordingHighlighter,
        val speech: RecordingSpeechOutput,
        val prefs: Prefs
    )

    private class RecordingObservationProvider : ObservationProvider {
        var calls: Int = 0
        override suspend fun getCurrentObservation(request: com.example.assistant.core.engine.ObservationRequest): ScreenObservation {
            calls++
            return ScreenObservation(
                packageName = "pkg",
                screenTitle = "title",
                screenshot = ScreenshotFrame(
                    width = 100,
                    height = 100,
                    mimeType = "image/png",
                    bytes = byteArrayOf(1, 2, 3),
                    sourceWidth = 100,
                    sourceHeight = 100
                ),
                uiTree = null,
                capturedAtMs = 1L
            )
        }
    }

    private class RoutingReasoner : Reasoner {
        var calls: Int = 0
        val observationModes = mutableListOf<ObservationMode>()
        val screenshotPresence = mutableListOf<Boolean>()

        override suspend fun reason(input: ReasonerInput): ReasonerOutput {
            calls++
            val mode = if (input.observation.screenshot == null) ObservationMode.NONE else ObservationMode.SCREENSHOT
            observationModes += mode
            screenshotPresence += (input.observation.screenshot != null)

            val t = input.question.lowercase().trim()
            val tool = when {
                t == "speak italian" -> ToolRequest(
                    name = AssistantTools.UPDATE_INTERACTION_LANGUAGE,
                    arguments = mapOf("language" to "it")
                )

                t.startsWith("move to the ") -> {
                    val pos = t.removePrefix("move to the ")
                    val mapped = when (pos) {
                        "top left" -> "top_left"
                        "top right" -> "top_right"
                        "bottom left" -> "bottom_left"
                        "bottom right" -> "bottom_right"
                        "center" -> "center"
                        else -> null
                    }
                    mapped?.let {
                        ToolRequest(
                            name = AssistantTools.MOVE_OVERLAY,
                            arguments = mapOf("position" to it)
                        )
                    }
                }

                else -> null
            }

            val isScreenQuestion = t.contains("what is on this screen") ||
                t.contains("where do i press") ||
                t.contains("weather widget") ||
                t.contains("login button")

            return ReasonerOutput(
                summary = "summary",
                spokenText = "spoken",
                rationale = null,
                targetNodeId = null,
                targetBounds = if (isScreenQuestion) RectBounds(10, 10, 30, 30) else null,
                targetNormalizedBox = null,
                targetLabel = if (isScreenQuestion) "target" else null,
                targetConfidence = if (isScreenQuestion) 0.9f else 0f,
                toolRequest = tool,
                answerConfidence = 0.9f,
                visualConfidence = if (isScreenQuestion) 0.9f else 0f
            )
        }
    }

    private class RecordingToolExecutor(
        private val prefs: Prefs,
        private val speech: RecordingSpeechOutput
    ) : ToolExecutor {
        var calls: Int = 0
        val requests = mutableListOf<ToolRequest>()

        override suspend fun execute(request: ToolRequest): ToolExecutionResult {
            calls++
            requests += request
            return when (request.name) {
                AssistantTools.UPDATE_INTERACTION_LANGUAGE -> {
                    val tag = request.arguments["language"].orEmpty()
                    prefs.setInteractionLanguage(tag)
                    speech.setLanguage(tag)
                    ToolExecutionResult(success = true, debugMessage = "tool_ok:update_interaction_language")
                }

                AssistantTools.MOVE_OVERLAY -> {
                    val position = request.arguments["position"].orEmpty()
                    setLastOverlayPosition(prefs, position)
                    ToolExecutionResult(success = true, debugMessage = "tool_ok:move_overlay")
                }

                else -> ToolExecutionResult(success = false, debugMessage = "unsupported")
            }
        }
    }

    private class RecordingSpeechOutput : SpeechOutput {
        val spoken = mutableListOf<String>()
        val languageSetterCalls = mutableListOf<String>()

        override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
            spoken += text
            onDone?.invoke()
        }

        override fun stop() = Unit

        override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult {
            languageSetterCalls += languageTag
            return SpeechLanguageUpdateResult(
                requestedLanguageTag = languageTag,
                requestedTtsLocale = languageTag,
                ttsSetLanguageResult = 0,
                success = true,
                activeTtsLocale = languageTag,
                activeTtsVoice = "recording-voice"
            )
        }

        override fun shutdown() = Unit
    }

    private class RecordingHighlighter {
        val shownTargets = mutableListOf<RectBounds?>()
        val nonNullTargets = mutableListOf<RectBounds>()

        fun onShow(target: RectBounds?) {
            shownTargets += target
            if (target != null) nonNullTargets += target
        }

        fun onClear() = Unit
    }

}

private fun setLastOverlayPosition(prefsObj: Prefs, value: String) {
    val field = Prefs::class.java.getDeclaredField("prefs")
    field.isAccessible = true
    val sharedPrefs = field.get(prefsObj) as android.content.SharedPreferences
    sharedPrefs.edit().putString("test_last_overlay_position", value).apply()
}

private fun getLastOverlayPosition(prefsObj: Prefs): String? {
    val field = Prefs::class.java.getDeclaredField("prefs")
    field.isAccessible = true
    val sharedPrefs = field.get(prefsObj) as android.content.SharedPreferences
    return sharedPrefs.getString("test_last_overlay_position", null)
}
