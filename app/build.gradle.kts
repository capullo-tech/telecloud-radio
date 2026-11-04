import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin (see RadioCapullo).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tech.capullo.telecloudradio"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.telecloudradio"
        minSdk = 23
        targetSdk = 36
        versionCode = 18
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. Keystore + passwords come from env vars (CI secrets wired in Build.yml;
    // exported vars for a local release build on the Windows host). If the keystore isn't present (e.g. a
    // fork PR without secrets) the release build is left unsigned rather than failing, so CI still
    // validates the build.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let(::file)
        ?.takeIf { it.exists() && it.length() > 0L }
    if (releaseKeystore == null) {
        // Configuration-time, so it prints on every invocation - including assembleDebug, where it
        // is expected and harmless. It matters for :app:assembleRelease (left unsigned) and for
        // :app:assembleRig, which silently falls back to the debug key: a rig APK built this way
        // cannot install over a release build, and the INSTALL_FAILED_UPDATE_INCOMPATIBLE that
        // follows reads like a signature bug rather than a missing secret. One wrong secret name
        // is enough to reach here with a green build.
        logger.warn(
            "RELEASE_KEYSTORE_FILE is unset or empty: release will be unsigned and the rig " +
                "variant will be debug-signed, so it will NOT install over a release build.",
        )
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // Rig build: debuggable, but signed with the RELEASE key so it installs straight over a
        // release build. That combination is the point. It is also where perf measurement happens
        // now - see the `benchmark` note at the end of this block.
        //
        // Telecloud's Telegram session lives in files/tdlib (TdLibTelegramClient sets
        // databaseDirectory there). A signature change forces an uninstall, and an uninstall wipes
        // it - so a normal debug build costs a full Telegram re-login every time, which is why the
        // rig has always tested release builds and had no `dbg` hooks, no `run-as`, and no way to
        // read app storage. Same key = install -r just works, and isDebuggable enables all three.
        //
        // The same applicationId as release, deliberately: a suffix would install side by side and
        // get its own empty files/tdlib, which is exactly what we are avoiding.
        //
        // Falls back to the debug key when the keystore is absent (fork PRs, CI without secrets) so
        // the variant still builds; it just cannot install over a release build in that case. That
        // fallback warns at configuration time, and CI fails the build if a keystore IS present but
        // the rig APK did not end up carrying the release cert.
        //
        // Identity comes from versionName, not versionCode: a versionCode lives in defaultConfig
        // and would move release too, and the two builds have to share one so that install -r
        // works in BOTH directions without --allow-downgrade. So rig reports "1.0-rig" and release
        // reports "1.0" at the same versionCode - `dumpsys package tech.capullo.telecloudradio`
        // tells them apart, and so does the apk-named filename, which already carries the variant.
        // An applicationIdSuffix would do it too, and is exactly what we must not use: it installs
        // side by side with its own empty files/tdlib.
        create("rig") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isDebuggable = true
            versionNameSuffix = "-rig"
            matchingFallbacks += listOf("debug")
        }
        // There used to be a `benchmark` type here: release-compiled, debuggable, debug-signed,
        // added in 013182f for the player/queue-sheet jank work (docs/perf/). It predated `rig`,
        // and `rig` superseded it. Release-compiled bought nothing measurable while
        // isMinifyEnabled stays false above - the old comment conceded as much, "minify is off in
        // release too, so this is near-identical perf-wise" - so the two differed only in signing
        // key, and debug-signed is the worse choice: it cannot install over a release build, which
        // on TC means an uninstall and a wiped files/tdlib.
        //
        // Revisit when R8 is turned on, but try `isMinifyEnabled = true` on `rig` FIRST rather
        // than reviving a second type: a minified, debuggable, release-signed rig profiles the
        // code that actually ships and still installs over a release build. The reason that might
        // not be enough is not the compilation flavour, it is that R8 renames and inlines - so
        // method-level profiling comes back obfuscated without the mapping file, and inlining
        // erases the frames a profiler wants. PerfTrace sections survive it (literal names passed
        // to Trace.beginSection), so frame-timing work like docs/perf/ should be fine on a
        // minified rig; symbol-level work is what would justify a separate type again.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors RadioCapullo). compilerOptions, NOT jvmToolchain(17):
    // the Windows host JBR is 21 with no standalone JDK 17 to provision.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Kotlin 2.3 annotation-target opt-in (bears on serialization + Room annotations).
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            // Required for TDLib .so files placed in app/src/main/jniLibs/<abi>/
            useLegacyPackaging = true
        }
    }

}

// Compose compiler stability/skippability reports (perf groundwork; docs/perf/).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

// Self-identifying APK copies: telecloud-radio-v<versionName>-vc<versionCode>-<variant>.apk under
// build/outputs/apk-named/<variant>/, produced automatically after each assemble. Uses only the
// public artifacts API (SingleArtifact.APK) - no internal AGP classes - so it survives AGP upgrades.
// The standard app-<variant>.apk stays in place for installDebug and friends.
androidComponents {
    onVariants { variant ->
        // The variant's OWN versionName, not defaultConfig's, so a versionNameSuffix reaches the
        // filename: the rig APK is telecloud-radio-v1.0-rig-vc16-rig.apk, and a rig and a release
        // APK at the same versionCode cannot be confused for each other on disk.
        val vn = variant.outputs.first().versionName
        val vc = android.defaultConfig.versionCode
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val copyNamedApk = tasks.register<Copy>("copyNamedApk$cap") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "telecloud-radio-v${vn.get()}-vc$vc-${variant.name}.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk-named/${variant.name}"))
        }
        afterEvaluate { tasks.named("assemble$cap").configure { finalizedBy(copyNamedApk) } }
    }
}

dependencies {
    // Telegram source: brings lib-tdlib-android (libtdjni.so + org.drinkless.tdlib)
    // transitively. Replaces Telecloud's own :tdlib module + TelegramClient/TdLibTelegramClient copies.
    implementation(pins.capullo.source.telegram)

    ksp(libs.hilt.android.compiler)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    implementation(pins.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(pins.androidx.material3)
    implementation(pins.androidx.compose.material.icons.core)
    implementation(pins.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(pins.androidx.startup.runtime)
    // Album-art colour extraction feeding the dynamic theme (see ui/theme/AlbumArtTheme.kt).
    implementation(pins.androidx.palette.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(pins.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation("net.jthink:jaudiotagger:3.0.1")

    // capullo-audio - the delivery engine's public transport classes: SnapserverProcess,
    // SnapclientProcess, SnapcastControlClient, SnapcontrolPlugin, FIFO sink, BalanceAudioProcessor
    // (re-exports capullo-audio-contracts as api). Brings lib-snapcast-android (the native
    // snapserver/snapclient/snapcontrol .so binaries) + ktor transitively - no direct lib-snapcast
    // pin here on purpose: a direct pin would race capullo-audio's transitive one on version
    // conflict and could package a stale .so (green build, dead control plane). QC does the same.
    implementation(pins.capullo.audio)
    implementation(pins.capullo.audio.ui) // shared control sheet + QR dialog
    // Public-link tunnel: TunnelManager + the cloudflared .so per ABI, carried by the AAR. Opt-in,
    // hence a separate coordinate: it costs ~8 MB per ABI and an app is free not to have a tunnel.
    implementation(pins.capullo.tunnel)

    // ktor WebSocket client for the Snapcast JSON-RPC control API
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    // QR generation for the multiroom "listen here" address
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(pins.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
