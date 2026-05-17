package com.example.assistant.app.session

import com.example.assistant.core.model.AssistantTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouteJsonParserTest {

    private val parser = IntentRouteJsonParser()

    @Test
    fun valid_localTool_routeAccepted() {
        val route = parser.parse(
            """
            {
              "route": "local_tool",
              "tool_name": "update_interaction_language",
              "arguments": { "language": "it" },
              "spoken_text": "Certo, parlerò in italiano.",
              "confidence": 0.8
            }
            """.trimIndent()
        )
        assertTrue("route=$route", route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, local.toolName)
        assertEquals("it", local.arguments["language"])
    }

    @Test
    fun valid_screenContext_routeAccepted() {
        val route = parser.parse(
            """
            {
              "route": "screen_context",
              "screen_context": { "mode": "screenshot", "purpose": "ui_navigation" },
              "confidence": 0.1
            }
            """.trimIndent()
        )
        assertTrue("route=$route", route is IntentRoute.ScreenContext)
    }

    @Test
    fun malformed_outputFallsBackToClarification_notScreenContext() {
        val route = parser.parse("not json")
        assertTrue(route is IntentRoute.Clarification)
    }

    @Test
    fun unknown_toolRejected() {
        val route = parser.parse(
            """
            {
              "route": "local_tool",
              "tool_name": "delete_everything",
              "arguments": { "now": "true" },
              "confidence": 0.9
            }
            """.trimIndent()
        )
        assertTrue(route is IntentRoute.Clarification)
    }

    @Test
    fun move_tool_isRejected() {
        val route = parser.parse(
            """
            {
              "r": "tool",
              "t": "move",
              "a": { "p": "top_right" }
            }
            """.trimIndent()
        )
        assertTrue(route is IntentRoute.Clarification)
        assertTrue(parser.latestDegradationReason?.startsWith("parser_compact_move_tool_rejected") == true)
    }

    @Test
    fun lowConfidence_localTool_degradesToClarification() {
        val route = parser.parse(
            """
            {
              "route": "local_tool",
              "tool_name": "update_trigger_button",
              "arguments": { "button": "volume_up" },
              "confidence": 0.55
            }
            """.trimIndent()
        )
        assertTrue(route is IntentRoute.Clarification)
    }

    @Test
    fun language_variants_areNormalized_toSupportedTags() {
        val italianVariants = listOf("Italian", "italian", "it", "it-IT")
        italianVariants.forEach { variant ->
            val route = parser.parse(
                """
                {
                  "route": "local_tool",
                  "tool_name": "update_interaction_language",
                  "arguments": { "language": "$variant" },
                  "confidence": 0.95
                }
                """.trimIndent()
            )
            assertTrue("variant=$variant route=$route", route is IntentRoute.LocalTool)
            assertEquals("it", (route as IntentRoute.LocalTool).arguments["language"])
        }

        val englishVariants = listOf("English", "english", "en", "en-US")
        englishVariants.forEach { variant ->
            val route = parser.parse(
                """
                {
                  "route": "local_tool",
                  "tool_name": "update_interaction_language",
                  "arguments": { "language": "$variant" },
                  "confidence": 0.95
                }
                """.trimIndent()
            )
            assertTrue("variant=$variant route=$route", route is IntentRoute.LocalTool)
            assertEquals("en", (route as IntentRoute.LocalTool).arguments["language"])
        }
    }

    @Test
    fun safe_tool_synonyms_areNormalized() {
        val setLanguage = parser.parse(
            """
            {
              "route": "local_tool",
              "tool_name": "set_language",
              "arguments": { "language": "italian" },
              "confidence": 0.95
            }
            """.trimIndent()
        )
        assertTrue(setLanguage is IntentRoute.LocalTool)
        assertEquals(AssistantTools.UPDATE_INTERACTION_LANGUAGE, (setLanguage as IntentRoute.LocalTool).toolName)
        assertEquals("it", setLanguage.arguments["language"])

    }

    @Test
    fun compact_tool_app_routesToPlayStoreTool_withAppName() {
        val route = parser.parse(
            """
            {"r":"tool","t":"app","a":{"name":"YouTube"}}
            """.trimIndent()
        )
        assertTrue(route is IntentRoute.LocalTool)
        val local = route as IntentRoute.LocalTool
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, local.toolName)
        assertEquals("YouTube", local.arguments["app_name"])
    }

    @Test
    fun compact_screen_mode_mapsToExpectedPurpose() {
        val route = parser.parse(
            """
            {"r":"screen","m":"message_safety"}
            """.trimIndent()
        )
        assertTrue(route is IntentRoute.ScreenContext)
        val screen = route as IntentRoute.ScreenContext
        assertEquals(ScreenContextPurpose.MESSAGE_SAFETY, screen.purpose)
    }

    @Test
    fun compact_screen_withoutMode_defaultsToScreenMapFirstAndGeneralPurpose() {
        val route = parser.parse(
            """
            {"r":"screen"}
            """.trimIndent()
        )

        assertTrue(route is IntentRoute.ScreenContext)
        val screen = route as IntentRoute.ScreenContext
        assertEquals(ScreenContextMode.SCREEN_MAP_FIRST, screen.mode)
        assertEquals(ScreenContextPurpose.GENERAL_SCREEN_HELP, screen.purpose)
        assertTrue(parser.compactScreenMissingModeDefaulted)
        assertEquals(ScreenContextMode.SCREEN_MAP_FIRST, parser.compactScreenDefaultMode)
        assertEquals(ScreenContextPurpose.GENERAL_SCREEN_HELP, parser.compactScreenDefaultPurpose)
    }

    @Test
    fun compact_screen_messageSafety_stillParsesToScreenContext() {
        val route = parser.parse(
            """
            {"r":"screen","m":"message_safety"}
            """.trimIndent()
        )

        assertTrue(route is IntentRoute.ScreenContext)
        val screen = route as IntentRoute.ScreenContext
        assertEquals(ScreenContextMode.SCREEN_MAP_FIRST, screen.mode)
        assertEquals(ScreenContextPurpose.MESSAGE_SAFETY, screen.purpose)
        assertFalse(parser.compactScreenMissingModeDefaulted)
    }

    @Test
    fun compact_screen_uiNavigation_stillParsesToScreenContext() {
        val route = parser.parse(
            """
            {"r":"screen","m":"ui_navigation"}
            """.trimIndent()
        )

        assertTrue(route is IntentRoute.ScreenContext)
        val screen = route as IntentRoute.ScreenContext
        assertEquals(ScreenContextMode.SCREEN_MAP_FIRST, screen.mode)
        assertEquals(ScreenContextPurpose.UI_NAVIGATION, screen.purpose)
        assertFalse(parser.compactScreenMissingModeDefaulted)
    }
}
