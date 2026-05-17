package com.example.assistant.gemma

import com.example.assistant.core.engine.Reasoner
import com.example.assistant.core.engine.ReasonerAvailability
import com.example.assistant.core.engine.ReasonerInput
import com.example.assistant.core.engine.ReasonerOutput
import com.example.assistant.core.model.ConversationRole
import com.example.assistant.core.parser.GuidanceResponseParser
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GemmaReasoner(
    private val config: GemmaConfig,
    private val parser: GuidanceResponseParser = GuidanceResponseParser()
) : Reasoner {

    @Volatile
    private var diagnostics = GemmaDiagnostics(
        liteRtDependencyPresent = detectLiteRtDependencyPresent(),
        modelPath = config.modelPath,
        modelFileExists = File(config.modelPath).exists(),
        engineInitialized = false,
        selectedBackend = config.backendName,
        multimodalImageInputEnabled = detectMultimodalImageInputPresent(),
        structuredOutputEnabled = false,
        structuredOutputBlocker = STRUCTURED_OUTPUT_BLOCKER,
        latestGemmaError = null,
        latestRawModelOutput = null
    )

    private val initMutex = Mutex()
    private var engine: Any? = null
    private var session: Any? = null
    private var conversation: Any? = null

    suspend fun checkAvailability(requireStructuredOutput: Boolean): ReasonerAvailability {
        Log.i(
            TAG,
            "checkAvailability(requireStructuredOutput=$requireStructuredOutput, modelPath=${config.modelPath})"
        )
        val preflight = if (requireStructuredOutput) {
            checkReleaseHardRequirementsWithoutInit()
        } else {
            checkDebugHardRequirementsWithoutInit()
        }
        if (preflight != null) {
            Log.w(TAG, "Preflight unavailable: ${preflight.technicalReason}")
            return preflight
        }

        return try {
            ensureSessionInitialized()
            if (conversation == null) {
                Log.w(TAG, "Availability check failed: Conversation API unavailable for multimodal inference")
                return unavailable(
                    userMessage = "Luca debug AI is unavailable because image reasoning is not supported by the current runtime path.",
                    technical =
                        "Conversation API is unavailable; Session.generateContent image path requires preprocessed tensors (SessionAdvanced) which are not exposed in public LiteRT-LM Android API."
                )
            }
            Log.i(TAG, "Availability check passed, session initialized")
            ReasonerAvailability.Available
        } catch (t: Throwable) {
            Log.e(TAG, "Availability check failed during engine/session init", t)
            unavailable(
                userMessage = "Luca can't run on this device because the on-device AI engine failed to initialize.",
                technical = "Gemma init failed: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }

    override suspend fun reason(input: ReasonerInput): ReasonerOutput {
        val compactPrompt = input.observation.screenMap?.let { map ->
            GemmaCompactScreenMapPromptBuilder.build(
                GemmaCompactScreenMapPromptInput(
                    userRequest = input.question,
                    interactionLanguage = input.interactionLanguage,
                    map = map
                )
            )
        }

        if (compactPrompt != null) {
            ensureSessionInitialized()
            val localConversation = conversation ?: return fallbackNoMultimodalRuntimePath()
            return try {
                val raw = withContext(Dispatchers.IO) {
                    runBestEffortTextOnlyTurn(localConversation, compactPrompt)
                }
                diagnostics = diagnostics.copy(latestRawModelOutput = raw, latestGemmaError = null)
                parser.parse(raw).copy(
                    compactPromptIncludesInteractionLanguage = compactPrompt.contains("Interaction language:"),
                    compactPromptCharCount = compactPrompt.length
                )
            } catch (t: Throwable) {
                fallbackInferenceError()
            }
        }

        val screenshotBytes = input.observation.screenshot?.bytes ?: return fallbackNoScreenshot(input)
        ensureSessionInitialized()

        val prompt = buildPrompt(input)
        Log.i(TAG, "reason(questionLen=${input.question.length}, screenshotBytes=${screenshotBytes.size})")

        // use the current conversation, do not create a new one at every question from the user
        val localConversation = conversation
            ?: return fallbackNoMultimodalRuntimePath()

        return try {
            val raw = withContext(Dispatchers.IO) {
                runBestEffortMultimodalTurn(
                    localConversation = localConversation,
                    prompt = prompt,
                    imageBytes = screenshotBytes
                )
            }
            diagnostics = diagnostics.copy(latestRawModelOutput = raw, latestGemmaError = null)
            Log.i(TAG, "Inference succeeded, rawOutputLen=${raw.length}")
            parser.parse(raw)
        } catch (t: Throwable) {
            val root = unwrapInvocationTarget(t)
            if (isSessionImagePreprocessRequiredError(root) ||
                (root.cause != null && isSessionImagePreprocessRequiredError(root.cause!!))
            ) {
                val technical = "LiteRT-LM Session image path requires preprocessed tensors..."
                diagnostics = diagnostics.copy(latestGemmaError = technical)
                Log.e(TAG, "Inference failed due to Session image preprocessing requirement", root)
                return fallbackNoMultimodalRuntimePath()
            }
            fallbackInferenceError()
        }
    }

    private fun runBestEffortTextOnlyTurn(localConversation: Any, prompt: String): String {
        val contentTextClass = Class.forName("com.google.ai.edge.litertlm.Content\$Text")
        val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
        val text = contentTextClass.getDeclaredConstructor(String::class.java).newInstance(prompt)
        val contents = contentsClass.getDeclaredConstructor(List::class.java).newInstance(listOf(text))

        val sendMessageMethod = localConversation.javaClass.methods.firstOrNull {
            it.name == "sendMessage" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0].isAssignableFrom(contents.javaClass) &&
                Map::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: throw NoSuchMethodException("Conversation.sendMessage(Contents, Map) not found")

        val responseMessage = sendMessageMethod.invoke(localConversation, contents, emptyMap<String, Any>())
        return extractMessageText(requireNotNull(responseMessage))
    }

    fun close() {
        try {
            session?.javaClass?.getMethod("close")?.invoke(session)
        } catch (_: Throwable) {
        }
        try {
            conversation?.javaClass?.getMethod("close")?.invoke(conversation)
        } catch (_: Throwable) {
        }
        try {
            engine?.javaClass?.getMethod("close")?.invoke(engine)
        } catch (_: Throwable) {
        }
        session = null
        conversation = null
        engine = null
        diagnostics = diagnostics.copy(engineInitialized = false)
    }

    fun structuredOutputUnavailableReason(): String = STRUCTURED_OUTPUT_BLOCKER
    fun modelPath(): String = config.modelPath
    fun isStructuredOutputEnabledNow(): Boolean = false
    fun isMultimodalImageInputEnabled(): Boolean = diagnostics.multimodalImageInputEnabled
    fun selectedBackendName(): String = diagnostics.selectedBackend
    fun isLiteRtDependencyPresent(): Boolean = diagnostics.liteRtDependencyPresent
    fun isModelFilePresent(): Boolean = File(config.modelPath).exists()
    fun latestGemmaError(): String? = diagnostics.latestGemmaError
    fun latestRawModelOutput(): String? = diagnostics.latestRawModelOutput
    fun isEngineInitialized(): Boolean = diagnostics.engineInitialized

    fun checkReleaseHardRequirementsWithoutInit(): ReasonerAvailability.Unavailable? {
        if (!diagnostics.liteRtDependencyPresent) {
            return unavailable(
                userMessage = "Luca can't run on this device because the on-device AI engine is not available.",
                technical = "LiteRT-LM dependency is missing at runtime"
            )
        }
        if (!File(config.modelPath).exists()) {
            return unavailable(
                userMessage = "Luca can't run on this device because the on-device AI model is missing.",
                technical = "Gemma model missing at ${config.modelPath}"
            )
        }
        if (!diagnostics.multimodalImageInputEnabled) {
            return unavailable(
                userMessage = "Luca can't run on this device because screenshot input is unavailable.",
                technical = "LiteRT-LM InputData.Image class unavailable"
            )
        }
        return unavailable(
            userMessage = "Luca can't run on this device because safe structured AI output is unavailable.",
            technical = STRUCTURED_OUTPUT_BLOCKER
        )
    }

    fun checkDebugHardRequirementsWithoutInit(): ReasonerAvailability.Unavailable? {
        if (!diagnostics.liteRtDependencyPresent) {
            return unavailable(
                userMessage = "Luca debug AI is unavailable because LiteRT-LM is missing.",
                technical = "LiteRT-LM dependency is missing at runtime"
            )
        }
        if (!File(config.modelPath).exists()) {
            return unavailable(
                userMessage = "Luca debug AI is unavailable because the model is missing.",
                technical = "Gemma model missing at ${config.modelPath}"
            )
        }
        if (!diagnostics.multimodalImageInputEnabled) {
            return unavailable(
                userMessage = "Luca debug AI is unavailable because screenshot input is unavailable.",
                technical = "LiteRT-LM InputData.Image class unavailable"
            )
        }
        if (!detectConversationApiSurfacePresent()) {
            return unavailable(
                userMessage = "Luca debug AI is unavailable because image reasoning is not supported by this LiteRT-LM runtime.",
                technical =
                    "Conversation API classes/methods unavailable; Session.generateContent image path is not usable without SessionAdvanced preprocessing."
            )
        }
        return null
    }

    private suspend fun ensureSessionInitialized(): Any {
        return initMutex.withLock {
            if (conversation != null && session != null) {
                return conversation as Any
            }
            if (conversation != null) {
                return conversation as Any
            }
            if (session != null) {
                val existingEngine = engine
                if (existingEngine != null) {
                    val recoveredConversation = createConversationIfSupported(existingEngine)
                    if (recoveredConversation != null) {
                        conversation = recoveredConversation
                        return recoveredConversation
                    }
                }
                return session as Any
            }

            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Initializing LiteRT-LM engine/session")
                    val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
                    val engineConfigClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
                    val backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
                    val cpuClass = Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")
                    val sessionConfigClass = Class.forName("com.google.ai.edge.litertlm.SessionConfig")
                    val samplerConfigClass = Class.forName("com.google.ai.edge.litertlm.SamplerConfig")

                    val cpuBackend = try {
                        cpuClass.getDeclaredConstructor().newInstance()
                    } catch (_: Throwable) {
                        cpuClass.getDeclaredConstructor(Int::class.javaObjectType).newInstance(null)
                    }

                    val cacheDir = config.cacheDirPath
                    if (!cacheDir.isNullOrBlank()) {
                        try {
                            File(cacheDir).mkdirs()
                        } catch (_: Throwable) {
                        }
                    }

                    val engineConfig = engineConfigClass.getDeclaredConstructor(
                        String::class.java,
                        backendClass,
                        backendClass,
                        backendClass,
                        Integer::class.java,
                        Integer::class.java,
                        String::class.java
                    ).newInstance(
                        config.modelPath,
                        cpuBackend,
                        cpuBackend,
                        cpuBackend,
                        null,
                        Integer.valueOf(config.maxNumImages),
                        cacheDir
                    )
                    Log.i(TAG, "EngineConfig(modelPath=${config.modelPath}, cacheDir=${cacheDir ?: "<default>"})")

                    val createdEngine = engineClass.getDeclaredConstructor(engineConfigClass)
                        .newInstance(engineConfig)

                    invokeEngineInitializeIfPresent(createdEngine)

                    val createdConversation = createConversationIfSupported(createdEngine)

                    val samplerConfig = samplerConfigClass.getDeclaredConstructor(
                        Int::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).newInstance(config.maxOutputTokens, 0.9, 0.2, 7)

                    Log.i(TAG, "SamplerConfig(maxOutputTokens=${config.maxOutputTokens}, topP=0.9, temperature=0.2, topK=7)")

                    val sessionConfig = sessionConfigClass.getDeclaredConstructor(samplerConfigClass)
                        .newInstance(samplerConfig)

                    val createdSession = if (createdConversation == null) {
                        try {
                            createdEngine.javaClass
                                .getMethod("createSession", sessionConfigClass)
                                .invoke(createdEngine, sessionConfig)
                        } catch (_: NoSuchMethodException) {
                            // Keep compatibility with API variants that expose createSession() without args.
                            createdEngine.javaClass
                                .getMethod("createSession")
                                .invoke(createdEngine)
                        }
                    } else {
                        Log.i(TAG, "Skipping Session creation because Conversation API is available")
                        null
                    }

                    engine = createdEngine
                    session = createdSession
                    conversation = createdConversation
                    diagnostics = diagnostics.copy(
                        modelFileExists = File(config.modelPath).exists(),
                        engineInitialized = true,
                        latestGemmaError = null,
                        structuredOutputEnabled = false,
                        structuredOutputBlocker = STRUCTURED_OUTPUT_BLOCKER
                    )
                    Log.i(
                        TAG,
                        "Engine/session initialized successfully, conversationAvailable=${createdConversation != null}"
                    )
                    createdSession ?: createdConversation ?: createdEngine
                } catch (t: Throwable) {
                    val root = unwrapInvocationTarget(t)
                    diagnostics = diagnostics.copy(
                        engineInitialized = false,
                        latestGemmaError = "Gemma init failed: ${root.message ?: root::class.java.simpleName}"
                    )
                    Log.e(TAG, "Engine/session init failed", root)
                    throw root
                }
            }
        }
    }

    private suspend fun createFreshConversationForTurn(): Any? {
        return initMutex.withLock {
            val localEngine = engine ?: return@withLock null
            createConversationIfSupported(localEngine)
        }
    }

    private fun closeRuntimeObjectSafely(obj: Any) {
        try {
            obj.javaClass.getMethod("close").invoke(obj)
        } catch (_: Throwable) {
        }
    }

    private fun invokeEngineInitializeIfPresent(createdEngine: Any) {
        val initializeMethod = createdEngine.javaClass.methods.firstOrNull {
            it.name == "initialize" && it.parameterCount == 0
        } ?: return
        Log.i(TAG, "Calling Engine.initialize() before session creation")
        initializeMethod.invoke(createdEngine)
    }

    private fun unwrapInvocationTarget(t: Throwable): Throwable {
        return when (t) {
            is InvocationTargetException -> t.targetException ?: t.cause ?: t
            else -> t
        }
    }

    private fun createInputDataText(prompt: String): Any {
        val textClass = Class.forName("com.google.ai.edge.litertlm.InputData\$Text")
        return textClass.getDeclaredConstructor(String::class.java).newInstance(prompt)
    }

    private fun createInputDataImage(bytes: ByteArray): Any {
        val imageClass = Class.forName("com.google.ai.edge.litertlm.InputData\$Image")
        return imageClass.getDeclaredConstructor(ByteArray::class.java).newInstance(bytes)
    }

    private fun detectConversationApiSurfacePresent(): Boolean {
        return try {
            val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
            val conversationConfigClass = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
            Class.forName("com.google.ai.edge.litertlm.Contents")
            Class.forName("com.google.ai.edge.litertlm.Content\$Text")
            Class.forName("com.google.ai.edge.litertlm.Content\$ImageBytes")
            engineClass.methods.any {
                it.name == "createConversation" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == conversationConfigClass.name
            }
        true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createConversationIfSupported(createdEngine: Any): Any? {
        return try {
            val conversationConfig = createConversationConfigForRuntime()
            val createConversationMethod = createdEngine.javaClass.methods.firstOrNull {
                it.name == "createConversation" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == conversationConfig.javaClass.name
            } ?: createdEngine.javaClass.declaredMethods.firstOrNull {
                it.name == "createConversation" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == conversationConfig.javaClass.name
            }

            val conversation = if (createConversationMethod != null) {
                createConversationMethod.isAccessible = true
                createConversationMethod.invoke(createdEngine, conversationConfig)
            } else {
                invokeCreateConversationDefaultIfPresent(createdEngine, conversationConfig)
                    ?: throw NoSuchMethodException(
                        "Engine.createConversation(ConversationConfig) and Engine.createConversation\$default(...) not found"
                    )
            }

            Log.i(TAG, "Conversation API available and initialized")
            conversation
        } catch (t: Throwable) {
            val root = unwrapInvocationTarget(t)
            Log.w(TAG, "Conversation API unavailable, falling back to Session API", root)
            null
        }
    }

    private fun invokeCreateConversationDefaultIfPresent(createdEngine: Any, conversationConfig: Any): Any? {
        val engineClass = createdEngine.javaClass
        val defaultMethod = (engineClass.methods + engineClass.declaredMethods).firstOrNull {
            it.name == "createConversation\$default" &&
                it.parameterCount == 4 &&
                it.parameterTypes[0].isAssignableFrom(engineClass) &&
                it.parameterTypes[1].name == conversationConfig.javaClass.name
        } ?: return null

        defaultMethod.isAccessible = true
        // Kotlin synthetic default-call bridge: (receiver, arg1, mask, marker)
        return defaultMethod.invoke(null, createdEngine, conversationConfig, 0, null)
    }

    private fun createConversationConfigForRuntime(): Any {
        val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
        val conversationConfigClass = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
        val emptyContents = contentsClass
            .getDeclaredConstructor(List::class.java)
            .newInstance(emptyList<Any>())

        val constructors = conversationConfigClass.declaredConstructors
            .filter { ctor -> ctor.parameterTypes.none { it.name.contains("DefaultConstructorMarker") } }
            .sortedBy { it.parameterCount }

        var lastError: Throwable? = null
        for (ctor in constructors) {
            val args = buildConversationConfigArgs(ctor.parameterTypes, emptyContents, contentsClass) ?: continue
            try {
                ctor.isAccessible = true
                val instance = ctor.newInstance(*args)
                Log.i(TAG, "ConversationConfig initialized via constructor with ${ctor.parameterCount} args")
                return instance
            } catch (t: Throwable) {
                lastError = unwrapInvocationTarget(t)
            }
        }

        throw IllegalStateException(
            "Unable to construct ConversationConfig for runtime API variant",
            lastError
        )
    }

    private fun buildConversationConfigArgs(
        parameterTypes: Array<Class<*>>,
        emptyContents: Any,
        contentsClass: Class<*>
    ): Array<Any?>? {
        return parameterTypes.map { type ->
            when {
                type == contentsClass -> emptyContents
                List::class.java.isAssignableFrom(type) -> emptyList<Any>()
                Map::class.java.isAssignableFrom(type) -> emptyMap<String, Any>()
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 0
                type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> 0L
                type == Float::class.javaPrimitiveType || type == Float::class.javaObjectType -> 0f
                type == Double::class.javaPrimitiveType || type == Double::class.javaObjectType -> 0.0
                type.isPrimitive -> return null
                else -> null
            }
        }.toTypedArray()
    }

    private fun runConversationTurn(localConversation: Any, prompt: String, imageBytes: ByteArray): String {
        val contents = createConversationContents(prompt = prompt, imageBytes = imageBytes)

        val sendMessageMethod = localConversation.javaClass.methods.firstOrNull {
            it.name == "sendMessage" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0].isAssignableFrom(contents.javaClass) &&
                Map::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: throw NoSuchMethodException("Conversation.sendMessage(Contents, Map) not found")

        val responseMessage = sendMessageMethod.invoke(localConversation, contents, emptyMap<String, Any>())
        return extractMessageText(requireNotNull(responseMessage))
    }

    private fun runBestEffortMultimodalTurn(
        localConversation: Any?,
        prompt: String,
        imageBytes: ByteArray
    ): String {
        if (localConversation == null) {
            val failure = IllegalStateException(
                "Conversation API is unavailable; Session image path requires SessionAdvanced preprocessing"
            )
            Log.e(TAG, "Multimodal inference unavailable: conversation runtime path not initialized", failure)
            throw failure
        }

        return try {
            Log.i(TAG, "Using Conversation API for multimodal inference")
            runConversationTurn(localConversation, prompt, imageBytes)
        } catch (t: Throwable) {
            val root = unwrapInvocationTarget(t)
            Log.e(TAG, "Conversation API failed for multimodal turn", root)
            throw IllegalStateException("Conversation multimodal turn failed", root)
        }
    }

    private fun isSessionImagePreprocessRequiredError(t: Throwable): Boolean {
        val msg = t.message?.lowercase() ?: return false
        return msg.contains("image must be preprocessed") && msg.contains("sessionadvanced")
    }

    private fun runSessionMultimodalTurn(localSession: Any, prompt: String, imageBytes: ByteArray): String {
        val textInput = createInputDataText(prompt)
        val imageInput = createInputDataImage(imageBytes)

        val listMethod = localSession.javaClass.methods.firstOrNull {
            it.name == "generateContent" &&
                it.parameterCount == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (listMethod != null) {
            Log.i(TAG, "Using Session.generateContent(List<InputData>) for multimodal inference")
            val raw = listMethod.invoke(localSession, listOf(textInput, imageInput))
            return raw?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Session.generateContent returned empty response")
        }

        val arrayMethod = localSession.javaClass.methods.firstOrNull {
            it.name == "generateContent" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isArray
        } ?: throw NoSuchMethodException("Session.generateContent(List/InputData[]) not found")

        val inputDataClass = Class.forName("com.google.ai.edge.litertlm.InputData")
        val inputArray = java.lang.reflect.Array.newInstance(inputDataClass, 2)
        java.lang.reflect.Array.set(inputArray, 0, textInput)
        java.lang.reflect.Array.set(inputArray, 1, imageInput)
        Log.i(TAG, "Using Session.generateContent(InputData[]) for multimodal inference")
        val raw = arrayMethod.invoke(localSession, inputArray)
        return raw?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Session.generateContent returned empty response")
    }

    private fun supportsSessionGenerateContent(localSession: Any): Boolean {
        return localSession.javaClass.methods.any {
            it.name == "generateContent" &&
                it.parameterCount == 1 &&
                (List::class.java.isAssignableFrom(it.parameterTypes[0]) || it.parameterTypes[0].isArray)
        }
    }

    private fun detectSessionMultimodalApiSurfacePresent(): Boolean {
        return try {
            val sessionClass = Class.forName("com.google.ai.edge.litertlm.Session")
            sessionClass.methods.any {
                it.name == "generateContent" &&
                    it.parameterCount == 1 &&
                    (List::class.java.isAssignableFrom(it.parameterTypes[0]) || it.parameterTypes[0].isArray)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun createConversationContents(prompt: String, imageBytes: ByteArray): Any {
        val textClass = Class.forName("com.google.ai.edge.litertlm.Content\$Text")
        val imageClass = Class.forName("com.google.ai.edge.litertlm.Content\$ImageBytes")
        val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
        val text = textClass.getDeclaredConstructor(String::class.java).newInstance(prompt)
        val image = imageClass.getDeclaredConstructor(ByteArray::class.java).newInstance(imageBytes)
        return contentsClass.getDeclaredConstructor(List::class.java).newInstance(listOf(text, image))
    }

    private fun extractMessageText(message: Any): String {
        val messageContents = message.javaClass.getMethod("getContents").invoke(message)
        val parts = messageContents.javaClass.getMethod("getContents").invoke(messageContents) as List<*>
        val textParts = parts.mapNotNull { part ->
            part ?: return@mapNotNull null
            if (part.javaClass.name.endsWith("\$Text")) {
                part.javaClass.getMethod("getText").invoke(part) as? String
            } else {
                null
            }
        }
        return textParts.joinToString("\n").ifBlank {
            throw IllegalStateException("Conversation response did not include text content")
        }
    }

    private fun buildPrompt(input: ReasonerInput): String {
        val packageHint = input.observation.packageName ?: "unknown"
        val titleHint = input.observation.screenTitle ?: "unknown"
        val conversationHistory = buildConversationHistoryBlock(input)
        return """
            You are Luca, a voice assistant helping elderly or non-tech-savvy users understand what is on their phone screen.
            You are the screen reasoner only. Do not choose assistant-control tools.
            Return exactly one JSON object with this schema:
            {
              "summary": "string",
              "spoken_text": "string",
              "rationale": "string or null",
              "target": {
                "source": "SCREENSHOT",
                "node_id": null,
                "box_2d": [0, 0, 0, 0],
                "label": "string or null",
                "target_confidence": 0.0
              },
              "tool_request": null,
              "answer_confidence": 0.0,
              "visual_confidence": 0.0
            }
            Rules:
            - No markdown.
            - No text outside JSON.
            - ${input.instructions}
            - box_2d order is [top, left, bottom, right] in range 0..1000.
            - box_2d must be a tight box around the exact tappable UI element that answers the question (icon/button row), not around large containers.
            - Never return a full-screen or near-full-screen box. If unsure, return target=null.
            - If target is present, keep target_confidence <= 0.4 when uncertain; use >= 0.5 only when the element location is visually clear.
            - Use target=null if there is no confident element.
            - Keep spoken_text short and simple.
            - Do not append session follow-up or closing phrases. The app will add localized session phrases when needed.
            - tool_request must always be null.
            - tool_request exists only for backward-compatible output schema; local tool routing is handled before this model.

            Context:
            - package: $packageHint
            - screen_title: $titleHint
            - conversation_history:
$conversationHistory
            - user_question: ${input.question}
        """.trimIndent()
    }

    private fun buildConversationHistoryBlock(input: ReasonerInput): String {
        if (input.conversationHistory.isEmpty()) return "  - none"
        val maxChars = 1_200
        val lines = mutableListOf<String>()
        for (turn in input.conversationHistory) {
            val role = when (turn.role) {
                ConversationRole.USER -> "user"
                ConversationRole.ASSISTANT -> "assistant"
            }
            val normalized = turn.text.replace("\n", " ").trim()
            if (normalized.isNotBlank()) {
                lines += "  - $role: $normalized"
            }
        }
        if (lines.isEmpty()) return "  - none"
        val joined = lines.joinToString("\n")
        if (joined.length <= maxChars) return joined
        return joined.takeLast(maxChars)
    }

    private fun fallbackNoScreenshot(input: ReasonerInput): ReasonerOutput {
        val spoken = when (input.interactionLanguage?.trim()?.lowercase()) {
            "it", "it-it" -> "Non riesco a vedere lo schermo in questo momento."
            else -> "I can't see the screen right now."
        }
        return ReasonerOutput(
            summary = "No screenshot available",
            spokenText = spoken,
            rationale = "Screen question was asked without screenshot image input",
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

    private fun fallbackInferenceError(): ReasonerOutput {
        return ReasonerOutput(
            summary = "Gemma inference failed",
            spokenText = "Sorry, I can't help right now.",
            rationale = "Gemma runtime failure",
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

    private fun fallbackNoMultimodalRuntimePath(): ReasonerOutput {
        return ReasonerOutput(
            summary = "Multimodal runtime path unavailable",
            spokenText = "I can’t analyze the screenshot on this device right now.",
            rationale =
                "Conversation API multimodal runtime path is unavailable or failed",
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

    private fun unavailable(userMessage: String, technical: String): ReasonerAvailability.Unavailable {
        diagnostics = diagnostics.copy(latestGemmaError = technical)
        Log.w(TAG, "Unavailable: $technical")
        return ReasonerAvailability.Unavailable(userMessage = userMessage, technicalReason = technical)
    }

    private fun detectLiteRtDependencyPresent(): Boolean {
        return try {
            Class.forName("com.google.ai.edge.litertlm.Engine")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun detectMultimodalImageInputPresent(): Boolean {
        return try {
            Class.forName("com.google.ai.edge.litertlm.InputData\$Image")
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "GemmaReasoner"
        const val STRUCTURED_OUTPUT_BLOCKER: String =
            "LiteRT-LM 0.10.2 Android API exposes multimodal input but does not expose JSON-Schema/grammar/regex constrained decoding hooks in public APIs; only prompt-level JSON is available."
    }
}
