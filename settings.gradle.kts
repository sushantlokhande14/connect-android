pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "connect"

include(":app")
include(":domain")
include(":data")
include(":fakebackend")
include(":testing")
include(":core:ui")
include(":feature:auth")
include(":feature:discovery")
include(":feature:profile")
include(":feature:chat")
