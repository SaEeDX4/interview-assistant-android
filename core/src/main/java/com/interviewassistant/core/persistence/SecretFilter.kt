package com.interviewassistant.core.persistence

import com.interviewassistant.core.domain.CoreJson
import com.interviewassistant.core.ingestion.dotNetRegex
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Mirror of `InterviewAssistant.Core/Persistence/SecretFilter.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port, with the JSON-tree adaptations registered as P3-PORT-NOTES DC-11:
 * System.Text.Json.Nodes is mutable, kotlinx JsonElement is not — so [clean] returns the cleaned copy
 * plus the C#'s "changed" flag ([CleanResult]) instead of mutating in place, and the C#
 * `Clean(JsonElement)` overload (which re-parses the element's raw text with checked options and
 * throws when the document cannot be checked) is [cleanChecked]. All raw-text entry points parse with
 * [StrictJson], which enforces the C#'s parser rules (MaxDepth, duplicate property names refused)
 * that kotlinx's own parser does not offer.
 *
 * Defensive filter applied to every file the user exports (account data, profile backups, raw ZIPs): properties whose
 * NAME says they hold a password, sign-in token, provider credential, API key or other secret are removed, and string
 * VALUES that look like a credential (API keys, JWTs, bearer <redacted>, private keys) are replaced. The app does not store
 * such values in these documents today — the filter makes sure a future field or a pasted key can never leak through
 * an export. It fails closed: anything nested deeper than it inspects is removed, and a document it cannot parse is
 * not exported ([cleanChecked] throws).
 */

/** Mirror of System.IO.InvalidDataException (a BCL type the C# throws and catches by name). */
class InvalidDataException(message: String, cause: Throwable? = null) : IOException(message, cause)

object SecretFilter {
    /** Replacement for a secret-looking value found inside ordinary text. */
    const val Removed = "[removed]"

    /** Containers nested deeper than this are replaced by [Removed] (never passed through unchecked). */
    const val MaxDepth = 96

    // Matched against the property name lower-cased with every non-letter/digit removed ("refresh_token" → "refreshtoken").
    private val keyMarkers = arrayOf(
        "password", "passwd", "passphrase", "pwd", "secret", "token", "apikey", "accesskey", "privatekey", "signingkey",
        "credential", "authorization", "bearer", "cookie", "jwt", "sessionkey", "clientkey", "recoverycode", "signature",
    )

    // Matched against whole words of the name (camelCase, snake_case, kebab-case): "Auth", "x-auth", "otpCode" — but not
    // "author" or "footprint".
    private val keyWords = arrayOf("auth", "otp")

    // Unmistakable credentials: distinctive key/token formats and explicit assignments of credential names. Résumé and
    // interview prose never has these shapes, so even the profile backup (which restores text exactly) removes them.
    private const val CredentialShapes =
        "\\bsk-[A-Za-z0-9_\\-]{16,}" + // OpenAI/Anthropic-style API keys
            "|\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}" + // JSON Web Tokens (access / refresh / id tokens)
            "|\\b[Bb]earer\\s+[A-Za-z0-9._~+/\\-]{16,}=*" + // Authorization: Bearer …
            "|\\bBasic\\s+(?=[A-Za-z0-9+/]*[0-9+/])[A-Za-z0-9+/]{12,}={0,2}" + // Authorization: Basic … (base64; "Basic Spanish" is prose)
            "|\\bpdl_[A-Za-z0-9_]{12,}" + // Paddle API keys
            "|\\b(?:ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}" + // GitHub tokens
            "|\\bxox[abprs]-[A-Za-z0-9\\-]{10,}" + // Slack tokens
            "|\\bAKIA[0-9A-Z]{16}\\b" + // AWS access keys
            "|\\bAIza[0-9A-Za-z_\\-]{30,}" + // Google API keys (35 after the prefix; shortened copies too)
            "|\\bsb_(?:secret|publishable)_[A-Za-z0-9_\\-]{8,}" + // Supabase keys
            "|-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)" +
            // Names that only ever label a credential: any value, also JSON-ish text that is not valid JSON ("access_token": "…").
            "|\\b(?i:(?:access|refresh|id)[_\\-]?token|api[_\\-]?key|client[_\\-]?secret|passwd)[\"']?\\s*[:=]\\s*[\"']?[^\\s\"',;}]+" +
            // "password"/"secret" as an assignment or with a quoted value ("password=<redacted>", "secret": "…").
            "|\\b(?i:password|secret)[\"']?\\s*(?:=\\s*[\"']?[^\\s\"',;}]+|:\\s*[\"'][^\"'\\s]+[\"']?)"

    // Credentials written as a sentence. Also safe for résumé prose ("Secret: active clearance" and "Password management"
    // do not match: the value must be one word with a digit), but only used where text is not restored as-is.
    private const val CredentialSentences =
        "\\b(?i:password|secret)\\s*:\\s*(?=[^\\s\"',;}]*\\d)[^\\s\"',;}]{6,}" +
            "|\\b(?i:my\\s+password\\s+is)\\s+\\S+"

    // The C# compiles these with a 2 s match timeout and fails closed on a timeout; the JVM regex engine
    // has no match timeout, so the timeout branches of the C# have no equivalent here (P3-PORT-NOTES).
    private val secretValue: Regex = dotNetRegex(CredentialShapes + "|" + CredentialSentences)
    private val credentialValue: Regex = dotNetRegex(CredentialShapes)

    /** True when a property with this name must never appear in an export. */
    fun isSecretKey(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        // The whole name is checked (no truncation: a long prefix must not hide a marker at the end).
        val norm = StringBuilder(name.length)
        for (c in name)
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') norm.append(c.lowercaseChar())
        val n = norm.toString()
        for (m in keyMarkers)
            if (n.contains(m)) return true
        if (n.endsWith("key")) return true // openAiKey, licenseKey, providerKey (not "keywords")
        for (word in words(name))
            if (keyWords.indexOf(word) >= 0) return true
        return false
    }

    /** The name split into lower-case words at separators and lower→upper case changes ("xAuthToken" → x, auth, token). */
    private fun words(name: String): List<String> {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var prev = '\u0000'
        for (c in name) {
            val boundary = !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') ||
                (c.isUpperCase() && (prev.isLowerCase() || prev.isDigit()))
            if (boundary && word.isNotEmpty()) {
                out.add(word.toString())
                word.clear()
            }
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') word.append(c.lowercaseChar())
            prev = c
        }
        if (word.isNotEmpty()) out.add(word.toString())
        return out
    }

    /** True when the text contains something shaped like a credential. */
    fun looksSecret(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return secretValue.containsMatchIn(text)
    }

    /** The text with every credential-shaped part replaced by [Removed]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return secretValue.replace(text, Removed)
    }

    /** The outcome of [clean]: the cleaned tree plus whether anything was removed (the C# mutates in place). */
    data class CleanResult(val node: JsonElement?, val changed: Boolean)

    /**
     * Cleans a JSON tree (also JSON documents stored inside string values). [CleanResult.changed] is true if
     * anything was removed.
     *
     * @param keepProse True for the profile backup, which restores the user's text exactly as written: values are
     * rewritten only for unmistakable credentials (key formats, tokens, "password=<redacted>"), never for sentence-like text.
     * False (readable and raw exports) also replaces credentials written as a sentence.
     */
    fun clean(node: JsonElement?, depth: Int = 0, keepProse: Boolean = false): CleanResult {
        if (node == null || node is JsonNull) return CleanResult(node, false)
        var changed = false
        return when (node) {
            is JsonObject -> {
                val out = LinkedHashMap<String, JsonElement>(node.size)
                for ((key, child) in node) {
                    if (isSecretKey(key)) {
                        changed = true
                        continue
                    }
                    if (child is JsonPrimitive && child.isString) {
                        val (cleaned, c) = cleanString(child.content, depth, keepProse)
                        if (c) {
                            out[key] = JsonPrimitive(cleaned)
                            changed = true
                        } else out[key] = child
                    } else if ((child is JsonObject || child is JsonArray) && depth + 1 > MaxDepth) {
                        out[key] = JsonPrimitive(Removed) // fail closed
                        changed = true
                    } else {
                        val r = clean(child, depth + 1, keepProse)
                        out[key] = r.node ?: JsonNull
                        changed = changed || r.changed
                    }
                }
                CleanResult(JsonObject(out), changed)
            }
            is JsonArray -> {
                val out = ArrayList<JsonElement>(node.size)
                for (item in node) {
                    if (item is JsonPrimitive && item.isString) {
                        val (cleaned, c) = cleanString(item.content, depth, keepProse)
                        if (c) {
                            out.add(JsonPrimitive(cleaned))
                            changed = true
                        } else out.add(item)
                    } else if ((item is JsonObject || item is JsonArray) && depth + 1 > MaxDepth) {
                        out.add(JsonPrimitive(Removed)) // fail closed
                        changed = true
                    } else {
                        val r = clean(item, depth + 1, keepProse)
                        out.add(r.node ?: JsonNull)
                        changed = changed || r.changed
                    }
                }
                CleanResult(JsonArray(out), changed)
            }
            else -> CleanResult(node, false)
        }
    }

    private val indented: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** A string value: a nested JSON document (stored reports) is cleaned as JSON, other text is redacted. */
    private fun cleanString(s: String, depth: Int, keepProse: Boolean): Pair<String, Boolean> {
        var changed = false
        val t = s.trimStart()
        if (t.length > 1 && (t[0] == '{' || t[0] == '[')) {
            val inner: JsonElement? = try {
                StrictJson.parse(s, StrictJson.ParseMaxDepth)
            } catch (e: StrictJson.SyntaxException) {
                null // not JSON: treat as text
            } catch (e: StrictJson.DuplicateKeyException) {
                // A duplicate property name: cannot be checked, so not exported (fail closed).
                return Pair(Removed, true)
            }
            if (inner != null) {
                if (depth + 1 > MaxDepth) return Pair(Removed, true) // fail closed
                val r = clean(inner, depth + 1, keepProse)
                if (!r.changed) return Pair(s, false)
                changed = true
                return Pair(indented.encodeToString(JsonElement.serializer(), r.node ?: JsonNull), true)
            }
        }
        val pattern = if (keepProse) credentialValue else secretValue
        if (!pattern.containsMatchIn(s)) return Pair(s, changed)
        changed = true
        return Pair(pattern.replace(s, Removed), true)
    }

    /**
     * Serializes [value] to JSON with secrets removed.
     *
     * @param keepProse See [clean].
     */
    fun <T> toJson(value: T, serializer: KSerializer<T>, indented: Boolean = true, keepProse: Boolean = false): String {
        val node = CoreJson.instance.encodeToJsonElement(serializer, value)
        val cleaned = clean(node, keepProse = keepProse).node
        return when {
            cleaned == null -> "null"
            indented -> this.indented.encodeToString(JsonElement.serializer(), cleaned)
            else -> cleaned.toString()
        }
    }

    /** Serializes [value] to JSON with secrets removed (serializer resolved from the reified type). */
    inline fun <reified T> toJson(value: T, indented: Boolean = true, keepProse: Boolean = false): String =
        toJson(value, serializer<T>(), indented, keepProse)

    /**
     * A cleaned copy of a JSON element (account export from the service) — the C# `Clean(JsonElement)` overload.
     *
     * @throws InvalidDataException The document cannot be checked (too deep, duplicate property names): it
     * must not be exported at all.
     */
    fun cleanChecked(element: JsonElement): JsonElement {
        try {
            val node = StrictJson.parse(element.toString(), StrictJson.ParseMaxDepth)
            val cleaned = clean(node).node
            return StrictJson.parse(cleaned?.toString() ?: "null", StrictJson.ParseMaxDepth)
        } catch (ex: Exception) {
            if (ex is StrictJson.SyntaxException || ex is IllegalArgumentException || ex is IllegalStateException) {
                throw InvalidDataException("The export could not be checked for secrets (" + ex.javaClass.simpleName + ").", ex)
            }
            throw ex
        }
    }
}

/**
 * A strict JSON parser producing kotlinx [JsonElement] trees with the System.Text.Json rules the C#
 * export paths rely on, which kotlinx's own parser does not offer: a maximum depth and duplicate
 * property names refused. (Comments and trailing commas are refused too, as with the C#'s defaults.)
 * Number literals are preserved verbatim. Used by [SecretFilter] and by `ProfileBundle`'s untrusted reads.
 */
internal object StrictJson {
    /** The C#'s `JsonDocumentOptions.MaxDepth` for export documents (SecretFilter.ParseOptions). */
    const val ParseMaxDepth = 128

    class SyntaxException(message: String) : Exception(message)
    class DuplicateKeyException(message: String) : IllegalArgumentException(message)

    fun parse(text: String, maxDepth: Int): JsonElement {
        val p = Parser(text, maxDepth)
        p.skipWhitespace()
        val v = p.parseValue(0)
        p.skipWhitespace()
        if (!p.atEnd()) throw SyntaxException("Trailing content after the JSON value.")
        return v
    }

    private class Parser(private val text: String, private val maxDepth: Int) {
        private var i = 0

        fun atEnd(): Boolean = i >= text.length

        fun skipWhitespace() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
        }

        fun parseValue(depth: Int): JsonElement {
            if (i >= text.length) throw SyntaxException("Unexpected end of JSON.")
            return when (text[i]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonPrimitive(parseString())
                't' -> {
                    expect("true")
                    JsonPrimitive(true)
                }
                'f' -> {
                    expect("false")
                    JsonPrimitive(false)
                }
                'n' -> {
                    expect("null")
                    JsonNull
                }
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            if (depth + 1 > maxDepth) throw SyntaxException("JSON nested deeper than $maxDepth.")
            i++ // '{'
            val map = LinkedHashMap<String, JsonElement>()
            skipWhitespace()
            if (i < text.length && text[i] == '}') {
                i++
                return JsonObject(map)
            }
            while (true) {
                skipWhitespace()
                if (i >= text.length || text[i] != '"') throw SyntaxException("Expected a property name.")
                val key = parseString()
                if (map.containsKey(key)) throw DuplicateKeyException("Duplicate property name: $key")
                skipWhitespace()
                if (i >= text.length || text[i] != ':') throw SyntaxException("Expected ':' after a property name.")
                i++
                skipWhitespace()
                map[key] = parseValue(depth + 1)
                skipWhitespace()
                if (i >= text.length) throw SyntaxException("Unterminated object.")
                when (text[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(map)
                    }
                    else -> throw SyntaxException("Expected ',' or '}' in object.")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            if (depth + 1 > maxDepth) throw SyntaxException("JSON nested deeper than $maxDepth.")
            i++ // '['
            val list = mutableListOf<JsonElement>()
            skipWhitespace()
            if (i < text.length && text[i] == ']') {
                i++
                return JsonArray(list)
            }
            while (true) {
                skipWhitespace()
                list.add(parseValue(depth + 1))
                skipWhitespace()
                if (i >= text.length) throw SyntaxException("Unterminated array.")
                when (text[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(list)
                    }
                    else -> throw SyntaxException("Expected ',' or ']' in array.")
                }
            }
        }

        private fun parseString(): String {
            // text[i] == '"'
            i++
            val sb = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        if (i + 1 >= text.length) throw SyntaxException("Unterminated escape.")
                        when (text[i + 1]) {
                            '"' -> { sb.append('"'); i += 2 }
                            '\\' -> { sb.append('\\'); i += 2 }
                            '/' -> { sb.append('/'); i += 2 }
                            'b' -> { sb.append('\b'); i += 2 }
                            'f' -> { sb.append('\u000C'); i += 2 }
                            'n' -> { sb.append('\n'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            'u' -> {
                                if (i + 5 >= text.length) throw SyntaxException("Bad \\u escape.")
                                val hex = text.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16) ?: throw SyntaxException("Bad \\u escape.")
                                sb.append(code.toChar())
                                i += 6
                            }
                            else -> throw SyntaxException("Bad escape character.")
                        }
                    }
                    c < ' ' -> throw SyntaxException("Unescaped control character in string.")
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            throw SyntaxException("Unterminated string.")
        }

        private fun parseNumber(): JsonElement {
            val start = i
            if (i < text.length && text[i] == '-') i++
            while (i < text.length && text[i].isDigit()) i++
            if (i < text.length && text[i] == '.') {
                i++
                while (i < text.length && text[i].isDigit()) i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                i++
                if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                while (i < text.length && text[i].isDigit()) i++
            }
            val token = text.substring(start, i)
            if (token.isEmpty() || token == "-") throw SyntaxException("Invalid JSON value.")
            // Parsed through kotlinx so the literal is validated and preserved verbatim.
            return try {
                Json.parseToJsonElement(token)
            } catch (e: Exception) {
                throw SyntaxException("Invalid number: $token")
            }
        }

        private fun expect(literal: String) {
            if (!text.regionMatches(i, literal, 0, literal.length)) throw SyntaxException("Invalid literal.")
            i += literal.length
        }
    }
}
