package com.example.assistant.app

import android.content.Context

private const val PREFS_NAME = "luca_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_done"
private const val KEY_INTERACTION_LANGUAGE = "interaction_language"
private const val KEY_TRIGGER_BUTTON = "trigger_button"

const val TRIGGER_BUTTON_VOLUME_UP = "volume_up"
const val TRIGGER_BUTTON_VOLUME_DOWN = "volume_down"

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    fun getInteractionLanguage(): String = prefs.getString(KEY_INTERACTION_LANGUAGE, "en-US") ?: "en-US"

    fun setInteractionLanguage(languageTag: String) {
        prefs.edit().putString(KEY_INTERACTION_LANGUAGE, languageTag).apply()
    }

    fun getTriggerButton(): String = prefs.getString(KEY_TRIGGER_BUTTON, TRIGGER_BUTTON_VOLUME_UP) ?: TRIGGER_BUTTON_VOLUME_UP

    fun setTriggerButton(button: String) {
        val safe = when (button) {
            TRIGGER_BUTTON_VOLUME_DOWN -> TRIGGER_BUTTON_VOLUME_DOWN
            else -> TRIGGER_BUTTON_VOLUME_UP
        }
        prefs.edit().putString(KEY_TRIGGER_BUTTON, safe).apply()
    }
}
