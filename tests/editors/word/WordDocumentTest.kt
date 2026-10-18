package dev.kern.editors.word

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WordDocumentTest {

    /** A Heading1 with a bold run, a plain body line, and a red-coloured line. */
    private fun sampleRichDocx(): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            doc.createParagraph().also { p ->
                p.style = "Heading1"
                p.createRun().also { r -> r.setText("Chapter"); r.isBold = true }
            }
            doc.createParagraph().createRun().setText("First body line.")
            doc.createParagraph().createRun().also { r -> r.setText("Coloured"); r.color = "FF0000" }
            doc.write(out)
        }
        return out.toByteArray()
    }

    /** A paragraph, then a 1x1 table, then a paragraph. */
    private fun tableDocx(): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("Before")
            doc.createTable().getRow(0).getCell(0).setText("Cell")
            doc.createParagraph().createRun().setText("After")
            doc.write(out)
        }
        return out.toByteArray()
    }

    private fun paragraphs(bytes: ByteArray): List<WordDocument.ParagraphBlock> =
        WordDocument.read(bytes).blocks.filterIsInstance<WordDocument.ParagraphBlock>()

    @Test
    fun read_returnsBlockTextAndCoarseKind() {
        val paras = paragraphs(sampleRichDocx())
        assertEquals("Chapter", paras[0].text)
        assertEquals(WordDocument.Kind.HEADING1, paras[0].props.kind)
        assertEquals("First body line.", paras[1].text)
        assertEquals(WordDocument.Kind.BODY, paras[1].props.kind)
    }

    @Test
    fun read_capturesRunFormatting() {
        val paras = paragraphs(sampleRichDocx())
        assertTrue("bold run captured", paras[0].runs.any { it.style.bold })
        assertEquals("FF0000", paras[2].runs.first().style.colorHex)
    }

    @Test
    fun applyEdits_replacesOnlyEditedParagraph() {
        val edited = WordDocument.applyEditsAndSerialize(
            sampleRichDocx(),
            mapOf(1 to listOf(WordDocument.Run("Edited body line."))),
        )
        val paras = paragraphs(edited)
        assertEquals("Edited body line.", paras[1].text)
        assertEquals("Chapter", paras[0].text)          // untouched
        assertEquals("Coloured", paras[2].text)          // untouched
    }

    @Test
    fun applyEdits_preservesFormattingOfUntouchedParagraphs() {
        val edited = WordDocument.applyEditsAndSerialize(
            sampleRichDocx(),
            mapOf(1 to listOf(WordDocument.Run("Edited body line."))),
        )
        val paras = paragraphs(edited)
        assertTrue("bold survives an edit elsewhere", paras[0].runs.any { it.style.bold })
        assertEquals("FF0000", paras[2].runs.first().style.colorHex)
    }

    @Test
    fun applyEdits_canApplyBoldToARun() {
        val edited = WordDocument.applyEditsAndSerialize(
            sampleRichDocx(),
            mapOf(1 to listOf(WordDocument.Run("Bolded", WordDocument.RunStyle(bold = true)))),
        )
        val p1 = paragraphs(edited)[1]
        assertEquals("Bolded", p1.text)
        assertTrue("re-read run is bold", p1.runs.all { it.style.bold })
    }

    @Test
    fun read_tableIsReadOnlyBlockAndIndexStaysStable() {
        val blocks = WordDocument.read(tableDocx()).blocks
        val tableIndex = blocks.indexOfFirst { it is WordDocument.TableBlock }
        assertTrue("a table block is present", tableIndex >= 0)
        assertEquals("Cell", (blocks[tableIndex] as WordDocument.TableBlock).rows.first().first())

        val afterIndex = blocks.indexOfLast {
            it is WordDocument.ParagraphBlock && (it as WordDocument.ParagraphBlock).text == "After"
        }
        assertTrue("paragraph after the table", afterIndex > tableIndex)

        // Editing the paragraph after the table (by its read-time index) round-trips
        // and leaves the table untouched.
        val edited = WordDocument.applyEditsAndSerialize(tableDocx(), mapOf(afterIndex to listOf(WordDocument.Run("After!"))))
        val re = WordDocument.read(edited).blocks
        assertEquals("After!", (re[afterIndex] as WordDocument.ParagraphBlock).text)
        assertEquals("Cell", (re.first { it is WordDocument.TableBlock } as WordDocument.TableBlock).rows.first().first())
    }

    @Test
    fun read_paragraphWithHyperlinkIsCapturedAndEditable() {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("See ")
            p.createHyperlinkRun("https://example.com").setText("example")
            doc.write(out)
        }
        val block = WordDocument.read(out.toByteArray()).blocks
            .filterIsInstance<WordDocument.ParagraphBlock>()
            .first()
        assertTrue("hyperlink paragraph is editable in 0.1.10+", block.editable)
        assertEquals("See example", block.text)
    }

    @Test
    fun read_emptyDocumentYieldsOneEditableParagraph() {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { it.write(out) }
        val blocks = WordDocument.read(out.toByteArray()).blocks
        assertEquals(1, blocks.size)
        val only = blocks.first() as WordDocument.ParagraphBlock
        assertEquals("", only.text)
        assertTrue(only.editable)
    }

    @Test
    fun read_runWithoutExplicitSizeHasNullSize() {
        // Regression: POI getFontSizeAsDouble() returns null for size-inheriting runs;
        // reading must treat it as nullable rather than unbox it (would NPE on open).
        val paras = paragraphs(sampleRichDocx())
        assertNull(paras[1].runs.first().style.sizePt)
    }

    @Test
    fun applyEdits_canApplyParagraphAlignmentAndKind() {
        val edited = WordDocument.applyEditsAndSerialize(
            sampleRichDocx(),
            paraProps = mapOf(
                1 to WordDocument.ParaPropsUpdate(
                    kind = WordDocument.Kind.HEADING2,
                    align = WordDocument.Align.CENTER,
                    indentTwips = 720,
                ),
            ),
        )
        val paras = paragraphs(edited)
        assertEquals(WordDocument.Kind.HEADING2, paras[1].props.kind)
        assertEquals(WordDocument.Align.CENTER, paras[1].props.align)
        assertEquals(720, paras[1].props.indentTwips)
    }

    @Test
    fun applyEdits_canEditTableCellAndAddRow() {
        val bytes = tableDocx()
        val edited = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(
                1 to listOf(
                    listOf("Updated Cell"),
                    listOf("New Row Cell"),
                ),
            ),
        )
        val blocks = WordDocument.read(edited).blocks
        val table = blocks.filterIsInstance<WordDocument.TableBlock>().first()
        assertEquals(2, table.rows.size)
        assertEquals("Updated Cell", table.rows[0][0])
        assertEquals("New Row Cell", table.rows[1][0])
    }

    // ---- input that is not a readable Word document ---------------------------

    /** [docx] with word/document.xml stopped mid-element, the shape a damaged file has. */
    private fun docxWithCutDocumentXml(docx: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            ZipOutputStream(out).use { copy ->
                var entry = zip.nextEntry
                while (entry != null) {
                    copy.putNextEntry(ZipEntry(entry.name))
                    val content = zip.readBytes()
                    if (entry.name == "word/document.xml") {
                        copy.write(content, 0, content.size / 2)
                    } else {
                        copy.write(content)
                    }
                    copy.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    /** The refusal [WordDocument.read] must produce for [bytes]; fails if it opens them. */
    private fun refusal(bytes: ByteArray): WordDocument.UnreadableContent {
        val opened = runCatching { WordDocument.read(bytes) }
        val failure = opened.exceptionOrNull()
        assertTrue(
            "these bytes are not a readable Word document, but reading produced " +
                "${opened.getOrNull()?.blocks?.size} blocks",
            failure is WordDocument.UnreadableContent,
        )
        return failure as WordDocument.UnreadableContent
    }

    /** The OLE2 container of Word 97-2003, as a legacy .doc starts. */
    private fun legacyDoc(): ByteArray = ByteArray(1024).also {
        it[0] = 0xD0.toByte()
        it[1] = 0xCF.toByte()
        it[2] = 0x11
        it[3] = 0xE0.toByte()
    }

    private data class UnreadableCase(
        val label: String,
        val bytes: ByteArray,
        val kind: WordDocument.UnreadableKind,
        val canRetry: Boolean,
    )

    @Test
    fun read_unreadableInput_isRefusedWithTheKindItIsAndTheRetryItDeserves() {
        val full = sampleRichDocx()
        val cases = listOf(
            UnreadableCase("nothing was read", ByteArray(0), WordDocument.UnreadableKind.EMPTY, canRetry = true),
            UnreadableCase(
                "plain text",
                "<html>not a word file</html>".toByteArray(),
                WordDocument.UnreadableKind.NOT_WORD,
                canRetry = false,
            ),
            UnreadableCase(
                "a Word 97-2003 container",
                legacyDoc(),
                WordDocument.UnreadableKind.LEGACY_DOC,
                canRetry = false,
            ),
            UnreadableCase(
                "a package that stopped mid-file",
                full.copyOf(full.size * 4 / 10),
                WordDocument.UnreadableKind.DAMAGED,
                canRetry = true,
            ),
        )
        for (case in cases) {
            val refused = refusal(case.bytes)
            assertEquals(case.label, case.kind, refused.kind)
            assertTrue("${case.label}: the user is told what happened", refused.userMessage.isNotBlank())
            assertEquals("${case.label}: another read is offered only when it can help", case.canRetry, refused.canRetry)
            assertNotNull("${case.label}: the parser's own failure is kept as the cause", refused.cause)
        }
    }

    @Test
    fun read_packageWithCutXml_isRefusedAsDamageAndKeepsTheXmlFailure() {
        val refused = refusal(docxWithCutDocumentXml(sampleRichDocx()))
        assertEquals(WordDocument.UnreadableKind.DAMAGED, refused.kind)
        assertTrue("a short read can be tried again", refused.canRetry)
        assertTrue(
            "the cause chain names the XML failure, was ${refused.cause?.javaClass?.simpleName}",
            generateSequence(refused.cause) { it.cause }.any { it.javaClass.simpleName == "XmlException" },
        )
    }
}
