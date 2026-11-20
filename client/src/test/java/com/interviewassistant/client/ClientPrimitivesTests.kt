package com.interviewassistant.client

import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The small pure types of the client mirror (P7 coverage closure): `Pkce.cs` verifier rules and
 * base64url shape, the `Uri.EscapeDataString`/`UnescapeDataString` equivalents, the
 * `LoopbackRedirect.ParseQuery` port and the redirect decision table in [RedirectUriParser],
 * `CloudOptions.cs` readiness rules and the `cloud.json` parse, `InstallationIdentity.cs`
 * validity and reuse rules, and the [TimeProvider] system instance.
 */
class ClientPrimitivesTests {

    // ---- Pkce ----

    @Test
    fun verifierValidity_LengthWindowAndAlphabet() {
        assertFalse(Pkce.isValidVerifier("a".repeat(42)))
        assertTrue(Pkce.isValidVerifier("a".repeat(43)))
        assertTrue(Pkce.isValidVerifier("a".repeat(128)))
        assertFalse(Pkce.isValidVerifier("a".repeat(129)))
        assertFalse(Pkce.isValidVerifier(""))
        // The RFC 7636 unreserved set is allowed...
        assertTrue(Pkce.isValidVerifier("Abc-._~09" + "x".repeat(40)))
        // ...everything else is not.
        for (bad in listOf('+', '/', '=', ' ', '%', 'é')) {
            assertFalse("char $bad", Pkce.isValidVerifier("a".repeat(42) + bad))
        }
    }

    @Test
    fun base64Url_UsesTheUrlAlphabetAndStripsPadding() {
        assertEquals("-_8", Pkce.base64Url(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))) // standard base64: "+/8="
        assertEquals("AQ", Pkce.base64Url(byteArrayOf(1))) // standard base64: "AQ=="
        assertEquals("", Pkce.base64Url(ByteArray(0)))
    }

    @Test
    fun generatedVerifierAndState_HaveTheCSharpShapes() {
        val v = Pkce.createVerifier()
        assertEquals(64, v.length) // 48 random bytes, base64url without padding
        assertTrue(Pkce.isValidVerifier(v))
        assertEquals(32, Pkce.createState().length) // 24 random bytes
    }

    // ---- UriEscape ----

    @Test
    fun escapeDataString_LeavesUnreservedAndEscapesEverythingElseAsUppercaseUtf8Hex() {
        assertEquals("abcXYZ019-_.~", UriEscape.escapeDataString("abcXYZ019-_.~"))
        assertEquals("", UriEscape.escapeDataString(""))
        assertEquals("%20", UriEscape.escapeDataString(" "))
        assertEquals("%2B", UriEscape.escapeDataString("+"))
        assertEquals("%2F%3A%3F%26%3D", UriEscape.escapeDataString("/:?&="))
        assertEquals("%C3%A9", UriEscape.escapeDataString("é"))
        assertEquals("a%20b", UriEscape.escapeDataString("a b"))
    }

    @Test
    fun unescapeDataString_DecodesPercentSequences_AndLeavesTheRestAlone() {
        assertEquals("plain", UriEscape.unescapeDataString("plain"))
        assertEquals("/", UriEscape.unescapeDataString("%2F"))
        assertEquals("é", UriEscape.unescapeDataString("%C3%A9"))
        assertEquals("a+b", UriEscape.unescapeDataString("a+b")) // '+' is a literal plus, as in .NET
        assertEquals("100%", UriEscape.unescapeDataString("100%25"))
        assertEquals("%zz", UriEscape.unescapeDataString("%zz")) // not hex: left as-is
        assertEquals("%4", UriEscape.unescapeDataString("%4")) // truncated sequence: left as-is
        assertEquals("%", UriEscape.unescapeDataString("%"))
        assertEquals("A", UriEscape.unescapeDataString("%41"))
    }

    // ---- RedirectUriParser (LoopbackRedirect.ParseQuery + the HandleAsync decision table) ----

    @Test
    fun parseQuery_FirstValueWins_PlusMeansSpace_KeysAndValuesAreDecoded() {
        assertEquals(mapOf("a" to "1", "b" to "2"), RedirectUriParser.parseQuery("?a=1&b=2"))
        assertEquals(mapOf("a" to "1"), RedirectUriParser.parseQuery("a=1&a=2"))
        assertEquals(mapOf("a" to ""), RedirectUriParser.parseQuery("a"))
        assertEquals(mapOf("a" to ""), RedirectUriParser.parseQuery("a="))
        assertEquals(mapOf("a" to "x y"), RedirectUriParser.parseQuery("a=x+y"))
        assertEquals(mapOf("a" to "/"), RedirectUriParser.parseQuery("a=%2F"))
        assertEquals(mapOf("a b" to "1"), RedirectUriParser.parseQuery("a%20b=1"))
        assertEquals(mapOf("a" to "b=c"), RedirectUriParser.parseQuery("a=b=c"))
        assertEquals(mapOf("a" to "1", "b" to "2"), RedirectUriParser.parseQuery("a=1&&b=2"))
        assertEquals(emptyMap<String, String>(), RedirectUriParser.parseQuery(""))
    }

    @Test
    fun evaluate_TheCallbackDecisionTable() {
        val state = "state-1"
        assertEquals(
            RedirectOutcome.Code("CODE"),
            RedirectUriParser.evaluate("https://app.example.test/callback?s=$state&code=CODE", state),
        )
        // A trailing slash on the path is trimmed, as in the C#.
        assertEquals(
            RedirectOutcome.Code("CODE"),
            RedirectUriParser.evaluate("https://app.example.test/callback/?s=$state&code=CODE", state),
        )
        // Wrong path / wrong state / unparseable: not ours.
        assertEquals(RedirectOutcome.NotOurs, RedirectUriParser.evaluate("https://app.example.test/other?s=$state&code=CODE", state))
        assertEquals(RedirectOutcome.NotOurs, RedirectUriParser.evaluate("https://app.example.test/callback?s=other&code=CODE", state))
        assertEquals(RedirectOutcome.NotOurs, RedirectUriParser.evaluate("https://app.example.test/callback?code=CODE", state))
        assertEquals(RedirectOutcome.NotOurs, RedirectUriParser.evaluate(":: not a uri ::", state))
        // An error aborts; error_description wins over error.
        assertEquals(
            RedirectOutcome.Error("User cancelled"),
            RedirectUriParser.evaluate("https://app.example.test/callback?s=$state&error=access_denied&error_description=User%20cancelled", state),
        )
        assertEquals(
            RedirectOutcome.Error("access_denied"),
            RedirectUriParser.evaluate("https://app.example.test/callback?s=$state&error=access_denied", state),
        )
        // Our callback without a code: missing (the wait continues in the C#).
        assertEquals(RedirectOutcome.MissingCode, RedirectUriParser.evaluate("https://app.example.test/callback?s=$state", state))
        assertEquals(RedirectOutcome.MissingCode, RedirectUriParser.evaluate("https://app.example.test/callback?s=$state&code=", state))
        // An EMPTY error_description does not shadow a real error... it does: the C# takes
        // error_description ?? error, and "" is not null — but "" is then treated as no error,
        // so a present code still wins.
        assertEquals(
            RedirectOutcome.Code("CODE"),
            RedirectUriParser.evaluate("https://app.example.test/callback?s=$state&error_description=&error=access_denied&code=CODE", state),
        )
    }

    @Test
    fun outcomes_CarryTheBrowserPageKeysOfTheCSharp() {
        assertEquals("browser_signed_in", RedirectOutcome.Code("x").browserMessageKey)
        assertEquals("browser_sign_in_failed", RedirectOutcome.Error("x").browserMessageKey)
        assertEquals("browser_missing_code", RedirectOutcome.MissingCode.browserMessageKey)
        assertEquals(null, RedirectOutcome.NotOurs.browserMessageKey)
    }

    // ---- CloudOptions ----

    @Test
    fun authReady_RequiresAnHttpsOrLoopbackSupabaseUrlAndAKey() {
        fun ready(url: String, key: String): Boolean =
            CloudOptions().apply { supabaseUrl = url; supabaseAnonKey = key }.authReady
        assertTrue(ready("https://proj.supabase.test", "k"))
        assertTrue(ready("https://proj.supabase.test/", "k"))
        assertFalse(ready("http://proj.supabase.test", "k"))
        assertTrue(ready("http://localhost:54321", "k"))
        assertTrue(ready("http://127.0.0.1:54321", "k"))
        // http://[::1]:54321 is loopback in .NET (Uri.IsLoopback); the port strips the
        // brackets java.net.URI.getHost() keeps (DC-200, fixed in P7 fix round 3).
        assertTrue(ready("http://[::1]:54321", "k"))
        assertFalse(ready("https://proj.supabase.test", ""))
        assertFalse(ready("https://proj.supabase.test", "   "))
        assertFalse(ready("", "k"))
        assertFalse(ready("not a url", "k"))
        assertFalse(ready("example.com", "k")) // relative: not absolute
    }

    @Test
    fun isConfigured_RequiresAnHttpsOrLoopbackApiUrl() {
        fun configured(url: String): Boolean = CloudOptions().apply { apiBaseUrl = url }.isConfigured
        assertTrue(configured("https://api.example.test"))
        assertFalse(configured("http://api.example.test"))
        assertTrue(configured("http://localhost:5149"))
        assertTrue(configured("http://127.0.0.1:5149"))
        assertFalse(configured(""))
        assertFalse(configured("api.example.test"))
    }

    @Test
    fun fromJson_ReadsTheCloudJsonShape_AndDefaultsWhatIsAbsent() {
        val full = CloudOptions.fromJson(
            """{"apiBaseUrl":"https://a.test","supabaseUrl":"https://s.test","supabaseAnonKey":"key",""" +
                """"oAuthProviders":["google","github"],"emailPassword":false,""" +
                """"emailConfirmedRedirectUrl":"https://a.test/confirmed","clientVersion":"9.9.9",""" +
                """"updateUrl":"https://updates.test/feed","someFutureKey":true}""",
        )
        assertEquals("https://a.test", full.apiBaseUrl)
        assertEquals("https://s.test", full.supabaseUrl)
        assertEquals("key", full.supabaseAnonKey)
        assertEquals(listOf("google", "github"), full.oAuthProviders)
        assertFalse(full.emailPassword)
        assertEquals("https://a.test/confirmed", full.emailConfirmedRedirectUrl)
        assertEquals("9.9.9", full.clientVersion)
        assertEquals("https://updates.test/feed", full.updateUrl)

        val empty = CloudOptions.fromJson("{}")
        assertEquals("", empty.apiBaseUrl)
        assertEquals("2.0.0", empty.clientVersion) // the C#'s default
        assertTrue(empty.emailPassword)
        assertEquals(emptyList<String>(), empty.oAuthProviders)
        assertEquals(null, empty.emailConfirmedRedirectUrl)

        try {
            CloudOptions.fromJson("{ not json")
            fail("expected a serialization failure")
        } catch (e: kotlinx.serialization.SerializationException) {
            // LoadOptions' caller catches this and keeps the defaults, as on Windows.
        }
    }

    // ---- InstallationIdentity ----

    @Test
    fun installationIdValidity_LengthWindowAndAlphabet() {
        assertFalse(InstallationIdentity.isValid("a".repeat(11)))
        assertTrue(InstallationIdentity.isValid("a".repeat(12)))
        assertTrue(InstallationIdentity.isValid("a".repeat(64)))
        assertFalse(InstallationIdentity.isValid("a".repeat(65)))
        assertFalse(InstallationIdentity.isValid(""))
        assertTrue(InstallationIdentity.isValid("inst-abc12345"))
        assertFalse(InstallationIdentity.isValid("inst-abc_12345")) // '_' is not in the C#'s alphabet
        assertFalse(InstallationIdentity.isValid("inst-abc 12345"))
        assertFalse(InstallationIdentity.isValid("inst-abcé12345"))
    }

    @Test
    fun installationId_ValidStoredIdIsReused_InvalidContentIsRegenerated() {
        val dir = Files.createTempDirectory("ia-inst-").toFile()
        try {
            val path = File(dir, "sub/id.txt").absolutePath
            File(dir, "sub").mkdirs()
            File(path).writeText("  inst-stored123\n") // surrounding whitespace is trimmed on read
            assertEquals("inst-stored123", InstallationIdentity.getOrCreate(path))
            assertEquals("  inst-stored123\n", File(path).readText()) // untouched

            File(path).writeText("not a valid id!")
            val created = InstallationIdentity.getOrCreate(path)
            assertTrue(created.startsWith("inst-"))
            assertEquals(37, created.length) // "inst-" + 32 lowercase hex digits
            assertTrue(created.drop(5).all { it in '0'..'9' || it in 'a'..'f' })
            assertEquals(created, File(path).readText())
            assertEquals(created, InstallationIdentity.getOrCreate(path))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- TimeProvider ----

    @Test
    fun systemTimeProvider_ReportsUtcNow_AndItsDefaultDelaySuspends() {
        val before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5)
        val now = TimeProvider.System.utcNow()
        val after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5)
        assertEquals(ZoneOffset.UTC, now.offset)
        assertTrue(now.isAfter(before) && now.isBefore(after))
        runBlocking {
            withTimeout(5.seconds) { TimeProvider.System.delay(20.milliseconds) }
        }
    }
}
