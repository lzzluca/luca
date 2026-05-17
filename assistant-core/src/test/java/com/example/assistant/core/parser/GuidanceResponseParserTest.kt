package com.example.assistant.core.parser

import com.example.assistant.core.model.AssistantTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceResponseParserTest {

    private val parser = GuidanceResponseParser()

    @Test
    fun parse_validScreenshotTarget_parsesFields() {
        val raw =
            """
            {
              "summary": "home screen",
              "spoken_text": "Press YouTube.",
              "target": {
                "source": "SCREENSHOT",
                "box_2d": [100, 200, 300, 400],
                "label": "YouTube",
                "target_confidence": 0.8
              },
              "answer_confidence": 0.7,
              "visual_confidence": 0.6
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertEquals("home screen", out.summary)
        assertEquals("Press YouTube.", out.spokenText)
        assertNotNull(out.targetNormalizedBox)
        assertEquals(100, out.targetNormalizedBox?.top)
        assertEquals(200, out.targetNormalizedBox?.left)
        assertEquals(300, out.targetNormalizedBox?.bottom)
        assertEquals(400, out.targetNormalizedBox?.right)
    }

    @Test
    fun parse_invalidJson_returnsFallback() {
        val out = parser.parse("not-json")
        assertEquals("I couldn't understand this screen clearly.", out.spokenText)
        assertNull(out.targetNormalizedBox)
    }

    @Test
    fun parse_missingSpokenText_usesSummaryFallback() {
        val raw =
            """
            {
              "summary": "This is a settings screen",
              "target": null
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertEquals("This is a settings screen", out.spokenText)
    }

    @Test
    fun parse_invalidBoxOrder_rejectsTarget() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "target": {
                "source": "SCREENSHOT",
                "box_2d": [500, 500, 100, 100],
                "target_confidence": 0.8
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertNull(out.targetNormalizedBox)
        assertNull(out.targetNodeId)
    }

    @Test
    fun parse_outOfRangeBox_clampsValues() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "target": {
                "source": "SCREENSHOT",
                "box_2d": [-10, 10, 1200, 1100],
                "target_confidence": 0.8
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertEquals(0, out.targetNormalizedBox?.top)
        assertEquals(10, out.targetNormalizedBox?.left)
        assertEquals(1000, out.targetNormalizedBox?.bottom)
        assertEquals(1000, out.targetNormalizedBox?.right)
    }

    @Test
    fun parse_uiTreeWithoutNodeId_rejectsTarget() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "target": {
                "source": "UI_TREE",
                "target_confidence": 0.8
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertNull(out.targetNodeId)
        assertNull(out.targetNormalizedBox)
    }

    @Test
    fun parse_unknownToolName_rejectsToolRequest() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "tool_request": {
                "name": "click_button",
                "arguments": { "id": "install" }
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertNull(out.toolRequest)
    }

    @Test
    fun parse_validAllowedToolName_acceptsToolRequest() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "tool_request": {
                "name": "open_play_store_and_search",
                "arguments": { "app_name": "YouTube" }
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertNotNull(out.toolRequest)
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, out.toolRequest?.name)
        assertEquals("YouTube", out.toolRequest?.arguments?.get("app_name"))
    }

    @Test
    fun parse_emptyToolArguments_ignoredSafely() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "tool_request": {
                "name": "move_overlay",
                "arguments": {
                  "position": "   ",
                  "other": ""
                }
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertNotNull(out.toolRequest)
        assertTrue(out.toolRequest!!.arguments.isEmpty())
    }

    @Test
    fun parse_malformedToolRequest_doesNotCrash() {
        val raw =
            """
            {
              "summary": "x",
              "spoken_text": "x",
              "tool_request": {
                "name": [1,2,3],
                "arguments": "bad"
              }
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertEquals("x", out.spokenText)
        assertFalse(out.toolRequest != null)
    }

    @Test
    fun parse_compactM3Output_parsesTargetIdAndConfidence() {
        val raw = """{"say":"Press Share","target_id":"E5","confidence":0.9}"""

        val out = parser.parse(raw)
        assertEquals("Press Share", out.spokenText)
        assertEquals("E5", out.compactTargetId)
        assertEquals(0.9f, out.compactConfidence)
    }

    @Test
    fun parse_compactM3Output_rejectsPlaceholderShortAnswer() {
        val raw = """{"say":"short answer","target_id":"E5","confidence":0.9}"""

        val out = parser.parse(raw)
        assertEquals("I couldn't understand this screen clearly.", out.spokenText)
        assertEquals("placeholder_say", out.compactOutputDegradationReason)
        assertEquals("E5", out.compactTargetId)
    }

    @Test
    fun parse_compactM3Output_rejectsPlaceholderSayAndAction() {
        val raw = """{"risk":"LOW","say":"short answer","action":"ok","target_id":"E5","confidence":0.9}"""

        val out = parser.parse(raw)
        assertEquals("I couldn't understand this screen clearly.", out.spokenText)
        assertTrue(out.compactOutputDegradationReason?.contains("placeholder_say") == true)
        assertTrue(out.compactOutputDegradationReason?.contains("placeholder_action") == true)
    }

    @Test
    fun parse_richOutput_rejectsPlaceholderSpokenText() {
        val raw =
            """
            {
              "summary": "done",
              "spoken_text": "short answer"
            }
            """.trimIndent()

        val out = parser.parse(raw)
        assertEquals("I couldn't understand this screen clearly.", out.spokenText)
        assertTrue(out.rationale?.contains("parser_degraded:placeholder_spoken_text,placeholder_summary") == true)
    }

    @Test
    fun parse_compactM3Output_rejectsPlaceholderTargetId() {
        val raw = """{"say":"Premi Cerca","target_id":"ID or null","confidence":0.9}"""

        val out = parser.parse(raw)
        assertEquals("Premi Cerca", out.spokenText)
        assertNull(out.compactTargetId)
        assertEquals("placeholder_target_id", out.compactOutputDegradationReason)
    }

    @Test
    fun parse_compactM3Output_rejectsFattoPlaceholder() {
        val raw = """{"say":"fatto","target_id":"","confidence":0.9}"""

        val out = parser.parse(raw)
        assertEquals("I couldn't understand this screen clearly.", out.spokenText)
        assertNull(out.compactTargetId)
        assertTrue(out.compactOutputDegradationReason?.contains("placeholder_say") == true)
    }
}
