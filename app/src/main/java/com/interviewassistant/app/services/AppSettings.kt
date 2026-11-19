package com.interviewassistant.app.services

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Mirror of `InterviewAssistant.App/Services/AppSettings.cs`. */

enum class FontPreset { Compact, Normal, Large, ExtraLarge }

/**
 * The settings file's `FontPreset`: System.Text.Json with the C#'s plain options writes an enum
 * as its ordinal number (no string-enum converter is registered in `AppSettings.cs`), so the
 * number is the wire form on both platforms. An out-of-range ordinal (a newer build's preset)
 * reads as [FontPreset.ExtraLarge] — the C#'s derived-size switches fall back to the same arm
 * (P4-PORT-NOTES DC-76).
 */
object FontPresetSerializer : KSerializer<FontPreset> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FontPreset", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: FontPreset) = encoder.encodeInt(value.ordinal)
    override fun deserialize(decoder: Decoder): FontPreset =
        FontPreset.entries.getOrElse(decoder.decodeInt()) { FontPreset.ExtraLarge }
}

/** `System.Guid`: the lowercase hyphenated "D" form — exactly what `java.util.UUID.toString()` writes. */
object GuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("System.Guid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID =
        try {
            UUID.fromString(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Invalid Guid value", e)
        }
}

object GuidNullableSerializer : KSerializer<UUID?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("System.Guid?", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID? =
        if (decoder.decodeNotNullMark()) GuidSerializer.deserialize(decoder) else decoder.decodeNull()
}

/** User preferences persisted as JSON in the roaming folder. Never contains secrets (the API key lives in SecretStore). */
@Serializable
class AppSettings(
    @SerialName("PlaybackDeviceId") var playbackDeviceId: String? = null, // null = follow the system default playback device
    @SerialName("SettingsVersion") var settingsVersion: Int = 3,
    @SerialName("AnswerModel") var answerModel: String = "gpt-5.4-mini",
    @SerialName("TranscriptionModel") var transcriptionModel: String = "gpt-4o-transcribe",
    @SerialName("RealtimeProtocol") var realtimeProtocol: String = "auto",
    @SerialName("FontPreset") @Serializable(with = FontPresetSerializer::class) var fontPreset: FontPreset = FontPreset.Large,
    @SerialName("WindowOpacity") var windowOpacity: Double = 0.97,
    @SerialName("VadSensitivity") var vadSensitivity: Double = 1.0, // turn-settle multiplier (higher = waits longer)
    @SerialName("UseFastCache") var useFastCache: Boolean = true,
    @SerialName("CompactMode") var compactMode: Boolean = false,
    @SerialName("AlwaysOnTop") var alwaysOnTop: Boolean = true,
    /** Legacy (kept so older settings files load): no UI toggle since rc.5 — diagnostics live in Help & about. */
    @SerialName("ShowDiagnostics") var showDiagnostics: Boolean = false,
    @SerialName("SaveSessionTranscript") var saveSessionTranscript: Boolean = false, // privacy: off by default
    @SerialName("HotkeyToggleWindow") var hotkeyToggleWindow: String = "Ctrl+Alt+Space",
    @SerialName("HotkeyStartPause") var hotkeyStartPause: String = "Ctrl+Alt+L",
    @SerialName("HotkeyManualInput") var hotkeyManualInput: String = "Ctrl+Alt+Q",
    @SerialName("HotkeyCoachToggle") var hotkeyCoachToggle: String = "Ctrl+Alt+C",
    @SerialName("HotkeyResetAdaptive") var hotkeyResetAdaptive: String = "Ctrl+Alt+R",
    /** "cloud" = Interview Assistant account (server-side AI); "developer" = own OpenAI key (prototype path). */
    @SerialName("Mode") var mode: String = "cloud",
    /** UI language (one of the 10 launch languages); null = follow the system display language. */
    @SerialName("UiLanguage") var uiLanguage: String? = null,
    /** "same" = answer in the interviewer's detected language, or a fixed language code. */
    @SerialName("AnswerLanguage") var answerLanguage: String = "same",
    /** Report language; null = UI language. */
    @SerialName("ReportLanguage") var reportLanguage: String? = null,
    @SerialName("AuthorizedUseAcknowledged") var authorizedUseAcknowledged: Boolean = false,
    /** Shrinks answer text (down to a readable minimum) so all bullets fit without scrolling. */
    @SerialName("AutoFitAnswer") var autoFitAnswer: Boolean = true,
    @SerialName("StartInCoachMode") var startInCoachMode: Boolean = false,
    @SerialName("CloudDeviceId") @Serializable(with = GuidNullableSerializer::class) var cloudDeviceId: UUID? = null,
    /** Update channel: "stable" (default) or "beta". */
    @SerialName("UpdateChannel") var updateChannel: String = "stable",
    @SerialName("Left") var left: Double? = null,
    @SerialName("Top") var top: Double? = null,
    @SerialName("Width") var width: Double = 720.0,
    @SerialName("Height") var height: Double = 560.0,
    @SerialName("FirstRunCompleted") var firstRunCompleted: Boolean = false,
    /** The Live Assistant's Quick help has opened by itself once (first use); afterwards only the ? button opens it. */
    @SerialName("LiveIntroDismissed") var liveIntroDismissed: Boolean = false,
    /** Interview Focus (rc.5): the preset chosen last (TopCentre, TopLeft, TopRight; null = never used). */
    @SerialName("InterviewFocusPreset") var interviewFocusPreset: String? = null,
    /** The "Keep your eye line natural" explanation was shown (first use of Interview Focus). */
    @SerialName("InterviewFocusIntroSeen") var interviewFocusIntroSeen: Boolean = false,
    /** The Live window placement before Interview Focus moved it ("Restore previous size"); null when not in focus. */
    @SerialName("FocusRestoreLeft") var focusRestoreLeft: Double? = null,
    @SerialName("FocusRestoreTop") var focusRestoreTop: Double? = null,
    @SerialName("FocusRestoreWidth") var focusRestoreWidth: Double? = null,
    @SerialName("FocusRestoreHeight") var focusRestoreHeight: Double? = null,
    // Home window placement (the live window uses Left/Top/Width/Height above).
    @SerialName("HomeLeft") var homeLeft: Double? = null,
    @SerialName("HomeTop") var homeTop: Double? = null,
    @SerialName("HomeWidth") var homeWidth: Double = 980.0,
    @SerialName("HomeHeight") var homeHeight: Double = 700.0,
    @SerialName("HomeMaximized") var homeMaximized: Boolean = false,
    @SerialName("ActiveProfileId") var activeProfileId: String? = null,
    @SerialName("ActiveTargetId") var activeTargetId: String? = null,
    // Approximate unit prices for the cost estimate shown in diagnostics (editable; estimates only).
    @SerialName("PriceTranscribePerMinute") var priceTranscribePerMinute: Double = 0.017,
    @SerialName("PriceInputPerMTok") var priceInputPerMTok: Double = 0.75,
    @SerialName("PriceOutputPerMTok") var priceOutputPerMTok: Double = 4.50,
) {
    // System.Text.Json also writes the two get-only derived properties at the end of settings.json
    // (it serializes every public getter; on read they are ignored, as they are here via
    // ignoreUnknownKeys). save() appends them to the rendered text — see renderLikeSystemTextJson.
    @Transient
    val answerFontSize: Double
        get() = when (fontPreset) {
            FontPreset.Compact -> 16.0
            FontPreset.Normal -> 19.0
            FontPreset.Large -> 22.0
            else -> 26.0
        }

    @Transient
    val questionFontSize: Double
        get() = when (fontPreset) {
            FontPreset.Compact -> 14.0
            FontPreset.Normal -> 16.0
            FontPreset.Large -> 17.0
            else -> 19.0
        }

    /** Writes the settings atomically. False = not written (disk full, read-only profile folder): the caller says so. */
    fun save(): Boolean {
        try {
            val tmp = filePath + ".tmp"
            File(tmp).writeText(renderLikeSystemTextJson(this), Charsets.UTF_8)
            Files.move(File(tmp).toPath(), File(filePath).toPath(), StandardCopyOption.REPLACE_EXISTING) // atomic replace: no half-written settings on crash
            return true
        } catch (ex: Exception) {
            if (ex is IOException || ex is SecurityException) {
                AppLog.warn("Could not save settings: " + AppLog.describe(ex))
                return false
            }
            throw ex
        }
    }

    companion object {
        var overridePath: String? = null // self-test isolation

        private val filePath: String get() = overridePath ?: File(AppPaths.roaming, "settings.json").path

        /** Where the settings are stored (Help → Diagnostics health check reads it back). */
        val currentPath: String get() = filePath

        // The C#'s JsonSerializerOptions { WriteIndented = true } (defaults otherwise):
        // every property written (defaults and nulls included), unknown properties ignored on read.
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun load(): AppSettings {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val s = json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))
                    if (s.settingsVersion < 2) {
                        // v1 defaults -> current recommended models (only if the user never changed them)
                        if (s.answerModel == "gpt-4.1-mini") s.answerModel = "gpt-5.4-mini"
                        s.settingsVersion = 2
                    }
                    if (s.settingsVersion < 3) {
                        // v2 shipped a transcription model id that OpenAI does not offer.
                        if (s.transcriptionModel == "gpt-live-transcribe") s.transcriptionModel = "gpt-4o-transcribe"
                        s.settingsVersion = 3
                    }
                    return s
                }
            } catch (ex: Exception) {
                if (ex is IOException || ex is SerializationException || ex is SecurityException) {
                    AppLog.warn("Settings unreadable, using defaults: " + AppLog.describe(ex))
                } else {
                    throw ex
                }
            }
            return AppSettings()
        }

        /**
         * Renders exactly what the C#'s `JsonSerializer.Serialize(this, WriteIndented)` produces:
         * the two derived get-only properties appended last, and integral doubles in
         * System.Text.Json's spelling ("720", where kotlinx writes "720.0"). Both forms parse
         * identically on both platforms; the spelling keeps a settings.json written by either
         * app byte-comparable (P4-PORT-NOTES DC-76).
         */
        private fun renderLikeSystemTextJson(s: AppSettings): String {
            val body = json.encodeToString(serializer(), s)
            val echoes = ",\n  \"AnswerFontSize\": ${stjDouble(s.answerFontSize)}" +
                ",\n  \"QuestionFontSize\": ${stjDouble(s.questionFontSize)}\n}"
            return stjDoubleSpellings(body.dropLast(1).trimEnd() + echoes)
        }

        /** System.Text.Json's double spelling: shortest round-trip form; integral values carry no fraction. */
        private fun stjDouble(v: Double): String {
            if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e16) return v.toLong().toString()
            return v.toString()
        }

        /** Rewrites integral-double literals outside string literals to the System.Text.Json spelling. */
        private fun stjDoubleSpellings(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            var inString = false
            var escaped = false
            while (i < text.length) {
                val c = text[i]
                when {
                    inString -> {
                        out.append(c)
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') inString = false
                        i++
                    }
                    c == '"' -> {
                        inString = true
                        out.append(c)
                        i++
                    }
                    c == '-' || c.isDigit() -> {
                        var j = i
                        while (j < text.length && (text[j].isDigit() || text[j] in ".eE+-")) j++
                        val token = text.substring(i, j)
                        val v = if (token.any { it == '.' || it == 'e' || it == 'E' }) token.toDoubleOrNull() else null
                        out.append(if (v != null) stjDouble(v) else token)
                        i = j
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
