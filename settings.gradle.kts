pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Explicit versions: the root and :app build scripts declare the
        // plugins without versions, and marker resolution used to rely on a
        // cache entry that is not guaranteed across machines/caches.
        id("com.android.application") version "8.10.1"
        id("com.android.asset-pack") version "8.10.1"
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Feelime"
include(":app")
include(":feelime-models")
