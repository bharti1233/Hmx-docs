package dev.kern.editors.csv

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kern.shared.DocumentFormat
import dev.kern.shared.io.RecentDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The CSV editor's part in the Recent list: a document is recorded when it has loaded,
 * not when it is tapped; a load that fails leaves nothing behind; a successful save
 * refreshes the entry, a failed one changes nothing.
 *
 * Robolectric runs the editor's coroutines against the real main looper; [await] drives it
 * while the file I/O finishes on another thread, the same way the app does on a device.
 * Local file:// URIs are used so the JVM needs no content provider.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CsvEditorViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel() = CsvEditorViewModel(app)

    private fun csvFile(name: String, content: String): Uri {
        val file = folder.newFile(name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    /** Idles the main looper until [condition] holds, then once more to settle the callbacks. */
    private fun await(failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(failure)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitLoaded(vm: CsvEditorViewModel) = await("the document did not finish loading") { !vm.loading }

    @Test
    fun openingADocument_recordsItInRecent() {
        val uri = csvFile("budget.csv", "a,b\n1,2\n")
        val vm = viewModel()

        vm.start(uri.toString())
        awaitLoaded(vm)

        assertNull(vm.error)
        val entry = RecentDocuments.list(app).single()
        assertEquals(uri.path, entry.identity)
        assertEquals("budget.csv", entry.name)
        assertEquals(DocumentFormat.CSV, entry.format)
        assertEquals(8L, entry.size)
        assertTrue(entry.modified > 0L)
    }

    @Test
    fun aDocumentThatFailsToLoad_isNotRecorded() {
        val missing = Uri.fromFile(File(folder.root, "missing.csv"))
        val vm = viewModel()

        vm.start(missing.toString())
        awaitLoaded(vm)

        assertTrue("the editor reports the failure", vm.error != null)
        assertTrue("a failed open leaves no Recent entry", RecentDocuments.list(app).isEmpty())
    }

    @Test
    fun twoDocuments_keepDistinctEntries_andReopeningMovesToTheTop() {
        val first = csvFile("a.csv", "a\n1\n")
        val second = csvFile("b.csv", "b\n2\n")

        val firstEditor = viewModel()
        firstEditor.start(first.toString())
        awaitLoaded(firstEditor)
        val secondEditor = viewModel()
        secondEditor.start(second.toString())
        awaitLoaded(secondEditor)

        assertEquals(listOf(second.path, first.path), RecentDocuments.list(app).map { it.identity })

        val reopened = viewModel()
        reopened.start(first.toString())
        awaitLoaded(reopened)

        val entries = RecentDocuments.list(app)
        assertEquals("no duplicate for the reopened document", 2, entries.size)
        assertEquals(listOf(first.path, second.path), entries.map { it.identity })
    }

    @Test
    fun aSuccessfulSave_refreshesTheEntry() {
        val uri = csvFile("budget.csv", "a,b\n1,2\n")
        val vm = viewModel()
        vm.start(uri.toString())
        awaitLoaded(vm)
        val before = RecentDocuments.list(app).single()

        vm.select(1, 1)
        vm.editSelected("222222")
        var ok: Boolean? = null
        vm.save { success, _ -> ok = success }
        await("the save did not finish") { ok != null }

        assertTrue("the save succeeded", ok == true)
        assertFalse("the editor is clean again", vm.dirty)
        val after = RecentDocuments.list(app).single()
        assertEquals(before.identity, after.identity)
        assertTrue("the entry knows the new size", after.size > before.size)
        assertTrue(after.modified >= before.modified)
    }

    @Test
    fun aFailedSave_leavesTheEntryAndTheDirtyFlagAlone() {
        // The document lives in its own directory so the directory can be removed out from
        // under the save; TemporaryFolder itself stays intact for the rule's cleanup.
        val directory = folder.newFolder("keep")
        val file = File(directory, "keep.csv").apply { writeText("a\n1\n") }
        val uri = Uri.fromFile(file)
        val vm = viewModel()
        vm.start(uri.toString())
        awaitLoaded(vm)
        val before = RecentDocuments.list(app).single()

        vm.select(0, 0)
        vm.editSelected("changed")
        // The document cannot be replaced any more: its directory is gone, so the write
        // fails the way a revoked provider grant would, without touching a good file.
        assertTrue(directory.deleteRecursively())

        var ok: Boolean? = null
        vm.save { success, _ -> ok = success }
        await("the save did not finish") { ok != null }

        assertFalse("the save is reported as failed", ok!!)
        assertTrue("the edit is still unsaved", vm.dirty)
        assertEquals(
            "failed saves do not refresh metadata",
            listOf(before),
            RecentDocuments.list(app),
        )
    }
}
