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
        // lib-snapcast-android (prebuilt snapserver/snapclient binaries)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TelecloudRadio"
include(":app")
// :tdlib is populated by running ./scripts/setup_tdlib.sh
include(":tdlib")
