# DOCX preservation — audit, fixes, and regression suite (Phase 2)

- **Status:** implemented; CI verification in §7, device verification still open (§6, §8)
- **Date:** 2026-10-16
- **Scope:** what a save preserves, what it does not, the document-access (URI
  permission) defect, safe save, and the regression suite that pins all of it.
- **Evidence tags used below:** `[code]` read in this repository · `[poi]` Apache POI
  5.5.1 source `[verified]` · `[aosp]` Android framework source · `[docs]` official
  Android documentation · `[ci]` observed GitHub Actions result · `[inference]`
  reasoned, not observed · `[not-verified]` needs a device.

## 1. What "preserved" means here

A save is *not* required to produce the same ZIP bytes: POI re-serializes every part it
touches, so the binary differs even when nothing was edited `[code]`. "Preserved" is
therefore defined semantically — paragraph and run text, run traits, table shape and
cell text, hyperlink targets, picture references, field instructions, numbering
references, block order, headers, footers — compared per part, never as a whole-file
diff `[inference; the roadmap's §7.2 warns that whole-file diffs are
namespace/whitespace noise]`.

`applyEditsAndSerialize` only rewrites what the user edited; paragraphs, tables,
headers, footers and styles that were not touched are left in place `[code]`. The
guarantee is deliberately *not* "byte-for-byte": the pre-fix header comment claimed
that and it was never true.

## 2. Data-loss audit

Classification of every path found while auditing the DOCX round trip. "Safely
retained" means the content is not modelled by the editor but is not rewritten either;
"known data-loss" means a real, documented loss that this phase does not fix.

| # | Path | Behaviour before this change | Classification | Disposition |
|---|------|-----------------------------|----------------|-------------|
| 1 | Block index used as body-element index (`WordDocument.kt` HEAD `read()` 106-123 / `applyEditsAndSerialize()` 137-144, 182) | A paragraph with pictures produced one extra image block, so every later block index pointed one body element too far. An edit could be written into the wrong paragraph, or dropped when the index ran past the end. | Known data-loss (wrong text saved) | **Fixed** — `Parsed.elementIndices` maps blocks to elements (`WordDocument.kt:108-154`), edits resolve through it (`:169-179`, `:216-221`) |
| 2 | Edits with no resolvable body element (`applyEditsAndSerialize() ?: continue`) | The edit was silently discarded: the save reported success and the user's text was gone. | Known data-loss (silent) | **Fixed** — throws `IllegalStateException` (`:176-179`, `:217-220`); editors show "Could not save the document. Use Export to save a copy." |
| 3 | Editing a paragraph deletes runs the model cannot rebuild (`writeRuns()` HEAD 288-292 removed **every** run) | Pictures, `w:fldSimple` fields, footnote/endnote references, comment marks, symbols and internal (bookmark) links that shared a paragraph with edited text were deleted. | Known data-loss | **Fixed** — runs with unmodelled children are kept, only their text children are dropped (`:340-373`, `:375-392`); the content stays, the paragraph text is not duplicated |
| 4 | Hyperlink target lost on edit of its paragraph (`readParagraph()` HEAD 198 stored the relationship id; `writeRuns()` HEAD 291 always created a plain run) | The URL was not re-attached, and what the model held was a relationship id, not a URL. | Known data-loss | **Fixed** — the target URL is resolved on read (`:236-240`), carried through the UI as a string annotation (`WordRichText.kt:32, 42, 60-63, 110-131`) and rewritten as a hyperlink run (`:387-388`) |
| 5 | Tab characters in edited text (`writeRunText` HEAD 293-299 split on `\n` twice, never on `\t`) | `w:tab` was read as `"\t"` and written back as a literal tab character inside `w:t`, which Word does not render as a tab stop. | Intentionally modified (silent) | **Fixed** — tabs are written as `w:tab` (`:394-403`; asserted against `word/document.xml` in the suite) |
| 6 | Deleting a table column (`writeTable()` only added cells) | The editor's grid shrank, the saved table kept the old cells, so the deleted column came back on reload. | Known data-loss (stale cells) | **Fixed** — surplus cells are removed from the XML (`:420-424`) |
| 7 | Untouched paragraphs, tables, headers, footers, pictures, styles | Left as parsed; POI re-serializes them. | Preserved | Asserted by the suite (`saveWithoutEdits_keepsEveryFixtureSemanticallyIdentical` and per-scenario tests) |
| 8 | Runs that carry content the model cannot rebuild, in paragraphs that are *not* edited | Never touched. | Safely retained | Asserted for fields; footnotes, comments, tracked changes, content controls and floating shapes are `[not-verified]` — no fixture generator, listed in §5 |
| 9 | Run properties the run model does not carry — superscript/subscript, character styles, language, proofing marks (`RunStyle` has bold/italic/underline/strike/size/colour/font/link only) | Lost **for the edited paragraph**, because its runs are rebuilt from the model. | **Known data-loss** (documented) | Not fixed this phase; one `@Ignore`d probe test records the expected behaviour (`WordPreservationTest.editingAParagraphKeepsSuperscript`) |
| 10 | Field cached result text (`w:fldSimple`'s `w:r`) inside an edited field paragraph | The field structure is kept, its own text child is dropped, and the paragraph shows exactly the edited text. | Intentionally modified (no duplication) | Asserted (`editAFieldParagraph_keepsTheField`) |
| 11 | Applying bullet/number formatting to a document that has no numbering definitions (`ensureNumbering()` creates an **empty** numbering part `[poi verified: XWPFDocument.createNumbering() 1017-1027]`) | `numID` is set but no definition matches it, so the list may render as plain text `[inference]`. | Known limitation (pre-existing) | Not fixed; numbering depth is roadmap Phase 4/6. Read/save of *existing* numbering is asserted (`editParagraph_keepsNumberingOnListParagraphs`) |
| 12 | Undo/redo rewrites the runs of every touched paragraph | Content is rebuilt from the model, so the run *contents* survive (they are the same runs), but the paragraph XML is rewritten. | Intentionally modified (content-neutral after fix 3) | No editor change this phase; the plan's "rewrites unrelated paragraphs" concern is neutralised by run preservation, and the mapping fix (§3) keeps indices correct after an undo |
| 13 | Document access (`content://` grant) | A document opened from the picker or an "Open with" hand-off could become unreadable after a device restart, and the raw platform denial text was shown. | Not content loss — availability/usability defect | **Fixed** — see `docs/decisions/0002-persistable-uri-access.md`; the user now reads "Access to this file has expired. Open it again to continue." with a "Back to files" action |

## 3. Fixes shipped in this phase

1. **Block → element mapping** (`src/editors/word/WordDocument.kt`): `Parsed` carries
   `elementIndices`; image blocks point at their paragraph; a picture-only paragraph
   gets no editable block; an edit that cannot be resolved fails loudly instead of
   being dropped.
2. **Run preservation** (`WordDocument.kt`): `hasUnmodelledContent` (fields, pictures,
   references, internal links, any child that is not `rPr`/`t`/`br`/`cr`/`tab`) keeps
   runs in place; `dropTextChildren` removes only their text so nothing is duplicated.
3. **Hyperlink round trip** (`WordDocument.kt`, `WordRichText.kt`): the target URL is
   resolved on read, annotated in the rich-text field, and rewritten via
   `createHyperlinkRun`.
4. **Tabs** (`WordDocument.kt`): `writeRunText` writes real `w:tab` elements.
5. **Table column deletion** (`WordDocument.kt`): surplus cells are removed with
   `XWPFTableRow.removeCell` `[poi verified]`.
6. **Document access** (`src/shared/io/DocumentIo.kt`, `src/browser/IntentHandler.kt`,
   `src/browser/FileBrowserScreen.kt`, six editor view models): persistable grants are
   requested per mode with a read-back, and the editors report access failures in
   actionable words instead of the platform's text.

## 4. Regression suite

Generated fixtures keep binaries out of git (the repository's existing pattern);
each fixture is deterministic and small. Nine builders cover the phases' fixture
types: plain paragraphs · formatted runs · breaks and tabs · table
(paragraph/table/paragraph order) · hyperlink · images (two in a captioned paragraph,
one picture-only paragraph) · mixed (heading, numbering, table with an empty cell,
field, blank paragraph, unicode, 120-word paragraph) · header and footer · numbering.

`tests/editors/word/WordPreservationTest.kt` — one test per scenario:

| Scenario | Test |
|----------|------|
| semantic load | `load_readsTextFormattingTablesPicturesLinksFieldsAndNumbering` |
| round trip, no edits (all 9 fixtures) | `saveWithoutEdits_keepsEveryFixtureSemanticallyIdentical` |
| edit a paragraph, everything else identical | `editParagraph_keepsEveryOtherBlockIdentical`, `editParagraph_keepsFormattingOfUntouchedRuns` |
| stale index fails loudly | `applyEdits_withAStaleBlockIndex_failsInsteadOfDroppingTheEdit` |
| images preserved across edits | `editParagraphBetweenPictures_keepsPictureCountAndOrder`, `editCaptionOfAParagraphWithPictures_keepsThePictures` |
| **index mapping regression** | `editParagraphAfterAPictureOnlyParagraph_hitsThatParagraph` |
| hyperlinks preserved | `editLinkedParagraph_keepsItsHyperlinkTarget`, `editParagraphAfterALink_keepsTheLink` |
| tables preserved | `editTableCell_changesOnlyThatCell`, `editParagraphAfterTable_keepsTheTableUntouchedAndInPlace` |
| table structure | `deleteTableColumn_removesTheColumnFromTheSavedTable`, `addTableRow_keepsTheOtherRowsIntact` |
| fields / unmodelled content | `editAFieldParagraph_keepsTheField` |
| headers and footers | `editParagraphElsewhere_keepsHeaderAndFooter` |
| numbering | `editParagraph_keepsNumberingOnListParagraphs` |
| blanks and empty cells | `editParagraph_keepsBlankParagraphsAndEmptyCells` |
| long and unicode text | `editParagraph_keepsLongAndUnicodeText` |
| tabs and breaks | `editingAParagraphWithBreaksAndTabs_writesRealTabStops` |
| documented loss (probe) | `editingAParagraphKeepsSuperscript` (`@Ignore`, see §2 row 9) |

`tests/shared/DocumentIoTest.kt` — pins the access/write decisions with a faked
resolver and a faked writer: read-only grants keep read access · the request order is
read, then read+write · a refused request persists nothing · a missing grant record
does not throw · a write that fails part-way restores the previous content and rethrows
· a first write to a new target reports the failure · the three load-error and two
save-error messages contain no provider name, PID, URI or path.

**Not covered by the suite** (and why): a real provider's behaviour, grant expiry
across a device restart, and the crash-window between write and commit need a device
(§8); footnotes, comments, tracked changes and content controls have no POI fixture
generator, so they are asserted only as "untouched parts stay untouched"; the
UI-level hop that carries the link URL (`WordRichText.toRuns`) is read-reviewed, not
unit-tested, because it needs Compose text classes in a strict `android.jar` unit-test
environment `[inference]`.

## 5. Known limitations (documented, not fixed)

- Run properties outside the model are lost when *that* paragraph is edited
  (§2 row 9). Fixing it means extending `RunStyle` (Phase 4 depth work), and the
  probe test is already in place for it.
- Footnotes/endnotes, comments, tracked changes, content controls and floating
  shapes: preserved when untouched, not represented or edited, not covered by a
  fixture. `[not-verified]`
- Numbering definitions are not created (§2 row 11).
- Within an *edited* paragraph, runs kept for unmodelled content stay in place but the
  new text is written after them, so an inline picture/field/symbol can shift relative
  to the text it sat between. Nothing is lost; a position-aware rebuild needs a richer
  run model (roadmap Phase 4/6).
- A malformed document whose numbering reference points at a missing abstract
  definition can still throw inside POI when the numbering format is read
  (`[poi verified: XWPFParagraph.getNumFmt() 310-330]`); the editor reports its
  open-failure message rather than crashing, but the corrupt/unsupported error taxonomy
  is roadmap Phase 3 work.
- Persisted URI grants are never released: `persistAccess` accumulates grants in the
  system's per-app list. The platform caps the list, and the app has no reason to
  release a document the user may reopen; noted so it is a decision, not an accident.
  `[docs]`
- A provider that never offers a persistable grant (some "Open with" hand-offs) still
  loses access at reboot; the fix there is the error message and the way back to the
  file list, not longer access.

## 6. Safe save and recovery

- **Single guarded write.** The document is serialized in full in memory first;
  `DocumentIo.writeBytes` then keeps the previous bytes and writes once. If the write
  throws part-way (revoked access, provider error, full disk), the previous content is
  written back and the *original* failure is rethrown, so a failed save can never be
  reported as success (`DocumentIo.kt:93-107`). There is no atomic replace in SAF, so
  this rollback is the strongest available guarantee `[docs]`; if the rollback also
  fails, the failure is logged and the user is told the save failed.
- **Nothing is written before the new bytes exist** — no partial serialization ever
  reaches the provider `[code]` (the document is materialised in memory by design,
  ADR 003).
- **Failure text.** Load: expired access / file moved or deleted / all-files access
  turned off / the editor's own fallback. Save: "This file can't be changed. Use
  Export to save a copy." for permission failures. Provider names, PIDs, URIs and
  paths never reach the user (`DocumentIo.kt:202-217`, asserted by tests).
- **Recovery affordance.** A load failure now offers "Back to files" under the message
  (`src/shared/ui/EditorChrome.kt:112-122`, `src/shared/ui/GridEditorScreen.kt:201-213`)
  instead of a dead end. No draft/journal file is written: the app never copies
  documents into private storage (ADR 003), so there is nothing to recover *from* —
  and the guarded write means the file on disk is either the old or the new revision.
- **Not covered:** a crash *between* the write and the provider's commit, and
  providers that implement a partial write without throwing. Both need device tests
  (§8) `[not-verified]`.

## 7. Verification

- Build/test entry points: `.github/workflows/android.yml` (`testDebugUnitTest`,
  `lintDebug`, `assembleDebug`), `.github/workflows/release-build-check.yml`
  (`assembleRelease`, unsigned APK). Local builds are not possible on the development
  device (only the android-36 platform installed, no `local.properties`, `ANDROID_HOME`
  unset, no POI in the Gradle cache, ~515 MB free RAM), so CI is the verification
  channel.
- Results — **observed**, all green:
  - [Android CI 38938323087](https://github.com/bharti1233/Hmx-docs/actions/runs/38938323087)
    and [Release build check 38938323128](https://github.com/bharti1233/Hmx-docs/actions/runs/38938323128)
    for commit `e239ca2`:
    - unit tests **71 tests, 0 failed, 1 skipped** in 10 suites (Phase 1 baseline: 37 tests,
      8 suites). The skipped test is the `@Ignore` probe from §2 row 9.
    - lint **0 errors, 49 warnings** — identical to the Phase 1 baseline, so this change
      adds none.
    - `Kern-debug.apk` sha256 `9d015ec3deb40f23fe739c8983603c530206db3a9c6a97834888ad1d6b3a9aee`.
    - `Kern-release-unsigned.apk` sha256 `a154f4e4b74694f3693de92ab4eed9af20c59ddb26213ca454c3821a48449221`;
      the job proves it is unsigned by requiring `apksigner` to **reject** it, and R8 ran.
  - The first run of this change ([38935275639](https://github.com/bharti1233/Hmx-docs/actions/runs/38935275639))
    failed on two assertions, both test-side (a fixture that replaced a whole paragraph
    without carrying the link URL, and a fake writer that truncated during its rollback);
    fixed in `2187156` and `e239ca2`, with no product-code change.
- What CI cannot prove: device behaviour (the checklist in §8), a real provider's grant
  lifetime and write semantics, and the crash window between write and commit.

## 8. Manual device test plan

Run on a device with Documents/Downloads files and at least one cloud provider
(Drive/OneDrive) mounted. Each step states the expected result; capture before/after
files for the round-trip cases.

1. **Picker, editable file** — open a `.docx` from Documents. Edit text, save, close,
   reopen. *Expected:* the edit is there; the file opens without a permission error.
2. **Picker, read-only file** — open a file the provider exposes as read-only. *Expected:*
   it opens and reads; saving offers "Export to save a copy."; no raw denial text.
3. **"Open with"** — share a `.docx` from a file manager/Files app into HMX Docs.
   *Expected:* it lands in the editor directly.
4. **Grant lifetime (the reported defect)** — from step 1 or 3, force-stop the app,
   **restart the device**, then reopen the app from Recents. *Expected:* if the document
   is listed/openable it opens; otherwise the editor shows "Access to this file has
   expired. Open it again to continue." with "Back to files" — **never** the platform's
   "Permission Denial … ACTION_OPEN_DOCUMENT" text, and never a crash.
5. **Save failure** — open a Google Drive document, turn airplane mode on, edit, save.
   *Expected:* a save-failed dialog with the Export hint; the original file unchanged
   when connectivity returns.
6. **Images** — open a document with an inline picture, edit the caption, save, reopen
   in Word/Docs. *Expected:* text changed, picture still present and in place.
7. **Picture-only paragraph** — same document, edit the paragraph *after* a paragraph
   that contains only an image. *Expected:* the edit lands on that paragraph (this is
   the index-mapping regression).
8. **Field** — open a document with a page number/date field, edit that paragraph,
   save, reopen in Word. *Expected:* the field still works and the edited text appears
   once.
9. **Hyperlink** — open a document with a link, edit the linked sentence's text, save,
   reopen in Word. *Expected:* the text changed and the link still points at the
   original URL.
10. **Table** — delete a column and add a row, save, reopen in Word. *Expected:* the
    deleted column is gone, the added row is present, other cells unchanged.
11. **Header/footer and long document** — open a document with a header and a body over
    ~50 pages, edit in the middle, save, reopen. *Expected:* header/footer intact, no
    truncation, no visible content shift.
12. **Undo/redo then save** — edit three paragraphs, undo two, save, reopen. *Expected:*
    only the final text is saved; nothing else in the document changed.
13. **Unsupported/encrypted** — open a legacy `.doc` or a password-protected `.docx`.
    *Expected:* a clear unsupported/error message, the file untouched (this is the
    roadmap's Phase 3 error-taxonomy work; today the message is the editor fallback).
14. **Rotate and background/foreground** during editing. *Expected:* no data loss, no
    duplicate save.

Steps 4, 5 and 12 are the ones this phase's changes are meant to alter; steps 6–11
are the ones the new suite predicts and a device must confirm.

## 9. Out of scope for this phase

Pagination and page layout, UI redesign, engine rewrite (no switch away from POI),
licensing changes, Gradle/Actions changes (`setup-gradle@v4` and the unsigned release
APK stay as they are), new dependencies, and the Phase 3 draft/journal work. Storage
permissions were deliberately **not** widened: `MANAGE_EXTERNAL_STORAGE` does not grant
access to another app's `content://` provider document `[aosp; see the decision
record]`.
