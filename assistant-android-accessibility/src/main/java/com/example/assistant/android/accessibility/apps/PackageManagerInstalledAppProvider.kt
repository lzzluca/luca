package com.example.assistant.android.accessibility.apps

import android.content.Context
import android.content.pm.ApplicationInfo

class PackageManagerInstalledAppProvider(
    private val context: Context
) : InstalledAppProvider {

    override fun getInstalledUserApps(): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { app ->
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .map { app ->
                InstalledApp(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    override fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

