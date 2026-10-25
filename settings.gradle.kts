pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (avoids Central's rate limiting); Central itself as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (avoids Central's rate limiting); Central itself as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "Zapline"
include(":app", ":core", ":desktop")
