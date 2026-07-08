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

// Dev: if the sibling capullo-audio checkout exists, build it from source and substitute it for its
// jitpack coordinate (local composite build). On CI / off-share builds (no sibling) the block is
// skipped and the coordinate resolves from jitpack.io at the pinned commit.
if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
        }
    }
}

rootProject.name = "TelecloudRadio"
include(":app")
// TDLib (client + prebuilt .so) now comes transitively via the capullo-source-telegram jitpack
// dependency (which depends on lib-tdlib-android), so there's no local :tdlib / setup_tdlib.sh.
