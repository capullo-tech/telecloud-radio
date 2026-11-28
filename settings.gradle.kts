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
        // capullo-tech coordinates exist only on jitpack. Without this, Gradle asks Maven Central for
        // them first, and CI has failed on Maven Central's 429 answers to those lookups.
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroupAndSubgroups("com.github.capullo-tech") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack.
        create("libs") { from("com.github.capullo-tech:build-conventions:c772e4f9bdb4d16bd7141c3459dcf4863513c00c") }
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
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-tunnel"))
                .using(project(":capullo-tunnel"))
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
