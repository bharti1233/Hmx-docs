package dev.kern.browser

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kern.shared.DocumentFormat
import dev.kern.shared.io.RecentDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The home screen's Recent list as the ViewModel presents it: entries come from the shared
 * store in store order, a refresh picks up whatever an editor recorded, search and the
 * delete action work on entries, and a stale entry is shown rather than dropped.
 *
 * The scan itself is not exercised here (no Documents/Downloads folders exist in this JVM);
 * these tests are about the recents plumbing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileBrowserViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel() = FileBrowserViewModel(app)

    private val providerDoc = Uri.parse("content://com.example.provider/document/42")

    @Test
    fun openedDocuments_appearInRecentOrder() {
        RecentDocuments.recordOpen(app, providerDoc, "report.docx", DocumentFormat.WORD, 10L)
        RecentDocuments.recordOpen(app, Uri.parse("content://com.example.provider/document/7"), "budget.xlsx", DocumentFormat.EXCEL, 20L)

        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)

        assertEquals(
            listOf("budget.xlsx", "report.docx"),
            vm.recentDocs.map { it.name },
        )
        assertEquals(DocumentFormat.EXCEL, vm.recentDocs.first().format)
        assertEquals(20L, vm.recentDocs.first().size)
    }

    @Test
    fun returningFromTheEditor_showsWhatItRecorded() {
        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)
        assertTrue("nothing opened yet", vm.recentDocs.isEmpty())

        // What an editor does once it has loaded the picked document…
        RecentDocuments.recordOpen(app, providerDoc, "report.docx", DocumentFormat.WORD, 10L)
        // …and what the screen does when the user comes back to it.
        vm.refresh(scanDocuments = false, scanDownloads = false)

        assertEquals("report.docx", vm.recentDocs.single().name)
        assertEquals(providerDoc.toString(), vm.recentDocs.single().path)
    }

    @Test
    fun theSearchQuery_filtersRecentEntries() {
        RecentDocuments.recordOpen(app, providerDoc, "report.docx", DocumentFormat.WORD, 10L)
        RecentDocuments.recordOpen(app, Uri.parse("content://com.example.provider/document/7"), "budget.xlsx", DocumentFormat.EXCEL, 20L)

        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)
        vm.updateQuery("budget")

        assertEquals(listOf("budget.xlsx"), vm.recentDocs.map { it.name })
    }

    @Test
    fun anEntryThatCannotBeReached_staysListed() {
        // A recorded document whose file has since vanished (or whose grant was revoked):
        // the row is what the user sees, and building the list must not touch the document.
        RecentDocuments.recordOpen(app, providerDoc, "report.docx", DocumentFormat.WORD, 10L)

        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)

        assertEquals(1, vm.recentDocs.size)
        assertTrue(vm.recentDocs.single().path.startsWith("content://"))
    }

    @Test
    fun removingAnEntry_dropsOnlyIt() {
        RecentDocuments.recordOpen(app, providerDoc, "report.docx", DocumentFormat.WORD, 10L)
        RecentDocuments.recordOpen(app, Uri.parse("content://com.example.provider/document/7"), "budget.xlsx", DocumentFormat.EXCEL, 20L)

        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)
        vm.removeRecent(providerDoc.toString())

        assertEquals(listOf("budget.xlsx"), vm.recentDocs.map { it.name })
    }

    @Test
    fun deletingAFile_forgetsItsRecentEntry() {
        val file = folder.newFile("gone.csv")
        file.writeText("a,b\n1,2\n")
        RecentDocuments.recordOpen(app, Uri.fromFile(file), "gone.csv", DocumentFormat.CSV, file.length())

        val vm = viewModel()
        vm.refresh(scanDocuments = false, scanDownloads = false)
        val entry = vm.recentDocs.single()

        assertTrue(vm.delete(entry))

        assertFalse("the file is gone from disk", file.exists())
        assertTrue("its recent entry is gone with it", vm.recentDocs.isEmpty())
        assertTrue(RecentDocuments.list(app).isEmpty())
    }
}
