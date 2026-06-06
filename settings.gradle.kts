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
        maven("https://jitpack.io")
    }
}

rootProject.name = "StreamiaX"

include(":app")

// Core modules
include(":core:domain")
include(":core:network")
include(":core:database")
include(":core:common")

// Integration modules
include(":core:streaming")   // Stremio add-on protocol
include(":core:anime")       // Aniyomi source system
include(":core:novel")       // LNReader plugin system

// Feature modules
include(":feature:catalog")
include(":feature:player")
include(":feature:reader")
include(":feature:library")
