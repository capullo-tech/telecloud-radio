plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

// Org code-style standard (mirrors RadioCapullo). Run `./gradlew spotlessApply` to format,
// `spotlessCheck` to verify (wired into the Build CI workflow). NOTE: the initial spotlessApply
// reformat has not been run/verified yet (no Android SDK on the authoring box) — do it on a clean
// tree as the first step of the Phase 0 toolchain pass, then CI's spotlessCheck goes green.
spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                ),
            )
    }
}
