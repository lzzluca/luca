package com.example.assistant.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val destination = resolveStartupDestination(prefs.isOnboardingDone())
        startActivity(Intent(this, destination))
        finish()
    }
}
