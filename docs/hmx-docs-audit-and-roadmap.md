# HMX Docs — Kern Repository Audit and 10-Phase Roadmap

**Scope note.** Everything below was gathered read-only from `/mnt/sdcard/AIProjects/kern-main` plus read-only queries to the upstream GitHub API and to Apache POI 5.5.1 sources (to verify library behaviour the code depends on). No project file was created, edited, moved, or deleted. No build, test, lint, or APK task was run — a scratch build in `/tmp` was started and then aborted on instruction; the scratch copy was deleted and no build process remains. No device or emulator verification was performed. Claims below are marked **[verified-code]** (read from this repository's source), **[verified-upstream]** (from the upstream repo's history/CI via API, or from POI's published source), **[inference]**, or **[unknown]**.

*Phase 1 note:* the audit was written against a snapshot with no `.git`, and a few statements describe that snapshot (for example "the local directory is not a git checkout"). That snapshot is now the first commit of this repository; those statements are history, not current state. Nothing else in this document has been revised.

---

## 1. Executive Summary

Kern is a real, working, single-module Android app (Kotlin + Compose, package `dev.kern`, AGPL-3.0) that opens DOCX/XLSX/PPTX/CSV/PDF/EPUB in place through Android's "Open with" system and edits them offline with zero network permissions. The DOCX path is built on Apache POI 5.5.1 (`XWPF`) with a custom block model and a surgical-write save design.

The honest headline for HMX Docs: **the Word editor is the least mature of Kern's editors.** It is a paragraph-block text editor with a formatting toolbar, not a page-layout word processor. Compared with the repo's own PowerPoint editor (0.1.11.0: theme resolution, geometry, move/resize, richer round-trip) and the EPUB editor (preservation-first save that copies untouched ZIP entries byte-for-byte), the Word editor has:

- **No pagination, no page geometry, no headers/footers rendering** — one continuous "paper" column; footer literally prints `Page 1 of 1 · N blocks · N words · N chars` (`src/editors/word/WordEditorScreen.kt:342`).
- **A lossy write path for edited paragraphs**: all runs in an edited paragraph are deleted and recreated as plain runs, so hyperlinks, fields, footnote references, character styles, highlight/shading, caps, and page breaks inside that paragraph are destroyed (`WordDocument.kt:287-300`, `331-339`).
- **Confirmed index-mapping defects**: blocks and `doc.bodyElements` are not always 1:1 (multi-image or image+text paragraphs), so edits can be applied to the wrong element; and deleting a table column in the UI never removes cells from the file (`WordDocument.kt:108-120`, `302-329`).
- **Undo/redo marks the entire document dirty**: one undo causes the next save to rewrite every paragraph and every table through the lossy writers (`WordEditorViewModel.kt:654-664`).
- **Non-atomic in-place save**: `openOutputStream(uri, "wt")` / `File.writeBytes` with no temp file, no backup, no `.bak` (`DocumentIo.kt:36-43`).

These are fixable without replacing the engine, and the repository already contains the right pattern (EPUB's preservation-first ZIP rewrite) to make DOCX round-trips safe. But "Microsoft Word-like editing experience" in the sense of pagination and layout fidelity is **not** a POI feature — it requires building a layout/pagination layer in this codebase, which is the single largest engineering item on the roadmap.

**Recommendation: Option A+ (keep Kern's Kotlin/Compose app and POI as the document engine; add a preservation-first save path, a widened document model, and a purpose-built pagination/render layer), with the commercial-SDK option only considered if HMX Docs' licensing constraints change.** Details in §6.

**Go/No-Go: GO** to proceed with Phase 1 on this repository, conditional on (a) a written decision about HMX Docs' licensing/distribution model (§5, §12), and (b) treating the confirmed data-loss defects as P0 before any compatibility claims are made.

---

## 2. Repository Inventory and Architecture

### 2.1 Identity

| Item | Value | Evidence |
|---|---|---|
| Name | Kern | `settings.gradle.kts:25` (`rootProject.name = "Kern"`) |
| Package / Application ID | `dev.kern` | `build.gradle.kts:8,13` |
| Version | `versionName 0.1.11.0`, `versionCode 27` | `build.gradle.kts:16-17` |
| License | AGPL-3.0 | `LICENSE`, `README.md:103` |
| Purpose | Offline, privacy-first document toolkit; reads/writes files in place; no app folder; no network | `README.md:30-47`, `docs/architecture.md:5` |
| Maturity | **Early alpha** (self-declared), pre-beta feature depth | `README.md:33`; uneven editor maturity in `CHANGELOG.md` |

### 2.2 Modules and layout

Single Gradle module; the root project *is* the app module; `src/` is mapped as `java.srcDirs("src")` and the test dirs are explicit (`build.gradle.kts:36-45`).

```
kern-main/
├── AndroidManifest.xml          # 1 exported Activity, FILE_TYPE intent filters, FileProvider
├── build.gradle.kts             # single-module Android app, R8 release config
├── gradle/libs.versions.toml    # version catalog (single source of dependency truth)
├── res/                         # launcher icon, 4 OFL fonts, theme, raw AGPL text, file_paths
├── src/
│   ├── MainActivity.kt          # single Activity; resolves "Open with" → start destination
│   ├── KernNavHost.kt           # NavHost: browser, settings, 6 editor routes
│   ├── browser/                 # FileScanner, FileBrowserScreen/VM, BrowserStore, IntentHandler
│   ├── editors/
│   │   ├── csv/                 # OpenCSV grid editor
│   │   ├── excel/               # POI XSSF grid editor
│   │   ├── word/                # POI XWPF: WordDocument, VM, screen, rich text, palette  ← HMX target
│   │   ├── pptx/                # POI XSLF: deepest editor (theme, geometry, decorations)
│   │   ├── pdf/                 # Android PdfRenderer viewer + native tools
│   │   └── epub/                # jsoup + ZIP, preservation-first save
│   ├── pdf-bridge/              # QyraPdf JNI surface (libkern_pdf.so, built from Qyra fork)
│   ├── settings/                # SettingsScreen/Groups/Storage/Links
│   └── shared/                  # DocumentFormat, FormatStyle, CellMerge, io/DocumentIo,
│                                # settings/KernSettings, theme/, ui/ (chrome, components, zoom)
├── tests/                       # mirrors src; JVM unit tests + instrumented (androidTest)
├── docs/                        # architecture.md, fonts.md
├── sample-files/                # one sample per format (kern-sample.docx exists)
├── config/lint.xml              # network-API-as-error lint policy
└── .github/                     # 3 workflows + build-pdf-bridge composite action
```

### 2.3 Toolchain and SDK

| Item | Value | Evidence |
|---|---|---|
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 | `build.gradle.kts:9,14-15` |
| Build tools | 35.0.0 | `build.gradle.kts:10` |
| Java / Kotlin | Java 17 (source/target/jvmTarget), Kotlin 2.0.21 | `build.gradle.kts:47-53`, `libs.versions.toml:4` |
| Gradle / AGP | 8.10.2 (wrapper properties; **no wrapper jar committed**) / 8.7.2 | `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/android.yml:31-35` |
| UI | Compose BOM `2026.05.01`, Material 3, Navigation Compose 2.8.3 | `libs.versions.toml:8-9,28-35` |
| Document libs | POI 5.5.1 (`poi`, `poi-ooxml`), OpenCSV 5.12.0, jsoup 1.23.2 | `libs.versions.toml:10-16,38-42` |
| Native | No NDK build in Gradle; prebuilt `jniLibs/<abi>/libkern_pdf.so` from the Qyra fork; `abiFilters` arm64-v8a, armeabi-v7a, x86_64 | `build.gradle.kts:20-22,40`; `src/pdf-bridge/README.md` |
| Build types | `debug` (default) and `release` (minify + shrink + proguard) | `build.gradle.kts:25-34` |
| Signing | **None.** `assembleRelease` produces an unsigned APK; releases publish debug builds | `build.gradle.kts:25-34`; `.github/workflows/release.yml:34-51` |

### 2.4 Entry points, navigation, permissions

- Single exported activity with `MAIN/LAUNCHER` and `VIEW/EDIT` filters for Word/Excel/PowerPoint/CSV/PDF/EPUB MIME types (`AndroidManifest.xml:32-66`). Intent handling resolves MIME → format and routes directly into the editor: `src/browser/IntentHandler.kt:19-30`, `src/MainActivity.kt:27-44`.
- NavHost routes: browser, settings, and `<editor>/{filePath}` for each format (`src/KernNavHost.kt:26-94`).
- Permissions: `READ_EXTERNAL_STORAGE` (≤32), `WRITE_EXTERNAL_STORAGE` (≤29), `READ_MEDIA_IMAGES`, `MANAGE_EXTERNAL_STORAGE`; **no INTERNET** by policy, enforced by lint config (`AndroidManifest.xml:13-22`, `config/lint.xml`).
- FileProvider for "Share a copy" with `<external-path path="." />` (`AndroidManifest.xml:71-79`, `res/xml/file_paths.xml`).

### 2.5 Repository status (upstream, read-only)

- Upstream `verycareful/kern` exists: default branch `main` @ `88c5d23c` (2026-09-12), plus `dev` and one stale `claude/…` branch; tags through `0.1.11.0`; **0 open issues/PRs** at inspection time; latest release ships a 35 MB **debug-signed** APK.
- The local snapshot corresponds to tag `0.1.11.0` (CHANGELOG ends there; file contents match).
- CI history at that commit is green for all three workflows (Android CI: debug APK + JVM unit tests + lint; Release build check: R8 with mapping.txt assertion; Release: tag → debug APK published). **[verified-upstream]**
- The local directory is **not a git checkout** (no `.git`), so local branch/commit/tag inspection is impossible; git-related findings come from the GitHub API.
- No `CLAUDE.md`, `.internal/`, ADR directory, or roadmap files are present (they are gitignored; `README.md:97`, `AndroidManifest.xml:7` and several comments reference documents that are not in the repo — e.g. "ADR 003", "CLAUDE.md"). Treat in-repo prose references to those as unverifiable.

### 2.6 Maturity assessment (evidence-based)

| Dimension | State |
|---|---|
| Product shell (browser, settings, theming, offline policy) | Functional, reskinned, tested at UI level |
| CSV/Excel/EPUB/PDF/PPTX editors | Functional alpha with known limitations documented per release |
| **Word (DOCX) editor** | Functional alpha with confirmed data-loss paths on save; last substantive work in 0.1.10.x |
| Release engineering | Debug-only releases, no signing key, no instrumented tests in CI, no dependency verification |
| Test depth on DOCX | 11 JVM tests, all on a hand-generated fixture family; no preservation assertions, no XML diffs, no real-world documents |

Verdict: **early alpha, pre-beta.** Not production-ready for documents the user cannot lose.

---

## 3. Current Feature Implementation Matrix

Legend: **IV** = implemented+verified (by an existing automated test or explicit CI evidence), **IU** = implemented, not fully verified, **PI** = partially implemented, **UI** = UI only, **EN** = engine support only, **NI** = not implemented, **?** = unknown, needs evidence.

| Feature | Kern today | Evidence | Limitations | Work needed | Pri | Cplx | Depends on |
|---|---|---|---|---|---|---|---|
| Open DOCX | IU — POI XWPF, whole file in memory | `WordDocument.kt:103-127`, `VM:178-224` | No size guard, no encrypted/`.doc` awareness (`.doc` is routed here and will error) | Tolerant open, error taxonomy | P0 | L | — |
| Save DOCX | IU — surgical edits replayed onto original bytes | `WordDocument.kt:132-191`, `VM:703-723` | Non-atomic overwrite; no backup; lossy for edited paragraphs; index-shift defect | Safe save + preservation | P0 | M | — |
| Safe file handling / recovery | NI | `DocumentIo.kt:36-43` | Truncating write in place; crash ⇒ possible corruption; Export-copy is the only safe path | Atomic write, backup, journal | P0 | M | — |
| Text editing | IU — per-paragraph `BasicTextField` | `WordEditorScreen.kt:381-438` | No paragraph insert/delete; Enter inserts a line break (`writeRuns` → `addBreak`, `WordDocument.kt:293-298`); no typing styles | Paragraph model ops | P0 | M | model |
| Bold/Italic/Underline/Strike | IU (written only when true) | `VM:256-282`, `WordDocument.kt:331-339` | Underline always written as SINGLE; not all underline variants read/written | Property model | P1 | L | model |
| Font family | PI — preserved, not rendered | `WordRichText.kt:30-61` | Editor always draws Outfit; family only survives via annotation | Font picker + substitution rules | P1 | M | layout |
| Font size / colour | IU | `VM:289-298`; palette sheet | `sp` treated as `pt` (scale mismatch vs Word); theme colours not resolved | Unit-correct rendering | P1 | M | layout |
| Paragraph alignment | IU | `WordDocument.kt:341-346` | No RTL/bidi; justify is app-side only | bidi | P2 | M | layout |
| Indent | PI — left indent only | `VM:344-364` | First-line/hanging/right indent unmodeled | Properties | P2 | M | model |
| Headings / styles | PI — Title/H1/H2 only | `kindOf`, `WordDocument.kt:261-269` | H3–H9 and custom styles display as body; no style catalog; writing kind writes literal `Title/Heading1/Heading2/Normal` | Style catalog + picker | P1 | M | model |
| Bullets / numbered lists | PI | read numFmt only; write sets numID 1/2, ilvl 0 | Nested levels flattened; numbering definitions may not exist/match; render always "• "/"1. " | Numbering model | P0 (data integrity) | M | model |
| Find & replace | IU | `VM:510-612` | Case-insensitive only; replace destroys run formatting in a paragraph; no whole-word/regex; active-match highlight always index 0 | Non-destructive replace | P1 | M | model |
| Undo / redo | IU — 30 whole-state snapshots | `VM:58-73,616-664` | Snapshot granularity = per keystroke; large-doc memory; **undo marks all blocks/paragraphs edited** | Op-based history | P0 | M | model |
| Tables | PI — text grid + modal | `WordDocument.kt:302-329`, `Screen:516-669` | No merges/widths/borders; multi-paragraph cells collapse; **column delete doesn't remove cells**; rows added via `createRow()` without grid widths; nested tables invisible | Table model | P1 | H | model |
| Images | PI — read + display | `WordDocument.kt:231-243`, `Screen:672-696` | No size/position (max 260 dp height), no insert/delete, images in tables not read, VML invisible, **index-shift bug with multi-image/text+image paragraphs**, image deleted if its paragraph is edited | Image model | P1 | H | model |
| Hyperlinks | PI — text read; URL captured but unused | `WordDocument.kt:198,257`; no writes elsewhere | Not clickable; **destroyed when the paragraph is edited** | Link runs | P1 | M | model |
| Page layout / pagination | NI | `WordPage` = single LazyColumn "paper" | No page size/margins/section geometry; `w:pgSz` ignored for display | Layout engine | P1 | VH | model |
| Headers/footers/page numbers | NI (preserved only if untouched) | never read in `WordDocument` | Page numbering ignored | Header/footer model + render | P2 | H | layout |
| Page breaks / section breaks | NI | `r.text()` maps `w:br` → `\n`; render as line break | A page break in an edited paragraph is downgraded to a line break | Break model | P1 | M | layout |
| Footnotes/endnotes | NI; references leak as `[footnoteRef:n]` text | POI `XWPFRun.text()` (upstream source) | Editing such a paragraph rewrites the marker as literal text and orphans the note | Note model | P2 | H | layout |
| Comments | NI | `word/comments.xml` untouched | Not shown; preserved only via POI re-serialization | Comments model | P3 | H | layout |
| Track changes | NI | runs inside `w:ins` are read as normal text (POI `XWPFParagraph.java`) | Edited paragraph accepts insertions silently; deletions may render as text | Revision model | P3 | VH | model |
| Document navigation | PI | browser + scroll | No outline/bookmark navigation | Outline extraction | P2 | M | model |
| Word/char count | IU | `VM:135-169`; `Screen:342` | Whole-document only | Scope selection | P3 | L | — |
| Accessibility / keyboard | PI | content descriptions on toolbar | No Ctrl+B/I/U, no match-scroll, no a11y structure for blocks | Shortcuts + semantics | P2 | M | — |
| Large-file performance | PI | LazyColumn added in 0.1.10.2 | Whole file + images in RAM; full re-serialization on every save; PNG decode per image | Streaming/preloading plan | P1 | H | — |
| Autosave / crash recovery | NI | `UnsavedChangesGuard` dialog only | Process death loses all edits | Draft persistence | P1 | M | safe save |
| Import/export copy | IU | `EditorChrome.kt:65-74`, `VM:701` | Export writes the document but never re-targets subsequent saves | Save-as semantics | P2 | S | — |
| Offline behaviour | IV | no INTERNET; lint enforces | — | — | — | — | — |
| HMX branding/UX | NI | `res/values/strings.xml` name is "Kern" | Need product decisions + assets | Rebrand pass | P3 | M | release |
| `.doc` (binary) support | NI | `DocumentFormat.WORD` includes `doc`; only XWPF used | Opens the editor, then errors | Convert-or-reject policy | P1 | L | triage |

---

## 4. DOCX Engine and Data-Flow Audit

### 4.1 Data flow, open → edit → save [verified-code]

1. **Entry.** `MainActivity.onCreate` (`MainActivity.kt:27-29`) → `IntentHandler.resolve` (MIME → `DocumentFormat`, URI percent-encoded into the route) → NavHost editor destination (`KernNavHost.kt:68-94`). Browser path: `FileScanner` (Documents/Downloads, depth ≤ 12) → `ScannedDoc` → route with a `file://` path.
2. **Load.** `WordEditorViewModel.start` (`VM:178-224`): `tryPersist` → `DocumentIo.readBytes` (resolver or `File`) → `WordDocument.read` on `Dispatchers.IO` → blocks; then per-block init: `fields[i] = TextFieldValue(toAnnotated(runs))` for every paragraph, `paraProps[i]`, `tableStates[i]`.
3. **Model.** `WordDocument.read` walks `doc.bodyElements` (`WordDocument.kt:103-127`): paragraph → extract pictures into `ImageBlock`s, then (if text or no images) a `ParagraphBlock`; table → `TableBlock` (text grid via `cell.text`); anything else → `OpaqueBlock`. Empty body → one empty paragraph (`:122-124`).
4. **UI.** `WordPage` LazyColumn: paper header → blocks → paper footer with block/word/char stats (`Screen:264-363`). Paragraphs are independent `BasicTextField`s; formatting lives as Compose `SpanStyle`s with font family stashed in a string annotation so it survives edits (`WordRichText.kt:30-61`). Tables render as a 6-row preview card, edited in a bottom-sheet grid; images render as a decoded bitmap capped at 260 dp.
5. **Editing state.** `onValueChange` stores the `TextFieldValue`, converts it back to runs and records `edits[index]` (`VM:230-241`). Paragraph properties, lists, indent, colour, size, alignment go to `paraProps`/`paraPropOps`; table edits to `tableStates`/`tableEdits`. Nothing toggles `WordDocument.ParagraphBlock.editable` — every body paragraph is editable (`WordDocument.kt:78,221`).
6. **Save.** `save()` → `write(uri)` (`VM:693-723`): take `originalBytes` (the file as opened, kept forever) → `WordDocument.applyEditsAndSerialize` (fresh POI parse of the original; apply run edits, paragraph props, table grids by `bodyElements` index; `doc.write(out)`) → `DocumentIo.writeBytes` to the *original* URI with mode `"wt"` (or `File.writeBytes`).
7. **Undo/redo.** Snapshots of the whole editable state (30 max); `applySnapshot` re-derives `edits[i]` for **every** captured paragraph and `tableEdits[i]` for every table (`VM:654-664`) — so any undo/redo converts the next save into a whole-body rewrite.
8. **Concurrency.** All document work on `Dispatchers.IO` inside `viewModelScope`; no cancellation handling, no save-in-progress state, no autosave, no draft persistence.

### 4.2 Feature-by-feature DOCX audit

*(P) parsed, (R) rendered, (E) editable, (S) preserved on save, (L) lost/altered on round-trip, (N) not implemented.*

| Area | Status | Evidence / notes |
|---|---|---|
| Paragraphs, text order | P/R/E/S | Blocks in `bodyElements` order incl. tables interleaved |
| Runs, mixed formatting | P/R/E partial | Only bold, italic, underline(!=NONE), strike, size, colour, font name. **L on edit**: highlight, shading, `vertAlign`, caps/smallCaps (POI uppercases text on read — the case change becomes permanent), `rStyle` character styles, double-strike, emboss/outline/shadow, spacing/kern/position, language, underline style/colour, complex-script variants (`WordDocument.kt:245-259` reads; `331-339` writes) |
| Styles / inheritance | P partial | `p.style` → 4 coarse kinds; style inheritance resolved by Word, not by the app; editing kind writes literal style IDs that may not exist in the document |
| Headings / outline levels | P partial | `w:outlineLvl` and Heading3+ are not distinguished |
| Tables | P/R/E partial | Text-only grid; widths/borders/shading survive untouched; **merges not modeled**; multi-paragraph cells collapse into one paragraph on edit (`writeTable` sets only `paragraphs[0]`); `setText` with embedded `\n` writes one `w:t` (POI source) — no line breaks; **column deletion never removes cells**; added rows have no `gridCol` widths; nested tables invisible |
| Images (DrawingML inline) | P/R/S partial | Extracted from runs; **no extents**; paragraph with 2+ images or image+text breaks block↔element index mapping; editing such a paragraph **deletes its images** (runs removed) |
| Floating images (anchor), shapes, text boxes | P/S only if untouched | Not extracted from runs (`embeddedPictures` covers inline + anchored inside runs, but nothing is rendered at its position); VML images unknown/likely invisible |
| Hyperlinks | P partial; L | Link ID captured and unused; not clickable; destroyed on edit of that paragraph; orphaned `rels` entry |
| Lists & numbering | P partial; risky write | Read: `numID != null` + `numFmt` only; level `ilvl` ignored. Write: `numID=1/2`, `ilvl=0`, numbering part created only if absent (`ensureNumbering`) |
| Sections / page size / margins | P(no)/S | `w:sectPr` never read for display; preserved while untouched |
| Headers / footers / page numbers | N/S(unverified) | Never read; POI re-serializes them on save; no test exists |
| Footnotes / endnotes | N; **corruption risk** | POI `XWPFRun.text()` renders references as `[footnoteRef:n]`; editing the paragraph writes that literal text and deletes the reference run |
| Comments | N | `comments.xml` preserved only via POI |
| Tracked changes | N; risk | POI `getRuns()` includes runs inside `w:ins` (verified in POI source, `buildRunsInOrderFromXml`); editing accepts them and leaves empty `w:ins`; `w:del` text handling unverified |
| Fields | P as text; L on edit | `w:fldSimple` runs included; `instrText` suppressed by POI; complex-field `fldChar` runs are removed by `writeRuns` → field destroyed on edit |
| Bookmarks | P/S | Siblings of runs; survive |
| SDT / content controls | N/S | Run-level SDT text excluded from `p.runs` (POI adds `XWPFSDT` only to `iruns`), so invisible in app; block-level SDT → `OpaqueBlock`. Preserved in XML while untouched |
| Math, charts, SmartArt, embedded objects | N/S best-effort | Invisible; POI/XMLBeans preservation unverified by any test |
| Metadata / rels | S | POI re-serializes docProps; relationships of deleted runs (hyperlinks) are orphaned but harmless |
| Unknown XML | ? | "Best-effort" by POI; **no verification exists** — must be measured (Phase 2) |

### 4.3 Serialization audit answers

- **Does it modify original OOXML or rebuild?** It reopens the original bytes with POI and applies targeted writes (runs, paragraph props, table cells). POI then re-serializes the package on `write()`. Structure-preserving at the model level for untouched regions; **byte-level fidelity is not guaranteed and the claim in `WordDocument.kt:21-24` ("survive byte-for-byte") is unverified for any part, including headers/footers.**
- **Do unrelated parts survive?** Non-XML parts (images, fonts) are copied; XML parts POI parsed are re-serialized. Unknown-element survival is best-effort and untested. **[inference from POI architecture + repo tests; needs measurement]**
- **Paragraph/run boundaries:** paragraphs preserved unless edited; runs are *deliberately* collapsed/recreated inside edited paragraphs (`collapse()` on read, `writeRuns()` on write).
- **Image relationships:** valid until an image-bearing paragraph is edited or an index-mapped edit lands on the wrong element; then the image is dropped and the relationship is orphaned.
- **Atomicity:** **no.** In-place truncated write (`DocumentIo.kt:36-43`); Export-copy is the only safe path.
- **Original protection:** none (no backup, no versioning, no conflict detection if another app changes the file).
- **Repeated cycles:** within a session, saves are idempotent from the in-memory original; across sessions each cycle re-serializes through POI (drift risk unmeasured).
- **Malformed/encrypted/huge:** all errors surface as the raw exception message in the error slot; no size guard; OLE2/encrypted DOCX and legacy `.doc` fail inside XWPF; zip bombs unhandled. **[inference from code paths]**

### 4.4 Rendering audit

- **No layout engine.** A LazyColumn of paragraph text fields inside one rounded "paper" box; fixed 18/26 dp padding; no page width, no margins from `sectPr`, no page boundaries, no headers/footers, no footnotes, no floats, no wrap-around images.
- Font metrics: fixed per-kind metrics (`Screen:367-372`), `sizePt` mapped to `sp` 1:1 (Word pt ≠ Android sp at device density — visual size differs from Word), body text in Outfit regardless of the document's font.
- Zoom scales font sizes and paddings (`pinchZoom`, 0.5–3.0); it does not re-layout pages.
- Tables render at 100 dp fixed cells, first 6 rows only; numbering renders "1." always; bullets "• ".
- Differences vs Word Android/desktop: paragraph shape correct-ish, everything else (pages, columns, spacing, line breaks from page breaks, fields, tables beyond text) differs. Missing capabilities require **document-model work** (breaks, sections, styles, notes), **UI work** (toolbar, dialogs), and for pagination a **new layout layer** — not simply an engine swap.

---

## 5. Build, CI, Dependencies, Security, Licensing

### 5.1 Build verification status

**Not executed** (per instruction). What is established instead:

- All declared versions resolve in the live repositories as of today: POI 5.5.1, jsoup 1.23.2, OpenCSV 5.12.0 (Maven Central), Compose BOM 2026.05.01 (includes `material-icons-extended`, so the BOM-managed dependency resolves), navigation-compose 2.8.3, AGP 8.7.2 (Google Maven). **[verified-upstream, HTTP 200]**
- The exact commit the local snapshot matches has green CI: Android CI (debug APK + `testDebugUnitTest` + `lintDebug`), Release build check (`assembleRelease` with R8 mapping assertion), Release workflow. **[verified-upstream]**
- **Gradle wrapper jar is absent**; builds rely on a Gradle 8.10.2 provisioned by the CI action or by the IDE. Any offline/desktop build must supply Gradle itself.
- Local environment limitation (this machine, if you ever build here): Android SDK at `/usr/lib/android-sdk` has only platform **android-36** (compileSdk 35 wants android-35) and Gradle caches lack POI — a local build would download SDK platform + dependencies; RAM is ~3.6 GB (shared with the phone), so R8/POI builds are heavy here. CI is the right place to build.
- Commands for a properly provisioned environment:
  - `gradle testDebugUnitTest --no-daemon` (pure JVM DOCX tests, no native bridge)
  - `gradle lintDebug --no-daemon`
  - `gradle assembleDebug --no-daemon` (after the Rust bridge build for PDF tools)
  - `gradle assembleRelease --no-daemon` (R8; prove POI keep rules)

### 5.2 CI/CD

| Workflow | What it does | Gaps |
|---|---|---|
| `android.yml` | debug APK + artifact; JVM unit tests; lint with `config/lint.xml` | No instrumented tests; no dependency/secret/dependency-verification scanning; no release build here |
| `release-build-check.yml` | `assembleRelease` (R8 + shrink), asserts `mapping.txt` exists, uploads R8 reports | Does not install/run the release build; unsigned by design |
| `release.yml` | On tag: builds bridge + debug APK, publishes a **debug-signed** release with a loud warning | No real signing key; no version bump automation (manual changelog discipline) |
| `build-pdf-bridge` action | Sparse-clones the Qyra fork, `cargo ndk` builds `libkern_pdf.so` for 3 ABIs | Pulls `main` of a fork by default — **unpinned external ref in the supply chain** |

### 5.3 Reproducibility and dependencies

- Versions are pinned in the catalog (good). No Gradle dependency verification (`verification-metadata.xml`), no lockfile, no Dependabot config, no SBOM.
- R8 rules keep all of `org.apache.poi.**`, `org.openxmlformats.**`, `com.microsoft.schemas.**`, `schemaorg_apache_xmlbeans.**`, and `-dontwarn java.awt.**` (`proguard-rules.pro`) — heavy but pragmatic; it means minification buys little for the DOCX stack.
- POI on Android notes: the library is desktop-oriented JVM (uses `java.awt` in some paths, XMLBeans, `javax.xml`); the current code avoids the AWT-dependent APIs, but any new feature that touches them (e.g., some image APIs) must be validated on device.

### 5.4 Security and privacy

- **No network permission; no telemetry** — verified in manifest and lint policy; no `Log`, `println`, or `printStackTrace` anywhere in `src/`. Document content is never logged.
- `MANAGE_EXTERNAL_STORAGE` is a **Play policy risk** (all-files access requires an approved use case and a declaration; document editors can qualify, but this is a distribution risk to plan for).
- `allowBackup="true"` with no `dataExtractionRules` — backups include preferences (pins/recents, theme), not document contents. Acceptable, worth an explicit rule.
- FileProvider exposes `<external-path path="." />`; the provider is non-exported and grants are per-share — acceptable but broader than needed.
- Exported activity accepts arbitrary `content://`/`file://` URIs; the app then reads them with all-files access. No URI redirection handling is needed (the URI is opened, not forwarded), but "Share a copy" of a file the user did not choose is a (user-initiated) exfil path only within the device — no network.
- In-place truncating writes are the biggest data-integrity risk in the app (see §4).
- PDF tool cache privacy was a real, now-closed upstream issue (#21, fixed by PR #22) — evidence that file-lifecycle hygiene needs ongoing tests.

### 5.5 Licensing

| Component | License | Source evidence |
|---|---|---|
| Kern itself | AGPL-3.0 | `LICENSE` (full AGPL text), `README.md:99-117` |
| Apache POI (docx/xlsx/pptx) | Apache-2.0 | `docs/architecture.md:57-62` |
| OpenCSV | Apache-2.0 | same |
| jsoup | MIT | `libs.versions.toml:41` |
| Jetpack Compose / AndroidX | Apache-2.0 | standard |
| Fonts (Outfit, Quicksand, Sora, IBM Plex Mono) | SIL OFL 1.1 | `licenses/fonts/*.txt` |
| Qyra `kern-bridge` native `.so` | GPL-3.0 (built from the Qyra fork) | `src/pdf-bridge/README.md`, license headers in `QyraPdf.kt` |

**Questions that need professional legal review before any HMX Docs distribution** (not legal advice):

1. **AGPL-3.0 derivative obligation.** HMX Docs built on this code is a derivative: it must remain AGPL-3.0, source-available to recipients (and to network users if you ever add a server component), with notices preserved. If HMX Docs is intended to be proprietary, closed-source, or distributed without source, **this codebase cannot be the base**.
2. **GPL-3.0 native component.** GPL-3.0 inside AGPL-3.0 is upward-compatible, but GPL-3.0 §6 requires distributing the corresponding source *and* build scripts for `libkern_pdf.so` — currently sourced from a fork whose `main` is unpinned in CI. Pin the ref and vendor build instructions/notices.
3. **Play distribution.** MANAGE_EXTERNAL_STORAGE declaration, AGPL source-offer obligations, and the "no network" claim (must stay true).
4. **Brand/trademark.** Rebranding to HMX Docs is permitted for a modified AGPL version, but copyright notices (e.g., `README.md:101`, `res/raw/license.txt`, per-file headers) must be preserved, and you should state changes.
5. Third-party notice bundle: currently only fonts have license files in-repo; there is no `NOTICE`/third-party page for POI/OpenCSV/jsoup/xmlbeans/commons transitives.

---

## 6. Architectural Options

| Criterion | **A. Continue Kern** (Compose + POI, upgrade in place) | **B. Kern shell + better renderer/engine where feasible** | **C. Base on a more mature OSS office engine** |
|---|---|---|---|
| DOCX compatibility | Limited by POI model + current code; improvable to "preservation-first alpha" | Same engine, plus a custom pagination/render layer (POI for model/IO is kept either way) | LibreOffice/OnlyOffice/Collabora are desktop/web stacks — no viable embedded Android OSS DOCX engine found |
| Pagination/layout fidelity | Must be built (largest single item) | Same build, but explicitly scoped as a subsystem with its own tests | Would come "for free" with a real engine; none exists for Android in-process |
| Effort | Moderate, incremental, keeps current tests/UI | Higher, but cleanly separable workstream | Very high + integration risk + shell rewrite |
| Native build complexity | None added (Rust bridge already exists for PDF) | Same | High (LibreOffice core, big C++/ICU/HarfBuzz toolchain; not cloud-CI-friendly) |
| Android perf/memory | Controllable with streaming, limits, benchmarks | Rendering heavy but feasible for pages | Poor (LibreOffice core is hundreds of MB in-process; not designed for app sandbox) |
| Maintenance | You own it; no upstream surprises | Same | You chase a huge upstream you can't realistically vendor |
| Licensing | AGPL fine (already) | Same | LibreOffice (MPL-2.0, OK but impractical); OnlyOffice (AGPL, server-centric); commercial SDKs (Syncfusion/Apryse/PSPDFKit) are closed-source — **incompatible with AGPL obligations without a license strategy change** |
| Offline | Yes | Yes | Yes for the practical ones, but with infra requirements |
| Cloud CI | Yes (current workflows) | Yes | Painful |
| Regression risk | Low (incremental, tests grow) | Medium (new rendering path beside old) | Very high |

**Primary recommendation: Option A, executed as "A+"** — keep the Compose app and POI; (1) make saving preservation-first (apply the EPUB recipe: patch only the parts you model, copy everything else verbatim), (2) widen the model (styles, numbering, tables with merges, images with extents, hyperlinks, breaks, eventually notes), (3) build an explicit pagination/layout layer in Compose with a testable measurement core, (4) grow the DOCX corpus tests in CI.

**Fallback: Option B** — same as A, but if round-trip measurements show POI cannot preserve a class of constructs that matters (Phase 2 evidence), add a raw-XML patching path for those parts (old/re-serialized-part diffing at the OPC level) rather than switching engines.

**What would change the recommendation:** (a) if HMX Docs must be proprietary → none of this code can be used (licensing, not technical); (b) if Phase 2 shows POI's re-serialization destroys real-world documents irrecoverably → move to XML-patching-only save (B); (c) if "Word-like" is defined to include pixel-accurate pagination of complex documents and budget allows → evaluate a commercial SDK as a separate product decision, accepting its closed-source license implications.

---

## 7. DOCX Compatibility Test Strategy

### 7.1 Corpus (proposed `tests/fixtures/docx/`, generated where possible)

| # | Fixture | Build method | Expected behaviour |
|---|---|---|---|
| 1 | Plain paragraphs, mixed runs | Generated (POI) | Open/edit/save; run boundaries collapse only in edited paragraphs; XML diff shows no other paragraph changed |
| 2 | Styles: Title/H1–H6, custom style, outlineLvl | Generated + hand-patched XML | Correct kind mapping (after Phase 4); untouched paragraphs' `pStyle` byte-identical after normalized diff |
| 3 | Nested lists, 3 levels, restarted numbering | Generated | Levels visible and editable without flattening after Phase 4; numbering part diff empty when no list edit |
| 4 | Simple table | Generated | Cell edit touches only that cell's first paragraph |
| 5 | Merged table (gridSpan, vMerge), widths, borders, shading | Hand-authored XML | Read-only render shows merges; text edit preserves merge markup; column delete removes cells (defect fix) |
| 6 | Inline images (1 and 2 per paragraph), image+text mixed | Generated | No index shift; image survives a text edit in its paragraph (defect fix); extents rendered |
| 7 | Hyperlinks (external, internal, in TOC) | Generated | Rendered as links; edit preserves link run or explicitly detaches it |
| 8 | Page breaks + `sectPr` change mid-document, landscape section | Hand-authored | Break preserved; pagination correct after Phase 7 |
| 9 | Header/footer with page number field | Hand-authored | Field preserved; header/footer rendered after Phase 8 |
| 10 | Footnotes + endnotes | Hand-authored | No `[footnoteRef:n]` leakage; note content round-trips |
| 11 | Comments + tracked insert/delete | Hand-authored | Not silently accepted on save; explicitly surfaced or refused until Phase 8 |
| 12 | Text box + floating image + chart + SmartArt | Hand-authored | Documented as "invisible but preserved"; no crash |
| 13 | Long document (500+ pages, 20 MB, 200 images) | Generated script | Open under N seconds; memory budget; scroll |
| 14 | Unicode: Devanagari, Tamil, Telugu, emoji, RTL Arabic/Hebrew | Generated | No mojibake; shaping renders after Phase 7 |
| 15 | Malformed: truncated ZIP, broken XML, encrypted DOCX, `.doc`, zip bomb | Prepared | Clear error, no crash, file untouched |
| 16 | Real-world Word docs (3–5, licensed for test use) | Collected manually | Golden structural diff after a no-op save |

Reuse already in repo: `sample-files/kern-sample.docx` (Title/H1, 4-ish tables, A4 sectPr, Courier runs — verified by unzipping), and the generative-fixture pattern in `tests/editors/word/WordDocumentTest.kt`.

### 7.2 Comparison method (no byte-for-byte ZIP equality)

1. **Part inventory:** same entry names; every *unmodeled* part (media, fonts, comments) byte-identical (SHA-256); `[Content_Types].xml` unchanged.
2. **XML-level structural diff:** canonicalize `document.xml` (strip whitespace between elements, namespace prefixes via a canonical map, attribute order normalized) and diff; assert changes are confined to intended XPaths. Do the same for `styles.xml`, `numbering.xml`, `footer*.xml`.
3. **Semantic re-read:** reopen with POI and assert text/style/table/image/hyperlink equality against the parsed original (a `WordDocument`-level model diff).
4. **Idempotence:** open → no edits → save → open → save; the second save must equal the first (modulo the same canonicalization).
5. **Targeted regression tests** for each confirmed defect in §4 (index shift with 2 images; column delete; undo-then-save not rewriting everything; hyperlink survival; footnote marker).
6. **Visual (non-CI):** render fixture PDFs with Word/LibreOffice and compare page images with a tolerance threshold for the pagination workstream only; keep out of CI.
7. **Performance (CI-able):** open/save wall-time and peak heap for fixture 13 using a JVM harness (`-Xmx` bound) — the Word document layer is pure JVM and testable without Android.

---

## 8. Ten-Phase Development Roadmap

Ordering rationale (vs the candidate themes): measure preservation *before* changing the model (Phase 2); fix confirmed data-loss defects *before* add features (Phase 3), because every feature layered on the current write path inherits them; model before editing depth (4 → 5 → 6); pagination after the model defines page/paragraph metrics (7); advanced Word features after pagination because they render into the page (8); reliability and productization last (9–10). Workstreams that can safely overlap are stated per phase.

Effort notes: day ranges are **engineering days of focused work** for the stated scope, assuming the engine decision stays Option A+ and taking the current codebase as the base. Solo part-time (evenings/weekends) typically takes **2–3× calendar time**; estimates exclude legal review and design/branding externalities.

---

### Phase 1 — Baseline validation, ownership, and build reproducibility

**Objective:** A trustworthy baseline: every published claim re-verified, the build reproducible from a clean machine, and HMX Docs' licensing/product split decided in writing.

**Current state:** CI is green for the exact 0.1.11.0 commit **[verified-upstream]**; no wrapper jar; no signing config; releases publish debug APKs; README/architecture references documents that aren't in the repo (ADR 003, CLAUDE.md); local checkout is not a git repo.

**Work packages:**
1. Re-run CI on a fresh branch: debug APK, `testDebugUnitTest`, `lintDebug`, `assembleRelease` (R8). Record artifact hashes.
2. Commit `gradle-wrapper.jar` + `gradlew` so builds no longer depend on a provisioned Gradle; verify `gradle wrapper --gradle-version 8.10.2` reproducibility on Linux and macOS.
3. Release signing decision + implementation: keystore in CI secrets, `signingConfig`, `assembleRelease` published; remove the "debug build" warning path (`release.yml:34-51`).
4. Licensing gate: written decision on HMX Docs' distribution model (AGPL-compatible vs proprietary) and pin the Qyra `kern-ref` in the bridge action (currently defaults to `main`).
5. Documentation truth pass: replace/remove references to non-existent ADRs; state the DOCX editor's real capabilities in README (it currently implies Word editing works — it does, but lossily).
6. Confirm environment expectations for maintainers: document the exact toolchain (JDK 17, Gradle 8.10.2, SDK 35) and that `assembleDebug` needs the Rust bridge only for PDF tools.

**Files:** `build.gradle.kts`, `gradle/wrapper/*`, `.github/workflows/release.yml`, `.github/actions/build-pdf-bridge/action.yml`, `README.md`, `docs/architecture.md`.

**Dependencies:** None.

**Strategy:** Do not change app code in this phase. Keep CI as the source of truth; local builds optional.

**Tests:** CI green on a fresh commit; a clean-clone build succeeds twice with identical `mapping.txt`/APK hash (modulo timestamps); unit tests still 37/37.

**Acceptance criteria:** Signed release APK installs and launches; `./gradlew testDebugUnitTest lintDebug assembleDebug` works from a clean clone; zero unverifiable references in top-level docs; licensing decision recorded in the repo.

**Risks:** Keystore/secrets handling; AGPL decision may invalidate downstream plans (this is why it gates Phases 9–10, not 2–8).

**Effort:** 2–4 days (solo part-time ≈ 1–2 weeks calendar). **Deliverable:** reproducible build + signed release pipeline + recorded licensing decision. **Do not proceed until:** signed release build artifact verified installable on a physical device.

---

### Phase 2 — DOCX compatibility test harness and preservation baseline

**Objective:** The corpus from §7 exists, runs in CI on every PR, and documents *measured* preservation behaviour for today's code — before any model change.

**Current state:** 11 Word unit tests on generated fixtures; they assert re-read model equality, never XML preservation; no fixture for images, merges, hyperlinks+save, fields, notes, track changes, real-world docs.

**Work packages:**
1. Build fixture generators (case 1–7, 14–15) as JVM tests; hand-author XML fixtures (8–12) under `tests/fixtures/docx/`.
2. Implement the canonicalizer + structural differ (§7.2 items 1–2) as a small test utility.
3. Add the defect-probe tests (expected-to-fail or `@Ignore`-documented): index shift with 2 images; column delete; hyperlink loss; footnote marker; undo-rewrites-all.
4. Add a JVM performance harness for a generated long document (open/save time, heap ceiling).
5. Wire the suite into `android.yml` (it already runs `testDebugUnitTest`; ensure the fixture dir is on the test classpath).

**Files:** `tests/editors/word/*` (new files), `tests/shared/zip`-style utilities, `tests/fixtures/docx/**` (new), `.github/workflows/android.yml` (only if classpath wiring is needed).

**Dependencies:** Phase 1 (build reliability), not strictly (can run in parallel after the CI is stable).

**Strategy:** Prefer generated fixtures (already the repo's pattern, keeps binaries out of git); hand-authored XML only where POI can't generate the construct.

**Tests:** The harness itself; each probe test must fail against today's code and pass after its fix in Phase 3.

**Acceptance criteria:** ≥ 16 corpus cases execute in CI in < 3 min; a machine-readable baseline report (per-case: open ✓, edit ✓, save ✓, preservation assertions) is attached to the CI run.

**Risks:** The differ can be over-strict (namespace/whitespace noise) — start with assertions per-part, not whole-file diffs.

**Effort:** 5–10 days (≈ 2–4 weeks part-time). **Deliverable:** CI-enforced DOCX corpus + baseline report. **Do not proceed until:** the baseline shows the five confirmed defects as failing tests.

---

### Phase 3 — Safe save, file lifecycle, and the confirmed-defect fixes

**Objective:** No save can corrupt or silently mangle a document; the P0 defects from §4 are fixed with regression tests.

**Current state:** Truncating in-place write (`DocumentIo.kt:36-43`); no backup; no atomicity; index-mapping defect; column-delete defect; undo-then-save rewrites the whole body; empty-document edits are silently dropped (`WordDocument.kt:122-124` vs `:142-144`).

**Work packages:**
1. Safe save: write to a sibling temp (same provider/dir), fsync where possible, then rename/commit through SAF (`openOutputStream` on the target only after the full byte patch is built); keep the previous revision as `.bak`/local draft where the provider allows; verify on both `file://` (rename via `File.renameTo` after `Files.move`-style replace) and `content://` (write-back only after successful full generation, feasible because the document is already materialised in memory).
2. Draft/recovery: persist pending serialized bytes (or the original + edit patch) into app-private storage keyed by document URI + mtime; offer recovery on reopen.
3. Fix block↔element index mapping: give `ParagraphBlock`/`TableBlock`/`ImageBlock` an explicit `elementIndex` (from `bodyElements`) and address edits by it; add the image+text and multi-image regression tests.
4. Fix table column deletion (remove cells in `writeTable`) and row/col insert widths (copy `gridCol`).
5. Stop `applySnapshot` from marking everything edited: track touched indices; `edits` should contain only user-modified paragraphs (delta vs the parsed run list), and undo must restore *and* clear deltas properly.
6. Re-target `originalBytes` after every successful save (so a session continues from the saved state), and keep `DocumentIo.writeBytes` for export only.
7. Error taxonomy: distinguish unsupported (encrypted `.doc`/OLE2), corrupt, permission-denied, disk-full; never leave the original truncated.

**Files:** `src/shared/io/DocumentIo.kt`, `src/editors/word/WordDocument.kt`, `src/editors/word/WordEditorViewModel.kt`, `src/shared/ui/EditorChrome.kt`, new `src/shared/io/DocumentJournal.kt` (proposed).

**Dependencies:** Phase 2 (tests exist to prove each fix).

**Strategy:** Serialize fully in memory (already the case) → single guarded write; borrow the journal idea from PDF-tool cache hygiene.

**Tests:** All six defect probes pass; crash-injection test (kill between temp write and commit) leaves the original intact; repeated save cycles idempotent.

**Acceptance criteria:** No test or manual case where a failed or interrupted save alters the original file; editing a paragraph containing images or hyperlinks no longer deletes them; deleting a column removes cells after reopen; undo followed by save produces a diff confined to undo-affected paragraphs.

**Risks:** SAF providers vary (Google Drive providers may not support rename semantics; some don't support `.bak` files) — design a fallback that degrades to "write-back with pre-verified bytes only".

**Effort:** 5–8 days (≈ 2–3 weeks part-time). **Deliverable:** safe save + recovery + P0 defect fixes, all regression-tested. **Do not proceed until:** crash-injection and corruption tests pass on a physical device with files in Documents/Downloads and via SAF.

---

### Phase 4 — Document model depth (styles, numbering, links, images, notes read-only)

**Objective:** A model that represents the document faithfully enough to render, navigate, and edit without losing what it doesn't edit.

**Current state:** 4-kind style enum; run flags; text-only tables; byte-only images; link URL dead field; no outline, notes, fields, or SDT awareness (`WordDocument.kt` entire).

**Work packages:**
1. Style catalog: parse `styles.xml` (style IDs, names, type, basedOn, outline level, built-in flags); map paragraphs to a `StyleRef` (id + resolved display kind); keep the current heuristics only as fallback.
2. Numbering: parse `numbering.xml` (abstractNum → numFmt, lvlText, indents, start) so lists read correctly at any level and write with a correct `numId`/`ilvl`; stop hard-coding 1/2.
3. Tables: read cell properties needed for rendering (widths from `tcW`/`tblGrid`, `gridSpan`, `vMerge`, `tcPr` shading/borders) and keep the write path text-preserving per cell with proper paragraph handling (multi-paragraph cells).
4. Images: read extents (`wp:extent`), relationship target, content type; model placement (inline/anchor) so the renderer can size them; keep bytes.
5. Hyperlinks: model as runs with an explicit link type and relationship id; render as links in the editor.
6. Break model: `w:br` variants (text-wrapping, page, column) and `w:lastRenderedPageBreak` handling for pagination seed.
7. Read-only awareness: fields (begin/separate/result/end), footnote/endnote references (real model, not POI's `[footnoteRef:n]` text), comments anchors, tracked-change regions, SDT ranges — represented so the UI can show them as non-editable islands and the save path can leave them byte-intact.
8. Document metadata: `sectPr` list (page size, orientation, margins, header/footer references), outline tree.

**Files:** `src/editors/word/WordDocument.kt` (split into `WordModel.kt`, `WordNumbering.kt`, `WordStyles.kt`, `WordTables.kt` — proposed), tests under `tests/editors/word/`.

**Dependencies:** Phase 3 stable save (so model changes can't corrupt files) and Phase 2 tests.

**Strategy:** Typed OOXML access via POI's `getCTP()`/`getCTR()` and `XWPFParagraph.getCTP()` rather than string XML, mirroring the PPTX work in 0.1.11.0 (which already uses `CTShape`, `CTGraphicalObjectFrame` etc.).

**Tests:** Corpus cases 2, 3, 5, 6, 7, 10, 11 re-read + preservation; unit tests per parser.

**Acceptance criteria:** A round-trip of fixtures 2–7, 10–12 with **no edits** leaves all modeled parts structurally identical and all unmodeled parts byte-identical; the model exposes style, numbering, merge, extent, and note information for every fixture.

**Risks:** POI accessor gaps (anchor extents, tcPr) may require raw CT access; treat escaping to `XmlObject` as normal, not exceptional.

**Effort:** 15–25 days (≈ 5–8 weeks part-time). **Deliverable:** widened model + parsers with tests. **Do not proceed until:** corpus no-op round-trip passes preservation assertions for all fixtures.

---

### Phase 5 — Core editing depth (structured text operations)

**Objective:** Editing behaves like a word processor at the paragraph level: insert/split/merge/delete paragraphs, delete/select ranges across blocks, and formatting is applied precisely to runs.

**Current state:** One `BasicTextField` per paragraph; Enter = in-paragraph line break; no way to add or remove a paragraph; formatting applies to whole paragraph when the caret is collapsed; runs are collapsed on read (`WordDocument.kt:271-283`).

**Work packages:**
1. Block operations: insert paragraph (before/after, with style inherit), split at caret, merge with previous/next, delete empty paragraph — mapped to bodyElement-relative ops in `applyEditsAndSerialize`.
2. Run model fidelity: stop collapsing runs on read (or track original run positions); write only the changed run range where possible; extend `RunStyle` with the properties from Phase 4 (underline variants, highlight, vertical align, character style ref) and map them into `AnnotatedString` spans/annotations.
3. Selection semantics: apply formatting to selection; caret style detection at run boundaries (current `styleAt` uses `sel.start - 1`, which is wrong at position 0 of a paragraph — verify and fix).
4. Typing undo coalescing (burst-merge like the PPTX editor) instead of one snapshot per keystroke.
5. Typeface selection UI (family picker reading the document's `fontTable.xml`) and correct pt→px display sizing per zoom.
6. Find/replace that preserves run structure (operate on run list, not on flattened text).

**Files:** `WordDocument.kt`, `WordEditorViewModel.kt`, `WordEditorScreen.kt`, `WordRichText.kt`, `WordColorPalette.kt`.

**Dependencies:** Phases 3–4.

**Strategy:** Keep the per-paragraph text-field UI (it is genuinely good for Compose + IME), add block ops around it; avoid a whole-document editor surface until Phase 7 defines the layout.

**Tests:** Block-op unit tests (insert/split/merge produce expected XML and re-read model); formatting range tests; corpus fixtures re-run after edits.

**Acceptance criteria:** A document can be rewritten end-to-end (add headings, paragraphs, formatted runs) and reopened with formatting intact; deletion of a paragraph works; no test where an edit changes an unrelated paragraph's XML.

**Risks:** IME interaction with per-block fields (composition, autocorrect) — needs manual device testing per Android version.

**Effort:** 15–25 days (≈ 5–8 weeks part-time). **Deliverable:** word-processor-grade text editing for simple documents. **Do not proceed until:** a 10-page hand-written document (headings, bold/italic, lists, table) is authored in-app and opens cleanly in Word/LibreOffice.

---

### Phase 6 — Rich content editing (tables, images, lists, hyperlinks)

**Objective:** Tables are a first-class grid (merges, widths, per-cell paragraphs), images can be inserted/resized, lists respect levels, hyperlinks can be created and edited.

**Current state:** Modal text-grid table editor; image blocks are read-only; lists are 2-state toggles; no link creation.

**Work packages:**
1. Table render + edit: spans rendered; add/remove row/col with `tblGrid` updates; merge/split cells; cell shading/borders read-only display; per-cell multi-paragraph editing; nested-table support (or explicit "unsupported, read-only" island).
2. Image insertion from SAF picker, stored as a new relationship + part (POI `XWPFRun.addPicture` accepts a stream — verify it works on Android), with extents from natural size; delete/resize/position; alt-text passthrough.
3. Numbering UI: indent/outdent moves `ilvl`; bullets↔numbers via existing numbering definitions rather than hard-coded `numId`s; define new definitions when none match (last resort only).
4. Hyperlink create/edit/remove (relationship + style `Hyperlink`), internal anchors for bookmarks.
5. Table/image paste from clipboard (image at least) — optional, keep out if budget tight.

**Files:** `WordDocument.kt` + new table/image modules, `WordEditorScreen.kt` (table sheet rework, image overlay), `WordEditorViewModel.kt`.

**Dependencies:** Phases 4–5.

**Tests:** Corpus 3–7 edited variants; relationship integrity (no orphan rels beyond tolerated ones; new image part present with correct content type); merge/split regression tests.

**Acceptance criteria:** A table with merges survives a cell edit and a column delete; an inserted image round-trips into Word; a 3-level list indents correctly; a new hyperlink is clickable in Word after save.

**Risks:** POI's table APIs are weak for merges — expect typed XML writes (`tcPr`, `vMerge`, `gridSpan`) and heavy fixtures.

**Effort:** 20–30 days (≈ 6–9 weeks part-time). **Deliverable:** rich content editing on the fixed model. **Do not proceed until:** corpus 4–7 passes "edit then reopen" identity checks in Word/LibreOffice.

---

### Phase 7 — Pagination, page layout, and the rendering engine

**Objective:** The document renders as pages with the document's own geometry (page size, orientation, margins), with breaks honoured and zoom correct; the layout core is unit-testable.

**Current state:** A single LazyColumn; no page metrics; `w:pgSz`/`w:pgMar` never read (`Screen:264-363`).

**Work packages:**
1. Layout core (pure Kotlin): given run properties + paragraph properties + page metrics, compute a flow of lines and boxes with font measurement abstracted behind an interface (Compose `TextMeasurer` in the app; a deterministic fake in tests).
2. Page assembly: split the flow into pages using `sectPr` geometry, explicit page breaks, and widow/orphan rules (start with breaks + margins only; add spacing rules incrementally).
3. Render layer: draw pages on a zoomable canvas; keep text fields positioned over their laid-out box; handle scroll-to-caret on focus and IME inset behaviour (see the edge-to-edge skill for insets discipline).
4. Seed pagination from `w:lastRenderedPageBreak` where available (Word's cached breaks) and reconcile with the computed layout — this is what makes "opens like Word" credible for unedited documents.
5. Performance: measure per-page layout cost; cache measured line breaks; virtualize pages; cap re-layout to the edited page's neighbourhood.
6. Tables/images in the flow: row splitting across pages (basic), image extents in layout, floats deferred.

**Files:** new `src/editors/word/layout/` package (proposed: `PageGeometry.kt`, `LineBreaker.kt`, `FlowLayout.kt`, `PageComposer.kt`), `WordEditorScreen.kt`, `WordDocument.kt` (geometry reads).

**Dependencies:** Phases 4–6 (model must expose styles/breaks/geometry); can start the pure-Kotlin layout core in parallel with Phase 6.

**Tests:** Layout unit tests (deterministic fake measurer): page counts, margins, break placement, indentation, spacing; corpus 8; a "Word parity" harness that compares page count/line starts against LibreOffice-generated PDFs for fixtures (offline, manual).

**Acceptance criteria:** For fixtures 1–3 and 8, page count and visible page breaks match Word/LibreOffice within tolerance; editing scrolls to the caret's page; no O(N) full relayout per keystroke (measured budget).

**Risks:** Font substitution and script shaping will differ from Word's (Android text stack vs DirectWrite) — acceptance must be "same page count for Latin fixtures, documented deltas for CJK/Indic", not pixel purity. This is where the schedule can blow up; timebox the parity target and bank the wins.

**Effort:** 25–40 days (≈ 8–12 weeks part-time) for the credible first version. **Deliverable:** paginated rendering with tests and a documented fidelity envelope. **Do not proceed until:** page counts match for the Latin fixture set and performance budgets hold on a mid-range device.

---

### Phase 8 — Advanced Word capabilities (headers, footers, notes, comments, revisions)

**Objective:** The document structures Word users expect are visible and, where safe, editable — or explicitly and safely non-editable.

**Current state:** None are read; all are preserved only by POI's re-serialization; footnote references currently corrupt on edit (§4.2).

**Work packages:**
1. Header/footer parts: parse per section, render into the page flow, support page-number fields (`PAGE`, `NUMPAGES`) as read-only computed values; editing header/footer text (no field editing) if affordable.
2. Footnotes/endnotes: render notes area per page (basic: end of document for endnotes), navigate reference↔note, and make references non-editable islands so they can't be rewritten as literal text.
3. Comments: read + render margin markers, optional create/reply/delete if POI's comment API behaves on Android; otherwise read-only with clear affordance.
4. Tracked changes: surface revisions (insertions/deletions) with accept/reject; at minimum, detect their presence and refuse silent acceptance on save (block editing paths that would merge revisions until implemented).
5. Fields: `.fldSimple` and complex fields rendered as chips; editing a field's cached result is not possible — mark as non-editable.
6. Bookmarks + outline navigation: side drawer with the heading tree (from Phase 4's outline levels), jump-to heading.

**Files:** `WordDocument.kt` (+ `WordHeaders.kt`, `WordNotes.kt` proposed), `WordEditorScreen.kt`, layout package.

**Dependencies:** Phase 7 (rendering surfaces) and Phase 4 (model).

**Tests:** Corpus 9–11; revision-presence "refuse to silently accept" tests; header/footer/page-number rendering assertions.

**Acceptance criteria:** A header with a page-number field renders correctly and is preserved after saving body edits; footnote editing does not corrupt references; a document with tracked changes cannot be silently normalised by an unrelated edit.

**Risks:** POI's footnote/comment APIs on Android are under-tested; complexity of revisions is high — scope carefully (read + guard first, write later).

**Effort:** 20–35 days (≈ 7–10 weeks part-time). **Deliverable:** advanced structure rendering + integrity guarantees. **Do not proceed until:** corpus 9–11 passes, including "no silent revision acceptance".

---

### Phase 9 — Reliability, recovery, performance, accessibility, security

**Objective:** HMX Docs survives real use: large files, low memory, crashes, keyboards, TalkBack, and hostile inputs.

**Current state:** Whole-file memory model; images decoded per block; no autosave; no shortcuts; a11y limited to content descriptions.

**Work packages:**
1. Memory/perf: streamed reads where POI allows, image downsampling by target display size, cap on simultaneously decoded images, lazy decode, document size guardrails with clear messaging, benchmark suite in CI (JVM) + manual device matrix.
2. Autosave + crash recovery using Phase 3's journal: periodic draft persistence, recovery prompt, "restore last saved" path.
3. Input: hardware keyboard shortcuts (Ctrl+B/I/U, Ctrl+Z/Y, Ctrl+F), Tab navigation between blocks, IME insets (edge-to-edge discipline), stylus/selection correctness.
4. Accessibility: semantics for blocks/pages, TalkBack traversal order, minimum touch targets in the table editor, contrast checks, dynamic type.
5. Security review: exported-activity input validation (URI scheme allowlist, size guard before read), FileProvider scope tightening, `allowBackup`/dataExtractionRules, encrypted/malformed handling, and a fuzz corpus run.
6. Telemetry-free crash reporting: since network is forbidden, ship a local "diagnostics" screen (last error, stack, file size) the user can copy manually.

**Files:** `WordEditorViewModel.kt`, `DocumentIo.kt`, `WordEditorScreen.kt`, layout package, `AndroidManifest.xml`, `config/lint.xml`, `res/xml/*`.

**Dependencies:** Phases 3, 7.

**Tests:** Device matrix (low-RAM phone, tablet, foldable), keyboard-only flows, TalkBack pass, fuzz corpus, memory ceilings on a 100+ page doc with images.

**Acceptance criteria:** Open + edit + save a 20 MB / 200-image document without ANR/OOM on a 4 GB device; recovery works after `kill -9`; all shortcuts function; no lint errors (network/aspect ratio/a11y warnings resolved or baselined with justification).

**Risks:** Device diversity; keep the perf harness automated where possible.

**Effort:** 15–25 days (≈ 5–8 weeks part-time). **Deliverable:** hardening pass with evidence. **Do not proceed until:** the device matrix and fuzz suite pass.

---

### Phase 10 — HMX Docs productization, compatibility evaluation, release candidate

**Objective:** Ship HMX Docs 1.0 (or a named beta) with branding, onboarding, file management, measured compatibility, and completed licensing review.

**Current state:** Kern-branded strings/assets; browser with pins/recents/delete; release pipeline publishes debug builds; compatibility claims are formless.

**Work packages:**
1. Rebrand: app name/strings (`res/values/strings.xml`), icons/splash/brand assets (`res/drawable-nodpi`, `mipmap-*`, `brand/`), About/attribution page including AGPL + GPL (Qyra) + third-party notices.
2. Onboarding: first-run explanation of offline/privacy model + storage access rationale (Play policy text), empty states, sample document.
3. File management: recent files correctness (stale/deleted), rename/duplicate/move (if pursued), share-copy flow, "open with" reliability across file managers, and .doc/encrypted messaging.
4. Compatibility evaluation: run the full corpus through Word Android + LibreOffice; publish an honest compatibility matrix (what opens, renders, edits, preserves; known deltas: pagination, fonts, revisions).
5. Release candidate: signed release, version/changelog, staged rollout checklist, legal sign-off (AGPL obligations, trademark, Play declarations), support/diagnostics path.
6. Post-RC gates: crash-free session target (locally measurable only via user reports given no telemetry — define an alternative: opt-in manual export of diagnostics).

**Files:** `res/**`, `README.md`, `CHANGELOG.md`, `docs/**`, release workflow.

**Dependencies:** Phases 1, 9 (signing, hardening), plus the Phase 1 licensing decision.

**Tests:** Full unit + instrumented suites, device matrix, install/upgrade/signature continuity, upgrade from Kern's debug build (uninstall guidance), accessibility pass on the release build.

**Acceptance criteria:** Signed RC installs over itself across versions; compatibility matrix published with per-fixture results; legal review recorded; no P0/P1 defect open.

**Risks:** Legal timeline; rebrand churn colliding with bug fixing — freeze features before RC.

**Effort:** 15–25 days (≈ 5–8 weeks part-time), excluding legal turnaround. **Deliverable:** HMX Docs release candidate + compatibility and compliance documentation. **Do not proceed until:** legal sign-off and the RC checklist are complete.

---

## 9. First Ten Actions, in Exact Order

1. Record the licensing decision for HMX Docs (AGPL-compatible derivative vs proprietary; if the latter, stop and re-plan the product base) — blocks everything downstream.
2. Freeze the baseline: tag the audited commit, verify green CI on a fresh branch (build + unit tests + lint + R8), and add the missing Gradle wrapper jar.
3. Implement release signing (keystore in CI secrets, `signingConfig`, publish `assembleRelease`) and remove the debug-release warning path.
4. Build the DOCX corpus generators (fixtures 1–7, 14–15) and the expected-failure probe tests for the five confirmed defects (index shift, column delete, hyperlink loss, footnote marker, undo-rewrites-all).
5. Add the canonical XML differ + no-op round-trip preservation test to CI and record the baseline report.
6. Fix the block↔`bodyElements` index mapping (explicit `elementIndex` on every block type) with regression tests.
7. Fix `writeTable` column deletion (and grid widths on insert) with regression tests.
8. Replace truncating in-place writes with the safe-save path (temp + commit, journal, post-save `originalBytes` refresh) with crash-injection tests.
9. Stop `applySnapshot` from marking all blocks edited; make undo/redo delta-accurate.
10. Pin the Qyra `kern-ref` in `build-pdf-bridge` and add a third-party notice page skeleton (POI, OpenCSV, jsoup, XMLBeans, commons transitives, Qyra).

---

## 10. MVP Scope and Deferred Features

**MVP definition (HMX Docs 1.0-preview):** an offline Android DOCX editor that opens real Word files, editorially edits common content, and saves without collateral damage.

In scope:
- Open (size guard, clear errors for `.doc`/encrypted/corrupt), safe save with backup/recovery, no network, no data loss on crash.
- Text: paragraphs (insert/split/merge/delete), bold/italic/underline/strike, font family/size/colour, alignment, left indent.
- Styles: Title, Heading 1–3, Body (+ custom styles display as body but preserved); lists: bullet + numbered, 3 levels.
- Tables: text, add/remove rows/cols (correctly), merges rendered read-only, per-cell multi-paragraph text.
- Images: display with real extents; insert from file picker; delete; no floats.
- Hyperlinks: display (tappable), preserve; create/edit simple external links.
- Find & replace (run-preserving), undo/redo (coalesced), word/char count, heading outline navigation.
- Rendering: paginated single-column view honouring page size/orientation/margins and explicit page breaks; no headers/footers on screen (they are preserved).
- Accessibility basics + hardware keyboard shortcuts.

Deferred: headers/footers/page-number editing; footnotes/endnotes editing; comments; tracked changes accept/reject; fields; floats/text boxes; charts/SmartArt rendering; columns; RTL layout; `.doc` import; PDF export; math; content controls; multi-window/tablet two-pane polish; autosave beyond draft recovery (nice-to-have).

---

## 11. Unknowns, Assumptions, and Required Follow-up Inspections

Unknowns / unverified:
1. **POI re-serialization fidelity** for untouched parts and unknown elements — must be measured (Phase 2). The "byte-for-byte" comment is unproven.
2. Actual runtime behaviour of runs inside `w:ins`/`w:del` on real tracked-change documents (code path verified in POI source; no fixture tested).
3. `XWPFRun.addPicture` (and any AWT-touching API) behaviour on Android — engine capability vs Android compatibility.
4. POI's memory behaviour on large documents on Android (heap ceilings, GC churn) — no measurements exist in the repo.
5. Whether CI runs currently green correspond to the exact local file set (local snapshot is not a git checkout; matched by changelog/content, not by hash).
6. Whether any real-world DOCX opens today without an error (no real-world fixture in the repo).
7. Play Store policy acceptance of MANAGE_EXTERNAL_STORAGE for this app.
8. Instrumented tests' current pass status (not run in CI).
9. Legal interpretation of AGPL + GPL obligations for HMX Docs distribution — professional review required.

Assumptions used in the plan: Option A+ engine decision; AGPL-compatible distribution; no server component (so AGPL's network clause stays theoretical); CI (GitHub Actions) is the build environment; solo/part-time execution with occasional full-time bursts; Compose remains the UI stack.

Follow-up inspections recommended before Phase 9: device matrix benchmark, TalkBack pass, storage-provider matrix (local SAF, Drive, OneDrive), and a 20-document real-world compatibility sweep.

---

## 12. Final Go/No-Go Recommendation

**GO — conditional.**

Technical grounds for GO: the app is real, builds green on CI at the audited commit, has a working offline architecture, sane module boundaries, an enforced no-network policy, and — decisively — the repository already contains the correct preservation-first save pattern (EPUB) that the DOCX path lacks. The confirmed DOCX defects are well-localized and fixable inside the current architecture; none of them require replacing POI.

Conditions:
1. **Licensing:** proceed only if HMX Docs will be AGPL-3.0-compatible, source-available, with GPL-3.0 notices for the Qyra bridge and third-party notices added. If HMX Docs must be proprietary/closed, this codebase is not a lawful base and the plan stops at Phase 1 for a re-plan.
2. **Discipline:** Phases 2–3 (corpus + safe save + defect fixes) must complete before any new editing feature. Feature work on the current write path ships data loss.
3. **Scope honesty:** the MVP promises "Word-like editing of common content with page-accurate rendering for Latin documents within a documented tolerance" — not Microsoft Word compatibility. Pagination fidelity (Phase 7) is the schedule risk; it is scoped as its own subsystem with measurable acceptance.
4. **Release engineering:** no signed release until Phase 1's signing work is done.

Do not start Phase 1 code work until the plan is approved. On approval, the first commit should be Phase 1's CI/verification work plus the licensing decision record — nothing in the Word engine until Phase 2's corpus is measuring it.
