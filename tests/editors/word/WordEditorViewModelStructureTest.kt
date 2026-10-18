package dev.kern.editors.word

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.util.Base64

/**
 * The editor's structural actions as the ViewModel drives them: a paragraph split by Enter, a
 * paragraph inserted, merged or deleted, a picture placed or removed, and the undo/redo history
 * over those changes. Every test goes through the ViewModel and then reads the *saved file* back,
 * so what is asserted is what the user gets on disk, not only the in-memory model.
 *
 * Robolectric runs the editor's coroutines against the real main looper; [await] drives it while
 * the file I/O finishes on another thread, the same way the app does on a device. Local file://
 * URIs are used so the JVM needs no content provider.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WordEditorViewModelStructureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val png = Base64.getDecoder().decode(PNG_1X1)

    private val png2 = Base64.getDecoder().decode(PNG_1X2)

    private fun viewModel() = WordEditorViewModel(app)

    private fun docx(build: (XWPFDocument) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            build(doc)
            doc.write(out)
        }
        return out.toByteArray()
    }

    private fun docxFile(name: String, bytes: ByteArray): Uri {
        val file = folder.newFile(name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun twoParagraphs(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("First line")
        doc.createParagraph().createRun().setText("Second line")
    }

    private fun addPicture(run: XWPFRun, name: String, bytes: ByteArray) {
        run.addPicture(
            ByteArrayInputStream(bytes),
            Document.PICTURE_TYPE_PNG,
            name,
            Units.toEMU(20.0),
            Units.toEMU(20.0),
        )
    }

    /** Two pictures that differ in size, so one removal can be told from the other. */
    private fun pictureParagraph(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("Caption")
            addPicture(p.createRun(), "first.png", png)
            addPicture(p.createRun(), "second.png", png2)
        }
    }

    /** A table whose first row spans two columns and whose first cell is merged downwards. */
    private fun mergedTable(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("Intro")
        val t = doc.createTable(2, 3)
        t.getRow(0).getCell(0).setText("Spanning")
        t.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(2))
        t.getRow(0).removeCell(1)
        t.getRow(1).getCell(0).setText("Lower")
        t.getRow(1).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART)
        t.getRow(1).getCell(1).setText("Plain")
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

    private fun open(vm: WordEditorViewModel, bytes: ByteArray, name: String = "note.docx"): Uri {
        val uri = docxFile(name, bytes)
        vm.start(uri.toString())
        await("the document did not finish loading") { !vm.loading }
        assertNull("the document opened", vm.error)
        return uri
    }

    private fun save(vm: WordEditorViewModel) {
        var answered = false
        vm.save { _, _ -> answered = true }
        await("the save did not finish") { answered && !vm.saving }
    }

    private fun saved(uri: Uri): ByteArray = File(uri.path!!).readBytes()

    private fun paragraphTexts(uri: Uri): List<String> =
        XWPFDocument(ByteArrayInputStream(saved(uri))).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFParagraph>().map { it.text ?: "" }
        }

    private fun documentXml(uri: Uri): String =
        XWPFDocument(ByteArrayInputStream(saved(uri))).use { doc -> doc.document.xmlText() }

    /** How many pictures the body references: a removed one leaves its unused part behind. */
    private fun pictureCount(uri: Uri): Int =
        XWPFDocument(ByteArrayInputStream(saved(uri))).use { doc ->
            doc.paragraphs.sumOf { p -> p.runs.sumOf { run -> run.embeddedPictures.size } }
        }

    private fun blockTexts(vm: WordEditorViewModel): List<String> =
        vm.blocks.filterIsInstance<WordDocument.ParagraphBlock>().map { it.text }

    // ---- Enter: the paragraph is split where the caret is ---------------------

    @Test
    fun enterSplitsTheParagraphAndTheSavedFileHasBothParts() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())

        vm.setFocus(0)
        vm.splitFocusedParagraph(offset = 5)
        await("the split did not finish") { !vm.busy }

        assertEquals(listOf("First", " line", "Second line"), blockTexts(vm))
        assertEquals("the caret waits in the new paragraph", 1, vm.focusedIndex)

        save(vm)
        assertEquals(listOf("First", " line", "Second line"), paragraphTexts(uri))
    }

    @Test
    fun typingThenEnterKeepsTheTypedTextInTheFirstHalf() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())

        vm.setFocus(0)
        vm.onValueChange(0, TextFieldValue("First line typed"))
        vm.splitFocusedParagraph(offset = 11)
        await("the split did not finish") { !vm.busy }

        save(vm)
        // Offset 11 is the space after "First line": the split happens exactly there.
        assertEquals(listOf("First line ", "typed", "Second line"), paragraphTexts(uri))
    }

    @Test
    fun aSplitIsUndoneInOneStepAndRedoPutsItBack() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())
        vm.setFocus(0)
        vm.splitFocusedParagraph(offset = 5)
        await("the split did not finish") { !vm.busy }
        assertTrue("the split can be undone", vm.canUndo)

        vm.undo()
        assertEquals("the paragraph is one again", listOf("First line", "Second line"), blockTexts(vm))
        assertTrue("the undone change can be redone", vm.canRedo)

        vm.redo()
        assertEquals(listOf("First", " line", "Second line"), blockTexts(vm))

        // The state the redo produced is what a save writes: history and model agree.
        save(vm)
        assertEquals(listOf("First", " line", "Second line"), paragraphTexts(uri))
    }

    /**
     * The bug this guards: after an undo over both a formatting change and a structural change,
     * the pending formatting write used to survive and be saved back into the document.
     */
    @Test
    fun undoingEverythingLeavesNothingOfTheUndoneEditsInTheFile() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())
        vm.setFocus(0)
        vm.setAlignment(WordDocument.Align.CENTER)
        assertEquals(WordDocument.Align.CENTER, vm.paraProps[0]?.align)

        vm.splitFocusedParagraph(offset = 5)
        await("the split did not finish") { !vm.busy }
        save(vm)
        assertTrue("the centred paragraph is written with the split", documentXml(uri).contains("w:jc"))

        vm.undo()
        assertEquals("the split is undone", 2, vm.blocks.size)
        vm.undo()
        assertEquals("the alignment is undone too", WordDocument.Align.START, vm.paraProps[0]?.align)
        assertFalse("there is nothing left to undo", vm.canUndo)

        save(vm)
        assertFalse("nothing of the undone edits comes back", documentXml(uri).contains("w:jc"))
        assertEquals(listOf("First line", "Second line"), paragraphTexts(uri))
    }

    // ---- insert, merge, delete -----------------------------------------------

    @Test
    fun insertingAParagraphPutsAnEmptyOneAfterTheCaret() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())

        vm.setFocus(0)
        vm.insertParagraphAfterFocused()
        await("the paragraph was not inserted") { !vm.busy }

        assertEquals(listOf("First line", "", "Second line"), blockTexts(vm))
        assertEquals("the caret waits in the new paragraph", 1, vm.focusedIndex)
        save(vm)
        assertEquals(listOf("First line", "", "Second line"), paragraphTexts(uri))
    }

    @Test
    fun mergingJoinsTheParagraphToTheOneBeforeItInTheSavedFile() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())

        assertFalse("a paragraph with nothing before it is not merged", vm.canMergeWithPrevious())
        vm.setFocus(1)
        vm.mergeFocusedWithPrevious()
        await("the merge did not finish") { !vm.busy }

        assertEquals(listOf("First lineSecond line"), blockTexts(vm))
        save(vm)
        assertEquals(listOf("First lineSecond line"), paragraphTexts(uri))
    }

    @Test
    fun deletingAParagraphTakesItOutOfTheSavedFile() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())

        vm.setFocus(1)
        vm.deleteFocusedBlock()
        await("the delete did not finish") { !vm.busy }

        assertEquals(listOf("First line"), blockTexts(vm))
        save(vm)
        assertEquals(listOf("First line"), paragraphTexts(uri))
    }

    @Test
    fun theLastParagraphOfADocumentCannotBeDeleted() {
        val vm = viewModel()
        open(vm, docx { doc -> doc.createParagraph().createRun().setText("Only paragraph") })
        vm.setFocus(0)

        assertFalse(vm.canDeleteFocused())
        vm.deleteFocusedBlock()
        assertEquals("the document still has its paragraph", listOf("Only paragraph"), blockTexts(vm))
    }

    // ---- pictures ------------------------------------------------------------

    @Test
    fun deletingAPictureRemovesItFromTheSavedFile() {
        val vm = viewModel()
        val uri = open(vm, pictureParagraph())
        val index = vm.blocks.indexOfFirst { it is WordDocument.ImageBlock }
        val ordinal = (vm.blocks[index] as WordDocument.ImageBlock).ordinal

        vm.deleteImage(index, ordinal)
        await("the picture was not removed") { !vm.busy }

        save(vm)
        assertEquals("one of the two pictures is left", 1, pictureCount(uri))
        assertEquals("the caption is untouched", listOf("Caption"), paragraphTexts(uri))
    }

    @Test
    fun insertingAPictureFromTheDevicePlacesItWhereTheCaretIs() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())
        val picture = docxFile("shot.png", png)

        vm.setFocus(0)
        vm.insertImage(picture)
        await("the picture attempt did not finish") { !vm.busy }

        assertTrue(
            "the picture was not placed (notice: ${vm.notice}, blocks: ${vm.blocks.map { it::class.simpleName }})",
            vm.blocks.any { it is WordDocument.ImageBlock },
        )
        save(vm)
        assertEquals(1, pictureCount(uri))
        assertEquals(
            "the picture has a paragraph of its own after the caret's block",
            listOf("First line", "", "Second line"),
            paragraphTexts(uri),
        )
        XWPFDocument(ByteArrayInputStream(saved(uri))).use { doc ->
            val inUse = doc.paragraphs.flatMap { p -> p.runs.flatMap { run -> run.embeddedPictures } }
            assertArrayEquals("the stored bytes are the picked file", png, inUse.single().pictureData.data)
        }
    }

    @Test
    fun anInsertedPictureCanBeUndone() {
        val vm = viewModel()
        val uri = open(vm, twoParagraphs())
        val picture = docxFile("shot.png", png)
        vm.setFocus(0)
        vm.insertImage(picture)
        await("the picture attempt did not finish") { !vm.busy }
        assertTrue(
            "the picture was not placed (notice: ${vm.notice}, blocks: ${vm.blocks.map { it::class.simpleName }})",
            vm.blocks.any { it is WordDocument.ImageBlock },
        )
        assertTrue("placing a picture is one undo step", vm.canUndo)

        vm.undo()
        assertEquals("the picture is out of the model again", 0, vm.blocks.count { it is WordDocument.ImageBlock })

        save(vm)
        assertEquals("and out of the saved file", 0, pictureCount(uri))
        assertEquals(listOf("First line", "Second line"), paragraphTexts(uri))
    }

    @Test
    fun aFileThatIsNotAPictureIsRefusedWithAMessage() {
        val vm = viewModel()
        open(vm, twoParagraphs())
        val notAPicture = docxFile("notes.txt", "just text".toByteArray())

        vm.insertImage(notAPicture)
        await("the attempt did not finish") { !vm.busy }

        assertTrue("the editor says why (blocks: ${vm.blocks.map { it::class.simpleName }})", vm.notice != null)
        assertEquals("and nothing was added", 2, vm.blocks.size)
    }

    // ---- tables with merged cells --------------------------------------------

    @Test
    fun aMergedTableTakesTextEditsButKeepsItsRowsAndColumns() {
        val vm = viewModel()
        val uri = open(vm, mergedTable())
        val index = vm.blocks.indexOfFirst { it is WordDocument.TableBlock }
        assertTrue("the table is read", index >= 0)

        vm.editTableCell(index, row = 1, col = 1, text = "Plain edited")
        vm.openTableEditor(index)
        assertEquals("the cell editor opens", index, vm.activeTableBlockIndex)
        assertFalse("but its rows and columns are not editable", vm.activeTableStructureEditable())

        val rowsBefore = vm.activeTableData.toList()
        vm.insertTableRow(0)
        vm.deleteTableColumn(0)
        assertEquals("no structural change went through", rowsBefore, vm.activeTableData)
        assertTrue("and the editor says why", vm.notice != null)

        save(vm)
        assertTrue("the span is still in the file", documentXml(uri).contains("gridSpan"))
        val rows = (WordDocument.read(saved(uri)).blocks[index] as WordDocument.TableBlock).rows
        assertTrue("the text edit is saved", rows.any { row -> row.contains("Plain edited") })
    }

    private companion object {
        /** A valid 1x1 PNG (70 bytes, RGBA, non-interlaced), pinned so fixtures need no tooling. */
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        /** A second valid PNG, 1x2 red, so two pictures in one document can be told apart. */
        const val PNG_1X2 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAIAAAAW4yFwAAAADklEQVR4nGP4z8DAAMQACf4B/4PiLjgAAAAASUVORK5CYII="
    }
}
