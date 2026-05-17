package com.example.assistant.core.parser

import com.example.assistant.core.engine.ReasonerOutput
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.NormalizedBox
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Parses the JSON output from Gemma into a typed ReasonerOutput.
 *
 * Validation policy:
 * - HARD MANDATORY: spoken_text. Without it, the response is unusable; fall back to a
 *   standard "I couldn't understand" message.
 * - SEMANTIC: target.source must be coherent with the identifier present.
 *     - source = "SCREENSHOT" requires a valid box_2d
 *     - source = "UI_TREE" requires a non-blank node_id
 *     - source = "INFERRED" or unknown values are discarded entirely (target -> null)
 *   When the target fails semantic validation, it is silently zeroed; the rest of the
 *   response (spoken_text, summary, confidences) is still returned.
 * - SOFT: every other field has a default and never blocks the response.
 *
 * Note: this parser is intentionally lenient on optional fields and strict on the
 * two things that determine whether the app speaks and renders correctly.
 */
class GuidanceResponseParser {

    private companion object {
        const val FALLBACK_COMPACT_EN = "I couldn't understand this screen clearly."
        private val PLACEHOLDER_TOKENS = setOf("short answer", "...", "fatto", "done", "ok", "okay")
    }

    fun parse(raw: String): ReasonerOutput {
        val json = parseObject(raw)
            ?: return fallbackOutput(reason = "json_parse_failed")

        if (json.has("say") || json.has("target_id")) {
            return parseCompact(raw, json)
        }

        // HARD MANDATORY: spoken_text must be present and non-blank.
        // We accept summary as an emergency fallback (it's at least objectively descriptive),
        // but we log the anomaly via the rationale field for debug visibility.
        val rawSpoken = json.optString("spoken_text").takeIf { it.isNotBlank() }
        val rawSummary = json.optString("summary").takeIf { it.isNotBlank() }

        if (rawSpoken == null && rawSummary == null) {
            return fallbackOutput(reason = "missing_mandatory_spoken_text_and_summary")
        }

        val invalidSpoken = isPlaceholder(rawSpoken)
        val invalidSummary = isPlaceholder(rawSummary)
        val spoken: String = (
            if (!invalidSpoken) {
                rawSpoken?.takeIf { it.isNotBlank() }
            } else if (!invalidSummary) {
                rawSummary
            } else {
                null
            }
            ) ?: FALLBACK_COMPACT_EN
        val summary = rawSummary ?: ""
        val parserDegradation = buildList {
            if (invalidSpoken) add("placeholder_spoken_text")
            if (invalidSummary) add("placeholder_summary")
        }.takeIf { it.isNotEmpty() }?.joinToString(",")
        val rationale = buildString {
            val modelRationale = json.optString("rationale").takeIf { it.isNotBlank() }
            if (modelRationale != null) append(modelRationale)
            if (parserDegradation != null) {
                if (isNotBlank()) append("; ")
                append("parser_degraded:")
                append(parserDegradation)
            }
        }.takeIf { it.isNotBlank() }
        // SEMANTIC VALIDATION of target.
        // We accept target only when source is coherent with the identifier present.
        // Any incoherent target is zeroed; the rest of the response still goes through.
        val targetObj = json.optJSONObject("target")
        val parsedTarget = parseValidTarget(targetObj)
        val toolRequest = parseToolRequest(json.optJSONObject("tool_request"))

        return ReasonerOutput(
            summary = summary,
            spokenText = spoken,
            rationale = rationale,
            targetNodeId = parsedTarget?.nodeId,
            targetBounds = parsedTarget?.bounds,
            targetNormalizedBox = parsedTarget?.normalizedBox,
            targetLabel = parsedTarget?.label,
            targetConfidence = parsedTarget?.confidence ?: 0f,
            toolRequest = toolRequest,
            answerConfidence = clamp01(json.optDouble("answer_confidence", 0.0).toFloat()),
            visualConfidence = clamp01(json.optDouble("visual_confidence", 0.0).toFloat())
        )
    }

    private fun parseCompact(raw: String, json: JSONObject): ReasonerOutput {
        val rawSay = json.optString("say").trim()
        val invalidSay = isPlaceholder(rawSay)
        val rawAction = json.optString("action").trim()
        val invalidAction = rawAction.isNotEmpty() && isPlaceholder(rawAction)
        val say = if (invalidSay) FALLBACK_COMPACT_EN else rawSay

        val rawTargetId = json.optString("target_id").trim()
        val invalidTargetId = rawTargetId.equals("ID or null", ignoreCase = true) || rawTargetId.isBlank()
        val targetId = if (invalidTargetId) null else rawTargetId
        val confidence = clamp01(json.optDouble("confidence", 0.0).toFloat()) ?: 0f
        val degradationReason = buildList {
            if (invalidSay) add("placeholder_say")
            if (invalidAction) add("placeholder_action")
            if (invalidTargetId && rawTargetId.isNotEmpty()) add("placeholder_target_id")
        }.takeIf { it.isNotEmpty() }?.joinToString(",")
        return ReasonerOutput(
            summary = "",
            spokenText = say,
            rationale = if (degradationReason == null) "compact_m3" else "compact_m3_degraded:$degradationReason",
            targetNodeId = null,
            targetBounds = null,
            targetNormalizedBox = null,
            targetLabel = null,
            targetConfidence = confidence,
            toolRequest = null,
            answerConfidence = confidence,
            visualConfidence = confidence,
            compactTargetId = targetId,
            compactConfidence = confidence,
            compactRawOutput = raw,
            compactParsedOutput = "{\"say\":\"$say\",\"action\":${if (rawAction.isBlank()) "null" else "\"$rawAction\""},\"target_id\":${if (targetId == null) "null" else "\"$targetId\""},\"confidence\":$confidence}",
            compactOutputDegradationReason = degradationReason
        )
    }

    private fun isPlaceholder(value: String?): Boolean {
        val normalized = value
            ?.trim()
            ?.trim('.', '!', '?', ',', ';', ':', '"', '\'', '…')
            ?.lowercase()
            .orEmpty()
        return normalized.isBlank() || normalized in PLACEHOLDER_TOKENS
    }

    private fun parseToolRequest(obj: JSONObject?): ToolRequest? {
        obj ?: return null
        val name = obj.optString("name").trim()
        if (name.isBlank() || name !in AssistantTools.allowed) return null

        val argsObj = obj.optJSONObject("arguments")
        val args = mutableMapOf<String, String>()
        if (argsObj != null) {
            val keys = argsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = argsObj.optString(key).trim()
                if (value.isNotBlank()) {
                    args[key] = value
                }
            }
        }

        return ToolRequest(name = name, arguments = args)
    }

    /**
     * Parses target only if it passes semantic validation.
     * Returns null (which the caller maps to "no target") when:
     * - target object is missing
     * - source is missing, unknown, or not supported in the current milestone (UI_TREE, INFERRED)
     * - source = "SCREENSHOT" but box_2d is missing/invalid
     * - source = "UI_TREE" but node_id is missing/blank
     */
    private fun parseValidTarget(targetObj: JSONObject?): ParsedTarget? {
        if (targetObj == null) return null

        val source = targetObj.optString("source").takeIf { it.isNotBlank() } ?: return null
        val nodeId = targetObj.optString("node_id").takeIf { it.isNotBlank() }
        val box = parseBox(targetObj.optJSONArray("box_2d"))
        val bounds = parseBounds(targetObj.optJSONObject("bounds"))
        val label = targetObj.optString("label").takeIf { it.isNotBlank() }
        val confidence = clamp01(targetObj.optDouble("target_confidence", 0.0).toFloat()) ?: 0f

        return when (source) {
            "SCREENSHOT" -> {
                // SCREENSHOT requires a valid normalized box.
                if (box == null) null
                else ParsedTarget(
                    nodeId = null,        // ignored for SCREENSHOT path
                    bounds = bounds,      // optional; may be filled by engine after coordinate mapping
                    normalizedBox = box,
                    label = label,
                    confidence = confidence
                )
            }
            "UI_TREE" -> {
                // UI_TREE requires a non-blank node_id.
                if (nodeId == null) null
                else ParsedTarget(
                    nodeId = nodeId,
                    bounds = bounds,
                    normalizedBox = null, // ignored for UI_TREE path
                    label = label,
                    confidence = confidence
                )
            }
            // INFERRED, unknown sources, or sources not supported in the current milestone
            // are rejected. The model is told in the prompt to use only SCREENSHOT in M1+M2.
            else -> null
        }
    }

    private data class ParsedTarget(
        val nodeId: String?,
        val bounds: RectBounds?,
        val normalizedBox: NormalizedBox?,
        val label: String?,
        val confidence: Float
    )

    private fun parseObject(raw: String): JSONObject? {
        return try {
            JSONObject(raw.trim())
        } catch (_: Throwable) {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    JSONObject(raw.substring(start, end + 1))
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }
    }

    private fun parseBox(array: JSONArray?): NormalizedBox? {
        if (array == null || array.length() != 4) return null
        val top = clamp1000(array.optInt(0))
        val left = clamp1000(array.optInt(1))
        val bottom = clamp1000(array.optInt(2))
        val right = clamp1000(array.optInt(3))
        if (right <= left || bottom <= top) return null
        return NormalizedBox(top = top, left = left, bottom = bottom, right = right)
    }

    private fun parseBounds(obj: JSONObject?): RectBounds? {
        obj ?: return null
        val left = obj.optInt("left", Int.MIN_VALUE)
        val top = obj.optInt("top", Int.MIN_VALUE)
        val right = obj.optInt("right", Int.MIN_VALUE)
        val bottom = obj.optInt("bottom", Int.MIN_VALUE)
        if (left == Int.MIN_VALUE || top == Int.MIN_VALUE || right == Int.MIN_VALUE || bottom == Int.MIN_VALUE) {
            return null
        }
        if (right <= left || bottom <= top) return null
        return RectBounds(left, top, right, bottom)
    }

    private fun clamp1000(v: Int): Int = min(1000, max(0, v))

    private fun clamp01(v: Float?): Float? {
        v ?: return null
        return min(1f, max(0f, v))
    }

    /**
     * Standard fallback when the response is unusable.
     * The reason is encoded in the rationale field so it shows up in the debug screen
     * and in logs without changing the ReasonerOutput data class.
     */
    private fun fallbackOutput(reason: String): ReasonerOutput {
        return ReasonerOutput(
            summary = "",
            spokenText = FALLBACK_COMPACT_EN,
            rationale = "parser_fallback: $reason",
            targetNodeId = null,
            targetBounds = null,
            targetNormalizedBox = null,
            targetLabel = null,
            targetConfidence = 0f,
            toolRequest = null,
            answerConfidence = 0f,
            visualConfidence = 0f
        )
    }
}
