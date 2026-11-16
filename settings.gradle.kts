pluginManagement {
    repositories {
        google()
        // Avoid Maven Central 429 rate limits on ephemeral GitHub Actions runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Avoid Maven Central 429 rate limits on ephemeral GitHub Actions runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "binance-overlay"
include(":app")
