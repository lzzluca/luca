package com.example.assistant.app.session

import com.example.assistant.android.accessibility.apps.InstalledApp
import com.example.assistant.android.accessibility.apps.InstalledAppProvider
import com.example.assistant.core.model.AssistantTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppToolRoutingTest {

    @Test
    fun appName_routesToPlayStoreSemanticImplementation_whenPlayStoreInstalled() {
        val routing = AppToolRouting(
            installedAppProvider = fakeProvider(
                apps = listOf(InstalledApp(name = "YouTube", packageName = "com.google.android.youtube")),
                playStoreInstalled = true
            )
        )

        val decision = routing.routeByAppName("YouTube")
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, decision.request?.name)
        assertEquals("YouTube", decision.request?.arguments?.get("app_name"))
        assertEquals("open_play_store_and_search", decision.semanticAppRoute)
        assertEquals("play_store", decision.internalAppRoute)
        assertEquals(true, decision.playStoreAvailable)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun missingApp_routesToPlayStoreSearch_whenPlayStoreInstalled() {
        val routing = AppToolRouting(
            installedAppProvider = fakeProvider(
                apps = listOf(InstalledApp(name = "Maps", packageName = "com.google.android.apps.maps")),
                playStoreInstalled = true
            )
        )

        val decision = routing.routeByAppName("Signal")
        assertEquals(AssistantTools.OPEN_PLAY_STORE_AND_SEARCH, decision.request?.name)
        assertEquals("Signal", decision.request?.arguments?.get("app_name"))
        assertEquals("open_play_store_and_search", decision.semanticAppRoute)
        assertEquals("play_store", decision.internalAppRoute)
        assertEquals(true, decision.playStoreAvailable)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun missingApp_andMissingPlayStore_returnsGracefulFallback() {
        val routing = AppToolRouting(
            installedAppProvider = fakeProvider(
                apps = emptyList(),
                playStoreInstalled = false
            )
        )

        val decision = routing.routeByAppName("Signal")
        assertNull(decision.request)
        assertEquals("open_play_store_and_search", decision.semanticAppRoute)
        assertEquals("fallback", decision.internalAppRoute)
        assertEquals(false, decision.playStoreAvailable)
        assertEquals(FallbackReason.PLAY_STORE_UNAVAILABLE, decision.fallbackReason)
    }

    private fun fakeProvider(
        apps: List<InstalledApp>,
        playStoreInstalled: Boolean
    ) = object : InstalledAppProvider {
        override fun getInstalledUserApps(): List<InstalledApp> = apps

        override fun isAppInstalled(packageName: String): Boolean {
            return if (packageName == "com.android.vending") playStoreInstalled else apps.any { it.packageName == packageName }
        }
    }
}
