pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Kursverlauf"
include(":app")
