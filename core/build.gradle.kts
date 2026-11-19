plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.interviewassistant.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// TRANSFER-MAP §6.1: :core depends on no other PROJECT (mirrors InterviewAssistant.Core project references).
// kotlinx.serialization is the BCL-JSON counterpart (Loc's table parsing), from the existing catalog.
// OkHttp (DC-38): the C# providers use BCL HttpClient/ClientWebSocket; Android has no BCL-equivalent
// (java.net.http does not exist on Android), so the catalog's existing OkHttp — the same artifact and
// version :client already uses for the same BCL surface — is the platform counterpart, not a new capability.
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
