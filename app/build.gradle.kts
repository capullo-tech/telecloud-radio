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
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. Keystore + passwords come from env vars (CI secrets wired in Build.yml;
    // exported vars for a local release build on the Windows host). If the keystore isn't present (e.g. a
    // fork PR without secrets) the release build is left unsigned rather than failing, so CI still
    // validates the build.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let(::file)
        ?.takeIf { it.exists() && it.length() > 0L }
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

dependencies {
    // TDLib client + models (Layer 3): brings lib-tdlib-android (libtdjni.so + org.drinkless.tdlib)
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
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(pins.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation("net.jthink:jaudiotagger:3.0.1")

    // capullo-audio (Layer 2) - the delivery engine's public transport classes: SnapserverProcess,
    // SnapclientProcess, SnapcastControlClient, SnapcontrolPlugin, FIFO sink, BalanceAudioProcessor
    // (re-exports capullo-audio-contracts as api). Brings lib-snapcast-android + ktor transitively.
    implementation(pins.capullo.audio)
    implementation(pins.capullo.audio.ui) // shared control sheet + QR dialog

    // Snapcast multiroom broadcast: native snapserver/snapclient binaries +
    // ktor WebSocket client for the Snapcast JSON-RPC control API
    implementation(pins.lib.snapcast.android)
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
