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
| 13 | Document access (`content://` grant) | A document opened from the picker or an "Open with" hand-off could become unreadable after a device restart, and the raw platform denial text was shown. | Not content loss — availability/usability defect | **Fixed** — see `docs/decisions/0002-persistable-uri-access.md`; the user now reads "Access to this file has expired. Open it again to continue." with a "Back to files" action. Follow-up 2026-10-17: the failure is classified rather than always called "expired", "Try again" is offered when a second read could help, and the route argument is decoded once instead of twice |

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
- Unsaved edits live in the editor's memory only, so a process death *while editing* loses
  them: there is no draft/journal yet (roadmap Phase 4). Access expiring mid-session does
  not lose them — a failed save keeps the document, the edits and the Export path in place,
  and `src/shared/ui/UnsavedChangesGuard.kt` still stands between the edits and a back press.
  A second "Open with" no longer replaces the editor either, so it cannot discard them
  (ADR 0003).
- Provider saves are not atomic: the platform has no replace-document operation for a
  `content://` target, so a kill or an OOM *during* the write can still leave a damaged file
  even though the save is reported as failed — the rollback only runs while the process
  lives. Local `file://` saves are replaced by an atomic rename instead (ADR 0003). `[code]`
- The pre-write check is package-level, not semantic: it catches output that is truncated or
  unopenable, and the stored file is not re-read after a successful write, so a provider that
  keeps less than it was handed is not detected. `[docs]`
- The failure classification and the retry decision are unit-tested against a faked resolver
  (`tests/shared/DocumentIoTest.kt`). Which exception a real provider throws for a revoked
  grant, and what a device shows after a force-stop, is device work (§8) `[not-verified]`.

## 6. Safe save and recovery

- **Serialize, check, then replace.** The document is serialized in full in memory first
  (`XxxDocument.applyEditsAndSerialize`), the resulting package is checked for integrity
  (`DocumentIo.verifyZipPackage`: it has entries, every entry inflates to its end, and the
  end-of-central-directory record is present), and only then is the destination touched. A
  rejected replacement throws before anything is opened and reads "The updated document
  could not be written safely, so the file was left unchanged. Use Export to save a copy." —
  the file keeps its last valid version (`DocumentIo.kt`, ADR 0003). Word, Excel, EPUB and
  PPTX pass this check; CSV is plain text and does not.
- **Local files are replaced by rename.** A `file://` document (what the in-app browser
  opens) goes through `DocumentIo.replaceFileAtomically`: the new content is written to a
  temporary file in the same directory, flushed to storage, and renamed over the document. A
  failure anywhere leaves the previous file in place, and no second full copy is held in
  memory for a rollback.
- **Provider writes keep a rollback copy.** There is no atomic replace for `content://`, so
  the previous content is read first and written back if the write throws, and the *original*
  failure is rethrown, so a failed save is never reported as success. When the previous
  content cannot be read, that is logged instead of silently skipped; if the rollback also
  fails, the failure is logged and the user is told (`DocumentIo.kt`).
- **One save at a time, and only the written revision clears dirty.** Every editor refuses a
  second save while one runs, the chrome reads "Saving…" and disables Save during it, and the
  dirty state is cleared only when the snapshots that were written still match the editor's
  (`save`/`snapshotForSave` in each `*EditorViewModel.kt`). EPUB keeps edits made during a
  save in its edit map instead of clearing the whole map.
- **Word parses the document once per save** (`WordDocument.readDocument` resolves the block
  indices on the document being edited), instead of reading the package a second time just to
  map blocks to body elements.
- **A second "Open with" no longer discards edits.** The document is pushed on top of the
  stack (`MainActivity.kt`), so the editor below keeps its ViewModel and its unsaved edits;
  back returns to it (ADR 0003, superseding the replacement behaviour described in ADR 0002).
- **Nothing is written before the new bytes exist** — the document is materialised in memory
  by design (ADR 003).
- **Failure text.** Load: revoked grant / denial while a grant is still held / file moved
  or deleted / all-files access turned off / provider failure / the editor's own fallback.
  Save: "This file can't be changed. Use Export to save a copy." for permission failures,
  and the "left unchanged" wording above for a replacement that failed its check. Provider
  names, PIDs, URIs and paths never reach the user (`DocumentIo.kt`, asserted by tests).
- **Recovery affordance.** A load failure offers "Try again" — one read per tap, shown only
  when a second read could fix the failure, never for a document that is gone — beside
  "Back to files" (`src/shared/ui/EditorChrome.kt`, `src/shared/ui/GridEditorScreen.kt`).
  Reselecting through the picker renews the grant; reopening the same document from the
  in-app browser needs no grant at all, because it is read as a file. Nothing retries on its
  own: no resume-triggered loop, and a second tap while a read is in flight is ignored. No
  draft/journal file is written: the app never copies documents into private storage
  (ADR 003), so there is nothing to recover *from*.
- **Not covered:** a crash *between* the write and the provider's commit, a provider that
  implements a partial write without throwing, and the stored file not being re-read after
  the write (the check runs on the bytes before it). A draft/journal for a process death
  while editing is roadmap Phase 4 work. All of it needs device tests (§8) `[not-verified]`.

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
  - Access-classification follow-up for `c4a4ffc9afc091c8ddcf65106f0d40afaf00ce3e` — **observed**,
    all green: [Android CI 39011723281](https://github.com/bharti1233/Hmx-docs/actions/runs/39011723281)
    and [Release build check 39011723287](https://github.com/bharti1233/Hmx-docs/actions/runs/39011723287):
    - unit tests **74 tests, 0 failed, 0 errors, 1 skipped** in 10 suites (71 → 74: the four
      `loadError` tests became seven `loadFailure` tests for the new classification).
    - lint **0 errors, 49 warnings** — unchanged again.
    - `Kern-debug.apk` sha256 `62923b20267cdbef6120cb3dd79e543a1ac40b93ca03074ff7c124e92decf6ff`.
    - `Kern-release-unsigned.apk` sha256 `3781edbfb8e009e9e3312c893b946d03542142232d3c901909b7ff9dde1d01e5`
      (7,149,520 bytes); `apksigner` rejects it, and R8 reports were produced.
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
15. **Revoked grant.** Android has no general UI for revoking one document grant, so revoke
    by reinstalling the app (grants are dropped with the package) or take the reboot path
    (step 4). *Expected:* the editor says access expired and offers "Try again" and
    "Back to files"; **no** raw platform text, **no** crash, and never a blank document
    standing in for the file.
16. **Reselect and recover.** From that state: (a) tap "Back to files" and open the same
    document again through the picker; and (b) open the same document from Files with
    "Open with" while the app is still running. *Expected:* both show the current content on
    disk and saving works again; (b) must not stack a second app instance, and back returns
    to the editor that was open before (which keeps its state; the expired one stays
    recoverable through "Try again" / "Back to files").
17. **Try again.** In the failure state, tap "Try again" while the document is still
    unreachable. *Expected:* one more attempt and then the same message — no self-retrying
    loop, no endless spinner. For a failure a second read cannot fix (a deleted document)
    the button is not offered at all.
18. **Unsaved edits survive an access failure.** Edit a document, make the save fail
    (step 5), then rotate the device and use Export. *Expected:* the edits are still in the
    editor after rotation, the exported copy contains them, and a back press still asks
    before discarding them. (A *process* death during editing is the one loss this phase
    does not prevent — roadmap Phase 4.)
19. **Save while saving.** Edit a large document and tap Save twice in quick succession, or
    tap Save and keep typing. *Expected:* the second tap does not start a second write; the
    subtitle reads "Saving…" and Save is disabled while the write runs; if the typing landed
    after the save started, the editor still shows "● Unsaved changes" when it finishes and
    a second Save stores that text.
20. **Local file replace.** Edit and save a document opened from the in-app browser (a
    `file://` path under Documents/Downloads), then kill the app right after the save.
    *Expected:* the file opens in Word/Docs with the edit, and no `.tmp` file is left beside
    it.
21. **Failed save keeps the file.** Edit a cloud document, turn airplane mode on, save, then
    reopen the file after reconnecting. *Expected:* a save-failed dialog; the file on the
    provider still holds the previous content (nothing half-written), and Export still
    writes the edited copy.
22. **Undo/redo then save state.** Edit, save, undo the edit, save again. *Expected:* the
    file matches what the editor shows after each save, and unrelated content is unchanged.
23. **Two documents in a row via "Open with".** Edit a document without saving, then open a
    second document from Files with "Open with". *Expected:* the second opens on top; back
    returns to the first with its unsaved edits still there (Phase 3 change, ADR 0003).

Steps 4, 5 and 12 are the ones Phase 2's changes were meant to alter; steps 15–18 cover the
access-recovery follow-up; steps 19–23 cover the Phase 3 save-safety changes; steps 6–11 are
the ones the preservation suite predicts and a device must confirm. A device is the only
place any of them can be settled.

## 9. Out of scope for this phase

Pagination and page layout, UI redesign, engine rewrite (no switch away from POI),
licensing changes, Gradle/Actions changes (`setup-gradle@v4` and the unsigned release
APK stay as they are), new dependencies, and the Phase 3 draft/journal work. Storage
permissions were deliberately **not** widened: `MANAGE_EXTERNAL_STORAGE` does not grant
access to another app's `content://` provider document `[aosp; see the decision
record]`.
