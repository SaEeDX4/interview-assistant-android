package com.interviewassistant.app

/**
 * Mirror of the static state of `InterviewAssistant.App/App.xaml.cs`: process-wide flags set by the
 * entry point. On Windows they come from command-line arguments (`--selftest`, `--fixture <dir>`); the
 * Android entry point has no equivalent yet, so they keep their C# defaults (null / 0) until a self-test
 * port sets them (P4-PORT-NOTES DC-95).
 */
object App {
    /** Set by --selftest; used as the process exit code after clean shutdown. */
    var requestedExitCode: Int = 0

    /** Non-null while a self-test runs; also the owner key of the self-test workspace. */
    var selfTestOutput: String? = null
        private set

    /** --fixture <dir>: test pack used by the self-test (never shipped in the product). */
    var fixtureDir: String? = null
        private set
}
