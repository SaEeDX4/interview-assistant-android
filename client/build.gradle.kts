plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.interviewassistant.client"
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

// TRANSFER-MAP §6.1: :client -> :core, :contracts (mirrors InterviewAssistant.Client references).
// api (not implementation) for the two project references: the client's public API exposes Core and
// Contracts types in its signatures, exactly like the transitive C# ProjectReference.
dependencies {
    api(project(":core"))
    api(project(":contracts"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
