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

// Full test diagnostics in CI logs: every test's start/pass is logged (so a hang names the
// test it stopped in), failures carry full stack traces, and a stuck test task fails after
// 10 minutes instead of hanging the run (CI run #77 hung 38+ minutes in :client tests).
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("started", "passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
        timeout.set(java.time.Duration.ofMinutes(6)) // TEMP-DIAG
    }
}
