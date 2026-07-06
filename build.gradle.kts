plugins {
    alias(libs.plugins.android.application) apply false
    // No kotlin.android alias: AGP 9.0+ ships built-in Kotlin (see RadioCapullo / the library repos).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

// Org code-style standard (mirrors RadioCapullo). `./gradlew spotlessApply` to format,
// `spotlessCheck` to verify (wired into the Build CI workflow).
spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    // Same relaxations QC took (mirrors that decision). ktlint can't auto-expand
                    // Compose wildcard imports (needs an interactive IDE, unavailable headless) and
                    // hand-wrapping the long lines in soon-to-be-recomposed UI isn't worth it.
                    // All other standard rules stay on; revisit in build-conventions.
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
