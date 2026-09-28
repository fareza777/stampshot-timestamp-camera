pluginManagement {
    repositories {
        google()
        // GCS mirror of Maven Central — repo.maven.apache.org is rate-limited in some environments.
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "StampShot"
include(":app")
