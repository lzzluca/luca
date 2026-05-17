package com.example.assistant.app.session

import com.example.assistant.gemma.TextOnlyRouteModel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Manual/eval harness for inspecting real pre-screen router behavior.
 *
 * Usage example:
 * ./gradlew :app:testDebugUnitTest --tests com.example.assistant.app.session.GemmaTextOnlyRouterManualEvalHarnessTest
 * -Drouter.eval.enabled=true
 *
 * Replace [NoopModel] with the real Gemma-backed model wiring in a debug-only environment
 * when LiteRT-LM runtime/model files are available.
 */
class GemmaTextOnlyRouterManualEvalHarnessTest {

    @Test
    fun evaluate_required_utterances_and_print_router_debug_fields() = runTest {
        if (System.getProperty("router.eval.enabled") != "true") return@runTest

        val router = GemmaIntentRouter(model = NoopModel())
        val utterances = listOf(
            "puoi parlare italiano?",
            "can you speak english?",
            "move on the top right",
            "move to the top right",
            "describe what's on the screen",
            "what is on this screen?"
        )

        utterances.forEach { utterance ->
            val route = router.route(
                IntentRouterInput(
                    transcript = utterance,
                    interactionLanguage = "en",
                    currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
                )
            )

            println("=== ROUTER EVAL ===")
            println("stt_transcript=$utterance")
            println("activeIntentRouter=${router.routerName}")
            println("preScreenFallbackRouterUsed=false")
            println("prompt=${router.latestPrompt}")
            println("raw_output=${router.latestRawOutput}")
            println("parsed_route=${router.latestParsedRoute}")
            println("degradation_reason=${router.latestDegradationReason}")
            println("final_route=$route")
        }
    }

    private class NoopModel : TextOnlyRouteModel {
        override suspend fun generateRouteJson(prompt: String): String {
            return """{"route":"clarification","spoken_text":"manual harness placeholder output","confidence":1.0}"""
        }
    }
}

