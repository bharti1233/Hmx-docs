# Architecture - Kern

## Overview

Kern is a single-Activity Android app built with Kotlin and Jetpack Compose. It registers intent filters for supported MIME types, so any file manager or app on the device can hand a file directly to Kern via "Open with". Kern reads and writes files in place - no copying to an internal folder, no cloud sync, no data leaving the device.

## Key components

| Component | Location | Responsibility |
|-----------|----------|----------------|
| File browser | `src/browser/` | Scans Documents + Downloads, renders the file list |
| Intent receiver | `src/browser/IntentHandler.kt` | Receives "Open with" intents, routes to the correct editor |
| CSV editor | `src/editors/csv/` | OpenCSV-backed table editor |
| Excel editor | `src/editors/excel/` | Apache POI xlsx |
| Word editor | `src/editors/word/` | Apache POI docx |
| PowerPoint editor | `src/editors/pptx/` | Apache POI pptx |
| EPUB editor | `src/editors/epub/` | jsoup + ZIP, preservation-first save |
| PDF viewer / tools | `src/editors/pdf/` | Android `PdfRenderer` for viewing, Qyra bridge for merge/split |
| PDF bridge | `src/pdf-bridge/` | Native PDF tool bridge (JNI/NDK, pure lopdf) |
| Settings | `src/settings/` | Settings screens |
| Shared | `src/shared/` | Compose theme, common components, document IO utilities |

## Navigation

Single Activity. Compose `NavHost` with these destinations:

```
FileBrowser (home)
  -> CsvEditor(filePath)
  -> ExcelEditor(filePath)
  -> WordEditor(filePath)
  -> PdfEditor(filePath)
  -> PptEditor(filePath)
  -> EpubEditor(filePath)
Settings (from the browser)
```

External "Open with" intents bypass FileBrowser and land directly on the correct editor.

## File access model

- Primary: `MANAGE_EXTERNAL_STORAGE` (scoped guidance toward Documents + Downloads)
- Secondary: SAF (Storage Access Framework) for files opened via picker or "Open with"
- Intent filters registered for supported MIME types
- No app-owned folder. Files are read and written at their original path.

## PDF bridge

PDF **viewing** uses Android's framework `PdfRenderer` (`src/editors/pdf/PdfDocument.kt`)
and needs no native code. PDF **tools** - merge and split - are delegated to a
native Rust crate through the JNI/NDK bridge declared in `src/pdf-bridge/`. The
Kotlin side exposes a small handle-based surface (open, page count, render,
close); the native library is loaded at runtime.

The native side is `kern-bridge` in the Qyra fork: pure `lopdf` + `jni`, with no
MuPDF and no `ndk-context`, so it takes plain filesystem paths. It is built by
`cargo ndk` into `jniLibs/<abi>/libkern_pdf.so` (gitignored, produced in CI by
`.github/actions/build-pdf-bridge` from a pinned Qyra commit). When the library is
absent, `QyraPdf.available` is false and the tools degrade gracefully instead of
crashing. See [src/pdf-bridge/README.md](../src/pdf-bridge/README.md).

## Data flow

1. User taps a file in the browser, or shares a file to Kern via "Open with".
2. `IntentHandler` resolves the MIME type, picks the editor, and passes the URI.
3. The editor opens the file (via SAF URI or direct path) and loads it into memory.
4. Compose renders the editable state.
5. On save, the editor writes back to the original URI/path.

## Build and CI

Single Gradle module. The Gradle wrapper is committed
(`gradlew`, `gradle/wrapper/`), pinned to Gradle 8.10.2 and checksum-verified, so a
clean checkout builds without a preinstalled Gradle. JDK 17, compileSdk 35,
build-tools 35.0.0.

GitHub Actions (`.github/workflows/`) runs the checks:

| Workflow | Trigger | Runs |
|----------|---------|------|
| `android.yml` | push/PR to `main` | `assembleDebug` (+ native bridge), `testDebugUnitTest`, `lintDebug`; uploads the debug APK, test report and lint report |
| `release-build-check.yml` | push/PR to `main` | `assembleRelease` (R8 + shrinking), verifies the APK is unsigned, uploads the unsigned release APK and the R8 mapping |
| `release.yml` | version tag | builds and publishes a **debug** APK (no release signing key exists yet) |

### Known CI warnings (accepted, 2026-10-16)

`testDebugUnitTest`, `lintDebug`, `assembleDebug` and `assembleRelease` all pass.
The warnings below are the residue; none of them fails a job.

- **Lint: 0 errors, 49 warnings.** They are informational, not correctness
  findings: 27 `GradleDependency` and 3 `AndroidGradlePluginVersion` ("a newer
  version exists"), 5 `ObsoleteLintCustomCheck` (library lint jars compiled
  against a different lint API are skipped), 2 `DefaultLocale`, 2
  `TrustAllX509TrustManager`, 2 `UnusedResources`, 2 `MonochromeLauncherIcon`,
  1 each `ScopedStorage`, `OldTargetApi`, `RedundantLabel`, `SelectedPhotoAccess`,
  `ObsoleteSdkInt`, `UseOfNonLambdaOffsetOverload`. Bumping dependencies is
  deliberately not part of a build/CI change; it belongs with the code work that
  can re-test them.
- `ScopedStorage` is worth reading: Play limits `MANAGE_EXTERNAL_STORAGE`. That is
  a distribution decision, tracked in
  [decisions/0001](decisions/0001-licensing-and-distribution.md).
- **Node 20 deprecation notices** for `actions/checkout@v4`,
  `actions/upload-artifact@v4` and `gradle/actions/setup-gradle@v4`: those tags
  target Node 20 and the runner forces Node 24. They still work. `setup-java` was
  the only one *explicitly* deprecated ("will no longer receive updates"), so it
  was moved to `@v6`; its inputs used here (`distribution`, `java-version`) did not
  change.
- **`gradle/actions` v6 is deliberately not adopted yet.** From v6 the caching
  component is no longer open source and upgrading means accepting Gradle's Terms
  of Use for it. For an AGPL-3.0, no-telemetry project that is the owner's
  decision, not a build fix - see
  [decisions/0001](decisions/0001-licensing-and-distribution.md). Staying on `@v4`
  costs a deprecation notice and nothing else.
- `release.yml` still publishes a **debug-signed** APK on tags. Publishing an
  unsigned release APK is not possible (it cannot be installed); a signed release
  needs a keystore in repository secrets and was explicitly out of scope.

## Dependencies

| Library | Purpose | License |
|---------|---------|---------|
| Apache POI | xlsx, docx, pptx | Apache-2.0 |
| OpenCSV | CSV parsing | Apache-2.0 |
| jsoup | EPUB (XHTML) parsing/editing | MIT |
| Jetpack Compose / AndroidX | UI | Apache-2.0 |
| Kotlin Coroutines | Async file ops | Apache-2.0 |
| Qyra `kern-bridge` (native `.so`) | PDF merge/split | GPL-3.0 |
