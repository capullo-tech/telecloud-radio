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
        // lib-snapcast-android + capullo-audio(-ui)/source-telegram + build-conventions (all jitpack)
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack.
        create("libs") { from("com.github.capullo-tech:build-conventions:a8439c66c46c7228e2be5fdc92e1a10e2fc693c0") }
        // Local pins: inter-repo capullo coordinates + TC's own deps + the non-BOM Compose libs.
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}

// Dev: if the sibling capullo-audio checkout exists, build it from source and substitute it for its
// jitpack coordinate (local composite build). On CI / off-share builds (no sibling) the block is
// skipped and the coordinate resolves from jitpack.io at the pinned commit.
if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
        }
    }
}

// Same dev/composite toggle for the Telegram source library: build it from the sibling checkout
// when present, otherwise resolve the pinned jitpack coordinate.
if (file("../capullo-source-telegram").exists()) {
    includeBuild("../capullo-source-telegram") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-source-telegram"))
                .using(project(":capullo-source-telegram"))
        }
    }
}

rootProject.name = "TelecloudRadio"
include(":app")
// TDLib (client + prebuilt .so) now comes transitively via the capullo-source-telegram jitpack
// dependency (which depends on lib-tdlib-android), so there's no local :tdlib / setup_tdlib.sh.
