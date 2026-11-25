// Red Phone for Android — standard Android Studio / Gradle build.
// (No Android SDK at hand? `python3 tools/build_apk.py` builds the same APK from Maven Central only.)
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

rootProject.name = "redphone-android"
include(":app")
