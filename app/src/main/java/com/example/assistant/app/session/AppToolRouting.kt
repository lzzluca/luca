package com.example.assistant.app.session

import com.example.assistant.android.accessibility.apps.InstalledApp
import com.example.assistant.android.accessibility.apps.InstalledAppProvider
import com.example.assistant.core.model.AssistantTools
import com.example.assistant.core.model.ToolRequest

data class AppRoutingDecision(
    val request: ToolRequest?,
    val requestedAppName: String? = null,
    val semanticAppRoute: String = "open_play_store_and_search",
    val internalAppRoute: String? = null,
    val playStoreAvailable: Boolean? = null,
    val fallbackReason: FallbackReason? = null
)

enum class FallbackReason {
    APP_NAME_MISSING,
    PLAY_STORE_UNAVAILABLE
}

class AppToolRouting(
    private val installedAppProvider: InstalledAppProvider,
    private val playStorePackage: String = "com.android.vending"
) {
    fun routeByAppName(appName: String): AppRoutingDecision {
        val normalized = appName.trim()
        if (normalized.isBlank()) {
            return AppRoutingDecision(
                request = null,
                requestedAppName = null,
                internalAppRoute = "fallback",
                playStoreAvailable = installedAppProvider.isAppInstalled(playStorePackage),
                fallbackReason = FallbackReason.APP_NAME_MISSING
            )
        }

        val playStoreAvailable = installedAppProvider.isAppInstalled(playStorePackage)

        return if (playStoreAvailable) {
            AppRoutingDecision(
                request = ToolRequest(
                    name = AssistantTools.OPEN_PLAY_STORE_AND_SEARCH,
                    arguments = mapOf("app_name" to normalized)
                ),
                requestedAppName = normalized,
                internalAppRoute = "play_store",
                playStoreAvailable = true
            )
        } else {
            AppRoutingDecision(
                request = null,
                requestedAppName = normalized,
                internalAppRoute = "fallback",
                playStoreAvailable = false,
                fallbackReason = FallbackReason.PLAY_STORE_UNAVAILABLE
            )
        }
    }
}
