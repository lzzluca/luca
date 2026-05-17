package com.example.assistant.gemma

data class GemmaConfig(
    val modelPath: String,
    val cacheDirPath: String? = null,
    val backendName: String = "cpu",
    val visionBackendName: String = "cpu",
    val maxNumImages: Int = 1,
    val maxOutputTokens: Int = 1024,
    val requireStructuredOutputInRelease: Boolean = true,
    val structuredOutputEnabledForDebug: Boolean = true,
)
