plugins {
    id("com.android.application") apply false
    id("com.android.asset-pack") apply false
    id("org.jetbrains.kotlin.android") apply false
}

// Root project hosts no build logic: :app owns the Android build, and plugin
// versions are pinned once in settings.gradle.kts pluginManagement.
//
// Warning: this file must NOT be a copy of app/build.gradle.kts. Applying the
// Android plugin at the root makes Gradle run the root project's
// checkEngineArtifacts against a nonexistent src/main/ and every build fails
// (scp-twin incident, 2026-08-29).
