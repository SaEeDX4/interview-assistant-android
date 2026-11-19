pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Same Maven Central mirror as below: the Kotlin compiler-plugin classpath
        // (kotlin-compose-compiler-plugin-embeddable) resolves against THIS repository
        // set — run #157 searched only repo.maven.apache.org despite the mirror being
        // present in dependencyResolutionManagement.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Fallback mirror of Maven Central (Google's official mirror): on 2026-11-19 two
        // consecutive CI runs failed to fetch kotlin-compose-compiler-plugin-embeddable
        // 2.0.21 from repo.maven.apache.org ("Could not find") although the artifact exists;
        // the mirror serves the identical Central content when the primary host fails.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    }
}

rootProject.name = "interview-assistant-android"
include(":app", ":core", ":client", ":contracts", ":parity")
