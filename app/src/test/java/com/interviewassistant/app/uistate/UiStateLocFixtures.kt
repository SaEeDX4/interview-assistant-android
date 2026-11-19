package com.interviewassistant.app.uistate

import com.interviewassistant.core.languages.Loc
import java.io.File

/**
 * Test fixture: installs the real shared i18n tables into [Loc] (the :app unit tests run with the module
 * directory as working directory, so the tables live in ../core/src/main/assets/i18n). Mirrors the C# test
 * fixture that points Loc at the app's i18n folder.
 */
object UiStateLocFixtures {
    private var installed = false

    @Synchronized
    fun installLocTables() {
        if (installed) return
        Loc.tableLoader = { code ->
            val candidates = listOf(
                File("src/main/assets/i18n/$code.json"),
                File("core/src/main/assets/i18n/$code.json"),
                File("../core/src/main/assets/i18n/$code.json"),
            )
            val file = candidates.firstOrNull { it.isFile }
            if (file == null) null else Loc.parseTable(file.readText(Charsets.UTF_8))
        }
        installed = true
    }
}
