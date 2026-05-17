package com.example.assistant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsAndAppBranchTest {

    @Test
    fun prefs_defaults_areSafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("luca_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val prefs = Prefs(context)
        assertFalse(prefs.isOnboardingDone())
        assertEquals("en-US", prefs.getInteractionLanguage())
        assertEquals(TRIGGER_BUTTON_VOLUME_UP, prefs.getTriggerButton())
    }

    @Test
    fun prefs_persist_language_and_trigger_with_safetyFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("luca_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val prefs = Prefs(context)
        prefs.setInteractionLanguage("it-IT")
        prefs.setTriggerButton("invalid")

        assertEquals("it-IT", prefs.getInteractionLanguage())
        assertEquals(TRIGGER_BUTTON_VOLUME_UP, prefs.getTriggerButton())

        prefs.setTriggerButton(TRIGGER_BUTTON_VOLUME_DOWN)
        assertEquals(TRIGGER_BUTTON_VOLUME_DOWN, prefs.getTriggerButton())
    }

    @Test
    fun appBranch_routesToOnboarding_whenOnboardingNotDone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("luca_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val destination = resolveStartupDestination(Prefs(context).isOnboardingDone())
        assertEquals(OnboardingActivity::class.java, destination)
    }

    @Test
    fun appBranch_routesToSetup_whenOnboardingDone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("luca_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).setOnboardingDone(true)

        val destination = resolveStartupDestination(Prefs(context).isOnboardingDone())
        assertEquals(SetupActivity::class.java, destination)
    }
}
