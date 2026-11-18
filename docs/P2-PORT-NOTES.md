# P2 Port Notes — Shared client (`:contracts`, `:client`, Core slice, `:app` platform)

Windows baseline: `4976e16f5379d38e9b265d3db979d2f8d7d7aa21` (read-only reference clone).
Scope per TRANSFER-MAP §7 P2 row and §5/§6.1/§8. Every file below is a complete port — no
placeholders, no TODO bodies. Anything not ported is named explicitly with its phase.

## 0. The one correction to the map (evidence over document)

TRANSFER-MAP §5.4 and the §2 row for `ApiContracts.cs` say the wire format uses "PascalCase JSON
names". The Windows **code** says otherwise on both sides of the wire:

- `BackendClient.cs`: `public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);`
- Backend `Program.cs`: `ConfigureHttpJsonOptions(o => o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase)`
- Backend `Infrastructure/Middleware.cs`: same Web defaults.

`JsonSerializerDefaults.Web` = camelCase names, case-insensitive read, nulls/defaults written (no
`DefaultIgnoreCondition` is configured anywhere in the source — verified by grep). The Kotlin DTOs
therefore use camelCase wire names (the Kotlin property names are the wire names), `encodeDefaults =
true`, nulls written, unknown keys ignored. The contract fixture tests pin this. **The map text
should be corrected at the next sync; the code was followed here, per the owner's rule that the
Windows code — not descriptions of it — is the spec.**

## 1. Per-file mapping

### `:contracts`
| C# | Kotlin | Notes |
|---|---|---|
| `Contracts/ApiContracts.cs` (92 lines, 27 types) | `contracts/ApiContracts.kt` | All 27 types, same order, same defaults, same doc comments. `Guid`→`java.util.UUID` (lowercase d-format), `DateTime`→`OffsetDateTime` normalized to UTC with System.Text.Json's exact rendering (Z suffix, ≤7 fractional digits, trimmed), `JsonElement`→kotlinx `JsonElement`, `IReadOnlyList`→`List`, `IReadOnlyDictionary`→`Map`. `BillingStates`/`AnswerPurposes` consts → `object` consts with the C#'s PascalCase member names so call sites read identically. Shared `ContractJson` instance = the Web-defaults semantics. |

### `:client`
| C# | Kotlin | Notes |
|---|---|---|
| `Client/Pkce.cs` | `client/Pkce.kt` | Exact: 48-byte verifier (64 chars), S256 over ASCII, 24-byte state, base64url without padding, verifier charset/length rule. Same file also hosts `UriEscape`, the `Uri.EscapeDataString`/`UnescapeDataString` equivalent used across the client (RFC 3986 unreserved set, uppercase UTF-8 hex). |
| `Client/Auth.cs` | `client/Auth.kt` | `AuthException`, `AuthSession`, `ITokenStore`, `ProtectedFileTokenStore`, `SupabaseAuth` — all members (see §3). Token-store file format mirrors the C#'s default-serializer format (PascalCase names, ISO offset) since the class is shared. |
| `Client/BackendClient.cs` | `client/BackendClient.kt` | All 21 endpoints + `StreamAnswerAsync` + `WebSocketUri` + `AccessTokenAsync`; `BackendException` with `Status/Code/CorrelationId/IsUpdateRequired/IsEntitlement` (+ `source` carrying the C#'s `Exception.Source`). SSE parser line-for-line: `event:` tracking, blank-line reset, `[DONE]`, `event: error` → 502 `ai_interrupted`, EOF without `[DONE]` → 502 `stream_truncated`, non-empty `delta` only. |
| `Client/CloudSession.cs` | `client/CloudSession.kt` | Idempotent start (GUID-N key reused, retry on 0/502/503 except `maintenance`, ≤3 attempts, 500 ms×attempt delay), heartbeat interval clamped [5,120], −1 sentinels preserved, warning at 0 < remaining ≤ 300 with `remaining/60 + 1`, end on 404/409/403, unstable warning at 3 consecutive other failures, stop with 5 s bound and swallowed failure, relay credential force-refresh flag (`Interlocked` → `AtomicInteger`). |
| `Client/CloudAnswerProvider.cs` | `client/CloudAnswerProvider.kt` | Purpose resolution identical, including `sessionId()` being consulted only for auto/live; `no_active_session` fallback only when the configured purpose is `auto` and the resolved purpose was `live`, and only around the first delta; `Map` taxonomy copied code-for-code and status-for-status. |
| `Client/CloudOptions.cs` | `client/CloudOptions.kt` | All 8 settings + `AuthReady`/`IsConfigured` with the same https-or-loopback rule (loopback = localhost / 127.0.0.0/8 / ::1, mirroring `Uri.IsLoopback`). Addition: `fromJson` — the `cloud.json` deserialization the Windows app does in `CloudService.LoadOptions` with Web options (the shipped `app/src/main/assets/cloud.json` keys are exactly these camelCase names). The file search stays with the app composition root, as `LoadOptions` stays outside the class on Windows. |
| `Client/InstallationIdentity.cs` | `client/InstallationIdentity.kt` | Same validity rule (12–64, ASCII alnum or `-`), same `inst-` + 32 lowercase hex shape, same read-trim-reuse/create behaviour. |
| `Client/UpdatePolicy.cs` | `client/UpdatePolicy.kt` | 6 h interval, 45 s startup delay, identical comparisons (compared as `java.time.Duration` so no rounding enters through `Duration` arithmetic). |
| `Client/LoopbackRedirect.cs` | `client/OAuthRedirect.kt` + `:app` Platform | See §2 (D5). The platform-independent decision table (path, state, error, code, keep-waiting cases) is `RedirectUriParser` in `:client`; `ParseQuery` is ported verbatim (first value wins, `+`→space before percent-decoding). |
| — (BCL type) | `client/TimeProvider.kt` | Stands in for .NET `TimeProvider` (`utcNow()` + a delay that defaults to real time, exactly like the .NET default timer the Windows tests rely on). New file, no Windows counterpart; used by `SupabaseAuth`/`CloudSession`. |

### `:core` slice (only what `:client` touches — the rest of each file is P3, named in §4)
| C# | Kotlin | Notes |
|---|---|---|
| `Core/Providers/Contracts.cs` | `core/providers/Contracts.kt` | **Whole file**: `ConnectionStatus`, `ProviderErrorKind` (16), `ProviderException` + `UserMessage` (all 16 key mappings), `ITranscriber`, `IAnswerProvider`. |
| `Core/Intelligence/PromptBuilder.cs` | `core/intelligence/PromptBuilder.kt` | Slice: `ChatMessage` only (the one type `:client` consumes from this file). |
| `Core/Languages/Loc.cs` | `core/languages/Loc.kt` | **Whole file**: `T/F/Get/Format/Has/Keys/TableFor/MissingKeys/Relocalize`, English fallback, reverse-table relocalization incl. the `(ref …)` suffix rule. Tables load through an installable `tableLoader` (assets on Android; embedded resources on Windows). `Format` implements .NET `string.Format` semantics for the tables' `{n}` placeholders incl. the FormatException→English fallback. |
| `Core/Languages/LanguageRegistry.cs` | `core/languages/LanguageRegistry.kt` | **Whole file**: all 10 `LanguageInfo` rows verbatim, `Find` region-stripping, `ResolveAnswerLanguage`, `AnswerLanguageRule`. `CultureInfo`→`Locale` (see DC-5). |
| `Core/Persistence/WorkspaceStore.cs` | `core/persistence/WorkspaceStore.kt` | Slice: `IDataProtector` + `NoProtection` (what `ProtectedFileTokenStore` consumes). |
| `Core/Diagnostics/InstallLayout.cs` | `core/diagnostics/InstallLayout.kt` | **Whole file** (its tests live in `UpdatePolicyTests.cs`). Path splitting follows Windows rules on any host OS — the paths it examines are Windows paths by definition. |

### `:app` platform (P2 pieces)
| Piece | Location | Notes |
|---|---|---|
| `KeystoreDataProtector : IDataProtector` | `app/platform/Platform.kt` | Android Keystore AES-256-GCM, layout IV(12)‖ciphertext+tag (§8 D3 substitute). |
| `sessionTokenStore(context)` | same | `ProtectedFileTokenStore(filesDir/session.bin, KeystoreDataProtector)` — the Windows `session.dpapi` + DPAPI composition, same class and format. |
| `AppLinkRedirectReceiver : OAuthRedirectReceiver` | same | Delivery channel for D5: `deliver(uri)` runs the shared parser; code completes the wait, error aborts with `browser_error`, anything else keeps waiting; timeout → `TimeoutException("Sign-in was not completed in the browser.")`, close → `TimeoutException("Sign-in was cancelled.")` — the C# messages. Default redirect URI `interviewassistant://oauth/callback?s=<state>` (keeps the `/callback` path the parser requires). |
| `OAuthRedirectBus` | same | Routes activity VIEW intents to the registered receiver; `MainActivity` delivers in `onCreate`/`onNewIntent` (added, `singleTop`), manifest gained the matching intent-filter. |
| `openInBrowser(context, url)` | same | The C#'s `openBrowser` callback target (system browser). See DC-2. |
| `installLocTables(context)` | same | Installs the asset loader for `Loc` (byte-identical `:core` i18n assets, merged into the APK); wired in `InterviewAssistantApplication.onCreate`. |

### Build files
- `client/build.gradle.kts`: project references became `api` (the client's public API exposes Core/Contracts types, like the transitive C# ProjectReference); `testImplementation(libs.junit)` added.
- `contracts/build.gradle.kts`: `testImplementation(libs.junit)` added.
- `core/build.gradle.kts`: serialization plugin + `kotlinx-serialization-json` (already in the catalog) for `Loc`'s table parsing — the BCL-JSON counterpart. **No new external dependencies anywhere in P2.**

## 2. The LoopbackRedirect → Android design (§8 D5, already registered)

`OAuthRedirectReceiver` (interface, `:client`) is the contract the C# class exposes:
`State` → `state`, `RedirectUri` → `redirectUri`, `WaitForCodeAsync(timeout, ct)` →
`waitForCode(timeout)`, `Dispose` → `close()`. Every C# error path exists:

| C# behaviour | Android |
|---|---|
| Wrong path or state → 404, keep waiting | `RedirectOutcome.NotOurs` → receiver keeps waiting |
| Callback without code → 400, keep waiting | `RedirectOutcome.MissingCode` → keeps waiting |
| `error`/`error_description` → `AuthException("browser_error", error)` | `RedirectOutcome.Error` → same exception, thrown from `waitForCode` |
| Timeout → `TimeoutException("Sign-in was not completed in the browser.")` | identical |
| Listener disposed → `TimeoutException("Sign-in was cancelled.")` | identical (receiver `close()`) |
| Caller cancellation → `OperationCanceledException` | coroutine `CancellationException` propagates |
| Browser page texts (`browser_signed_in` etc.) | same Loc keys exposed as `RedirectOutcome.browserMessageKey` for the platform layer to show |

`SupabaseAuth` receives the receiver as a parameter of `signInInteractiveAsync` /
`recoverPasswordAsync` instead of constructing a listener internally — the platform owns the
redirect surface on Android. The receiver's lifecycle is the caller's (the C# disposes its own
listener; the Android bus-registered receiver outlives a single flow by design).

## 3. Completeness checklist (every public C# member → Kotlin counterpart)

**ApiContracts.cs** — ApiError✓ Entitlements✓ BillingStates (Trial✓ TrialExhausted✓ Active✓
Canceling✓ PastDue✓ Paused✓ Expired✓ Revoked✓) UsageSummary✓ AccountDto✓ SubscriptionDto✓
DeleteAccountResponse✓ RegisterDeviceRequest✓ DeviceDto✓ SyncDocument✓ UpsertDocumentRequest✓
StartSessionRequest✓ RealtimeCredential✓ StartSessionResponse✓ HeartbeatRequest✓
HeartbeatResponse✓ AnswerMessage✓ AnswerStreamRequest✓ AnswerPurposes (Live✓ Prep✓ Manual✓)
TranscriptionContextDocument✓ PrepJobResponse✓ PlanDto✓ CheckoutRequest✓ UrlResponse✓
RuntimeConfig✓ AuthClientConfig✓ ProductLinks✓ — **27/27 types, all fields, all defaults.**

**Pkce.cs** — CreateVerifier✓ Challenge✓ CreateState✓ Base64Url✓ IsValidVerifier✓.
**UpdatePolicy.cs** — CheckInterval✓ StartupDelay✓ ShouldCheck✓ ShouldCheckNow✓ CanApplyNow✓ NormalizeChannel✓.
**CloudOptions.cs** — ApiBaseUrl✓ SupabaseUrl✓ SupabaseAnonKey✓ OAuthProviders✓ EmailPassword✓
EmailConfirmedRedirectUrl✓ ClientVersion✓ AuthReady✓ UpdateUrl✓ IsConfigured✓.
**InstallationIdentity.cs** — GetOrCreate✓ IsValid✓.
**Auth.cs** — AuthException(+Code)✓ AuthSession✓ ITokenStore (Load✓ Save✓ Clear✓)
ProtectedFileTokenStore (Load✓ Save✓ Clear✓) SupabaseAuth: SessionChanged→listener list✓
SignedOutReason✓ Session✓ IsSignedIn✓ BuildAuthorizeUrl✓ SendMagicLinkAsync✓ ExchangeCodeAsync✓
SignUpResult✓ SignUpAsync✓ SignInWithPasswordAsync✓ RecoverPasswordAsync✓
IsPerAddressRateLimit✓ RevealsAccountExistence✓ UpdatePasswordAsync✓ SignInInteractiveAsync✓
GetAccessTokenAsync✓ SignOutAsync✓ SignOutLocal✓ Friendly✓ (+ private RequireReady/Send/Request/
TokenRequestAsync/StoreTokensAsync/Fail — all ported as privates).
**BackendClient.cs** — BackendException (Status✓ Code✓ CorrelationId✓ IsUpdateRequired✓
IsEntitlement✓) Json✓ ForAuth✓ LastCorrelationId✓ ClientVersion✓ AccessTokenAsync✓
WebSocketUri✓ ConfigAsync✓ PlansAsync✓ MeAsync✓ ExportAsync✓ DeleteAccountAsync✓
CheckoutAsync✓ PortalAsync✓ RegisterDeviceAsync✓ DevicesAsync✓ RevokeDeviceAsync✓ ListAsync✓
PutAsync✓ DeleteDocumentAsync✓ StartPreparationAsync✓ StartSessionAsync✓ HeartbeatAsync✓
RenewRealtimeSecretAsync✓ PutTranscriptionContextAsync✓ StopSessionAsync✓ StreamAnswerAsync✓
RequestTimeout✓ (+ Send/SendRaw/ToError privates✓).
**CloudSession.cs** — Id✓ HeartbeatIntervalSeconds✓ RemainingSeconds✓ PeriodRemainingSeconds✓
TodayRemainingSeconds✓ Language✓ TargetId✓ Active✓ EndReason✓ Credential✓ RelayEndpoint✓
RelayHeaders✓ Ended/RemainingChanged/Warning→listener lists✓ StartAsync✓
NextConnectionCredentialAsync✓ ForceTokenRefresh✓ HeartbeatOnceAsync✓ StopAsync✓ DisposeAsync✓.
**CloudAnswerProvider.cs** — Name✓ Purpose✓ LastPurpose✓ StreamAsync✓ Map✓.
**LoopbackRedirect.cs** — State✓ RedirectUri✓ WaitForCodeAsync✓ ParseQuery✓ (internal in C#,
public parser object here so the platform receiver and tests share it) — `Port` has **no**
counterpart: there is no listening socket on Android (D5); the receiver is identified by its
redirect URI instead. Accounted, not dropped.
**Core Providers/Contracts.cs** — ConnectionStatus✓ ProviderErrorKind✓ ProviderException
(Kind✓ Code✓ CorrelationId✓ UserMessage✓) ITranscriber (all 6 events→listener lists✓
LastStoppedItemId✓ StartAsync✓ StopAsync✓ SendAudio✓ Status✓ DisposeAsync→disposeAsync✓)
IAnswerProvider (Name✓ StreamAsync✓).
**Core Loc.cs** — Language✓ LanguageChanged→listeners✓ IsRtl✓ Culture→`locale`✓ T✓ F✓ Get✓
Format✓ Has✓ Keys✓ TableFor✓ MissingKeys✓ Relocalize✓.
**Core LanguageRegistry.cs** — LanguageInfo (+DisplayName✓ ToString✓) All✓ Find✓ IsSupported✓
IsRtl✓ EnglishName✓ FormattingCulture→formattingLocale✓ ResolveAnswerLanguage✓ AnswerLanguageRule✓.
**Core WorkspaceStore.cs (slice)** — IDataProtector (Protect✓ Unprotect✓) NoProtection✓.
**Core InstallLayout.cs** — IsInstalled✓.
**Core PromptBuilder.cs (slice)** — ChatMessage✓.

### Language-level adaptations applied uniformly (not per-file divergences)
- `CancellationToken` parameters → coroutine cancellation (the caller's scope). `TimeSpan` → `kotlin.time.Duration`, `DateTimeOffset` → `java.time.OffsetDateTime`, `Guid` → `UUID`.
- C# `event`s → listener lists with `add…Listener` registration (Kotlin has no events).
- `HttpClient` → injected `OkHttpClient`; tests fake it with an interceptor (the C# tests fake the `HttpMessageHandler` — the same seam). `BackendClient` additionally takes `baseUrl` because OkHttp has no `BaseAddress`.
- `Func<…>` delegates → Kotlin function types; `IAsyncEnumerable<string>` → `Flow<String>` (plus `AnswerStreamSession` where the C# consumer drives the enumerator by hand).
- `async Task` methods → `suspend fun`, named `…Async` as in C#.

## 4. Not ported in P2 (named, with phase — nothing silently missing)
- `WorkspaceStore` document store, `ProfileBundle`, `ExportFile`, `SecretFilter`, `WorkspaceLocator` (rest of `WorkspaceStore.cs` + Persistence package) → **P3**.
- `ConversationTurn`, `PromptRequest`, `PromptBuilder` (rest of `PromptBuilder.cs`) and all other Core files not listed above (incl. `Diagnostics/Clock.cs`, `Backoff`, `Metrics`) → **P3**.
- The Compose UI that *shows* the redirect outcome pages and the sign-in screens → **P4** (the receiver, bus and parser are complete; only their visual host is a later phase).
- Device registration *call site* (composition) → P4/P6 wiring; `BackendClient.registerDeviceAsync` itself is ported and the platform value is `"android"` per §7 P2.

## 5. Divergence candidates registered by P2 (all small; §8 D3/D5 already cover the big two)
- **DC-2** Custom Tabs: `androidx.browser` is **not in the version catalog**, so `openInBrowser` uses a plain `ACTION_VIEW` browser intent. Behaviour (system browser, PKCE) is identical; the in-app tab presentation is not. Recommendation: add `androidx.browser` in P4 with the UI shell — owner-visible dependency decision, not taken unilaterally here.
- **DC-3** Empty-body POST/PUT: OkHttp refuses a null body where HttpClient sends none, so bodyless POST/PUT requests carry `Content-Length: 0`. The backend and Supabase treat both as an empty body; no behaviour change observed or expected.
- **DC-4** Verified-https App Link host is not pinned anywhere in the Windows source (the operator's redirect domain is a deployment value). The manifest therefore carries only the D5 custom-scheme fallback filter; the https `autoVerify` filter must be added when the host is pinned. The shared parser enforces path+state+PKCE identically on either surface.
- **DC-5** `LanguageRegistry.FormattingCulture`'s Arabic-Gregorian calendar clone has no `java.util.Locale` representation; it is exposed as `usesGregorianCalendar(code)` for date-formatting call sites (Reports/exports, P3). The only P2-visible consumer — number formatting in `Loc.Format` — is exact.
- **DC-6** Answer-stream line reads are blocking IO reads (on `Dispatchers.IO`); coroutine cancellation lands between lines or when the collector closes the session, where C#'s `ReadLineAsync(ct)` cancels mid-read. No delta-level behaviour differs for well-formed streams.
- **DC-7** `InstallationIdentity.getOrCreate` with a directory-less path: C# throws (null directory + `!`); Kotlin creates the file in place. Unreachable from the app's own call sites.
- **DC-8** `SupabaseAuth.fail` with a non-object JSON error body: C# lets an `InvalidOperationException` escape; Kotlin falls back to `http_<status>`. Only reachable on a malformed auth-server error body.

## 6. Tests (written in this phase, per §5 discipline)
- `contracts/src/test/…/ApiContractsJsonTest.kt` — **11 tests**, new contract fixtures per §5.4: literal expected JSON for ApiError, UsageSummary (−1/0 defaults), StartSessionResponse, HeartbeatResponse, AnswerStreamRequest, StartSessionRequest, fractional-second dates, a full `/me` payload round-trip, the RuntimeConfig public shape (+defaults), SyncDocument/UpsertDocumentRequest, BillingStates/AnswerPurposes string sets.
- `client/src/test/…/CloudAuthTests.kt` — port of `CloudAuthTests.cs`: **22 test methods covering all 37 C# cases** (19 [Fact] + 3 [Theory] with 5+3+10 [InlineData]). Same names (camelCase rendering), same inputs, same expected values. xUnit theories run as case loops inside one JUnit test each. The C#'s `Stub` handler → OkHttp interceptor fake; `LoopbackRedirect` → `TestLoopbackReceiver`, a test implementation of `OAuthRedirectReceiver` over a real loopback socket with the C# listener's exact accept rules, exercising the shared `RedirectUriParser`. Loc tables in tests are the real byte-identical `:core` assets, loaded from disk by the test loader.
- `client/src/test/…/UpdatePolicyTests.kt` — port of `UpdatePolicyTests.cs`: **6 test methods covering all 10 C# cases** (UpdatePolicyTests 4 [Fact] + channel [Theory]×5, InstallLayoutTests 1 [Fact]).

Totals: C# 28 methods / 47 cases → Kotlin 28 methods / 47 cases, plus 11 new contract fixtures.
CI (`:client:testDebugUnitTest`, `:contracts:testDebugUnitTest`) is the execution check.

## 7. Honest compile-risk notes (no Gradle run was possible in this environment)
- `CloudOptions` is a `@Serializable` **class** (not data class) with body `var`s — a supported kotlinx pattern, but the least-common shape in this codebase; if the plugin rejects any member, the fallback is a data class with the same wire names (wire format unchanged).
- `SupabaseAuth.TokenResponse`/`TokenUser` are private nested `@Serializable` classes referenced via `serializer()` — standard, flagged only because they are private.
- The nullable-GUID/DateTime serializers use the documented `decodeNotNullMark` pattern for `@Serializable(with=…)` on nullable properties.
- Everything else uses only stable APIs: OkHttp 4.12, kotlinx-serialization 1.7.3, coroutines 1.9, JUnit 4 — all already in the catalog; no experimental opt-ins are used anywhere in P2.
