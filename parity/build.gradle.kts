plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.interviewassistant.parity"
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

// Parity harness module (TRANSFER-MAP §6.1): holds TEST-MAP, asset checksums and golden
// fixtures, and hosts the parity tests of §5. It depends on the modules it verifies.
dependencies {
    implementation(project(":core"))
    implementation(project(":contracts"))
    implementation(project(":client"))
}
