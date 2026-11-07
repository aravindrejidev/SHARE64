pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SHARE64"

include(":core-cpp")
include(":core-network")
include(":core-app")
include(":app-mobile")
// Next part adds: include(":app-tv")
