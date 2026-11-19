package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.services.IAudioCaptureService
import com.interviewassistant.app.services.INetworkAvailability
import com.interviewassistant.app.services.IUpdateService
import com.interviewassistant.app.services.PlaybackDevice
import com.interviewassistant.app.services.ProcessStats
import com.interviewassistant.app.services.ProcessStatsProvider
import com.interviewassistant.app.services.captureStatusLabel
import com.interviewassistant.core.knowledge.BankQuestion
import com.interviewassistant.core.knowledge.CandidateProfile
import com.interviewassistant.core.knowledge.CandidateStory
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.knowledge.PromptTemplates
import com.interviewassistant.core.knowledge.TargetContext

/** Test doubles for the two platform seams + the small seams, and the fixture knowledge base. */

/** In-memory [IAudioCaptureService]: records calls, lets a test raise frames/statuses like the device would. */
class FakeCaptureService : IAudioCaptureService {
    override val frameReadyListeners: MutableList<(ByteArray, Double) -> Unit> = mutableListOf()
    override val statusChangedListeners: MutableList<(String) -> Unit> = mutableListOf()

    override var currentDeviceName: String = "Fake Headset"
    override var status: String = "STOPPED"
    override val statusLabel: String get() = captureStatusLabel(status)
    override var sampleRate: Int = 48000
    override var channels: Int = 2
    override var encoding: String = "Float32"
    override var lastPeakDb: Double = -100.0
    override var lastRmsDb: Double = -100.0
    override var framesWithSignal: Long = 0
    override var isRunning: Boolean = false
        private set

    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    var lastDeviceId: String? = null
        private set

    override fun listDevices(): List<PlaybackDevice> =
        listOf(PlaybackDevice(null, "System default"), PlaybackDevice("dev-1", "Fake Headset"))

    override fun start(deviceId: String?) {
        startCalls++
        lastDeviceId = deviceId
        isRunning = true
        setStatus("ACTIVE")
    }

    override fun stop() {
        stopCalls++
        isRunning = false
        setStatus("STOPPED")
    }

    override fun dispose() {
        disposeCalls++
    }

    fun setStatus(s: String) {
        status = s
        for (listener in statusChangedListeners.toList()) listener(s)
    }

    fun raiseFrame(frame: ByteArray, db: Double) {
        for (listener in frameReadyListeners.toList()) listener(frame, db)
    }
}

/** In-memory [IUpdateService]: records ticks/checks; state is settable by the test. */
class FakeUpdateService : IUpdateService {
    override var status: String = ""
    override var statusText: String = ""
    override var isInstalled: Boolean = false
    override var updateReady: Boolean = false
    override var pendingVersion: String? = null
    override var channel: String = "stable"
    override var canCheck: Boolean = false
    override var isChecking: Boolean = false
    override val changedListeners: MutableList<() -> Unit> = mutableListOf()

    val tickCalls = mutableListOf<Boolean>()
    val checkNowCalls = mutableListOf<Boolean>()
    var applyOnExitCalls: Int = 0
        private set
    var tryApplyResult: Boolean = false

    override suspend fun tickAsync(sessionActive: Boolean) {
        tickCalls += sessionActive
    }

    override suspend fun checkNowAsync(sessionActive: Boolean) {
        checkNowCalls += sessionActive
    }

    override fun applyOnExit() {
        applyOnExitCalls++
    }

    override fun tryApplyAndRestart(sessionActive: Boolean): Boolean = tryApplyResult
}

/** In-memory [INetworkAvailability]: tests fire availability changes explicitly. */
class FakeNetworkAvailability : INetworkAvailability {
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    override fun addAvailabilityChangedListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    override fun removeAvailabilityChangedListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    fun fire(available: Boolean) {
        for (listener in listeners.toList()) listener(available)
    }
}

/** Fixed [ProcessStatsProvider] (the diagnostics test varies it through [stats]). */
class FakeProcessStats(var stats: ProcessStats = ProcessStats(0, 128L * 1048576, 64L * 1048576, 8)) : ProcessStatsProvider {
    override fun sample(): ProcessStats = stats
}

/**
 * The fixture knowledge base for the VM tests: the two bank questions the Windows SelfTest drives the
 * real pack with (Q001 "Tell me about yourself." and Q027 "How would you increase XAB adoption?"),
 * field-for-field from `samples/shervin-teroxx/question_bank.json` — same canonical questions, keywords,
 * variants, bullets, coach keywords and structures, so the matcher behaves exactly as in the SelfTest.
 */
object FixtureKnowledge {
    fun build(): KnowledgeBase = KnowledgeBase(
        profile = CandidateProfile(name = "Shervin", preferredName = "Shervin"),
        stories = listOf(
            CandidateStory(id = "arzif_revenue_retention", company = "Arzif Crypto Exchange", role = "Operations Lead"),
        ),
        questions = listOf(
            BankQuestion(
                questionId = "Q001",
                canonicalQuestion = "Tell me about yourself.",
                category = "INTRODUCTION",
                intent = "Tell me about yourself",
                keywords = listOf(
                    "yourself", "walk", "through", "background", "introduce", "give",
                    "quick", "introduction", "bit", "career", "far", "shervin",
                ),
                semanticVariants = listOf(
                    "Walk me through your background", "Introduce yourself", "Can you give us a quick introduction",
                    "Tell us a bit about your career so far", "Walk me through your CV", "Who is Shervin",
                ),
                answerModeRaw = "VERIFIED",
                shortBullets = listOf(
                    "I'm a business and operations leader with over fifteen years across consumer electronics, IT and crypto.",
                    "At Arzif Crypto Exchange I led operations and strategy, growing the platform to sixty thousand active traders and revenue by sixty percent.",
                    "Before that, at LG I led go-to-market for product launches, and my software engineering degree helps me work closely with tech teams.",
                    "Product Ownership of XAB is the natural next step, because it combines users, crypto, growth and technology in one role.",
                ),
                coachKeywords = listOf("IT", "ARZIF CRYPTO EXCHANGE", "LG"),
                answerStructure = "Situation → what I did → result",
                confidence = 1.0,
            ),
            BankQuestion(
                questionId = "Q027",
                canonicalQuestion = "How would you increase XAB adoption?",
                category = "XAB",
                intent = "How would you increase XAB adoption",
                keywords = listOf(
                    "increase", "xab", "adoption", "drive", "get", "more",
                    "users", "use", "grow", "user", "base", "usage", "token",
                ),
                semanticVariants = listOf(
                    "How would you drive XAB adoption", "How do we get more users to use XAB",
                    "How would you grow the XAB user base", "How would you increase usage of the token",
                ),
                answerModeRaw = "HYPOTHETICAL",
                shortBullets = listOf(
                    "I'd start with utility, understanding exactly why users should earn, hold and use XAB.",
                    "Then I'd connect meaningful benefits like fee advantages, rewards and status progression to real user behaviour.",
                    "Finally, I'd measure activation, XAB usage, retention, reward cost and incremental business value.",
                ),
                candidateEvidence = listOf("arzif_revenue_retention"),
                followUpQuestions = listOf("Which metric would you look at first?"),
                coachKeywords = listOf("XAB", "REWARD", "INCREMENTAL"),
                answerStructure = "Direct answer → approach → measure",
                confidence = 1.0,
            ),
        ),
        snippets = listOf(),
        systemPromptTemplate = PromptTemplates.LiveAnswer,
        context = TargetContext(
            candidateName = "Shervin",
            roleTitle = "Product Owner",
            companyName = "Teroxx",
            interviewLanguage = "en",
        ),
    )
}
