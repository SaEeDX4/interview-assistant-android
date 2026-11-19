package com.interviewassistant.app.services

/** Mirror of `InterviewAssistant.App/Services/BuildFlavor.cs`. */

/**
 * Customer builds use the Interview Assistant service only. Developer builds
 * additionally allow the prototype "own OpenAI key" path and model/protocol overrides for local testing.
 *
 * The C# switches this with the `DEVELOPER_BUILD` compilation symbol (`#if`); the Kotlin
 * counterpart is this source-level const: a developer build flips it to `true` and rebuilds
 * (P4-PORT-NOTES DC-78). Customer builds ship `false`.
 */
object BuildFlavor {
    const val Developer: Boolean = false
}
