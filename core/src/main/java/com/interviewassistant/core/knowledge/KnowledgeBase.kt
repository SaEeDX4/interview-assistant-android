package com.interviewassistant.core.knowledge

import com.interviewassistant.core.ingestion.dotNetRegex
import java.io.File
import java.io.FileNotFoundException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Mirror of `InterviewAssistant.Core/Knowledge/KnowledgeBase.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * The C# loads with `JsonSerializerOptions { ReadCommentHandling = Skip, AllowTrailingCommas = true }`
 * over System.Text.Json defaults (unknown properties ignored, names from `[JsonPropertyName]`).
 * The kotlinx [jsonOptions] mirror ignores unknown keys; comment / trailing-comma tolerance has no
 * kotlinx equivalent and is registered as a divergence candidate in P3-PORT-NOTES.md (Package B).
 * The C#'s `DirectoryNotFoundException` (an `IOException`) is mirrored by [FileNotFoundException].
 */

/** A titled paragraph from a markdown playbook/brief, used for compact retrieval. */
@Serializable
data class KnowledgeSnippet(
    @SerialName("Source") val source: String,
    @SerialName("Heading") val heading: String,
    @SerialName("Text") val text: String,
)

/** Everything loaded from the knowledge/ folder. Immutable after load. */
class KnowledgeBase(
    val profile: CandidateProfile,
    val stories: List<CandidateStory>,
    val questions: List<BankQuestion>,
    val snippets: List<KnowledgeSnippet>,
    val systemPromptTemplate: String,
    val context: TargetContext = TargetContext(),
    val directory: String = "",
) {
    companion object {
        private val jsonOptions = Json { ignoreUnknownKeys = true }

        val snippetFiles: List<String> = listOf(
            "product_owner_playbook.md", "crypto_fintech_playbook.md", "company_brief.md",
            "product_brief.md", "role_brief.md", "whitepaper_notes.md",
        )

        fun load(directory: String): KnowledgeBase {
            if (!File(directory).isDirectory)
                throw FileNotFoundException("Knowledge directory not found: $directory")

            fun <T> read(file: String, decode: (String) -> T): T =
                decode(File(directory, file).readText(Charsets.UTF_8))

            val snippets = mutableListOf<KnowledgeSnippet>()
            for (file in snippetFiles) {
                val path = File(directory, file)
                if (path.exists()) snippets.addAll(splitMarkdown(file, path.readText(Charsets.UTF_8)))
            }
            val notesDir = File(directory, "notes")
            if (notesDir.isDirectory) {
                for (path in notesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray()) {
                    snippets.addAll(splitMarkdown(path.name, path.readText(Charsets.UTF_8)))
                }
            }

            val profile = read("candidate_profile.json") { jsonOptions.decodeFromString<CandidateProfile>(it) }
            return KnowledgeBase(
                profile = profile,
                stories = read("candidate_stories.json") { jsonOptions.decodeFromString<StoriesFile>(it) }.stories,
                questions = read("question_bank.json") { jsonOptions.decodeFromString<QuestionBankFile>(it) }.questions,
                snippets = snippets,
                systemPromptTemplate = PromptTemplates.LiveAnswer,
                context = loadContext(directory, profile),
                directory = directory,
            )
        }

        /** Generic knowledge with no candidate data: live AI answers still work; nothing is claimed as history. */
        fun empty(playbooks: List<KnowledgeSnippet>? = null): KnowledgeBase = KnowledgeBase(
            profile = CandidateProfile(), stories = listOf(), questions = listOf(),
            snippets = playbooks ?: listOf(), systemPromptTemplate = PromptTemplates.LiveAnswer, context = TargetContext(),
        )

        private fun loadContext(directory: String, profile: CandidateProfile): TargetContext {
            val path = File(directory, "target_context.json")
            val ctx = if (path.exists()) jsonOptions.decodeFromString<TargetContext>(path.readText(Charsets.UTF_8)) else TargetContext()
            if (ctx.candidateName == "the candidate" && profile.name.isNotEmpty()) ctx.candidateName = profile.name
            ctx.employers = profile.experience.map { it.company }
            return ctx
        }

        /** Splits markdown into small retrievable snippets: one per bullet / table row / paragraph, tagged with its heading. */
        fun splitMarkdown(source: String, markdown: String): List<KnowledgeSnippet> {
            val out = mutableListOf<KnowledgeSnippet>()
            var heading = ""
            for (raw in markdown.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith('#')) {
                    heading = line.trimStart('#').trim()
                    continue
                }
                if (tableSeparator.containsMatchIn(line)) continue // table separator
                if (line.startsWith("| Fact") || line.startsWith("| # ") || line.startsWith("| Theme")) continue // table header
                val text = line.trimStart('-', '*', ' ').replace("**", "")
                if (text.length < 25) continue
                out.add(KnowledgeSnippet(source, heading, text))
            }
            return out
        }

        private val tableSeparator: Regex = dotNetRegex("^\\|[\\s\\-\\|]+\\|$")

        /** Walks up from [start] to find [relative] containing [marker]. */
        fun findDirectory(start: String, relative: String = "knowledge", marker: String = "question_bank.json"): String? {
            var dir: File? = File(start).absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (File(candidate, marker).exists()) return candidate.path
                dir = dir.parentFile
            }
            return null
        }
    }
}
