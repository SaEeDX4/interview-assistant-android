# Third-Party Notices — Interview Assistant for Android

The Android counterpart of the Windows product's `THIRD_PARTY_NOTICES.md`
(TRANSFER-MAP §7 P7 hygiene: licence compliance mirrors the Windows posture).
Every dependency of the Android build is listed below with its licence.
The list is pinned by `app/src/test/java/com/interviewassistant/app/LicenseComplianceTests.kt`,
which parses `gradle/libs.versions.toml` and fails if a shipped dependency is
missing here (or an entry here names no real dependency).

## Runtime dependencies (shipped in the app)

| Dependency | Version | Licence |
|---|---|---|
| `androidx.core:core-ktx` | 1.15.0 | Apache License 2.0 |
| `androidx.activity:activity-compose` | 1.9.3 | Apache License 2.0 |
| `androidx.compose:compose-bom` | 2024.12.01 | Apache License 2.0 |
| `androidx.compose.ui:ui` | (Compose BOM 2024.12.01) | Apache License 2.0 |
| `androidx.compose.ui:ui-graphics` | (Compose BOM 2024.12.01) | Apache License 2.0 |
| `androidx.compose.ui:ui-tooling` | (Compose BOM 2024.12.01) | Apache License 2.0 |
| `androidx.compose.ui:ui-tooling-preview` | (Compose BOM 2024.12.01) | Apache License 2.0 |
| `androidx.compose.material3:material3` | (Compose BOM 2024.12.01) | Apache License 2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache License 2.0 |
| `androidx.browser:browser` | 1.8.0 | Apache License 2.0 |
| `com.android.billingclient:billing` | 9.1.0 | Apache License 2.0 |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache License 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Apache License 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | Apache License 2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.21 (Kotlin plugin) | Apache License 2.0 |

Transitive runtime dependencies pulled in by the above (also shipped):
`com.squareup.okio:okio` (Apache License 2.0), `org.jetbrains.kotlinx:kotlinx-coroutines-core`
(Apache License 2.0), `org.jetbrains.kotlinx:kotlinx-serialization-core` (Apache License 2.0),
`org.jetbrains:annotations` (Apache License 2.0), and the AndroidX annotation/collection
libraries (Apache License 2.0).

## Test-only dependencies (not shipped)

| Dependency | Version | Licence |
|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 |
| `androidx.test.ext:junit` | 1.2.1 | Apache License 2.0 |
| `androidx.test.espresso:espresso-core` | 3.6.1 | Apache License 2.0 |

## Notes

- The Windows product ships PdfPig (Apache License 2.0) and NAudio (MIT) notices; the
  Android port uses neither library (PDF text extraction is a hand-written layer over the
  platform text APIs — TRANSFER-MAP D6 / P3-PORT-NOTES DC-10; audio capture is the Android
  platform API), so those notices have no Android counterpart.
- Velopack (Windows updates) is likewise not used on Android; updates run through the
  project's own release system (TRANSFER-MAP D4).
- Packaging step (P8): this file ships inside the release APK/AAB assets alongside the
  app, mirroring the Windows release build copying its notices into the package.
