package dev.kern.shared.io

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kern.shared.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Recent-documents store, run on the JVM with the app's SharedPreferences.
 *
 * These tests pin what makes an entry: its identity (a provider URI or a local path), the
 * metadata shown in the list, de-duplication, ordering, the cap, and reading back both the
 * current format and the old path-per-line one. They use URIs only as strings — no provider
 * or document is involved, which is exactly how the store should behave at the home screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecentDocumentsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val providerDoc = Uri.parse("content://com.example.provider/document/42")
    private val otherProviderDoc = Uri.parse("content://com.example.provider/document/43")
    private val localFile = Uri.fromFile(java.io.File("/storage/emulated/0/Documents/notes.docx"))

    private fun recentUris() = RecentDocuments.list(context).map { it.identity }

    @Test
    fun openingADocument_storesItWithItsMetadata() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 4096L)

        val entry = RecentDocuments.list(context).single()
        assertEquals("content://com.example.provider/document/42", entry.identity)
        assertEquals("report.docx", entry.name)
        assertEquals(DocumentFormat.WORD, entry.format)
        assertEquals(4096L, entry.size)
        assertTrue("a content:// identity is a provider document", entry.isProviderDocument)
        assertTrue("the entry carries a timestamp", entry.modified > 0L)
    }

    @Test
    fun openingALocalFile_keepsThePathAsItsIdentity() {
        RecentDocuments.recordOpen(context, localFile, "notes.docx", DocumentFormat.WORD, 10L)

        val entry = RecentDocuments.list(context).single()
        assertEquals("/storage/emulated/0/Documents/notes.docx", entry.identity)
        assertFalse(entry.isProviderDocument)
    }

    @Test
    fun reopeningTheSameDocument_movesItToTheTopWithoutDuplicating() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 1L)
        RecentDocuments.recordOpen(context, otherProviderDoc, "budget.xlsx", DocumentFormat.EXCEL, 2L)
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 3L)

        assertEquals(listOf(providerDoc.toString(), otherProviderDoc.toString()), recentUris())
        // The newest open wins, metadata included.
        assertEquals(3L, RecentDocuments.list(context).first().size)
    }

    @Test
    fun differentDocuments_keepDistinctEntries() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 1L)
        RecentDocuments.recordOpen(context, otherProviderDoc, "budget.xlsx", DocumentFormat.EXCEL, 2L)

        val entries = RecentDocuments.list(context)
        assertEquals(2, entries.size)
        assertEquals(providerDoc.toString(), entries[1].identity)
        assertEquals(otherProviderDoc.toString(), entries[0].identity)
        assertEquals(DocumentFormat.WORD, entries[1].format)
        assertEquals(DocumentFormat.EXCEL, entries[0].format)
    }

    @Test
    fun aSavedDocument_updatesOnlyItsOwnEntry() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 1L)
        RecentDocuments.recordOpen(context, otherProviderDoc, "budget.xlsx", DocumentFormat.EXCEL, 2L)
        val before = RecentDocuments.list(context).first { it.identity == providerDoc.toString() }

        RecentDocuments.noteSaved(context, providerDoc, 99L)

        val after = RecentDocuments.list(context)
        assertEquals(2, after.size)
        assertEquals(99L, after.first { it.identity == providerDoc.toString() }.size)
        assertTrue(after.first { it.identity == providerDoc.toString() }.modified >= before.modified)
        assertEquals(2L, after.first { it.identity == otherProviderDoc.toString() }.size)
    }

    @Test
    fun savingADocumentThatWasNeverOpened_changesNothing() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 1L)

        RecentDocuments.noteSaved(context, otherProviderDoc, 5L)

        assertEquals(listOf(providerDoc.toString()), recentUris())
    }

    @Test
    fun removingAnEntry_leavesTheOthers() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 1L)
        RecentDocuments.recordOpen(context, otherProviderDoc, "budget.xlsx", DocumentFormat.EXCEL, 2L)

        RecentDocuments.remove(context, providerDoc.toString())

        assertEquals(listOf(otherProviderDoc.toString()), recentUris())
    }

    @Test
    fun entriesAreStored_soTheySurviveBeingReadBack() {
        RecentDocuments.recordOpen(context, providerDoc, "report.docx", DocumentFormat.WORD, 4096L)

        // The entry is in the app's preferences, not in the process: a list read again sees
        // it with no earlier call in between.
        val raw = context.getSharedPreferences("kern_browser", Context.MODE_PRIVATE)
            .getString("recents", null)
        assertTrue("the store is written through", raw != null && raw.contains("report.docx"))
        assertEquals(1, RecentDocuments.list(context).size)
    }

    @Test
    fun oldPathPerLineEntries_areStillRead() {
        context.getSharedPreferences("kern_browser", Context.MODE_PRIVATE)
            .edit()
            .putString("recents", "/storage/emulated/0/Documents/a.docx\n/storage/emulated/0/Downloads/b.pdf")
            .apply()

        val entries = RecentDocuments.list(context)
        assertEquals(
            listOf("/storage/emulated/0/Documents/a.docx", "/storage/emulated/0/Downloads/b.pdf"),
            entries.map { it.identity },
        )
        assertEquals("a.docx", entries[0].name)
        assertEquals(DocumentFormat.WORD, entries[0].format)
        assertEquals(DocumentFormat.PDF, entries[1].format)
        assertEquals("an old entry has no metadata of its own", 0L, entries[0].size)
    }

    @Test
    fun unreadableStoredEntries_doNotCrashTheList() {
        context.getSharedPreferences("kern_browser", Context.MODE_PRIVATE)
            .edit()
            .putString("recents", "not json at all\n[{broken")
            .apply()

        assertEquals(emptyList<RecentDocument>(), RecentDocuments.list(context))
    }

    @Test
    fun theListIsCapped_newestFirst() {
        repeat(55) { i ->
            RecentDocuments.recordOpen(
                context,
                Uri.parse("content://com.example.provider/document/$i"),
                "doc$i.docx",
                DocumentFormat.WORD,
                i.toLong(),
            )
        }

        val entries = RecentDocuments.list(context)
        assertEquals(50, entries.size)
        assertEquals("content://com.example.provider/document/54", entries.first().identity)
        assertEquals("content://com.example.provider/document/5", entries.last().identity)
    }
}
