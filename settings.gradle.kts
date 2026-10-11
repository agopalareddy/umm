pluginManagement {
    repositories {
        if (providers.gradleProperty("flatpak").isPresent) {
            maven { url = uri("${rootDir}/flatpak-repo") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("flatpak").isPresent) {
            maven { url = uri("${rootDir}/flatpak-repo") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "umm"
include(":core", ":ui", ":app", ":desktop", ":linux")
