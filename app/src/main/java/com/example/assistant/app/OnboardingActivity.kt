package com.example.assistant.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        content.addView(TextView(this).apply {
            text = "Luca helps you understand what is on your phone screen."
            textSize = 20f
        })

        content.addView(TextView(this).apply {
            text = "\nLuca uses:\n• Accessibility permission\n• Screen vision permission\n• Microphone permission\n\nLuca only guides and speaks. It does not control your phone.\n\nDefault activation is volume-up long press. Some devices may not support volume key interception."
        })

        content.addView(Button(this).apply {
            text = "Get started"
            setOnClickListener {
                Prefs(this@OnboardingActivity).setOnboardingDone(true)
                startActivity(Intent(this@OnboardingActivity, SetupActivity::class.java))
                finish()
            }
        })

        root.addView(content)
        setContentView(root)
    }
}

