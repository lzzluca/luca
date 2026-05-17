package com.example.assistant.android.overlay

import android.content.Context
import kotlin.math.roundToInt

private const val PREFS_NAME = "luca_overlay"
private const val KEY_X = "bubble_x"
private const val KEY_Y = "bubble_y"

class BubblePositionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(x: Float, y: Float) {
        prefs.edit()
            .putInt(KEY_X, x.roundToInt())
            .putInt(KEY_Y, y.roundToInt())
            .apply()
    }

    fun load(): Pair<Int, Int>? {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y)) return null
        return prefs.getInt(KEY_X, 0) to prefs.getInt(KEY_Y, 0)
    }
}

