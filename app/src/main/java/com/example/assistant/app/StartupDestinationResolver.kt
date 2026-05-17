package com.example.assistant.app

import androidx.appcompat.app.AppCompatActivity

fun resolveStartupDestination(onboardingDone: Boolean): Class<out AppCompatActivity> {
    return if (onboardingDone) SetupActivity::class.java else OnboardingActivity::class.java
}

