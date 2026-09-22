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

rootProject.name = "Nexus"

// Clean Architecture module graph:
//   :app  ->  :core:designsystem, :core:ai, :core:model, :core:common
//   :core:ai     ->  :core:model, :core:common
//   :core:model  ->  :core:common
include(":app")
include(":core:common")
include(":core:model")
include(":core:ai")
include(":core:designsystem")
