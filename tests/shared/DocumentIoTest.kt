package dev.kern.shared.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for the decisions [DocumentIo] makes about document access and writes.
 *
 * The resolver is faked here, so these tests pin the *logic* — which grants are requested,
 * what is reported when nothing can be persisted, what a failed write restores, what the
 * user is told, and which failures are worth another read — and deliberately say nothing
 * about a real provider's behaviour. That needs a device; see the manual checklist in
 * docs/docx-preservation.md.
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
    fun loadFailure_permissionDenialWithoutAGrant_saysAccessExpired() {
        val failure = SecurityException(
            "Permission Denial: reading com.android.providers.media.MediaDocumentsProvider URI " +
                "content://com.android.providers.media.documents/document/document:384825 from pid=14332, uid=10305",
        )
        val problem = DocumentIo.loadFailure(failure, "Could not open the document.")
        assertTrue("the message says what to do, was: ${problem.message}", problem.message.contains("Open it again"))
        assertTrue("re-picking the file is the way out", problem.canRetry)
        assertNoProviderDetail(problem.message)
    }

    @Test
    fun loadFailure_denialWhileAGrantIsHeld_doesNotBlameAnExpiredGrant() {
        // Regression: every SecurityException was reported as "access has expired", even while
        // the app still held a persisted READ grant. That told the user to re-pick a file whose
        // permission was never the problem.
        val problem = DocumentIo.loadFailure(
            SecurityException("Permission Denial: reading com.android.providers.media.documents"),
            "Could not open the document.",
            readGrantHeld = true,
        )
        assertFalse("was: ${problem.message}", problem.message.contains("expired"))
        assertTrue("another read is worth offering", problem.canRetry)
        assertNoProviderDetail(problem.message)
    }

    @Test
    fun loadFailure_missingFile_isTerminalAndSaysSo() {
        val problem = DocumentIo.loadFailure(
            FileNotFoundException("/storage/emulated/0/Documents/report.docx: open failed: ENOENT"),
            "Could not open the document.",
        )
        assertTrue("was: ${problem.message}", problem.message.contains("no longer available"))
        assertFalse("a second read cannot bring a deleted file back", problem.canRetry)
        assertFalse("paths must not reach the user", problem.message.contains("/storage"))
    }

    @Test
    fun loadFailure_revokedReadReportedAsNotFound_isStillAnExpiredGrant() {
        // Providers answer a revoked read with a denial or with "not found", depending on the
        // provider. The second one must not be reported as a moved or deleted document.
        val problem = DocumentIo.loadFailure(
            FileNotFoundException("/Documents/report.docx: open failed: Permission denied"),
            "Could not open the document.",
        )
        assertTrue("was: ${problem.message}", problem.message.contains("Open it again"))
        assertTrue(problem.canRetry)
    }

    @Test
    fun loadFailure_providerFailure_isRetryableAndNotBlamedOnTheGrant() {
        // The resolver reports a provider that died, stalled or held no stream this way (the
        // second message is this app's own). The document itself may be perfectly fine.
        for (failure in listOf(
            FileNotFoundException("Unable to open content: content://com.android.providers.media.documents/document/document:1"),
            FileNotFoundException(DocumentIo.NO_STREAM),
        )) {
            val problem = DocumentIo.loadFailure(failure, "Could not open the document.")
            assertFalse("was: ${problem.message}", problem.message.contains("expired"))
            assertFalse("was: ${problem.message}", problem.message.contains("no longer available"))
            assertTrue("a provider that came back can serve the read", problem.canRetry)
        }
    }

    @Test
    fun loadFailure_withoutFilesAccess_pointsAtTheSetting() {
        val problem = DocumentIo.loadFailure(
            FileNotFoundException("/storage/emulated/0/a.docx: open failed: EACCES (Permission denied)"),
            "Could not open the document.",
        )
        assertTrue("was: ${problem.message}", problem.message.contains("Files access"))
        assertTrue("turning access on and trying again is the fix", problem.canRetry)
    }

    @Test
    fun loadFailure_unclassifiedFailure_usesTheCallersFallback() {
        assertEquals(
            "Could not open the EPUB.",
            DocumentIo.loadFailure(IllegalStateException("stale block"), "Could not open the EPUB.").message,
        )
        assertEquals(
            "a null failure falls back too",
            "Could not open.",
            DocumentIo.loadFailure(null, "Could not open.").message,
        )
    }

    private fun assertNoProviderDetail(message: String) {
        assertFalse("provider details must not reach the user", message.contains("providers.media"))
        assertFalse(message.contains("384825"))
        assertFalse(message.contains("content://"))
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

    @Test
    fun saveError_refusedReplacement_saysTheFileIsUnchanged() {
        val message = DocumentIo.saveError(
            DocumentIo.DocumentNotWrittenException("Serialized document did not pass its integrity check"),
            "Could not save the document.",
        )
        assertTrue("was: $message", message.contains("left unchanged"))
        assertTrue("was: $message", message.contains("Export"))
    }

    // ---- save integrity ------------------------------------------------------

    @Test
    fun verifyZipPackage_acceptsACompletePackage() {
        val package_ = zip(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to "<w:document/>".toByteArray(),
        )
        assertTrue("a complete package must pass", DocumentIo.verifyZipPackage(package_))
    }

    @Test
    fun verifyZipPackage_rejectsATruncatedPackage() {
        // What an interrupted save would leave behind. Writing these bytes over the
        // document is exactly the data loss the check exists to stop.
        val complete = zip("word/document.xml" to "x".repeat(4096).toByteArray())
        assertFalse(DocumentIo.verifyZipPackage(complete.copyOf(complete.size / 2)))
    }

    @Test
    fun verifyZipPackage_rejectsAPackageWithoutItsCentralDirectory() {
        // Every entry is intact, but the end record was never written: readers such as
        // Word reject this, so it must not replace a document either.
        val complete = zip("word/document.xml" to "<w:document/>".toByteArray())
        val centralDirectory = indexOf(
            complete,
            byteArrayOf(0x50.toByte(), 0x4b.toByte(), 0x01.toByte(), 0x02.toByte()),
        )
        assertTrue("the fixture has a central directory to cut off", centralDirectory > 0)
        assertFalse(DocumentIo.verifyZipPackage(complete.copyOf(centralDirectory)))
    }

    @Test
    fun verifyZipPackage_rejectsGarbageAndAnEmptyPackage() {
        assertFalse("no bytes at all", DocumentIo.verifyZipPackage(ByteArray(0)))
        assertFalse("not a package", DocumentIo.verifyZipPackage("not a document".toByteArray()))
        assertFalse("a zip with no entries", DocumentIo.verifyZipPackage(zip()))
    }

    @Test
    fun writeChecked_failedCheck_neverOpensTheDestination() {
        // Regression: the destination used to be replaced with whatever the serializer
        // produced, so a bad serialization destroyed the last valid copy.
        var writes = 0
        try {
            DocumentIo.writeChecked(REPLACEMENT, PREVIOUS, verify = { false }) { writes++ }
            fail("a refused replacement must reach the caller")
        } catch (refused: IOException) {
            assertTrue(
                "refused before anything was written, was: ${refused.javaClass.simpleName}",
                refused is DocumentIo.DocumentNotWrittenException,
            )
        }
        assertEquals("the destination is never touched", 0, writes)
    }

    @Test
    fun writeChecked_acceptedCheck_writesTheReplacement() {
        var content = PREVIOUS
        DocumentIo.writeChecked(REPLACEMENT, PREVIOUS, verify = { true }) { content = it }
        assertEquals(REPLACEMENT.decodeToString(), content.decodeToString())
    }

    @Test
    fun writeChecked_flushFailure_restoresThePreviousContent() {
        // The bytes arrive, then the stream reports the failure on flush/close. The
        // rollback still runs, and the failure still reaches the caller.
        var content = PREVIOUS
        var attempt = 0
        try {
            DocumentIo.writeChecked(REPLACEMENT, PREVIOUS, verify = { true }) {
                attempt++
                content = it
                if (attempt == 1) throw IOException("flush failed")
            }
            fail("a failed write must reach the caller")
        } catch (reported: IOException) {
            assertEquals("flush failed", reported.message)
            assertEquals("the last valid copy is restored", PREVIOUS.decodeToString(), content.decodeToString())
        }
        assertEquals("written once, rolled back once", 2, attempt)
    }

    @Test
    fun replaceFileAtomically_replacesTheContentAndLeavesNoTemporaryBehind() {
        val directory = Files.createTempDirectory("kern-save").toFile()
        val document = File(directory, "report.docx")
        document.writeBytes(PREVIOUS)

        DocumentIo.replaceFileAtomically(document, REPLACEMENT)

        assertEquals(REPLACEMENT.decodeToString(), document.readText())
        assertEquals(
            "the temporary is renamed onto the document, not left next to it",
            listOf("report.docx"),
            directory.list()?.sorted(),
        )
    }

    @Test
    fun replaceFileAtomically_refusedReplacement_leavesTheDocumentUntouched() {
        val directory = Files.createTempDirectory("kern-save").toFile()
        val document = File(directory, "report.docx")
        document.writeBytes(PREVIOUS)

        try {
            DocumentIo.replaceFileAtomically(document, REPLACEMENT, verify = { false })
            fail("a refused replacement must reach the caller")
        } catch (_: DocumentIo.DocumentNotWrittenException) {
            // expected
        }

        assertEquals("the previous content is still there", PREVIOUS.decodeToString(), document.readText())
        assertEquals(listOf("report.docx"), directory.list()?.sorted())
    }

    /** A zip package built here, so the check is tested against real bytes, not a mock. */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private companion object {
        const val URI = "content://com.android.providers.media.documents/document/document:1"
        val PREVIOUS = "previous document content".toByteArray()
        val REPLACEMENT = "replacement document content".toByteArray()
    }
}
