package com.interviewassistant.app.viewmodels

import com.interviewassistant.app.ui.components.ToastKind
import com.interviewassistant.app.uistate.HomeRoute
import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.ProfileFact
import com.interviewassistant.core.persistence.StoredSession
import com.interviewassistant.core.persistence.WorkspaceStore
import com.interviewassistant.core.reports.InterviewReport
import com.interviewassistant.core.reports.ReportQuestion
import com.interviewassistant.core.reports.ReportSerializer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

/**
 * The HomeViewModel fixture: the real MainViewModel + real CloudService (fake transports, the
 * P4-C1/C2 pattern) with Home's view seams faked and its clocks fixed, so navigation, guard,
 * editor, account, dashboard, readiness, help and export flows run exactly as in the window.
 */
class HomeFixture(val cloud: VmCloudFixture, initial: HomeRoute? = null) {
    val vmFixture: VmFixture = VmFixture(cloud)
    val vm: MainViewModel get() = vmFixture.vm

    val dialogs = FakeHomeDialogs()
    val files = FakeHomeFiles()
    val clipboard = FakeHomeClipboard()
    val shell = FakeHomeShell()
    val settingsPage = FakeSettingsPageController()
    val preInterview = FakePreInterviewPageController()

    /** Home's own notifications (the window's toast host), in order. */
    val toasts = mutableListOf<Triple<String, ToastKind, String?>>()
    val focusRequests = mutableListOf<String>()

    /** Fixed clocks: 2026-11-19 12:00 local / 17:00 UTC, so stamps and dates are deterministic. */
    var nowLocal: OffsetDateTime = OffsetDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneOffset.ofHours(-5))
    var nowUtc: OffsetDateTime = OffsetDateTime.of(2026, 11, 19, 17, 0, 0, 0, ZoneOffset.UTC)
    var today: LocalDate = LocalDate.of(2026, 11, 19)

    var signer: String? = null
    var os: String = "Test OS 1.0"

    val home: HomeViewModel = HomeViewModel(
        vm = vmFixture.vm,
        scope = vmFixture.scope,
        postToUi = { it() },
        dialogs = dialogs,
        files = files,
        clipboard = clipboard,
        shell = shell,
        settingsPage = settingsPage,
        preInterview = preInterview,
        initial = initial,
        codeSigner = { signer },
        osVersion = { os },
        now = { nowLocal },
        nowUtc = { nowUtc },
        today = { today },
    )

    init {
        home.notifyListeners += { text, kind, detail -> toasts += Triple(text, kind, detail) }
        home.focusListeners += { target -> focusRequests += target }
    }

    /** The signed-in account's store (the workspace must be open). */
    fun store(): WorkspaceStore = vm.workspace!!.store

    fun addProfile(name: String = "Ada Lovelace", facts: List<ProfileFact> = emptyList()): CandidateProfileRecord {
        val p = CandidateProfileRecord(name = name)
        p.facts += facts
        store().saveProfile(p)
        return store().getProfile(p.id)!!
    }

    fun addTarget(
        profileId: String,
        title: String = "Senior Engineer",
        company: String = "Acme",
    ): InterviewTarget {
        val tg = InterviewTarget(profileId = profileId, jobTitle = title, company = company)
        store().saveTarget(tg)
        return store().getTarget(tg.id)!!
    }

    fun addReportSession(
        profileId: String,
        targetId: String,
        started: OffsetDateTime = OffsetDateTime.of(2026, 11, 18, 15, 0, 0, 0, ZoneOffset.UTC),
        questions: List<ReportQuestion> = listOf(
            ReportQuestion(index = 1, originalWording = "Tell me about yourself", suggestionsShown = listOf("I led…")),
        ),
    ): StoredSession {
        val report = InterviewReport(
            sessionId = "s-1",
            profileLabel = "Ada Lovelace",
            targetLabel = "Senior Engineer · Acme",
            startedUtc = started,
            endedUtc = started.plusMinutes(25),
            questions = questions,
        )
        val s = StoredSession(
            profileId = profileId, targetId = targetId,
            startedUtc = started, endedUtc = started.plusMinutes(25),
            reportJson = ReportSerializer.toJson(report),
        )
        store().saveSession(s)
        return s
    }

    fun close() {
        home.close()
        vmFixture.close()
    }
}

/** A fixture whose cloud service is signed in (session + refresh) with the workspace open. */
fun signedInHomeFixture(initial: HomeRoute? = null): HomeFixture {
    val cloud = VmCloudFixture(session = vmSession())
    val f = HomeFixture(cloud, initial)
    runBlocking { cloud.service.refreshAsync() }
    f.vm.openWorkspaceForCurrentAccount()
    f.home.refreshAll()
    return f
}

/** A fixture with no session: configured service, signed out. */
fun signedOutHomeFixture(initial: HomeRoute? = null): HomeFixture {
    val cloud = VmCloudFixture(session = null)
    val f = HomeFixture(cloud, initial)
    runBlocking { cloud.service.refreshAsync() }
    f.home.refreshAll()
    return f
}
