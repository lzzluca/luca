package com.example.assistant.app.session

import com.example.assistant.gemma.TextOnlyRouteModel
import com.example.assistant.gemma.TextOnlyRouteModelLifecycleDiagnostics
import com.example.assistant.gemma.TextOnlyRouterInitializationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaIntentRouterLifecycleTest {

    @Test
    fun route_waitsForModelInitializationCompletion_beforeReturningRoute() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = AwaitingInitModel(gate)
        val router = GemmaIntentRouter(model = model)

        val deferredRoute = async {
            router.route(
                IntentRouterInput(
                    transcript = "describe the screen",
                    interactionLanguage = "en",
                    currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
                )
            )
        }

        assertFalse(deferredRoute.isCompleted)
        gate.complete(Unit)
        val route = deferredRoute.await()

        assertTrue(model.generateCalled.get())
        assertTrue(route is IntentRoute.ScreenContext)
    }

    @Test
    fun route_onInitializationFailure_surfacesRouterUnavailableDiagnostics_withoutGenericClarification() = runTest {
        val model = FailingInitModel()
        val router = GemmaIntentRouter(model = model)

        val route = router.route(
            IntentRouterInput(
                transcript = "speak italian",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )

        assertTrue(route is IntentRoute.Clarification)
        val clarification = route as IntentRoute.Clarification
        assertEquals("Text router unavailable: initialization failed.", clarification.spokenText)
        assertEquals("text_router_unavailable_initialization_failed", router.latestDegradationReason)
        assertEquals("failed", router.latestModelInitializationStatus)
        assertTrue(router.latestModelInitializationFailure?.contains("diagnostic_label=null_engine") == true)
        assertTrue(router.latestModelInitializationFailure?.contains("target_exception_message=Engine is not initialized.") == true)
        assertTrue(router.latestException?.contains("TextOnlyRouterInitializationException") == true)
    }

    @Test
    fun route_afterInitializationFailure_doesNotProduceRawOutput_orParsedRoute() = runTest {
        val model = FailingInitModel()
        val router = GemmaIntentRouter(model = model)

        router.route(
            IntentRouterInput(
                transcript = "speak italian",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )

        assertEquals(1, model.generateAttempts)
        assertEquals(null, router.latestRawOutput)
        assertEquals(null, router.latestParsedRoute)
    }

    private class AwaitingInitModel(
        private val gate: CompletableDeferred<Unit>
    ) : TextOnlyRouteModel {
        val generateCalled = AtomicBoolean(false)

        override suspend fun generateRouteJson(prompt: String): String {
            generateCalled.set(true)
            gate.await()
            return """{"route":"screen_context","screen_context":{"mode":"screenshot","purpose":"screen_explanation"},"confidence":0.9}"""
        }
    }

    private class FailingInitModel : TextOnlyRouteModel, TextOnlyRouteModelLifecycleDiagnostics {
        var generateAttempts: Int = 0
            private set

        override val initializationStatus: String
            get() = "failed"

        override val initializationFailureDiagnostics: String?
            get() = "diagnostic_label=null_engine\ntarget_exception_message=Engine is not initialized."

        override suspend fun generateRouteJson(prompt: String): String {
            generateAttempts += 1
            throw TextOnlyRouterInitializationException(
                message = "Text-only router initialization failed",
                diagnostics = initializationFailureDiagnostics
            )
        }
    }
}
