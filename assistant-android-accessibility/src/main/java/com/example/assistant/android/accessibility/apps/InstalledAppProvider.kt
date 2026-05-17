package com.example.assistant.android.accessibility.apps

data class InstalledApp(
    val name: String,
    val packageName: String
)

interface InstalledAppProvider {
    fun getInstalledUserApps(): List<InstalledApp>
    fun isAppInstalled(packageName: String): Boolean
}

