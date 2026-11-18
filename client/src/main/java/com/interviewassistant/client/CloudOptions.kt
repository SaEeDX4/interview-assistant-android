package com.interviewassistant.client

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mirror of `InterviewAssistant.Client/CloudOptions.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port, plus the `cloud.json` deserialization the Windows app performs in
 * `CloudService.LoadOptions` with `JsonSerializerDefaults.Web` options (camelCase, case-insensitive) —
 * see [fromJson]. The file search itself (bundled asset first, developer override) stays with the app
 * composition root, exactly as `LoadOptions` stays outside this class on Windows.
 */

/**
 * Public, non-secret cloud settings. Only [apiBaseUrl] ships with the app; the Supabase URL and publishable
 * key come from the service's public /api/v1/config (cached locally). No provider secret is ever part of the app.
 */
@Serializable
class CloudOptions {
    @SerialName("apiBaseUrl")
    var apiBaseUrl: String = ""

    @SerialName("supabaseUrl")
    var supabaseUrl: String = ""

    @SerialName("supabaseAnonKey")
    var supabaseAnonKey: String = ""

    /** OAuth providers enabled in the Supabase project (e.g. "google"); empty = e-mail sign-in only. */
    @SerialName("oAuthProviders")
    var oAuthProviders: List<String> = emptyList()

    @SerialName("emailPassword")
    var emailPassword: Boolean = true

    /** Page the sign-up confirmation e-mail returns to (served by the API). */
    @SerialName("emailConfirmedRedirectUrl")
    var emailConfirmedRedirectUrl: String? = null

    @SerialName("clientVersion")
    var clientVersion: String = "2.0.0"

    /** Sign-in can be attempted (Supabase endpoint + publishable key known). */
    val authReady: Boolean
        get() {
            val s = parseAbsolute(supabaseUrl) ?: return false
            return (s.scheme == "https" || isLoopback(s)) && supabaseAnonKey.isNotBlank()
        }

    /** Velopack update feed (https URL of a static release folder or a GitHub repository URL). Empty = updates disabled. */
    @SerialName("updateUrl")
    var updateUrl: String = ""

    /** The service is configured for this build (https API endpoint, or loopback for local development). */
    val isConfigured: Boolean
        get() {
            val a = parseAbsolute(apiBaseUrl) ?: return false
            return a.scheme == "https" || isLoopback(a)
        }

    companion object {
        private val cloudJson = Json { ignoreUnknownKeys = true }

        /**
         * Parses `cloud.json` text the way `CloudService.LoadOptions` does on Windows
         * (`JsonSerializer.Deserialize<CloudOptions>(…, JsonSerializerDefaults.Web)`).
         * Throws on malformed JSON — the caller applies LoadOptions' catch-and-keep-defaults rule.
         */
        fun fromJson(text: String): CloudOptions = cloudJson.decodeFromString(serializer(), text)

        private fun parseAbsolute(value: String): URI? =
            try {
                val uri = URI(value)
                if (uri.isAbsolute) uri else null
            } catch (e: Exception) {
                null
            }

        /** `Uri.IsLoopback`: localhost, 127.0.0.0/8 or ::1. */
        private fun isLoopback(uri: URI): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return host == "localhost" || host == "::1" || host.startsWith("127.")
        }
    }
}
