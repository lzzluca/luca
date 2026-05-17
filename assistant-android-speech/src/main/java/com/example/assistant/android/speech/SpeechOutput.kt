package com.example.assistant.android.speech

data class SpeechLanguageUpdateResult(
    val requestedLanguageTag: String,
    val requestedTtsLocale: String,
    val ttsSetLanguageResult: Int,
    val success: Boolean,
    val activeTtsLocale: String? = null,
    val activeTtsVoice: String? = null,
    val failureReason: String? = null
)

interface SpeechOutput {
    fun speak(
        text: String,
        interrupt: Boolean = true,
        onDone: (() -> Unit)? = null,
        onError: ((Throwable?) -> Unit)? = null
    )

    fun stop()
    fun setLanguage(languageTag: String): SpeechLanguageUpdateResult
    fun shutdown()
}
