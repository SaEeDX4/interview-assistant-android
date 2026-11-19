package com.interviewassistant.core.ingestion

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Mirror of `InterviewAssistant.Core/Ingestion/UploadValidator.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Adaptations: the C# `IUploadScanner.IsClean(name, bytes, out reason)` out-parameter has no Kotlin
 * equivalent; it is [IUploadScanner.blockReason] (null = clean) — see P3-PORT-NOTES DC-12. The DOCX
 * structure check reads entries through [ZipInputStream]; entry sizes are counted from the decompressed
 * bytes (bounded by the same 200 MiB limit) because ZIP local headers may carry a data descriptor
 * instead of a size — the accept/reject outcomes are identical.
 */

enum class UploadType { Pdf, Docx, Text }

data class UploadCheck(val ok: Boolean, val type: UploadType, val safeFileName: String, val error: String?)

/** Hook for anti-malware scanning (e.g. Windows AMSI on desktop, ClamAV on server). Default: allow. */
interface IUploadScanner {
    /** The reason the content is blocked, or null when it is clean (mirrors C# `IsClean(..., out string? reason)`). */
    fun blockReason(safeFileName: String, content: ByteArray): String?
}

class AllowAllScanner : IUploadScanner {
    override fun blockReason(safeFileName: String, content: ByteArray): String? = null
}

/**
 * Validates untrusted uploads: size cap, extension allow-list, magic bytes, DOCX structure, text encoding,
 * and file-name sanitisation (the client file name is never used as a path).
 */
object UploadValidator {
    const val MaxBytes = 10 * 1024 * 1024

    fun sanitizeFileName(name: String?): String {
        // Path.GetFileName over the last '/'-separated segment (backslashes were replaced first).
        val n = (name ?: "").replace('\\', '/').split('/').last()
        val sb = StringBuilder()
        for (c in n) {
            // char.IsControl, or the Windows invalid-file-name set (audit §3.5: hard-coded, OS-independent),
            // which includes the C# code's explicit ':' '*' '?' '"' '<' '>' '|' checks.
            sb.append(if (c.isISOControl() || c in "\"<>|:*?\\/") '_' else c)
        }
        var s = sb.toString().trim().trimStart('.')
        if (s.isEmpty()) s = "document"
        return if (s.length > 120) s.substring(0, 120) else s
    }

    fun validate(fileName: String?, content: ByteArray, scanner: IUploadScanner? = null): UploadCheck {
        val safe = sanitizeFileName(fileName)
        if (content.isEmpty()) return UploadCheck(false, UploadType.Text, safe, "File is empty.")
        if (content.size > MaxBytes) return UploadCheck(false, UploadType.Text, safe, "File is larger than ${MaxBytes / 1024 / 1024} MB.")
        val ext = extensionOf(safe).lowercase()
        val type: UploadType
        when (ext) {
            ".pdf" -> {
                if (!(content.size > 5 && String(content, 0, 5, Charsets.US_ASCII) == "%PDF-"))
                    return UploadCheck(false, UploadType.Pdf, safe, "Not a valid PDF file.")
                type = UploadType.Pdf
            }
            ".docx" -> {
                if (!(content.size > 4 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte() && content[2] == 3.toByte() && content[3] == 4.toByte()))
                    return UploadCheck(false, UploadType.Docx, safe, "Not a valid DOCX file.")
                try {
                    var entries = 0
                    var total = 0L
                    var hasDocument = false
                    ZipInputStream(ByteArrayInputStream(content)).use { zip ->
                        val buf = ByteArray(81920)
                        while (true) {
                            val e = zip.nextEntry ?: break
                            entries++
                            if (e.name == "word/document.xml") hasDocument = true
                            var n: Int
                            while (zip.read(buf).also { n = it } > 0) total += n
                        }
                    }
                    if (!hasDocument) return UploadCheck(false, UploadType.Docx, safe, "DOCX has no document body.")
                    if (entries > 2000 || total > 200L * 1024 * 1024) return UploadCheck(false, UploadType.Docx, safe, "DOCX is too large when unpacked.")
                } catch (e: ZipException) {
                    return UploadCheck(false, UploadType.Docx, safe, "DOCX is corrupted.")
                } catch (e: EOFException) {
                    return UploadCheck(false, UploadType.Docx, safe, "DOCX is corrupted.")
                }
                type = UploadType.Docx
            }
            ".txt", ".md" -> {
                if (content.any { it == 0.toByte() }) return UploadCheck(false, UploadType.Text, safe, "Text file contains binary data.")
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(content))
                } catch (e: java.nio.charset.CharacterCodingException) {
                    return UploadCheck(false, UploadType.Text, safe, "Text file must be UTF-8.")
                }
                type = UploadType.Text
            }
            else -> return UploadCheck(false, UploadType.Text, safe, "Unsupported file type. Use PDF, DOCX or TXT.")
        }
        if (scanner != null) {
            val why = scanner.blockReason(safe, content)
            if (why != null) return UploadCheck(false, type, safe, "Blocked by security scan: " + why)
        }
        return UploadCheck(true, type, safe, null)
    }

    /** System.IO.Path.GetExtension: the dot-included extension of the last path segment, "" when none. */
    private fun extensionOf(fileName: String): String {
        val segment = fileName.substringAfterLast('/')
        val dot = segment.lastIndexOf('.')
        return if (dot >= 0) segment.substring(dot) else ""
    }
}
