package com.interviewassistant.app.services

import com.interviewassistant.app.uistate.UiStateLocFixtures
import com.interviewassistant.core.languages.Loc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The BuildInfo derivation table (version / commit / channel / summary) and the
 * Services/SelfTest.cs "Build information: version, channel and commit" check.
 */
class BuildInfoTests {
    private var original: String = "0.0.0"

    @Before
    fun setUp() {
        UiStateLocFixtures.installLocTables()
        original = BuildInfo.informationalVersion
    }

    @After
    fun tearDown() {
        BuildInfo.installInformationalVersion(original)
    }

    private fun check(informational: String, version: String, commit: String, channel: String) {
        BuildInfo.installInformationalVersion(informational)
        assertEquals("version of $informational", version, BuildInfo.version)
        assertEquals("commit of $informational", commit, BuildInfo.commit)
        assertEquals("channel of $informational", channel, BuildInfo.channel)
    }

    @Test
    fun derivation_FromTheInformationalVersion() {
        check("2.1.0-rc.5+abc1234deadbeef", "2.1.0-rc.5", "abc1234", "Release Candidate")
        check("2.1.0-rc.5", "2.1.0-rc.5", "local", "Release Candidate")
        check("2.0.0-RC.1", "2.0.0-RC.1", "local", "Release Candidate") // case-insensitive, as in C#
        check("3.0.0-beta.2+x", "3.0.0-beta.2", "x", "Beta")
        check("3.0.0-alpha+1234567", "3.0.0-alpha", "1234567", "Beta")
        check("3.0.0-preview.1", "3.0.0-preview.1", "local", "Pre-release")
        check("3.0.0", "3.0.0", "local", "Stable")
        check("1.0.0+abc", "1.0.0", "abc", "Stable") // short metadata kept whole
        check("1.0.0+", "1.0.0", "local", "Stable") // empty metadata: local
        check("0.0.0", "0.0.0", "local", "Stable")
    }

    @Test
    fun summary_IsVersionChannelBuild() {
        BuildInfo.installInformationalVersion("2.1.0-rc.5+abc1234deadbeef")
        assertEquals("Version 2.1.0-rc.5 · Release Candidate · Build abc1234", BuildInfo.summary)
    }

    @Test
    fun buildInformation_SelfTestCheck() {
        BuildInfo.installInformationalVersion("2.1.0-rc.5+abc1234deadbeef")
        val v = BuildInfo.version
        assertTrue(v.isNotEmpty())
        assertFalse(v.contains('+'))
        assertTrue(BuildInfo.channel.isNotEmpty())
        assertTrue(BuildInfo.commit.isNotEmpty())
        if (v.contains("-rc")) assertEquals("Release Candidate", BuildInfo.channel)
        assertTrue(BuildInfo.summary.isNotEmpty())
    }

    @Test
    fun channelLabel_ResolvesThroughLoc() {
        BuildInfo.installInformationalVersion("2.1.0-rc.5")
        assertEquals("Release Candidate", BuildInfo.channelLabel) // en: channel_rc
        BuildInfo.installInformationalVersion("3.0.0-beta.1")
        assertEquals(Loc.t("channel_beta_build"), BuildInfo.channelLabel)
        BuildInfo.installInformationalVersion("3.0.0")
        assertEquals(Loc.t("channel_stable_build"), BuildInfo.channelLabel)
    }

    @Test
    fun isInstalled_OnAndroidEveryCopyIsInstalled() {
        assertTrue(BuildInfo.isInstalled)
    }
}
