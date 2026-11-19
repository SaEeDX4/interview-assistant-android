package com.interviewassistant.core

import com.interviewassistant.core.domain.CandidateProfileRecord
import com.interviewassistant.core.domain.DocumentKind
import com.interviewassistant.core.domain.InterviewTarget
import com.interviewassistant.core.domain.PreparedPack
import com.interviewassistant.core.domain.ProfileService
import com.interviewassistant.core.knowledge.KnowledgeBase
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.preparation.PreparationAssets
import com.interviewassistant.core.preparation.PreparationPipeline
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test support mirroring `InterviewAssistant.Tests/Fixtures.cs` (the parts Package A's tests use) and the
 * DOCX/PDF builders of `ProfileIngestionTests.cs`. Fixture texts are the byte-identical copies under
 * `src/test/resources/fixtures/` (SHA-256 recorded in P3-PORT-NOTES.md).
 *
 * The C# tests build PDFs with PdfPig's writer; there is no PdfPig equivalent here (DC-10), so [buildPdf]
 * writes a minimal one-page PDF by hand (Helvetica, one Tm/Tj per line at the same coordinates the C#
 * uses: x=50, y=780 stepping down 20, size 11).
 */
object Fixtures {
    fun text(vararg parts: String): String {
        val path = "/fixtures/" + parts.joinToString("/")
        val stream = Fixtures::class.java.getResourceAsStream(path) ?: error("fixture not found: $path")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    fun shervinProfile(confirm: Boolean = true): CandidateProfileRecord {
        val p = CandidateProfileRecord(interviewLanguage = "en")
        ProfileService.addResume(
            p,
            ProfileService.createDocument("shervin.txt", DocumentKind.Resume, text("resumes", "shervin_fallahdoust.txt")),
        )
        if (confirm) p.confirmAll()
        return p
    }

    fun teroxxTarget(profileId: String): InterviewTarget = InterviewTarget(
        profileId = profileId,
        jobDescriptionText = text("jobs", "teroxx_product_owner.txt"),
        expectedLanguage = "en",
    )

    /** Mirror of `ProfileIngestionTests.BuildDocx`. */
    fun buildDocx(paragraphs: List<String>, bulletLast: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>",
            )
            val body = paragraphs.mapIndexed { i, p ->
                val pPr = if (bulletLast && i == paragraphs.size - 1)
                    "<w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>" else ""
                "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">${xmlEscape(p.trimStart('•', ' '))}</w:t></w:r></w:p>"
            }.joinToString("")
            entry(
                "word/document.xml",
                "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>",
            )
        }
        return out.toByteArray()
    }

    private fun xmlEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** One page with the given lines (the C# PdfIsExtracted layout), or an image-only page when [lines] is null. */
    fun buildPdf(lines: List<String>?): ByteArray {
        val content: ByteArray? = lines?.let {
            val sb = StringBuilder("BT /F1 11 Tf")
            var y = 780
            for (l in it) {
                sb.append(" 1 0 0 1 50 $y Tm (${pdfEscape(l)}) Tj")
                y -= 20
            }
            sb.append(" ET")
            sb.toString().toByteArray(Charsets.UTF_8)
        }
        val objects = mutableListOf<ByteArray>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.UTF_8)
        objects += "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.UTF_8)
        objects += ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >>" +
            (if (content != null) " /Contents 5 0 R" else "") + " >>").toByteArray(Charsets.UTF_8)
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".toByteArray(Charsets.UTF_8)
        if (content != null) {
            val stream = ByteArrayOutputStream()
            stream.write("<< /Length ${content.size} >>\nstream\n".toByteArray(Charsets.UTF_8))
            stream.write(content)
            stream.write("\nendstream".toByteArray(Charsets.UTF_8))
            objects += stream.toByteArray()
        }
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.UTF_8))
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, bytes ->
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.write("\nendobj\n".toByteArray(Charsets.UTF_8))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun pdfEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '(' -> append("\\(")
            ')' -> append("\\)")
            else -> append(c)
        }
    }

    // ---- Package B: golden knowledge set + Loc tables (mirror of `TestKnowledge.cs`) ----

    /** The 12 files of the golden `samples/shervin-teroxx` set, byte-identical under `src/test/resources/knowledge/`. */
    val knowledgeFiles: List<String> = listOf(
        "answer_policy.md", "candidate_profile.json", "candidate_stories.json", "company_brief.md",
        "crypto_fintech_playbook.md", "product_brief.md", "product_owner_playbook.md", "question_bank.json",
        "role_brief.md", "target_context.json", "test_questions.json", "whitepaper_notes.md",
    )

    private var knowledgeDirCache: File? = null

    /** Materializes the classpath `knowledge/` resources into a real directory once (KnowledgeBase.load reads a directory). */
    fun knowledgeDir(): File {
        knowledgeDirCache?.let { return it }
        val dir = Files.createTempDirectory("ia-test-knowledge").toFile()
        for (name in knowledgeFiles) {
            val stream = Fixtures::class.java.getResourceAsStream("/knowledge/$name")
                ?: error("knowledge resource not found: /knowledge/$name")
            File(dir, name).writeBytes(stream.readBytes())
        }
        knowledgeDirCache = dir
        return dir
    }

    private var testKnowledgeCache: KnowledgeBase? = null

    /** Mirror of C# `TestKnowledge.Load()` — one lazily loaded instance of the golden set. */
    fun testKnowledge(): KnowledgeBase {
        testKnowledgeCache?.let { return it }
        val kb = KnowledgeBase.load(knowledgeDir().absolutePath)
        testKnowledgeCache = kb
        return kb
    }

    /** Wires [Loc.tableLoader] to the real byte-identical i18n assets in `:core` (same pattern as the `:client` tests). */
    fun installLocTables() {
        Loc.tableLoader = { code ->
            val candidates = listOf(
                File("src/main/assets/i18n/$code.json"),
                File("core/src/main/assets/i18n/$code.json"),
                File("../core/src/main/assets/i18n/$code.json"),
            )
            val file = candidates.firstOrNull { it.isFile }
                ?: throw IllegalStateException("i18n asset $code.json not found from ${File(".").absolutePath}")
            Loc.parseTable(file.readText(Charsets.UTF_8))
        }
    }

    /** Raw text of one i18n asset (the coverage tests scan the tables themselves). */
    fun i18nAssetText(code: String): String {
        val candidates = listOf(
            File("src/main/assets/i18n/$code.json"),
            File("core/src/main/assets/i18n/$code.json"),
            File("../core/src/main/assets/i18n/$code.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("i18n asset $code.json not found from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    // ---- Package C: preparation assets, the prepared Shervin pack, recorded-style audio fixtures ----

    fun bytes(vararg parts: String): ByteArray {
        val path = "/fixtures/" + parts.joinToString("/")
        val stream = Fixtures::class.java.getResourceAsStream(path) ?: error("fixture not found: $path")
        return stream.readBytes()
    }

    private var assetsCache: PreparationAssets? = null

    /**
     * Mirror of C# `Fixtures.Assets`: the shipped generic knowledge (the C# test project's `generic-knowledge`
     * output dir = `src/main/assets/knowledge/` here), materialized into a real directory for
     * [PreparationAssets.load].
     */
    fun assets(): PreparationAssets {
        assetsCache?.let { return it }
        val roots = listOf(
            File("src/main/assets/knowledge"),
            File("core/src/main/assets/knowledge"),
            File("../core/src/main/assets/knowledge"),
        )
        val root = roots.firstOrNull { File(it, PreparationAssets.LibraryFileName).isFile }
            ?: throw IllegalStateException("shipped knowledge assets not found from ${File(".").absolutePath}")
        val dir = Files.createTempDirectory("ia-test-assets").toFile()
        File(root, PreparationAssets.LibraryFileName).copyTo(File(dir, PreparationAssets.LibraryFileName))
        val pbTarget = File(dir, "playbooks")
        pbTarget.mkdirs()
        for (f in File(root, "playbooks").listFiles { file -> file.isFile && file.name.endsWith(".md") } ?: emptyArray()) {
            f.copyTo(File(pbTarget, f.name))
        }
        val loaded = PreparationAssets.load(dir.absolutePath)
        assetsCache = loaded
        return loaded
    }

    /** Mirror of C# `Fixtures.ShervinPackAsync()`. */
    fun shervinPack(): PreparedPack {
        val p = shervinProfile()
        return runBlocking { PreparationPipeline(assets()).runAsync(p, teroxxTarget(p.id)) }
    }

    private var audioDirCache: File? = null

    /**
     * Materializes the byte-identical `fixtures/audio/` classpath resources (74 WAVs + fixtures.json +
     * MANIFEST.md) into a real directory once — the mirror of C# `Fixtures.Path("audio")`.
     */
    fun audioFixtureDir(): File {
        audioDirCache?.let { return it }
        val dir = Files.createTempDirectory("ia-test-audio").toFile()
        val index = Json.parseToJsonElement(text("audio", "fixtures.json")).jsonObject
        val names = index["fixtures"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }
        File(dir, "fixtures.json").writeBytes(bytes("audio", "fixtures.json"))
        File(dir, "MANIFEST.md").writeBytes(bytes("audio", "MANIFEST.md"))
        for (name in names) File(dir, name).writeBytes(bytes("audio", name))
        audioDirCache = dir
        return dir
    }
}
