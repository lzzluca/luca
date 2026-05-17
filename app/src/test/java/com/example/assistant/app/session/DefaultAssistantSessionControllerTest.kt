package com.example.assistant.app.session

import com.example.assistant.android.speech.SpeechOutput
import com.example.assistant.core.engine.AssistantEngine
import com.example.assistant.core.engine.ObservationRequest
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.GuidanceResult
import com.example.assistant.core.model.GuidanceTarget
import com.example.assistant.core.model.RectBounds
import com.example.assistant.core.model.TargetSource
import com.example.assistant.core.model.ToolRequest
import com.example.assistant.android.speech.SpeechLanguageUpdateResult
import com.example.assistant.core.model.UserQuestion
import com.example.assistant.gemma.TextOnlyRouteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAssistantSessionControllerTest {

    @Test
    fun activate_reappliesItalianTtsLocale_beforeGreeting_andKeepsInteractionLanguage() = runTest {
        val spoken = mutableListOf<String>()
        val requestedLocales = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val speech = object : SpeechOutput {
            override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                spoken += text
                onDone?.invoke()
            }

            override fun stop() = Unit

            override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult {
                requestedLocales += languageTag
                return SpeechLanguageUpdateResult(
                    requestedLanguageTag = languageTag,
                    requestedTtsLocale = "it-IT",
                    ttsSetLanguageResult = 0,
                    success = true,
                    activeTtsLocale = "it-IT",
                    activeTtsVoice = "it-voice"
                )
            }

            override fun shutdown() = Unit
        }

        val controller = newController(
            scope = this,
            speechOutput = speech,
            onDebugResult = { debug += it },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        controller.activate()
        advanceUntilIdle()

        assertEquals(listOf("it-IT"), requestedLocales)
        assertTrue(spoken.isNotEmpty())
        assertEquals("Ciao, sono Luca. Come posso aiutarti?", spoken.first())
        assertTrue(debug.any { it == "persistedInteractionLanguageOnActivation=it-IT" })
        assertTrue(debug.any { it == "ttsLocaleReappliedOnActivation=true" })
        assertTrue(debug.any { it == "requestedTtsLocaleOnActivation=it-IT" })
        assertTrue(debug.any { it == "activeTtsLocaleAfterActivation=it-IT" })
        assertTrue(debug.any { it == "activeTtsVoiceAfterActivation=it-voice" })
    }

    @Test
    fun activate_reappliesEnglishTtsLocale_beforeGreeting_andKeepsInteractionLanguage() = runTest {
        val spoken = mutableListOf<String>()
        val requestedLocales = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val speech = object : SpeechOutput {
            override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                spoken += text
                onDone?.invoke()
            }

            override fun stop() = Unit

            override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult {
                requestedLocales += languageTag
                return SpeechLanguageUpdateResult(
                    requestedLanguageTag = languageTag,
                    requestedTtsLocale = "en-US",
                    ttsSetLanguageResult = 0,
                    success = true,
                    activeTtsLocale = "en-US",
                    activeTtsVoice = "en-voice"
                )
            }

            override fun shutdown() = Unit
        }

        val controller = newController(
            scope = this,
            speechOutput = speech,
            onDebugResult = { debug += it },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "en-US", triggerButton = "volume_up") }
        )

        controller.activate()
        advanceUntilIdle()

        assertEquals(listOf("en-US"), requestedLocales)
        assertTrue(spoken.isNotEmpty())
        assertEquals("Hi, I'm Luca. How can I help you?", spoken.first())
        assertTrue(debug.any { it == "persistedInteractionLanguageOnActivation=en-US" })
        assertTrue(debug.any { it == "ttsLocaleReappliedOnActivation=true" })
        assertTrue(debug.any { it == "requestedTtsLocaleOnActivation=en-US" })
        assertTrue(debug.any { it == "activeTtsLocaleAfterActivation=en-US" })
        assertTrue(debug.any { it == "activeTtsVoiceAfterActivation=en-voice" })
    }

    @Test
    fun activate_doesNotResetInteractionLanguageToEnglish_whenPersistedIsItalian() = runTest {
        val requestedLocales = mutableListOf<String>()
        val routedInteractionLanguages = mutableListOf<String?>()
        val questions = mutableListOf<UserQuestion>()
        val controller = newController(
            scope = this,
            speechOutput = object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                    onDone?.invoke()
                }

                override fun stop() = Unit

                override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult {
                    requestedLocales += languageTag
                    return SpeechLanguageUpdateResult(languageTag, "it-IT", 0, true, "it-IT", "it-voice")
                }

                override fun shutdown() = Unit
            },
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    questions += question
                    return guidance()
                }
            },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    routedInteractionLanguages += input.interactionLanguage
                    return IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.UI_NAVIGATION,
                        confidence = 1f
                    )
                }
            }
        )

        controller.activate()
        advanceUntilIdle()
        controller.onSpeechRecognized("dove devo premere")
        advanceUntilIdle()

        assertEquals(listOf("it-IT"), requestedLocales)
        assertEquals(listOf("it-IT"), routedInteractionLanguages)
        assertEquals(1, questions.size)
        assertEquals("it-IT", questions.first().interactionLanguage)
    }

    @Test
    fun activate_emitsTtsReapplyFailureReason_whenLocaleApplyFails() = runTest {
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                    onDone?.invoke()
                }

                override fun stop() = Unit

                override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult =
                    SpeechLanguageUpdateResult(
                        requestedLanguageTag = languageTag,
                        requestedTtsLocale = "it-IT",
                        ttsSetLanguageResult = -1,
                        success = false,
                        activeTtsLocale = "en-US",
                        activeTtsVoice = "en-voice",
                        failureReason = "tts_language_not_supported"
                    )

                override fun shutdown() = Unit
            },
            onDebugResult = { debug += it },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        controller.activate()
        advanceUntilIdle()

        assertTrue(debug.any { it == "ttsLocaleReappliedOnActivation=false" })
        assertTrue(debug.any { it == "ttsReapplyFailureReason=tts_language_not_supported" })
    }

    @Test
    fun silenceTimeoutClosing_usesItalianPhrase_whenInteractionLanguageIsItalian() = runTest {
        val spoken = mutableListOf<String>()
        val speech = object : SpeechOutput {
            override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                spoken += text
                onDone?.invoke()
            }

            override fun stop() = Unit
            override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult =
                SpeechLanguageUpdateResult(languageTag, languageTag, 0, true, languageTag, "test-voice")

            override fun shutdown() = Unit
        }
        val controller = newController(
            scope = this,
            speechOutput = speech,
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        val followupField = DefaultAssistantSessionController::class.java.getDeclaredField("isInFollowupWindow")
        followupField.isAccessible = true
        followupField.setBoolean(controller, true)
        controller.onSpeechRecognitionError(message = "silence", isSilence = true)
        advanceUntilIdle()

        assertTrue(spoken.contains("Va bene, a presto!"))
        assertFalse(spoken.contains("Ok, talk to you soon!"))
    }

    @Test
    fun silenceTimeoutClosing_usesEnglishPhrase_whenInteractionLanguageIsEnglish() = runTest {
        val spoken = mutableListOf<String>()
        val speech = object : SpeechOutput {
            override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                spoken += text
                onDone?.invoke()
            }

            override fun stop() = Unit
            override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult =
                SpeechLanguageUpdateResult(languageTag, languageTag, 0, true, languageTag, "test-voice")

            override fun shutdown() = Unit
        }
        val controller = newController(
            scope = this,
            speechOutput = speech,
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "en-US", triggerButton = "volume_up") }
        )

        val followupField = DefaultAssistantSessionController::class.java.getDeclaredField("isInFollowupWindow")
        followupField.isAccessible = true
        followupField.setBoolean(controller, true)
        controller.onSpeechRecognitionError(message = "silence", isSilence = true)
        advanceUntilIdle()

        assertTrue(spoken.contains("Ok, talk to you soon!"))
    }

    @Test
    fun processingPhrase_usesItalian_whenInteractionLanguageIsItalian() = runTest {
        val spoken = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.UI_NAVIGATION,
                        confidence = 1f
                    )
                }
            }
        )

        controller.onSpeechRecognized("dove devo premere")
        advanceUntilIdle()

        assertTrue(spoken.contains("Sto controllando lo schermo..."))
    }

    @Test
    fun clarificationFallback_usesItalian_whenRouterThrows_andInteractionLanguageIsItalian() = runTest {
        val spoken = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    throw IllegalStateException("router_down")
                }
            }
        )

        controller.onSpeechRecognized("aiutami")
        advanceUntilIdle()

        assertTrue(spoken.contains("Puoi chiarire di cosa hai bisogno?"))
        assertFalse(spoken.any { it == "Can you clarify what you need?" })
    }

    @Test
    fun preScreenLocalTool_invokesToolExecutor() = runTest {
        val toolCalls = mutableListOf<ToolRequest>()
        val controller = newController(
            scope = this,
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.MOVE_OVERLAY,
                        arguments = mapOf("position" to "top_right"),
                        spokenConfirmation = null,
                        confidence = 0.9f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("move please")
        advanceUntilIdle()
        assertEquals(1, toolCalls.size)
        assertEquals("move_overlay", toolCalls.first().name)
    }

    @Test
    fun screenReasoner_toolRequest_isIgnoredAndExecutorNotCalled() = runTest {
        val toolCalls = mutableListOf<ToolRequest>()
        val debug = mutableListOf<String>()
        val controller = newController(
            engineResult = guidance(toolRequest = ToolRequest("move_overlay", mapOf("position" to "top_right"))),
            scope = this,
            onDebugTool = { debug += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.UI_NAVIGATION,
                        confidence = 0.9f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("where do I press")
        advanceUntilIdle()

        assertTrue(toolCalls.isEmpty())
        assertTrue(debug.any { it.contains("tool_rejected:screen_reasoner_tool_disabled") })
    }

    @Test
    fun preScreen_localTool_executesBeforeEngine_withoutTarget() = runTest {
        var engineCalls = 0
        var routedInteractionLanguage: String? = null
        val shown = mutableListOf<RectBounds?>()
        val toolCalls = mutableListOf<ToolRequest>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    engineCalls++
                    return guidance()
                }
            },
            onShowTarget = { shown += it },
            onDebugResult = { debug += it },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    routedInteractionLanguage = input.interactionLanguage
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.MOVE_OVERLAY,
                        arguments = mapOf("position" to "bottom_right"),
                        spokenConfirmation = "Moved.",
                        confidence = 0.9f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("move to the bottom right")
        advanceUntilIdle()

        assertEquals(0, engineCalls)
        assertEquals("it-IT", routedInteractionLanguage)
        assertEquals(1, toolCalls.size)
        assertEquals(1, shown.size)
        assertEquals(null, shown.first())
        assertTrue(debug.any { it.contains("pre_screen_final_branch=local_tool") })
        assertTrue(debug.any { it.contains("assistant_engine_called=false") })
        assertFalse(debug.any { it.contains("assistant_engine_called=true") })
    }

    @Test
    fun preScreen_screenContext_callsEngine_andCanShowTarget() = runTest {
        var engineCalls = 0
        var explicitObservationRequest: ObservationRequest? = null
        val shown = mutableListOf<RectBounds?>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    throw IllegalStateException("Legacy answerQuestion(question) should not be used for screen_context")
                }

                override suspend fun answerQuestion(
                    question: UserQuestion,
                    observationRequest: ObservationRequest
                ): GuidanceResult {
                    engineCalls++
                    explicitObservationRequest = observationRequest
                    return guidance(
                        target = GuidanceTarget(
                            source = TargetSource.SCREENSHOT,
                            nodeId = null,
                            bounds = RectBounds(1, 2, 3, 4),
                            normalizedBox = null,
                            label = "ok",
                            targetConfidence = 0.9f
                        )
                    )
                }
            },
            onShowTarget = { shown += it },
            onDebugResult = { debug += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.UI_NAVIGATION,
                        confidence = 0.1f
                    )
                }
            }
        )

        controller.onSpeechRecognized("where do I press")
        advanceUntilIdle()

        assertEquals(1, engineCalls)
        assertEquals("SCREENSHOT", explicitObservationRequest?.mode?.name)
        assertEquals("m2b_route:ui_navigation", explicitObservationRequest?.reason)
        assertEquals(RectBounds(1, 2, 3, 4), shown.last())
        assertTrue(debug.any { it.contains("pre_screen_final_branch=screen_context") })
        assertTrue(debug.any { it.contains("assistant_engine_called=true") })
        assertTrue(debug.any { it.contains("observation_provider_called=true") })
        assertTrue(debug.any { it.contains("target_highlight_emitted=true") })
    }

    @Test
    fun whereDoIPressToSearch_forcesScreenContext_neverCompletesLocalTool_andEmitsRoutingSpokenTrace() = runTest {
        var engineCalls = 0
        var explicitObservationRequest: ObservationRequest? = null
        val debug = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolRequest>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    throw IllegalStateException("Legacy answerQuestion(question) should not be used for screen_context")
                }

                override suspend fun answerQuestion(
                    question: UserQuestion,
                    observationRequest: ObservationRequest
                ): GuidanceResult {
                    engineCalls++
                    explicitObservationRequest = observationRequest
                    return guidance(spokenText = "Tap the search icon at the top.")
                }
            },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.MOVE_OVERLAY,
                        arguments = mapOf("position" to "top_right"),
                        spokenConfirmation = null,
                        confidence = 1f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, spokenTextOverride = "Fatto.", debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("where do I press to search?")
        advanceUntilIdle()

        assertEquals(1, engineCalls)
        assertEquals("SCREEN_MAP_FIRST", explicitObservationRequest?.mode?.name)
        assertTrue(toolCalls.isEmpty())
        assertFalse(spoken.any { it.equals("fatto", ignoreCase = true) || it.equals("fatto.", ignoreCase = true) })
        assertFalse(spoken.any { it.equals("short answer", ignoreCase = true) })
        assertTrue(debug.any { it.contains("pre_screen_final_branch=screen_context") })
        assertTrue(debug.any { it.contains("pre_screen_degradation_reason=forced_screen_context_ui_question") })
        assertTrue(debug.any { it.contains("per_turn_routing_spoken_trace") })
        assertTrue(debug.any { it.contains("tool_request_present=false") })
        assertTrue(debug.any { it.contains("tool_executed=false") })
        assertTrue(debug.any { it.contains("final_spoken_text_source=rich_reasoner") })
    }

    @Test
    fun preScreen_screenContext_overridesLegacyM1IntentHeuristic() = runTest {
        var engineCalls = 0
        var explicitObservationRequest: ObservationRequest? = null
        val controller = newController(
            scope = this,
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    throw IllegalStateException("Legacy answerQuestion(question) should not be used for screen_context")
                }

                override suspend fun answerQuestion(
                    question: UserQuestion,
                    observationRequest: ObservationRequest
                ): GuidanceResult {
                    engineCalls++
                    explicitObservationRequest = observationRequest
                    return guidance()
                }
            },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.MESSAGE_SAFETY,
                        confidence = 0.9f
                    )
                }
            }
        )

        // Contains legacy M1 assistant-control keywords, but M2B route must win.
        controller.onSpeechRecognized("move your bubble and is this safe?")
        advanceUntilIdle()

        assertEquals(1, engineCalls)
        assertEquals("SCREENSHOT", explicitObservationRequest?.mode?.name)
        assertEquals("m2b_route:message_safety", explicitObservationRequest?.reason)
    }

    @Test
    fun screenContext_placeholderSpokenText_isSanitized_withEnglishFallback_andNoHighlight() = runTest {
        val spoken = mutableListOf<String>()
        val shown = mutableListOf<RectBounds?>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onShowTarget = { shown += it },
            onDebugResult = { debug += it },
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    return GuidanceResult(
                        summary = "s",
                        spokenText = "short answer",
                        rationale = "compact_output_degradation_reason=placeholder_say",
                        target = GuidanceTarget(
                            source = com.example.assistant.core.model.TargetSource.SCREENSHOT,
                            nodeId = null,
                            bounds = RectBounds(10, 10, 40, 40),
                            normalizedBox = null,
                            label = "x",
                            targetConfidence = 0.9f
                        ),
                        toolRequest = null,
                        answerConfidence = 0.8f,
                        visualConfidence = 0.8f
                    )
                }
            },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute =
                    IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.MESSAGE_SAFETY,
                        confidence = 1f
                    )
            },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "en-US", triggerButton = "volume_up") }
        )

        controller.onSpeechRecognized("is this message spam?")
        advanceUntilIdle()

        assertTrue(spoken.isNotEmpty())
        assertFalse(spoken.any { it.equals("short answer", ignoreCase = true) })
        assertTrue(shown.last() == null)
        assertTrue(debug.any { it.contains("compact_output_degradation_reason=") })
        assertTrue(debug.any { it.contains("message_safety_guard_triggered=true") })
    }

    @Test
    fun screenContext_placeholderSpokenText_usesItalianFallback() = runTest {
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            engineResult = guidance(spokenText = "short answer"),
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute =
                    IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.MESSAGE_SAFETY,
                        confidence = 1f
                    )
            },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        controller.onSpeechRecognized("is this message spam?")
        advanceUntilIdle()
        assertTrue(spoken.isNotEmpty())
        assertFalse(spoken.any { it.equals("short answer", ignoreCase = true) })
        assertTrue(debug.any { it.contains("message_safety_guard_triggered=true") })
    }

    @Test
    fun messageSafety_repeatOfUserQuestion_isRejectedAndSanitized() = runTest {
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            engineResult = guidance(
                spokenText = "questo messaggio è una truffa",
                rationale = "compact_screen_map_visible_text_count=1; compact_screen_map_ocr_count=0; compact_screen_map_preview=T1:bonifico urgente; compact_output_degradation_reason=null"
            ),
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute =
                    IntentRoute.ScreenContext(
                        mode = ScreenContextMode.SCREENSHOT,
                        purpose = ScreenContextPurpose.MESSAGE_SAFETY,
                        confidence = 1f
                    )
            },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        controller.onSpeechRecognized("questo messaggio è una truffa?")
        advanceUntilIdle()

        assertTrue(spoken.isNotEmpty())
        assertEquals("Non riesco a leggere o valutare chiaramente questo messaggio.", spoken.last())
        assertFalse(spoken.any { it.equals("questo messaggio è una truffa", ignoreCase = true) })
        assertTrue(debug.any { it.contains("answer_repeats_user_question=true") })
        assertTrue(debug.any { it.contains("message_safety_guard_triggered=true") })
        assertTrue(debug.any { it.contains("FINAL_SPEECH_TRACE") })
        assertTrue(debug.any { it.contains("FINAL_SPEECH_TRACE") && it.contains("final_spoken_text=Non riesco a leggere o valutare chiaramente questo messaggio.") })
    }

    @Test
    fun finalTtsBoundary_generalAnswerRepeat_isRejectedImmediatelyBeforeSpeak() = runTest {
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.GeneralAnswer("questo messaggio è una truffa", 0.95f)
                }
            },
            assistantSettingsProvider = { AssistantSettingsSnapshot(interactionLanguage = "it-IT", triggerButton = "volume_up") }
        )

        controller.onSpeechRecognized("questo messaggio è una truffa?")
        advanceUntilIdle()

        assertTrue(spoken.isNotEmpty())
        assertEquals("Non riesco a leggere o valutare chiaramente questo messaggio.", spoken.last())
        assertTrue(debug.any { it.contains("FINAL_SPEECH_TRACE") && it.contains("last_mile_guard_triggered=true") })
        assertTrue(debug.any { it.contains("FINAL_SPEECH_TRACE") && it.contains("final_spoken_text_source=last_mile_repeat_guard_fallback") })
    }

    @Test
    fun preScreen_generalAnswer_skipsEngineAndTarget() = runTest {
        var engineCalls = 0
        val shown = mutableListOf<RectBounds?>()
        val spoken = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    engineCalls++
                    return guidance()
                }
            },
            onShowTarget = { shown += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.GeneralAnswer("I can help with settings.", 0.9f)
                }
            }
        )

        controller.onSpeechRecognized("what can you do")
        advanceUntilIdle()
        assertEquals(0, engineCalls)
        assertEquals(null, shown.last())
        assertTrue(spoken.contains("I can help with settings."))
    }

    @Test
    fun preScreen_clarification_skipsEngineAndTarget() = runTest {
        var engineCalls = 0
        val shown = mutableListOf<RectBounds?>()
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    engineCalls++
                    return guidance()
                }
            },
            onShowTarget = { shown += it },
            onDebugResult = { debug += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.Clarification("Can you clarify?", 1f)
                }
            }
        )

        controller.onSpeechRecognized("help me")
        advanceUntilIdle()
        assertEquals(0, engineCalls)
        assertEquals(null, shown.last())
        assertTrue(spoken.contains("Can you clarify?"))
        assertTrue(debug.any { it.contains("stt_transcript=help me") })
        assertTrue(debug.any { it.contains("activeIntentRouter=") })
        assertTrue(debug.any { it.contains("preScreenFallbackRouterUsed=") })
        assertTrue(debug.any { it.contains("pre_screen_final_branch=clarification") })
        assertTrue(debug.any { it.contains("assistant_engine_called=false") })
        assertTrue(debug.any { it.contains("screenshot_observation_requested=false") })
    }

    @Test
    fun preScreen_compactScreen_withoutMode_routesToScreenContext_callsEngine_andSkipsClarificationSpeech() = runTest {
        var engineCalls = 0
        val debug = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    throw IllegalStateException("Legacy answerQuestion(question) should not be used for screen_context")
                }

                override suspend fun answerQuestion(
                    question: UserQuestion,
                    observationRequest: ObservationRequest
                ): GuidanceResult {
                    engineCalls++
                    return guidance(spokenText = "This looks like a message on your screen.")
                }
            },
            intentRouter = GemmaIntentRouter(
                model = object : TextOnlyRouteModel {
                    override suspend fun generateRouteJson(prompt: String): String = """{"r":"screen"}"""
                }
            )
        )

        controller.onSpeechRecognized("Questo messaggio è una truffa?")
        advanceUntilIdle()

        assertEquals(1, engineCalls)
        assertTrue(debug.any { it.contains("pre_screen_raw={\"r\":\"screen\"}") })
        assertTrue(debug.any { it.contains("pre_screen_final_branch=screen_context") })
        assertTrue(debug.any { it.contains("assistant_engine_called=true") })
        assertTrue(
            debug.any {
                it.contains("per_turn_routing_spoken_trace") &&
                    it.contains("pre_screen_raw_output={\"r\":\"screen\"}") &&
                    it.contains("pre_screen_final_branch=screen_context") &&
                    it.contains("assistant_engine_called=true")
            }
        )
        assertTrue(spoken.none { it.contains("clarify", ignoreCase = true) || it.contains("chiar", ignoreCase = true) })
    }

    @Test
    fun preScreen_routerException_isSurfacedInDebug_andClarifies() = runTest {
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val controller = newController(
            scope = this,
            speechOutput = fakeSpeech(spoken),
            onDebugResult = { debug += it },
            onDebugError = { errors += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    throw IllegalStateException("router_down")
                }
            }
        )

        controller.onSpeechRecognized("describe the screen")
        advanceUntilIdle()

        assertTrue(errors.any { it.contains("Pre-screen router failure") })
        assertTrue(debug.any { it.contains("pre_screen_exception=") })
        assertTrue(debug.any { it.contains("pre_screen_final_branch=clarification") })
        assertTrue(spoken.any { it.contains("clarify", ignoreCase = true) })
    }

    @Test
    fun toolSpokenOverride_replacesModelSpokenText() = runTest {
        val spoken = mutableListOf<String>()
        val speech = fakeSpeech(spoken)
        val controller = newController(
            scope = this,
            speechOutput = speech,
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.MOVE_OVERLAY,
                        arguments = mapOf("position" to "top_right"),
                        spokenConfirmation = "fallback confirmation",
                        confidence = 0.9f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    return ToolExecutionResult(success = true, spokenTextOverride = "tool override", debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("move please")
        advanceUntilIdle()
        assertTrue(spoken.contains("tool override"))
    }

    @Test
    fun findApp_toolSpokenText_isExact_andClosingPhraseNotMergedOrAppended() = runTest {
        val spoken = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val speech = object : SpeechOutput {
            override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
                spoken += text
                onDone?.invoke()
            }

            override fun stop() = Unit

            override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult =
                SpeechLanguageUpdateResult(
                    requestedLanguageTag = languageTag,
                    requestedTtsLocale = languageTag,
                    ttsSetLanguageResult = 0,
                    success = true,
                    activeTtsLocale = languageTag,
                    activeTtsVoice = "test-voice"
                )

            override fun shutdown() = Unit
        }
        val expected = "Cerco Spotify nel Play Store. Se è già installata, premi Apri. Altrimenti, premi Installa."
        val controller = newController(
            scope = this,
            speechOutput = speech,
            onDebugResult = { debug += it },
            assistantSettingsProvider = {
                AssistantSettingsSnapshot(
                    interactionLanguage = "it-IT",
                    triggerButton = null
                )
            },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.OPEN_PLAY_STORE_AND_SEARCH,
                        arguments = mapOf("app_name" to "Spotify"),
                        spokenConfirmation = null,
                        confidence = 1f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    return ToolExecutionResult(success = true, spokenTextOverride = expected, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("apri Spotify")
        advanceUntilIdle()

        assertEquals(1, spoken.size)
        assertEquals(expected, spoken.first())
        assertTrue(spoken.none { it.contains("a presto", ignoreCase = true) })
        assertTrue(debug.any { it.contains("toolSpokenTextOverride=$expected") })
        assertTrue(debug.any { it.contains("finalSpokenText=$expected") })
        assertTrue(debug.any { it.contains("sessionClosingPhraseAppended=false") })
        assertTrue(debug.any { it.contains("sessionClosingPhrase=Va bene, a presto!") })
    }

    @Test
    fun toolExecutionFailure_doesNotCrash() = runTest {
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            onDebugTool = { debug += it },
            intentRouter = object : IntentRouter {
                override suspend fun route(input: IntentRouterInput): IntentRoute {
                    return IntentRoute.LocalTool(
                        toolName = AssistantTools.MOVE_OVERLAY,
                        arguments = mapOf("position" to "top_right"),
                        spokenConfirmation = null,
                        confidence = 0.9f
                    )
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    throw IllegalStateException("boom")
                }
            }
        )

        controller.onSpeechRecognized("move please")
        advanceUntilIdle()
        assertTrue(debug.any { it.contains("tool_failed") })
    }

    @Test
    fun staleTarget_isClearedAcrossTurns() = runTest {
        val shownTargets = mutableListOf<RectBounds?>()
        var count = 0
        val controller = newController(
            scope = this,
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    count++
                    return if (count == 1) {
                        guidance(
                            target = GuidanceTarget(
                                source = TargetSource.SCREENSHOT,
                                nodeId = null,
                                bounds = RectBounds(10, 10, 20, 20),
                                normalizedBox = null,
                                label = "x",
                                targetConfidence = 0.8f
                            )
                        )
                    } else {
                        guidance(target = null)
                    }
                }
            },
            onShowTarget = { shownTargets += it }
        )

        controller.onSpeechRecognized("first")
        advanceUntilIdle()
        controller.onSpeechRecognized("second")
        advanceUntilIdle()

        assertEquals(2, shownTargets.size)
        assertEquals(RectBounds(10, 10, 20, 20), shownTargets[0])
        assertEquals(null, shownTargets[1])
    }

    @Test
    fun staleScreenReasonerToolRequest_isIgnoredAcrossTurns() = runTest {
        val toolCalls = mutableListOf<ToolRequest>()
        var count = 0
        val controller = newController(
            scope = this,
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    count++
                    return if (count == 1) {
                        guidance(toolRequest = ToolRequest("move_overlay", mapOf("position" to "top_right")))
                    } else {
                        guidance(toolRequest = null)
                    }
                }
            },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("first")
        advanceUntilIdle()
        controller.onSpeechRecognized("second")
        advanceUntilIdle()

        assertTrue(toolCalls.isEmpty())
    }

    @Test
    fun unknownScreenReasonerToolRequest_isRejectedAndExecutorNotCalled() = runTest {
        val toolCalls = mutableListOf<ToolRequest>()
        val debug = mutableListOf<String>()
        val controller = newController(
            scope = this,
            engineResult = guidance(toolRequest = ToolRequest("do_bad_thing", mapOf("x" to "1"))),
            onDebugTool = { debug += it },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    toolCalls += request
                    return ToolExecutionResult(success = true, debugMessage = "ok")
                }
            }
        )

        controller.onSpeechRecognized("do something unsafe")
        advanceUntilIdle()

        assertTrue(toolCalls.isEmpty())
        assertTrue(debug.any { it.contains("tool_rejected:screen_reasoner_tool_disabled") })
    }

    @Test
    fun debugDiagnostics_reportsParsedResult_andToolOutcome_andLatestError() = runTest {
        val debugResult = mutableListOf<String>()
        val debugTool = mutableListOf<String>()
        val debugError = mutableListOf<String>()
        val controller = newController(
            scope = this,
            engineResult = guidance(toolRequest = ToolRequest("move_overlay", mapOf("position" to "top_right"))),
            onDebugTool = { debugTool += it },
            onDebugResult = { debugResult += it },
            onDebugError = { debugError += it },
            toolExecutor = object : ToolExecutor {
                override suspend fun execute(request: ToolRequest): ToolExecutionResult {
                    return ToolExecutionResult(success = true, debugMessage = "tool_ok:move_overlay")
                }
            }
        )

        controller.onSpeechRecognized("move")
        advanceUntilIdle()
        assertTrue(debugResult.any { it.contains("GuidanceResult(") })
        assertTrue(debugTool.any { it.contains("tool_rejected:screen_reasoner_tool_disabled") })

        val failing = newController(
            scope = this,
            onDebugError = { debugError += it },
            engine = object : AssistantEngine {
                override suspend fun answerQuestion(question: UserQuestion): GuidanceResult {
                    throw IllegalStateException("engine_down")
                }
            }
        )
        failing.onSpeechRecognized("hi")
        advanceUntilIdle()
        assertTrue(debugError.any { it.contains("Assistant engine failure") })
    }

    private fun guidance(
        spokenText: String = "hello",
        target: GuidanceTarget? = null,
        toolRequest: ToolRequest? = null,
        rationale: String? = null
    ): GuidanceResult = GuidanceResult(
        summary = "s",
        spokenText = spokenText,
        rationale = rationale,
        target = target,
        toolRequest = toolRequest,
        answerConfidence = 0.8f,
        visualConfidence = 0.8f
    )

    private fun fakeSpeech(spoken: MutableList<String>) = object : SpeechOutput {
        override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?, onError: ((Throwable?) -> Unit)?) {
            spoken += text
        }

        override fun stop() = Unit
        override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult =
            SpeechLanguageUpdateResult(
                requestedLanguageTag = languageTag,
                requestedTtsLocale = languageTag,
                ttsSetLanguageResult = 0,
                success = true,
                activeTtsLocale = languageTag,
                activeTtsVoice = "test-voice"
            )
        override fun shutdown() = Unit
    }

    private fun newController(
        engineResult: GuidanceResult = guidance(),
        engine: AssistantEngine = object : AssistantEngine {
            override suspend fun answerQuestion(question: UserQuestion): GuidanceResult = engineResult
        },
        speechOutput: SpeechOutput = fakeSpeech(mutableListOf()),
        onShowTarget: (RectBounds?) -> Unit = {},
        onDebugTool: (String) -> Unit = {},
        onDebugResult: (String) -> Unit = {},
        onDebugError: (String) -> Unit = {},
        assistantSettingsProvider: () -> AssistantSettingsSnapshot? = { null },
        intentRouter: IntentRouter = DefaultScreenContextIntentRouter(),
        toolExecutor: ToolExecutor? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    ) = DefaultAssistantSessionController(
        assistantEngine = engine,
        speechOutput = speechOutput,
        intentRouter = intentRouter,
        assistantSettingsProvider = assistantSettingsProvider,
        onStartListening = {},
        onStopListening = {},
        onShowTarget = onShowTarget,
        onClearTarget = {},
        onDebugResult = onDebugResult,
        onDebugError = onDebugError,
        onDebugTool = onDebugTool,
        toolExecutor = toolExecutor,
        scope = scope
    )
}
