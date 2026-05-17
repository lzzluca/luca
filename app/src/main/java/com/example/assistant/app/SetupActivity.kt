package com.example.assistant.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.assistant.android.accessibility.service.LucaAccessibilityService
import com.example.assistant.android.capture.DebugScreenshotCapturer
import com.example.assistant.android.overlay.AssistantBubbleOverlayController
import com.example.assistant.android.overlay.FocusHighlighter
import com.example.assistant.android.speech.AndroidSpeechOutput
import com.example.assistant.app.session.AssistantUiState
import com.example.assistant.app.session.DebugSessionBootstrap
import com.example.assistant.app.session.SpeechRecognizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class SetupActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "SetupActivity"
    }

    private lateinit var checker: PermissionStateChecker
    private lateinit var projectionStore: ProjectionPermissionStore
    private var speechOutput: AndroidSpeechOutput? = null
    private val screenCaptureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val debugScreenshotCapturer = DebugScreenshotCapturer()
    private var debugSession: DebugSessionBootstrap? = null
    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private val focusHighlighter = FocusHighlighter()
    private lateinit var debugInfoState: TextView
    private lateinit var debugCaptureState: TextView
    private lateinit var debugResultState: TextView
    private lateinit var debugErrorState: TextView
    private lateinit var debugReasonerState: TextView
    private lateinit var debugUiTreeState: TextView
    private lateinit var debugTypedRouterInput: EditText
    private lateinit var debugTypedRouterOutput: TextView

    private lateinit var accessibilityStatus: TextView
    private lateinit var microphoneStatus: TextView
    private lateinit var screenVisionStatus: TextView
    private lateinit var doneStatus: TextView

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val requestProjectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            projectionStore.setPermissionGranted(result.resultCode == Activity.RESULT_OK)
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checker = PermissionStateChecker(this)
        projectionStore = ProjectionPermissionStore(this)
        speechOutput = AndroidSpeechOutput(this)
        val isDebugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Debug screen section (debug builds only)
        debugInfoState = TextView(this)
        debugCaptureState = TextView(this)
        debugResultState = TextView(this)
        debugErrorState = TextView(this)
        debugReasonerState = TextView(this)
        debugUiTreeState = TextView(this)
        debugTypedRouterInput = EditText(this)
        debugTypedRouterOutput = TextView(this)
        debugInfoState.text = "Current state: Dismissed"
        debugCaptureState.text = "Capture availability: ${checker.isScreenVisionGranted()}"
        debugResultState.text = "Pre-screen runtime trace:\n(none yet)"
        debugErrorState.text = "Latest error: n/a"
        debugUiTreeState.text = "Latest UI tree: unavailable"
        debugTypedRouterInput.hint = "Type text and run intent router test"
        debugTypedRouterOutput.text = "Typed router test output:\n(none yet)"

        speechRecognizerManager = SpeechRecognizerManager(
            context = this,
            onResult = { text -> debugSession?.controller?.onSpeechRecognized(text) },
            onError = { msg, isSilence ->
                debugSession?.controller?.onSpeechRecognitionError(msg, isSilence)
                if (!isSilence) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )

        debugSession = DebugSessionBootstrap(
            activity = this,
            isDebugBuild = isDebugBuild,
            onStartListening = { speechRecognizerManager?.startListening() },
            onStopListening = { speechRecognizerManager?.stopListening() },
            // displays a rectangle
            onShowTarget = { bounds ->
                val b = bounds ?: return@DebugSessionBootstrap

                LucaAccessibilityService.getActiveInstance()?.let { service ->
                    // Usiamo la nuova funzione showBox
                    focusHighlighter.showBox(service, b)
                }
            },
            onClearTarget = { focusHighlighter.clear() },
            onDebugResult = { payload ->
                val prev = debugResultState.text?.toString().orEmpty()
                val base = if (prev.startsWith("Pre-screen runtime trace:")) prev else "Pre-screen runtime trace:"
                val lines = base
                    .lineSequence()
                    .toMutableList()
                    .toMutableList()
                lines += payload
                val trimmed = if (lines.size > 40) {
                    lines.take(1) + lines.takeLast(39)
                } else {
                    lines
                }
                debugResultState.text = trimmed.joinToString("\n")
            },
            onDebugError = { message -> debugErrorState.text = "Latest error: $message" }
        )

        lifecycleScope.launch {
            debugSession?.controller?.state?.collectLatest { state ->
                when (state) {
                    is AssistantUiState.Error -> {
                        Toast.makeText(this@SetupActivity, state.message, Toast.LENGTH_SHORT).show()
                        debugInfoState.text = "Current state: Error(${state.message})"
                    }
                    AssistantUiState.Dismissed -> debugInfoState.text = "Current state: Dismissed"
                    AssistantUiState.Greeting -> debugInfoState.text = "Current state: Greeting"
                    AssistantUiState.Listening -> debugInfoState.text = "Current state: Listening"
                    AssistantUiState.Processing -> debugInfoState.text = "Current state: Processing"
                    is AssistantUiState.Responding -> debugInfoState.text = "Current state: Responding"
                }
            }
        }

        lifecycleScope.launch {
            LucaAccessibilityService.latestUiTree.collectLatest { uiTree ->
                debugUiTreeState.text = if (uiTree == null) {
                    "Latest UI tree: unavailable"
                } else {
                    "Latest UI tree: available=${uiTree.available}, root=${uiTree.rootClassName ?: "n/a"}"
                }
            }
        }

        lifecycleScope.launch {
            LucaAccessibilityService.lastTriggerLongPressMs.collectLatest { ts ->
                if (ts != null) {
                    debugSession?.controller?.activate()
                    LucaAccessibilityService.getActiveInstance()?.let { service ->
                        AssistantBubbleOverlayController.show(service)
                    }
                }
            }
        }


        debugReasonerState.text = buildString {
            val s = debugSession
            append("Active reasoner: ${s?.activeReasoner ?: "Unavailable"}")
            append("\nLiteRT-LM initialized: ${s?.liteRtInitialized ?: false}")
            append("\nModel path: ${s?.modelPath ?: "n/a"}")
            append("\nModel cache dir: ${s?.modelCacheDirPath ?: "n/a"}")
            append("\nModel file exists: ${s?.modelFileExists ?: false}")
            append("\nSelected backend: ${s?.selectedBackend ?: "n/a"}")
            append("\nMultimodal screenshot input enabled: ${s?.multimodalImageInputEnabled ?: false}")
            append("\nStructured output enabled: ${s?.structuredOutputEnabled ?: false}")
            append("\nGemma init error: ${s?.latestGemmaError ?: "none"}")
            append("\nLatest raw model output: ${s?.latestRawModelOutput ?: "n/a"}")
        }

        if (isDebugBuild) {
            content.addView(TextView(this).apply {
                text = "\nDebug panel"
                textSize = 18f
            })
            content.addView(debugInfoState)
            content.addView(debugCaptureState)
            content.addView(debugReasonerState)
            content.addView(debugResultState)
            content.addView(debugErrorState)
            content.addView(debugUiTreeState)
            content.addView(TextView(this).apply {
                text = "\nTyped intent router test"
            })
            content.addView(debugTypedRouterInput)
            content.addView(Button(this).apply {
                text = "Run typed router test"
                setOnClickListener {
                    lifecycleScope.launch {
                        val input = debugTypedRouterInput.text?.toString().orEmpty()
                        val resultLines = debugSession?.runTypedRouterDiagnostics(input).orEmpty()
                        debugTypedRouterOutput.text = buildString {
                            append("Typed router test output:")
                            if (resultLines.isEmpty()) {
                                append("\n(none)")
                            } else {
                                resultLines.forEach { append("\n").append(it) }
                            }
                        }
                    }
                }
            })
            content.addView(debugTypedRouterOutput)
        }

        content.addView(TextView(this).apply {
            text = "Set up Luca"
            textSize = 20f
        })

        content.addView(TextView(this).apply {
            text = "\nStep 1 — Accessibility permission\nThis lets Luca understand what's on your screen."
        })
        accessibilityStatus = TextView(this)
        content.addView(accessibilityStatus)
        content.addView(Button(this).apply {
            text = "Enable in settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        content.addView(TextView(this).apply {
            text = "\nStep 2 — Microphone permission\nThis lets Luca hear your question."
        })
        microphoneStatus = TextView(this)
        content.addView(microphoneStatus)
        content.addView(Button(this).apply {
            text = "Allow microphone"
            setOnClickListener {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        })

        content.addView(TextView(this).apply {
            text = "\nStep 3 — Screen vision permission\nThis lets Luca see your screen to help you."
        })
        screenVisionStatus = TextView(this)
        content.addView(screenVisionStatus)
        content.addView(Button(this).apply {
            text = "Allow screen vision"
            setOnClickListener {
                val manager = getSystemService(MediaProjectionManager::class.java)
                requestProjectionPermission.launch(manager.createScreenCaptureIntent())
            }
        })

        doneStatus = TextView(this)
        content.addView(doneStatus)

        if (isDebugBuild) {
            content.addView(Button(this).apply {
                text = "Debug activation"
                setOnClickListener {
                    LucaAccessibilityService.getActiveInstance()?.let { service ->
                        AssistantBubbleOverlayController.toggle(service)
                    }
                    debugSession?.controller?.activate()
                }
            })

            content.addView(Button(this).apply {
                text = "Debug speak greeting"
                setOnClickListener {
                    speechOutput?.speak("Hi, I'm Luca. How can I help you?")
                }
            })

            content.addView(Button(this).apply {
                text = "Debug show focus dot"
                setOnClickListener {
                    LucaAccessibilityService.getActiveInstance()?.let { service ->
                        val w = resources.displayMetrics.widthPixels
                        val h = resources.displayMetrics.heightPixels
                        focusHighlighter.showDot(service, w / 2, h / 3)
                    }
                }
            })

            content.addView(Button(this).apply {
                text = "Debug clear focus dot"
                setOnClickListener {
                    focusHighlighter.clear()
                }
            })

            content.addView(Button(this).apply {
                text = "Debug capture screenshot"
                setOnClickListener {
                    runDebugScreenshotCapture()
                }
            })
        }

        root.addView(content)
        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        screenCaptureScope.cancel()
        focusHighlighter.clear()
        speechRecognizerManager?.destroy()
        speechRecognizerManager = null
        debugSession?.close()
        debugSession = null
        speechOutput?.shutdown()
        speechOutput = null
        super.onDestroy()
    }

    private fun runDebugScreenshotCapture() {
        val service = LucaAccessibilityService.getActiveInstance() ?: return
        screenCaptureScope.launch {
            AssistantBubbleOverlayController.hide()
            // one-frame pause before capture
            delay(34)
            val bitmap = debugScreenshotCapturer.captureActivityWindow(this@SetupActivity)
            val file = debugScreenshotCapturer.saveJpegToCache(this@SetupActivity, bitmap)
            AssistantBubbleOverlayController.show(service)
            debugCaptureState.text = "Capture availability: true\nLatest screenshot: ${bitmap.width}x${bitmap.height} image/jpeg (${file.name})"
            speechOutput?.speak("Saved screenshot to ${file.name}")
        }
    }

    private fun refreshStatus() {
        val accessibilityEnabled = checker.isAccessibilityEnabled(
            "com.example.assistant.android.accessibility.service.LucaAccessibilityService"
        )
        val micGranted = checker.isMicGranted()
        val projectionGranted = checker.isScreenVisionGranted()

        accessibilityStatus.text = statusLine(accessibilityEnabled)
        microphoneStatus.text = statusLine(micGranted)
        screenVisionStatus.text = statusLine(projectionGranted)
        doneStatus.text = if (accessibilityEnabled && micGranted && projectionGranted) {
            "\nAll done — start using Luca"
        } else {
            "\nComplete all steps to start using Luca"
        }
    }

    private fun statusLine(ok: Boolean): String = if (ok) {
        "Status: allowed"
    } else {
        "Status: not allowed"
    }
}
