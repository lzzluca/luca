package com.example.assistant.app.session

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.app.Prefs
import com.example.assistant.core.engine.Reasoner
import com.example.assistant.core.engine.ReasonerInput
import com.example.assistant.core.engine.ReasonerOutput
import com.example.assistant.gemma.GemmaConfig
import com.example.assistant.gemma.GemmaReasoner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugSessionBootstrapRuntimeModeTest {

    @Test
    fun activation_appliesPersistedInteractionLanguage_andEmitsTtsDiagnostics() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(appContext)
        prefs.setInteractionLanguage("it-IT")
        val debug = mutableListOf<String>()

        val bootstrap = DebugSessionBootstrap(
            activity = activity,
            isDebugBuild = true,
            onStartListening = {},
            onStopListening = {},
            onShowTarget = {},
            onClearTarget = {},
            onDebugResult = { debug += it },
            onDebugError = {}
        )

        assertTrue(debug.any { it.contains("persistedInteractionLanguageOnActivation=it-IT") })
        assertTrue(debug.any { it.contains("ttsLocaleAppliedOnActivation=") })
        assertTrue(debug.any { it.contains("activeTtsVoice=") })
        bootstrap.close()
    }

    @Test
    fun debugBuild_preflightFailure_fallsBackToFakeReasoner() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var fakeFactoryCalls = 0

        val bootstrap = DebugSessionBootstrap(
            activity = activity,
            isDebugBuild = true,
            onStartListening = {},
            onStopListening = {},
            onShowTarget = {},
            onClearTarget = {},
            onDebugResult = {},
            onDebugError = {},
            fakeReasonerFactory = {
                fakeFactoryCalls++
                object : Reasoner {
                    override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                        error("unused in this test")
                    }
                }
            }
        )

        assertEquals("FakeReasoner", bootstrap.activeReasoner)
        assertEquals(1, fakeFactoryCalls)
        bootstrap.close()
    }

    @Test
    fun releaseBuild_preflightFailure_blocksSessionController() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var fakeFactoryCalls = 0

        val bootstrap = DebugSessionBootstrap(
            activity = activity,
            isDebugBuild = false,
            onStartListening = {},
            onStopListening = {},
            onShowTarget = {},
            onClearTarget = {},
            onDebugResult = {},
            onDebugError = {},
            fakeReasonerFactory = {
                fakeFactoryCalls++
                object : Reasoner {
                    override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                        error("unused in this test")
                    }
                }
            }
        )

        assertEquals("Unavailable", bootstrap.activeReasoner)
        assertEquals(0, fakeFactoryCalls)
        bootstrap.close()
    }

    @Test
    fun releaseBuild_preflightFailure_doesNotUseFakeReasonerPath() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var fakeFactoryCalls = 0

        val bootstrap = DebugSessionBootstrap(
            activity = activity,
            isDebugBuild = false,
            onStartListening = {},
            onStopListening = {},
            onShowTarget = {},
            onClearTarget = {},
            onDebugResult = {},
            onDebugError = {},
            fakeReasonerFactory = {
                fakeFactoryCalls++
                object : Reasoner {
                    override suspend fun reason(input: ReasonerInput): ReasonerOutput {
                        error("unused in this test")
                    }
                }
            }
        )

        assertEquals("Unavailable", bootstrap.activeReasoner)
        assertFalse(bootstrap.activeReasoner == "FakeReasoner")
        assertTrue(bootstrap.controller.javaClass.simpleName.contains("UnavailableSessionController"))
        assertEquals(0, fakeFactoryCalls)
        bootstrap.close()
    }
}
