package com.example.assistant.android.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class AndroidSpeechOutput(context: Context) : SpeechOutput {

    private val appContext = context.applicationContext
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready: Boolean = false
    @Volatile
    private var initStatus: Int? = null
    private val callbacks = ConcurrentHashMap<String, Pair<(() -> Unit)?, ((Throwable?) -> Unit)?>>()
    private val pendingSpeaks = CopyOnWriteArrayList<PendingSpeak>()

    private data class PendingSpeak(
        val text: String,
        val interrupt: Boolean,
        val onDone: (() -> Unit)?,
        val onError: ((Throwable?) -> Unit)?
    )

    private fun normalizeLocaleForTag(languageTag: String): Locale {
        return when (languageTag.lowercase()) {
            "it" -> Locale.ITALIAN
            "it-it" -> Locale.forLanguageTag("it-IT")
            "en" -> Locale.US
            "en-us" -> Locale.US
            else -> Locale.forLanguageTag(languageTag)
        }
    }

    init {
        val created = TextToSpeech(appContext) { status ->
            initStatus = status
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                flushPendingSpeaks()
            } else {
                failPendingSpeaks(IllegalStateException("TextToSpeech init failed: $status"))
            }
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                callbacks.remove(utteranceId)?.first?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                callbacks.remove(utteranceId)?.second?.invoke(null)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId ?: return
                callbacks.remove(utteranceId)?.second?.invoke(IllegalStateException("TTS error: $errorCode"))
            }
        })
        tts = created
    }

    override fun speak(
        text: String,
        interrupt: Boolean,
        onDone: (() -> Unit)?,
        onError: ((Throwable?) -> Unit)?
    ) {
        val engine = tts
        if (engine == null) {
            onError?.invoke(IllegalStateException("TextToSpeech engine unavailable"))
            return
        }

        if (!ready) {
            pendingSpeaks += PendingSpeak(text, interrupt, onDone, onError)
            return
        }

        speakInternal(engine, text, interrupt, onDone, onError)
    }

    private fun speakInternal(
        engine: TextToSpeech,
        text: String,
        interrupt: Boolean,
        onDone: (() -> Unit)?,
        onError: ((Throwable?) -> Unit)?
    ) {

        if (interrupt) {
            engine.stop()
            callbacks.clear()
        }

        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone to onError
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onError?.invoke(IllegalStateException("Failed to speak"))
        }
    }

    private fun flushPendingSpeaks() {
        val engine = tts ?: return
        if (!ready) return
        val pending = pendingSpeaks.toList()
        pendingSpeaks.clear()
        pending.forEach { pendingSpeak ->
            speakInternal(
                engine = engine,
                text = pendingSpeak.text,
                interrupt = pendingSpeak.interrupt,
                onDone = pendingSpeak.onDone,
                onError = pendingSpeak.onError
            )
        }
    }

    private fun failPendingSpeaks(error: Throwable) {
        val pending = pendingSpeaks.toList()
        pendingSpeaks.clear()
        pending.forEach { it.onError?.invoke(error) }
    }

    override fun stop() {
        tts?.stop()
        callbacks.clear()
    }

    override fun setLanguage(languageTag: String): SpeechLanguageUpdateResult {
        val engine = tts
        if (engine == null) {
            return SpeechLanguageUpdateResult(
                requestedLanguageTag = languageTag,
                requestedTtsLocale = languageTag,
                ttsSetLanguageResult = TextToSpeech.ERROR,
                success = false,
                failureReason = "tts_engine_unavailable"
            )
        }

        val locale = normalizeLocaleForTag(languageTag)
        val result = engine.setLanguage(locale)
        val success = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val failureReason = when {
            result == TextToSpeech.LANG_MISSING_DATA -> "tts_language_missing_data"
            result == TextToSpeech.LANG_NOT_SUPPORTED -> "tts_language_not_supported"
            result == TextToSpeech.ERROR -> "tts_set_language_error"
            else -> null
        }
        return SpeechLanguageUpdateResult(
            requestedLanguageTag = languageTag,
            requestedTtsLocale = locale.toLanguageTag(),
            ttsSetLanguageResult = result,
            success = success,
            activeTtsLocale = engine.language?.toLanguageTag(),
            activeTtsVoice = engine.voice?.name,
            failureReason = failureReason
        )
    }

    override fun shutdown() {
        failPendingSpeaks(
            IllegalStateException(
                "TextToSpeech shutdown before ready${initStatus?.let { " (initStatus=$it)" } ?: ""}"
            )
        )
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        initStatus = null
    }
}
