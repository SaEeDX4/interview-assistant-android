# P3 Package A — Port Notes (Domain / Persistence / Ingestion)

Local-only work: nothing pushed, nothing compiled or run locally (no JVM in this environment — GitHub
Actions is the first execution check, per the P2 pattern). This file records exactly what was written,
the test mapping, every divergence candidate, the fixtures, and the honest compile risks.

Package A scope per TRANSFER-MAP §7 P3: `Models.cs`, `ProfileMigration.cs`, `ProfileService.cs`,
`WorkspaceStore.cs`, `ProfileBundle.cs`, `SecretFilter.cs`, `WorkspaceLocator.cs`, `ExportFile.cs`,
`DocumentTextExtractor.cs`, `JobDescriptionParser.cs`, `PromptInjectionGuard.cs`, `ResumeParser.cs`,
`UploadValidator.cs` + their tests. Also changed: `project/core/build.gradle.kts`
(+ `testImplementation(libs.junit)` via the existing catalog — the only dependency change).

## 1. Per-file port checklist

| C# file (lines) | Kotlin file (lines) | Completeness |
|---|---|---|
| Domain/Models.cs (263) | domain/Models.kt (393) | Full: all enums (DocumentKind, FactKind, FactStatus, FactSource, InterviewType, PreparationState), Provenance, SourceDocument, ProfileFact (+ isVerifiedClaim/isReviewable/awaitingReview/setConfirmed), FactStats, PersonalCategories (21 keys verbatim), Story, CandidateProfileRecord (+ confirmAll/confirmedFacts/stats), InterviewTarget (+ displayName), RequirementMatch, PreparedPack (+ toKnowledgeBase). Also hosts `CoreJson` (System.Text.Json **default** semantics for this layer: PascalCase wire names via @SerialName, enum names, defaults/nulls written, unknown keys ignored) and `CoreDateTimeSerializer`/`CoreDateTimeNullableSerializer` (STJ-compatible UTC tick formatting, copied from the :contracts pattern; :core depends on nothing). |
| Domain/ProfileMigration.cs (28) | domain/ProfileMigration.kt (27) | Full: `object ProfileMigration.migrate(...)`, exact logic. |
| Domain/ProfileService.cs (218) | domain/ProfileService.kt (256) | Full: the C# class is static → Kotlin `object`. createDocument (SHA-256 of the ORIGINAL text), upload (Pair for the C# tuple; exception filter mapped per DC-11), addResume, addManualFact, editFact, deleteFact, setPersonal (600-char cap), rebuildStories, storyTopics (11 topic regexes), storyQuestionTypes, toEngineProfile, personalEntries, boundaryEntries, yearsOfExperience, toEngineStory, `IngestResult`. |
| Persistence/WorkspaceStore.cs (206) | persistence/WorkspaceStore.kt (297) | Full: WorkspaceStore (CoreJson at rest through IDataProtector, synchronized tmp-write + `fd.sync()` + atomic rename, `.corrupt` quarantine + listener list for the C# event, blacklist, getActive/setActive, all CRUD, applyRetention, exportProfile ZIP via SecretFilter) + StoredSession, in the C#'s order. The P2 `IDataProtector`/`NoProtection` **declarations are byte-identical**; only the file header comment and the import list grew around them. |
| Persistence/ProfileBundle.cs (515) | persistence/ProfileBundle.kt (617) | Full: ProfileBundleError, ProfileBundleException, ProfileBundleLimits (+ Default), manifest classes (camelCase @SerialName), Preview/Import/Content types, write (manifest first, SecretFilter keepProse entries), read (bounded reads, EOCD declared-count pre-check, unsafe-name → UnsafePath, entry-name regex with `\z` → Invalid, OrdinalIgnoreCase duplicate detection, size/SHA-256 verification → Tampered, counts check → Tampered, StrictJson parse + typed decode + descriptor-based RequireValues), import/importWithReport (new ids, target map, JSON-tree Guard with document flag recompute, pack Context profile_id/target_id rewrite, report SessionId remap, delete-on-failure cleanup). |
| Persistence/SecretFilter.cs (215) | persistence/SecretFilter.kt (446) | Full: key markers/words, both credential regexes, isSecretKey/words/looksSecret/redact, clean (fail-closed depth 96), cleanChecked (parse depth 128), toJson (serializer + reified). Also hosts the mirrored `InvalidDataException : IOException` and `StrictJson` (strict recursive-descent parser: depth limit + duplicate keys refused + number literals preserved) — see DC-11. |
| Persistence/WorkspaceLocator.cs (55) | persistence/WorkspaceLocator.kt (66) | Full: accountKey (first 24 lowercase hex of SHA-256 of `"ia-workspace:" + trimmed/lowercased id`), rootFor, hasLegacyData, adoptLegacy (+ marker). |
| Persistence/ExportFile.cs (42) | persistence/ExportFile.kt (55) | Full: tempPathFor, writeAsync (CREATE_NEW temp + flush + atomic move, temp deleted on failure), writeTextAsync (UTF-8, no BOM). One overload subsumed — DC-14. |
| Ingestion/DocumentTextExtractor.cs (87) | ingestion/DocumentTextExtractor.kt (417) | Full for TXT/DOCX: text path with BOM trim + normalize (NBSP→space, BOM strip); DOCX via namespace-aware SAX (DOCTYPE disallowed) running the C# XmlReader paragraph/bullet state machine. PDF: no PdfPig equivalent exists in the version catalog and no dependency was added → hand-written content-stream text layer — **DC-10**. Also hosts the mirrored `XmlException`. |
| Ingestion/JobDescriptionParser.cs (101) | ingestion/JobDescriptionParser.kt (115) | Full: all 5 multilingual heading regexes, bullet regex, NotTerms, terminology extraction. One ordering nuance — DC-16. |
| Ingestion/PromptInjectionGuard.cs (35) | ingestion/PromptInjectionGuard.kt (49) | Full: patterns, sanitize (line removal + flags), looksMalicious. Also hosts internal `dotNetRegex(...)` used across :core (.NET regexes are Unicode-aware by default; the JVM needs UNICODE_CHARACTER_CLASS, plus CASE_INSENSITIVE|UNICODE_CASE for the C#'s IgnoreCase|CultureInvariant). |
| Ingestion/ResumeParser.cs (208) | ingestion/ResumeParser.kt (264) | Full: ParsedResume, 7 section-heading regexes, PeriodRx/MetricRx/BulletRx, header/summary/experience/education/skills/languages/certifications/projects logic, provenance (240-char snippet cap), consistency flags, SplitOutsideBrackets, ExtractMetrics, NormalizePeriod, LooksLikeName, ToTitle. |
| Ingestion/UploadValidator.cs (72) | ingestion/UploadValidator.kt (120) | Full: 10 MiB cap, Windows invalid-filename set hard-coded (audit §3.5), magic bytes, DOCX via ZipInputStream (sizes counted from decompressed bytes under the same 200 MiB zip limit — data-descriptor ZIPs carry no local sizes), strict UTF-8 via reporting decoder, sanitizeFileName. Scanner `out string? reason` adapted — DC-12. |

No TODO / NotImplemented / placeholder bodies anywhere in the package. Every C# member is present or
registered below as a divergence candidate.

**Regex parity check (run locally):** every regex-looking string literal was extracted from the C#
sources and from the Kotlin ports, unescaped on both sides, and diffed per file — JobDescriptionParser
12=12, ResumeParser 15=15, PromptInjectionGuard 3=3, ProfileService 6=6, ProfileBundle 2=2,
SecretFilter patterns all equal (the only extras are an error-message string and the filename char
set). The Persian ZWNJ (U+200C) heading alternatives are byte-identical.

## 2. Test mapping

Test deps: `testImplementation(libs.junit)` added to `core/build.gradle.kts` (catalog junit 4.13.2).
xUnit theories became case loops inside one JUnit test each (P2 pattern). No C# test here observes a
faulted Task, so DC-9's supervisorScope was not needed in this package.

### ProfileIngestionTests.cs → ingestion/ProfileIngestionTests.kt — 9/9 ported
resumeParserExtractsAllFiveRolesWithExactFacts, everyFactHasProvenanceAndStartsUnverified,
unconfirmedFactsNeverReachTheEngineProfile, ambiguousRolesAreFlaggedNotGuessed,
germanResumeParsesWithLocalizedHeadings, docxIsExtracted, pdfIsExtracted,
imageOnlyPdfAsksForTextInsteadOfReturningNothing, multipleCvVersionsDoNotDuplicateFacts.
Same names (camelCase), inputs, expected values (exact role companies/titles, periods, 28 achievements,
2 education, 3 languages, 10 skills). Test-side adaptations: the DOCX builder is mirrored in
`Fixtures.buildDocx`; the PDF tests use a hand-built minimal PDF (Helvetica, Tm/Tj at the C#'s
coordinates x=50, y=780 step −20, size 11) instead of PdfPig's writer — tied to DC-10.

### Rc5DataExportTests.cs → persistence/Rc5DataExportTests.kt — 13 methods ported
secretKeyNamesAreRecognised (14 cases), ordinaryKeyNamesAreKept (15), 
filterRemovesSecretKeysEverywhere_AndRedactsCredentialShapedValues, credentialShapedValuesAreDetected
(7), ordinaryTextIsNotTreatedAsSecret (4), bundle_HasAManifestWithHashes_AndEveryEntry,
bundle_RoundTrip_ImportsAsANewProfileWithRemappedIds_AndNeverOverwrites, bundle_TamperedEntryIsRejected,
bundle_PathTraversalIsRejected (6), bundle_UnexpectedEntriesAndBadJsonAreRejected,
bundle_OversizeAndZipBombsAreRejected, bundle_VersionFormatAndNonZipAreRejected,
noSecretsInAnyProfileExport (bundle + raw-ZIP parts).

Seed adaptation (in-code comment): the C# seed builds the pack via PreparationPipeline and the report
via ReportSerializer (later packages). The bundle only carries those payloads, so the seed constructs
the PreparedPack directly (same ids, Context with profile/target ids) and an opaque report JSON with
the same `SessionId` field the bundle remapper rewrites; the remap is asserted by parsing that field,
exactly how ProfileBundle treats it.

**Deferred from Rc5DataExportTests (named, with target package):**
- DurationsReadLikeAPerson (7 cases), DurationsAreLocalized,
  ExportLabelsExistAndAreTranslatedInAllTenLanguages → **Reports package** (ExportLabels + Loc tables).
- AccountHtml_IsHuman_Escaped_AndHasNoSecrets, AccountHtml_UsesTheDesktopPlanWordsWhenGiven,
  AccountHtml_EmptyExportStillRendersEverySection, AccountHtml_IsLocalizedAndMirroredForRtl →
  **Reports package** (AccountExportRenderer).
- ProfileHtml_GroupsConfirmedExperienceByRole_MarksFactsToReview_AndEscapes, ProfileHtml_IsLocalized
  (4 cases), ProfileHtml_HasNoSecrets → **Reports package** (ProfileExportRenderer).
- The HTML assertion inside NoSecretsInAnyProfileExport → **Reports package** (the bundle/raw parts
  are ported).
- DesktopWiring_ExportChoiceDialogs_AndImportBackupAction → **App/UI package** (source-level test of
  the WPF shell; on Android it becomes a UI-wiring test of the Android app shell, P4+).

### SecurityAuditExportTests.cs → persistence/SecurityAuditExportTests.kt — 15 methods ported
malformedBackup_IsRejectedAsInvalid_NeverAnUnhandledException (12 MemberData cases),
wellFormedHandMadeBackup_IsStillAccepted, importMemory_IsBounded, newSecretKeyNames_AreRecognised
(13), ordinaryKeyNames_AreStillKept (11), longKeyNames_AreCheckedInFull,
deepNesting_FailsClosed_InBothCleanOverloads, uncheckableServiceExport_IsNotExported,
everyLeakCaseOfTheReview_IsFiltered (8), nonSecretIdsAndEmail_AreKept,
resumeProse_IsNotTreatedAsSecret (8), backup_KeepsProse_ButStillRemovesUnmistakableCredentials
(behavioral part), backup_RoundTripsResumeProseUnchanged,
import_ReSanitizesDocumentsFactsInterviewsAndPacks_AndRecomputesTheFlags,
exportFile_OnFailure_LeavesNoFileUnderTheRealName_AndNoTemporaryFile.

Adaptation inside uncheckableServiceExport_IsNotExported: a kotlinx JsonElement cannot contain
duplicate keys (they collapse at parse), so the C#'s duplicate-key sub-case is asserted at the raw-text
boundary the checked overload relies on (StrictJson refuses duplicates); the 130-deep sub-case is
ported unchanged. In backup_KeepsProse…, the C#'s anonymous type becomes a small @Serializable holder,
and its source-text tail (assertions on the .cs files' contents) is not ported — the behavior those
lines produce is covered by the bundle tests.

**Deferred from SecurityAuditExportTests (named, with target package):**
- ImportHandler_EndsWithACatchAll_ShowingTheInvalidBackupMessage,
  ImportHandler_WarnsLikeAnUpload_WhenInstructionsWereRemoved,
  EveryExportIsWrittenThroughATemporaryFile_AndAccountExportErrorsStayInThePrivacySection,
  DeleteAccount_CapturesTheLocalFoldersBeforeTheDeletion_AndOffersNoDeletionWhenTheSubscriptionBlocks,
  AppLog_StripsControlCharacters_AndLogsFileErrorsByTypeOnly, UpdateCheck_CatchesEveryException →
  **App/UI package** (source-level tests of the WPF shell/services).
- EntryNamesWithATrailingNewline_AreRejected → **App/UI package** for its source-text half; its
  behavioral half (profile id `"abc\n"` → Invalid) is ported as MalformedBundles case 12.
- LocalDataDeletion_ReportsAFolderItCouldNotRemove → **App/UI package** (`LocalData` lives in
  InterviewAssistant.App.UiState on Windows).

## 3. Divergence candidates

- **DC-10 — PDF text extraction (map D6 area).** The catalog has no PDF library and no dependency was
  added, so `DocumentTextExtractor`'s PDF path is a hand-written content-stream text layer: finds stream
  objects, inflates FlateDecode, reads Tj/TJ/'/" with Tm/Td/TD/TL/T* positioning, groups fragments by
  the C#'s rule (sort rounded-Y desc, then X asc; new line when rounded Y moves > 2, space otherwise).
  Limits vs PdfPig: fragments are whole shown strings (not glyph-gap-split words); font encodings/CMaps
  are not interpreted (standard encodings + UTF-16BE hex strings are); TJ word gaps come only from
  adjustments < −200; multi-stream-per-page concatenation is approximated; XObject/Form text is not
  followed. The test PDFs are likewise hand-built (see §2). Revisit if a PDF dependency is ever approved.
- **DC-11 — JSON tree / BCL adaptations in SecretFilter + ProfileBundle.** kotlinx JsonElement is
  immutable, so C# `Clean(JsonNode)` (in-place, returns bool) returns `CleanResult(node, changed)`, and
  `Clean(JsonElement)` is `cleanChecked(element)`. Duplicate keys and depth limits are enforced by the
  `StrictJson` parser at every raw-text entry point (the C# gets them from System.Text.Json), but a
  duplicate cannot exist inside an already-parsed kotlinx element (see the test adaptation in §2). The
  C#'s reflection-based `RequireValues` (NullabilityInfoContext) is re-implemented over kotlinx serial
  descriptors (same nullability/shape information). Mirrored `InvalidDataException`/`XmlException` stand
  in for the BCL types. Edge: a bundle manifest with `"appVersion": null` / `"profileName": null` /
  `"counts": null` is accepted by the C# (`??=`) but rejected as Invalid by Kotlin decoding into
  non-nullable fields — both reject nothing valid; error code differs only for those null edges.
  The JVM regex engine has no match timeout, so the C#'s RegexMatchTimeoutException fail-closed
  branches have no equivalent (noted in code).
- **DC-12 — Upload scanner reason.** C# `IUploadScanner.IsClean(name, bytes, out string? reason)` →
  Kotlin `blockReason(name, bytes): String?` (null = clean). A scanner that blocks while leaving the
  reason null cannot be represented (the C# would produce `"Blocked by security scan: "`).
- **DC-13 — Exported-JSON bytes.** System.Text.Json's default encoder escapes non-ASCII and
  HTML-sensitive characters and its indented writer differs from kotlinx pretty-print (2-space indent
  set explicitly). Bundle hashes are self-consistent and cross-implementation reads parse identically;
  generated bytes are not byte-identical to Windows output.
- **DC-14 — ExportFile overload.** The C#'s `Action<Stream>` overload is subsumed by the suspending
  overload (Kotlin cannot overload on suspending vs non-suspending lambdas without ambiguity at call
  sites); a synchronous writer is still a valid suspending lambda body.
- **DC-15 — Computed members in JSON (candidate, low).** System.Text.Json serializes public getters;
  the kotlinx plugin serializes only backed properties. `CandidateProfileRecord.stats` /
  `FactStats.confirmedExperience` are computed views: if the Windows profile JSON carries a `Stats`
  member, Android output omits it (reads still work — unknown keys are ignored on both sides). Decide
  at the first cross-platform JSON diff (P4 device testing).
- **DC-16 — Terminology tie-break ordering (candidate, low).** JobDescriptionParser's final
  `ThenBy(term)` uses .NET's culture-sensitive string comparison (CurrentCulture); the Kotlin port
  orders ordinally. Only affects the order of equal-frequency terms.

## 4. Fixtures copied (byte-identical, SHA-256 verified after copy)

Into `project/core/src/test/resources/fixtures/` (loaded from the classpath by the test `Fixtures`):

| Fixture | SHA-256 |
|---|---|
| resumes/shervin_fallahdoust.txt | `9e88aab5c84cbb5dee31f1e09a1a02d00304702a194f2b00891fcef560cad9c7` |
| resumes/maria_keller_de.txt | `a25a6a637a1d65a044ed513e9631717eff12475c46966ed5568626076c067772` |
| jobs/teroxx_product_owner.txt | `867e35256adb98dd50c91158beb82e01d7a2ff4c08500f5bdec7929e47955be8` |
| jobs/backend_engineer_de.txt | `837960f473ab4a1abe791bf5848ebffc63c62406f9351313fe2bfdbe78418658` |

(backend_engineer_de.txt is copied for parity completeness; Package A's ported tests reference the
other three.)

## 5. Honest compile-risk notes

**Nothing in this package has been compiled or executed.** There is no JVM in this environment; CI is
the first check. Specific risks, in order:

1. **Cross-package shapes (highest risk).** The Knowledge and Languages files are still P1 skeletons
   owned by sibling P3 packages, so `:core` cannot compile until they land. Package A assumes the
   established camelCase constructor shapes for: `knowledge.CandidateProfile`, `Experience`,
   `EducationEntry`, `LanguageEntry`, `PersonalEntry`, `CandidateStory`, `BankQuestion`,
   `TargetContext` (incl. `profileId`/`targetId`), `KnowledgeBase` (constructor + the
   `PreparedPack.toKnowledgeBase()` named arguments), `knowledge.PromptTemplates.LiveAnswer`, and
   `languages.LanguageDetector.detectOr(text, fallback, threshold)` as an object. If the sibling ports
   choose different shapes, Models.kt/ProfileService.kt need small call-site adjustments.
2. **RequireValues via descriptors** assumes `SerialDescriptor.getElementName` returns the @SerialName
   wire name (it does in kotlinx-serialization) and that list element descriptors sit at index 0.
3. **Manifest timestamp assertion** (`createdUtc` = `2026-10-17T09:00:00Z`) depends on
   CoreDateTimeSerializer's BASE formatter printing seconds — it does in the :contracts pattern it was
   copied from; CI will confirm.
4. **SecretFilter keepProse assignment case:** the bundle-mode redaction of `"portal password=hunter2"`
   (asserted by noSecretsInAnyProfileExport) follows the transcribed C# marker/unmistakable logic —
   a CI watch item, as is the JD text passing `looksSecret` == false for every bundle entry.
5. **Facts serialization equality** in bundle_RoundTrip… depends on the tick-faithful datetime
   serializer round-tripping `setConfirmed` timestamps losslessly (it trims to 100 ns ticks exactly
   like System.Text.Json).
6. **JUnit surface:** `assertThrows(String, Class, ThrowingRunnable)` and the returned-exception form
   are JUnit 4.13.2 APIs ✓. Tests reference `internal StrictJson` — visible from the test source set
   under the standard Gradle/Kotlin friend-module setup.
7. **Warnings expected (not errors):** a few unused imports; `e.path == null`/`e.sha256 == null`
   always-false conditions in ProfileBundle.parseManifest (kept deliberately — they mirror the C#'s
   explicit null checks against its nullable manifest model).
8. Two invisible-byte defects were caught and fixed during the review pass: a literal NUL inside a
   char literal in SecretFilter.kt (now `'\u0000'`) and literal form-feed chars in
   DocumentTextExtractor.kt/SecretFilter.kt (now `'\u000C'`; Kotlin has no `\f` escape). A final scan
   confirms no NUL/VT/FF bytes remain in any Package A source.

---

# P3 Package B — Port Notes (Knowledge / Languages / Intelligence)

Scope: `knowledge/*` (4 files), `languages/LanguageDetector.kt` + `languages/UiStrings.kt`,
`intelligence/*` (15 files). All ports are full 1:1 mirrors of the C# in
`windows-reference/src/InterviewAssistant.Core/` @4976e16. Nothing in this package was compiled or
test-run locally (no JVM on this VM); CI is the first check. No GitHub push was made.

**Verification method (important):** every C# source in scope was re-read in full during this package
and compared member-by-member against the port. Two files in an early draft (QuestionMatcher,
QuestionExtractor) and one more (UtteranceGate) did not match the real C# shapes and were rewritten
from the actual sources before delivery; the final ports below are the verified ones. A scripted
literal audit extracted every string/char literal from both sides of all 22 production file pairs,
unescaped them, and compared them as multisets: 1,608 C# literals vs 1,600 Kotlin literals, with every
remaining delta explained (XML-doc `<see cref>`/`<paramref>` names counted as C# "literals" by the
extractor, Kotlin `@SerialName` wire names, interpolation-hole normalization, KDoc line-wrap
punctuation, and the Kotlin-only `regexEscape` helper's char literals). All regex patterns, word lists,
thresholds and prompt texts match after unescaping. `PromptTemplates.LiveAnswer` was additionally
compared byte-for-byte under C# raw-string rules: identical, 2,358 chars both sides.
**Defect found in a pre-existing file:** `languages/LanguageDetector.kt` as found on disk was
truncated mid-statement — the file literally ended with a `...[truncated 2314 chars]` marker inside
the `words` split list, and the whole scoring tail + `detectOr` were missing. It was repaired from the
C# source (see the file row below). The parent agent may want to sweep other skeleton-era files
outside this package's scope for the same defect class. A final scan of all 33 Package B files
(production + tests + Fixtures) confirms: no NUL/VT/FF bytes, no truncation markers, and balanced
braces/parens/brackets after stripping strings and comments.

## B1. Per-file port checklist

| C# file (lines) | Kotlin file (lines) | Members |
|---|---|---|
| Knowledge/KnowledgeBase.cs (107) | knowledge/KnowledgeBase.kt (126) | Complete: `KnowledgeSnippet` (@Serializable data class, PascalCase @SerialName as the C# record under STJ defaults — Package A's CoreJson packs depend on it), `KnowledgeBase` class with the exact constructor Package A's `toKnowledgeBase()` calls, companion `load`/`empty`/`splitMarkdown`/`findDirectory`/`snippetFiles`/`loadContext`. |
| Knowledge/KnowledgeModels.cs (120) | knowledge/KnowledgeModels.kt (135) | Complete: `AnswerMode`, `BankQuestion` (+ computed `mode`, `effectiveStoryIds`), `QuestionBankFile`, `CandidateStory` (+ `toEvidenceLine()`), `StoriesFile`, `Experience`, `CandidateProfile` (+ `personalFor`), `PersonalEntry`, `EducationEntry`, `LanguageEntry`. Plain classes (not data classes) — the C# has reference equality and ContextRetriever's story de-dup relies on it. @SerialName = the C#'s snake_case JsonPropertyNames. |
| Knowledge/PromptTemplates.cs (38) | knowledge/PromptTemplates.kt (41) | Complete: `LiveAnswer` byte-identical (2,358 chars, verified by extraction under C# raw-string rules; no trailing newline, exactly as the C#). |
| Knowledge/TargetContext.cs (95) | knowledge/TargetContext.kt (130) | Complete: `TermAlias` (lazy regex), `TargetContext` (all 14 serialized fields + @Transient `employers`, `normalizeTerms`/`buildTermRules`/`buildTranscriptionPrompt`/`transcriptionTerms`). Also hosts internal `regexEscape()` (module-wide .NET `Regex.Escape` mirror). |
| Languages/LanguageDetector.cs (98) | languages/LanguageDetector.kt (100) | Complete: `DetectedLanguage(code, confidence)`, object with `detect` (script shares → stopword/diacritic/suffix scoring, all four "und" exits incl. `best < 1 → ("und", 0.1)`) and `detectOr(text, fallback, threshold = 0.5)`. REPAIRED — see the defect note above; script ranges and the PersianOnly/ArabicOnly code points were re-verified code-point-by-code-point against the C#. |
| Languages/UiStrings.cs (24) | languages/UiStrings.kt (27) | Complete: object delegating to `Loc` — `keys` (44), `languages`, `get(lang?, key)`, `format(lang?, key, args)`, `isComplete(lang)`. |
| Intelligence/AnswerPlanner.cs (220) | intelligence/AnswerPlanner.kt (228) | Complete: `AnswerConfidence`, `PersonalTopic`, `AnswerPlan` (+ `personalWithoutData`, `toPromptSection()`), `AnswerPlanner` object (13 personal rules, `detectPersonal`, `plan`, `personalGuidance`, 10-language `personalTexts` — literal-audited), `QualityReport`, `AnswerQualityGate` (`isRedundant`, `assess`). |
| Intelligence/AnswerStyle.cs (27) | intelligence/AnswerStyle.kt (38) | Complete: `AnswerStyle` enum (C# order), `AnswerStyleSpec.for` (all 7 specs incl. Balanced = exactly 3 bullets, 10–22 words/bullet, 35–75 total, 170 tokens), `maxBullets`. |
| Intelligence/BulletStreamParser.cs (74) | intelligence/BulletStreamParser.kt (76) | Complete: `push`/`complete`/`mode`/`bullets`/`pending`, MODE-line parsing (first MODE line only, peek for pending). |
| Intelligence/ContextRetriever.cs (53) | intelligence/ContextRetriever.kt (73) | Complete: `RetrievedContext`, `retrieve(question, cls, match, maxStories = 3, maxSnippets = 6, storyPenalty = null)` — matched-question evidence first (penalty < 2 gate), category+lexical ranking, snippet jaccard + 0.05/token. |
| Intelligence/EvidenceIndex.cs (64) | intelligence/EvidenceIndex.kt (67) | Complete: ctor items, `count`, `supports(text, coverage = 0.6)`, companion `for(kb)` + `topic(text)` (49-word Weak set, NumberWord regex). |
| Intelligence/FactValidator.cs (155) | intelligence/FactValidator.kt (186) | Complete: `ValidationIssue`, `ValidationReport` (+ `hasFactualIssues`), `validate`, `hasUnsupportedClaim`, companion `normalizeNumber` + `softenClaim` (all 27 verb mappings), NumberRegex/PastClaim/ClauseEnd/EmployerClaim patterns literal-audited. |
| Intelligence/FollowUpIntent.cs (128) | intelligence/FollowUpIntent.kt (140) | Complete: `FollowUpKind`, object with Lead/Frame/Verb/Movement/MovementBare/MovementSpeaking pattern constants (concatenated exactly as the C#), `classify`, `isImperativeAsk`, `isStandaloneFollowUp`, `isVeryShortImperative`. |
| Intelligence/PersonalGrounding.cs (42) | intelligence/PersonalGrounding.kt (43) | Complete: framing set built from `TextNormalizer.tokenize` of the C# framing string, `unsupported`, `isGrounded`, prefix-support rule (≥4 shared, or whole shorter stem ≥3 with length diff ≤1). |
| Intelligence/PromptBuilder.cs (125) | intelligence/PromptBuilder.kt (130) | Complete: the P2 `ChatMessage` declaration preserved byte-identically; `ConversationTurn`, `PromptRequest` (C# field order), `PromptBuilder` (profile digest in init, `languageRule`/`memoryContext`/`extraInstruction` vars, both `build` overloads, `bound(s, max)` = truncate to max + "…" + quotes→apostrophes). |
| Intelligence/PromptEchoDetector.cs (98) | intelligence/PromptEchoDetector.kt (99) | Complete: `EchoResult`, `check` (default evidence = zero evidence), `contextOverlap`, companion `generic`, longest-common-run DP. |
| Intelligence/QuestionClassifier.cs (103) | intelligence/QuestionClassifier.kt (112) | Complete: `Classification`, object with all 23 category rules in C# order, `classify(question, ctx = null, evidence = null)`, `isDirectExperienceQuestion`, evidence/topic logic. |
| Intelligence/QuestionExtractor.cs (195) | intelligence/QuestionExtractor.kt (226) | Complete — REWRITTEN from the real C# (see verification note): `ExtractedQuestion` (10 fields, `plain(q)`, `hasContext` ⇔ contextSummary), object with `sentences`, `extract(text, earlierContext, ctx, gate)` (restart-cue handling, adjacent-question grouping, long-core AskStart trimming, restates-ask filtering, constraint/entity extraction), `restatesAsk(sentence, core)`, and `PendingContext` (turn-id-keyed items, `add(turnId, text, nowMs)`, `take(nowMs): String`, `remove(turnId)`, `count`, `clear`). |
| Intelligence/QuestionMatcher.cs (112) | intelligence/QuestionMatcher.kt (125) | Complete — REWRITTEN from the real C# (see verification note): `MatchConfidence`, `MatchResult(question, score, confidence, runnerUp, runnerUpScore)` in C# positional order, `QuestionMatcher(questions: Iterable<BankQuestion>)` (NOT KnowledgeBase), BM25 over canonical+variants+stemmed keywords blended 50/50 with best-phrasing token/trigram jaccard ×1.35, thresholds 0.62/0.42/0.25, ambiguity downgrade (< 0.03 gap) and `topicConsistent` via classifier categories + BroadCategories. |
| Intelligence/SpeechPlausibility.cs (80) | intelligence/SpeechPlausibility.kt (87) | Complete: `AcousticSupport` enum, object with consts 35/70/9.0/2, `assess`, `syllables` (CJK ranges U+3040–9FFF + U+AC00–D7AF, Latin vowel-group rules incl. silent-e/-es/-ed). |
| Intelligence/TextNormalizer.cs (118) | intelligence/TextNormalizer.kt (129) | Complete: `cleanTranscript` (filler regex + 7 term fixes + alias application), `tokenize` (ASCII `[a-z0-9][a-z0-9-]*` + 65 stop words), `stem`, `fingerprint`, `charTrigrams`, `jaccard`, question-mark helpers, `isLatinScript`, `wordCount`, `stripBullet`. Also hosts internal `dotNetRound2` (see DC-24). |
| Intelligence/UtteranceGate.cs (322) | intelligence/UtteranceGate.kt (350) | Complete — REWRITTEN from the real C# (see verification note): `UtteranceKind` (12, C# order), `UtteranceVerdict` (+ `answerable`, `notSpeech`), class ctor `(echo, candidateNames = null, knownTerms = null)` (names split on space/hyphen, filtered, OrdinalIgnoreCase-equivalent sets), `echo` property, `evaluateSegment`, `evaluate` (artefact → acoustic switch → echo → very-short-imperative → per-sentence logistics/addressee/question loop), `addressedTo` (HandOver/LeadingVocative/TrailingVocative/BareTrailingName named-group regexes), companion `for(kb)`, `isEllipticalFollowUp`, `isEllipticalAsk`, `isQuestionSentence`, `notClearlySpeech(text, evidence, support): String?`, `maxVoicedMsPerShortSyllable = 350`, ~170-entry NotNames set. |

Test support: `Fixtures.kt` (Package A's file) was EXTENDED, not duplicated: `knowledgeFiles` (12 names),
`knowledgeDir()` (materializes the classpath `knowledge/` resources into a temp dir once),
`testKnowledge()` (cached `KnowledgeBase.load`, the mirror of C# `TestKnowledge.Load()`),
`installLocTables()` (wires `Loc.tableLoader` to the real i18n assets in `:core`, same candidate-path
pattern as the `:client` tests), `i18nAssetText(code)`.

## B2. Test mapping

51 JUnit test methods written. xUnit theories became case loops inside one JUnit test (P2 pattern);
no test in this package observes a faulted Task, so no supervisorScope case arose (DC-9 not triggered).

| C# file | Kotlin file | Ported | Deferred |
|---|---|---|---|
| KnowledgeTests.cs (7 facts) | knowledge/KnowledgeTests.kt | 7/7 (incl. the full-bank quality/fact evaluation and the ≥80-utterance file check) | — |
| LanguageTests.cs (LanguageTests 5 methods/16 cases + UiStringsTests 4 methods) | languages/LanguageTests.kt | 9/9 methods | — |
| LocalizationCoverageTests.cs (10 methods) | languages/LocalizationCoverageTests.kt | 8 methods: NoTableDefinesAKeyTwice (over the 10 Android i18n assets), EnglishTableIsTheReference, EverySupportedLanguageIsComplete (9 languages, incl. placeholder-multiset equality + SameInEveryLanguage/Cognates untranslated detection with HasEnglishWords copied exactly), RightToLeftLanguagesAreRegisteredAsRtl (2), LiveLanguageSwitchRaisesAnEventAndChangesText, MessagesAlreadyOnScreen_FollowALanguageSwitch, UiLanguageNeverChangesTheAnswerLanguage, and the dynamic-key half of EveryKeyUsedInTheAppExistsInEnglish as `everyDynamicKeyExistsInEnglish` (fact_kind_* per FactKind, pc_/pcs_ per PersonalCategories.all, audio_* ×5, font_* ×4) | XamlHasNoHardCodedUserText → App/UI package; DesktopCodeHasNoHardCodedUserText → App/UI package; the source-scan half of EveryKeyUsedInTheAppExistsInEnglish → App/UI package (no Android UI source tree exists to scan yet) |
| L10nDateAndBidiTests.cs (11 methods) | languages/L10nDateAndBidiTests.kt | 4 methods, adapted to the Kotlin registry surface (see DC-21): ArabicFormattingCulture_IsGregorian, PersianFormattingCulture_KeepsItsCalendar, FormattingCulture_OtherLanguagesUseTheirRegisteredCulture, ArabicUiFormatting_EverywhereUsesTheGregorianCalendar (behavioral core: Loc.locale is the registry formatting locale) | ExportDates_HaveNoWeekday (5 cases) → Reports; ExportDates_HaveNoWeekday_InAnyLanguage (10 cases) → Reports; ExportDates_ReadInsideTheSentence → Reports; ExportDates_KeepTheCulturesOwnOrder → Reports; LongDatePattern_LosesOnlyTheWeekday (7 cases) → Reports (all need ExportLabels); AccountPage_IsolatesVersionsAndEmailsInLocalizedSentences → App/UI (Windows source scan); the Loc.cs source-text half of ArabicUiFormatting… → App/UI |
| ClassifierAndTextTests.cs (9 methods) | intelligence/ClassifierAndTextTests.kt | 9/9 (ModeIsClassified ×7, CategoryIsClassified ×4, FollowUpsDetected ×3, TranscriptCleanupFixesDomainTerms ×3, WithoutTargetAliases…, Fingerprint…, BulletParser…, ValidatorFlags…, ValidatorAccepts…) | — |
| MatcherTests.cs (5 methods) | intelligence/MatcherTests.kt | 5/5 (canonical self-match, paraphrase top-1 ≥ 0.9 + wrong-high ≤ 2 over test_questions.json, unexpected-not-from-cache, KeyQuestionsMatch ×5, MatchingIsFast < 15 ms/match) | — |
| HeldOutMatcherTests.cs (1 method, 30 cases) | intelligence/HeldOutMatcherTests.kt | 1/1, case table extracted verbatim from the C# source by script | — |
| MatcherEvaluationTests.cs (1 method, 55 cases) | intelligence/MatcherEvaluationTests.kt | 1/1 (43 known + 12 novel), case table extracted verbatim from the C# source by script | — |
| NonLatinQuestionTests.cs (3 methods) | intelligence/NonLatinQuestionTests.kt | 2 methods: QuestionMarkOfAnyScript_IsAQuestion (5 cases — the UtteranceGate.isQuestionSentence + TextNormalizer.endsWithQuestionMark assertions), ChineseContextThenQuestion_SplitsIntoSentences (full) | the TurnDetector.IsNonQuestion assertion inside QuestionMarkOfAnyScript_IsAQuestion → Turn package; Engine_AnswersANonLatinQuestion (3 cases) → Orchestration |
| PersonalGroundingTests.cs (6 methods) + FactCountSemanticsTests (2 methods) | intelligence/PersonalGroundingTests.kt | 5 methods: Rephrasing_TheConfirmedFact_IsGrounded (×5), AddedTraitsFeelingsBenefitsOrDetails_AreNotGrounded (×6), Motivation_MayBeRephrased_NotEmbellished, AddingAHobby_IsCountedAsAnInterviewProfileAnswer_NotAsAnExperienceFact, SplitCountKeys_HaveTheirPlaceholdersInEveryLanguage | Engine_DropsInventedPersonalBullets_AndFallsBackToTheConfirmedWords → Orchestration; Engine_FactsInAnotherLanguage_AreNotComparedWordForWord → Orchestration; Engine_KeepsAGroundedRephrasing_AndDropsOnlyTheEmbellishment → Orchestration (all InterviewSimulator) |
| LiveIntelligenceTests.cs (15 methods) | — | 0 — nothing in the file depends solely on Package B's surface (all use InterviewEngine / SessionMemory / CoachOutput / InterruptionTracker) | ALL 15 → Orchestration/Live: CoachModeFromCacheIsImmediateAndHonoursContract, CoachModeViaAiParsesContractAndFallsBackWhenMalformed, CoachToggleSwitchesModes, CoachParserWorksForAnyScript, FollowUpsAreLinkedAndMemoryReachesThePrompt, RepeatedStoryIsAvoidedAndAlreadyMentionedIsRecognised, StoryPenaltyRanksLeastUsedFirst (SessionMemory), MemoryStaysBoundedOverLongInterviewAndSerializes, MemorySurvivesRestoreAfterDisconnect, InterruptionsShortenAnswersAndResetRestoresNormal, TypedQuestionsAndBareSpeechStartsNeverChangeThePace, BeepsAndVariantsNeverChangeThePace, CalmPaceIsNotAnInterruptionAndAdaptiveDecays, RapidModeTrimsCachedAnswersToTwoBullets, AnswersFollowTheInterviewerLanguageAndMemorySurvivesSwitches |
| LongContextRegressionTests.cs (4 theory methods) | — | 0 — all InterviewSimulator-driven | ALL 4 → Orchestration: Background_ThenTwoPartQuestion_OneAnswerWithBothPartsAndTheConstraints, Background_ThenRephrasedAsk_TheRestatedAskWins, Background_InterruptedByAnAside_StillOneAnswerForTheRealQuestion, Background_QuestionThenQuickContinuation_IsOneQuestion |
| AnswerQualityEvaluationTests.cs (2 facts) | — | 0 — both are adversarial-stub engine evaluations | BOTH → Orchestration: AdversarialModelOutput_IsCleanedBeforeTheCandidateSeesIt, PersonalQuestions_UseConfirmedProfileAnswersOrHonestGuidance_NeverInventions |

## B3. Divergence candidates (continuing Package A's numbering)

- **DC-17 — KnowledgeBase.Load JSON tolerance.** The C# loads with
  `JsonSerializerOptions { ReadCommentHandling = Skip, AllowTrailingCommas = true }`. kotlinx has no
  equivalent for either; the port's `Json { ignoreUnknownKeys = true }` rejects comments and trailing
  commas in knowledge JSON files. Only hand-edited knowledge files would notice.
- **DC-18 — DirectoryNotFoundException → FileNotFoundException.** `KnowledgeBase.load` throws
  `java.io.FileNotFoundException` with the identical message ("Knowledge directory not found: …");
  both are IOExceptions, but the concrete type differs from .NET's.
- **DC-19 — Prompt line endings.** `PromptBuilder`/`AnswerPlan.toPromptSection` build with Kotlin
  `appendLine` (`\n`); .NET `StringBuilder.AppendLine` emits `Environment.NewLine` (`\r\n` on the
  Windows build, `\n` on Linux .NET). Prompt text therefore differs from the Windows build by line
  endings only.
- **DC-20 — Matcher tie ordering.** The C#'s `List.Sort` is an unstable introsort; Kotlin's
  `sortedByDescending` is stable. When two bank questions score bit-identically, best/runner-up order
  can differ from Windows. (ContextRetriever's `OrderByDescending` rankings have the same property;
  LINQ's OrderByDescending is stable, Kotlin's is too — those match.)
- **DC-21 — L10n calendar surface (extends P2's DC-5).** A JVM `Locale` carries no calendar, so the
  adapted L10n tests assert the Gregorian/Persian facts through the registry's own
  `usesGregorianCalendar` flag + registered culture names instead of .NET `Calendar` types. Actual
  date *rendering* (Reports/ExportLabels) must revisit this when ported.
- **DC-22 — ContextRetriever duplicate story ids.** The C#'s `ToDictionary` throws on duplicate story
  ids at retriever construction; Kotlin's `associateBy` keeps the last story silently.
- **DC-23 — FactValidator ClauseBoundary DOTALL.** That one C# regex is `RegexOptions.Singleline`;
  the shared `dotNetRegex` helper has no DOTALL option, so this single pattern is compiled directly
  via `java.util.regex.Pattern` with `DOTALL | UNICODE_CHARACTER_CLASS` (+ case flags). Semantics
  equal for this pattern; flagged so the exception is visible.
- **DC-24 — dotNetRound2 midpoint rule.** .NET `Math.Round(x, 2)` rounds the *binary* double to even;
  the port's `BigDecimal.valueOf(x).setScale(2, HALF_EVEN)` rounds the double's canonical decimal
  string. Results can differ on exact decimal midpoints that are not binary midpoints (classic
  2.675-style cases) in `AnswerPlanner` relevance and `PromptEchoDetector` coverage.
- **DC-25 — Capitalize.** QuestionExtractor's `Capitalize` and TextNormalizer's first-letter raise use
  Kotlin `Char.uppercaseChar()` (Unicode default mapping) vs .NET `char.ToUpperInvariant`
  (culture-invariant mapping). They agree on all letters in the supported languages; exotic one-to-one
  case pairs could differ.
- **DC-26 — KnowledgeBase.Load reads the profile once.** The C# reads `candidate_profile.json` twice
  (profile + the copy passed to LoadContext); the port reads once and reuses the instance. Observable
  only if the file changes mid-load.
- **DC-27 — notes/*.md enumeration order.** Snippet order from a `notes/` subdirectory follows
  filesystem enumeration on both platforms (C# `Directory.GetFiles`, Kotlin `listFiles`) and is not
  sorted in either; cross-platform order may differ. The golden set has no `notes/` directory.
- **DC-28 — Null-text entry points.** Several C# statics accept null (`FollowUpIntent.Classify`,
  `UtteranceGate.IsEllipticalAsk`, `SpeechPlausibility.Syllables`) via `?? ""`; the Kotlin parameters
  are non-nullable. No in-repo caller passes null; external callers must pass "".
- **DC-29 — Gate name-set casing.** The C#'s candidate-name/known-term sets are OrdinalIgnoreCase;
  the port stores them lowercase-normalized and lowercases lookups. Equivalent for all names/terms the
  product produces; a term containing characters whose lowercase mapping is one-to-many (e.g. 'İ')
  would compare differently.

## B4. Fixtures copied (byte-identical, SHA-256 verified after copy)

Golden sample set `windows-reference/samples/shervin-teroxx/` → `core/src/test/resources/knowledge/`
(the C# tests load the same set from their output `knowledge/` directory):

| File | SHA-256 |
|---|---|
| answer_policy.md | c38481f75c6c5a4c9637464fc25a7a853e170d2f6747dc5565eed7a22cc33884 |
| candidate_profile.json | e798097c48be7b1877db48140abf2c35e1ec062f3a96e4814785db07ca978aba |
| candidate_stories.json | 8ea06ca2d0ac79b8d5dc313a24e75dc5ce08c3c52fd9dd2da06da195a74e85f3 |
| company_brief.md | f4c5afd9124e5e3b67e3d02c321bcd102156cfc140d6cb2513a89d56d069e936 |
| crypto_fintech_playbook.md | 627ba8f79d5eed412915a2d1a66ea5725401e893747cc1503bcebd94f3eaf993 |
| product_brief.md | b25d61338618eb961b7ee60d814943134744789e3beb19130ea108bf7d84979a |
| product_owner_playbook.md | 99a7d24583e0ea160075cef7be9461f0ef4226353c9dbad3417d1583dded8b4a |
| question_bank.json | a42c2065dde4cc7a827eeecc394c63ee90fbc3aa567fd349bc47f945cfe2bc2a |
| role_brief.md | 4f6302795fdbae7bc84a7ed760ef8f9f597d7755cdae84b4c5053bc37c7e3310 |
| target_context.json | 16639db5556f9d36d106135600db870968ab5c7685787bef663c72abbae97d0f |
| test_questions.json | d50cc70596d9f6ac6dd2b6fb65391ae87ceaa272a24b53c0b01f4b0064c2575f |
| whitepaper_notes.md | 06bc7809984f7e81dc3b05daec68d3043822545090529681ecc5755d246fa178 |

The i18n tables were NOT copied: the tests read the real byte-identical assets already in
`core/src/main/assets/i18n/` through `Loc.tableLoader` (same as the `:client` tests).

## B5. Package A call-site adjustments

**None.** Every shape Package A assumed was confirmed against the real C# and ported exactly:
`KnowledgeBase(profile=, stories=, questions=, snippets=, systemPromptTemplate=, context=, directory=)`
named-argument construction in `PreparedPack.toKnowledgeBase()`; `TargetContext` with
`profileId`/`targetId` and a settable `employers`; `PromptTemplates.LiveAnswer`;
`LanguageDetector.detectOr(text, fallback, threshold)` as an object (Package A calls it with
threshold 0.4); the camelCase constructors of `CandidateProfile`/`Experience`/`EducationEntry`/
`LanguageEntry`/`PersonalEntry`/`CandidateStory`/`BankQuestion`; and `KnowledgeSnippet` with the
PascalCase wire names Package A's CoreJson packs serialize. No Package A file was edited by this
package (only its test-side `Fixtures.kt` was extended, as instructed).

## B6. Honest compile-risk notes

1. **`SpeechEvidence` does not exist yet (highest risk).** `audio/AcousticAnalyzer.kt` is still a P1
   placeholder owned by the Audio package, so `:core` cannot compile until that package lands
   `com.interviewassistant.core.audio.SpeechEvidence`. Package B codes against the exact C# contract
   (AcousticAnalyzer.cs:387–497), which the Audio port must reproduce for this package to compile and
   behave identically: constructor `(framesObserved, spanMs, activeMs, voicedMs, tonalMs,
   pitchPairs = 0, steadyPairs = 0, jumpPairs = 0, windowMs = 0, trackedHops = 0, runHops = 0,
   flatRunHops = 0, stretchHops = 0)`; computed `unknown` (framesObserved == 0), `incomplete`
   (!unknown && windowMs > 0 && spanMs*4 < windowMs*3), `hasSpeech` (!unknown && voicedMs >= 100),
   `isShortNonSpeechEvent`, `tonalShare`, `pitchSteadiness`, `pitchJumpShare`, `pitchTracked`
   (trackedHops > 0), `flatRunShare`, `heldTone` (run-judged flat-share rule), `voiceLike`
   (hasSpeech && !tooTonal && !heldTone), `tooTonal` (tonalMs >= 100 && tonalShare >= 0.2),
   `pitchJudged` (pitchTracked ? runHops >= 10 : pitchPairs >= 10), `sustainedVoicing`
   (stretchHops > 0), `stretchMeasured` (pitchTracked && stretchHops >= 0), `notVoiceReason`;
   constants `MinVoicedMs = 100`, `ShortEventMs = 1500`, `MaxTonalShare = 0.2`, `MinTonalMs = 100`,
   `MaxPitchSteadiness = 0.65`, `MinPitchPairs = 10`, `MinRunHops = 10`, `MaxFlatRunShare = 0.75`,
   `SteadyWithFlatRuns = 0.65`, `MinFlatRunShareWhenSteady = 0.5`, `UnheardWindowMs = 600_000`;
   `Unheard = SpeechEvidence(0,0,0,0,0, windowMs = 600_000)`; a `plus` operator; and the C#
   `ToString()` format ("acoustics: n/a" when unknown, else "voiced {v} ms, active {a} ms, tonal {t}
   ms over {span} ms" + optional pitch/flat-run/stretch suffixes with percent formatting) — the
   gate's reason strings interpolate it verbatim. Members Package B actually calls: unknown,
   voiceLike, incomplete, hasSpeech, voicedMs, activeMs, notVoiceReason, pitchTracked,
   stretchMeasured, sustainedVoicing, pitchJudged, ShortEventMs, and the zero-evidence default
   `SpeechEvidence(0, 0, 0, 0, 0)`.
2. **SplitCountKeys digit risk.** `FactCountSemanticsTests.splitCountKeys…` asserts the formatted
   counts contain Latin digits "44"/"3"/"1" in all 10 languages, as the C# asserts (and passes on
   Windows with .NET formatting). JVM `NumberFormat` for ar/fa locales may emit Arabic-Indic digits
   through P2's `Loc.format`. If CI fails there, the defect is in Loc's number formatting (P2
   surface), not in these tests — they were ported faithfully, not weakened.
3. **L10n adapted assertions depend on P2 registry behavior:** `formattingLocale("xx")` falling back
   to en-US, `formattingLocale` returning cached instances (assertSame), and culture names
   round-tripping through `Locale.toLanguageTag()` (e.g. "fa-IR"). All match the P2 code as read;
   CI is the check.
4. **MatchingIsFast on CI hardware.** The C#'s < 15 ms/match bound is kept verbatim (with the same
   warm-up call); shared CI runners are slower than the dev machine — a borderline failure there is a
   timing-environment signal, not necessarily a port defect.
5. **Named regex groups on the JVM.** UtteranceGate's vocative/hand-over patterns use `(?<name>…)`
   groups and one `(?i:…)` scoped-flag group — both are supported by `java.util.regex`, and the
   patterns are compiled through the shared `dotNetRegex` helper; flagged only because they are the
   first named-group patterns in `:core`.
6. **QuestionExtractor ↔ UtteranceGate cycle.** `extract` calls the gate instance + companion
   statics; `evaluate` calls `QuestionExtractor.sentences`. Same module, legal in Kotlin, but the
   pair must land together.
7. **Warnings expected (not errors):** a few unused imports in test files; `endIdx` in
   `QuestionExtractor.extract` is assigned-but-effectively-final in one branch (kept to mirror the C#
   control flow).

---

# P3 Package C — Port Notes (Turn / Live / Preparation / Audio / Diagnostics)

Ported 2026-11-19 from the Windows baseline `4976e16`. All 13 production files are full 1:1 ports
replacing the P1 skeletons; no TODO/placeholder bodies, no dropped members. Nothing in this package
has been compiled or run locally (no JVM on this VM) — GitHub Actions is the first check, exactly as
for Packages A/B. Zero new external dependencies (only `java.time`, `java.io`, `java.nio`,
`java.security`, `java.util.concurrent`, and the already-present kotlinx-coroutines/serialization).

## C1. Per-file port checklist

| Kotlin file | C# source | C# lines → Kotlin lines | Members |
|---|---|---:|---|
| `diagnostics/Clock.kt` | `Diagnostics/Clock.cs` | 19 → 24 | `IClock` (interface, `nowMs`), `SystemClock` (nanoTime-based, monotonic), `ManualClock` (`nowMs` var + `advance`). Complete. |
| `diagnostics/Backoff.kt` | `Diagnostics/Backoff.cs` | 26 → 33 | `ExponentialBackoff(initial: Duration? = null, max: Duration? = null)`, `attempt` (private set), `next()`, `reset()`. `TimeSpan` → `java.time.Duration`. Jitter formula and 2^min(attempt,10) cap verbatim. Complete. |
| `diagnostics/Metrics.kt` | `Diagnostics/Metrics.cs` | 110 → 241 | `LatencySample` data class (9 fields, `at: OffsetDateTime`); `SessionMetrics(capacity: Int = 200)`: all 24 counters (AtomicInteger, the Interlocked analog), `audioMsSent`/`approxLlmInputChars`/`approxLlmOutputChars` plain vars (C# public fields), all 24 `inc…()` methods, `add`/`samples` (ConcurrentLinkedQueue trimmed to capacity), `suppression` (12 keys, C# insertion order), `totalSuppressed`, `estimatedCostUsd`, companion `percentile` (ceil-index rule, −1 when empty). Complete. |
| `turn/ConversationMemory.kt` | `Turn/ConversationMemory.cs` | 22 → 24 | `capacity` ctor param (init-only in C#), `add` (first 3 bullets, 110-char truncation + "…"), `snapshot`, `clear`, `count`. Complete. |
| `turn/DuplicateGuard.kt` | `Turn/DuplicateGuard.cs` | 51 → 60 | ctor params `capacity = 12`, `windowMs = 30_000`, `similarityThreshold = 0.8`; `isDuplicate(question, nowMs, scope)` with the jaccard + containment (≥ 0.85, size-diff ≤ 1) rules; `register` (newest first, bounded); `suppressedCount`; `clear`. Scope semantics verbatim. Complete. |
| `turn/TurnDetector.kt` | `Turn/TurnDetector.cs` | 482 → 560 | `TurnState` enum (6 members), `FinalizedTurn` data class (8 fields, C# defaults incl. `evidence = SpeechEvidence(0,0,0,0,0)`, `speechStartMs = -1`); all settable knobs with exact defaults (sensitivity 1.0, questionCompleteMs **600**, defaultMs **800**, incompleteMs **1700**, missingTranscriptTimeoutMs 4000, maxSegmentsPerTurn 400, maxTurnChars 8000, maxPartialChars 4000, continuationWindowMs 2500, followUpReplyAllowanceMs 1800, displayTailChars Int.MAX_VALUE); `aliases`, `segmentFilter`, `partialFilter`, `termNormalizer`, `turnEvidence`, `hasTrustedText`, `droppedSegments`; `start/stop/reset/onSpeechStarted/onSpeechStopped/onPartial/onSegmentCompleted/onConnectionInterrupted/tick/adaptiveDelayMs/currentText/displayText/forget/inContinuationWindow`; finalize path incl. follow-up split, restart-cue stripping, restatement merge, evidence summing; events → listener lists (`addStateChangedListener`, `addLiveTextChangedListener`, `addTurnFinalizedListener`, `addFollowUpSplitListener`); companion `isNonQuestion` + the 8 regexes. Settle timings exact. Complete. |
| `audio/PcmConverter.kt` | `Audio/PcmConverter.cs` | 144 → 158 | `SampleEncoding` enum, `SourceFormat` data class (`bytesPerSample`, `blockAlign`), `PcmConverter` (`TargetRate = 24000` const; ctor validation "Invalid source format"; streaming `convert` with partial-frame join, box-filter downsample / linear interpolation for ratio < 1, half-even sample rounding via `Math.rint`), companion `levelDb` (−100 floor, 1e-5 silence threshold), `AudioFramer` (100 ms default; returns the complete frames eagerly — C# is a lazy `IEnumerable`, content identical). Complete. |
| `audio/AcousticAnalyzer.kt` | `Audio/AcousticAnalyzer.cs` | 540 → 707 | All four C# types: `FrameAcoustics` data class + `plus`; `PitchRunTracker` (consts MinRunWindows 5 / MinStretchWindows 8 / FlatSpan 0.02, private JumpPerHop 0.025; `track` internal, `openRun`, `reset`; synchronized); `AcousticAnalyzer` object (public consts SampleRate 24000, ActiveDbfs −50, VoicedCorrelation 0.5, TonalConcentration 0.97, SustainedSimilarity 0.95, SteadyPitchDelta 0.006, PitchJumpDelta 0.025; 12 kHz decimation, 20 ms hop / 40 ms window, MinLag 30 / MaxLag 150, FFT 512, MinShortLag 5, ShortPeriodMargin 0.02; ThreadStatic scratch → ThreadLocal); `SpeechEvidence` (see §C1.1); `SpeechEvidenceTimeline` (ring, default capacity 1800, `sum` over [fromMs, toMs]). Complete. |
| `live/CoachOutput.kt` | `Live/CoachOutput.cs` | 41 → 59 | `Presentation` enum (Answer, Coach); `CoachOutput` data class (`keywordLine`, `isValid` — exactly 3 keywords of 1–32 chars, structure contains '→' and ≤ 90 chars); companion `parse` (label regex, keyword split `[·•|,،、]`, arrow normalization, letter-containing keywords uppercased) and `fromPrepared` (pads with "RESULT" using the FULL keyword count, default structure). Complete. |
| `live/CoachPrompt.kt` | `Live/CoachPrompt.cs` | 45 → 56 | `build` (8-line system prompt verbatim, `\n` joins per DC-19; MEMORY/EVIDENCE/MODE/QUESTION user part) + `keywords` fallback (word regex, OrdinalIgnoreCase stop set, uppercase-first then length ranking, GOAL/APPROACH/RESULT fill). Complete. |
| `live/InterruptionTracker.kt` | `Live/InterruptionTracker.cs` | 65 → 104 | `AdaptiveLevel` enum; `SpeakingWordsPerSecond = 2.4` const; `level`/`manuallyLocked` (private set); `answerShown`; `interviewerSpeechStarted` (expected = words/2.4·1000 + 1500, calm at ≥ 0.85·expected, confidence clamp + 0.15 repeat bonus, 0.35 floor, Rapid at ≥ 2 events in 5 min, note strings verbatim, confidence via `dotNetRound2` per DC-24); `reset` (does NOT clear the shown answer — mirrored), `lock`; LevelChanged → listener list. `InterruptionEvent` itself lives in SessionMemory.kt, as in the C# layout. Complete. |
| `live/SessionMemory.kt` | `Live/SessionMemory.cs` | 206 → 240 | `QuestionRecord`, `HeardEvidence`, `InterruptionEvent`, `SessionMemory` as @Serializable classes, @SerialName PascalCase in C# declaration order, CoreJson + CoreDateTimeSerializer (Package A pattern); `HeardEvidence.from/toEvidence` (positional order per C# ctor); `add` (index assignment, story/metric/topic counting), `completeAnswer` (LINQ Except snapshot semantics), `recentlyUsedStories`, `overusedMetrics`, `storyPenalty`, `buildPromptContext`, `compact` (12 detailed / 400 records / 200 interruptions bounds, rolling summary format verbatim), `toJson`/`fromJson` (JSON "null" → new instance, as C#), companion `referencesEarlier` (544-char multilingual regex, verified joined-equal) + `intentKeyFor`. Complete. |
| `preparation/PreparationPipeline.kt` | `Preparation/PreparationPipeline.cs` | 217 → 295 | `PreparationProgress` data class; `IPreparationEnricher` (`suspend fun enrichAsync(pack, progress)` — CancellationToken maps to coroutine cancellation); `GenericLibraryItem` + private `LibFile` (@SerialName snake_case); `PreparationAssets` (`LibraryFileName` const, `load`/`loadShipped` via KnowledgeBase.findDirectory/splitMarkdown); `PreparationPipeline.runAsync` — all 15 stages with verbatim detail strings and progress reporting (stage+1, 15, name, detail), match map (0.25 gap threshold), terminology (OrdinalIgnoreCase distinct, 60 cap), products filter, story ranking, truth-policy string, pack assembly; enricher failure filter (ProviderException or IOException → warning, else rethrow); companion `GeneratorVersion = "local-2.0"`, `stages` (15 names verbatim), `detectDomains`, `keyPhrase` (all three regexes verbatim). Complete. |
| `preparation/QuestionFactory.kt` | `Preparation/QuestionFactory.cs` | 370 → 529 | `internal class QuestionFactory` — all sections (Core incl. all 8 fixed questions with verbatim bullets/coach/structure, BehaviouralSet 11 topics verbatim + failure question, PerRole ≤ 6 roles × ≤ 2 achievements, Requirements ≤ 30 with Verified/Bridge/CASE_STUDY triplets, Responsibilities ≤ 20, library pass-through at 0.85), adaptive bank (fingerprint dedup, 150 cap by confidence, `T{n:000}` renumber copying all 19 fields), `questionsToAsk` (Insert(2) semantics — later responsibilities land earlier), `positioning`, and every helper (Speak/Clause/ShortenClauses with the exact separator list + digit-match rule, ContentKeywords with uppercase weight 2 and GOAL/APPROACH/MEASURE fill, VerbPhrase map + base-verb regex, KeyPhraseShort/Trim/FirstWord/Upper/Lower/Cap/Article/Join/StartYear). Complete. |

### C1.1 SpeechEvidence vs the §B6.1 contract — point by point

- Constructor `(framesObserved, spanMs, activeMs, voicedMs, tonalMs, pitchPairs = 0, steadyPairs = 0, jumpPairs = 0, windowMs = 0, trackedHops = 0, runHops = 0, flatRunHops = 0, stretchHops = 0)` — exact names/order/defaults; implemented as a Kotlin **data class** (Package C's Rc5 tests need `copy(...)`, as the C# tests use `with`). ✓
- Computed: `unknown` (framesObserved == 0), `incomplete` (!unknown && windowMs > 0 && spanMs·4 < windowMs·3), `hasSpeech` (!unknown && voicedMs ≥ 100), `isShortNonSpeechEvent`, `tonalShare`, `pitchSteadiness`, `pitchJumpShare`, `pitchTracked` (trackedHops > 0), `flatRunShare`, `heldTone` (runs-judged rule: pitchTracked && runHops ≥ 10 → flatRunShare ≥ 0.75 || (pitchPairs ≥ 10 && steadiness ≥ 0.65 && flatRunShare ≥ 0.5); else pitchPairs ≥ 10 && steadiness ≥ 0.65), `voiceLike` (hasSpeech && !tooTonal && !heldTone), `tooTonal` (tonalMs ≥ 100 && tonalShare ≥ 0.2), `pitchJudged` (tracked ? runHops ≥ 10 : pitchPairs ≥ 10), `sustainedVoicing` (stretchHops > 0), `stretchMeasured` (pitchTracked && stretchHops ≥ 0), `notVoiceReason` ("no voiced sound" / "tonal {P0}" / runs-judged ? "held pitch {flatRunShare:P0}" : "steady pitch {pitchSteadiness:P0}" / ""). ✓
- Constants keep the C# PascalCase names in the companion (Package B's UtteranceGate already calls `SpeechEvidence.ShortEventMs`): MinVoicedMs 100, ShortEventMs 1500, MaxTonalShare 0.2, MinTonalMs 100, MaxPitchSteadiness 0.65, MinPitchPairs 10, MinRunHops 10, MaxFlatRunShare 0.75, SteadyWithFlatRuns 0.65, MinFlatRunShareWhenSteady 0.5, UnheardWindowMs 600_000; `Unheard = SpeechEvidence(0,0,0,0,0, windowMs = 600_000)`. ✓
- `plus` operator: component-wise in constructor order (windowMs summed too). ✓
- `toString()`: "acoustics: n/a" when unknown; else "voiced {v} ms, active {a} ms, tonal {t} ms over {span} ms" + ", pitch steady {P0} / jumps {P0} of {pairs}" (pitchPairs > 0) + ", flat runs {P0} of {runHops}" (runHops > 0) + ", stretches {n}" (stretchHops > 0). {P0} rendering: see DC-30. ✓
- Members Package B calls (unknown, voiceLike, incomplete, hasSpeech, voicedMs, activeMs, notVoiceReason, pitchTracked, stretchMeasured, sustainedVoicing, pitchJudged, ShortEventMs, zero-evidence default ctor) all exist with those exact names. ✓

### C1.2 Regex parity check (scripted)

Extract + unescape + diff over the package's file pairs: **30 Kotlin regex patterns** (all via the
shared `dotNetRegex` helper). 29 matched a C# literal in the corresponding file exactly;
`SessionMemory.AlreadyMentioned` (a 3-part concatenated literal on both sides) was verified by an
explicit join comparison — EQUAL, 544 chars. The check caught one real defect, fixed before delivery:
TurnDetector's sentence-split lookbehind class `[.?!？؟]` must be U+FF1F **followed by U+061F** (Arabic
؟) — the first Kotlin draft had U+FF1F twice (the glyphs are visually identical). The same
FF1F/061F confusion was found and fixed in `isCompleteAsk`'s terminal-punctuation string
(`.?!？？。！` = FF1F, 061F, U+3002, U+FF01). After the fixes both patterns compare EQUAL codepoint by
codepoint. All non-Latin test strings (Multilingual table, NonLatin cases) were likewise set-compared
against the C# test files: exact match.

## C2. Test mapping

Test support: `core/src/test/.../Fixtures.kt` extended (not duplicated) with `bytes()`, `assets()`
(mirror of C# `Fixtures.Assets` — the shipped `src/main/assets/knowledge/` materialized to a temp dir),
`shervinPack()` (mirror of `ShervinPackAsync`), `audioFixtureDir()` (mirror of `Fixtures.Path("audio")`).

| C# test file | Kotlin file | Ported | Deferred |
|---|---|---|---|
| AudioTests.cs (4 methods, 7 cases incl. 4-case theory) | `audio/AudioTests.kt` | 4/4 methods, 7/7 cases | — |
| AcousticAnalyzerTests.cs (8 methods, 13 cases) | `audio/AcousticAnalyzerTests.kt` | 8/8 methods, 13/13 cases (incl. the perf budget test and the timeline ring test) | — |
| SyntheticAudio.cs (test support) | `audio/SyntheticAudio.kt` | Full port incl. `DotNetRandom`, an exact reproduction of .NET's seeded (legacy subtractive) Random so synthetic signals are bit-identical | — |
| RecordedStyleAudioTests.cs (9 methods) | `audio/RecordedStyleAudioTests.kt` | 4/4 portable methods: Fixtures_AreReproducibleDocumentedAndWithinBudget, AcousticAnalyzer_ClassifiesEveryFixture, ShortNonSpeechSounds_AreShortAcousticEvents, SungMelody_LooksVoiced_ButItsTypicalTranscriptsAreStillDropped; plus the `AudioFixture`/`AudioFixtures` helper and the static `Evidence(pcm)` helper (AudioFramer + PitchRunTracker, windowMs = 0, span = duration − 100) | 5 → Package D (below) |
| TurnDetectorTests.cs (9 methods, 13 cases) | `turn/TurnDetectorTests.kt` | 9/9 methods, 13/13 cases + the deferred IsNonQuestion theory (below) | — |
| ShortTurnTests.cs (4 classes, 13 methods, 60 cases) | `turn/ShortTurnTests.kt` | All 4 classes (ShortTurnTests, DuplicateContextTests, ImperativeAskTests, SyllableEstimateTests), 60/60 cases | — |
| NonLatinQuestionTests.cs — TurnDetector half of QuestionMarkOfAnyScript_IsAQuestion (deferred BY NAME by Package B) | `turn/TurnDetectorTests.kt` → `questionMarkOfAnyScript_IsAQuestion` | 5/5 cases (Chinese ×2, Arabic, Persian, German), TurnDetector assertion only (B ported the gate/normalizer assertions) | — |
| Rc5FollowUpTests.cs (16 methods) | `live/Rc5FollowUpTests.kt` | 9 methods: Positive_IsACandidateDirectedAsk (18 cases), ImperativeFollowUp_IsItsOwnKind (10), FullImperativeQuestion_IsAQuestion (3), SameVerbs_NotAnAsk (7), Panel_FollowUpToAnotherInterviewer_IsOtherDirected (4), Panel_FollowUpToTheCandidate_IsAnswerable (3), OneWordFollowUp_IsNotDroppedByTheTurnDetector (4), FlatFollowUp_WasRejectedByTheRc4Rule_AndIsAcceptedByTheRunRule, ShortAndPulsingHeldTones_AreNotVoiceLike (12 cases, incl. the `Shape` helper) | 7 → Package D (below) |
| MultilingualLiveEvaluationTests.cs (2 methods) | `live/MultilingualLiveEvaluationTests.kt` | FixtureCoversTenLanguagesAndDetectsEachQuestionLanguage + the full 10 × 7 `Questions` table verbatim | 1 → Package D (below) |
| PreparationTests.cs (7 methods) | `preparation/PreparationTests.kt` | 5 methods: PreparationRunsAllStagesAndBuildsAdaptiveBank, VerifiedPreparedAnswersOnlyUseConfirmedFactsAndPassValidator, PartiallyConfirmedProfileNeverLeaksUnconfirmedMetrics, PreparationRequiresConfirmedFacts, MultipleProfilesAndTargetsAreIsolated + the GOLDEN test (below) | 2 → Package D (below) |
| TurnBoundaryAcceptanceTests.cs (whole file) | — | 0 — every case drives InterviewSimulator | Whole file → Package D |
| LiveEvaluationTests.cs (whole file) | — | 0 — needs OPENAI_API_KEY + InterviewEngine + OpenAiChatAnswerProvider | Whole file → Package D |

**Deferred by name to Package D (Orchestration)** — all InterviewSimulator / InterviewEngine / provider cases:
RecordedStyleAudioTests: `Engine_NonSpeechFixture_WithHallucinatedTranscript_AnswersNothing`,
`Engine_NonSpeechFixture_HeardAsAShortQuestion_IsVetoedAcoustically`,
`Engine_NonSpeechFixture_HeardAsAContextDerivedQuestion_AnswersNothing`, `Engine_SpeechFixture_IsAnswered`,
`Engine_SpeechFixture_ShowsLiveWordsBeforeTheTurnEnds`.
Rc5FollowUpTests: `FollowUpFixture_CreatesExactlyOneTurnHistoryItemAndRequest`,
`SamePhrasesOverNonSpeech_CreateNothing`, `SamePhrasesOverShortNonSpeech_CreateNothing`,
`QuickFollowUp_AfterACompleteQuestion_IsItsOwnTurn`, `UnfinishedQuestion_ThenGoOn_StillMergesIntoOneTurn`,
`FollowUp_AfterTheAnswer_IsAnsweredOnce`, `Panel_JohnTellMeMore_IsNotAnswered_ShervinTellMeMore_Is`.
PreparationTests: `PackDrivesTheLiveEngineEndToEnd`, `LowConfidenceTemplateAnswersAreReferenceNotInstant`.
MultilingualLiveEvaluationTests: `LiveAnswersInAllTenLanguages`. TurnBoundaryAcceptanceTests: entire file.
LiveEvaluationTests: entire file.

**GOLDEN test** (`goldenBank_PipelineMatchesLibrarySourcedQuestionsAndCoreModes`, TRANSFER-MAP §5.2
rule 5 / §5.4): the golden `question_bank.json` holds 97 questions (asserted; VERIFIED 23 /
HYPOTHETICAL 70 / BRIDGE 4 by mode). Analysis (scripted against the byte-identical fixtures): the
golden bank is a CURATED knowledge bank, not the deterministic pipeline's output — its structures are
only the three generator defaults while the current factory assigns custom per-question structures,
so a full-bank equality test would fail even on Windows (and no such C# test exists). The pinned
comparison therefore covers: (a) the library-sourced overlap — golden ∩ generic-library ∩ pipeline
fingerprints (36 golden questions are exact generic-library items; ≥ 30 asserted present in the
overlap) must agree on mode AND structure; (b) the unconditional Core() questions present in both banks
must agree on mode: "Tell me about yourself." (Verified), "Why do you want to work at Teroxx?"
(Hypothetical), "Why should we hire you?" (Verified), "What are your strengths?" (Verified), "What
would you do in your first 90 days?" (Hypothetical), "Do you have any questions for us?" (Hypothetical),
"Where do you see yourself in five years?" (Hypothetical); (c) the single pinned exception, documented
like KnownVoiceLookalikes: "What is your biggest weakness?" is VERIFIED in the golden bank but the
current factory generates it as BRIDGE — asserted as exactly that difference.

## C3. Divergence candidates (DC-30+)

- **DC-30 — `{value:P0}` percent rendering in SpeechEvidence strings.** .NET's `P0` format is
  CurrentCulture-dependent; the C# tests pin only the "steady pitch" prefix (Rc5FollowUpTests). The
  port renders en-US style: `Math.rint(v * 100)` (half-even, matching .NET Core's formatting) + "%",
  no space. Hosted as `SpeechEvidence.percent0` and used by `notVoiceReason`/`toString()`.
- **DC-31 — `InvalidOperationException` → `IllegalStateException`.** PreparationPipeline's
  no-confirmed-facts guard throws `IllegalStateException` with the identical message ("Confirm at
  least some extracted profile facts before preparing an interview."); the ported
  PreparationRequiresConfirmedFacts asserts that type. Same class of mapping as Package A's
  InvalidDataException handling (DC-11). Likewise PcmConverter's `ArgumentException` →
  `IllegalArgumentException` via `require(...)` (identical message "Invalid source format").
- **DC-32 — CancellationToken → coroutine cancellation.** `IPreparationEnricher.enrichAsync` drops
  the token parameter; the pipeline's Step checks `currentCoroutineContext().ensureActive()` where
  the C# calls `ct.ThrowIfCancellationRequested()`. The enricher catch filter mirrors
  `ProviderException or HttpRequestException` as `ProviderException || IOException`
  (HttpRequestException's platform analog), rethrowing everything else (including CancellationException).
- **DC-33 — SpeechEvidence/FrameAcoustics are data classes, not record structs.** Value equality and
  `copy()` match the C# record-struct semantics the tests use (`with` expressions); the C#
  `default(SpeechEvidence)` is `SpeechEvidence(0,0,0,0,0)`.
- **DC-34 — `Spectrum`'s uninitialized `stackalloc` peak array.** The C# reads `p[0]` (never written)
  in the strong-peak check at k = 2; the memory is uninitialized stack. The Kotlin port uses a
  zero-initialized DoubleArray — the only deterministic mirror, and the one the 74 recorded fixtures
  validate in CI.
- **DC-35 — SessionMemory `CompanyTopics` iteration order.** C# `HashSet<string>` enumeration order
  is unspecified; kotlinx's MutableSet is insertion-ordered (LinkedHashSet). Only affects the order of
  names inside the rolling-summary string when more than 8 company topics exist.
- **DC-36 — OrdinalIgnoreCase set lookups via lowercase.** GenericReqWords / PastVerbs / PassiveVerbs /
  NotKeywords / CoachPrompt's Stop set are stored lowercase and probed with the (already normalized or
  explicitly lowercased) token; CommonTech is probed with `equals(ignoreCase = true)`. Equivalent for
  the ASCII vocabularies involved; noted because the C# sets are comparer-based.
- **DC-37 — `PreparationAssets.load` playbook file order.** `Directory.GetFiles` order is
  filesystem-dependent in .NET too; the port enumerates `File.listFiles` unsorted, inheriting the same
  nondeterminism (snippet order only; no test asserts it).
- Not divergences, recorded for reviewers: `QuestionFactory._facts` uses `associateBy` — the DC-22
  class (C# `ToDictionary` throws on duplicate ids; associateBy keeps the last). Duplicate fact ids
  cannot occur from the ingestion layer (ids are generated unique). `AudioFramer.push` returns an
  eager `List<ByteArray>` where C# yields lazily — same frames, same order. `PreparationPipeline.stages`
  is a `val List<String>` for the C#'s `static readonly string[] Stages`.

## C4. Fixtures copied (byte-identical, SHA-256 verified after copy)

Audio fixtures: `windows-reference/tests/fixtures/audio/` → `project/core/src/test/resources/fixtures/audio/` —
**76 files** (74 WAV + `fixtures.json` + `MANIFEST.md`), every file byte-compared after copy, 0 mismatches.
(The TRANSFER-MAP "80 fixtures" counts the wider set: these 76 + the 2 job TXTs + 2 résumé TXTs already
copied by Package A.) The C# tests load fixtures by path; the port loads them through the classpath
(`Fixtures.audioFixtureDir()` materializes them to a real directory, mirroring `Fixtures.Path("audio")`).

| File | SHA-256 |
|---|---|
| MANIFEST.md | `8d95cc908905000b4ed373c0c38f783ead0f1d82f4f503167b9a7a58503a1d6c` |
| chime_glock_arpeggio__calln.wav | `a74f441e8e76985458a30d7e336a04171a047fb2e0138bd5a430cd84df24daa5` |
| chime_glock_arpeggio__callq.wav | `e953962ac54cb743dbb721ab1788d17901e0d773947c92a796fd3296ec83adbe` |
| chime_glock_arpeggio__direct.wav | `7374b81103fe97be9b9514f7d363cf0e6bcce7b9d42963610cca57dccf101f90` |
| chime_glock_dingdong__calln.wav | `bd0c3146127b8dfb9421f1520f8e069cbe2bee81f1fdf71533eb14bcbea8837d` |
| chime_glock_dingdong__callq.wav | `30ef2b795182244fd726f3087d8f23282339dada19b910d8054f0c041633bca0` |
| chime_glock_dingdong__direct.wav | `0015acb7173c146ca769d8627495dd01e53d2137a3f3bff2ee0e5b7b6a6db91a` |
| chime_marimba_low__calln.wav | `f2158335f27a4a2f6dc195e8141aefac1cb96b21af466d6f814d9e76e999a301` |
| chime_marimba_low__callq.wav | `6da8c227f4a4e0fccfc62b8d4d499ba9018f02f6544d81266fa6e347d85bbab0` |
| chime_marimba_low__direct.wav | `a28cb548ca6a06098d7ff6735d5bdbe21ffc346fc6f41f8b3c400db711aa1c6c` |
| click_keyboard__calln.wav | `96229a32b098afcc28781dbf5e7bbed0a1d139a05e8fba93080935a58c5a998a` |
| click_keyboard__callq.wav | `304fcb38fed9c9f4e7806c67740c62a0441be3ffbc118ac435ade5f4b844a042` |
| click_mouse__calln.wav | `6dd1e31025a444fd74cbcffd66719e1f3f4bf2a9838f2d32c11d998b1a0ca212` |
| click_mouse__callq.wav | `716290acc520c9faee7b45c53f68f16ba62c89afcc654004503a338c2223cfce` |
| error_critical_stop__calln.wav | `f1815d9608223777eedf49b0f7dc3f565a08e2e3b6a64c87f2122594d4f68b11` |
| error_critical_stop__callq.wav | `4367d8f82be166271f1170adcc482e60262b8a5f5a1a33db89d1fa6d83ca8d3b` |
| error_critical_stop__direct.wav | `01708cbc44515d09df8ea867c22a198f7ee42bcb713d245b52074aea9b3e26bc` |
| error_low_buzz__calln.wav | `298f9f44c481de359677f94fb3beff68d50b3355ddd2fb138fe2b1f0b1ed05ac` |
| error_low_buzz__callq.wav | `d2dc31b9705ea5fc93e41622f1f66338d24ae146c09084e89afbe3a97fc6f7d2` |
| error_low_buzz__direct.wav | `10ff8a5aebe5b6ff8d04801abbadcfbc555d226ae16943827cad27c2f8e5cb6d` |
| fixtures.json | `94c0d3a24da1a852fcbc8217618ad24d42329a3981c7dbdf51b98e06027fc229` |
| knock_door__calln.wav | `be45648cbd6f1dfd5452b2c20fa18765d32c9a027d30d9311534305b6eb0a65b` |
| knock_door__callq.wav | `3e96447a50f14e67a31872b06bd2148b7e5f08c5c9645ec8180322fd51518489` |
| music_band__calln.wav | `7bce03413150f115371a22a52afcc9ff6384e1f1f02abef4c26d51c5f5c04d4f` |
| music_band__callq.wav | `fa63e647fc49030275d48a61cbda92f6da4d7f348f51866c8c68c332b709f951` |
| music_piano__calln.wav | `1fb19e7e3407757630326d1eda01bde2478e7165a736b89c10c396233d30aa4c` |
| music_piano__callq.wav | `fd5dd29ffcf42176cddf9df957f425e1496913de6f396b4ecbf9bc78683533d7` |
| music_sung__calln.wav | `06e18303acb87447d43107a7eda3dbcb4e1ab5e789ee8ecd3293366cb77671c6` |
| music_sung__callq.wav | `06fb89e0bf65054840ac20a8a9ba36563b0348644e02843caad9002a6870609a` |
| shutter_camera__calln.wav | `981616e5bfb7440929a07175ef003a03baaf458646425c4597ac0f52bdc72228` |
| shutter_camera__callq.wav | `149a6b6581cdc259c067b2e03768ca1a0c187a6685bed7ba6e58c644c1e9bca6` |
| shutter_camera__direct.wav | `9a628f0a9744f1bf5f5581d123b88c0b795bd52db475cd3f541fb71f901e88f6` |
| speech_andthen__calln.wav | `d18cc2049164674901c16e3c9b52a3b07123a373882465149e9535bdfc94e9a6` |
| speech_andthen__callq.wav | `d933fbe81c40c6d820dbf8d22f22b90a7c52b886841657589bebd7f716bb9995` |
| speech_anythingelse__calln.wav | `755cf364de45c6f6a20e7458a70f0df76da2534cc58f6fcbbecd33420c535f96` |
| speech_anythingelse__callq.wav | `1981bd8731dd53b08ccd3cb2aa2717e524f518e85936db09d9655b95908e7aea` |
| speech_de_warum__calln.wav | `b9d2d6f14458617cbc904e829621c7350c981b20515897628ecefdeeca428247` |
| speech_de_warum__callq.wav | `58e00fb3b277c78b30f983b89e07303e4f9a026841ebfe974b10c93236e68926` |
| speech_es_reto__calln.wav | `cbb5c3d3bbfc7818127362f38a3367ad172d652e0e872ff8ccff287f31bf18ec` |
| speech_es_reto__callq.wav | `5bc8c579d0dfa429c62967a1c4ad7cb4427e7ec34a05acf2510beb9e0cb53d55` |
| speech_fast_ninetydays__calln.wav | `01cdfc6fdfdf49d18f07660830b9e3b6bf4283509db96af8b9f5966ccaac53b4` |
| speech_fast_ninetydays__callq.wav | `c84621e3e23273ed0dc3639068741ae43efedc70edcafa2ebc4f858fb716d2a1` |
| speech_fu_another__calln.wav | `80ecc1799ccb36fddeea9fe3f6b58a09f78f47f36bd68a2fe1f6d63adcccdbc4` |
| speech_fu_continue__callq.wav | `dcf99037da23ffbf7953e8a45f46220183e6397050c11e4dced9f0549c85b0b3` |
| speech_fu_elaborate__calln.wav | `7d1a649a94869bdf03be3cbbf1f0878de7b6e842b31c43ccc424f9bffd6e869e` |
| speech_fu_example__callq.wav | `06d0bd8c5c06554b84a66e407edc053945e34e526f8528be62b008843ea7d358` |
| speech_fu_expand__callq.wav | `be15ca5d1c7c36ba60023f1757f951d8c2c35c0249f5d323ae0c2c5c540673ca` |
| speech_fu_explainfurther__callq.wav | `ff984fadfd923a4180888cff8a391f1bda8fbd725892daf070bafcf90a43de47` |
| speech_fu_goon__calln.wav | `937e2a31d34e390dc19b8dfe539aab9ab7695cfe7f0c05788c6c2bddcbcdfe8f` |
| speech_fu_howdid__callq.wav | `0f744634996421ed795905f9e718724da0a4693e659657ed8f56036ba5dfe09f` |
| speech_fu_moredetail__calln.wav | `edf5989d404cb8a66a3c026eec277dbbc26c951022a59eb0ddc1cad5023ff90b` |
| speech_fu_process__calln.wav | `8a6feb0cc158f5c190f5b6128efbe677dc7bfb4e90eb546f5dfc6f3a88ba3f41` |
| speech_fu_talkit__calln.wav | `a752f2a06cffb2485654bb54977b6cb12e7910e5fa3f3a132414d25148a8321e` |
| speech_fu_tellmemore_flat__callq.wav | `eb343fd771d1477092d9a765c598139c086fa751a209775507a1c31df2f0dfc3` |
| speech_fu_thinking__callq.wav | `bac91ddf87b0a3bee3cac60802f9cb3988e7c9b1621503bb9195f1ddb28cbf4d` |
| speech_fu_walkthat__calln.wav | `de1a0d7b52a85aa68ec1212bae0903c1a7213feeda33e83b2cedfaf5db6b1831` |
| speech_howso__calln.wav | `340bd0c1bda32e963c4fd0aef846403dbd9005ff2ba7a198ab34df6939459d0d` |
| speech_howso__callq.wav | `71af3b86fcd87f7b1ae3de7487a740e0c2cf715ab155217dd165a7b79fe4292e` |
| speech_metrics__calln.wav | `f95c22b800fd3552c77d6bd511891f0506d45faa41175e5cfe7db6dec9f8b912` |
| speech_metrics__callq.wav | `27b0e165e338bd126c5f8406620f21c1fe00c953bfe56458866a9aeb32a5674d` |
| speech_metrics_oneword__calln.wav | `fb8c34f78a102c42ff4af8f202e42a677b3d32648b4c3d3a60ac3e26f710c3fd` |
| speech_metrics_oneword__callq.wav | `84df0a312b4d3b86a692933cad4a5c36fb94ae032e86cddca75d7ab52f23d1fd` |
| speech_ninetydays__calln.wav | `c752d6a3281cab5e3b530f18dba17e915a5689f24975a938c7c3bc476b4afde9` |
| speech_ninetydays__callq.wav | `997adb5f8ba67f5ffda9b3c09498dbf727e068c212f5c370630487f09c32f8cd` |
| speech_really__calln.wav | `44dbb199f67ab8f7de4959fe015dccb14591cf824c7fe474fb5dfc9551cc6042` |
| speech_really__callq.wav | `6ac716c397bd2a9246296313ae3e37b0f5870f02f81f468d4a22a277d1a6cc93` |
| speech_tellme__calln.wav | `cd4c48adeafc37fe243df295076da631499e4463bcbe6e3cfbb2129af2b6db2f` |
| speech_tellme__callq.wav | `8c9f133ebf42612d48c234bd0e0ca22ddb62963f463bddd22021ea177dd1c840` |
| speech_tellmemore__calln.wav | `f2e39cd4852b3e61bf1b15d2a1eea53ff4d456af342d090edc107dba83a222f2` |
| speech_tellmemore__callq.wav | `61042bd2ea94e6a6d77663625a72e74c3acd3cbb92c4d7ce6f1569f7dded5907` |
| speech_whathappened__calln.wav | `63cd1d191669050333ace82a848b38f30403146e6f672fb33fd180da16fde58c` |
| speech_whathappened__callq.wav | `ae7cf621a6bc176480c0bcde7a06510c27c2707e9a373d2a405431a1267c47c0` |
| speech_why__calln.wav | `c22c5d9335dfa8c2ff18732d031f4bce6a73a2f1e4523e8eef9426891a1a31ca` |
| speech_why__callq.wav | `3b058695766393827298a704edc561010f8ec88a2e3446ec18955aeaca140531` |
| speech_why_falling__calln.wav | `90ea427192bd3c26fbade6df7e912d1198b07c91633cd9760cec829512b652ef` |
| speech_why_falling__callq.wav | `5ee127800e4cf04d5778996ff85dac0f271cc0db09c416a7ad3f35d0d59c4edc` |

Sample set: the golden test reuses Package B's byte-identical copies of `samples/shervin-teroxx/` under
`core/src/test/resources/knowledge/` (no re-copy). Re-verified for this package — all 12 byte-identical:

| File | SHA-256 |
|---|---|
| answer_policy.md | `c38481f75c6c5a4c9637464fc25a7a853e170d2f6747dc5565eed7a22cc33884` |
| candidate_profile.json | `e798097c48be7b1877db48140abf2c35e1ec062f3a96e4814785db07ca978aba` |
| candidate_stories.json | `8ea06ca2d0ac79b8d5dc313a24e75dc5ce08c3c52fd9dd2da06da195a74e85f3` |
| company_brief.md | `f4c5afd9124e5e3b67e3d02c321bcd102156cfc140d6cb2513a89d56d069e936` |
| crypto_fintech_playbook.md | `627ba8f79d5eed412915a2d1a66ea5725401e893747cc1503bcebd94f3eaf993` |
| product_brief.md | `b25d61338618eb961b7ee60d814943134744789e3beb19130ea108bf7d84979a` |
| product_owner_playbook.md | `99a7d24583e0ea160075cef7be9461f0ef4226353c9dbad3417d1583dded8b4a` |
| question_bank.json | `a42c2065dde4cc7a827eeecc394c63ee90fbc3aa567fd349bc47f945cfe2bc2a` |
| role_brief.md | `4f6302795fdbae7bc84a7ed760ef8f9f597d7755cdae84b4c5053bc37c7e3310` |
| target_context.json | `16639db5556f9d36d106135600db870968ab5c7685787bef663c72abbae97d0f` |
| test_questions.json | `d50cc70596d9f6ac6dd2b6fb65391ae87ceaa272a24b53c0b01f4b0064c2575f` |
| whitepaper_notes.md | `06bc7809984f7e81dc3b05daec68d3043822545090529681ecc5755d246fa178` |

## C5. Honest compile-risk notes

Nothing in this package has been compiled or tested locally (no JVM on this VM); CI is the first
check. Self-review was by re-reading every port against its C# source, the scripted regex parity
check (§C1.2), a corruption sweep (no `[truncated` markers, no NUL/VT/FF bytes in any of the 24
delivered files), and brace/paren balance smoke checks (all balanced). Residual risks, in order of
concern:

1. **Golden-test assumptions (PreparationTests).** The golden comparison rests on the scripted
   analysis in §C2 (36 library-sourced golden questions; ≥ 30 asserted in the triple overlap to leave
   room for factory questions shadowing library fingerprints in the pipeline's dedup). If CI shows a
   smaller overlap or a mode/structure disagreement, the failure will be in the analysis assumptions
   or a real factory divergence — the test reports the exact question. The rest of
   PreparationRunsAllStagesAndBuildsAdaptiveBank (bank 50–150, "ledger" gap, MiCAR/MiCA terminology,
   the Arzif PerRole question) exercises the whole A+B+C stack end to end and is the package's
   broadest integration risk: JD parsing, story rebuild, tokenization/stemming parity all feed it.
2. **Audio bit-faithfulness.** The fixture classification test is the arbiter: any drift in the FFT,
   autocorrelation, decimation, or `DotNetRandom` (synthetic signals) shows up as classification
   failures with per-fixture numbers. Specific watch points: the zero-initialized spectrum array
   (DC-34), `Math.rint` vs .NET's half-even `Math.Round` in PcmConverter/Periodicity, and the
   ThreadLocal scratch buffers (sized/grown exactly as the C#'s ThreadStatic ones).
3. **`PreparationAssets.LibFile` is a `private` @Serializable nested class.** kotlinx generates its
   serializer fine for same-file use in our experience with A/B patterns, but if the serialization
   plugin rejects the visibility, the fix is mechanical (drop `private`); flagged because it is the
   first private serializable in :core.
4. **Cross-package shape assumptions about Package D (Providers/Orchestration/Reports — still
   skeletons; D must code against these, or flag back):**
   - Events are listener lists: `TurnDetector.addStateChangedListener/addLiveTextChangedListener/addTurnFinalizedListener/addFollowUpSplitListener`, `InterruptionTracker.addLevelChangedListener` (the WorkspaceStore precedent; there are no remove functions, as in A/B).
   - `TurnDetector.segmentFilter`/`partialFilter`/`termNormalizer` are mutable lambda properties; `aliases` is `List<TermAlias>?`.
   - `IPreparationEnricher.enrichAsync(pack, progress)` is a suspend interface method (DC-32); an enricher written against the C# signature (Task + CancellationToken) will not match.
   - `PitchRunTracker.openRun()` returns the nested data class `OpenRunResult(runs, flat, stretch)`; `track(...)` is internal and returns `TrackResult(tracked, runs, flat, stretch)`.
   - `SessionMetrics` counters are read via properties (`questions`, `totalSuppressed`, `suppression`, …) and bumped via `inc…()` methods; `LatencySample.at` is `OffsetDateTime`.
   - `SpeechEvidence`/`FrameAcoustics` are data classes with member `plus` operators; timeline is `SpeechEvidenceTimeline(capacity).add/sum/clear`.
   - `PreparationPipeline.stages` (lowercase val) and `GeneratorVersion` (const) on the companion; `runAsync` is suspend with `enricher`/`progress` optional params.
   - `QuestionFactory` is internal (pipeline-only), as in C#.
5. **Kotlin/JVM numeric edges mirrored deliberately:** `(int)(delay * sensitivity)` truncation →
   `Double.toInt()`; C# integer divisions in hop/ms math → Kotlin Int division (all operands
   non-negative where the C# relies on truncation); `Math.Round(double)` (banker's) → `Math.rint`
   in the two audio spots and `dotNetRound2` (DC-24) in InterruptionTracker.
6. **Warnings expected (not errors):** the `Rig` helper in TurnDetectorTests destructures via a
   Triple in one test; a few diagnostic `println` calls replace `ITestOutputHelper` (as in B);
   `percent0` is duplicated as a private helper in RecordedStyleAudioTests for its diagnostic table
   (the production one is internal to SpeechEvidence's companion).
7. **Test-side timing:** `analysis_IsCheapEnoughForTheCaptureThread` keeps the C#'s < 10 ms/frame
   bound verbatim on CI hardware (same caveat class as Package B's MatchingIsFast note).


---

# Package D — Providers, Reports, Orchestration (FINAL P3 package)

Ported locally only; nothing compiled or run (no JVM/Kotlin toolchain on this VM — GitHub Actions is the
first execution check). Line counts are `wc -l` of the finished files.

## D1. Production ports — per-file checklist (member completeness)

| C# source (lines) | Kotlin port (lines) | Completeness |
|---|---|---|
| Providers/OpenAiAuthProbe.cs (34) | providers/OpenAiAuthProbe.kt (72) | Complete: `object OpenAiAuthProbe`, `Result(ok, kind, detail, ms)`, `suspend check(key, client?)`; status mapping 200/401/403/429/other, timeout/network kinds exactly as C#. |
| Providers/OpenAiChatAnswerProvider.cs (208) | providers/OpenAiChatAnswerProvider.kt (315) | Complete: `ChatProviderOptions` (all fields/defaults), `IAnswerProvider` + `Closeable`, `buildBody` byte-faithful (key order, reasoning branch, +600 completion-token rule), ≤3-attempt retry only on BadRequest (reasoning-drop / fallback-model), first-token + idle + max-duration stream budgets, `parseDelta`, `mapError`, companion `isReasoningModel`/`autoEffort`, counters, `activeModel`, `close()`. |
| Providers/OpenAiRealtimeTranscriber.cs (373) | providers/OpenAiRealtimeTranscriber.kt (492) | Complete: both constructors (suspend credential provider + sync api-key provider), full run/reconnect loop, model fallback + GA→beta protocol switch, server-configured session rules (401-refresh-once, refused-status sets, callbacks), `buildSessionUpdate` (GA + beta shapes), `handleEvent` full dispatch, audio queue (30, DROP_OLDEST), session rotation, backoff status text. |
| Reports/ReportLabels.cs (38) | reports/ReportLabels.kt (385) | Complete: 33 keys × 10 languages, GENERATED from the C# literals by script and verified byte-identical by a second script (all 330 strings). |
| Reports/ExportLabels.cs (163) | reports/ExportLabels.kt (230) | Complete: 96 keys, `get`/`format` via Loc, `isComplete`, `code`, `isRtl`, `culture`, `duration`, `date`, `dateAndTime`, `shortDate`, `number`, `head` (exact CSS), `htmlEncode` (contract pinned by Windows tests: named entities for & < > " ', `&#N;` for control chars, everything else literal), `longDateWithoutWeekday` + `stripWeekday` (algorithm ported exactly; see DC-41). |
| Reports/InterviewReport.cs (211) | reports/InterviewReport.kt (275) | Complete: `ReportQuestion` (15 props, PascalCase @SerialName), `StringIntPair` (Item1/Item2 STJ ValueTuple parity), `InterviewReport` (27 props), `ReportBuilder.build` (echo filter, uncertain re-validation, interruptions removal, all rpt_* strings, percentiles, excludedTurns), `ReportSerializer` (blank/invalid → null), pretty JSON with two-space indent. |
| Reports/HtmlReportRenderer.cs (119) | reports/HtmlReportRenderer.kt (154) | Complete: `header` dedupe algorithm, `render`, `renderText`, HALF_EVEN rounding mirroring Math.Round. |
| Reports/AccountExportRenderer.cs (178) | reports/AccountExportRenderer.kt (264) | Complete: `AccountExportContext`, tolerant JSON accessors (case-insensitive), usage grouping, session sort, document grouping/order, `accountStatus`/`subscriptionState`/`endReason` mappings, all sections byte-faithful. |
| Reports/ProfileExportRenderer.cs (142) | reports/ProfileExportRenderer.kt (184) | Complete: facts pipeline (reviewable/verified/answers/awaiting), role grouping, provenance + confirmed lines, experience grouping, answers ordering via PersonalCategories, documents + technical sections. |
| Orchestration/EngineModels.cs (103) | orchestration/EngineModels.kt (142) | Complete: `EngineStatus` (13), `AnswerSource` (5), `AnswerKind` (3), `AnswerView` (all members incl. lock-guarded bullets), `EngineOptions` (all fields/defaults). |
| Orchestration/InterviewEngine.cs (968) | orchestration/InterviewEngine.kt (1224) | Complete, member by member: lifecycle (start/pause/resume/stop/dispose, suspend/ensure transcription), audio path + evidence timeline, tick loop (hold release, NoAudio/Quiet), turn + transcriber wiring, `handleFinalized` (verdict counting, follow-up/weak-support, revision merge, duplicate guard, interruption events), `answerAsync` (classifier/matcher/language/style/record/context/plan, cache rules + provider-null fallback, continuation hold, LLM streaming, coach path, personal grounding fallback, provider-error fallback ladder, finally: variant restore, quality gate, record completion, pace, latency sample), `emitCached`, `coachAsync`, `streamLlm`, 9-step `acceptBullet`, `restoreMemory`, `togglePresentation`, `resetAdaptive`. Events are public listener lists (P2 ITranscriber precedent). Regex parity: scripted extract+unescape+diff — BridgeCue, PastResult, LeakGuard, all 9 bridge cue patterns, all 9 bridge sentences and HonestBridge are BYTE-IDENTICAL to the C# patterns. |

Build change: `core/build.gradle.kts` gained `implementation(libs.okhttp)` (catalog 4.12.0, the version
:client already uses) with an explanatory comment — see DC-38. No other dependency was added.

§C5 shape assumptions all held against the real C#: listener-list events, the suspend enricher
interface, `OpenRunResult`/`TrackResult`, `PreparationPipeline.stages` naming. No A/B/C production
call-site changes are required by Package D except the one test-infrastructure item in D6.

## D2. Test mapping (Package D test files; theories → case loops; DC-9 applied)

| C# test file | Kotlin port | @Test methods |
|---|---|---|
| ProviderTests.cs | providers/ProviderTests.kt (+ ServerConfiguredTranscriptionTests) | 13 |
| ReportTests.cs (incl. Rc2ReportTests) | reports/ReportTests.kt | 10 |
| Rc4ReportDefenseTests.cs | reports/Rc4ReportDefenseTests.kt | 8 |
| Rc5DataExportTests.cs — cases deferred by name from Package A | reports/Rc5DataExportReportTests.kt (DurationsReadLikeAPerson 7 cases, DurationsAreLocalized, ExportLabelsExistAndAreTranslatedInAllTenLanguages, AccountHtml_* ×4, ProfileHtml_* ×3 groups; the HTML assertion of NoSecretsInAnyProfileExport is `profileHtml_HasNoSecrets` here, paired with the A-side persistence test) | 10 |
| L10nDateAndBidiTests.cs — ExportLabels date cases | languages/L10nDateAndBidiTests.kt APPENDED (Package B file; 5 methods incl. 7 stripWeekday cases) | 5 |
| EngineTests.cs | orchestration/EngineTests.kt | 14 |
| EngineAcceptanceTests.cs (scenarios A–I) | orchestration/EngineAcceptanceTests.kt | 12 |
| Rc2EngineAcceptanceTests.cs (incl. Rc2EngineTimingTests) | orchestration/Rc2EngineAcceptanceTests.kt | 33 |
| Rc5EngineAuditTests.cs | orchestration/Rc5EngineAuditTests.kt | 22 |
| Rc5FollowUpTests.cs — 7 engine cases deferred from C | orchestration/Rc5FollowUpEngineTests.kt | 7 |
| RecordedStyleAudioTests.cs — 5 Engine_* cases deferred from C | orchestration/RecordedStyleAudioEngineTests.kt | 5 |
| LiveIntelligenceTests.cs — 15 engine methods deferred from B | orchestration/LiveIntelligenceTests.kt | 15 |
| LongContextRegressionTests.cs — 4 methods deferred from B | orchestration/LongContextRegressionTests.kt | 4 (12 case-runs) |
| AnswerQualityEvaluationTests.cs — 2 facts deferred from B | orchestration/AnswerQualityEvaluationTests.kt | 2 |
| TurnBoundaryAcceptanceTests.cs — all, deferred from C | orchestration/TurnBoundaryAcceptanceTests.kt | 10 |
| LiveEvaluationTests.cs — all, deferred from C (live-only, self-skips without OPENAI_API_KEY) | orchestration/LiveEvaluationTests.kt | 1 |
| MultilingualLiveEvaluationTests.cs — LiveAnswersInAllTenLanguages, deferred from C (live-only) | orchestration/MultilingualLiveEvaluationTests.kt (reuses B's Questions table) | 1 |
| PreparationTests.cs — 2 engine cases deferred from C | orchestration/PreparationEngineTests.kt | 2 |
| NonLatinQuestionTests.cs — 3 Engine_* cases deferred from B | orchestration/NonLatinQuestionEngineTests.kt | 1 (3 cases) |
| PersonalGroundingTests.cs — 3 Engine_* facts deferred from B | orchestration/PersonalGroundingEngineTests.kt | 3 |
| SoakTests.cs (incl. VirtualLatencyProvider) | orchestration/SoakTests.kt | 2 |
| WorkspaceStoreTests.cs (incl. AccountWorkspaceIsolationTests) — Package A gap | persistence/WorkspaceStoreTests.kt | 10 |
| UploadSecurityTests.cs — Package A gap | ingestion/UploadSecurityTests.kt | 6 |
| ShippedAssetsTests.cs — gap | preparation/ShippedAssetsTests.kt | 2 |
| Fakes.cs / InterviewSimulator.cs harness mirrors | orchestration/Fakes.kt, orchestration/InterviewSimulator.kt | — |

Package D total: **198 @Test methods** (case-expanded runs are far higher: fixture × phrase matrices in
the follow-up/audit/audio suites). Case counts were preserved exactly; no case was dropped.

## D3. FINAL whole-suite accounting — every Windows Core test file

Cross-checked against `ls windows-reference/tests/InterviewAssistant.Tests/*.cs` (58 files) plus the
separate Backend.Tests project.

| Windows file | Disposition |
|---|---|
| AcousticAnalyzerTests.cs | Ported — Package C (audio) |
| AnswerQualityEvaluationTests.cs | Ported — Package D (orchestration) |
| AudioTests.cs | Ported — Package C (audio) |
| ClassifierAndTextTests.cs | Ported — Package B (intelligence) |
| CloudAuthTests.cs | Ported — P2 (:client) |
| EngineAcceptanceTests.cs | Ported — Package D |
| EngineTests.cs | Ported — Package D |
| ErrorStateTests.cs | **Deferred to App/UI package (P4)** — the file tests `InterviewAssistant.App.UiState` + `InterviewAssistant.Client.BackendException` ONLY; it contains no Core surface (see D5 note — this contradicts the Package D task grouping, resolved by following the C#). UiState half → P4 UiState mirror; BackendException half → :client-side mirror. |
| Fakes.cs | Harness — mirrored across A/B/C test source sets; engine-facing fakes completed by Package D (orchestration/Fakes.kt) |
| Fixtures.cs | Harness — mirrored as test Fixtures.kt (Package A; extended by B/C/D) |
| GlobalUsings.cs | No Kotlin equivalent (test-project support only, 0 test content) |
| HeldOutMatcherTests.cs | Ported — Package B (intelligence) |
| InterviewSimulator.cs | Harness — mirrored by Package D (orchestration/InterviewSimulator.kt) |
| KnowledgeTests.cs | Ported — Package B (knowledge) |
| L10nDateAndBidiTests.cs | Ported — Package B (languages); ExportLabels date cases appended by Package D |
| LanguageTests.cs | Ported — Package B (languages) |
| LicenseComplianceTests.cs | Mapped to P7 counterpart (scans the Windows repo's THIRD_PARTY_NOTICES.md; Android licence mirror lands in P7 per map §7) |
| LiveEvaluationTests.cs | Ported — Package D (live-only) |
| LiveIntelligenceTests.cs | Split: non-engine facts Package B; 15 engine methods Package D |
| LocalizationCoverageTests.cs | Ported — Package B (languages) |
| LongContextRegressionTests.cs | Ported — Package D |
| MatcherEvaluationTests.cs | Ported — Package B (intelligence) |
| MatcherTests.cs | Ported — Package B (intelligence) |
| MultilingualLiveEvaluationTests.cs | Split: fixture fact + Questions table Package B (live/); LiveAnswersInAllTenLanguages Package D (live-only) |
| NonLatinQuestionTests.cs | Split: detector facts Package B; 3 Engine_* cases Package D |
| PersonalGroundingTests.cs | Split: gate facts Package B; 3 Engine_* facts Package D |
| PreparationTests.cs | Split: pipeline cases Package C; 2 engine cases Package D |
| ProfileIngestionTests.cs | Ported — Package A (ingestion/domain) |
| ProviderTests.cs | Ported — Package D |
| Rc2EngineAcceptanceTests.cs | Ported — Package D (incl. Rc2EngineTimingTests) |
| Rc4ReportDefenseTests.cs | Ported — Package D |
| Rc5AccessibilityTests.cs | Deferred — P4 UiState/Compose counterpart (map §5.2 rule 3) |
| Rc5AccountTests.cs | Deferred — P4 UiState/Compose counterpart; also named in the §7 P6 exit gate |
| Rc5DataExportTests.cs | Split: persistence-side cases Package A; renderer cases (by name) Package D |
| Rc5DesignSystemTests.cs | Deferred — P4 UiState/Compose counterpart |
| Rc5EngineAuditTests.cs | Ported — Package D |
| Rc5FollowUpTests.cs | Split: gate/turn cases Package C; 7 engine cases Package D |
| Rc5LiveWindowTests.cs | Deferred — P4 UiState/Compose counterpart |
| Rc5ReadinessPageTests.cs | Deferred — P4 UiState/Compose counterpart |
| Rc5ReadinessTests.cs | Deferred — P4 UiState/Compose counterpart |
| Rc5SettingsTests.cs | Deferred — P4 UiState/Compose counterpart |
| RecordedStyleAudioTests.cs | Split: acoustic cases Package C; 5 Engine_* cases Package D |
| ReportTests.cs | Ported — Package D |
| RepositoryHygieneTests.cs | Mapped to CI/P7 hygiene counterpart (repo secret scan) |
| SecurityAuditExportTests.cs | Ported — Package A (persistence; its own named deferrals are listed in §A) |
| SecurityAuditFixTests.cs | Deferred — App/UI package (tests App.UiState + Client + App source scans) |
| ShippedAssetsTests.cs | Ported — Package D |
| ShortTurnTests.cs | Ported — Package C (turn) |
| SoakTests.cs | Ported — Package D |
| SyntheticAudio.cs | Harness — mirrored by Package C (audio test source set) |
| TestKnowledge.cs | Harness — mirrored in test Fixtures.kt (Package A) |
| TurnBoundaryAcceptanceTests.cs | Ported — Package D |
| TurnDetectorTests.cs | Ported — Package C (turn) |
| UiFoundationTests.cs | Deferred — P4 UiState/Compose counterpart |
| UiPagesTests.cs | Deferred — P4 UiState/Compose counterpart |
| UpdatePolicyTests.cs | Ported — P2 (:client) |
| UploadSecurityTests.cs | Ported — Package D |
| WorkspaceStoreTests.cs | Ported — Package D |
| InterviewAssistant.Backend.Tests (separate project) | Re-run unchanged against the shared backend per map §5.2 rule 1 (not a port target) |

Result: every Core test file is ported (A/B/C/D/P2), split-ported with both halves named, deferred by
name to the App/UI (P4) package, or mapped to its P7/CI counterpart. No file is unaccounted for.

## D4. Divergence candidates (DC-38+)

- **DC-38 — OkHttp in :core (BCL parity).** The C# providers use BCL `HttpClient`/`ClientWebSocket`;
  Android has no BCL-equivalent (`java.net.http` does not exist on Android). The catalog's existing
  OkHttp 4.12.0 (already used by :client) was added to `core/build.gradle.kts`. This does not break the
  map §6.1 rule (core → no other PROJECT module); it supplies the BCL capability the C# takes for
  granted. Test seam divergence: C# injects an `HttpMessageHandler`; the ports inject an `OkHttpClient`
  (tests use an application interceptor to capture request bodies / stub responses).
- **DC-39 — Chat-provider stream cancellation granularity.** C# cancels an in-flight body read
  promptly via its CancellationToken; the Kotlin port reads through Okio with an `AsyncTimeout`
  deadline per read, so cancellation/timeout takes effect at the next chunk or at the armed deadline,
  not mid-read. First-token budget is computed as remaining time before the first read. Behavioural
  contract (timeout kinds, messages, ordering) is unchanged.
- **DC-40 — No caller token on ITranscriber.startAsync (P2 contract).** C#'s engine passes its run
  CancellationToken into the transcriber; the P2-pinned Kotlin contract has no token parameter, so the
  engine lifecycle relies on explicit `stopAsync()`, and the per-answer Job is parented to the CALLER's
  job (it dies with the caller's scope) where C#'s answer CTS is standalone. Engine stop/dispose paths
  cancel explicitly, mirroring the observable behaviour.
- **DC-41 — ExportLabels dates on the JVM.** JVM localized date patterns name the weekday field `E`
  where .NET uses `d` runs; `stripWeekday` applies the ported algorithm to both run letters (the 7
  Windows pattern cases pass against the verbatim .NET form). The JVM has no Persian chronology, so
  `fa` dates render with the ISO/Gregorian calendar where .NET renders Solar Hijri; `ar` is forced to
  IsoChronology via `LanguageRegistry.usesGregorianCalendar`, matching the C# behaviour. The Windows
  InAnyLanguage assertions (no weekday + non-empty) hold under both.
- **DC-42 — Engine events and answer cancellation.** C# events → public listener lists (established P2
  ITranscriber precedent). C# CancellationTokenSource/Interlocked.Exchange → an
  `AtomicReference<Job?>`; each answer Job is a child of the caller's job, the body runs in the caller's
  context, and only the continuation-hold await and the provider-stream collection run under the answer
  Job so cancellation aborts exactly those, mirroring the C# token's reach.
- **DC-43 — JVM analogs in timing/soak tests.** GC-pause and memory assertions use used-heap around
  `System.gc()` and `ManagementFactory` GC counts instead of .NET GC counters; `System.Random`
  sequences are reproduced bit-exactly by the test `DotNetRandom` with `Next(max) = (nextDouble() *
  max).toInt()` (the .NET legacy formula). The C# thresholds (<4 MB / <8 MB growth, <50 ms max handler,
  p99 <10 ms, local-pipeline p95 <25 ms) are ported faithfully and are the most hardware-sensitive
  assertions in the suite on CI.
- **DC-44 — Digit substitution in formatted numbers (.NET vs JVM).** .NET `string.Format` never
  substitutes digits (proven by the Windows suite: DurationsAreLocalized expects fa "0 ثانیه" with an
  ASCII zero). JVM `NumberFormat` DOES substitute digits for ar/fa. `Loc.format` (P2) formats through
  `LanguageRegistry.numberFormat` (Package B), which returns a plain `NumberFormat` — so the fa
  duration case ported in D will fail on the JVM until the adjustment in D6 is applied. The test is
  ported faithfully; the fix belongs to the number-format factory, not to the test.

## D5. Notes on scope findings

- **ErrorStateTests.cs placement (flagged prominently):** the Package D task grouped this file under
  orchestration/, but the C# file tests ONLY `InterviewAssistant.App.UiState` and
  `InterviewAssistant.Client.BackendException` — there is no Core surface to port. Following the C#
  (per the task's own rule), it is accounted as deferred to the App/UI package (P4 UiState mirror),
  with the BackendException half belonging to the :client side. Nothing was ported into orchestration/
  for it, and no Core test was silently dropped.
- **Fixtures copied:** none. No binary fixtures were needed by Package D. `ReportLabels.kt` was
  generated from the C# source literals by script and verified byte-identical by a second script
  (330/330 strings), so no fixture files or SHA-256 entries arise.
- **Fake credentials in tests:** ProviderTests/Rc5DataExportReportTests use the same clearly-fake
  fixture literals as the Windows suite and Package A's port (a `sk-proj-…` shaped string and a sample
  JWT) — test fixtures only, not real credentials.

## D6. Earlier-package call-site adjustments needed (exact list; NOT applied by Package D)

1. **`languages/LanguageRegistry.kt` — `numberFormat(code)` (Package B file):** force Latin digits to
   match .NET formatting (DC-44). Concretely: build the instance from
   `Locale.Builder().setLocale(formattingLocale(code)).setExtension('u', "nu-latn").build()`, or set
   `DecimalFormatSymbols.zeroDigit = '0'` on the returned DecimalFormat. One factory change fixes every
   `Loc.format` consumer. Required for `Rc5DataExportReportTests.durationsAreLocalized` (fa case).
2. No other A/B/C (or P2) call-site changes are needed: every §C5 assumption and every dependency
   shape Package D coded against was verified against the ported sources by grep/read during the port.

## D7. Honest compile-risk notes

- **Nothing in Package D has been compiled or executed.** There is no JVM/Kotlin toolchain on this VM;
  GitHub Actions is the first compile and the first test run. All counts above are source-level.
- **Highest-risk file: orchestration/InterviewEngine.kt** — the Job-graph concurrency (answer/variant
  exchange, continuation hold, UNDISPATCHED finalize launch) mirrors the C# control flow step by step,
  but a mis-wired cancellation path would only show at CI/runtime, not on paper.
- **ProviderTests stalling-stream case** depends on Okio `AsyncTimeout` closing the raw source to
  release a blocked read (DC-39); if OkHttp's timeout plumbing differs, that one test is the canary.
- **Timing/memory budgets** in Rc2EngineTimingTests, SoakTests and the local-pipeline p95 check are
  ported with the exact C# thresholds; shared CI hardware may behave differently from the Windows dev
  machine they were tuned on (DC-43).
- **Expected failure until D6.1 lands:** `durationsAreLocalized` fa assertion (DC-44) — known,
  diagnosed, and owned by the number-format factory, not by the test.
- **Report JSON:** kotlinx-serialization writes non-ASCII literally where System.Text.Json's default
  encoder escapes it; content-equal after parse, byte-different for non-ASCII payloads. This is the
  established CoreJson house convention from Packages A–C, noted here for the report layer specifically.
- **Live-only tests** (LiveEvaluationTests, MultilingualLiveEvaluationTests.LiveAnswersInAllTenLanguages)
  self-skip without `OPENAI_API_KEY`, mirroring the C# suite; they will report as skipped, not passed,
  in CI.
- Final sweep completed over all 38 Package D files (11 production + 1 build file + 25 new test files + 1 appended Package B test file):
  no `[truncated]` signature, no literal NUL/VT/FF bytes; string/comment-stripped brace/paren/bracket
  balance clean (two apparent anomalies traced to nested-quote string templates + CSS literals, i.e.
  checker artifacts, verified by inspection).

---

## CI fix round 1 (2026-11-19, after Actions run #125 on f449061 — :core:compileDebugKotlin, 37 errors)

All fixes are compile-level Kotlin/JVM adaptations; no behavior, literal, or test expectation changed.

- **DC-45 — `dotNetRegex` construction.** `Regex(java.util.regex.Pattern)` is an internal Kotlin
  constructor. The helper now builds the Regex from the pattern string with embedded JVM flags:
  `(?U)` always (UNICODE_CHARACTER_CLASS) and `(?iu)` added when ignoreCase (CASE_INSENSITIVE +
  UNICODE_CASE) — the same flag set as before. FactValidator's `clauseBoundary` (the one DOTALL
  pattern) uses `(?iuUs)` the same way. Both `java.util.regex.Pattern` imports removed.
- **DC-46 — nested block comments.** Kotlin block comments nest, so the `/*` inside comment text
  `Languages/i18n/*.json` (UiStrings) and `targets/*.json, packs/*.json, sessions/*.json`
  (ProfileBundle KDoc) opened nested comments and swallowed code (this also caused the
  `ProfileBundle.FormatName/FormatVersion` unresolved errors). Comments reworded; no code changed.
  (The same character sequence inside a WorkspaceStore string literal is harmless and untouched.)
- **DC-47 — FactValidator claim type.** `isSupported`'s `claim` parameter is
  `kotlin.text.MatchResult` (the C#'s `System.Text.RegularExpressions.Match`), now fully qualified
  so it no longer binds to QuestionMatcher's own `MatchResult` data class in the same package.
- **DC-48 — `regexEscape` single home.** The faithful .NET `Regex.Escape` port already lives in
  `knowledge/TargetContext.kt`; QuestionExtractor's import corrected from `ingestion` to
  `knowledge` (QuestionClassifier already imported it from there).
- **DC-49 — InterviewEngine imports + constructor shadowing.** Imports corrected to the real
  packages (`turn.DuplicateGuard`, `languages.LanguageDetector` — the C# namespaces differ from
  the Kotlin package layout). In `init`, the constructor parameters `clock`/`options` shadow the
  like-named properties, so the two uses are now `this.clock` / `this.options` (the properties
  already carry the C#'s `?? new` defaults).
- **DC-50 — OkHttp coroutine bridge calls.** `CancellableContinuation.resume(value, onCancellation)`
  is a member needing the second argument: call sites now pass `null`; `resumeWithException` is
  the kotlinx extension and is now imported in OpenAiAuthProbe / OpenAiChatAnswerProvider.
- **DC-51 — RealtimeTranscriber constructor disambiguation.** A bare lambda matches both the
  `suspend () -> String?` primary and the `() -> String?` secondary constructor. The secondary's
  delegation now passes `suspend { apiKeyProvider() }`; ProviderTests call sites that the C#
  binds to the sync `Func<string?>` constructor now name it (`apiKeyProvider = { "k" }`).
- **DC-52 — ExportLabels chronology.** `java.time.chrono.Chronology` has no `fromLocale`; the
  JVM counterpart of the C#'s culture calendar lookup is `Chronology.ofLocale(locale)`.
