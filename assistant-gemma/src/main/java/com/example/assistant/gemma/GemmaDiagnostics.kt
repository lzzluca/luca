package com.example.assistant.gemma

data class GemmaDiagnostics(
    val liteRtDependencyPresent: Boolean,
    val modelPath: String,
    val modelFileExists: Boolean,
    val engineInitialized: Boolean,
    val selectedBackend: String,
    val multimodalImageInputEnabled: Boolean,
    val structuredOutputEnabled: Boolean,
    val structuredOutputBlocker: String?,
    val latestGemmaError: String?,
    val latestRawModelOutput: String?
)

