package com.example.assistant.app.session

import com.example.assistant.core.model.AssistantTools
import com.example.assistant.gemma.TextOnlyRouteModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaIntentRouterProductionConfigTest {

    @Test
    fun speakItalian_routesToLocalTool_updateInteractionLanguage() = runTest {
        val router = GemmaIntentRouter(model = ScriptedModel())
        val route = router.route(
            IntentRouterInput(
                transcript = "speak Italian",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )

        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, local.toolName)
        assertEquals("it", local.arguments["language"])
    }

    @Test
    fun moveBottomRight_routesToClarification_notMoveTool() = runTest {
        val router = GemmaIntentRouter(model = ScriptedModel())
        val route = router.route(
            IntentRouterInput(
                transcript = "move to the bottom right",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )

        assertTrue(route is IntentRoute.Clarification)
    }

    @Test
    fun screenQuestion_routesToScreenContext() = runTest {
        val router = GemmaIntentRouter(model = ScriptedModel())
        val route = router.route(
            IntentRouterInput(
                transcript = "what is on this screen?",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )
        assertTrue(route is IntentRoute.ScreenContext)
    }

    @Test
    fun prompt_containsGoldenExamples_forCriticalUtterances() = runTest {
        val capture = PromptCaptureModel(
            response = """{"r":"screen","m":"auto"}"""
        )
        val router = GemmaIntentRouter(model = capture)
        router.route(
            IntentRouterInput(
                transcript = "describe what's on the screen",
                interactionLanguage = "en",
                currentAssistantSettings = AssistantSettingsSnapshot("en", "volume_up")
            )
        )

        val prompt = capture.lastPrompt
        assertTrue(prompt.contains("JSON only"))
        assertTrue(prompt.contains("{\"r\":\"tool\",\"t\":\"language\""))
        assertTrue(!prompt.contains("move to the top right"))
        assertTrue(!prompt.contains("\"t\":\"move\""))
        assertTrue(!prompt.contains("top_right"))
        assertTrue(prompt.contains("describe the screen"))
        assertTrue(prompt.contains("open YouTube"))
        assertTrue(prompt.contains("{\"r\":\"screen\"}"))
    }

    @Test
    fun openYoutube_routesToPlayStoreTool_withNoScreenshotRoute() = runTest {
        val router = GemmaIntentRouter(
            model = SingleResponseModel(
                """{"r":"tool","t":"app","a":{"name":"YouTube"}}"""
            )
        )
        val route = router.route(
            IntentRouterInput(
                transcript = "open YouTube",
                interactionLanguage = "en-US",
                currentAssistantSettings = AssistantSettingsSnapshot("en-US", "volume_up")
            )
        )
        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, local.toolName)
        assertEquals("YouTube", local.arguments["app_name"])
    }

    @Test
    fun findYoutubeOnMyPhone_routesToPlayStoreTool() = runTest {
        val router = GemmaIntentRouter(
            model = SingleResponseModel(
                """{"r":"tool","t":"app","a":{"name":"YouTube"}}"""
            )
        )
        val route = router.route(
            IntentRouterInput(
                transcript = "find YouTube on my phone",
                interactionLanguage = "en-US",
                currentAssistantSettings = AssistantSettingsSnapshot("en-US", "volume_up")
            )
        )
        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, local.toolName)
        assertEquals("YouTube", local.arguments["app_name"])
    }

    @Test
    fun cercaYoutubeSulTelefono_routesToPlayStoreTool() = runTest {
        val router = GemmaIntentRouter(
            model = SingleResponseModel(
                """{"r":"tool","t":"app","a":{"name":"YouTube"}}"""
            )
        )
        val route = router.route(
            IntentRouterInput(
                transcript = "cerca YouTube sul telefono",
                interactionLanguage = "it-IT",
                currentAssistantSettings = AssistantSettingsSnapshot("it-IT", "volume_up")
            )
        )
        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, local.toolName)
        assertEquals("YouTube", local.arguments["app_name"])
    }

    @Test
    fun missingAppCase_routesToPlayStoreTool() = runTest {
        val router = GemmaIntentRouter(
            model = SingleResponseModel(
                """{"r":"tool","t":"app","a":{"name":"Signal"}}"""
            )
        )
        val route = router.route(
            IntentRouterInput(
                transcript = "install Signal",
                interactionLanguage = "en-US",
                currentAssistantSettings = AssistantSettingsSnapshot("en-US", "volume_up")
            )
        )
        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, local.toolName)
        assertEquals("Signal", local.arguments["app_name"])
    }

    @Test
    fun prompt_contextHints_includePersistedInteractionLanguage() = runTest {
        val capture = PromptCaptureModel(
            response = """{"r":"screen","m":"auto"}"""
        )
        val router = GemmaIntentRouter(model = capture)

        router.route(
            IntentRouterInput(
                transcript = "descrivi questo schermo",
                interactionLanguage = "it-IT",
                currentAssistantSettings = AssistantSettingsSnapshot("it-IT", "volume_up")
            )
        )

        val prompt = capture.lastPrompt
        assertTrue(prompt.contains("interaction_language: it-IT"))
    }

    @Test
    fun requestedUtterances_parseToExpectedRoutes_withNearValidOutput() = runTest {
        val scripted = object : TextOnlyRouteModel {
            override suspend fun generateRouteJson(prompt: String): String {
                val transcript = prompt.substringAfter("Transcript:").trim().lowercase()
                return when {
                    transcript.contains("puoi parlare italiano") ->
                        """{"r":"tool","t":"language","a":{"l":"it"}}"""

                    transcript.contains("can you speak english") ->
                        """{"r":"tool","t":"language","a":{"l":"en"}}"""

                    transcript.contains("switch to english") ->
                        """{"r":"tool","t":"language","a":{"l":"en"}}"""

                    transcript.contains("use volume down") ->
                        """{"r":"tool","t":"trigger","a":{"b":"volume_down"}}"""

                    transcript.contains("open play store and search youtube") ->
                        """{"r":"tool","t":"app","a":{"name":"YouTube"}}"""

                    transcript.contains("describe what's on the screen") ->
                        """{"r":"screen","m":"auto"}"""

                    transcript.contains("what is on this screen") ->
                        """{"r":"screen","m":"auto"}"""

                    else ->
                        """{"r":"clarify","text":"Can you clarify what you need?"}"""
                }
            }
        }
        val router = GemmaIntentRouter(model = scripted)

        val r1 = router.route(IntentRouterInput("puoi parlare italiano?", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r1 is IntentRoute.LocalTool)
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, (r1 as IntentRoute.LocalTool).toolName)
        assertEquals("it", r1.arguments["language"])

        val r2 = router.route(IntentRouterInput("can you speak english?", "it", AssistantSettingsSnapshot("it", "volume_up")))
        assertTrue(r2 is IntentRoute.LocalTool)
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, (r2 as IntentRoute.LocalTool).toolName)
        assertEquals("en", r2.arguments["language"])

        val r2b = router.route(IntentRouterInput("switch to English", "it", AssistantSettingsSnapshot("it", "volume_up")))
        assertTrue(r2b is IntentRoute.LocalTool)
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, (r2b as IntentRoute.LocalTool).toolName)
        assertEquals("en", r2b.arguments["language"])

        val r2c = router.route(IntentRouterInput("use volume down", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r2c is IntentRoute.LocalTool)
        assertEquals(AssistantTools.UPDATE_TRIGGER_BUTTON, (r2c as IntentRoute.LocalTool).toolName)
        assertEquals("volume_down", r2c.arguments["button"])

        val r2d = router.route(IntentRouterInput("open Play Store and search YouTube", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r2d is IntentRoute.LocalTool)
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, (r2d as IntentRoute.LocalTool).toolName)
        assertEquals("YouTube", r2d.arguments["app_name"])

        val r3 = router.route(IntentRouterInput("move on the top right", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r3 is IntentRoute.Clarification)

        val r4 = router.route(IntentRouterInput("move to the top right", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r4 is IntentRoute.Clarification)

        val r5 = router.route(IntentRouterInput("describe what's on the screen", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r5 is IntentRoute.ScreenContext)

        val r6 = router.route(IntentRouterInput("what is on this screen?", "en", AssistantSettingsSnapshot("en", "volume_up")))
        assertTrue(r6 is IntentRoute.ScreenContext)
    }

    private class PromptCaptureModel(
        private val response: String
    ) : TextOnlyRouteModel {
        var lastPrompt: String = ""

        override suspend fun generateRouteJson(prompt: String): String {
            lastPrompt = prompt
            return response
        }
    }

    private class ScriptedModel : TextOnlyRouteModel {
        override suspend fun generateRouteJson(prompt: String): String {
            val transcript = prompt.substringAfter("Transcript:").trim().lowercase()
            return when {
                transcript.contains("speak italian") ->
                    """{"r":"tool","t":"language","a":{"l":"it"}}"""

                transcript.contains("bottom right") ->
                    """{"r":"tool","t":"move","a":{"p":"bottom_right"}}"""

                else ->
                    """{"r":"screen","m":"auto"}"""
            }
        }
    }

    private class SingleResponseModel(
        private val response: String
    ) : TextOnlyRouteModel {
        override suspend fun generateRouteJson(prompt: String): String = response
    }
}
