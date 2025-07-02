//@Suppress("UnstableApiUsage") // Suppress warnings for incubating APIs
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
@Suppress("UnstableApiUsage") // Suppress warnings for incubating APIs
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NewRfidReader"
include(":app")
