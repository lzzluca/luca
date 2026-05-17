package com.example.assistant.app.session

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognizerManager(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String, Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } else {
            null
        }
    private var isListening = false
    private var stopRequested = false

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                stopRequested = false
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                isListening = false
                stopRequested = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    onResult(text)
                } else {
                    this@SpeechRecognizerManager.onError("No speech recognized", true)
                }
            }

            override fun onError(error: Int) {
                val requestedStop = stopRequested
                isListening = false
                stopRequested = false

                if (
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    (error == SpeechRecognizer.ERROR_CLIENT && requestedStop) ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    onError("Silence", true)
                } else {
                    onError("Speech error: $error", false)
                }
            }
        })
    }

    fun startListening() {
        runOnMainThread {
            val r = recognizer ?: run {
                onError("SpeechRecognizer unavailable", false)
                return@runOnMainThread
            }
            if (isListening) {
                return@runOnMainThread
            }
            stopRequested = false
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
            r.startListening(intent)
        }
    }

    fun stopListening() {
        runOnMainThread {
            stopRequested = true
            if (isListening) {
                recognizer?.stopListening()
            } else {
                recognizer?.cancel()
            }
        }
    }

    fun destroy() {
        runOnMainThread {
            isListening = false
            stopRequested = false
            recognizer?.destroy()
        }
    }

    private inline fun runOnMainThread(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}
