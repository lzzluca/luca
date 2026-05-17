pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "elderly-phone-assistant"

include(
    ":app",
    ":assistant-core",
    ":assistant-android-accessibility",
    ":assistant-android-overlay",
    ":assistant-android-speech",
    ":assistant-android-capture",
    ":assistant-gemma"
)

