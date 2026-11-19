package com.interviewassistant.core.domain

/**
 * Mirror of `InterviewAssistant.Core/Domain/ProfileMigration.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 *
 * Upgrades stored profiles to the current schema without losing anything: RC1 profiles (résumé facts only) gain
 * provenance (source, file name, timestamps). Confirmation state is never changed by a migration.
 */
object ProfileMigration {
    /** Returns true when the profile was changed (the caller may persist it). */
    fun migrate(p: CandidateProfileRecord): Boolean {
        if (p.schemaVersion >= CandidateProfileRecord.CurrentSchemaVersion) return false
        // Tolerates duplicate document ids (hand-edited or damaged files) instead of making the profile unloadable.
        val docs = p.documents.groupBy { it.id }.mapValues { it.value.first().fileName }
        for (f in p.facts) {
            if (f.source == FactSource.Unknown && f.provenance != null) {
                f.source = FactSource.ResumeImport
                f.sourceName = docs[f.provenance.documentId] ?: f.sourceName
            }
            if (f.createdUtc == null) f.createdUtc = p.createdUtc
            if (f.status == FactStatus.UserConfirmed && f.confirmedUtc == null) f.confirmedUtc = p.updatedUtc
        }
        p.schemaVersion = CandidateProfileRecord.CurrentSchemaVersion
        return true
    }
}
