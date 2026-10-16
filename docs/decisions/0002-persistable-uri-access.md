# 0002 - Persistable document access (`content://`) and the picker contract

- **Status:** DECIDED and implemented (Phase 2, 2026-10-16)
- **Date opened:** 2026-10-16
- **Blocks:** nothing; edit-time access is independent of Phase 3's draft/journal work
- **Does not block:** anything in the roadmap. It is a fix for a pre-existing defect,
  not a new capability.

## Context

Opening a document produced a raw platform error, reported once as:

> Permission Denial: reading com.android.providers.media.MediaDocumentsProvider URI
> content://com.android.providers.media.documents/document/document:384825 from
> pid=14332, uid=10305 requires that you obtain access using ACTION_OPEN_DOCUMENT or
> related APIs

(The IDs and document numbers vary per device; the shape is what matters.) The message
reached the user because every editor surfaced `Throwable.message` verbatim. The defect
pre-dates this fork's Phase 1 work `[code: git history, no app code changed in Phase 1]`.

## Facts that constrain the decision

1. **A URI grant is transient by default.** A picker/`ACTION_VIEW` grant lasts for the
   receiving activity's lifetime and "until the device restarts" unless it is persisted
   explicitly `[docs: developer.android.com/training/data-storage/shared/documents-files]`.
2. **Persisting is all-or-nothing and silent on refusal.** AOSP's
   `UriPermission#takePersistableModes(int)` returns `false` when *any* requested mode is
   not in `persistableModeFlags`; it logs and throws nothing. `persistableModeFlags` is
   populated only for grants that arrived with
   `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` `[aosp:
   frameworks/base/core/java/android/content/UriPermission.java]`.
3. **The canonical app-side pattern is masking.** The documented pattern is to pass
   `intent.getFlags() & (FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION)`
   to `takePersistableUriPermission` `[docs: same page]`.
4. **`ActivityResultContracts.OpenDocument()` cannot supply those flags.**
   `activity-1.9.3` adds no grant flags to the request and drops the result Intent, so
   the contract's callback (which delivers only the `Uri`) cannot see what was granted
   `[verified: bytecode in androidx.activity 1.9.3; both delegated
   `ContentResolver#takePersistableUriPermission(uri, flags)` calls in that version
   already fail this way for read-only results]`.
5. **What the app did before.** `DocumentIo.tryPersist` asked for
   `READ | WRITE` in one call, inside `runCatching` — so on any grant that did not carry
   write, the platform refused the whole request, nothing was persisted, and the return
   value was ignored. `IntentHandler.resolve` never persisted anything. The navigation
   route (and therefore the restored state) keeps the encoded `content://` URI, so after
   the process or device restarted the app held a URI it no longer had access to
   `[code: HEAD src/shared/io/DocumentIo.kt, src/browser/IntentHandler.kt]`.
6. **All-files access does not help.** `MANAGE_EXTERNAL_STORAGE` covers filesystem and
   media access; an app still needs a URI grant to read another app's provider document
   through `ContentResolver` `[aosp: the permission does not bypass
   `DocumentsProvider`-issued grants]`. Widening storage permission would also require a
   Play policy declaration (see 0001).

## Options

### Option A - keep `OpenDocument()` and request the modes app-side with a read-back (chosen)

Ask for `READ` alone, then `READ | WRITE`, and read `persistedUriPermissions` back to
learn what actually stuck. No picker-contract change, no new dependency, and the app
still opens the document with whatever transient access it holds.

- Consequences: read-only picks now keep usable access across restarts; write access is
  persisted only when the provider offers it; a provider that offers nothing persistable
  still loses access at reboot, and the app's answer to that is the error message plus
  "Back to files" rather than a raw platform string.

### Option B - a custom ActivityResultContract that surfaces the result Intent flags

Would let the app mask exactly what was granted. Rejected: it is the same amount of
"ask and read back" logic, plus a custom contract to maintain, and it changes the
document-opening contract for all six editors. Its only advantage (not asking for a mode
that was not offered) is already handled by the two-step request.

### Option C - ignore persistence, re-pick the document when access is gone

Rejected: a document the user opened once should reopen; making the user find it again
on every restart is worse than the defect it works around, and it would also lose the
read-only case that *can* be persisted today.

### Option D - widen storage access instead

Rejected outright: `MANAGE_EXTERNAL_STORAGE` does not grant authority over another
provider's `content://` document (fact 6), it is already present for the in-app file
browser, and a security-relevant permission must not be added as a speculative fix.

## Decision

**Option A**, implemented as:

- `DocumentIo.persistAccess(context, uri)` / `persistAccess(ops, uri)` — request
  `{READ}`, then `{READ, WRITE}`, then read the persisted grants back and return them.
  The resolver operations sit behind `GrantOps` so the platform rule can be simulated
  in a unit test (`tests/shared/DocumentIoTest.kt`).
- Called at every point a document URI enters the app: the SAF picker
  (`FileBrowserScreen.kt:165`), an "Open with"/`ACTION_VIEW` hand-off
  (`IntentHandler.kt:33`), and each editor view model's `start()` (so a re-created route
  re-asks while the transient grant still exists).
- Failures are never user-visible as platform text: `loadError` maps an expired access
  to "Access to this file has expired. Open it again to continue." and the editor offers
  "Back to files" (`EditorChrome.kt:112-122`, `GridEditorScreen.kt:201-213`).
- No broad storage permission was added, and `ACTION_OPEN_DOCUMENT` remains the only
  picker used.

## Consequences

- A document opened read-only stays readable after a device restart, and an editable
  document stays editable — provided the provider granted persistable access.
- When a provider offers none (some "Open with" hand-offs), the transient grant still
  dies at reboot; the user now gets an actionable message and a way back to the file
  list instead of a denial dump.
- Persisted grants accumulate in the system's per-app list and are never released. This
  is deliberate: releasing a grant for a document the user may reopen would recreate the
  original defect. The platform caps the list per app `[docs]`; if that ever becomes
  measurable, release-on-close is the upgrade path.
- The unit test proves the *decision logic* (which modes are asked for, in which order,
  and what is reported). It cannot prove what a real provider does — that is device
  checklist item 4 in `docs/docx-preservation.md` `[not-verified]`.
