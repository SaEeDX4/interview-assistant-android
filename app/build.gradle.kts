plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // P4-B: the settings model (AppSettings) is a @Serializable mirror of the C# class;
    // same plugin and version as :core (P4-PORT-NOTES DC-83).
    alias(libs.plugins.kotlin.serialization)
}

// P8 (P8-PORT-NOTES DC-203): the versionCode is DERIVED from versionName by a fixed
// mechanical scheme, so a lockstep Windows version bump maps to exactly one Android
// versionCode with no hand bookkeeping:
//   MAJOR * 10,000,000 + MINOR * 100,000 + PATCH * 1,000 + stage
//   stage = the N of a "-rc.N" prerelease suffix (1..998), or 999 for a final release.
// Monotonic across the whole lockstep line: 2.1.0-rc.5 (20,100,005) < 2.1.0 final
// (20,100,999) < 2.1.1-rc.1 (20,101,001) < 2.2.0-rc.1 (20,200,001).
val appVersionName = "2.1.0-rc.5"

fun lockstepVersionCode(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3) { "versionName must be MAJOR.MINOR.PATCH[-rc.N]: $version" }
    val stage = if ('-' in version) {
        val suffix = version.substringAfter('-')
        require(suffix.startsWith("rc.")) { "only -rc.N prerelease suffixes are mapped: $version" }
        val rc = suffix.removePrefix("rc.").toInt()
        require(rc in 1..998) { "rc stage must be 1..998 (999 is the final-release stage): $version" }
        rc
    } else {
        999
    }
    return parts[0] * 10_000_000 + parts[1] * 100_000 + parts[2] * 1_000 + stage
}

// P8 (P8-PORT-NOTES DC-204): release signing material is INJECTED, never stored.
// The release signing config exists only when all four values are supplied through
// environment variables (CI secrets / the owner's machine) or -P gradle properties:
//   IA_RELEASE_STORE_FILE / releaseStoreFile         — keystore path (outside this repo)
//   IA_RELEASE_STORE_PASSWORD / releaseStorePassword
//   IA_RELEASE_KEY_ALIAS / releaseKeyAlias           — default "interview-assistant"
//   IA_RELEASE_KEY_PASSWORD / releaseKeyPassword
// With none supplied (the normal CI case today) the release build is UNSIGNED and the
// log line below says so plainly; the owner signs locally per the P8 runbook. No
// keystore, password or secret is ever committed — CI carries a guard step for this.
// DC-208: a variable that is present but EMPTY counts as absent. CI defines the
// IA_RELEASE_* names from (currently unset) secrets, which yields empty strings,
// not nulls — without the blank check the build treated "" as an injected
// keystore path and failed configuration with "Cannot convert '' to a File".
fun String?.presentOrNull(): String? = this?.takeIf { it.isNotBlank() }
val releaseStoreFilePath = (System.getenv("IA_RELEASE_STORE_FILE") ?: findProperty("releaseStoreFile")?.toString()).presentOrNull()
val releaseStorePassword = (System.getenv("IA_RELEASE_STORE_PASSWORD") ?: findProperty("releaseStorePassword")?.toString()).presentOrNull()
val releaseKeyAlias = (System.getenv("IA_RELEASE_KEY_ALIAS") ?: findProperty("releaseKeyAlias")?.toString()).presentOrNull() ?: "interview-assistant"
val releaseKeyPassword = (System.getenv("IA_RELEASE_KEY_PASSWORD") ?: findProperty("releaseKeyPassword")?.toString()).presentOrNull()
val releaseSigningReady = releaseStoreFilePath != null && releaseStorePassword != null && releaseKeyPassword != null
logger.lifecycle(
    if (releaseSigningReady) {
        "Release signing: CONFIGURED (injected keystore, alias '$releaseKeyAlias')."
    } else {
        "Release signing: NOT configured — release artifacts will be UNSIGNED (signing is a local owner step, P8 runbook)."
    },
)

android {
    namespace = "com.interviewassistant.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.interviewassistant.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = lockstepVersionCode(appVersionName)
        // Version name tracks the mirrored Windows release (TRANSFER-MAP §1.2 step 7, Lockstep).
        versionName = appVersionName
    }
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // P8 (P8-PORT-NOTES DC-202): R8/minify stays OFF for the first release —
            // the Windows app ships unobfuscated, the serialization/OkHttp/Billing
            // reflection surface cannot be smoke-tested on a device in this
            // environment, and an unverifiable shrinker is a correctness risk.
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    sourceSets {
        // P8 (P8-PORT-NOTES DC-205): the release variant carries the third-party
        // licence notices as assets/THIRD_PARTY_NOTICES.md, generated from the
        // single source of truth at parity/THIRD_PARTY_NOTICES.md by the
        // copyThirdPartyNotices task below (closes the packaging half of DC-193).
        getByName("release") {
            assets.srcDir(layout.buildDirectory.dir("generated/thirdPartyNotices"))
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

// P8 (DC-205): copy the licence notices into the generated release-assets directory
// wired into the release source set above, before the release assets are merged.
// The packaged path inside the APK/AAB is assets/THIRD_PARTY_NOTICES.md.
val copyThirdPartyNotices = tasks.register<Copy>("copyThirdPartyNotices") {
    from(rootProject.file("parity/THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/thirdPartyNotices"))
}

tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(copyThirdPartyNotices)
}
