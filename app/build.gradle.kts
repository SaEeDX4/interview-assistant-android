plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // P4-B: the settings model (AppSettings) is a @Serializable mirror of the C# class;
    // same plugin and version as :core (P4-PORT-NOTES DC-83).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.interviewassistant.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.interviewassistant.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        // Version name tracks the mirrored Windows release (TRANSFER-MAP §1.2 step 7, Lockstep).
        versionName = "2.1.0-rc.5"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

// TRANSFER-MAP §6.1: :app -> :core, :client (mirrors InterviewAssistant.App references).
dependencies {
    implementation(project(":core"))
    implementation(project(":client"))
    // kotlinx.serialization on :app's compile classpath too: ErrorCopy matches
    // SerializationException directly (P4-PORT-NOTES DC-64, parent-resolved), and the
    // settings model (P4-B) serializes settings.json with it.
    implementation(libs.kotlinx.serialization.json)
    // OkHttp on :app's compile classpath too: CloudService constructs the auth/API clients itself
    // (the C# constructs its HttpClients), with the same catalog artifact :core/:client use for
    // the BCL HttpClient surface (P4-PORT-NOTES DC-85).
    implementation(libs.okhttp)
    // P6 (P6-PORT-NOTES DC-182): Google Play Billing (OD-1: Android billing is Play Billing) and
    // Custom Tabs for the Paddle checkout/portal path (TRANSFER-MAP §6.2 browser row).
    implementation(libs.play.billing)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
