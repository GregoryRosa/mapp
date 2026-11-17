plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Aplicado em app/build.gradle.kts só quando existe google-services.json.
    alias(libs.plugins.google.services) apply false
}
