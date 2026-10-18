package dev.kern.editors.word

import dev.kern.shared.io.DocumentIo
import org.apache.poi.util.Units
import org.apache.poi.wp.usermodel.HeaderFooterType
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFFieldRun
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    private val png2 = Base64.getDecoder().decode(PNG_1X2)

    private fun addPicture(run: XWPFRun, name: String, bytes: ByteArray = png) {
        run.addPicture(
            ByteArrayInputStream(bytes),
            Document.PICTURE_TYPE_PNG,
            name,
            Units.toEMU(20.0),
            Units.toEMU(20.0),
        )
    }

    /** One paragraph holding two pictures that differ in size, so a removal can be told apart. */
    private fun withTwoDistinctPictures(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("Caption")
            addPicture(p.createRun(), "first.png")
            addPicture(p.createRun(), "second.png", png2)
        }
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
        // The field keeps its place: the character the edit added was typed in front of it.
        assertEquals(listOf("t(Page 7)", "fld"), runOrder(after, "Page 7"))
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

    // ---- save safety --------------------------------------------------------

    @Test
    fun saving_producesAPackageThatReadsBackWithTheEditAndItsUnrelatedContent() {
        // A save is only real if the result can be read again: the check the editors run
        // before replacing the file, then a reopen of what the check accepted.
        val bytes = mixed()
        val index = blockIndex(bytes, "Tail")
        val before = snapshot(bytes)
        val saved = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited tail"))))

        assertTrue("the serializer's own output passes the save check", DocumentIo.verifyZipPackage(saved))
        assertEquals("the edit is in the reopened document", "Edited tail", textOf(saved, index))
        val after = snapshot(saved)
        assertEquals("headers survive", before.headers, after.headers)
        assertEquals("footers survive", before.footers, after.footers)
        assertEquals(
            "the table, the field and the numbering survive",
            before.blocks.filter { it.startsWith("tbl(") || it.contains("fld(") || it.contains("num(") },
            after.blocks.filter { it.startsWith("tbl(") || it.contains("fld(") || it.contains("num(") },
        )
    }

    @Test
    fun saving_keepsImageRelationshipsResolvable() {
        val bytes = withImages()
        val before = snapshot(bytes)
        val index = blockIndex(bytes, "After pictures")
        val saved = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited after pictures"))))

        assertEquals("every picture is still referenced", before.pictures, snapshot(saved).pictures)
        assertTrue(
            "the relationships the pictures point at are still in the package",
            part(saved, "word/_rels/document.xml.rels").contains("media/"),
        )
    }

    @Test
    fun saveCheckRefusesATruncatedSerialization() {
        // Item 17 of the Phase 3 test list: an incomplete package must never be reported as
        // a successful save. The editors pass the check before opening the destination.
        val bytes = plain()
        val index = blockIndex(bytes, "Second line")
        val saved = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited line"))))

        assertFalse("half a document must not replace the file", DocumentIo.verifyZipPackage(saved.copyOf(saved.size / 2)))
        assertTrue("the complete one is accepted", DocumentIo.verifyZipPackage(saved))
    }

    @Test
    fun undoRedo_keepsUnrelatedContentOfEveryFixture() {
        // An edit, its undo, and its redo each go through the serializer. Table structure,
        // hyperlink targets and picture relationships must be identical in all three.
        for ((name, bytes) in listOf(
            "table" to withTable(),
            "hyperlink" to withHyperlink(),
            "images" to withImages(),
        )) {
            val before = snapshot(bytes)
            val read = WordDocument.read(bytes)
            val index = read.blocks.indexOfLast { it is WordDocument.ParagraphBlock && it.text.isNotEmpty() }
            check(index >= 0) { "no editable paragraph in the $name fixture" }
            val original = textOf(bytes, index)
            val edit = mapOf(index to listOf(WordDocument.Run("Edited")))

            val undone = WordDocument.applyEditsAndSerialize(bytes)
            val redone = WordDocument.applyEditsAndSerialize(bytes, edit)

            for ((step, result) in listOf("undo" to undone, "redo" to redone)) {
                val after = snapshot(result)
                assertEquals("$name/$step: pictures", before.pictures, after.pictures)
                assertEquals("$name/$step: headers", before.headers, after.headers)
                assertEquals("$name/$step: footers", before.footers, after.footers)
                assertEquals(
                    "$name/$step: tables and hyperlinks",
                    before.blocks.filter { it.startsWith("tbl(") || it.contains("lnk(") },
                    after.blocks.filter { it.startsWith("tbl(") || it.contains("lnk(") },
                )
            }
            assertEquals("$name: undo keeps the paragraph as it was", original, textOf(undone, index))
            assertEquals("$name: redo reapplies the edit", "Edited", textOf(redone, index))
        }
    }

    @Test
    fun serializerLeavesTheCapturedSnapshotUntouched() {
        // A save works from the maps captured when it started. If serialization mutated
        // them, an edit arriving during the save could change what is written mid-flight,
        // and the editors' "was the written revision still the current one?" comparison.
        val bytes = withTable()
        val paragraph = blockIndex(bytes, "Before table")
        val table = tableBlockIndex(bytes)
        val edits = mutableMapOf(paragraph to listOf(WordDocument.Run("Edited")))
        val props = mutableMapOf<Int, WordDocument.ParaPropsUpdate>()
        val tables = mutableMapOf(table to listOf(listOf("A1", "B1"), listOf("A2", "B2")))

        val saved = WordDocument.applyEditsAndSerialize(bytes, edits, props, tables)

        assertEquals("the edit map is unchanged", listOf(WordDocument.Run("Edited")), edits[paragraph])
        assertEquals("no entry is added to the edit map", 1, edits.size)
        assertTrue("no property entry is added", props.isEmpty())
        assertEquals(
            "the table grid is unchanged",
            listOf(listOf("A1", "B1"), listOf("A2", "B2")),
            tables[table],
        )
        assertEquals("the edit reached the document", "Edited", textOf(saved, paragraph))
        assertTrue(
            "the table edit reached the document",
            snapshot(saved).blocks.any { it.contains("tbl(A1|B1;A2|B2)") },
        )
    }

    // ---- Phase 4: lists -----------------------------------------------------

    /** An abstract numbering with two levels, the shape Word writes for a list. */
    private fun abstractNumbering(id: Long, bullet: Boolean): XWPFAbstractNum {
        val abstractNum = CTAbstractNum.Factory.newInstance()
        abstractNum.setAbstractNumId(BigInteger.valueOf(id))
        for (level in 0 until 2) {
            val lvl = abstractNum.addNewLvl()
            lvl.setIlvl(BigInteger.valueOf(level.toLong()))
            lvl.addNewNumFmt().setVal(if (bullet) STNumberFormat.BULLET else STNumberFormat.DECIMAL)
            lvl.addNewLvlText().setVal(if (bullet) "\u2022" else "%${level + 1}.")
        }
        return XWPFAbstractNum(abstractNum)
    }

    private fun listItem(doc: XWPFDocument, text: String, numId: Long, level: Long = 0) {
        doc.createParagraph().also { p ->
            p.createRun().setText(text)
            p.setNumID(BigInteger.valueOf(numId))
            p.setNumILvl(BigInteger.valueOf(level))
        }
    }

    /**
     * Bullets (with a second level), a numbered list of two items, a plain paragraph and a
     * second numbered list that restarts at one — the shapes a numbering change must neither
     * merge nor restart.
     */
    private fun lists(): ByteArray = docx { doc ->
        doc.createNumbering().also { numbering ->
            numbering.addAbstractNum(abstractNumbering(0, bullet = true))
            numbering.addAbstractNum(abstractNumbering(1, bullet = false))
            numbering.addNum(BigInteger.ZERO, BigInteger.ONE)
            numbering.addNum(BigInteger.ONE, BigInteger.valueOf(2))
            numbering.addNum(BigInteger.ONE, BigInteger.valueOf(3))
        }
        listItem(doc, "Bullet one", numId = 1)
        listItem(doc, "Bullet two", numId = 1, level = 1)
        listItem(doc, "Numbered one", numId = 2)
        listItem(doc, "Numbered two", numId = 2)
        doc.createParagraph().createRun().setText("Plain between lists")
        listItem(doc, "Restarted one", numId = 3)
    }

    private fun listTypeOf(bytes: ByteArray, text: String): WordDocument.ListType =
        WordDocument.read(bytes).blocks
            .filterIsInstance<WordDocument.ParagraphBlock>()
            .first { it.text == text }
            .props.listType

    @Test
    fun load_readsBulletsNumbersAndListRestartsApart() {
        val bytes = lists()
        assertEquals(WordDocument.ListType.BULLET, listTypeOf(bytes, "Bullet one"))
        assertEquals("a sub-level of a bullet list is still a bullet", WordDocument.ListType.BULLET, listTypeOf(bytes, "Bullet two"))
        assertEquals(WordDocument.ListType.NUMBER, listTypeOf(bytes, "Numbered one"))
        assertEquals(WordDocument.ListType.NUMBER, listTypeOf(bytes, "Numbered two"))
        assertEquals("the restarted list is still a numbered list", WordDocument.ListType.NUMBER, listTypeOf(bytes, "Restarted one"))
        assertEquals(WordDocument.ListType.NONE, listTypeOf(bytes, "Plain between lists"))
    }

    @Test
    fun load_readsABulletThatCarriesNoItemLevelFromItsDefinition() {
        // Word writes w:ilvl for lists it creates, but converted documents often carry only
        // w:numId; OOXML then means level 0, whose format has to come from the definition.
        val bytes = docx { doc ->
            doc.createNumbering().also { numbering ->
                numbering.addAbstractNum(abstractNumbering(0, bullet = true))
                numbering.addNum(BigInteger.ZERO, BigInteger.ONE)
            }
            doc.createParagraph().also { p ->
                p.createRun().setText("Bullet without level")
                p.setNumID(BigInteger.ONE)
            }
        }
        assertEquals(WordDocument.ListType.BULLET, listTypeOf(bytes, "Bullet without level"))
    }

    @Test
    fun applyBullet_writesADefinitionTheDocumentCanResolve() {
        // Before the fix the save created an empty numbering part and pointed the paragraph at
        // num id 1, which did not exist: the list rendered as plain text.
        val bytes = plain()
        val index = blockIndex(bytes, "Second line")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            paraProps = mapOf(index to WordDocument.ParaPropsUpdate(listType = WordDocument.ListType.BULLET)),
        )

        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val p = doc.bodyElements.filterIsInstance<XWPFParagraph>()[1]
            val numId = p.numID
            assertNotNull("the paragraph references a list", numId)
            assertEquals("the reference resolves to a bullet list", "bullet", p.numFmt)
            assertNotNull("the num exists", doc.numbering.getNum(numId))
        }
        val read = WordDocument.read(after).blocks[index] as WordDocument.ParagraphBlock
        assertEquals("the editor reads the item back as a bullet", WordDocument.ListType.BULLET, read.props.listType)
        assertEquals("the text is untouched", "Second line", read.text)
    }

    @Test
    fun applyNumberToTwoParagraphs_putsThemInOneList() {
        val bytes = plain()
        val update = WordDocument.ParaPropsUpdate(listType = WordDocument.ListType.NUMBER)
        val after = WordDocument.applyEditsAndSerialize(bytes, paraProps = mapOf(0 to update, 1 to update))

        val numIds = XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFParagraph>().map { it.numID }
        }
        assertNotNull("both items reference a definition", numIds[0])
        assertEquals("both items share one list", numIds[0], numIds[1])
        assertEquals(
            listOf(
                WordDocument.ListType.NUMBER,
                WordDocument.ListType.NUMBER,
                WordDocument.ListType.NONE,
            ),
            WordDocument.read(after).blocks.map { (it as WordDocument.ParagraphBlock).props.listType },
        )
    }

    @Test
    fun applyBullet_keepsTheExistingListsAndTheirDefinitions() {
        val bytes = lists()
        val index = blockIndex(bytes, "Plain between lists")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            paraProps = mapOf(index to WordDocument.ParaPropsUpdate(listType = WordDocument.ListType.BULLET)),
        )

        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            fun paragraph(text: String) = doc.bodyElements.filterIsInstance<XWPFParagraph>().first { it.text == text }
            assertEquals("the bullet list keeps its id", BigInteger.ONE, paragraph("Bullet one").numID)
            assertEquals("the numbered list keeps its id", BigInteger.valueOf(2), paragraph("Numbered one").numID)
            assertEquals("the restarted list keeps its own id", BigInteger.valueOf(3), paragraph("Restarted one").numID)
            assertEquals("the numbered list is still decimal", "decimal", paragraph("Numbered one").numFmt)
            val applied = paragraph("Plain between lists").numID
            assertNotNull("the new item references a list", applied)
            assertTrue("the new list is its own", applied != null && applied !in listOf(BigInteger.ONE, BigInteger.valueOf(2), BigInteger.valueOf(3)))
            assertEquals("the new list resolves", "bullet", paragraph("Plain between lists").numFmt)
        }
        assertEquals(
            "the editor reads the new item back as a bullet",
            WordDocument.ListType.BULLET,
            listTypeOf(after, "Plain between lists"),
        )
        assertEquals("the numbered items stay numbered", WordDocument.ListType.NUMBER, listTypeOf(after, "Numbered two"))
    }

    @Test
    fun applyNumberToAnItemThatIsAlreadyNumbered_doesNotRestartIt() {
        val bytes = lists()
        val index = blockIndex(bytes, "Numbered one")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            paraProps = mapOf(index to WordDocument.ParaPropsUpdate(listType = WordDocument.ListType.NUMBER)),
        )
        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val p = doc.bodyElements.filterIsInstance<XWPFParagraph>().first { it.text == "Numbered one" }
            assertEquals("the item stays in its own list", BigInteger.valueOf(2), p.numID)
            assertEquals("no definition was added", 2, doc.numbering.getAbstractNums().size)
            assertEquals("no list was added", 3, doc.numbering.getNums().size)
        }
    }

    @Test
    fun changeTheListTypeOfAnItem_movesItToTheOtherList() {
        val bytes = lists()
        val index = blockIndex(bytes, "Numbered two")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            paraProps = mapOf(index to WordDocument.ParaPropsUpdate(listType = WordDocument.ListType.BULLET)),
        )
        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            fun paragraph(text: String) = doc.bodyElements.filterIsInstance<XWPFParagraph>().first { it.text == text }
            assertEquals("the item became a bullet", "bullet", paragraph("Numbered two").numFmt)
            assertEquals("the other item keeps its list", BigInteger.valueOf(2), paragraph("Numbered one").numID)
        }
    }

    @Test
    fun aDanglingNumberingReferenceDoesNotStopOpenOrSave() {
        // POI's getNumFmt() throws when the num the paragraph points at has no abstract
        // definition [verified against XWPFParagraph 310-330]. A damaged document must open
        // and save, with the damaged reference left as it is.
        val bytes = docx { doc ->
            doc.createNumbering().also { numbering ->
                numbering.addAbstractNum(abstractNumbering(0, bullet = false))
                numbering.addNum(BigInteger.ZERO, BigInteger.ONE)
                numbering.removeAbstractNum(BigInteger.ZERO)
            }
            doc.createParagraph().also { p ->
                p.createRun().setText("Damaged item")
                p.setNumID(BigInteger.ONE)
            }
            doc.createParagraph().createRun().setText("Tail")
        }

        assertEquals(
            "a damaged list reference still reads as a list item",
            WordDocument.ListType.NUMBER,
            listTypeOf(bytes, "Damaged item"),
        )
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(1 to listOf(WordDocument.Run("Edited tail"))))
        assertEquals("the edit is saved", "Edited tail", textOf(after, 1))
        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            assertEquals(
                "the damaged reference is left alone",
                BigInteger.ONE,
                doc.bodyElements.filterIsInstance<XWPFParagraph>()[0].numID,
            )
        }
    }

    // ---- Phase 4: run order inside an edited paragraph ----------------------

    /** Text with a picture between two text runs of the same paragraph. */
    private fun pictureBetweenText(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createRun().setText("before ")
            addPicture(p.createRun(), "middle.png")
            p.createRun().setText("after")
        }
        doc.createParagraph().createRun().setText("Tail")
    }

    /** Two hyperlinks pointing at different targets, with plain text between them. */
    private fun withTwoLinks(): ByteArray = docx { doc ->
        doc.createParagraph().also { p ->
            p.createHyperlinkRun("https://example.com/one").setText("first")
            p.createRun().setText(" and ")
            p.createHyperlinkRun("https://example.com/two").setText("second")
        }
    }

    /** The runs of the first paragraph whose text contains [text], in document order. */
    private fun runOrder(bytes: ByteArray, text: String): List<String> =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            val p = doc.bodyElements.filterIsInstance<XWPFParagraph>().first { it.text.contains(text) }
            p.runs.map { run ->
                when {
                    run.embeddedPictures?.isNotEmpty() == true -> "img"
                    run is XWPFFieldRun -> "fld"
                    else -> "t(${run.text()})"
                }
            }
        }

    @Test
    fun editCaption_keepsThePicturesAfterTheEditedText() {
        // Before the fix a kept run was written in front of the rebuilt text, so editing the
        // caption moved its pictures to the start of the paragraph.
        val bytes = withImages()
        val index = blockIndex(bytes, "Caption")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("New caption"))))
        assertEquals(listOf("t(New caption)", "img", "img"), runOrder(after, "New caption"))
        assertEquals("no picture is lost", 3, snapshot(after).pictures.size)
    }

    @Test
    fun editTextAroundAPicture_keepsThePictureBetweenTheText() {
        val bytes = pictureBetweenText()
        val index = blockIndex(bytes, "before after")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("before edited after"))))
        // The picture sits exactly at the character offset the inserted text starts at, so the
        // text goes in front of it — the same rule as typing beside a picture in Word. Either
        // way the picture stays between the words instead of moving to the start of the
        // paragraph, which is what the old code did.
        assertEquals(listOf("t(before edited )", "img", "t(after)"), runOrder(after, "before"))
        assertEquals("the picture is still in the paragraph", 1, snapshot(after).pictures.size)
        assertEquals("before edited after", textOf(after, index))
    }

    @Test
    fun editTextBetweenTwoLinks_keepsBothTargets() {
        val bytes = withTwoLinks()
        val index = blockIndex(bytes, "first and second")
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            mapOf(
                index to listOf(
                    WordDocument.Run("first", WordDocument.RunStyle(linkUrl = "https://example.com/one")),
                    WordDocument.Run(" plus "),
                    WordDocument.Run("second", WordDocument.RunStyle(linkUrl = "https://example.com/two")),
                ),
            ),
        )
        val read = WordDocument.read(after).blocks[index] as WordDocument.ParagraphBlock
        assertEquals("first plus second", read.text)
        assertEquals(
            "both targets survive the edit",
            listOf("https://example.com/one", "https://example.com/two"),
            read.runs.mapNotNull { it.style.linkUrl },
        )
    }

    // ---- Phase 4: table cells ----------------------------------------------

    /** A table with a two-paragraph cell, a bold cell, an empty cell and a picture cell. */
    private fun withRichTable(): ByteArray = docx { doc ->
        val t = doc.createTable(2, 2)
        t.getRow(0).getCell(0).also { cell ->
            cell.paragraphs[0].createRun().setText("First")
            cell.addParagraph().createRun().setText("Second")
        }
        t.getRow(0).getCell(1).also { cell ->
            cell.paragraphs[0].createRun().also { run -> run.setText("Bold"); run.isBold = true }
        }
        t.getRow(1).getCell(0).setText("")
        t.getRow(1).getCell(1).also { cell -> addPicture(cell.paragraphs[0].createRun(), "cell.png") }
    }

    private fun tableGrid(bytes: ByteArray, nth: Int = 0): List<List<String>> {
        val index = tableBlockIndex(bytes, nth)
        return (WordDocument.read(bytes).blocks[index] as WordDocument.TableBlock).rows
    }

    private fun cellLines(bytes: ByteArray, row: Int, col: Int): List<String> =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFTable>().first()
                .getRow(row).getCell(col).paragraphs.map { it.text ?: "" }
        }

    /**
     * How many pictures the (row, col) cell of the first table holds. Counted rather than
     * named: POI's addPicture ignores the file name it is handed and auto-names the stored
     * data (image1.png), so only the presence can be asserted.
     */
    private fun cellPictureCount(bytes: ByteArray, row: Int, col: Int): Int =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFTable>().first()
                .getRow(row).getCell(col).paragraphs
                .sumOf { p -> p.runs.sumOf { run -> run.embeddedPictures.size } }
        }

    private fun cellIsBold(bytes: ByteArray, row: Int, col: Int): Boolean =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFTable>().first()
                .getRow(row).getCell(col).paragraphs[0].runs[0].isBold
        }

    private fun editCell(bytes: ByteArray, index: Int, row: Int, col: Int, text: String): ByteArray {
        val grid = tableGrid(bytes).mapIndexed { r, cells ->
            if (r == row) cells.mapIndexed { c, cell -> if (c == col) text else cell } else cells
        }
        return WordDocument.applyEditsAndSerialize(bytes, tableEdits = mapOf(index to grid))
    }

    @Test
    fun load_readsACellsParagraphsAsLines() {
        val rows = tableGrid(withRichTable())
        assertEquals(listOf("First\nSecond", "Bold"), rows[0])
        assertEquals(listOf("", ""), rows[1])
    }

    @Test
    fun saveWithoutEdits_keepsMultiParagraphCellsTheirFormattingAndTheirPicture() {
        val bytes = withRichTable()
        val after = WordDocument.applyEditsAndSerialize(bytes)
        assertEquals("the cell keeps both paragraphs", listOf("First", "Second"), cellLines(after, 0, 0))
        assertEquals("the bold cell keeps its text", listOf("Bold"), cellLines(after, 0, 1))
        assertTrue("the bold cell is still bold", cellIsBold(after, 0, 1))
        assertEquals("the cell keeps its picture", 1, cellPictureCount(after, 1, 1))
        assertEquals("the empty cell stays empty", listOf(""), cellLines(after, 1, 0))
    }

    @Test
    fun editOneCell_changesOnlyThatCellAndKeepsTheOthersWhole() {
        // Before the fix every cell's first paragraph was rewritten on any table edit, which
        // dropped the bold of a cell nobody had touched and deleted pictures inside cells.
        val bytes = withRichTable()
        val index = tableBlockIndex(bytes)
        val after = editCell(bytes, index, row = 0, col = 1, text = "Bold edited")

        assertEquals("the edited cell took the new text", listOf("Bold edited"), cellLines(after, 0, 1))
        assertTrue("the edited cell keeps its formatting", cellIsBold(after, 0, 1))
        assertEquals("the two-paragraph cell is untouched", listOf("First", "Second"), cellLines(after, 0, 0))
        assertEquals("the picture cell is untouched", 1, cellPictureCount(after, 1, 1))
        assertEquals("the empty cell stays empty", listOf(""), cellLines(after, 1, 0))
        assertEquals(
            "the grid reads back with the edit",
            listOf(listOf("First\nSecond", "Bold edited"), listOf("", "")),
            tableGrid(after),
        )
    }

    @Test
    fun editATwoParagraphCell_keepsBothLines() {
        // The cell used to be read as "FirstSecond" (POI concatenates a cell's paragraphs with
        // nothing between them) and written back into its first paragraph, duplicating the rest.
        val bytes = withRichTable()
        val index = tableBlockIndex(bytes)
        val after = editCell(bytes, index, row = 0, col = 0, text = "First\nSecond edited")

        assertEquals(listOf("First", "Second edited"), cellLines(after, 0, 0))
        assertEquals("the other cells are untouched", listOf("Bold"), cellLines(after, 0, 1))
        assertEquals("the cell keeps its picture", 1, cellPictureCount(after, 1, 1))
    }

    @Test
    fun editACellWithAPicture_keepsThePicture() {
        val bytes = withRichTable()
        val index = tableBlockIndex(bytes)
        val after = editCell(bytes, index, row = 1, col = 1, text = "Caption text")

        assertEquals(listOf("Caption text"), cellLines(after, 1, 1))
        assertEquals("the picture stays in the cell", 1, cellPictureCount(after, 1, 1))
    }

    @Test
    fun editACellThatHoldsAWholeCellLink_keepsTheTarget() {
        val bytes = docx { doc ->
            val t = doc.createTable(1, 1)
            t.getRow(0).getCell(0).paragraphs[0].createHyperlinkRun("https://example.com/cell").setText("Cell link")
        }
        val after = editCell(bytes, tableBlockIndex(bytes), row = 0, col = 0, text = "Edited cell link")

        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val run = doc.bodyElements.filterIsInstance<XWPFTable>().first()
                .getRow(0).getCell(0).paragraphs[0].runs.first()
            assertTrue("the rewritten text is a link again, was ${run.javaClass.simpleName}", run is XWPFHyperlinkRun)
            assertEquals(
                "https://example.com/cell",
                doc.getHyperlinkByID((run as XWPFHyperlinkRun).getHyperlinkId())?.getURL(),
            )
        }
    }

    @Test
    fun editACellThatHoldsAPartlyLinkedText_writesPlainTextAndDropsThePartialLink() {
        // The grid model has no way to say which words of a cell were linked, so an edited
        // part-linked cell becomes plain text. This is a documented limitation, not an
        // accident: the assertion is here so the behaviour cannot change unnoticed.
        val bytes = docx { doc ->
            val t = doc.createTable(1, 1)
            t.getRow(0).getCell(0).paragraphs[0].also { p ->
                p.createRun().setText("See ")
                p.createHyperlinkRun("https://example.com/docs").setText("the docs")
            }
        }
        val after = editCell(bytes, tableBlockIndex(bytes), row = 0, col = 0, text = "See the updated docs")

        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val runs = doc.bodyElements.filterIsInstance<XWPFTable>().first()
                .getRow(0).getCell(0).paragraphs[0].runs
            assertEquals("See the updated docs", runs.joinToString("") { it.text() })
            assertTrue("no link is left behind", runs.none { it is XWPFHyperlinkRun })
        }
    }

    // ---- Phase 4: content the editor cannot model ---------------------------

    /** A non-default section: the page size and margins of a real document. */
    private fun withSectionProperties(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("Body")
        val body = doc.getDocument().getBody()
        val sect = body.getSectPr() ?: body.addNewSectPr()
        (sect.pgSz ?: sect.addNewPgSz()).also {
            it.setW(BigInteger.valueOf(11906))
            it.setH(BigInteger.valueOf(16838))
        }
        (sect.pgMar ?: sect.addNewPgMar()).also {
            it.setTop(BigInteger.valueOf(1440))
            it.setLeft(BigInteger.valueOf(1800))
        }
    }

    private fun section(bytes: ByteArray): String = XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
        val sect = doc.document.body.sectPr
        "${sect?.pgSz?.w}x${sect?.pgSz?.h} top=${sect?.pgMar?.top} left=${sect?.pgMar?.left}"
    }

    @Test
    fun editParagraph_keepsSectionProperties() {
        val bytes = withSectionProperties()
        val before = section(bytes)
        assertTrue("the fixture carries a custom page size, was $before", before.contains("11906"))
        val index = blockIndex(bytes, "Body")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited body"))))
        assertEquals("the page setup survives the save", before, section(after))
    }

    /**
     * A document whose XML carries content POI cannot generate: a tracked insertion, a
     * tracked deletion and an inline content control. Built by rewriting the document part
     * of a POI-written package, because POI has no API for any of the three.
     */
    private fun withUnmodelledXml(): ByteArray {
        val bytes = docx { doc ->
            // The first paragraph carries no text of its own, so what the editor reads back is
            // exactly what the injected XML says.
            doc.createParagraph().createRun()
            doc.createParagraph().createRun().setText("Elsewhere")
        }
        val before = part(bytes, "word/document.xml")
        val injected = "<w:ins w:id=\"1\" w:author=\"tester\"><w:r><w:t>Inserted subject</w:t></w:r></w:ins>" +
            "<w:del w:id=\"2\" w:author=\"tester\"><w:r><w:delText>Removed subject</w:delText></w:r></w:del>" +
            "<w:sdt><w:sdtPr><w:id w:val=\"7\"/></w:sdtPr><w:sdtContent><w:r><w:t>Control subject</w:t></w:r></w:sdtContent></w:sdt>"
        // Injected at the end of the first paragraph: the exact serialization of the run POI
        // wrote is not stable enough to match on, the paragraph end tag is.
        val end = before.indexOf("</w:p>")
        check(end > 0) { "the fixture could not find the first paragraph" }
        return withPart(bytes, "word/document.xml", before.substring(0, end) + injected + before.substring(end))
    }

    /** Replaces one part of a package, leaving every other entry as it was. */
    private fun withPart(bytes: ByteArray, name: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == name) content.toByteArray() else input.readBytes())
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun load_hidesTrackedDeletionsButKeepsThemInThePackage() {
        val bytes = withUnmodelledXml()
        val text = (WordDocument.read(bytes).blocks[0] as WordDocument.ParagraphBlock).text
        assertFalse("deleted text is not editable text, was '$text'", text.contains("Removed subject"))
        assertTrue("a tracked insertion is editable text", text.contains("Inserted subject"))
        assertFalse("a content control stays outside the editable text, was '$text'", text.contains("Control subject"))
        assertTrue("the content control is still in the package", part(bytes, "word/document.xml").contains("Control subject"))
    }

    @Test
    fun editParagraphElsewhere_keepsTrackedChangesAndContentControls() {
        val bytes = withUnmodelledXml()
        val index = blockIndex(bytes, "Elsewhere")
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("Edited elsewhere"))))
        val xml = part(after, "word/document.xml")

        assertTrue("the tracked insertion survives", xml.contains("<w:ins") && xml.contains("Inserted subject"))
        assertTrue("the tracked deletion survives", xml.contains("<w:del") && xml.contains("Removed subject"))
        assertTrue("the content control survives", xml.contains("<w:sdt>") && xml.contains("Control subject"))
        assertEquals("the edit is saved", "Edited elsewhere", textOf(after, blockIndex(after, "Edited elsewhere")))
    }

    @Test
    fun editingAParagraphWithATrackedDeletion_keepsTheDeletionIntact() {
        val bytes = withUnmodelledXml()
        val index = blockIndex(bytes, "Inserted subject")
        val observed = (WordDocument.read(bytes).blocks[index] as WordDocument.ParagraphBlock).text
        val after = WordDocument.applyEditsAndSerialize(bytes, mapOf(index to listOf(WordDocument.Run("$observed edited"))))
        val xml = part(after, "word/document.xml")

        assertTrue("the deletion mark and its hidden text survive", xml.contains("<w:del") && xml.contains("Removed subject"))
        assertTrue("the edit is in the document", textOf(after, index).contains("edited"))
        assertFalse(
            "the hidden text did not become visible text",
            (WordDocument.read(after).blocks[index] as WordDocument.ParagraphBlock).text.contains("Removed subject"),
        )
    }

    // ---- Phase 5: paragraph structure, list levels, pictures -----------------

    /** Body paragraphs (empty ones included), in document order. */
    private fun paragraphTexts(bytes: ByteArray): List<String> =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.bodyElements.filterIsInstance<XWPFParagraph>().map { it.text ?: "" }
        }

    /** Every embedded picture of the document, body paragraphs and table cells alike. */
    private fun pictureCount(bytes: ByteArray): Int = XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
        doc.bodyElements.filterIsInstance<XWPFParagraph>()
            .sumOf { p -> p.runs.sumOf { run -> run.embeddedPictures.size } } +
            doc.bodyElements.filterIsInstance<XWPFTable>()
                .sumOf { table ->
                    table.rows.sumOf { row ->
                        row.tableCells.sumOf { cell ->
                            cell.paragraphs.sumOf { p -> p.runs.sumOf { run -> run.embeddedPictures.size } }
                        }
                    }
                }
    }

    /** How many times a piece of XML appears in the document part. */
    private fun xmlCount(bytes: ByteArray, needle: String): Int =
        part(bytes, "word/document.xml").split(needle).size - 1

    private fun numberingDefinitionCount(bytes: ByteArray): Int =
        part(bytes, "word/numbering.xml").split("<w:num ").size - 1

    private fun gridColumns(bytes: ByteArray, nth: Int = 0): Int =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.tables[nth].getCTTbl().getTblGrid()?.sizeOfGridColArray() ?: 0
        }

    private fun gridWidths(bytes: ByteArray, nth: Int = 0): List<Long> =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.tables[nth].getCTTbl().getTblGrid()?.gridColList
                ?.mapNotNull { (it.getW() as? Number)?.toLong() } ?: emptyList()
        }

    private fun rowCellCount(bytes: ByteArray, row: Int, nth: Int = 0): Int =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc -> doc.tables[nth].getRow(row).tableCells.size }

    private fun cellWidth(bytes: ByteArray, row: Int, col: Int): Long? =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            (doc.tables[0].getRow(row).getCell(col).getCTTc().getTcPr()?.getTcW()?.getW() as? Number)?.toLong()
        }

    private fun splitParagraph(bytes: ByteArray, index: Int, offset: Int): ByteArray =
        WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.SplitParagraph(index, offset))

    @Test
    fun splitParagraph_movesTheTextAfterTheCaretIntoANewParagraph() {
        val bytes = plain()
        val after = splitParagraph(bytes, blockIndex(bytes, "First line"), offset = 5)

        assertEquals(
            listOf("First", " line", "Second line", "Third line"),
            paragraphTexts(after),
        )
        assertEquals(
            "not a character is lost",
            paragraphTexts(bytes).joinToString(""),
            paragraphTexts(after).joinToString(""),
        )
        assertEquals("the untouched paragraphs keep their formatting", snapshot(bytes).blocks.last(), snapshot(after).blocks.last())
    }

    @Test
    fun splitParagraph_keepsTheRunFormattingOfBothHalves() {
        val bytes = docx { doc ->
            doc.createParagraph().also { p ->
                p.createRun().also { r -> r.setText("Bold part"); r.isBold = true }
                p.createRun().also { r -> r.setText(" and plain"); r.isItalic = true }
            }
        }
        val after = splitParagraph(bytes, 0, offset = 9)
        val blocks = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()

        assertEquals(listOf("Bold part", " and plain"), blocks.map { it.text })
        assertTrue("the first half keeps its bold run", blocks[0].runs.any { it.style.bold && it.text == "Bold part" })
        assertTrue("the second half keeps its italic run", blocks[1].runs.any { it.style.italic && it.text == " and plain" })
    }

    @Test
    fun splitParagraph_keepsTheListAndDoesNotInventADefinition() {
        val bytes = lists()
        val definitionsBefore = numberingDefinitionCount(bytes)
        assertTrue("the fixture has numbering definitions", definitionsBefore > 0)
        val index = blockIndex(bytes, "Numbered one")
        val after = splitParagraph(bytes, index, offset = 4)

        val halves = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()
            .filter { it.text == "Numb" || it.text == "ered one" }
        assertEquals("both halves are separate blocks", 2, halves.size)
        assertTrue("both halves are numbered", halves.all { it.props.listType == WordDocument.ListType.NUMBER })
        val ids = XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            doc.paragraphs.filter { it.text == "Numb" || it.text == "ered one" }.map { it.numID }
        }
        assertEquals("the two halves stay in one list", 1, ids.distinct().size)
        assertEquals("no numbering definition was invented", definitionsBefore, numberingDefinitionCount(after))
        assertEquals("the other lists are untouched", WordDocument.ListType.BULLET, listTypeOf(after, "Bullet one"))
        assertEquals("the restarted list keeps its own id", WordDocument.ListType.NUMBER, listTypeOf(after, "Restarted one"))
    }

    @Test
    fun splitParagraphAtTheEnd_addsAnEmptyParagraphAfterTheText() {
        val bytes = plain()
        val after = splitParagraph(bytes, blockIndex(bytes, "First line"), offset = "First line".length)

        assertEquals(listOf("First line", "", "Second line", "Third line"), paragraphTexts(after))
    }

    @Test
    fun splitParagraphWithAPicture_keepsThePictureAndTheTextAroundIt() {
        val bytes = withImages()
        val picturesBefore = pictureCount(bytes)
        val after = splitParagraph(bytes, blockIndex(bytes, "Caption"), offset = 3)

        assertEquals(listOf("Cap", "tion"), paragraphTexts(after).take(2))
        assertEquals("every picture is still in the document", picturesBefore, pictureCount(after))
        assertEquals("the pictures still read back", photos(bytes).size, photos(after).size)
    }

    private fun photos(bytes: ByteArray): List<WordDocument.ImageBlock> =
        WordDocument.read(bytes).blocks.filterIsInstance<WordDocument.ImageBlock>()

    @Test
    fun insertParagraph_takesTheFormattingOfTheNearestParagraph() {
        val bytes = mixed()
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            blockOp = WordDocument.BlockOp.InsertParagraph(after = 0, inheritFrom = 0),
        )
        val blocks = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()
        assertEquals("the new paragraph is empty", "", blocks[1].text)
        assertEquals("it carries the heading style of the paragraph above", WordDocument.Kind.HEADING1, blocks[1].props.kind)
        assertEquals("the heading itself is untouched", "Title", blocks[0].text)

        val top = WordDocument.applyEditsAndSerialize(
            bytes,
            blockOp = WordDocument.BlockOp.InsertParagraph(after = -1, inheritFrom = -1),
        )
        assertEquals("", paragraphTexts(top).first())
        assertEquals(
            "a paragraph at the top takes the document defaults",
            WordDocument.Kind.BODY,
            WordDocument.read(top).blocks.filterIsInstance<WordDocument.ParagraphBlock>().first().props.kind,
        )
    }

    @Test
    fun mergeWithPrevious_joinsTheTextAndKeepsTheFirstParagraphsProperties() {
        val bytes = docx { doc ->
            doc.createParagraph().also { p ->
                p.style = "Heading1"
                p.createRun().setText("Keep")
            }
            doc.createParagraph().also { p -> p.createRun().also { r -> r.setText(" merged"); r.isItalic = true } }
        }
        val after = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.MergeWithPrevious(1))
        val block = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>().single()

        assertEquals("Keep merged", block.text)
        assertEquals("the paragraph that stays keeps its style", WordDocument.Kind.HEADING1, block.props.kind)
        assertTrue("the run that moved keeps its italic", block.runs.any { it.text == " merged" && it.style.italic })
    }

    @Test
    fun mergeWithPrevious_keepsThePictureOfTheParagraphItRemoves() {
        val bytes = docx { doc ->
            doc.createParagraph().createRun().setText("Text")
            doc.createParagraph().also { p -> addPicture(p.createRun(), "merged.png") }
        }
        val after = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.MergeWithPrevious(1))

        assertEquals("the paragraph is gone", listOf("Text"), paragraphTexts(after))
        assertEquals("its picture moved into the paragraph that stayed", 1, pictureCount(after))
    }

    @Test
    fun mergeWithPrevious_refusesWhenTheBlockBeforeIsNotAParagraph() {
        val bytes = withTable()
        val index = blockIndex(bytes, "After table")

        val refused = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.MergeWithPrevious(index))
        }.isFailure

        assertTrue("merging across a table is refused", refused)
    }

    @Test
    fun deleteBlock_removesTheParagraphFromTheDocumentAndKeepsTheRest() {
        val bytes = mixed()
        val before = snapshot(bytes)
        val index = blockIndex(bytes, "Body line")
        val after = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteBlock(index))

        assertFalse("the paragraph is out of the XML", part(after, "word/document.xml").contains("Body line"))
        assertEquals("everything else stays in order", before.blocks.filter { !it.contains("Body line") }, snapshot(after).blocks)
        assertEquals("the table cell text is untouched", "L|", tableGrid(after).first().joinToString("|"))
        assertTrue("the field is still there", part(after, "word/document.xml").contains("PAGE"))
        assertEquals("the pictures are untouched", before.pictures, snapshot(after).pictures)
    }

    @Test
    fun deleteBlock_refusesToLeaveTheBodyEmpty() {
        val bytes = docx { doc -> doc.createParagraph().createRun().setText("Only paragraph") }

        val refused = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteBlock(0))
        }.isFailure

        assertTrue("the last block of a document cannot be removed", refused)
    }

    @Test
    fun aBlockOpWithAStaleIndex_failsInsteadOfChangingTheWrongBlock() {
        val bytes = plain()

        assertTrue(
            "splitting a block that does not exist is refused",
            runCatching { splitParagraph(bytes, 9, 0) }.isFailure,
        )
        assertTrue(
            "deleting a block that does not exist is refused",
            runCatching {
                WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteBlock(5))
            }.isFailure,
        )
    }

    @Test
    fun deletingOnePicture_removesExactlyThatDrawing() {
        val bytes = withTwoDistinctPictures()
        val index = WordDocument.read(bytes).blocks.indexOfFirst { it is WordDocument.ImageBlock }
        val ordinals = WordDocument.read(bytes).blocks.filterIsInstance<WordDocument.ImageBlock>().map { it.ordinal }
        assertEquals("the pictures of the paragraph are numbered in order", listOf(0, 1), ordinals)

        val firstRemoved = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteImage(index, 0))
        assertEquals(1, pictureCount(firstRemoved))
        assertArrayEquals("the second picture is the one that stayed", png2, pictureBytes(firstRemoved).single())
        assertEquals("the caption is untouched", "Caption", paragraphTexts(firstRemoved).first())

        val secondRemoved = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteImage(index, 1))
        assertEquals(1, pictureCount(secondRemoved))
        assertArrayEquals("the first picture is the one that stayed", png, pictureBytes(secondRemoved).single())
    }

    /**
     * The bytes of the pictures the body actually references, in document order. A removal
     * leaves the unused part in the package, so the parts alone cannot say which one is gone.
     */
    private fun pictureBytes(bytes: ByteArray): List<ByteArray> =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.paragraphs.flatMap { p -> p.runs.flatMap { run -> run.embeddedPictures } }
                .mapNotNull { it.pictureData?.data }
        }

    @Test
    fun aPictureThatDoesNotExistIsRefusedInsteadOfRemovingSomethingElse() {
        val bytes = withTwoDistinctPictures()
        val index = WordDocument.read(bytes).blocks.indexOfFirst { it is WordDocument.ImageBlock }

        val refused = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteImage(index, 4))
        }.isFailure

        assertTrue("removing a picture that is not there is refused", refused)
        assertEquals("and nothing was removed", 2, pictureCount(bytes))
    }

    @Test
    fun insertPicture_addsThePartInOrderAndInlinesTheBytes() {
        val bytes = plain()
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            blockOp = WordDocument.BlockOp.InsertImage(
                after = 0,
                bytes = png,
                fileName = "shot.png",
                widthEmu = 40 * 9525,
                heightEmu = 20 * 9525,
            ),
        )

        assertEquals(
            "the picture sits in a paragraph of its own after the caret's block",
            listOf("First line", "", "Second line", "Third line"),
            paragraphTexts(after),
        )
        assertEquals(1, pictureCount(after))
        val stored = pictureBytes(after).single()
        assertArrayEquals("the stored bytes are the picked file", png, stored)
        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val drawing = doc.paragraphs[1].runs.first().getCTR().getDrawingArray(0)
            assertEquals("the picture is inline", 1, drawing.sizeOfInlineArray())
        }
    }

    @Test
    fun insertPicture_keepsUnrelatedContentOfTheDocument() {
        val bytes = withHeaderAndFooter()
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            blockOp = WordDocument.BlockOp.InsertImage(0, png, "shot.png", 40 * 9525, 20 * 9525),
        )
        val snapshotAfter = snapshot(after)

        assertEquals(listOf("Header text"), snapshotAfter.headers)
        assertEquals(listOf("Footer text"), snapshotAfter.footers)
        assertEquals("the paragraph the picture followed is still there", "Body", paragraphTexts(after).first())
    }

    // ---- Phase 5: list levels -------------------------------------------------

    private fun applyProps(bytes: ByteArray, index: Int, update: WordDocument.ParaPropsUpdate): ByteArray =
        WordDocument.applyEditsAndSerialize(bytes, paraProps = mapOf(index to update))

    @Test
    fun listLevel_movesTheItemToARealNumberingLevel() {
        val bytes = lists()
        val index = blockIndex(bytes, "Numbered two")
        val numIdBefore = XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            doc.paragraphs.first { it.text == "Numbered two" }.numID
        }

        val after = applyProps(bytes, index, WordDocument.ParaPropsUpdate(listLevel = 1))
        val block = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()
            .first { it.text == "Numbered two" }

        assertEquals("the level is read back", 1, block.props.listLevel)
        assertEquals("it is still one list item", WordDocument.ListType.NUMBER, block.props.listType)
        val numIdAfter = XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            doc.paragraphs.first { it.text == "Numbered two" }.numID
        }
        assertEquals("the item was not moved to another list", numIdBefore, numIdAfter)
        val ilvl = XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            doc.paragraphs.first { it.text == "Numbered two" }.numIlvl
        }
        assertEquals("the level is written as w:ilvl", 1, ilvl?.toInt())
        assertEquals("the level is real list structure, not indentation", 0, block.props.indentTwips)
        assertEquals(
            "no numbering definition was added or rebuilt",
            numberingDefinitionCount(bytes),
            numberingDefinitionCount(after),
        )
        // Only the paragraph that was moved: the other items, of this list and of the bullet
        // list above it, keep the list and the level they had.
        XWPFDocument(ByteArrayInputStream(after)).use { doc ->
            val others = doc.paragraphs.filter { it.text in listOf("Bullet one", "Bullet two", "Numbered one") }
            val levels = others.map { it.text to (it.numID?.toInt() to it.numIlvl?.toInt()) }
            assertEquals(
                "the other lists are untouched",
                listOf("Bullet one" to (1 to 0), "Bullet two" to (1 to 1), "Numbered one" to (2 to 0)),
                levels,
            )
        }
    }

    @Test
    fun listLevelBeyondTheDefinition_fallsBackToTheDeepestLevelItHas() {
        val bytes = lists()
        val index = blockIndex(bytes, "Numbered one")

        val after = applyProps(bytes, index, WordDocument.ParaPropsUpdate(listLevel = 8))
        val block = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()
            .first { it.text == "Numbered one" }

        assertEquals("the fixture list has two levels", 1, block.props.maxListLevel)
        assertEquals("the item goes to the deepest level there is", 1, block.props.listLevel)
    }

    @Test
    fun indentingAPlainParagraph_stillMovesItsOwnIndentation() {
        val bytes = plain()
        val index = blockIndex(bytes, "Second line")

        val after = applyProps(bytes, index, WordDocument.ParaPropsUpdate(indentTwips = 720))
        val block = WordDocument.read(after).blocks.filterIsInstance<WordDocument.ParagraphBlock>()
            .first { it.text == "Second line" }

        assertEquals("no list was invented for it", WordDocument.ListType.NONE, block.props.listType)
        assertEquals("the indentation is written", 720, block.props.indentTwips)
        assertNull(
            "and no numbering reference was added",
            XWPFDocument(ByteArrayInputStream(after)).use { doc -> doc.paragraphs[1].numID },
        )
    }

    // ---- Phase 5: table structure --------------------------------------------

    private fun tableWithWidths(): ByteArray = docx { doc ->
        doc.createParagraph().createRun().setText("Intro")
        val t = doc.createTable(2, 2)
        t.getRow(0).getCell(0).setText("A1")
        t.getRow(0).getCell(1).setText("B1")
        t.getRow(1).getCell(0).setText("A2")
        t.getRow(1).getCell(1).setText("B2")
        t.getCTTbl().addNewTblGrid().also { grid ->
            grid.addNewGridCol().setW(BigInteger.valueOf(3000))
            grid.addNewGridCol().setW(BigInteger.valueOf(3000))
        }
        t.rows.forEach { row ->
            row.tableCells.forEach { cell ->
                cell.getCTTc().addNewTcPr().addNewTcW().setW(BigInteger.valueOf(3000))
            }
        }
    }

    @Test
    fun deletingATableColumn_keepsTheGridInStepWithTheCells() {
        val bytes = tableWithWidths()
        val index = tableBlockIndex(bytes)
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(index to tableGrid(bytes).map { row -> row.dropLast(1) }),
        )

        assertEquals("the cell is gone from the row", 1, rowCellCount(after, 0))
        assertEquals("the grid description follows", 1, gridColumns(after))
        assertEquals("and keeps the width of the column that stayed", listOf(3000L), gridWidths(after))
    }

    @Test
    fun addingATableColumn_addsAGridColumnAndGivesTheCellItsNeighboursWidth() {
        val bytes = tableWithWidths()
        val index = tableBlockIndex(bytes)
        val after = WordDocument.applyEditsAndSerialize(
            bytes,
            tableEdits = mapOf(index to tableGrid(bytes).map { row -> row + "" }),
        )

        assertEquals("the new cell is in the row", 3, rowCellCount(after, 0))
        assertEquals("the grid describes three columns", 3, gridColumns(after))
        assertEquals("with the width of the column it was added beside", listOf(3000L, 3000L, 3000L), gridWidths(after))
        assertEquals("the new cell carries a width too", 3000L, cellWidth(after, 1, 2))
        assertEquals("the existing cells keep their text", listOf("A1", "B1", ""), tableGrid(after).first())
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

    @Test
    fun aMergedTable_isReadAsMerged() {
        val tables = WordDocument.read(mergedTable()).blocks.filterIsInstance<WordDocument.TableBlock>()
        assertTrue("the span is reported", tables.single().merged)
        assertFalse("a plain table is not", WordDocument.read(withTable()).blocks.filterIsInstance<WordDocument.TableBlock>().single().merged)
    }

    @Test
    fun aMergedTable_keepsItsSpansThroughSavesAndCellEdits() {
        val bytes = mergedTable()
        val index = tableBlockIndex(bytes)
        val untouched = WordDocument.applyEditsAndSerialize(bytes)

        assertTrue("the span survives a save with no edits", xmlCount(untouched, "w:gridSpan") > 0)
        assertTrue("the vertical merge survives too", xmlCount(untouched, "w:vMerge") > 0)

        val edited = editCell(untouched, index, row = 1, col = 1, text = "Plain edited")
        assertTrue("the span survives a text edit", xmlCount(edited, "w:gridSpan") > 0)
        assertTrue("the merge survives a text edit", xmlCount(edited, "w:vMerge") > 0)
        assertEquals("the text landed", "Plain edited", tableGrid(edited).last()[1])
        assertEquals("no grid was invented for the merged table", 0, gridColumns(edited))
        assertEquals("its first row still has its own two cells", 2, rowCellCount(edited, 0))
        assertTrue("the table is still reported as merged", WordDocument.read(edited).blocks.filterIsInstance<WordDocument.TableBlock>().single().merged)
    }

    @Test
    fun aMergedTable_refusesRowAndColumnChanges() {
        val bytes = mergedTable()
        val index = tableBlockIndex(bytes)
        val grid = tableGrid(bytes)

        val wider = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, tableEdits = mapOf(index to grid.map { row -> row + "" }))
        }.isFailure
        val shorter = runCatching {
            WordDocument.applyEditsAndSerialize(bytes, tableEdits = mapOf(index to grid.dropLast(1)))
        }.isFailure

        assertTrue("adding a column to a merged table is refused", wider)
        assertTrue("removing a row from a merged table is refused", shorter)
    }

    // ---- Phase 5: unmodelled content around structural edits ------------------

    @Test
    fun aSplitInADocumentWithTrackedChangesAndContentControls_keepsThemAll() {
        val bytes = withUnmodelledXml()
        val after = splitParagraph(bytes, blockIndex(bytes, "Elsewhere"), offset = 4)
        val xml = part(after, "word/document.xml")

        assertTrue("the tracked insertion survives", xml.contains("<w:ins") && xml.contains("Inserted subject"))
        assertTrue("the tracked deletion survives", xml.contains("<w:del") && xml.contains("Removed subject"))
        assertTrue("the content control survives", xml.contains("<w:sdt>") && xml.contains("Control subject"))
        assertTrue("the split happened", paragraphTexts(after).contains("Else"))
        assertTrue("and the rest of the text is still there", paragraphTexts(after).contains("where"))
    }

    @Test
    fun splittingAParagraphThatEndsASection_doesNotDuplicateTheSectionBreak() {
        val bytes = docx { doc ->
            doc.createParagraph().also { p ->
                p.createRun().setText("Section one")
                val pPr = p.getCTP().getPPr() ?: p.getCTP().addNewPPr()
                pPr.addNewSectPr().addNewPgSz().setW(BigInteger.valueOf(12240))
            }
            doc.createParagraph().createRun().setText("Section two")
        }
        val breaksBefore = xmlCount(bytes, "<w:sectPr")

        val after = splitParagraph(bytes, 0, offset = 7)

        assertEquals("exactly one section break, as before", breaksBefore, xmlCount(after, "<w:sectPr"))
        assertEquals(listOf("Section", " one", "Section two"), paragraphTexts(after))
    }

    @Test
    fun aSplitInADocumentWithPageSetup_keepsThePageSetup() {
        val bytes = withSectionProperties()
        val before = section(bytes)
        val after = splitParagraph(bytes, blockIndex(bytes, "Body"), offset = 2)

        assertEquals("the page setup survives", before, section(after))
        assertTrue("the split happened", paragraphTexts(after).contains("Bo"))
    }

    @Test
    fun deletingAParagraphInADocumentWithAHeaderAndFooter_keepsThem() {
        val bytes = docx { doc ->
            doc.createParagraph().createRun().setText("Body")
            doc.createParagraph().createRun().setText("Extra")
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header text")
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer text")
        }
        val index = blockIndex(bytes, "Extra")

        val saved = WordDocument.applyEditsAndSerialize(bytes, blockOp = WordDocument.BlockOp.DeleteBlock(index))
        val after = snapshot(saved)

        assertEquals(listOf("Header text"), after.headers)
        assertEquals(listOf("Footer text"), after.footers)
        assertEquals("the paragraph that stayed is the only one left", listOf("Body"), paragraphTexts(saved))
        assertFalse("the deleted paragraph is gone", part(saved, "word/document.xml").contains("Extra"))
    }

    private companion object {
        /**
         * A valid 1x1 PNG, generated once and pinned as base64 so fixtures never depend on
         * image tooling. 70 bytes, RGBA, non-interlaced; checked against a real image reader,
         * because bytes that only carry a PNG signature are accepted by the package writer and
         * rejected the moment something tries to draw them.
         */
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        /**
         * A second valid PNG, different from [PNG_1X1] in its dimensions (1x2, red), so a test
         * can tell which of two pictures was removed.
         */
        const val PNG_1X2 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAIAAAAW4yFwAAAADklEQVR4nGP4z8DAAMQACf4B/4PiLjgAAAAASUVORK5CYII="

        val LONG_TEXT = "long paragraph content ".repeat(120).trim()
    }
}
