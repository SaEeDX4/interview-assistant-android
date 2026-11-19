package com.interviewassistant.core.ingestion

import com.interviewassistant.core.persistence.InvalidDataException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs
import kotlin.math.roundToLong
import org.xml.sax.Attributes
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/**
 * Mirror of `InterviewAssistant.Core/Ingestion/DocumentTextExtractor.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full port of the DOCX and text paths; the PDF path is the D6/DC-10 adaptation described below.
 *
 * Extracts plain text from PDF, DOCX (OpenXML parsed directly, no dependency) and UTF-8 text.
 *
 * DOCX adaptation: the C# walks `word/document.xml` with a streaming XmlReader (DTD prohibited, no
 * resolver); the JVM/Android-common equivalent is a namespace-aware SAX parse with DOCTYPE disallowed,
 * running the same state machine (paragraph / numbering / text-run). SAX reports an empty element as
 * start+end, where XmlReader reports a single empty element node — the one place that differs (an empty
 * `w:p`) appends its line from the end-element branch instead, with the same net output.
 *
 * PDF adaptation (P3-PORT-NOTES DC-10, the map's D6): PdfPig has no equivalent in the version catalog
 * and `:core` may not take new dependencies, so [fromPdf] re-implements the text layer over the PDF
 * content streams directly: FlateDecode streams are inflated, text-showing operators (Tj, TJ, ', ")
 * are read with their text-matrix positions, and the C#'s line rule is applied to the positioned
 * fragments — sorted by baseline (rounded Y descending, then X ascending), a new line when the rounded
 * Y moves by more than 2, a space otherwise. Fragments are whole shown strings rather than PdfPig's
 * glyph-gap-split words, and font encodings/CMaps are not interpreted (standard encodings and
 * UTF-16BE hex strings are) — see the divergence register for the exact limits.
 */

/** Mirror of System.Xml.XmlException (a BCL type the C# catches by name). */
class XmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ExtractedText(val text: String, val needsOcr: Boolean, val pages: Int)

object DocumentTextExtractor {
    fun extract(type: UploadType, content: ByteArray): ExtractedText = when (type) {
        UploadType.Pdf -> fromPdf(content)
        UploadType.Docx -> ExtractedText(fromDocx(content), false, 0)
        else -> ExtractedText(normalize(String(content, Charsets.UTF_8).trimStart('\uFEFF')), false, 0)
    }

    fun fromPdf(content: ByteArray): ExtractedText {
        val pages = PdfTextLayer.pages(content)
        val sb = StringBuilder()
        for (page in pages) {
            // Group words into lines by baseline to keep résumé structure (one logical line per visual line).
            val words = page.fragments.sortedWith(
                compareByDescending<PdfTextLayer.Fragment> { it.y.roundToLong() }.thenBy { it.x },
            )
            var lastY: Double? = null
            for (w in words) {
                val y = w.y.roundToLong()
                if (lastY != null && abs(y - lastY) > 2) sb.appendLine()
                else if (lastY != null) sb.append(' ')
                sb.append(w.text)
                lastY = y.toDouble()
            }
            sb.appendLine().appendLine()
        }
        val text = normalize(sb.toString())
        // Image-only PDFs have pages but no extractable text: flag for OCR rather than silently returning nothing.
        return ExtractedText(text, pages.isNotEmpty() && text.trim().length < 20, pages.size)
    }

    fun fromDocx(content: ByteArray): String {
        val xml: InputStream = try {
            var found: InputStream? = null
            ZipInputStream(ByteArrayInputStream(content)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "word/document.xml") {
                        found = ByteArrayInputStream(zip.readBytes())
                        break
                    }
                }
            }
            found ?: throw InvalidDataException("No document.xml")
        } catch (e: ZipException) {
            throw InvalidDataException(e.message ?: "Invalid DOCX", e)
        }
        val handler = DocxHandler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            // XmlReaderSettings: DtdProcessing.Prohibit, XmlResolver = null.
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (e: Exception) {
                // A parser without the feature: DOCX parts never legitimately carry a DOCTYPE; keep parsing.
            }
            factory.newSAXParser().parse(xml, handler)
        } catch (e: SAXParseException) {
            throw XmlException(e.message ?: "XML error", e)
        } catch (e: org.xml.sax.SAXException) {
            throw XmlException(e.message ?: "XML error", e)
        }
        return normalize(handler.result())
    }

    fun normalize(text: String): String {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        val lines = t.split('\n').map { l -> dotNetRegex("[ \\t]+").replace(l, " ").trimEnd() }
        return dotNetRegex("\\n{3,}").replace(lines.joinToString("\n"), "\n\n").trim()
    }

    /** The SAX state machine mirroring the C# XmlReader walk over `word/document.xml`. */
    private class DocxHandler : DefaultHandler() {
        private val sb = StringBuilder()
        private val para = StringBuilder()
        private var inText = false
        private var numbered = false

        fun result(): String = sb.toString()

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (uri != W) return
            when (localName) {
                "p" -> {
                    para.clear(); numbered = false
                }
                "numPr" -> numbered = true
                "t" -> inText = true
                "tab" -> para.append('\t')
                "br", "cr" -> para.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) para.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri != W) return
            if (localName == "t") inText = false
            else if (localName == "p") {
                sb.append(if (numbered) "• " else "").appendLine(para.toString())
            }
        }

        companion object {
            const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        }
    }
}

/**
 * Minimal PDF text layer for [DocumentTextExtractor.fromPdf] (DC-10): locates each page's content
 * stream (direct or in an object stream-free classic layout), inflates FlateDecode data, and extracts
 * positioned text fragments from the text-showing operators. Page count comes from the page tree
 * (`/Type /Page` objects, falling back to `/Count` of the first `/Pages` node).
 */
private object PdfTextLayer {
    data class Fragment(val text: String, val x: Double, val y: Double)
    data class Page(val fragments: List<Fragment>)

    private val latin1 = Charsets.ISO_8859_1

    fun pages(content: ByteArray): List<Page> {
        val raw = String(content, latin1)
        val streams = extractStreams(raw)
        val contentStreams = streams.filter { it.second.contains("BT") }
        if (contentStreams.isEmpty()) {
            // No text-showing stream: still report the page count (image-only PDFs flag for OCR).
            val count = declaredPageCount(raw)
            return List(count) { Page(emptyList()) }
        }
        // One content stream per page in document order (the layout every producer the app accepts uses;
        // a page referencing several streams concatenates them in order — handled when counts agree).
        val declared = declaredPageCount(raw)
        val pages = contentStreams.map { (_, streamText) -> Page(parseContent(streamText)) }
        return if (declared > pages.size) pages + List(declared - pages.size) { Page(emptyList()) } else pages
    }

    private fun declaredPageCount(raw: String): Int {
        val pageObjects = Regex("/Type\\s*/Page(?![sA-Za-z])").findAll(raw).count()
        if (pageObjects > 0) return pageObjects
        val count = Regex("/Count\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return count ?: 0
    }

    /** All `stream`…`endstream` payloads, FlateDecode-inflated when the owning dictionary says so. */
    private fun extractStreams(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (true) {
            val s = raw.indexOf("stream", i)
            if (s < 0) break
            // "stream" must be a keyword, not the tail of "endstream".
            if (s >= 3 && raw.substring(s - 3, s) == "end") {
                i = s + 6
                continue
            }
            var dataStart = s + 6
            if (dataStart < raw.length && raw[dataStart] == '\r') dataStart++
            if (dataStart < raw.length && raw[dataStart] == '\n') dataStart++
            val e = raw.indexOf("endstream", dataStart)
            if (e < 0) break
            var dataEnd = e
            if (dataEnd > dataStart && raw[dataEnd - 1] == '\n') dataEnd--
            if (dataEnd > dataStart && raw[dataEnd - 1] == '\r') dataEnd--
            val dict = raw.substring(maxOf(0, s - 2048), s)
            val data = raw.substring(dataStart, dataEnd).toByteArray(latin1)
            val text = if (dict.contains("/FlateDecode")) {
                try {
                    String(InflaterInputStream(ByteArrayInputStream(data)).readBytes(), latin1)
                } catch (ex: Exception) {
                    ""
                }
            } else {
                String(data, latin1)
            }
            out.add(dict to text)
            i = e + 9
        }
        return out
    }

    /** Reads the text-showing operators of one content stream into positioned fragments. */
    private fun parseContent(stream: String): List<Fragment> {
        val fragments = mutableListOf<Fragment>()
        var x = 0.0
        var y = 0.0
        var lineX = 0.0
        var lineY = 0.0
        var leading = 0.0
        val numbers = ArrayDeque<Double>()
        var pendingString: String? = null
        var pendingArray: MutableList<Any>? = null
        var inArray = false
        var i = 0
        val n = stream.length

        fun showText(text: String) {
            if (text.isNotEmpty()) fragments.add(Fragment(text, x, y))
            x += 0.0 // advance width is font-dependent; ordering uses line starts and stream order
        }

        while (i < n) {
            val c = stream[i]
            when {
                c == '(' -> {
                    val (str, next) = parseLiteralString(stream, i)
                    if (inArray) pendingArray?.add(str) else pendingString = str
                    i = next
                }
                c == '<' && i + 1 < n && stream[i + 1] != '<' -> {
                    val end = stream.indexOf('>', i)
                    if (end < 0) break
                    val str = parseHexString(stream.substring(i + 1, end))
                    if (inArray) pendingArray?.add(str) else pendingString = str
                    i = end + 1
                }
                c == '[' -> {
                    inArray = true
                    pendingArray = mutableListOf()
                    i++
                }
                c == ']' -> {
                    inArray = false
                    i++
                }
                c.isDigit() || (c == '-' && i + 1 < n && (stream[i + 1].isDigit() || stream[i + 1] == '.')) || (c == '.' && i + 1 < n && stream[i + 1].isDigit()) -> {
                    var j = i
                    while (j < n && (stream[j].isDigit() || stream[j] == '.' || stream[j] == '-' || stream[j] == '+')) j++
                    val v = stream.substring(i, j).toDoubleOrNull()
                    if (v != null) {
                        if (inArray) pendingArray?.add(v) else numbers.addLast(v)
                    }
                    i = j
                }
                c.isLetter() || c == '\'' || c == '"' -> {
                    var j = i
                    while (j < n && (stream[j].isLetter() || stream[j] == '\'' || stream[j] == '"' || stream[j] == '*')) j++
                    val op = stream.substring(i, j)
                    when (op) {
                        "BT" -> {
                            x = 0.0; y = 0.0; lineX = 0.0; lineY = 0.0
                        }
                        "Tm" -> {
                            if (numbers.size >= 6) {
                                val v = numbers.takeLast(6)
                                lineX = v[4]; lineY = v[5]; x = lineX; y = lineY
                            }
                            numbers.clear()
                        }
                        "Td" -> {
                            if (numbers.size >= 2) {
                                val v = numbers.takeLast(2)
                                lineX += v[0]; lineY += v[1]; x = lineX; y = lineY
                            }
                            numbers.clear()
                        }
                        "TD" -> {
                            if (numbers.size >= 2) {
                                val v = numbers.takeLast(2)
                                leading = -v[1]
                                lineX += v[0]; lineY += v[1]; x = lineX; y = lineY
                            }
                            numbers.clear()
                        }
                        "TL" -> {
                            if (numbers.isNotEmpty()) leading = numbers.last()
                            numbers.clear()
                        }
                        "T*" -> {
                            lineY -= leading; x = lineX; y = lineY
                        }
                        "Tj", "'" -> {
                            pendingString?.let { showText(it) }
                            pendingString = null
                            numbers.clear()
                        }
                        "\"" -> {
                            pendingString?.let { showText(it) }
                            pendingString = null
                            numbers.clear()
                        }
                        "TJ" -> {
                            val arr = pendingArray
                            if (arr != null) {
                                val sb = StringBuilder()
                                for (item in arr) {
                                    when (item) {
                                        is String -> sb.append(item)
                                        // A large positive adjustment is a word gap in glyph space.
                                        is Double -> if (item < -200) sb.append(' ')
                                    }
                                }
                                showText(sb.toString())
                            }
                            pendingArray = null
                            numbers.clear()
                        }
                        else -> numbers.clear()
                    }
                    i = j
                }
                else -> i++
            }
        }
        return fragments
    }

    /** A literal string `(…)`: balanced nested parentheses and the PDF escape set. Returns text + next index. */
    private fun parseLiteralString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 1
        var i = start + 1
        while (i < s.length && depth > 0) {
            val c = s[i]
            when (c) {
                '\\' -> {
                    if (i + 1 >= s.length) {
                        i++
                        break
                    }
                    val e = s[i + 1]
                    when (e) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { sb.append(e); i += 2 }
                        '\n' -> i += 2
                        '\r' -> {
                            i += 2
                            if (i < s.length && s[i] == '\n') i++
                        }
                        in '0'..'7' -> {
                            var j = i + 1
                            var code = 0
                            var digits = 0
                            while (j < s.length && digits < 3 && s[j] in '0'..'7') {
                                code = code * 8 + (s[j] - '0')
                                j++; digits++
                            }
                            sb.append(code.toChar())
                            i = j
                        }
                        else -> { sb.append(e); i += 2 }
                    }
                }
                '(' -> { depth++; sb.append(c); i++ }
                ')' -> {
                    depth--
                    if (depth > 0) sb.append(c)
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() to i
    }

    /** A hex string: UTF-16BE when it carries the BOM, otherwise one byte per character (WinAnsi/Latin-1). */
    private fun parseHexString(hex: String): String {
        val clean = hex.filter { !it.isWhitespace() }
        val bytes = ByteArray((clean.length + 1) / 2)
        for (k in bytes.indices) {
            val hi = Character.digit(clean[k * 2], 16)
            val lo = if (k * 2 + 1 < clean.length) Character.digit(clean[k * 2 + 1], 16) else 0
            bytes[k] = ((hi shl 4) or lo).toByte()
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else String(bytes, Charsets.ISO_8859_1)
    }
}
