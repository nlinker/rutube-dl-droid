plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.rust.android) apply false
    // Before AGP 9 Kotlin was compiled by the plugin `org.jetbrains.kotlin.android`.
    // Now AGP 9 compiles Kotlin code itself. The declaration below puts the current version
    // of the Kotlin compiler (see libs.versions.toml) on the classpath,
    // where both AGP and the Compose plugin pick it up
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
