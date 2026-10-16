package dev.kern.editors.word

import org.apache.poi.util.Units
import org.apache.poi.wp.usermodel.HeaderFooterType
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFFieldRun
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * DOCX preservation regression suite.
 *
 * Every test follows the same shape: build a small deterministic document, make one edit
 * through [WordDocument], and compare *semantic* snapshots of the document before and after
 * (text, run formatting, tables, hyperlink targets, picture references, block order, parts) —
 * never raw ZIP bytes, which POI legitimately reorders when it re-serializes a package.
 *
 * Fixtures are generated with POI at test time instead of being committed as binaries, so
 * they stay small, reviewable and reproducible. The trade-off is that they are POI-written
 * documents rather than Word-written ones; the elements this suite cannot represent
 * (footnotes, comments, tracked changes, content controls, floating shapes) and the elements
 * that are known to be lost are listed in docs/docx-preservation.md, together with the
 * manual device checks that cover them.
 */
class WordPreservationTest {

    // ---- fixtures -----------------------------------------------------------

    private fun docx(build: (XWPFDocument) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            build(doc)
            doc.write(out)
        }
        return out.toByteArray()
    }

    private val png = Base64.getDecoder().decode(PNG_1X1)

    private fun addPicture(run: XWPFRun, name: String) {
        run.addPicture(
            ByteArrayInputStream(png),
            Document.PICTURE_TYPE_PNG,
            name,
            Units.toEMU(20.0),
            Units.toEMU(20.0),
        )
    }

    /** Plain paragraphs: the baseline round trip. */
    private fun plain(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("First line")
        doc.createParagraph().createRun().setText("Second line")
        doc.createParagraph().createRun().setText("Third line")
    }

    /** Paragraph and run formatting that must survive an edit elsewhere. */
    private fun formatted(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.style = "Heading1"
            p.createRun().also { r ->
                r.setText("Heading")
                r.isBold = true
                r.setFontSize(18.0)
            }
        }
        doc.createParagraph().also { p ->
            p.createRun().also { r -> r.setText("Bold "); r.isBold = true }
            p.createRun().also { r -> r.setText("Italic "); r.isItalic = true }
            p.createRun().also { r -> r.setText("Under "); r.underline = UnderlinePatterns.SINGLE }
            p.createRun().also { r -> r.setText("Red"); r.setColor("FF0000") }
        }
        doc.createParagraph().createRun().setText("Plain tail")
    }

    /** One paragraph carrying both a break and a tab stop. */
    private fun breaksAndTabs(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            val r = p.createRun()
            r.setText("before")
            r.addBreak()
            r.setText("after break")
            r.addTab()
            r.setText("after tab")
        }
        doc.createParagraph().createRun().setText("Tail")
    }

    /** Paragraph, 2x2 table, paragraph. */
    private fun withTable(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("Before table")
        val t = doc.createTable(2, 2)
        t.getRow(0).getCell(0).setText("A1")
        t.getRow(0).getCell(1).setText("B1")
        t.getRow(1).getCell(0).setText("A2")
        t.getRow(1).getCell(1).setText("B2")
        doc.createParagraph().createRun().setText("After table")
    }

    /** A paragraph with an external hyperlink, followed by a normal paragraph. */
    private fun withHyperlink(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("See ")
            p.createHyperlinkRun("https://example.com/docs").setText("the docs")
        }
        doc.createParagraph().createRun().setText("Tail after link")
    }

    /**
     * Two pictures in a captioned paragraph, a plain paragraph, a picture-only paragraph
     * (which gets no editable block of its own) and a closing paragraph.
     */
    private fun withImages(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("Caption")
            addPicture(p.createRun(), "image1.png")
            addPicture(p.createRun(), "image2.png")
        }
        doc.createParagraph().createRun().setText("Between pictures")
        doc.createParagraph().also { p -> addPicture(p.createRun(), "image3.png") }
        doc.createParagraph().createRun().setText("After pictures")
    }

    /** Heading, numbered paragraph, table, field, blanks, unicode and a long paragraph. */
    private fun mixed(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.style = "Heading1"
            p.createRun().also { r -> r.setText("Title"); r.isBold = true }
        }
        doc.createParagraph().also { p ->
            p.createRun().setText("Numbered item")
            p.numID = BigInteger.valueOf(1)
        }
        val t = doc.createTable(1, 2)
        t.getRow(0).getCell(0).setText("L")
        t.getRow(0).getCell(1).setText("")
        doc.createParagraph().createRun().setText("Body line")
        // A simple field: text the editor shows, structure it must not delete.
        doc.createParagraph().also { p ->
            p.createRun().setText("Page ")
            p.createFieldRun().setFieldInstruction("PAGE \\* MERGEFORMAT")
        }
        doc.createParagraph()
        doc.createParagraph().createRun().setText("Ünïcödé — 日本語 — العربية — emoji 😀")
        doc.createParagraph().createRun().setText(LONG_TEXT)
        doc.createParagraph().createRun().setText("Tail")
    }

    private fun withHeaderAndFooter(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("Body")
        doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header text")
        doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer text")
    }

    /** Paragraphs carrying numbering references, plus one that is not a list item. */
    private fun numbered(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("One")
            p.numID = BigInteger.valueOf(1)
        }
        doc.createParagraph().also { p ->
            p.createRun().setText("Two")
            p.numID = BigInteger.valueOf(1)
        }
        doc.createParagraph().createRun().setText("Not a list item")
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Semantic snapshot: the content, formatting and structure the editor promises to keep,
     * expressed independently of the app's own model and of the ZIP layout.
     */
    private data class Snapshot(
        val blocks: List<String>,
        val pictures: List<String>,
        val headers: List<String>,
        val footers: List<String>,
    )

    private fun snapshot(bytes: ByteArray): Snapshot = XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
        Snapshot(
            blocks = doc.bodyElements.map { element ->
                when (element) {
                    is XWPFParagraph -> describe(doc, element)
                    is XWPFTable -> describe(element)
                    else -> "opaque"
                }
            },
            pictures = doc.paragraphs.flatMap { p ->
                p.runs.flatMap { run -> run.embeddedPictures ?: emptyList() }
                    .mapNotNull { it.pictureData?.fileName }
            },
            headers = doc.headerList.map { it.text.trim() },
            footers = doc.footerList.map { it.text.trim() },
        )
    }

    private fun describe(doc: XWPFDocument, p: XWPFParagraph): String = buildString {
        append("p{")
        append(
            p.runs.joinToString("+") { run ->
                val traits = buildList {
                    if (run.isBold) add("b")
                    if (run.isItalic) add("i")
                    run.underline?.takeIf { it != UnderlinePatterns.NONE }?.let { add("u") }
                    run.color?.let { add("c$it") }
                    run.fontSizeAsDouble?.let { add("s${it.toInt()}") }
                }.joinToString(",")
                "[${run.text().escaped()}|$traits]"
            },
        )
        append("}")
        val pictures = p.runs.flatMap { it.embeddedPictures ?: emptyList() }
            .mapNotNull { it.pictureData?.fileName }
        if (pictures.isNotEmpty()) append(" img(${pictures.joinToString(",")})")
        val links = p.runs.filterIsInstance<XWPFHyperlinkRun>().map { run ->
            run.getHyperlinkId()?.let { doc.getHyperlinkByID(it)?.getURL() } ?: "internal"
        }
        if (links.isNotEmpty()) append(" lnk(${links.joinToString(",")})")
        val fields = p.runs.filterIsInstance<XWPFFieldRun>().map { it.getFieldInstruction() }
        if (fields.isNotEmpty()) append(" fld(${fields.joinToString(",")})")
        p.numID?.let { append(" num($it)") }
        p.style?.let { append(" style($it)") }
    }

    private fun describe(t: XWPFTable): String =
        "tbl(" + t.rows.joinToString(";") { row ->
            row.tableCells.joinToString("|") { cell -> (cell.text ?: "").escaped() }
        } + ")"

    private fun String.escaped(): String = replace("\n", "\\n").replace("\t", "\\t")

    /** The block index of the first editable paragraph whose text is [text]. */
    private fun blockIndex(bytes: ByteArray, text: String): Int {
        val index = WordDocument.read(bytes).blocks.indexOfFirst {
            it is WordDocument.ParagraphBlock && it.text == text
        }
        check(index >= 0) { "no editable paragraph with text '$text'" }
        return index
    }

    /** The block index of the document's Nth table block. */
    private fun tableBlockIndex(bytes: ByteArray, nth: Int = 0): Int =
        WordDocument.read(bytes).blocks.withIndex()
            .filter { (_, block) -> block is WordDocument.TableBlock }
            .elementAt(nth)
            .index

    private fun textOf(bytes: ByteArray, index: Int): String =
        (WordDocument.read(bytes).blocks[index] as WordDocument.ParagraphBlock).text

    /** Raw XML of one part of the saved package, for claims about structure rather than text. */
    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().decodeToString()
            }
        }
        error("no $name in the saved document")
    }

    // ---- load ---------------------------------------------------------------

    @Test
    fun load_readsTextFormattingTablesPicturesLinksFieldsAndNumbering() {
        val snap = snapshot(mixed())
        assertTrue("heading text and style", snap.blocks.any { it.contains("Title") && it.contains("style(Heading1)") })
        assertTrue("numbered paragraph keeps its numbering", snap.blocks.any { it.contains("Numbered item") && it.contains("num(1)") })
        assertTrue("table shape and cells", snap.blocks.any { it == "tbl(L|)" })
        assertTrue("field instruction", snap.blocks.any { it.contains("fld(PAGE \\* MERGEFORMAT)") })
        assertTrue("unicode text is read verbatim", snap.blocks.any { it.contains("Ünïcödé — 日本語 — العربية — emoji 😀") })

        assertEquals("three picture references, in order", 3, snapshot(withImages()).pictures.size)

        val linked = snapshot(withHyperlink())
        assertTrue("hyperlink target is read", linked.blocks.any { it.contains("lnk(https://example.com/docs)") })
    }

    // ---- round trip ---------------------------------------------------------

    @Test
    fun saveWithoutEdits_keepsEveryFixtureSemanticallyIdentical() {
        val fixtures = linkedMapOf(
            "plain" to plain(),
            "formatted" to formatted(),
            "breaks and tabs" to breaksAndTabs(),
            "table" to withTable(),
            "hyperlink" to withHyperlink(),
            "images" to withImages(),
            "mixed" to mixed(),
            "header and footer" to withHeaderAndFooter(),
            "numbered" to numbered(),
        )
        for ((name, bytes) in fixtures) {
            assertEquals("fixture: $name", snapshot(bytes), snapshot(WordDocument.applyEditsAndSerialize(bytes)))
        }
    }

    @Test
    fun editParagraph_keepsEveryOtherBlockIdentical() {
        val before = snapshot(mixed())
        val bytes = mixed()
        val index = blockIndex(bytes, "Body line")
        val after = snapshot(
            WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited body line")))),
        )
        assertEquals("block count", before.blocks.size, after.blocks.size)
        for (i in before.blocks.indices) {
            if (i == index) {
                assertNotEquals("edited block changed", before.blocks[i], after.blocks[i])
            } else {
                assertEquals("block $i", before.blocks[i], after.blocks[i])
            }
        }
        assertTrue(after.blocks[index].contains("Edited body line"))
    }

    @Test
    fun editParagraph_keepsFormattingOfUntouchedRuns() {
        val before = snapshot(formatted())
        val bytes = formatted()
        val index = blockIndex(bytes, "Plain tail")
        val after = snapshot(
            WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail")))),
        )
        assertEquals(before.blocks[0], after.blocks[0])
        assertEquals(before.blocks[1], after.blocks[1])
        assertEquals("Edited tail", textOf(WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail")))), index))
    }

    @Test
    fun applyEdits_withAStaleBlockIndex_failsInsteadOfDroppingTheEdit() {
        val bytes = plain()
        val failure = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, mapOf(99 to listOf(WordDocument.Run("Lost"))))
        }.exceptionOrNull()
        assertTrue("a save that cannot place an edit must not report success, was $failure", failure is IllegalStateException)
    }

    // ---- images -------------------------------------------------------------

    @Test
    fun editParagraphBetweenPictures_keepsPictureCountAndOrder() {
        val before = snapshot(withImages())
        val bytes = withImages()
        val index = blockIndex(bytes, "Between pictures")
        val after = snapshot(
            WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited between")))),
        )
        assertEquals("picture references are unchanged", before.pictures, after.pictures)
        assertEquals(3, after.pictures.size)
        assertEquals(before.blocks.count { it.contains("img(") }, after.blocks.count { it.contains("img(") })
    }

    @Test
    fun editCaptionOfAParagraphWithPictures_keepsThePictures() {
        val bytes = withImages()
        val index = blockIndex(bytes, "Caption")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("New caption"))))
        val snapshot = snapshot(after)
        assertEquals("New caption", textOf(after, index))
        assertTrue("the caption's pictures are still there", snapshot.blocks.any { it.contains("img(") })
        assertEquals(3, snapshot.pictures.size)
    }

    @Test
    fun editParagraphAfterAPictureOnlyParagraph_hitsThatParagraph() {
        // Regression: the picture-only paragraph adds a block without a body element, so a
        // block index is not a body index. Before the mapping fix this edit was either
        // dropped or written into the picture paragraph.
        val bytes = withImages()
        val index = blockIndex(bytes, "After pictures")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited last"))))
        assertEquals("Edited last", textOf(after, index))
        assertEquals("Between pictures", textOf(after, blockIndex(after, "Between pictures")))
        assertEquals("the picture-only paragraph keeps its picture", 3, snapshot(after).pictures.size)
    }

    // ---- hyperlinks ---------------------------------------------------------

    @Test
    fun editLinkedParagraph_keepsItsHyperlinkTarget() {
        val bytes = withHyperlink()
        val index = blockIndex(bytes, "See the docs")
        // The rich-text field keeps the target as a string annotation on the linked range,
        // so an edited linked run still carries its URL (WordRichText.toRuns). A caller that
        // builds runs without one is asking for the link to be dropped.
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            mapOf(
                index to listOf(
                    WordDocument.Run("See "),
                    WordDocument.Run(
                        "the updated docs",
                        WordDocument.RunStyle(linkUrl = "https://example.com/docs"),
                    ),
                ),
            ),
        )
        // Run markers separate the runs in the snapshot description, so the text is
        // asserted through the document model and the target through the snapshot.
        val read = WordDocument.read(after).blocks[index] as WordDocument.ParagraphBlock
        assertEquals("See the updated docs", read.text)
        val block = snapshot(after).blocks[index]
        assertTrue("the link target survives the rewrite, was $block", block.contains("lnk(https://example.com/docs)"))
    }

    @Test
    fun editParagraphAfterALink_keepsTheLink() {
        val bytes = withHyperlink()
        val index = blockIndex(bytes, "Tail after link")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail"))))
        val linked = snapshot(after).blocks.first { it.contains("lnk(") }
        assertTrue("the untouched link keeps its target, was $linked", linked.contains("lnk(https://example.com/docs)"))
    }

    // ---- tables -------------------------------------------------------------

    @Test
    fun editTableCell_changesOnlyThatCell() {
        val bytes = withTable()
        val index = tableBlockIndex(bytes)
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(index to listOf(listOf("A1", "B1"), listOf("A2", "B2 edited"))),
        )
        val table = snapshot(after).blocks[index]
        assertEquals("tbl(A1|B1;A2|B2 edited)", table)
    }

    @Test
    fun deleteTableColumn_removesTheColumnFromTheSavedTable() {
        val bytes = withTable()
        val index = tableBlockIndex(bytes)
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(index to listOf(listOf("A1"), listOf("A2"))),
        )
        val table = snapshot(after).blocks[index]
        assertEquals("tbl(A1;A2)", table)
        val read = WordDocument.read(after).blocks[index] as WordDocument.TableBlock
        assertEquals("both rows lost a column", listOf(1, 1), read.rows.map { it.size })
    }

    @Test
    fun addTableRow_keepsTheOtherRowsIntact() {
        val bytes = withTable()
        val index = tableBlockIndex(bytes)
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(index to listOf(listOf("A1", "B1"), listOf("A2", "B2"), listOf("A3", "B3"))),
        )
        assertEquals("tbl(A1|B1;A2|B2;A3|B3)", snapshot(after).blocks[index])
    }

    @Test
    fun editParagraphAfterTable_keepsTheTableUntouchedAndInPlace() {
        val before = snapshot(withTable())
        val bytes = withTable()
        val index = blockIndex(bytes, "After table")
        val after = snapshot(WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited after")))))
        assertEquals("block order is unchanged", before.blocks.map { it.take(3) }, after.blocks.map { it.take(3) })
        assertEquals(before.blocks[1], after.blocks[1])
        assertTrue(after.blocks[2].contains("Edited after"))
    }

    // ---- content the editor does not model ----------------------------------

    @Test
    fun editAFieldParagraph_keepsTheField() {
        val bytes = mixed()
        val index = blockIndex(bytes, "Page ")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Page 7"))))
        val block = snapshot(after).blocks[index]
        assertTrue("the field structure survives, was $block", block.contains("fld(PAGE \\* MERGEFORMAT)"))
        assertTrue("the edited text is applied exactly once, was $block", block.contains("[Page 7|]"))
        assertTrue("no duplicated field text, was $block", !block.contains("Page 7Page"))
    }

    @Test
    fun editParagraphElsewhere_keepsHeaderAndFooter() {
        val bytes = withHeaderAndFooter()
        val index = blockIndex(bytes, "Body")
        val after = snapshot(WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited body")))))
        assertEquals(listOf("Header text"), after.headers)
        assertEquals(listOf("Footer text"), after.footers)
    }

    @Test
    fun editParagraph_keepsNumberingOnListParagraphs() {
        val bytes = numbered()
        val index = blockIndex(bytes, "Not a list item")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Still not a list item"))))
        val blocks = snapshot(after).blocks
        assertTrue("first item keeps numbering", blocks[0].contains("One") && blocks[0].contains("num(1)"))
        assertTrue("second item keeps numbering", blocks[1].contains("Two") && blocks[1].contains("num(1)"))
        assertTrue(blocks[2].contains("Still not a list item"))
    }

    @Test
    fun editParagraph_keepsBlankParagraphsAndEmptyCells() {
        val before = snapshot(mixed())
        val bytes = mixed()
        val index = blockIndex(bytes, "Tail")
        val after = snapshot(WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail")))))
        assertEquals("blank paragraphs survive", before.blocks.count { it == "p{}" }, after.blocks.count { it == "p{}" })
        assertTrue("empty table cell survives", after.blocks.any { it == "tbl(L|)" })
    }

    @Test
    fun editParagraph_keepsLongAndUnicodeText() {
        val before = snapshot(mixed())
        val bytes = mixed()
        val index = blockIndex(bytes, "Tail")
        val after = snapshot(WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail")))))
        assertEquals(before.blocks.count { it.contains(LONG_TEXT) }, 1)
        assertEquals(before.blocks.count { it.contains("😀") }, after.blocks.count { it.contains("😀") })
        assertTrue(after.blocks.any { it.contains(LONG_TEXT) })
    }

    @Test
    fun editingAParagraphWithBreaksAndTabs_writesRealTabStops() {
        val bytes = breaksAndTabs()
        val index = blockIndex(bytes, "before\nafter break\tafter tab")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            mapOf(index to listOf(WordDocument.Run("before\nafter break\tafter tab"))),
        )
        assertEquals("before\nafter break\tafter tab", textOf(after, index))
        val xml = part(after, "word/document.xml")
        assertTrue("a real tab stop (w:tab) is written, not a literal tab character", xml.contains("<w:tab"))
        assertTrue("a real break (w:br) is written", xml.contains("<w:br"))
    }

    /**
     * Known data loss (documented in docs/docx-preservation.md): run properties the editor's
     * run model does not carry — superscript, character styles, language, proofing marks — are
     * not rebuilt when their paragraph is edited. Enabled when the run model carries them.
     */
    @Ignore("Known data loss: unmodelled run properties are dropped when their paragraph is edited")
    @Test
    fun editingAParagraphKeepsSuperscript() {
        val bytes = docx { doc -> doc.createParagraph().createRun().setText("x2") }
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(0 to listOf(WordDocument.Run("x3"))))
        assertTrue(part(after, "word/document.xml").contains("<w:vertAlign"))
    }

    private companion object {
        /**
         * A valid 1x1 PNG, generated once and pinned as base64 so fixtures never depend on
         * image tooling. 68 bytes, RGBA, non-interlaced.
         */
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

        val LONG_TEXT = "long paragraph content ".repeat(120).trim()
    }
}
