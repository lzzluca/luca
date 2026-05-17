package com.example.assistant.app

import android.content.Context

private const val PREFS_NAME = "luca_prefs"
private const val KEY_PROJECTION_GRANTED = "projection_granted"

class ProjectionPermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPermission(): Boolean = prefs.getBoolean(KEY_PROJECTION_GRANTED, false)

    fun setPermissionGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_PROJECTION_GRANTED, granted).apply()
    }
}

