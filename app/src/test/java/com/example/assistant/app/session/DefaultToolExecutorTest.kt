package com.example.assistant.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.android.accessibility.apps.InstalledApp
import com.example.assistant.android.accessibility.apps.InstalledAppProvider
import com.example.assistant.android.accessibility.service.LucaAccessibilityService
import com.example.assistant.android.speech.SpeechLanguageUpdateResult
import com.example.assistant.app.Prefs
import com.example.assistant.app.TRIGGER_BUTTON_VOLUME_DOWN
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.ToolRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultToolExecutorTest {

    private fun languageResult(
        requested: String,
        success: Boolean = true,
        resultCode: Int = 0,
        activeLocale: String = requested,
        failureReason: String? = null
    ) = SpeechLanguageUpdateResult(
        requestedLanguageTag = requested,
        requestedTtsLocale = requested,
        ttsSetLanguageResult = resultCode,
        success = success,
        activeTtsLocale = activeLocale,
        activeTtsVoice = if (success) "voice-$requested" else null,
        failureReason = failureReason
    )

    @After
    fun tearDown() {
        setActiveService(null)
    }

    @Test
    fun execute_withoutAccessibilityService_returnsFailure() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(
                name = "update_interaction_language",
                arguments = mapOf("language" to "it-IT")
            )
        )

        assertFalse(result.success)
        assertEquals(AssistantSessionPhrases.forLanguage(prefs.getInteractionLanguage()).cantdothat, result.spokenTextOverride)
    }

    @Test
    fun execute_updateInteractionLanguage_persistsAndAppliesLanguage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)

        var appliedLanguage: String? = null
        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = {
                appliedLanguage = it
                languageResult(requested = it)
            }
        )

        val result = executor.execute(
            ToolRequest(
                name = "update_interaction_language",
                arguments = mapOf("language" to "it-IT")
            )
        )

        assertTrue(result.success)
        assertEquals("it-IT", prefs.getInteractionLanguage())
        assertEquals("it-IT", appliedLanguage)
    }

    @Test
    fun execute_updateTriggerButton_persistsSafeValue() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(
                name = "update_trigger_button",
                arguments = mapOf("button" to TRIGGER_BUTTON_VOLUME_DOWN)
            )
        )

        assertTrue(result.success)
        assertEquals(TRIGGER_BUTTON_VOLUME_DOWN, prefs.getTriggerButton())
    }

    @Test
    fun execute_moveOverlay_missingPosition_returnsInvalidArgs() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(ToolRequest(name = "move_overlay", arguments = emptyMap()))

        assertFalse(result.success)
        assertTrue(result.debugMessage.contains("tool_invalid_args:move_overlay"))
    }

    @Test
    fun execute_updateLanguage_missingLanguage_returnsInvalidArgs() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(name = "update_interaction_language", arguments = mapOf("language" to "   "))
        )

        assertFalse(result.success)
        assertTrue(result.debugMessage.contains("tool_invalid_args:update_interaction_language"))
    }

    @Test
    fun execute_updateTrigger_invalidButton_returnsInvalidArgs() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(name = "update_trigger_button", arguments = mapOf("button" to "power"))
        )

        assertFalse(result.success)
        assertTrue(result.debugMessage.contains("tool_invalid_args:update_trigger_button"))
    }

    @Test
    fun execute_openPlayStore_withAppName_returnsPlayStoreGuidance() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(name = "open_play_store_and_search", arguments = mapOf("app_name" to "YouTube"))
        )

        assertTrue(result.success)
        assertTrue(result.spokenTextOverride?.contains("YouTube") == true)
        assertTrue(result.spokenTextOverride?.contains("Play Store", ignoreCase = true) == true)
    }

    @Test
    fun execute_openPlayStore_routesToPlayStoreWithOpenInstallWording_inEnglish() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        prefs.setInteractionLanguage("en-US")
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = { languageResult(it) },
            context = context,
            appToolRouting = AppToolRouting(fakeAppProvider(playStoreInstalled = true))
        )

        val result = executor.execute(
            ToolRequest(name = AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, arguments = mapOf("app_name" to "YouTube"))
        )

        assertTrue(result.success)
        assertEquals(
            "I’ll search for YouTube in the Play Store. If it’s installed, press Open. Otherwise, press Install.",
            result.spokenTextOverride
        )
        assertTrue(result.debugMessage.contains("tool_ok:open_play_store_and_search"))
        assertTrue(result.debugMessage.contains("app=YouTube"))
        assertTrue(result.debugMessage.contains("final_app_route=play_store"))
    }

    @Test
    fun execute_openPlayStore_routesToPlayStoreWithOpenInstallWording_inItalian() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        prefs.setInteractionLanguage("it-IT")
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)
        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = { languageResult(it) },
            context = context,
            appToolRouting = AppToolRouting(fakeAppProvider(playStoreInstalled = true))
        )

        val result = executor.execute(
            ToolRequest(name = AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, arguments = mapOf("app_name" to "YouTube"))
        )

        assertTrue(result.success)
        assertEquals(
            "Cerco YouTube nel Play Store. Se è già installata, premi Apri. Altrimenti, premi Installa.",
            result.spokenTextOverride
        )
    }

    @Test
    fun execute_openPlayStore_withoutService_returnsGracefulFallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        setActiveService(null)
        val executor = DefaultToolExecutor(prefs = prefs, speechOutputLanguageSetter = { languageResult(it) })

        val result = executor.execute(
            ToolRequest(name = "open_play_store_and_search", arguments = mapOf("app_name" to "SomeMissingApp"))
        )

        assertFalse(result.success)
        assertTrue(result.debugMessage.contains("tool_failed:no_accessibility_service"))
    }

    @Test
    fun execute_updateInteractionLanguage_it_normalizesTag_appliesItalianLocale_andIncludesDiagnostics() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)

        var appliedLanguage: String? = null
        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = {
                appliedLanguage = it
                languageResult(requested = it, activeLocale = "it-IT")
            }
        )

        val result = executor.execute(
            ToolRequest(name = "update_interaction_language", arguments = mapOf("language" to "it"))
        )

        assertTrue(result.success)
        assertEquals("Certo, parlerò in italiano.", result.spokenTextOverride)
        assertEquals("it-IT", prefs.getInteractionLanguage())
        assertEquals("it-IT", appliedLanguage)
        assertTrue(result.debugMessage.contains("requestedInteractionLanguage=it"))
        assertTrue(result.debugMessage.contains("persistedInteractionLanguage=it-IT"))
        assertTrue(result.debugMessage.contains("requestedTtsLocale=it-IT"))
        assertTrue(result.debugMessage.contains("ttsLanguageChangeSucceeded=true"))
        assertTrue(result.debugMessage.contains("spokenConfirmationLanguage=it-IT"))
        assertTrue(result.debugMessage.contains("spokenConfirmationText=Certo, parlerò in italiano."))
    }

    @Test
    fun execute_updateInteractionLanguage_en_normalizesTag_appliesEnglishLocale_andSpeaksEnglishConfirmation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)

        var appliedLanguage: String? = null
        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = {
                appliedLanguage = it
                languageResult(requested = it, activeLocale = "en-US")
            }
        )

        val result = executor.execute(
            ToolRequest(name = "update_interaction_language", arguments = mapOf("language" to "en"))
        )

        assertTrue(result.success)
        assertEquals("Sure, I’ll speak English.", result.spokenTextOverride)
        assertEquals("en-US", prefs.getInteractionLanguage())
        assertEquals("en-US", appliedLanguage)
        assertTrue(result.debugMessage.contains("spokenConfirmationLanguage=en-US"))
        assertTrue(result.debugMessage.contains("spokenConfirmationText=Sure, I’ll speak English."))
    }

    @Test
    fun execute_updateInteractionLanguage_ttsFailure_reportsDiagnosticsAndClearMessage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val service = Robolectric.buildService(LucaAccessibilityService::class.java).get()
        setActiveService(service)

        val executor = DefaultToolExecutor(
            prefs = prefs,
            speechOutputLanguageSetter = {
                languageResult(
                    requested = it,
                    success = false,
                    resultCode = -2,
                    activeLocale = "en-US",
                    failureReason = "tts_language_missing_data"
                )
            }
        )

        val result = executor.execute(
            ToolRequest(name = "update_interaction_language", arguments = mapOf("language" to "it"))
        )

        assertFalse(result.success)
        assertEquals("La voce italiana non è installata su questo dispositivo.", result.spokenTextOverride)
        assertTrue(result.debugMessage.contains("ttsLanguageChangeSucceeded=false"))
        assertTrue(result.debugMessage.contains("failureReason=tts_language_missing_data"))
        assertTrue(result.debugMessage.contains("activeTtsLocale=en-US"))
    }

    private fun setActiveService(value: LucaAccessibilityService?) {
        val field = LucaAccessibilityService::class.java.getDeclaredField("activeInstance")
        field.isAccessible = true
        field.set(null, value)
    }

    private fun fakeAppProvider(playStoreInstalled: Boolean) = object : InstalledAppProvider {
        override fun getInstalledUserApps(): List<InstalledApp> = emptyList()

        override fun isAppInstalled(packageName: String): Boolean {
            return packageName == "com.android.vending" && playStoreInstalled
        }
    }
}
