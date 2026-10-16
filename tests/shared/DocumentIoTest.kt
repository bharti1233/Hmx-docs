package dev.kern.shared.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Unit tests for the decisions [DocumentIo] makes about document access and writes.
 *
 * The resolver is faked here, so these tests pin the *logic* — which grants are requested,
 * what is reported when nothing can be persisted, what a failed write restores, and what the
 * user is told — and deliberately say nothing about a real provider's behaviour. That needs a
 * device; see the manual checklist in docs/docx-preservation.md.
 */
class DocumentIoTest {

    /**
     * Test double for the resolver that mirrors the platform's rule
     * (`UriPermission#takePersistableModes`): a request naming a mode the provider did not
     * grant is refused as a whole and silently — it does not persist part of it.
     */
    private class FakeGrants(private val granted: Set<DocumentIo.UriAccess>) : DocumentIo.GrantOps {

        val attempts = mutableListOf<Set<DocumentIo.UriAccess>>()
        private val persisted = mutableSetOf<DocumentIo.UriAccess>()

        override fun takePersistable(uri: String, modes: Set<DocumentIo.UriAccess>) {
            attempts.add(modes)
            if (!granted.containsAll(modes)) return
            persisted.addAll(modes)
        }

        override fun persisted(uri: String): Set<DocumentIo.UriAccess> = persisted.toSet()
    }

    // ---- long-lived access ---------------------------------------------------

    @Test
    fun persistAccess_readOnlyGrant_keepsReadAccess() {
        // Regression: the old code asked for read+write in one call. On a read-only pick the
        // platform refuses the whole request, so no access was persisted at all and the
        // document became unreadable once the transient grant died (device restart).
        val grants = FakeGrants(setOf(DocumentIo.UriAccess.READ))
        val kept = DocumentIo.persistAccess(grants, URI)
        assertEquals(setOf(DocumentIo.UriAccess.READ), kept)
        assertEquals(
            "read is asked for on its own first, then read+write",
            listOf(
                setOf(DocumentIo.UriAccess.READ),
                setOf(DocumentIo.UriAccess.READ, DocumentIo.UriAccess.WRITE),
            ),
            grants.attempts,
        )
    }

    @Test
    fun persistAccess_readWriteGrant_keepsBothModes() {
        val grants = FakeGrants(setOf(DocumentIo.UriAccess.READ, DocumentIo.UriAccess.WRITE))
        assertEquals(
            setOf(DocumentIo.UriAccess.READ, DocumentIo.UriAccess.WRITE),
            DocumentIo.persistAccess(grants, URI),
        )
    }

    @Test
    fun persistAccess_noPersistableGrant_reportsNoAccess() {
        assertTrue(DocumentIo.persistAccess(FakeGrants(emptySet()), URI).isEmpty())
    }

    @Test
    fun persistAccess_missingGrantRecord_doesNotThrow() {
        // An "Open with" hand-off the provider does not offer as persistable makes the
        // resolver throw. That is expected and must not break opening the document.
        val grants = object : DocumentIo.GrantOps {
            override fun takePersistable(uri: String, modes: Set<DocumentIo.UriAccess>) {
                throw SecurityException("No permission grant found")
            }

            override fun persisted(uri: String): Set<DocumentIo.UriAccess> = emptySet()
        }
        assertTrue(DocumentIo.persistAccess(grants, URI).isEmpty())
    }

    // ---- safe writes ---------------------------------------------------------

    @Test
    fun writeGuarded_failedWrite_restoresThePreviousContent() {
        var content = PREVIOUS
        var attempt = 0
        try {
            DocumentIo.writeGuarded(REPLACEMENT, PREVIOUS) { written ->
                attempt++
                // The first write dies part-way through; the rollback (second write) is
                // what has to put the original content back intact.
                content = if (attempt == 1) written.copyOf(3) else written
                if (attempt == 1) throw IOException("provider went away")
            }
            fail("a failed write must reach the caller")
        } catch (reported: IOException) {
            assertEquals("provider went away", reported.message)
            assertEquals("the last valid copy is restored", PREVIOUS.decodeToString(), content.decodeToString())
        }
        assertEquals("the document is written once and rolled back once", 2, attempt)
    }

    @Test
    fun writeGuarded_successfulWrite_keepsTheReplacement() {
        var content = PREVIOUS
        DocumentIo.writeGuarded(REPLACEMENT, PREVIOUS) { content = it }
        assertEquals(REPLACEMENT.decodeToString(), content.decodeToString())
    }

    @Test
    fun writeGuarded_firstWriteToANewTarget_reportsTheFailure() {
        var attempt = 0
        try {
            DocumentIo.writeGuarded(REPLACEMENT, null) {
                attempt++
                throw IOException("no space left")
            }
            fail("a failed write must reach the caller")
        } catch (reported: IOException) {
            assertEquals("no space left", reported.message)
        }
        assertEquals("nothing to restore for a new target", 1, attempt)
    }

    // ---- what the user is told ----------------------------------------------

    @Test
    fun loadError_permissionDenial_tellsTheUserWhatToDoInsteadOfLeakingTheProvider() {
        val failure = SecurityException(
            "Permission Denial: reading com.android.providers.media.MediaDocumentsProvider URI " +
                "content://com.android.providers.media.documents/document/document:384825 from pid=14332, uid=10305",
        )
        val message = DocumentIo.loadError(failure, "Could not open the document.")
        assertFalse("provider and PID details must not reach the user", message.contains("providers.media"))
        assertFalse(message.contains("384825"))
        assertTrue("the message says what to do, was: $message", message.contains("Open it again"))
    }

    @Test
    fun loadError_missingFile_isExplainedWithoutThePath() {
        val failure = FileNotFoundException("/storage/emulated/0/Documents/report.docx: open failed: ENOENT")
        val message = DocumentIo.loadError(failure, "Could not open the document.")
        assertFalse("paths must not reach the user", message.contains("/storage"))
        assertTrue("was: $message", message.contains("no longer available"))
    }

    @Test
    fun loadError_withoutFilesAccess_pointsAtTheSetting() {
        val failure = FileNotFoundException("/storage/emulated/0/a.docx: open failed: EACCES (Permission denied)")
        val message = DocumentIo.loadError(failure, "Could not open the document.")
        assertTrue("was: $message", message.contains("Files access"))
    }

    @Test
    fun loadError_unknownFailure_usesTheCallersFallback() {
        assertEquals(
            "Could not open the EPUB.",
            DocumentIo.loadError(IOException("stream closed"), "Could not open the EPUB."),
        )
        assertEquals("a null failure falls back too", "Could not open.", DocumentIo.loadError(null, "Could not open."))
    }

    @Test
    fun saveError_readOnlyTarget_keepsTheExportHint() {
        val message = DocumentIo.saveError(SecurityException("write not permitted"), "Save failed.")
        assertTrue("was: $message", message.contains("Export"))
    }

    @Test
    fun saveError_unknownFailure_usesTheCallersFallback() {
        assertEquals("Save failed.", DocumentIo.saveError(IllegalStateException("stale block"), "Save failed."))
    }

    private companion object {
        const val URI = "content://com.android.providers.media.documents/document/document:1"
        val PREVIOUS = "previous document content".toByteArray()
        val REPLACEMENT = "replacement document content".toByteArray()
    }
}
