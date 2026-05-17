package com.example.assistant.gemma

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface TextOnlyRouteModel {
    suspend fun generateRouteJson(prompt: String): String
}

interface TextOnlyRouteModelDiagnostics {
    val latestInvocationDiagnostics: String?
}

interface TextOnlyRouteModelLifecycleDiagnostics {
    val initializationStatus: String
    val initializationFailureDiagnostics: String?
}

interface TextOnlyRouteModelTimingDiagnostics {
    val latestPromptBuildMs: Long?
    val latestModelInitMs: Long?
    val latestConversationCreateMs: Long?
    val latestInferenceMs: Long?
    val latestParseMs: Long?
    val latestValidationMs: Long?
    val latestGenerationConfigMaxOutputTokens: Int?
    val latestUsedCachedEngine: Boolean?
    val latestUsedCachedConversation: Boolean?
}

class TextOnlyRouterInitializationException(
    message: String,
    cause: Throwable? = null,
    val diagnostics: String? = null
) : RuntimeException(message, cause)

class GemmaTextOnlyRouterModel(
    private val config: GemmaConfig
) : TextOnlyRouteModel, TextOnlyRouteModelDiagnostics, TextOnlyRouteModelLifecycleDiagnostics, TextOnlyRouteModelTimingDiagnostics {
    private interface LabeledFailure {
        val label: String
    }

    private class InitializationDiagnosticException(
        override val label: String,
        message: String,
        cause: Throwable? = null
    ) : RuntimeException(message, cause), LabeledFailure

    private val initMutex = Mutex()
    private var engine: Any? = null
    private var conversation: Any? = null
    private var latestInitializationDiagnostics: String? = null

    @Volatile
    override var latestInvocationDiagnostics: String? = null
        private set

    @Volatile
    private var initAttempted: Boolean = false

    @Volatile
    private var initSucceeded: Boolean = false

    @Volatile
    override var initializationFailureDiagnostics: String? = null
        private set

    @Volatile
    override var latestPromptBuildMs: Long? = null
        private set
    @Volatile
    override var latestModelInitMs: Long? = null
        private set
    @Volatile
    override var latestConversationCreateMs: Long? = null
        private set
    @Volatile
    override var latestInferenceMs: Long? = null
        private set
    @Volatile
    override var latestParseMs: Long? = null
        private set
    @Volatile
    override var latestValidationMs: Long? = null
        private set
    @Volatile
    override var latestGenerationConfigMaxOutputTokens: Int? = null
        private set
    @Volatile
    override var latestUsedCachedEngine: Boolean? = null
        private set
    @Volatile
    override var latestUsedCachedConversation: Boolean? = null
        private set

    override val initializationStatus: String
        get() = when {
            initSucceeded -> "initialized"
            initAttempted && initializationFailureDiagnostics != null -> "failed"
            initAttempted -> "initializing"
            else -> "not_initialized"
        }

    override suspend fun generateRouteJson(prompt: String): String {
        val ensureResult = ensureConversation()
        latestUsedCachedEngine = ensureResult.usedCachedEngine
        latestUsedCachedConversation = ensureResult.usedCachedConversation
        latestModelInitMs = ensureResult.modelInitMs
        latestConversationCreateMs = ensureResult.conversationCreateMs
        val localConversation = ensureResult.conversation
        val inferenceStartedAt = System.nanoTime()
        return withContext(Dispatchers.IO) {
            runTextOnlyTurn(localConversation, prompt).also {
                latestInferenceMs = ((System.nanoTime() - inferenceStartedAt) / 1_000_000L)
            }
        }
    }

    private data class EnsureConversationResult(
        val conversation: Any,
        val usedCachedEngine: Boolean,
        val usedCachedConversation: Boolean,
        val modelInitMs: Long,
        val conversationCreateMs: Long
    )

    private suspend fun ensureConversation(): EnsureConversationResult {
        return initMutex.withLock {
            conversation?.let {
                return EnsureConversationResult(
                    conversation = it,
                    usedCachedEngine = engine != null,
                    usedCachedConversation = true,
                    modelInitMs = 0L,
                    conversationCreateMs = 0L
                )
            }
            if (initAttempted && !initSucceeded) {
                throw TextOnlyRouterInitializationException(
                    message = "Text-only router initialization failed previously.",
                    diagnostics = initializationFailureDiagnostics
                )
            }

            initAttempted = true
            val initStartedAt = System.nanoTime()
            var modelInitMs = 0L
            var conversationCreateMs = 0L

            try {
                withContext(Dispatchers.IO) {
                    val initDiagnostics = StringBuilder().apply {
                        appendLine("reflection_stage=ensureConversation")
                        appendLine("engine_object_null=${engine == null}")
                        appendLine("conversation_object_null=${conversation == null}")
                        appendLine("diagnostics_builder_field_null=${this == null}")
                        appendLine("selected_init_method=null")
                        appendLine("init_result_null=not_invoked")
                        appendLine("state_method_result_null=not_checked")
                    }

                    val modelPath = requireNotNullForInit(config.modelPath, "null_model_path")
                    val modelPathIsBlank = modelPath.isBlank()
                    initDiagnostics.appendLine("model_path_null=$modelPathIsBlank")
                    initDiagnostics.appendLine("model_path=$modelPath")
                    val modelFile = File(modelPath)
                    initDiagnostics.appendLine("model_file_exists=${modelFile.exists()}")
                    initDiagnostics.appendLine("model_file_is_file=${modelFile.isFile}")

                    if (modelPathIsBlank) {
                        initDiagnostics.appendLine("engine_config_null=true")
                        latestInitializationDiagnostics = initDiagnostics.toString().trim()
                        throw InitializationDiagnosticException(
                            label = "null_engine_config",
                            message = "Model path is blank, engine config cannot be created"
                        )
                    }

                    if (!modelFile.exists() || !modelFile.isFile) {
                        initDiagnostics.appendLine("missing_model_file=true")
                        latestInitializationDiagnostics = initDiagnostics.toString().trim()
                        throw InitializationDiagnosticException(
                            label = "missing_model_file",
                            message = "Model file is missing or not a file: $modelPath"
                        )
                    }

                    val engineClass = requireNotNullForInit(
                        Class.forName("com.google.ai.edge.litertlm.Engine"),
                        "null_engine_class"
                    )
                    val engineConfigClass = requireNotNullForInit(
                        Class.forName("com.google.ai.edge.litertlm.EngineConfig"),
                        "null_engine_config"
                    )
                    val backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
                    val cpuClass = Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")

                    val cpuBackend = requireNotNullForInit(try {
                        cpuClass.getDeclaredConstructor().newInstance()
                    } catch (_: Throwable) {
                        cpuClass.getDeclaredConstructor(Int::class.javaObjectType).newInstance(null)
                    }, "null_engine_config")

                    val engineConfig = requireNotNullForInit(engineConfigClass.getDeclaredConstructor(
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
                        config.cacheDirPath
                    ), "null_engine_config")
                    initDiagnostics.appendLine("engine_config_null=false")

                    val createdEngine = requireNotNullForInit(engineClass.getDeclaredConstructor(engineConfigClass)
                        .newInstance(engineConfig)
                    , "null_engine")
                    initDiagnostics.appendLine("engine_object_null=false")

                    initDiagnostics.apply {
                        appendLine("reflection_stage=createEngine")
                        appendLine("engine_class=${createdEngine.javaClass.name}")
                        appendLine("engine_ctor=${engineClass.getDeclaredConstructor(engineConfigClass).toGenericString()}")
                        appendLine("engine_config_class=${engineConfig.javaClass.name}")
                        appendLine("model_path=${config.modelPath}")
                        appendLine("cache_dir=${config.cacheDirPath}")
                        appendLine("backend_class=${cpuBackend.javaClass.name}")
                        appendLine("candidate_engine_lifecycle_methods=${discoverEngineLifecycleMethods(createdEngine)}")
                    }

                    val engineInitStartedAt = System.nanoTime()
                    initializeEngineIfRequired(createdEngine, initDiagnostics)
                    modelInitMs = ((System.nanoTime() - engineInitStartedAt) / 1_000_000L)

                    val engineState = readEngineStateIfAvailable(createdEngine)
                    initDiagnostics.appendLine("state_method_result_null=${engineState == null}")
                    initDiagnostics.appendLine("engine_state_before_createConversation=${engineState ?: "null"}")

                    val conversationConfig = createConversationConfig()
                    initDiagnostics.appendLine("conversation_config_null=false")

                    val createConversationMethods = createdEngine.javaClass.methods.filter {
                        it.name == "createConversation" && it.parameterCount == 1
                    }
                    initDiagnostics.appendLine(
                        "create_conversation_method_list=${createConversationMethods.joinToString(" | ") { it.signatureString() }}"
                    )

                    val createConversationMethod = createConversationMethods.firstOrNull()
                    if (createConversationMethod == null) {
                        initDiagnostics.appendLine("selected_method_null=true")
                        latestInitializationDiagnostics = initDiagnostics.toString().trim()
                        throw InitializationDiagnosticException(
                            label = "null_create_conversation_method",
                            message = "Engine.createConversation(ConversationConfig) not found"
                        )
                    }
                    initDiagnostics.appendLine("selected_method_null=false")

                    val conversationCreateStartedAt = System.nanoTime()
                    val createdConversation = requireNotNullForInit(invokeMethodDetailed(
                        method = createConversationMethod,
                        receiver = createdEngine,
                        args = arrayOf(conversationConfig),
                        stage = "createConversation"
                    ), "null_create_conversation_result")
                    conversationCreateMs = ((System.nanoTime() - conversationCreateStartedAt) / 1_000_000L)
                    initDiagnostics.appendLine("conversation_object_null=false")
                    latestInitializationDiagnostics = initDiagnostics.toString().trim()
                    engine = createdEngine
                    conversation = createdConversation
                    initSucceeded = true
                    initializationFailureDiagnostics = null
                    EnsureConversationResult(
                        conversation = createdConversation,
                        usedCachedEngine = false,
                        usedCachedConversation = false,
                        modelInitMs = modelInitMs,
                        conversationCreateMs = conversationCreateMs
                    )
                }
            } catch (t: Throwable) {
                initSucceeded = false
                val detail = buildInitializationFailureDetail(t)
                initializationFailureDiagnostics = detail
                throw TextOnlyRouterInitializationException(
                    message = "Text-only router initialization failed",
                    cause = t,
                    diagnostics = detail
                )
            } finally {
                if (!initSucceeded) {
                    latestModelInitMs = ((System.nanoTime() - initStartedAt) / 1_000_000L)
                }
            }
        }
    }

    private fun buildInitializationFailureDetail(t: Throwable): String {
        val diagnosticLabel = when {
            t is LabeledFailure -> t.label
            t.cause is LabeledFailure -> (t.cause as LabeledFailure).label
            else -> null
        }
        return buildString {
            appendLine("init_stage=engine_or_conversation_setup")
            appendLine("diagnostic_label=${diagnosticLabel ?: "unlabeled"}")
            appendLine("exception_class=${t::class.java.name}")
            appendLine("exception_message=${t.message ?: "no_message"}")
            appendLine("cause_class=${t.cause?.javaClass?.name ?: "null"}")
            appendLine("cause_message=${t.cause?.message ?: "no_message"}")
            appendLine("model_path=${config.modelPath}")
            appendLine("cache_dir=${config.cacheDirPath}")
            latestInitializationDiagnostics?.let {
                appendLine("initialization_diagnostics_begin")
                appendLine(it)
                appendLine("initialization_diagnostics_end")
            }
            appendLine("full_stack_trace=${Log.getStackTraceString(t)}")
        }
    }

    private fun discoverEngineLifecycleMethods(createdEngine: Any): String {
        val methods = createdEngine.javaClass.methods
            .filter {
                val n = it.name.lowercase()
                n.contains("init") || n.contains("load") || n.contains("start") || n.contains("create") || n.contains("ready") || n.contains("state")
            }
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterCount })
        if (methods.isEmpty()) return "none"
        return methods.joinToString(" | ") { it.signatureString() }
    }

    private fun initializeEngineIfRequired(createdEngine: Any, diagnostics: StringBuilder) {
        val candidates = createdEngine.javaClass.methods
            .filter {
                val n = it.name.lowercase()
                (n == "initialize" || n.startsWith("initialize") || n.startsWith("init") || n.startsWith("load") || n.startsWith("start")) &&
                    it.parameterCount == 0
            }
            .sortedWith(compareBy<Method> { initMethodPriority(it.name.lowercase()) }.thenBy { it.name })

        if (candidates.isEmpty()) {
            diagnostics.appendLine("reflection_stage=initializeEngine")
            diagnostics.appendLine("selected_init_method=none")
            diagnostics.appendLine("init_skipped_reason=no_zero_arg_init_method_found")
            return
        }

        val method = candidates.first()
        diagnostics.appendLine("reflection_stage=initializeEngine")
        diagnostics.appendLine("selected_init_method=${method.signatureString()}")
        diagnostics.appendLine("init_args=none")
        diagnostics.appendLine("init_return_type=${method.returnType.name}")

        val initReturn = invokeMethodDetailed(
            method = method,
            receiver = createdEngine,
            args = emptyArray(),
            stage = "initializeEngine"
        )
        diagnostics.appendLine("init_result_null=${initReturn == null}")
        val awaitStatus = awaitInitializationCompletionIfNeeded(initReturn)
        diagnostics.appendLine("init_completion_status=$awaitStatus")
    }

    private fun initMethodPriority(name: String): Int {
        return when {
            name == "initialize" -> 0
            name.startsWith("initialize") -> 1
            name == "init" -> 2
            name.startsWith("init") -> 3
            name.startsWith("load") -> 4
            name.startsWith("start") -> 5
            else -> 6
        }
    }

    private fun awaitInitializationCompletionIfNeeded(initReturn: Any?): String {
        if (initReturn == null) return "completed_sync_void_or_null"

        if (initReturn is Future<*>) {
            initReturn.get(120, TimeUnit.SECONDS)
            return "awaited_java_future_completed"
        }

        val javaClass = initReturn.javaClass
        val awaitMethod = javaClass.methods.firstOrNull {
            it.name == "await" && it.parameterCount == 0
        }
        if (awaitMethod != null) {
            awaitMethod.invoke(initReturn)
            return "awaited_return_await()_completed"
        }

        val joinMethod = javaClass.methods.firstOrNull {
            it.name == "join" && it.parameterCount == 0
        }
        if (joinMethod != null) {
            joinMethod.invoke(initReturn)
            return "awaited_return_join()_completed"
        }

        val getMethod = javaClass.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 0
        }
        if (getMethod != null) {
            getMethod.invoke(initReturn)
            return "awaited_return_get()_completed"
        }

        return "no_async_wait_mechanism_detected_return_class=${javaClass.name}"
    }

    private fun readEngineStateIfAvailable(createdEngine: Any): String? {
        val stateMethods = createdEngine.javaClass.methods.filter {
            it.parameterCount == 0 && (
                it.name == "isInitialized" ||
                    it.name == "initialized" ||
                    it.name == "getState" ||
                    it.name == "state" ||
                    it.name == "isReady" ||
                    it.name == "ready"
                )
        }

        if (stateMethods.isEmpty()) {
            throw InitializationDiagnosticException(
                label = "null_method_inventory",
                message = "No supported engine state probe methods were found"
            )
        }

        val entries = stateMethods.mapNotNull { method ->
            try {
                method.isAccessible = true
                val value = method.invoke(createdEngine)
                "${method.name}=${value ?: "null"}"
            } catch (t: Throwable) {
                "${method.name}=error(${t::class.java.simpleName}:${t.message ?: "no_message"})"
            }
        }

        if (entries.isEmpty()) {
            throw InitializationDiagnosticException(
                label = "null_state_probe_result",
                message = "Engine state probe produced no entries"
            )
        }
        return entries.joinToString(",")
    }

    private fun createConversationConfig(): Any {
        val contentsClass = requireNotNullForInit(
            Class.forName("com.google.ai.edge.litertlm.Contents"),
            "null_conversation_config"
        )
        val conversationConfigClass = requireNotNullForInit(
            Class.forName("com.google.ai.edge.litertlm.ConversationConfig"),
            "null_conversation_config"
        )
        val emptyContents = requireNotNullForInit(
            contentsClass.getDeclaredConstructor(List::class.java).newInstance(emptyList<Any>()),
            "null_conversation_config"
        )

        val ctor = conversationConfigClass.declaredConstructors
            .firstOrNull { c -> c.parameterTypes.any { it == contentsClass } }
            ?: conversationConfigClass.declaredConstructors.firstOrNull()
            ?: throw InitializationDiagnosticException(
                label = "null_conversation_config",
                message = "ConversationConfig has no constructors"
            )

        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { type ->
            when {
                type == contentsClass -> emptyContents
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 0
                type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> 0L
                List::class.java.isAssignableFrom(type) -> emptyList<Any>()
                Map::class.java.isAssignableFrom(type) -> emptyMap<String, Any>()
                else -> null
            }
        }.toTypedArray()
        return requireNotNullForInit(ctor.newInstance(*args), "null_conversation_config")
    }

    private fun runTextOnlyTurn(localConversation: Any, prompt: String): String {
        val buildContentsStartedAt = System.nanoTime()
        val textClass = Class.forName("com.google.ai.edge.litertlm.Content\$Text")
        val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
        val text = textClass.getDeclaredConstructor(String::class.java).newInstance(prompt)
        val contents = contentsClass.getDeclaredConstructor(List::class.java).newInstance(listOf(text))
        val buildContentsMs = elapsedMsSince(buildContentsStartedAt)

        val sendMessageCandidates = localConversation.javaClass.methods
            .filter { it.name == "sendMessage" }
            .sortedBy { it.parameterCount }
        if (sendMessageCandidates.isEmpty()) {
            throw InitializationDiagnosticException(
                label = "null_send_message_method",
                message = "Conversation.sendMessage(...) not found"
            )
        }

        val selectMethodStartedAt = System.nanoTime()
        val sendMessageMethod = selectSendMessageMethod(sendMessageCandidates, contentsClass)
        val selectSendMessageMethodMs = elapsedMsSince(selectMethodStartedAt)

        val buildArgsStartedAt = System.nanoTime()
        val args = buildSendMessageArgs(sendMessageMethod, prompt, contents)
        val buildSendMessageArgsMs = elapsedMsSince(buildArgsStartedAt)

        val generationConfigMaxTokensApplied = if (config.maxOutputTokens > 0) config.maxOutputTokens else null
        latestGenerationConfigMaxOutputTokens = generationConfigMaxTokensApplied
        val generationConfig: Map<String, Any> = generationConfigMaxTokensApplied?.let {
            mapOf("max_tokens" to it)
        } ?: emptyMap()
        val constrainedJsonEnabled =
            config.requireStructuredOutputInRelease || config.structuredOutputEnabledForDebug

        latestInvocationDiagnostics = buildString {
            appendLine("stage=before_send_message")
            appendLine("engineClass=${engine?.javaClass?.name ?: "null"}")
            appendLine("conversationClass=${localConversation.javaClass.name}")
            appendLine("conversationNull=${localConversation == null}")
            appendLine("promptNonEmpty=${prompt.isNotBlank()}")
            appendLine("promptLength=${prompt.length}")
            appendLine("prompt_char_count=${prompt.length}")
            appendLine("threadName=${Thread.currentThread().name}")
            appendLine("isMainThread=${Thread.currentThread().name == "main"}")
            appendLine("sendMessageCandidates=${sendMessageCandidates.joinToString(" | ") { it.signatureString() }}")
            appendLine("selectedSendMessage=${sendMessageMethod.signatureString()}")
            appendLine("selected_send_message_signature=${sendMessageMethod.signatureString()}")
            appendLine("selectedParamTypes=${sendMessageMethod.parameterTypes.joinToString(",") { it.name }}")
            appendLine("selectedReturnType=${sendMessageMethod.returnType.name}")
            appendLine("selectedArgs=${args.joinToString(",") { it?.javaClass?.name ?: "null" }}")
            appendLine("build_contents_ms=$buildContentsMs")
            appendLine("select_send_message_method_ms=$selectSendMessageMethodMs")
            appendLine("build_send_message_args_ms=$buildSendMessageArgsMs")
            appendLine("generation_config_max_tokens=${generationConfigMaxTokensApplied ?: "none"}")
            appendLine("generation_config=${if (generationConfig.isEmpty()) "none" else generationConfig}")
            appendLine("temperature=0")
            appendLine("constrained_json_enabled=$constrainedJsonEnabled")
            appendLine("structured_output_release_required=${config.requireStructuredOutputInRelease}")
            appendLine("structured_output_debug_enabled=${config.structuredOutputEnabledForDebug}")
            appendLine("timeout_count=0")
            appendLine("retry_count=0")
        }

        val sendMessageInvokeStartedAt = System.nanoTime()
        val response = requireNotNullForInit(
            invokeMethodDetailed(
                method = sendMessageMethod,
                receiver = localConversation,
                args = args,
                stage = "sendMessage"
            ),
            "null_send_message_method"
        )
        val sendMessageInvokeMs = elapsedMsSince(sendMessageInvokeStartedAt)

        val extractResponseTextStartedAt = System.nanoTime()
        val responseText = extractMessageText(response)
        val extractResponseTextMs = elapsedMsSince(extractResponseTextStartedAt)

        latestInvocationDiagnostics = mergeDiagnostics(
            latestInvocationDiagnostics,
            buildString {
                appendLine("stage=after_send_message")
                appendLine("send_message_invoke_ms=$sendMessageInvokeMs")
                appendLine("extract_response_text_ms=$extractResponseTextMs")
                appendLine("response_text_length=${responseText.length}")
                appendLine("approximate_output_char_count=${responseText.length}")
            }
        )

        return responseText
    }

    private fun selectSendMessageMethod(candidates: List<Method>, contentsClass: Class<*>): Method {
        val twoParamContentsMap = candidates.firstOrNull { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[0] == contentsClass &&
                Map::class.java.isAssignableFrom(method.parameterTypes[1])
        }
        if (twoParamContentsMap != null) return twoParamContentsMap

        val oneParamContents = candidates.firstOrNull { method ->
            method.parameterCount == 1 && method.parameterTypes[0] == contentsClass
        }
        if (oneParamContents != null) return oneParamContents

        val oneParamString = candidates.firstOrNull { method ->
            method.parameterCount == 1 && method.parameterTypes[0] == String::class.java
        }
        if (oneParamString != null) return oneParamString

        return candidates.first()
    }

    private fun buildSendMessageArgs(method: Method, prompt: String, contents: Any): Array<Any?> {
        val paramTypes = method.parameterTypes
        return when {
            paramTypes.size == 2 && Map::class.java.isAssignableFrom(paramTypes[1]) -> {
                arrayOf(contents, emptyMap<String, Any>())
            }

            paramTypes.size == 1 && paramTypes[0] == String::class.java -> {
                arrayOf(prompt)
            }

            paramTypes.size == 1 -> {
                arrayOf(contents)
            }

            else -> {
                Array(paramTypes.size) { index ->
                    when {
                        paramTypes[index] == String::class.java -> prompt
                        Map::class.java.isAssignableFrom(paramTypes[index]) -> emptyMap<String, Any>()
                        else -> null
                    }
                }
            }
        }
    }

    private fun invokeMethodDetailed(method: Method, receiver: Any, args: Array<Any?>, stage: String): Any? {
        return try {
            method.invoke(receiver, *args)
        } catch (ite: InvocationTargetException) {
            val detail = buildInvocationTargetExceptionDetail(ite, method, stage)
            latestInvocationDiagnostics = mergeDiagnostics(latestInvocationDiagnostics, detail)
            latestInitializationDiagnostics = mergeDiagnostics(latestInitializationDiagnostics, detail)
            Log.e("GemmaTextOnlyRouter", detail)
            throw RuntimeException(detail, ite.targetException ?: ite)
        } catch (t: Throwable) {
            val detail = buildThrowableDetail(t, "reflective_invoke_failure", method, stage)
            latestInvocationDiagnostics = mergeDiagnostics(latestInvocationDiagnostics, detail)
            latestInitializationDiagnostics = mergeDiagnostics(latestInitializationDiagnostics, detail)
            Log.e("GemmaTextOnlyRouter", detail)
            throw RuntimeException(detail, t)
        }
    }

    private fun buildInvocationTargetExceptionDetail(
        ite: InvocationTargetException,
        method: Method,
        stage: String
    ): String {
        val target = ite.targetException
        val targetCause = target?.cause
        return buildString {
            appendLine("reflection_stage=$stage")
            appendLine("selected_method=${method.signatureString()}")
            appendLine("exception_class=${ite::class.java.name}")
            appendLine("exception_message=${ite.message ?: "no_message"}")
            appendLine("target_exception_class=${target?.javaClass?.name ?: "null"}")
            appendLine("target_exception_message=${target?.message ?: "no_message"}")
            appendLine("target_exception_cause_class=${targetCause?.javaClass?.name ?: "null"}")
            appendLine("target_exception_cause_message=${targetCause?.message ?: "no_message"}")
            appendLine("full_stack_trace=${Log.getStackTraceString(ite)}")
        }
    }

    private fun buildThrowableDetail(t: Throwable, label: String, method: Method, stage: String): String {
        return buildString {
            appendLine("reflection_stage=$stage")
            appendLine("selected_method=${method.signatureString()}")
            appendLine("label=$label")
            appendLine("exception_class=${t::class.java.name}")
            appendLine("exception_message=${t.message ?: "no_message"}")
            appendLine("cause_class=${t.cause?.javaClass?.name ?: "null"}")
            appendLine("cause_message=${t.cause?.message ?: "no_message"}")
            appendLine("full_stack_trace=${Log.getStackTraceString(t)}")
        }
    }

    private fun mergeDiagnostics(existing: String?, next: String): String {
        return if (existing.isNullOrBlank()) next else "$existing\n---\n$next"
    }

    private fun Method.signatureString(): String {
        val params = parameterTypes.joinToString(", ") { it.simpleName }
        return "${declaringClass.simpleName}.$name($params): ${returnType.simpleName}"
    }

    private fun elapsedMsSince(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000L

    private fun extractMessageText(message: Any): String {
        val messageContents = requireNotNullForInit(
            message.javaClass.getMethod("getContents").invoke(message),
            "null_send_message_method"
        )
        val partsRaw = requireNotNullForInit(
            messageContents.javaClass.getMethod("getContents").invoke(messageContents),
            "null_send_message_method"
        )
        val parts = partsRaw as? List<*>
            ?: throw InitializationDiagnosticException(
                label = "null_send_message_method",
                message = "Message contents list had unexpected type: ${partsRaw.javaClass.name}"
            )
        val textParts = parts.mapNotNull { part ->
            part ?: return@mapNotNull null
            if (part.javaClass.name.endsWith("\$Text")) {
                part.javaClass.getMethod("getText").invoke(part) as? String
            } else {
                null
            }
        }
        return textParts.joinToString("\n").ifBlank {
            Log.w("GemmaTextOnlyRouter", "Model returned no text")
            "{}"
        }
    }

    private fun <T : Any> requireNotNullForInit(value: T?, label: String): T {
        return value ?: throw InitializationDiagnosticException(
            label = label,
            message = "Required initialization value missing",
            cause = NullPointerException(label)
        )
    }
}
