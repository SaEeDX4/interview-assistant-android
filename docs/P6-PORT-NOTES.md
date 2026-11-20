# P6 — Billing & account completion: port notes

Scope (TRANSFER-MAP §7 P6 row): the real update service against the project's own release
system (D4), Google Play Billing behind a testable seam (OD-1, D7), the Paddle path in
Custom Tabs, developer-mode/account completion verification, and the wiring that makes the
app constructible end-to-end (DC-91 closed). Nothing here is claimed compiled or tested —
CI is the first execution check.

## What was built

### Updates (the C# `UpdateService.cs`, 194 lines, + `Authenticode.cs` → D4)

- `platform/updates/UpdateManifest.kt` (136) — the release-manifest model and the pure
  decisions: `ReleaseSelection` applies the release system's own validity rules (the website
  `standards/RELEASES.md` + `app.js` `validFile`, mirrored exactly: non-empty name, `https://`
  URL, finite size ≥ 0, 64-hex SHA-256) and selects the first valid **android** release on the
  settings channel with **no cross-channel fallback** (the C# updater's `ExplicitChannel`
  semantics); `VersionCompare` is the semver-style ordering (release > prerelease; numeric
  identifiers before alphanumeric; build metadata ignored).
- `platform/updates/UpdateCore.kt` (262) — the C# state machine member-for-member over fake
  ports: the construction gates in the C#'s order (blank feed → `upd_not_enabled`; non-https
  → `upd_not_https`; unsigned build → `upd_unsigned`), the same English statuses + `upd_*`
  Loc keys/args, `tickAsync`/`checkNowAsync` guards (pending/checking), `UpdatePolicy`
  timing (45 s startup delay, 6 h interval, never during a session — `upd_after_session`),
  download → **SHA-256 verified first** (the manifest hash; mismatch deletes the file) →
  **same-signer verified** (certificate digest equality, `MessageDigest.isEqual`) → pending
  (`upd_ready`); failures → `upd_failed` with the C#'s plain/"(unexpected)" log buckets.
  Coroutine cancellation is rethrown, never reported as a failed check.
- `platform/UpdateService.kt` (135) — the thin `IUpdateService` adapter at the TRANSFER-MAP
  row-32 path: OkHttp transport, PackageManager signer digests (installed + archive), the
  FileProvider installer handoff (`ACTION_VIEW`, package-archive MIME — the system install
  prompt is the user's confirm), cache-dir download store.

### Play Billing (OD-1)

- `platform/billing/BillingModels.kt` (118) — the Android-free model: `PlayBilling`
  (`PRO_PRODUCT_ID = "pro_monthly"`, provisional — DC-187), the `BillingResponseCode`
  constants, `BillingProduct`/`BillingPurchase`, the sealed `BillingState`
  (Idle/Connecting/Ready/PurchaseInFlight/PurchasePending/**PurchasedUnverified**/Unavailable/
  Failed), and `BillingOfferSelection` (base plan = the `offerId == null` offer wins; the
  displayed price is the infinite-recurring phase's, else the last phase's).
- `platform/billing/PlayBillingGateway.kt` (37) — the JVM-testable seam (no Android types).
- `platform/billing/BillingCoordinator.kt` (160) — connect → restore (owned purchase:
  acknowledge-if-needed → `PurchasedUnverified`, token held as `purchaseTokenForAccount`;
  owned pending → `PurchasePending`) → product query → `Ready`; launch mapping
  (cancel returns to `Ready(product)`); listener mapping incl. `ITEM_ALREADY_OWNED` → restore.
- `platform/billing/PlayBillingClient.kt` (177) — the real gateway over **Billing Library
  9.1.0** (current release 2026-06-18; new apps/updates must be on v8+ since 2026-08-31):
  pending purchases (one-time products), auto service reconnection, the PBL-8
  `QueryProductDetailsResult` listener signature, acknowledge via `AcknowledgePurchaseParams`.

### Paddle path + platform halves

- `platform/CustomTabsUrlLauncher.kt` (43) — Custom Tabs with an `ACTION_VIEW` fallback;
  installed by MainActivity as `CloudService.urlLauncher`, so every gated open (Paddle
  checkout/portal, legal pages) renders in a Custom Tab. The https/DNS gate itself is
  unchanged in CloudService; the 5 s × 15 min upgrade poll is untouched (P4-C1).
- `platform/BatteryExemption.kt` (43) — the D12 decision (probe + ask at most once per
  process), platform halves installed by MainActivity (`PowerManager` probe;
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt), triggered when a session first
  becomes active.

### Wiring (sanctioned points only)

- `AppComposition.kt` — the throwing update provider is **deleted**; `updateService` (real,
  feed = `cloud.options.updateUrl`), `playBillingClient`, and `playBilling` lazies added;
  MainViewModel receives the real service (DC-189).
- `MainActivity.kt` — installs the Custom Tabs launcher, starts the billing coordinator and
  provides its purchase-flow activity (`launchPlayBillingUpgrade()` entry), installs the
  battery-exemption halves + the session-start trigger; onDestroy clears the activity provider.
- `AndroidManifest.xml` — `com.android.vending.BILLING` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- `app/src/main/assets/cloud.json` — `updateUrl` → the release manifest (DC-183).

## Scope-4 audit (developer mode + account completion) — complete as built, no rebuild

- Upgrade → poll → Pro: covered at the client level by `CloudServiceTests`
  (`upgrade_PollsUntilPaid_ThenClearsPending`, `upgrade_NeverPaid_TimesOutAndClearsPending`,
  `upgrade_NonHttpsCheckoutLink_Refused`, `portal_SignedIn_OpensThePortalUrl_SignedOut_Refused`)
  and the Account dispatch by `HomeAccountTests.billing_Upgrade_VisibleOnTrial_AndCheckoutRuns`.
- Delete 2-step + local-data choice: `HomeAccountTests.deleteAccount_FlowOrdering_AndCompletion`,
  `deleteAccount_BlockedSubscription_ManageBillingInstead`, `deleteAccount_KeepChoice_DoesNothing`.
- Export via SAF + SecretFilter: `HomeViewModel` applies `SecretFilter.cleanChecked` to the
  export text (line ~3342); covered by `HomeDataExportTests`, `ui/home/StagedExportsTest`,
  and the :core SecretFilter suite (P3).
- Developer mode: `BuildFlavor.Developer` (false in customer builds, DC-78), the MainViewModel
  `developerKeyMode` branch in `BuildEngine` (direct providers), and the Settings developer
  key panel incl. remove/test in `SettingsPageControllerTests`. Account parity: `Rc5AccountTests`.
- No coverage gap was found that new tests would honestly close; the remaining account risks
  are device-only (below).

## Tests added (JVM only; 44 @Test)

- `platform/updates/UpdateManifestTest` (3) — channel-exact selection, invalid-release
  skipping, APK picking; the version-ordering table (incl. symmetry + `isNewer`).
- `platform/updates/UpdateCoreTest` (19) — the three construction gates by exact English
  status; startup-delay/interval/session tick gating; session refusal status; up-to-date for
  older/equal; release-supersedes-own-prerelease; download→hash→signer→ready; SHA mismatch,
  signer mismatch, and unsigned download → untrusted + file deleted; transport failure →
  failed status; no re-check while pending; installer handoff refused mid-session / allowed
  idle / refused with nothing pending; `signersEqual` truth table.
- `platform/billing/BillingCoordinatorTest` (15) — connect failure mapping (Unavailable vs
  Failed), start-once idempotence, product query → Ready, product-missing → Ready(null) +
  launch refused, owned-purchase restore + acknowledge + token, owned pending, launch →
  in-flight → completion, cancel at launch and via listener, pending via listener,
  already-owned → restore, error code → Failed, other products' purchases ignored.
- `platform/billing/BillingOfferSelectionTest` (4) — base-plan preference, first-offer
  fallback, renewing-phase display price, product construction.
- `platform/BatteryExemptionTest` (3) — state mapping, exempt → never asks, ask-once.

## Divergences (DC-182…)

- **DC-182 — Dependencies added (the DC-38/DC-85 precedent):** `com.android.billingclient:
  billing` **9.1.0** and `androidx.browser:browser` **1.9.0** in `gradle/libs.versions.toml`
  + `:app`. Billing 9.1.0 is the current library (PBL 8+ is mandatory for new apps/updates
  since 2026-08-31; 9.x targets SDK 35 like this app). Browser 1.9.0 is pinned instead of
  the newer 1.10.0 for compileSdk-35 safety; the Custom Tabs surface used is identical.
- **DC-183 — The bundled `cloud.json` `updateUrl` is set to the project's release manifest**
  (`https://admin.cybervalida.online/manifest`). This is operator build configuration of the
  same field the C# reads (`Cloud.Options.UpdateUrl` → `new UpdateService(Settings, …)`); the
  Windows shipped value is empty because the Windows operator left the Velopack feed unset.
  No test pins the bundled value (verified by search before the edit).
- **DC-184 — UpdateService realization (D4):** a manifest client replaces Velopack (the
  project's own release system is the Android feed — Step 41). Selection is channel-exact
  with no fallback; a manifest release without a valid APK file is not selectable. The
  manifest's SHA-256 is verified **before** the signer check (a hash mismatch already proves
  the bytes are not the published build). Same-signer = SHA-256 digest equality of the first
  signing certificate — stricter than the C#'s Authenticode subject comparison (subjects can
  collide across certificates; digests cannot), and the honest Android equivalent of D4's
  "verify before install". File names from the untrusted manifest are used as bare names only.
- **DC-185 — `applyOnExit` is a logged no-op; Restart-to-update is the install path.** Android
  has no exit-install hook (the D4 residual). The verified download stays in cache;
  `tryApplyAndRestart` hands it to the system installer, whose prompt is the user's confirm —
  never a fake silent apply. The shared `upd_ready` wording ("installs when you close the
  app") is kept verbatim for lockstep; the behavioral difference is recorded here, not hidden.
- **DC-186 — Paddle checkout/portal open in Custom Tabs** (was: the generic external open).
  `CloudService.urlLauncher` had never been installed in production before P6 — `openExternal`
  always refused; MainActivity now installs the Custom Tabs launcher (fallback `ACTION_VIEW`).
  The https/DNS gate and the upgrade poll are exactly as P4-C1 built them.
- **DC-187 — The D7 honest boundary: Play purchases are never granted entitlement client-side.**
  The Play client is complete (connect, query `pro_monthly`, launch, listener, acknowledge,
  restore) and the coordinator holds a completed purchase's token for the account layer
  (`BillingState.PurchasedUnverified`). The Account Upgrade button's verified dispatch
  (HomeViewModel → CloudService Paddle checkout + poll) is **not** rerouted to Play in P6:
  Play purchase verification lives server-side, the shared backend is read-only Windows-repo
  code, and without that bridge a Play purchase would take payment with no entitlement path.
  The Play launch entry is hosted at the activity level (`MainActivity.launchPlayBillingUpgrade`);
  rerouting the button is a one-line change that must land together with the server-side
  bridge (a separate owner-approved backend step). Product id `"pro_monthly"` is provisional
  pending the owner's Play Console setup (OD-3 — the owner's deferred check; no console state
  is invented here).
- **DC-188 — The battery-optimisation exemption flow (D12, deferred from P5)** is hosted by
  MainActivity and asked at most once per process when a session first becomes active, via
  the platform's own prompt + the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission. The
  PreInterview readiness surface gains no battery row — Windows has no battery check to
  mirror. Note for P8: Play policy restricts this permission (justification required); the
  direct-APK channel is unaffected.
- **DC-189 — DC-91 is FULLY resolved.** Both halves are real now (capture in P5, updates
  here); `AppComposition` constructs the whole object graph and the app is constructible
  end-to-end. This also unblocks the P5 §7 device gate, whose session could not run while
  the view-model construction threw.

## Deferred by name (owner/device step — NOT CI-verifiable, not claimed done)

1. **A real Play purchase** against the owner's Play Console (depends on OD-3: the owner's
   deferred check of his developer account + creating the `pro_monthly` subscription), and
   the server-side Play→backend verification bridge (separate owner-approved backend step).
2. **Upgrade → poll → Pro against the Paddle sandbox on a device** (checkout in a Custom Tab,
   poll completion, entitlement refresh in the Account page).
3. **Export/delete end-to-end on a device** (SAF picker, file contents after SecretFilter,
   the 2-step delete with both local-data choices).
4. **The combined P5+P6 real-device session** (P5 §7 gate: relay transcription, metering vs
   the server ledger, pause/stop/expiry billing tails, 60-minute soak, D1 in both capture
   modes) — plus the carried-forward P2 device items (real sign-in + device registration as
   platform `android` + config/plans/account fetch against the live shared backend) and a
   Backend.Tests re-run.
5. **Update end-to-end on a device**: publish a signed newer APK through the release panel,
   confirm the app offers it, the signer check passes for the same key (and fails for a
   differently-signed build), and the installer handoff completes.
