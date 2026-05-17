# Luca

**An on-device AI assistant that helps elderly users navigate their smartphone — powered by Gemma 4.**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Gemma 4](https://img.shields.io/badge/Powered_by-Gemma_4_E2B-purple.svg)](https://ai.google.dev/gemma)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)

> *"The assistant my parents can call when I can't pick up."*

Submission for the [**Gemma 4 Good Hackathon**](https://www.kaggle.com/competitions/gemma-4-good-hackathon) — track: **Digital Equity & Inclusivity**.

---

## The problem

Most AI assistants are built for people who already know how to use a smartphone. My parents are in their 70s, live in Italy, and call me every week for help with apps, photos, and suspicious messages.

Digital exclusion isn't about a lack of interest. It's about not having someone who picks up at 9 PM.

Luca is an attempt to be that someone — always available, always patient, and entirely on the user's phone.

---

## What Luca does

- **Launch any app via Play Store handoff.** Instead of asking elderly users to hunt for icons, Luca opens the app's Play Store page where the "Open" button is large and standardized.
- **Highlight the right button.** Luca reads the current screen and draws a red overlay around the button the user needs to tap.
- **Detect scam messages.** Luca reads message content locally and warns the user about suspicious patterns before they click anything.
- **Natural conversation.** No technical jargon. Users ask in plain language: *"How do I open the gallery?"* or *"Is this message safe?"*

All powered by **Gemma 4 E2B**, running on-device through **LiteRT-LM**. No cloud. No accounts. No subscriptions.

---

## How it works

Luca uses a **hybrid screen-reading approach** to balance speed, battery, and accuracy:

1. **Accessibility tree + OCR first** — fast and battery-friendly, covers most well-structured apps.
2. **Vision fallback** — Gemma 4's multimodal capability kicks in only when the interface is messy, unlabeled, or visually complex.
3. **Reasoning everywhere** — Gemma 4 decides *what to do*, *what to say*, and *what to warn about* at every step.

This hybrid design is why Luca fits within the RAM constraints of mid-range Android devices — the kind of phones elderly users actually own.

---

## Project structure

The codebase is organized into modular components with clear separation of concerns:

| Module | Purpose |
|---|---|
| [`app/`](app/) | Main Android app: UI entrypoint, onboarding, permission setup, session orchestration. Core logic in [`MainActivity.kt`](app/src/main/java/com/example/assistant/app/MainActivity.kt) and [`SetupActivity.kt`](app/src/main/java/com/example/assistant/app/SetupActivity.kt). |
| [`assistant-core/`](assistant-core/) | Platform-agnostic domain layer: models, planner, intent classifiers, parsers, core interfaces. Example: [`DefaultAssistantEngine.kt`](assistant-core/src/main/java/com/example/assistant/core/engine/DefaultAssistantEngine.kt). |
| [`assistant-gemma/`](assistant-gemma/) | Gemma 4 reasoner integration: prompt building, model configuration, availability checks, diagnostics. Example: [`GemmaReasoner.kt`](assistant-gemma/src/main/java/com/example/assistant/gemma/GemmaReasoner.kt). |
| [`assistant-android-accessibility/`](assistant-android-accessibility/) | Android Accessibility Service integration for screen observation and installed-app metadata. Example: [`LucaAccessibilityService.kt`](assistant-android-accessibility/src/main/java/com/example/assistant/android/accessibility/service/LucaAccessibilityService.kt). |
| [`assistant-android-capture/`](assistant-android-capture/) | Screen / screenshot capture pipeline for visual fallback. Example: [`DebugScreenshotCapturer.kt`](assistant-android-capture/src/main/java/com/example/assistant/android/capture/DebugScreenshotCapturer.kt). |
| [`assistant-android-overlay/`](assistant-android-overlay/) | Floating bubble UI and visual assistance components (highlight, focus, bubble position). Example: [`AssistantBubbleOverlayController.kt`](assistant-android-overlay/src/main/java/com/example/assistant/android/overlay/AssistantBubbleOverlayController.kt). |
| [`assistant-android-speech/`](assistant-android-speech/) | Voice output (TTS) abstraction and Android speech implementation. Example: [`AndroidSpeechOutput.kt`](assistant-android-speech/src/main/java/com/example/assistant/android/speech/AndroidSpeechOutput.kt). |
| [`gradle/`](gradle/) + root build files | Multi-module build system, dependency and configuration management. |

**In short:** clean separation between **app shell** ([`app/`](app/)), **platform-agnostic core logic** ([`assistant-core/`](assistant-core/)), **Gemma 4 reasoning** ([`assistant-gemma/`](assistant-gemma/)), and **Android-specific adapters** ([`assistant-android-*`](assistant-android-accessibility/)).

This modular design means the reasoning logic could be reused with a different model, and the Android adapters could be replaced for a different platform.

---

## Tech stack

- **Language:** Kotlin
- **Model:** Gemma 4 E2B (instruction-tuned) — multimodal, vision + reasoning
- **Runtime:** LiteRT-LM
- **Screen reading:** Android Accessibility Service + OCR + Gemma 4 vision fallback
- **Voice:** Android TTS / SpeechRecognizer
- **Min SDK:** Android 10+ (API 29)
- **Build:** Gradle multi-module

---

## Current status

This is a **hackathon prototype**, not a finished product.

### What works today

- ✅ Gemma 4 E2B running on LiteRT-LM
- ✅ Scam text classification on common patterns
- ✅ Icon grounding via accessibility tree (e.g. finding "Settings", "Send", etc.)
- ✅ Play Store handoff for app launching
- ✅ Red box overlay highlighting

### Honest gaps

- ⚠️ Latency too high for fluid interaction on vision-heavy turns
- ⚠️ Occasional grounding errors (e.g. wrong button identified)
- ⚠️ No dialect support yet — a real issue for elderly users who speak primarily a regional dialect at home
- ⚠️ No onboarding / settings screen
- ⚠️ No "stop" button or visual feedback while Luca is listening / speaking

---

## Demo

Video pitch: https://youtu.be/aUE03TDudvA

---

## Why this matters

The hackathon's Digital Equity track is about making AI useful for people who currently aren't served by it. Most AI products assume users who:

- Have flagship phones with lots of RAM
- Trust cloud services with personal data
- Can navigate signup, payment, and account flows
- Read technical interfaces fluently

My parents — and millions of elderly people like them — meet none of these assumptions. Luca is designed for exactly that gap:

- **On-device:** runs on the phones they already own; no flagship required
- **No accounts, no payments:** because account creation is a barrier in itself
- **Natural language:** no jargon, no "settings → notifications → allow"
- **Private:** their messages and screen content never leave the device

---

## License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

Gemma 4 is also released under Apache 2.0 by Google DeepMind, making this submission fully open and redistributable.

---

## Acknowledgements

Built for the [Gemma 4 Good Hackathon](https://www.kaggle.com/competitions/gemma-4-good-hackathon) hosted by Kaggle and Google DeepMind.