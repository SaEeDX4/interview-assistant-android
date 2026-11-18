// Root build file — plugin versions are pinned in gradle/libs.versions.toml.
// Module dependency directions mirror the Windows solution exactly (TRANSFER-MAP §6.1):
//   app -> core, client | client -> core, contracts | core -> (nothing) | contracts -> (nothing)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
