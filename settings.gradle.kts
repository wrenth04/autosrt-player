pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.dagger.hilt.android") version "2.50" apply false
        id("org.jetbrains.kotlin.android") version "1.9.24" apply false
        id("com.android.application") version "8.5.2" apply false
        id("kotlin-kapt") version "1.9.24" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "autosrt-player"
include("app")
