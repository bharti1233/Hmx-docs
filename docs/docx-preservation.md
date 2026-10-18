# DOCX preservation — audit, fixes, and regression suite (Phases 2–4)

- **Status:** implemented; CI verification in §7, device verification still open (§6, §8)
- **Date:** 2026-10-16 (Phases 2–3) · 2026-10-18 (Phase 4: document-model fidelity, §1.1, §2 rows 3/8/11/14–18, §3 Phase 4, §5, §8 steps 26–32)
- **Scope:** what a save preserves, what it does not, the document model and its sources
  of truth, the document-access (URI permission) defect, safe save, and the regression
  suite that pins all of it.
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

### 1.1 The document model and its sources of truth

One authoritative model per open document: `WordDocument.read()` produces the block
list the UI renders, and every save re-reads the package and applies only the
recorded changes to it. The UI never serializes itself back into the file `[code]`.

| Layer | Owns | Writes |
|-------|------|--------|
| `.docx` package | text, character and paragraph styles, numbering definitions, tables, pictures, headers/footers, sections, tracked changes, content controls | only the parts the save touched |
| `WordDocument` (POI bridge) | block list, run list, paragraph properties, table grid, picture bytes, `Parsed.elementIndices` (block → body element) | paragraphs and tables whose block index has a recorded edit |
| `WordEditorViewModel` | pending changes (`runEdits`, `paraPropOps`, `tableEdits`), undo/redo snapshots | the model's blocks, then the file through `applyEditsAndSerialize` |
| `WordRichText` (Compose field) | the text and selection the user sees, plus span annotations | the run list of the edited block (`editRuns` / `replaceRuns`) |

Consequences the rest of this document relies on:

- **The document is the single source of truth for formatting.** The rich-text
  field emits text; the editor maps that text onto the model's runs, so a style
  cannot exist in the field but not in the model.
- **A block index is a layout index, not a body index.** `elementIndices` resolves
  it back through the same parse the save uses (§2 row 1).
- **Untouched content is never rewritten.** A save writes only what the view model
  recorded, which is what makes "preserved" mean more than "still parses".
- **The model is lossy on purpose.** Runs holding what it cannot express stay in
  the XML and are skipped by the model (`hasUnmodelledContent`); the model never
  claims to represent them.

## 2. Data-loss audit

Classification of every path found while auditing the DOCX round trip. "Safely
retained" means the content is not modelled by the editor but is not rewritten either;
"known data-loss" means a real, documented loss that this phase does not fix.

| # | Path | Behaviour before this change | Classification | Disposition |
|---|------|-----------------------------|----------------|-------------|
| 1 | Block index used as body-element index (`WordDocument.kt` HEAD `read()` 106-123 / `applyEditsAndSerialize()` 137-144, 182) | A paragraph with pictures produced one extra image block, so every later block index pointed one body element too far. An edit could be written into the wrong paragraph, or dropped when the index ran past the end. | Known data-loss (wrong text saved) | **Fixed** — `Parsed.elementIndices` maps blocks to elements (`WordDocument.kt:108-154`), edits resolve through it (`:169-179`, `:216-221`) |
| 2 | Edits with no resolvable body element (`applyEditsAndSerialize() ?: continue`) | The edit was silently discarded: the save reported success and the user's text was gone. | Known data-loss (silent) | **Fixed** — throws `IllegalStateException` (`:176-179`, `:217-220`); editors show "Could not save the document. Use Export to save a copy." |
| 3 | Editing a paragraph deletes runs the model cannot rebuild (`writeRuns()` HEAD 288-292 removed **every** run) | Pictures, `w:fldSimple` fields, footnote/endnote references, comment marks, symbols and internal (bookmark) links that shared a paragraph with edited text were deleted. | Known data-loss | **Fixed** — runs with unmodelled children are kept, only their text children are dropped; Phase 4 gives each kept run back its *position*: `writeRuns` records its character offset, `mapAnchor` maps that offset through the edit, and `insertNewRun(index)`/`insertNewHyperlinkRun(index, url)` write the new text around it `[poi verified]` |
| 4 | Hyperlink target lost on edit of its paragraph (`readParagraph()` HEAD 198 stored the relationship id; `writeRuns()` HEAD 291 always created a plain run) | The URL was not re-attached, and what the model held was a relationship id, not a URL. | Known data-loss | **Fixed** — the target URL is resolved on read (`:236-240`), carried through the UI as a string annotation (`WordRichText.kt:32, 42, 60-63, 110-131`) and rewritten as a hyperlink run (`:387-388`) |
| 5 | Tab characters in edited text (`writeRunText` HEAD 293-299 split on `\n` twice, never on `\t`) | `w:tab` was read as `"\t"` and written back as a literal tab character inside `w:t`, which Word does not render as a tab stop. | Intentionally modified (silent) | **Fixed** — tabs are written as `w:tab` (`:394-403`; asserted against `word/document.xml` in the suite) |
| 6 | Deleting a table column (`writeTable()` only added cells) | The editor's grid shrank, the saved table kept the old cells, so the deleted column came back on reload. | Known data-loss (stale cells) | **Fixed** — surplus cells are removed from the XML (`:420-424`) |
| 7 | Untouched paragraphs, tables, headers, footers, pictures, styles | Left as parsed; POI re-serializes them. | Preserved | Asserted by the suite (`saveWithoutEdits_keepsEveryFixtureSemanticallyIdentical` and per-scenario tests) |
| 8 | Runs that carry content the model cannot rebuild, in paragraphs that are *not* edited | Never touched. | Safely retained | Asserted for fields; Phase 4 adds injected-XML fixtures for tracked changes and content controls (`editParagraphElsewhere_keepsTrackedChangesAndContentControls` observes their XML unchanged); footnotes, comments and floating shapes remain `[not-verified]` — no fixture generator, listed in §5 |
| 9 | Run properties the run model does not carry — superscript/subscript, character styles, language, proofing marks (`RunStyle` has bold/italic/underline/strike/size/colour/font/link only) | Lost **for the edited paragraph**, because its runs are rebuilt from the model. | **Known data-loss** (documented) | Not fixed this phase; one `@Ignore`d probe test records the expected behaviour (`WordPreservationTest.editingAParagraphKeepsSuperscript`) |
| 10 | Field cached result text (`w:fldSimple`'s `w:r`) inside an edited field paragraph | The field structure is kept, its own text child is dropped, and the paragraph shows exactly the edited text. | Intentionally modified (no duplication) | Asserted (`editAFieldParagraph_keepsTheField`) |
| 11 | Applying bullet/number formatting to a document that has no numbering definitions (`ensureNumbering()` created an **empty** numbering part `[poi verified: XWPFDocument.createNumbering() 1017-1027]`) | `numID` was set but no definition matched it, so the list rendered as plain text `[inference]`. When the document *did* have definitions, num ids 1 and 2 were reused blindly, so a "bullet" could point at someone else's numbered list. | Known limitation (pre-existing) | **Fixed (Phase 4)** — `createNumberingDefinition` writes a real abstract numbering (three levels) plus a concrete num, with ids allocated above the document's own, and `applyEditsAndSerialize` leaves a paragraph that is already in that list type alone. Asserted by `applyBullet_writesADefinitionTheDocumentCanResolve`, `applyNumberToTwoParagraphs_putsThemInOneList`, `applyBullet_keepsTheExistingListsAndTheirDefinitions`, `applyNumberToAnItemThatIsAlreadyNumbered_doesNotRestartIt`, `changeTheListTypeOfAnItem_movesItToTheOtherList` |
| 12 | Undo/redo rewrites the runs of every touched paragraph | Content is rebuilt from the model, so the run *contents* survive (they are the same runs), but the paragraph XML is rewritten. | Intentionally modified (content-neutral after fix 3) | No editor change this phase; the plan's "rewrites unrelated paragraphs" concern is neutralised by run preservation, and the mapping fix (§3) keeps indices correct after an undo |
| 13 | Document access (`content://` grant) | A document opened from the picker or an "Open with" hand-off could become unreadable after a device restart, and the raw platform denial text was shown. | Not content loss — availability/usability defect | **Fixed** — see `docs/decisions/0002-persistable-uri-access.md`; the user now reads "Access to this file has expired. Open it again to continue." with a "Back to files" action. Follow-up 2026-10-17: the failure is classified rather than always called "expired", "Try again" is offered when a second read could help, and the route argument is decoded once instead of twice |
| 14 | A list paragraph that carries `w:numId` but no `w:ilvl` (common in converted documents) | `getNumFmt()` matches no level and returns `null`, so a bullet was read as a number and the list buttons acted on the wrong type. | Misread, not lost | **Fixed (Phase 4)** — `definitionFormat` falls back to the definition's first level, which is where OOXML puts an item without `w:ilvl`; asserted by `load_readsABulletThatCarriesNoItemLevelFromItsDefinition` |
| 15 | A table cell with more than one paragraph (`XWPFTableCell.getText()` concatenates paragraphs with no separator `[poi verified]`) | Read as one line (`FirstSecond`), and any save wrote that back into the cell's first paragraph, duplicating the rest. | Known data-loss | **Fixed (Phase 4)** — `readCellText` reads one line per paragraph, `writeCell` writes one paragraph per line; asserted by `load_readsACellsParagraphsAsLines`, `saveWithoutEdits_keepsMultiParagraphCellsTheirFormattingAndTheirPicture`, `editATwoParagraphCell_keepsBothLines` |
| 16 | Every table save rewrote every cell's first paragraph | The cell text was replaced by an unformatted run, so character formatting inside cells the user had not touched was dropped, and a picture or field inside a cell was deleted. | Known data-loss | **Fixed (Phase 4)** — `writeTable` compares each cell with its text and calls `writeCell` only for changed ones; a rewritten cell keeps its own first-run formatting and its inline content (`cellBaseStyle` + the ordered `writeRuns`). Asserted by `editOneCell_changesOnlyThatCellAndKeepsTheOthersWhole`, `editACellWithAPicture_keepsThePicture`, `editACellThatHoldsAWholeCellLink_keepsTheTarget` |
| 17 | Text inside a tracked deletion (`w:del` / `w:moveFrom`) | POI's `XWPFRun.text()` includes `w:delText` `[poi verified]`, so text Word hides was shown as editable text and written back as ordinary text on the next edit of that paragraph. | Known data-loss (hidden text resurrected) | **Fixed (Phase 4)** — runs inside a deletion are read out of the model (`isTrackedDeletion`), kept in place, and never rewritten or text-stripped; asserted by `load_hidesTrackedDeletionsButKeepsThemInThePackage`, `editingAParagraphWithATrackedDeletion_keepsTheDeletionIntact` |
| 18 | Section properties, headers/footers, styles and media parts | Not modelled, not rewritten. | Preserved | Asserted for the section (`editParagraph_keepsSectionProperties`: page size and margins) and for headers/footers (`editParagraphElsewhere_keepsHeaderAndFooter`) |

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

### Phase 4: document-model fidelity

7. **Ordered run rewrite** (`WordDocument.kt`): `writeRuns` collects the runs it must
   keep together with their character offsets *before* removing anything, removes the
   model-owned runs, then rebuilds the paragraph around the kept ones with
   `insertNewRun(index)` / `insertNewHyperlinkRun(index, url)` `[poi verified: both
   accept an insert position and fall back to append at the end]`. `mapAnchor` maps an
   old offset through the edit by common prefix/suffix, so text inserted at a kept run's
   position stays in front of it — the caret was beside it, as in Word — and an offset
   inside replaced text moves to the end of the replacement instead of splitting a word.
8. **Real numbering definitions** (`WordDocument.kt`): `createNumberingDefinition`
   builds an abstract numbering with three levels (bullet `•`/`o`/`▪`, decimal
   `%1.`/`%2.`/`%3.`, 720-twip steps) and a concrete num, using ids above everything the
   document already uses. `applyEditsAndSerialize` shares one definition per list type
   per save (so two paragraphs applied in one save form one list) and leaves a paragraph
   that is already in that list type untouched (so re-applying Number cannot restart a
   list). The definition is committed with the rest of the package
   `[poi verified: POIXMLDocument.write() → onSave() → XWPFNumbering.commit()]`.
9. **Defensive numbering read** (`WordDocument.kt`): `listTypeOf` and `definitionFormat`
   wrap POI's `getNumFmt`, so a dangling `numId` — a num whose abstract definition is
   missing — can no longer stop a document from opening or saving `[poi verified:
   XWPFParagraph.getNumFmt() throws NPE on a missing abstract numbering]`. Such an item
   keeps its reference and reads as a numbered item.
10. **Table cell fidelity** (`WordDocument.kt`): a cell reads as one line per paragraph
    and writes back one paragraph per line; a cell the editor did not touch is left
    exactly as it was; a rewritten cell keeps its own first-run formatting, its inline
    content and a link that covered the whole cell (a partly linked edited cell becomes
    plain text — §5).
11. **Tracked deletions** (`WordDocument.kt`): runs inside `w:del`/`w:moveFrom` are
    excluded from the editable text, kept in place, and left untouched by a rewrite.

### Phase 5: structured text operations

12. **Block operations** (`WordDocument.kt`, `WordEditorViewModel.kt`,
    `WordEditorScreen.kt`): split at the caret, insert a paragraph with inherited
    formatting, merge with the previous paragraph and delete a paragraph are applied to
    the document itself (`applyEditsAndSerialize(..., blockOp = ...)`) and the result is
    read back as the new model, so nothing has to keep a block index across a change.
    Enter starts a new paragraph; the toolbar carries the other three actions plus a
    picture. The paragraph's `w:pPr` is copied wholesale minus `w:sectPr`, and the
    body's trailing `w:sectPr` stays last `[poi verified: insertNewParagraph(anchor
    cursor) inserts before the anchor; unsetting an absent sectPr on a detached copy
    throws, so the copy checks first]`.
13. **Lists across a split** (`WordDocument.kt`): both halves keep the paragraph's
    `numId`/`ilvl`, so the text after the caret continues the same list item sequence
    and no numbering definition is invented. `[harness verified]`
14. **Real list levels** (`WordDocument.kt`): indent/outdent move an item through the
    `w:ilvl` values its own definition offers (falling back to the deepest one below,
    and to paragraph indentation outside a list); `ParaProps.maxListLevel` keeps the UI
    inside that depth. The items of one list keep sharing a single definition.
    `[harness verified]`
15. **`w:tblGrid` kept in step with the cells** (`WordDocument.kt`): a column is added or
    removed only together with its grid entry, and a new column takes its neighbour's
    cell properties with `gridSpan`/`vMerge` removed. A table with merged cells keeps its
    row and column structure: the shape check refuses such a change in the model, and the
    editor disables the controls (its cell text is still editable). `[harness verified]`
16. **Pictures** (`WordDocument.kt`, `WordEditorViewModel.kt`): a picture picked on the
    device is sniffed by magic bytes, stored as it is (never re-encoded) in a paragraph of
    its own at the caret, and scaled down to the 6.5 in text column when wider; a picture
    can be removed by its ordinal inside the paragraph; a file that is not a picture this
    editor can place is refused with a message.
17. **Undo/redo over structural changes** (`WordEditorViewModel.kt`): a history snapshot
    carries the document bytes, the blocks, the paragraph fields, the properties and the
    table grids it belongs to, and `undo`/`redo` rebuild the pending writes from the
    restored state, so an undone change cannot be written back by the next save.
    `[vm tested]`

## 4. Regression suite

Generated fixtures keep binaries out of git (the repository's existing pattern);
each fixture is deterministic and small. The builders cover the phases' fixture
types: plain paragraphs · formatted runs · breaks and tabs · table
(paragraph/table/paragraph order) · hyperlink · images (two in a captioned paragraph,
one picture-only paragraph) · mixed (heading, numbering, table with an empty cell,
field, blank paragraph, unicode, 120-word paragraph) · header and footer · numbering ·
and, from Phase 4: lists (bullets with a level, two numbered lists) · a picture
between two text runs · two links in one paragraph · a rich table (two-paragraph cell,
bold cell, empty cell, picture cell) · section properties · a package with `w:ins`,
`w:del` and `w:sdt` XML injected into `word/document.xml` (POI cannot generate them,
so the fixture rewrites that one part and leaves every other entry as it was).

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
| list reading (bullets, levels, restarts) | `load_readsBulletsNumbersAndListRestartsApart`, `load_readsABulletThatCarriesNoItemLevelFromItsDefinition` |
| list writing (definitions, sharing, no restarts) | `applyBullet_writesADefinitionTheDocumentCanResolve`, `applyNumberToTwoParagraphs_putsThemInOneList`, `applyBullet_keepsTheExistingListsAndTheirDefinitions`, `applyNumberToAnItemThatIsAlreadyNumbered_doesNotRestartIt`, `changeTheListTypeOfAnItem_movesItToTheOtherList` |
| malformed numbering | `aDanglingNumberingReferenceDoesNotStopOpenOrSave` |
| ordered run rewrite | `editCaption_keepsThePicturesAfterTheEditedText`, `editTextAroundAPicture_keepsThePictureBetweenTheText`, `editAFieldParagraph_keepsTheField` (asserts run order) |
| two links in one paragraph | `editTextBetweenTwoLinks_keepsBothTargets` |
| table cells (lines, formatting, inline content) | `load_readsACellsParagraphsAsLines`, `saveWithoutEdits_keepsMultiParagraphCellsTheirFormattingAndTheirPicture`, `editOneCell_changesOnlyThatCellAndKeepsTheOthersWhole`, `editATwoParagraphCell_keepsBothLines`, `editACellWithAPicture_keepsThePicture` |
| cell links | `editACellThatHoldsAWholeCellLink_keepsTheTarget`, `editACellThatHoldsAPartlyLinkedText_writesPlainTextAndDropsThePartialLink` |
| section properties | `editParagraph_keepsSectionProperties` |
| tracked changes and content controls | `load_hidesTrackedDeletionsButKeepsThemInThePackage`, `editParagraphElsewhere_keepsTrackedChangesAndContentControls`, `editingAParagraphWithATrackedDeletion_keepsTheDeletionIntact` |
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
(§8); tracked changes and content controls are covered by injected-XML fixtures for
*preservation* only — the editor cannot edit them and never rewrites their XML;
footnotes/endnotes, comments and floating shapes still have no POI fixture generator;
the UI-level hop that carries the link URL (`WordRichText.toRuns`) is read-reviewed, not
unit-tested, because it needs Compose text classes in a strict `android.jar` unit-test
environment `[inference]`.

## 5. Known limitations (documented, not fixed)

- Run properties outside the model are lost when *that* paragraph is edited
  (§2 row 9). Fixing it means extending `RunStyle`; the `@Ignore`d probe test records the
  expected behaviour. Phase 4 did not change this.
- Footnotes/endnotes, comments and floating shapes: preserved when untouched, not
  represented or edited, no fixture generator. `[not-verified]`
- Tracked changes: an untouched paragraph keeps its revision marks exactly; inside an
  *edited* paragraph a tracked insertion's text is rewritten as ordinary text — the
  insertion is effectively accepted, the `w:ins` wrapper stays but empties, so its
  id/author/date no longer describes any run. Tracked deletions and content controls are
  preserved and cannot be edited (their text is not part of the editable text).
  Accepting or rejecting revisions in the UI is not supported.
- Numbering: applying Number in two *separate* saves creates a second definition, so the
  second list starts at 1 — the UI has no "continue list" action, and a list the document
  already carries is never merged into. The definitions the editor creates are three
  levels of one kind (bullet `•`/`o`/`▪`, or `%1.`/`%2.`/`%3.`); a document's own levels
  are preserved, but the UI offers a single level per list type.
- Tables are edited as a grid of strings: editing a cell collapses that cell's per-run
  formatting to the look of its first run, a partly-linked cell loses its link when it is
  edited, and there are no merge operations in the UI (a merged cell cannot be created or
  split here). Merged cells (`gridSpan`/`vMerge`) survive untouched, and because a flat
  grid cannot describe a span, a table that has them refuses row and column changes
  (model and UI both) while its cell text stays editable. `w:tblGrid` now follows the
  cells when a column is added or removed; the added column copies its neighbour's width.
  Advanced table layout is deliberately out of scope.
- Enter now starts a new paragraph (Phase 5). There is no soft line break (`Shift+Enter`)
  or line-break action in the editor, and the IME's action key is "Next", so a keyboard
  that only offers that key still produces paragraphs.
- Removing a picture leaves its image part and relationship in the package. The document
  is valid and Word ignores the part; it is not garbage-collected on save.
- A paragraph that carried a paragraph-level section break keeps it on the first half
  after a split: the break is never duplicated, but the text that moved down belongs to
  the following section. Moving the break to the second half, and editing page setup in
  general, is not offered.
- Only PNG, JPEG, GIF and BMP can be inserted (sniffed from the bytes). WebP and HEIC are
  refused with a message rather than converted, and a picture is never re-encoded.
- The file's bytes, not only the edits, are part of the history: up to four whole-document
  versions are kept in memory per open document (a fifth structural change drops the
  oldest). Text edits share their version, so ordinary typing does not multiply memory.
- A malformed numbering reference (a `numId` whose abstract definition is missing) no
  longer throws on open or save: it reads as a numbered item and its reference is left as
  it is. Other POI failures still surface through the editor's load-error message; the
  full corrupt/unsupported error taxonomy is roadmap Phase 3 work.
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
  - Phase 3 safe save (ADR 0003) for `189729e1f979b052b7f6480848af136f39ce52e7` — **observed**,
    all green: [Android CI 39016588759](https://github.com/bharti1233/Hmx-docs/actions/runs/39016588759)
    and [Release build check 39016588775](https://github.com/bharti1233/Hmx-docs/actions/runs/39016588775):
    - unit tests **89 tests, 0 failed, 0 errors, 1 skipped** in 10 suites (74 → 89:
      `DocumentIoTest` 16 → 26, `WordPreservationTest` 21 → 26; the skipped test is still the
      pre-existing `@Ignore` probe from §2 row 9). The new tests cover the integrity check
      (complete / truncated / no central directory / garbage / empty), no write when the check
      refuses, rollback on write and on flush failures, the atomic local replace (content
      replaced, no temporary left, document untouched when refused), the new failure message,
      and — on the Word side — a save that reopens with the edit and its unrelated content, a
      truncation refusal, an undo/redo fixture loop and "serialization leaves the snapshot
      untouched".
    - lint **0 errors, 49 warnings** — unchanged.
    - `Kern-debug.apk` sha256 `9aabd500c8fe66c3dd57e6227826aaacefeb280b3bede8ed0a996da008a2814c`.
    - `Kern-release-unsigned.apk` sha256 `3f2a1aee33fcd34041d654b592740b1dee6bf15d17b65143fc78fe72057e6a6a`
      (7,149,520 bytes); the workflow's "Verify the release APK is unsigned" and "Verify R8
      actually ran" steps both succeeded, and the R8 reports (mapping/usage/configuration)
      were produced.
    - The first push of this change (`333fb69`) had a test-side literal type error in the new
      zip helper (`byteArrayOf(0x50, …)` needs `.toByte()`); its runs were cancelled when the
      follow-up push arrived, and `189729e1` carries the fix. No product-code change.
  - Field/formatting ownership (the rich-text fix) for
    `8a37c5327225dcad913600323129b7bc8eba6c73` — **observed**, all green:
    [Android CI 39059503862](https://github.com/bharti1233/Hmx-docs/actions/runs/39059503862)
    and [Release build check 39059503859](https://github.com/bharti1233/Hmx-docs/actions/runs/39059503859):
    - unit tests **118 tests, 0 failed, 0 errors, 1 skipped** in 12 suites (89 → 118: the
      rich-text pipeline driven against a real Compose text field, the field tests, and the
      stacked-toolbar-toggle test; the skipped test is still the pre-existing `@Ignore` probe).
    - lint **0 errors, 49 warnings** — unchanged.
    - `Kern-release-unsigned.apk` sha256
      `728188b2ef4e2ac3f8140096fddc2387f34d43b6f6264be81e5b22fbf7f44ba6` (7,149,520 bytes);
      the unsigned-verification and R8 steps succeeded and the R8 reports were produced.
  - Phase 4 document-model fidelity for `f668553bd2804d0b89158db22fd293f2b412a5b8` —
    **observed**, all green:
    [Android CI 39117613412](https://github.com/bharti1233/Hmx-docs/actions/runs/39117613412)
    (Build debug APK, Unit tests and Android Lint all succeeded) and
    [Release build check 39117613379](https://github.com/bharti1233/Hmx-docs/actions/runs/39117613379):
    - unit tests **140 tests, 0 failed, 0 errors, 1 skipped** in 12 suites (118 → 140: 22 new
      tests in `WordPreservationTest` covering list definitions and levels, run order around
      pictures/fields/links, table cells (lines, formatting, inline content, whole-cell and
      partial links), section properties, and tracked changes / content controls).
    - lint **0 errors, 49 warnings** — unchanged.
    - `Kern-debug.apk` sha256
      `9b9183e069b096d62addca90c18448584ef4ff23c8d9c05757dcb857114546e0` (35,436,259 bytes).
    - `Kern-release-unsigned.apk` sha256
      `f9be6162036e8a7a873e786c2a1db421f097954db6f56b1011e0176076e13fec` (7,149,520 bytes);
      "Build release APK (unsigned)", "Verify R8 actually ran" and "Verify the release APK is
      unsigned" all succeeded, and the R8 reports (mapping, usage, configuration, seeds) were
      produced.
    - Three pushes were needed, all recorded here: `e580fe3` did not compile (two
      union-valued schema attributes were read with `longValue()`, and XmlBeans types those
      `Object`; Android CI 39109603391 and Release check 39109603411 both failed on it);
      `93952ad` reads them as numbers — release check passed, Android CI 39110071469 failed in
      the *test* source set instead (an `if`/`else` mixing a `String` with a `ByteArray` in a
      fixture helper); `7702b50` compiled and ran the whole suite, where 6 of the 22 new tests
      failed on test-side expectations only (POI's `addPicture` ignores the file name it is
      given and names the picture data `image1.png`; `blockIndex` matches a paragraph's text
      exactly, and the injected fixture's first paragraph read `SubjectInserted subject`; and
      the picture-anchor case asserted the wrong split when the insertion starts exactly at the
      picture's offset). `f668553` carries those test fixes and is the commit above. Product
      code changed in `e580fe3` (the Phase 4 fixes) and `93952ad` (the compile fix) only.
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
24. **Formatting survives the keyboard.** Open a document, put the caret in a paragraph, tap
    Bold (and separately Italic, Underline, Strikethrough, Size, Colour), then tap elsewhere
    in the text, move the caret with the arrows or a tap, type, backspace, let autocorrect
    fix a misspelled word, and finally save. *Expected:* the formatting is still on screen
    after every one of those interactions and is still in the file after save and reopen.
    Before this fix any IME command replaced the paragraph's formatting with the text
    field's own span-less value, so this step failing again means the field's value is being
    trusted for formatting somewhere new. The merge policy is unit-tested
    (`tests/editors/word/WordRichTextTest.kt`); the IME path itself is device work only.
25. **Find & replace keeps the surrounding formatting.** Put a plain word in a paragraph that
    also has bold or coloured text, then Replace and Replace all that word. *Expected:* only
    the searched text changes; the formatting of the rest of the paragraph stays.
26. **Apply a bullet and a number.** Tap Bullet on a plain paragraph and Number on two other
    paragraphs, save, reopen in Word. *Expected:* real bullet marks, and a single numbered
    list reading 1., 2.; lists the document already had keep their own numbering and their
    place in it. (Before Phase 4 the numbering part was empty, so the list could render as
    plain text.)
27. **Number an item that is already numbered.** Apply Number to a paragraph that is already
    a numbered item. *Expected:* nothing moves — it does not restart at 1 and does not leave
    its list.
28. **Caption beside a picture.** Edit the caption text of a paragraph whose picture sits
    after the text, save, reopen in Word. *Expected:* the picture is still after the caption.
    (Before Phase 4 the picture was written in front of the rebuilt text, jumping to the
    start of the paragraph.)
29. **Picture between two words.** Type on both sides of an inline picture inside one
    paragraph, save, reopen. *Expected:* the picture stays between the words, unrepeated.
30. **Multi-paragraph cell.** In a table whose cell holds two paragraphs, edit one of them,
    save, reopen in Word. *Expected:* the other paragraph is still there exactly once (before
    Phase 4 the cell text was read as one line and duplicated into the first paragraph).
31. **Picture inside a cell.** Edit a *different* cell of a table that has a picture inside a
    cell, save, reopen. *Expected:* the picture is still in its cell (before Phase 4 every
    table save rewrote every cell and deleted it), and cells nobody touched keep their
    bold/italic/size and links.
32. **Tracked change and content control.** In Word, turn on Track Changes, delete a word and
    add one, then open the file here: edit a *different* paragraph, save, reopen in Word.
    *Expected:* the tracked deletion and insertion are still there with their authors
    (Word still shows the deletion hidden), the plain-text content control is still there,
    and only the edited paragraph changed.
33. **Enter starts a paragraph.** Put the caret in the middle of a paragraph and press
    Enter. *Expected:* the text after the caret moves down into a paragraph of its own
    carrying the same style (and list level, if it was a list item), the keyboard stays
    open with the caret at the start of the new paragraph, and after saving Word shows two
    paragraphs.
34. **Paragraph toolbar.** With the caret in a middle paragraph, use "New paragraph",
    "Merge with previous paragraph" and "Delete paragraph" one after another, then undo
    each with the toolbar's undo. *Expected:* each action changes exactly that paragraph in
    the file after saving, the caret lands where typing should continue, and undo restores
    the text, the list and the alignment.
35. **Undo across a structural change.** Type a word, tap Bold, press Enter, then undo
    twice and save. *Expected:* the saved file is the document as it was before the edits —
    no leftover bold, no extra paragraph. (Before this fix a pending formatting write
    survived the undo and was saved.)
36. **List levels.** In a numbered list, use indent, then outdent, save and reopen in Word.
    *Expected:* the item moves through real list levels (`1.` → `a.` → `1.`), the numbering
    of the other list items is unchanged, and the list does not restart.
37. **Table column, grid and spans.** In a plain table, add a column and delete another,
    save, reopen in Word. *Expected:* the table renders with the same number of columns as
    cells in every row, the new column's width matches the one it was added beside, and the
    other cells (including multi-paragraph and picture cells) are unchanged. Then open a
    file with a merged cell: the row/column buttons are disabled, the header says "merged
    cells", and editing cell text still saves with the span intact.
38. **A picture in and out.** Put the caret in a paragraph, tap the picture button, pick a
    PNG or JPEG from the device, save and reopen in Word. *Expected:* the picture appears
    after that paragraph, keeps its proportions, is not converted, and the surrounding
    text is unchanged. Remove it again with the small bin under it and save: *Expected:*
    the picture is gone from the document, the rest of the paragraph and the other pictures
    stay. Pick a WebP or HEIC: *Expected:* a message that it cannot be placed, nothing
    added.
39. **Undo of a picture.** Insert a picture, then undo, then save. *Expected:* the file
    does not contain the picture and the Recent list still opens it.
40. **Split inside a section.** In a document with more than one section, press Enter in a
    paragraph that ends a section, save, reopen in Word. *Expected:* there is still one
    section break (not two) and the document still opens with the same page setup.

Steps 4, 5 and 12 are the ones Phase 2's changes were meant to alter; steps 15–18 cover the
access-recovery follow-up; steps 19–23 cover the Phase 3 save-safety changes; steps 24–25
cover the rich-text editing fix (the editor, not the text field, owns formatting); steps 26–32
cover the Phase 4 document-model fixes (lists, run order, table cells, tracked changes); steps
33–40 cover the Phase 5 structured editing (Enter, paragraph actions, undo over them, list
levels, table grid and spans, pictures, sections); steps
6–11 are the ones the preservation suite predicts and a device must confirm. A device is the
only place any of them can be settled.

## 9. Out of scope for this phase

Pagination and page layout, UI redesign, engine rewrite (no switch away from POI),
licensing changes, Gradle/Actions changes (`setup-gradle@v4` and the unsigned release
APK stay as they are), new dependencies, and the Phase 3 draft/journal work. Storage
permissions were deliberately **not** widened: `MANAGE_EXTERNAL_STORAGE` does not grant
access to another app's `content://` provider document `[aosp; see the decision
record]`.
