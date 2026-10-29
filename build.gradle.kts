// Root build script. Plugin versions are declared here and applied per-module
// so the whole project upgrades from one place.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
