package com.interviewassistant.app.services

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Mirror of `InterviewAssistant.App/Services/Branding.cs`. */

/**
 * Product identity in one place. Defaults are a neutral placeholder until the commercial brand is chosen;
 * override without recompiling via branding.json in the app base directory:
 * {"ProductName": "...", "CompanyName": "...", "SupportUrl": "..."}.
 * (The C# reads the file next to the EXE; on Android the base directory is not user-writable,
 * so the override serves support/diagnostics rather than end users — P4-PORT-NOTES DC-82.)
 */
object Branding {
    var productName: String = "Interview Assistant"
        private set
    var companyName: String = "Interview Assistant"
        private set
    var supportUrl: String = ""
        private set

    private val json = Json { ignoreUnknownKeys = true }

    fun load(baseDir: String) {
        val path = File(baseDir, "branding.json")
        if (!path.exists()) return
        try {
            val b = json.decodeFromString<Map<String, String>>(path.readText(Charsets.UTF_8))
            productName = b["ProductName"] ?: productName
            companyName = b["CompanyName"] ?: companyName
            supportUrl = b["SupportUrl"] ?: supportUrl
        } catch (ex: Exception) {
            if (ex is SerializationException || ex is IOException) {
                AppLog.warn("branding.json ignored: " + AppLog.describe(ex))
            } else {
                throw ex
            }
        }
    }
}
