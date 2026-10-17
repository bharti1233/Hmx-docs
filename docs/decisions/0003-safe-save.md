# 0003 - Safe save: integrity checks, atomic local replace, one save at a time

Status: accepted (Phase 3)
Date: 2026-10-17
Audited at: `5f29fb3` (Phase 2 close-out)

## Context

Phase 3 asks for the save pipeline to be audited and made safe against data loss:
never destroy the last valid copy, do not report a save that did not happen, do not
mark a document saved when newer edits are still pending, and keep recoverable edits
available after a failure. This note records what the pipeline actually did at
`5f29fb3`, which of those behaviours were confirmed risks, and what changed.

The audit covers every editor that writes in place (Word, Excel, CSV, EPUB, PPTX) and
the two ways a document leaves the app: Save (same URI) and Export/Save-as (a URI the
user picks). There is no autosave, no background save, and no lifecycle-triggered save;
a save only runs when the user taps Save or Export.

## What the pipeline did (audited behaviour)

| Step | Behaviour at `5f29fb3` |
|------|------------------------|
| Edit → dirty | The edit maps (`edits`, `paraPropOps`, `tableEdits`, …) are Compose state; `dirty` is set by the same edits (`dirty` is derived from the edit map in EPUB). |
| Snapshot | `write()` copies the edit maps (`edits.toMap()`) on the main thread *before* the IO coroutine starts, and serialization uses only those copies. An edit made while the save runs is not half-serialized. |
| Serialize | `XxxDocument.applyEditsAndSerialize(originalBytes, …)` builds the whole package in memory and returns `ByteArray`. The destination is not opened yet. Word parsed the document twice per save (once to resolve block → body-element indices, once to edit). |
| Check | None. Whatever the serializer returned was written. |
| Open destination | `DocumentIo.writeBytes` → for `file://` a `File.writeBytes` (truncates); for `content://` `openOutputStream(uri, "wt")` (truncates at open). |
| Rollback | The previous content is read into memory first and written back if the write throws (`writeGuarded`). Only effective while the process survives, and only when the previous content could be read. |
| Success flag | `save()` sets `dirty = false` (EPUB: clears the whole edit map) on any successful write, whatever happened since. |
| Verify after | None: the saved file was never re-read. |
| UI | The subtitle showed "● Unsaved changes" until the flag cleared; the Save action was enabled whenever `dirty` was true. |
| Leaving with edits | `UnsavedChangesGuard` intercepts back navigation; opening another document from the browser pushes a new editor and leaves the previous one (with its ViewModel) on the stack. |

## Confirmed risks

- **R1 - overlapping saves and stale results.** `save()` starts a new coroutine per tap
  and the Save action stays enabled while one runs, so two saves can open two output
  streams on the same document at once; and a save that finishes after the user edited
  again cleared `dirty`, hiding unsaved edits. EPUB cleared *its entire edit map* on
  success, discarding edits made while the save ran. Evidence:
  `WordEditorViewModel.kt:720` (and the same shape in
  `CsvEditorViewModel.kt:176`, `ExcelEditorViewModel.kt:245`, `EpubEditorViewModel.kt:123`,
  `PptEditorViewModel.kt:625`), `EditorChrome.kt:103` (`enabled = dirty`).
- **R2 - unverified replacement.** The destination was replaced with bytes nothing had
  checked. A serializer defect or a truncated in-memory package would have overwritten
  the last valid copy with an unopenable file. Evidence: `DocumentIo.kt:76-91`.
- **R3 - local files truncated before the new content is written.** `File.writeBytes`
  truncates at open; the in-memory rollback only helps if the process survives (a kill
  or OOM mid-write left a truncated document). Evidence: `DocumentIo.kt:80`.
- **R4 - a second "Open with" discarded unsaved edits.** `MainActivity.kt:63-65` popped
  the editor off the stack (inclusive of the start destination) and replaced it, so a
  dirty editor's only copy of its edits was dropped without a warning.
- **R5 - two full POI documents per save.** `WordDocument.kt:169-170` read the document
  to resolve block indices and then parsed it again to edit it (Phase 3 Part H).
- **R6 - no visible saving state.** The chrome could not distinguish "saving" from
  "unsaved", and the Save action remained tappable during a save (Part G).

Working as intended before this phase: serialization always completed in memory before
the destination was opened; a failed write was never reported as success; failures were
classified into actionable text (Phase 2); back navigation warned about unsaved changes;
editors kept their document in memory after a failed save, so Export stayed available.

## Decisions

- **D1 - check the package before replacing the file.** `DocumentIo.verifyZipPackage`
  reads every entry of the serialized ZIP to its end and requires the end-of-central-
  directory record. `writeBytes(..., verify = …)` calls it *before* opening the
  destination; a rejection throws `DocumentNotWrittenException`, nothing is opened, and
  `saveError` reports "left unchanged, use Export" instead of a save. Word, Excel, EPUB
  and PPTX saves pass it. This is package integrity, not semantic validation: it catches
  truncated or unopenable output, not a document that means something different.
- **D2 - local files are replaced by rename.** `replaceFileAtomically` writes the new
  content to a temporary file in the same directory, flushes it to storage (`fd.sync`),
  and renames it over the document. A failure before the rename leaves the previous file
  untouched; no rollback copy is needed, so the save no longer holds a second full copy
  of a `file://` document in memory.
- **D3 - provider destinations keep the in-memory rollback**, because the platform has
  no atomic replace for `content://`. The read of the previous content is now logged when
  it fails (no rollback possible), rather than silently skipped, and the KDoc states the
  limitation instead of implying atomicity.
- **D4 - one save at a time, and only the written revision clears the flag.** Each
  editor has a `saving` flag: a second save while one runs is refused. On success the
  dirty flag is cleared only if the edit snapshots still equal the ones that were
  written, so edits made during the save stay unsaved (EPUB keeps them in its edit map
  instead of clearing it).
- **D5 - the chrome shows "Saving…" and disables Save while a save runs** (`saving`
  parameter on `EditorChrome` / `GridEditorScreen`), so a second tap cannot start an
  overlapping write and the state is not misreported.
- **D6 - Word parses the document once per save** (`readDocument` on the open document
  resolves block indices), removing the second full POI tree (R5).
- **D7 - a second "Open with" pushes an editor instead of replacing the stack.** The
  previous editor keeps its ViewModel and its unsaved edits; back returns to it. This
  supersedes the Phase 2 follow-up note in ADR 0002 that the new document should become
  the only editor on the stack — silently dropping recoverable edits is the worse
  outcome, and an expired editor left below is a recoverable state (it offers Retry and
  Back to files).
- **D8 - the save path is unchanged for exports.** Export writes to a URI the user just
  chose, which has no previous content to protect; it uses the same code path and the
  same check, and never clears the dirty flag of the source document.

## What this does not guarantee

- **Not atomic for `content://`.** A kill, OOM, or lost provider connection during a
  provider write can still leave a damaged document; the rollback only runs while the
  process lives. An atomic replace would need a provider-specific "replace document"
  call that the platform does not offer generically.
- **A kill during a local replace can leave a temporary file behind.** The document itself
  is either the previous or the new one (that is what the rename guarantees), but the
  abandoned `<name>.<random>.tmp` next to it is debris the user may have to delete.
- **Not a semantic check.** A package that passes `verifyZipPackage` can still have lost
  content; that is covered by the preservation suite and the limitations in
  `docs/docx-preservation.md`, not by this check.
- **No read-back of the stored file.** The check runs on the bytes before the write; it
  does not re-read what the provider stored. A provider that silently stores less than
  it was given (quota, sync conflict) is not detected.
- **No crash journal or autosave.** Edits still live only in memory until Save; process
  death loses them (Phase 4 work, out of scope here).
- **No concurrent-save test.** The one-save-at-a-time and stale-result rules live in the
  ViewModels, which this project's strict-JVM unit tests cannot instantiate; they are
  covered by code review and the manual device steps in `docs/docx-preservation.md`.

## Verification

- `tests/shared/DocumentIoTest.kt`: integrity check (complete, truncated, garbage,
  missing central directory), no-write-when-the-check-fails, rollback on write and on
  flush failures, atomic local replace (content replaced, no temporary left, untouched
  when the check fails), and the failure message.
- `tests/editors/word/WordPreservationTest.kt`: a save round trip that reopens the
  serialized document, edits that leave unrelated content intact through undo/redo, and
  a truncated package being refused.
- Manual device checklist: `docs/docx-preservation.md` §8, steps 19-23.
- CI — **observed**, green for `189729e1f979b052b7f6480848af136f39ce52e7`:
  [Android CI 39016588759](https://github.com/bharti1233/Hmx-docs/actions/runs/39016588759)
  (89 tests, 0 failures, 0 errors, 1 pre-existing skip; lint 0 errors, 49 warnings) and
  [Release build check 39016588775](https://github.com/bharti1233/Hmx-docs/actions/runs/39016588775)
  (`Kern-release-unsigned.apk` sha256
  `3f2a1aee33fcd34041d654b592740b1dee6bf15d17b65143fc78fe72057e6a6a`; the workflow proves it
  is unsigned and that R8 ran). No device run has been performed: the concurrency rules
  above are code-reviewed, not device-verified `[not-verified]`. Details in
  `docs/docx-preservation.md` §7.
