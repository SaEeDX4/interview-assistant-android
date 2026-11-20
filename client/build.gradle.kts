plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    jacoco
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

// P7 (TRANSFER-MAP §5.5): JaCoCo line-coverage gate for the mirror modules. AGP writes the
// unit-test execution data when the jacoco plugin is applied; the verification task enforces
// the §5.5 floor (>= 90% line coverage per module) and fails the build below it. The report
// task emits per-package XML/HTML (the §5.5 per-package reporting for Intelligence, Turn,
// Live, Preparation and Audio). Measured numbers are recorded in docs/P7-PORT-NOTES.md.
jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = false
    }
}

val coverageExcludes = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*")
val coverageClassDirs = files(
    fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") { exclude(coverageExcludes) },
    fileTree("${layout.buildDirectory.get().asFile}/intermediates/javac/debug/classes") { exclude(coverageExcludes) },
)
val coverageExecData = fileTree(layout.buildDirectory.get().asFile) {
    // The Gradle jacoco plugin writes the Test task's exec data to
    // jacoco/<task>.exec; AGP's managed coverage writes it under
    // outputs/unit_test_code_coverage/. Accept both — an empty
    // executionData set makes the JaCoCo tasks SKIP themselves, which
    // would silently disable the gate (DC-198).
    include("jacoco/testDebugUnitTest.exec")
    include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageExecData)
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("testDebugUnitTest")
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(coverageClassDirs)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageExecData)
}
