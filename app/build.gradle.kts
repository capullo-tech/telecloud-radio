import com.android.build.api.artifact.SingleArtifact
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.net.URI
import java.security.MessageDigest

// Build-time-only deps for the fetchCloudflared task: a .deb is an ar archive holding
// data.tar.xz, and unpacking must work identically on the Windows release host too (no
// shelling out to ar/tar), so it's done in pure JVM with commons-compress + xz.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.commons:commons-compress:1.27.1")
        classpath("org.tukaani:xz:1.10")
    }
}

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
        versionCode = 14
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
        // Perf-measurement build: release-compiled (minify is off in release too, so this is
        // near-identical perf-wise) but debuggable + debug-signed, which is required on API 28
        // for atrace app sections and Studio profiling. See docs/perf/.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            matchingFallbacks += listOf("release")
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
    // cloudflared lands in jniLibs as libcloudflared.so (see fetchCloudflared below) and is
    // exec'd from nativeLibraryDir - the same mechanism the engine uses for snapserver.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("cloudflared-jnilibs").get().asFile)
        }
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
        val vn = android.defaultConfig.versionName
        val vc = android.defaultConfig.versionCode
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val copyNamedApk = tasks.register<Copy>("copyNamedApk$cap") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "telecloud-radio-v$vn-vc$vc-${variant.name}.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk-named/${variant.name}"))
        }
        afterEvaluate { tasks.named("assemble$cap").configure { finalizedBy(copyNamedApk) } }
    }
}

// cloudflared (Cloudflare quick tunnel) powers the public-link feature. Stock cloudflared
// builds are static Go binaries whose pure-Go resolver needs /etc/resolv.conf (absent on
// Android → no DNS); Termux's package is a GOOS=android (bionic) build of the same
// Apache-2.0 sources, verified working on-device. Pinned by version + per-ABI sha256 -
// bump deliberately. Follow-up before release: own lib-cloudflared-android build repo.
val cloudflaredVersion = "2026.8.2"
val cloudflaredAbis = mapOf(
    "arm64-v8a" to ("aarch64" to "7ecda51a05326f34a832be6e763eb7c6f71edf4ad49f096b291fa6f8ec5a5377"),
    "armeabi-v7a" to ("arm" to "d2177a6b0724885842d3ec56176aef08ceb7b2ab9d43465054e710d41a583cc9"),
    "x86" to ("i686" to "9e63f8f5dc24c4d31fa4bc9f8ef5cf02bf072c6e1243d0538a34a8f18688fc4f"),
    "x86_64" to ("x86_64" to "33a0d6e69fbc738b98de03d51e3de7bf5de1b28e0b6501ed6cba0cc74ab8cd0e"),
)

val fetchCloudflared = tasks.register("fetchCloudflared") {
    val debDir = layout.buildDirectory.dir("cloudflared/deb")
    val jniDir = layout.buildDirectory.dir("cloudflared-jnilibs")
    inputs.property("version", cloudflaredVersion)
    inputs.property("abis", cloudflaredAbis)
    outputs.dir(jniDir)
    doLast {
        cloudflaredAbis.forEach { (abi, archAndSha) ->
            val (termuxArch, sha256) = archAndSha
            val deb = debDir.get().asFile.resolve("cloudflared-$cloudflaredVersion-$termuxArch.deb")
            if (!deb.exists()) {
                deb.parentFile.mkdirs()
                val url = "https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/" +
                    "cloudflared_${cloudflaredVersion}_$termuxArch.deb"
                URI(url).toURL().openStream().use { input ->
                    deb.outputStream().use { input.copyTo(it) }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            deb.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256) {
                deb.delete()
                throw GradleException(
                    "cloudflared $termuxArch sha256 mismatch: $actual (expected $sha256) - refusing to package",
                )
            }
            // .deb = ar archive containing data.tar.xz; pull usr/bin/cloudflared out of it.
            ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
                generateSequence { ar.nextEntry }
                    .firstOrNull { it.name.startsWith("data.tar") }
                    ?: throw GradleException("data.tar missing in $deb")
                // ar is now positioned at the data.tar.xz payload.
                TarArchiveInputStream(XZCompressorInputStream(ar, true)).use { tar ->
                    generateSequence { tar.nextEntry }
                        .firstOrNull { it.name.endsWith("usr/bin/cloudflared") }
                        ?: throw GradleException("cloudflared binary missing in $deb")
                    val out = jniDir.get().asFile.resolve("$abi/libcloudflared.so")
                    out.parentFile.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    out.setExecutable(true)
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchCloudflared) }

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
