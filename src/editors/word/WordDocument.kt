package dev.kern.editors.word

import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFFieldRun
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Apache POI bridge for .docx documents (XWPF) with full rich-text, formatting,
 * table editing, and structural block support.
 *
 * Reads the body in element order (paragraphs interleaved with tables and media)
 * so block indices remain stable across all editing operations.
 *
 * Non-destructive round-trip guarantee:
 * - A block index maps back to the exact body element it was read from (see [Parsed]).
 * - Only edited paragraphs' runs and modified paragraph properties are rewritten.
 * - Only edited tables are modified (updating cells or structural rows/cols).
 * - Run content the run model cannot express (pictures, fields, footnote/endnote
 *   references, comment marks, anchored links) is kept in place instead of being
 *   rebuilt, and stays at the character position it occupied when the paragraph's text
 *   is edited, so editing a caption next to a picture deletes nothing and moves nothing.
 * - Hyperlink runs are rewritten as hyperlink runs, keeping their target URL.
 * - Bullet and number formatting applied in the editor gets a real list definition
 *   (abstract numbering plus concrete num); a document's existing lists keep their
 *   definitions and their place in them.
 * - A table cell is rewritten only when the editor changed it, as many paragraphs as the
 *   text has lines, keeping the cell's own formatting and inline content.
 * - Untouched paragraphs, tables, images, headers, footers and styles survive the
 *   round trip. POI re-serializes the package, so the ZIP bytes themselves differ.
 */
object WordDocument {

    /** Paragraph styles, mapped to standard display metrics. */
    enum class Kind { TITLE, HEADING1, HEADING2, BODY }

    /** Text alignment options. */
    enum class Align { START, CENTER, END, JUSTIFY }

    /** List formatting options. */
    enum class ListType { NONE, BULLET, NUMBER }

    /**
     * Character-level formatting for a run. Null fields inherit the paragraph / style default.
     */
    data class RunStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val sizePt: Float? = null,
        val colorHex: String? = null,
        val fontFamily: String? = null,
        val linkUrl: String? = null,
    )

    /** A styled text run. */
    data class Run(val text: String, val style: RunStyle = RunStyle())

    /** Paragraph-level formatting properties. */
    data class ParaProps(
        val kind: Kind = Kind.BODY,
        val styleId: String? = null,
        val align: Align = Align.START,
        val listType: ListType = ListType.NONE,
        val indentTwips: Int = 0,
    )

    /** Patch updates for paragraph properties. */
    data class ParaPropsUpdate(
        val kind: Kind? = null,
        val align: Align? = null,
        val listType: ListType? = null,
        val indentTwips: Int? = null,
    )

    /** Document block interface. */
    sealed interface Block

    /** An editable or rendered body paragraph. */
    data class ParagraphBlock(
        val props: ParaProps,
        val runs: List<Run>,
        val editable: Boolean = true,
    ) : Block {
        val text: String get() = runs.joinToString("") { it.text }
    }

    /**
     * An editable table represented as a 2D grid of cell strings. A cell's paragraphs are
     * the lines of its string, so a cell holding more than one paragraph can be edited.
     */
    data class TableBlock(val rows: List<List<String>>) : Block

    /** An embedded raster image from the document. */
    data class ImageBlock(val bytes: ByteArray, val fileName: String? = null) : Block {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageBlock) return false
            return bytes.contentEquals(other.bytes) && fileName == other.fileName
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + (fileName?.hashCode() ?: 0)
    }

    /** Passthrough for unmodeled XML structures. Never rewritten. */
    data object OpaqueBlock : Block

    /** Block with no body element behind it: the placeholder block of an empty document. */
    private const val UNMAPPED = -1

    /** Parsed document representation.
     *
     * [elementIndices] maps each block back to the index of the body element it was read
     * from — an image block points at the paragraph that holds the picture — so an edit
     * made against a block index is applied to the element that produced that block.
     * Without the mapping, any paragraph carrying an image shifts every later block onto
     * the wrong body element.
     */
    data class Parsed(val blocks: List<Block>, val elementIndices: List<Int>)

    fun read(bytes: ByteArray): Parsed =
        XWPFDocument(ByteArrayInputStream(bytes)).use { readDocument(it) }

    /**
     * The block layout of an already open document, with the body-element index of every
     * block. [applyEditsAndSerialize] resolves its block indices through this on the same
     * document it is about to edit, so a save parses the package once instead of twice.
     */
    internal fun readDocument(doc: XWPFDocument): Parsed {
        val blocks = ArrayList<Block>()
        val elementIndices = ArrayList<Int>()
        doc.bodyElements.forEachIndexed { elementIndex, element ->
            when (element) {
                is XWPFParagraph -> {
                    val images = extractPictures(element)
                    images.forEach {
                        blocks.add(it)
                        elementIndices.add(elementIndex)
                    }
                    val para = readParagraph(element, doc)
                    // A paragraph that only carries pictures has no text to edit, so it
                    // does not get an editable block of its own.
                    if (para.text.isNotEmpty() || images.isEmpty()) {
                        blocks.add(para)
                        elementIndices.add(elementIndex)
                    }
                }
                is XWPFTable -> {
                    blocks.add(readTable(element))
                    elementIndices.add(elementIndex)
                }
                else -> {
                    blocks.add(OpaqueBlock)
                    elementIndices.add(elementIndex)
                }
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(ParagraphBlock(ParaProps(), listOf(Run("")), editable = true))
            elementIndices.add(UNMAPPED)
        }
        return Parsed(blocks, elementIndices)
    }

    /**
     * Applies paragraph edits, property changes, and table modifications to the document.
     */
    fun applyEditsAndSerialize(
        originalBytes: ByteArray,
        edits: Map<Int, List<Run>> = emptyMap(),
        paraProps: Map<Int, ParaPropsUpdate> = emptyMap(),
        tableEdits: Map<Int, List<List<String>>> = emptyMap(),
    ): ByteArray {
        // Resolve every block index through the layout read() produced: pictures and
        // unmodelled elements add or reorder blocks, so a block index is not a body index.
        // Both come from the same parse, which is also the only one this save does.
        XWPFDocument(ByteArrayInputStream(originalBytes)).use { doc ->
            val elementIndices = readDocument(doc).elementIndices
            val elements = doc.bodyElements

            // Apply paragraph run edits and property changes
            val allParaIndices = (edits.keys + paraProps.keys).distinct()
            // Lists this save creates, so every paragraph set to the same list type shares
            // one definition instead of each of them starting a list of its own.
            val createdLists = HashMap<ListType, BigInteger>()
            for (index in allParaIndices) {
                val elementIndex = elementIndices.getOrNull(index)
                    ?: throw IllegalStateException("Paragraph block $index does not exist in this document")
                val paragraph = elements.getOrNull(elementIndex) as? XWPFParagraph
                    ?: throw IllegalStateException("Paragraph block $index does not resolve to a paragraph")
                
                // Update runs if modified
                edits[index]?.let { writeRuns(paragraph, it) }

                // Update paragraph-level properties if modified
                paraProps[index]?.let { update ->
                    update.align?.let { paragraph.alignment = mapAlignment(it) }
                    update.indentTwips?.let { paragraph.indentationLeft = it.coerceAtLeast(0) }
                    update.kind?.let {
                        val styleName = when (it) {
                            Kind.TITLE -> "Title"
                            Kind.HEADING1 -> "Heading1"
                            Kind.HEADING2 -> "Heading2"
                            Kind.BODY -> "Normal"
                        }
                        paragraph.style = styleName
                    }
                    update.listType?.let { listType ->
                        // Re-applying the list a paragraph is already in would move it to a
                        // new num id and restart its numbering, so it is left alone.
                        if (listTypeOf(paragraph, doc) != listType) {
                            when (listType) {
                                ListType.NONE -> paragraph.numID = null
                                ListType.BULLET, ListType.NUMBER -> {
                                    val numId = createdLists.getOrPut(listType) {
                                        createNumberingDefinition(doc, listType)
                                    }
                                    paragraph.setNumID(numId)
                                    paragraph.setNumILvl(BigInteger.ZERO)
                                }
                            }
                        }
                    }
                }
            }

            // Apply table cell and structural edits
            for ((index, newGrid) in tableEdits) {
                val elementIndex = elementIndices.getOrNull(index)
                    ?: throw IllegalStateException("Table block $index does not exist in this document")
                val table = elements.getOrNull(elementIndex) as? XWPFTable
                    ?: throw IllegalStateException("Table block $index does not resolve to a table")
                writeTable(doc, table, newGrid)
            }

            ByteArrayOutputStream().use { out ->
                doc.write(out)
                return out.toByteArray()
            }
        }
    }

    // ---- Read Helpers -------------------------------------------------------

    private fun readParagraph(p: XWPFParagraph, doc: XWPFDocument): ParagraphBlock {
        val runs = ArrayList<Run>()
        for (r in p.runs) {
            // A tracked deletion holds text Word hides behind the revision mark. Reading it
            // would show it as ordinary text and write it back as such on the next edit.
            if (isTrackedDeletion(r)) continue
            // The relationship id is not a URL: resolve the target so a rewrite can re-link.
            // An internal (bookmark) link resolves to null and is then kept as-is on write.
            runs.add(Run(r.text() ?: "", styleOfRun(r, linkUrlOf(r, doc))))
        }
        val merged = collapse(runs).ifEmpty { listOf(Run("")) }
        val align = when (p.alignment) {
            ParagraphAlignment.CENTER -> Align.CENTER
            ParagraphAlignment.RIGHT, ParagraphAlignment.END -> Align.END
            ParagraphAlignment.BOTH, ParagraphAlignment.DISTRIBUTE -> Align.JUSTIFY
            else -> Align.START
        }
        val indent = p.indentationLeft.coerceAtLeast(0)
        val props = ParaProps(
            kind = kindOf(p.style),
            styleId = p.style,
            align = align,
            listType = listTypeOf(p, doc),
            indentTwips = indent,
        )
        return ParagraphBlock(props, merged, editable = true)
    }

    /**
     * The list formatting of a paragraph. `numFmt` answers from the numbering definition, so
     * it needs a definition POI can follow and an item level: a paragraph with a numbering
     * reference but no level falls back to the format of the definition's first level, which
     * is where OOXML puts an item without `w:ilvl`. A document whose reference points at a
     * missing definition makes POI throw while resolving the format
     * [poi verified: XWPFParagraph.getNumFmt()], and a damaged reference must not stop the
     * document from opening; such a paragraph is treated as a numbered item.
     */
    private fun listTypeOf(p: XWPFParagraph, doc: XWPFDocument): ListType {
        val numId = p.numID ?: return ListType.NONE
        val format = try {
            p.numFmt
        } catch (_: Exception) {
            null
        } ?: definitionFormat(doc, numId)
        return if (format.equals("bullet", ignoreCase = true)) ListType.BULLET else ListType.NUMBER
    }

    /** The number format of a list definition's first level, or null when it cannot be read. */
    private fun definitionFormat(doc: XWPFDocument, numId: BigInteger): String? = try {
        val numbering = doc.numbering
        val abstractNumId = numbering?.getNum(numId)?.getCTNum()?.getAbstractNumId()?.getVal()
        numbering?.getAbstractNum(abstractNumId)?.getAbstractNum()
            ?.getLvlArray(0)?.getNumFmt()?.getVal()?.toString()
    } catch (_: Exception) {
        null
    }

    /** The URL behind a hyperlink run; an internal (bookmark) link has no relationship id. */
    private fun linkUrlOf(run: XWPFRun, doc: XWPFDocument): String? =
        (run as? XWPFHyperlinkRun)?.getHyperlinkId()?.let { doc.getHyperlinkByID(it)?.getURL() }

    private fun readTable(t: XWPFTable): TableBlock {
        val rows = t.rows.map { row -> row.tableCells.map(::readCellText) }
        return TableBlock(rows)
    }

    /**
     * The text of a cell: one line per paragraph. POI's own `cell.text` concatenates a cell's
     * paragraphs with nothing between them, which cannot be told apart from a single
     * paragraph, so a two-paragraph cell used to be read as one line and written back into
     * its first paragraph — duplicating the second one.
     */
    private fun readCellText(cell: XWPFTableCell): String =
        cell.paragraphs.joinToString("\n") { it.text ?: "" }

    private fun extractPictures(p: XWPFParagraph): List<ImageBlock> {
        val list = ArrayList<ImageBlock>()
        for (r in p.runs) {
            val pics = r.embeddedPictures ?: continue
            for (pic in pics) {
                val data = pic.pictureData?.data
                if (data != null && data.isNotEmpty()) {
                    list.add(ImageBlock(data, pic.pictureData?.fileName))
                }
            }
        }
        return list
    }

    private fun styleOfRun(r: XWPFRun, linkUrl: String? = null): RunStyle {
        val size = r.fontSizeAsDouble?.takeIf { it > 0.0 }?.toFloat()
        val color = r.color?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }?.uppercase()
        val underlined = (r.underline ?: UnderlinePatterns.NONE) != UnderlinePatterns.NONE
        return RunStyle(
            bold = r.isBold,
            italic = r.isItalic,
            underline = underlined,
            strike = r.isStrikeThrough,
            sizePt = size,
            colorHex = color,
            fontFamily = r.fontFamily,
            linkUrl = linkUrl,
        )
    }

    private fun kindOf(styleId: String?): Kind {
        val s = styleId ?: return Kind.BODY
        return when {
            s.contains("Title", ignoreCase = true) -> Kind.TITLE
            s.contains("Heading1", ignoreCase = true) || s.contains("Heading 1", ignoreCase = true) -> Kind.HEADING1
            s.contains("Heading", ignoreCase = true) -> Kind.HEADING2
            else -> Kind.BODY
        }
    }

    private fun collapse(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.style == run.style) {
                out[out.size - 1] = last.copy(text = last.text + run.text)
            } else {
                out.add(run)
            }
        }
        return out
    }

    // ---- Write Helpers ------------------------------------------------------

    /** Run children the run model can rebuild: run properties and literal text, breaks, tabs. */
    private val modelledRunChildren = setOf("rPr", "t", "br", "cr", "tab")

    /** Children that carry the run's text, dropped from a run that is kept for its content. */
    private val textRunChildren = setOf("t", "br", "cr", "tab")

    /**
     * True for a run inside a tracked change's deletion (`w:del`/`w:moveFrom`): the text is a
     * revision mark, hidden by Word and kept as it is rather than rewritten.
     */
    private fun isTrackedDeletion(run: XWPFRun): Boolean {
        val parent = run.getCTR().domNode?.parentNode?.localName ?: return false
        return parent == "del" || parent == "moveFrom"
    }

    /**
     * True when [run] carries element content the run model cannot rebuild: pictures
     * (inline or floating), simple and complex fields, footnote and endnote references,
     * comment marks, symbols, or an internal (bookmark) hyperlink. Rewriting such a run
     * from the model would delete that content, so the run is kept instead.
     */
    private fun hasUnmodelledContent(run: XWPFRun): Boolean {
        if (run is XWPFFieldRun) return true
        if (run is XWPFHyperlinkRun && run.getHyperlinkId() == null) return true
        if (isTrackedDeletion(run)) return true
        var unmodelled = false
        run.getCTR().newCursor().use { cursor ->
            cursor.selectPath("./*")
            while (!unmodelled && cursor.toNextSelection()) {
                unmodelled = cursor.name?.localPart !in modelledRunChildren
            }
        }
        return unmodelled
    }

    /**
     * Drops the text of a run kept by [hasUnmodelledContent] so the rewritten paragraph
     * does not show that text twice; every other child (picture, field, reference) stays.
     */
    private fun dropTextChildren(run: XWPFRun) {
        var removed = true
        while (removed) {
            removed = false
            run.getCTR().newCursor().use { cursor ->
                cursor.selectPath("./*")
                while (cursor.toNextSelection()) {
                    if (cursor.name?.localPart in textRunChildren) {
                        cursor.removeXml()
                        removed = true
                        // Mutating invalidates the walk's position, so start it over.
                        return@use
                    }
                }
            }
        }
    }

    /**
     * Rewrites the paragraph's text from [runs] while keeping the runs that hold content
     * the model cannot express (pictures, fields, references, internal links) both alive and
     * where they were: each kept run is written back at the character offset it occupied in
     * the paragraph, mapped through the edit, so an edit beside a picture or field leaves it
     * beside the same words instead of moving it to the front of the paragraph.
     */
    private fun writeRuns(p: XWPFParagraph, runs: List<Run>) {
        // The paragraph's run list is a live view while runs are added and removed, so the
        // kept runs and their offsets are collected from a copy first.
        val original = ArrayList(p.runs)
        val kept = ArrayList<Pair<Int, XWPFRun>>()
        val readText = StringBuilder()
        for (run in original) {
            if (hasUnmodelledContent(run)) kept.add(readText.length to run)
            // The offsets are the ones the model read the paragraph at, so a tracked
            // deletion (whose text the model does not read) must not shift them.
            if (!isTrackedDeletion(run)) readText.append(run.text() ?: "")
        }
        val oldText = readText.toString()

        for (i in original.indices.reversed()) {
            if (hasUnmodelledContent(original[i])) continue
            p.removeRun(i)
        }
        kept.forEach { if (!isTrackedDeletion(it.second)) dropTextChildren(it.second) }

        val effective = if (runs.isEmpty()) listOf(Run("")) else runs
        val newText = effective.joinToString("") { it.text }
        var cursor = 0
        for ((anchor, keptRun) in kept) {
            val at = mapAnchor(anchor, oldText, newText).coerceIn(cursor, newText.length)
            writeSegment(p, effective, cursor, at, keptRun)
            cursor = at
        }
        writeSegment(p, effective, cursor, newText.length, null)
    }

    /**
     * Writes the runs covering [from]..[to) of the paragraph's text, in front of [anchor]
     * (or at the end of the paragraph when [anchor] is null).
     */
    private fun writeSegment(p: XWPFParagraph, runs: List<Run>, from: Int, to: Int, anchor: XWPFRun?) {
        if (to <= from) return
        for (run in sliceRuns(runs, from, to)) {
            val url = run.style.linkUrl
            val index = if (anchor != null) p.runs.indexOf(anchor) else -1
            // A linked run has to be rewritten as a link, or the URL is lost on the next save.
            val r = when {
                index < 0 && url != null -> p.createHyperlinkRun(url)
                index < 0 -> p.createRun()
                url != null -> p.insertNewHyperlinkRun(index, url)
                else -> p.insertNewRun(index)
            } ?: p.createRun()
            applyStyle(r, run.style)
            writeRunText(r, run.text)
        }
    }

    /**
     * Where a kept run's character offset lands in the edited text. Text inserted exactly at
     * the offset stays in front of the run (the caret was beside it, as in Word), text after
     * the offset keeps its distance, and an offset inside replaced text moves to the end of
     * the replacement so the run is never left in the middle of a word.
     */
    private fun mapAnchor(anchor: Int, oldText: String, newText: String): Int {
        val prefix = commonPrefix(oldText, newText)
        val suffix = commonSuffix(oldText, newText, prefix)
        val oldTail = oldText.length - suffix
        val newTail = newText.length - suffix
        return when {
            anchor < prefix -> anchor
            anchor >= oldTail -> newTail + (anchor - oldTail)
            else -> newTail
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffix(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length - prefix, b.length - prefix)
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /** The part of [runs] between [from] and [to], split at those offsets. */
    internal fun sliceRuns(runs: List<Run>, from: Int, to: Int): List<Run> {
        if (to <= from) return emptyList()
        val out = ArrayList<Run>()
        var pos = 0
        for (run in runs) {
            val start = pos
            val end = pos + run.text.length
            pos = end
            if (end <= from || start >= to) continue
            val a = (from - start).coerceIn(0, run.text.length)
            val b = (to - start).coerceIn(0, run.text.length)
            if (b > a) out.add(run.copy(text = run.text.substring(a, b)))
        }
        return out
    }

    /** Writes [text] into [r], turning line breaks into breaks and tabs into tab stops. */
    private fun writeRunText(r: XWPFRun, text: String) {
        text.split("\n").forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) r.addBreak()
            line.split("\t").forEachIndexed { tabIndex, part ->
                if (tabIndex > 0) r.addTab()
                r.setText(part)
            }
        }
    }

    private fun writeTable(doc: XWPFDocument, table: XWPFTable, newGrid: List<List<String>>) {
        val targetRowCount = newGrid.size
        while (table.rows.size < targetRowCount) {
            table.createRow()
        }
        while (table.rows.size > targetRowCount && table.rows.size > 1) {
            table.removeRow(table.rows.size - 1)
        }

        for (r in newGrid.indices) {
            val row = table.getRow(r) ?: continue
            val targetColCount = newGrid[r].size
            while (row.tableCells.size < targetColCount) {
                row.addNewTableCell()
            }
            // A column deleted in the editor has to leave the XML too, or the saved table
            // keeps its old width with a stale cell at the end.
            while (row.tableCells.size > targetColCount) {
                row.removeCell(row.tableCells.size - 1)
            }
            for (c in newGrid[r].indices) {
                val cell = row.getCell(c) ?: continue
                val text = newGrid[r][c]
                // A cell the editor did not touch is left exactly as it was, formatting and
                // inline content (pictures, fields) included.
                if (readCellText(cell) == text) continue
                writeCell(doc, cell, text)
            }
        }
    }

    /**
     * Replaces a cell's text, keeping what the grid model cannot express: the cell keeps one
     * paragraph per line of its text, its inline content stays in place, and the rewritten
     * text keeps the formatting of the text it replaces.
     */
    private fun writeCell(doc: XWPFDocument, cell: XWPFTableCell, text: String) {
        val lines = text.split("\n")
        while (cell.paragraphs.size < lines.size) {
            cell.addParagraph()
        }
        while (cell.paragraphs.size > lines.size && cell.paragraphs.size > 1) {
            cell.removeParagraph(cell.paragraphs.size - 1)
        }
        lines.forEachIndexed { line, paragraph ->
            val p = cell.paragraphs.getOrNull(line) ?: return@forEachIndexed
            writeRuns(p, listOf(Run(paragraph, cellBaseStyle(doc, p))))
        }
    }

    /**
     * The look a rewritten cell keeps: the formatting of the cell's own text. A link that
     * covered the whole cell stays a link; a partly linked cell is written as plain text,
     * because the grid model cannot say which of its words were linked.
     */
    private fun cellBaseStyle(doc: XWPFDocument, p: XWPFParagraph): RunStyle {
        val runs = p.runs.filter { !it.text().isNullOrEmpty() }
        val first = runs.firstOrNull() ?: return RunStyle()
        val url = linkUrlOf(first, doc)
        val wholeCellLinked = url != null && runs.all { linkUrlOf(it, doc) == url }
        val style = styleOfRun(first, url)
        return if (wholeCellLinked) style else style.copy(linkUrl = null)
    }

    private fun applyStyle(r: XWPFRun, s: RunStyle) {
        if (s.bold) r.isBold = true
        if (s.italic) r.isItalic = true
        if (s.underline) r.underline = UnderlinePatterns.SINGLE
        if (s.strike) r.isStrikeThrough = true
        s.sizePt?.let { r.setFontSize(it.toDouble()) }
        s.colorHex?.let { r.setColor(it) }
        s.fontFamily?.let { r.setFontFamily(it) }
    }

    private fun mapAlignment(align: Align): ParagraphAlignment = when (align) {
        Align.START -> ParagraphAlignment.LEFT
        Align.CENTER -> ParagraphAlignment.CENTER
        Align.END -> ParagraphAlignment.RIGHT
        Align.JUSTIFY -> ParagraphAlignment.BOTH
    }

    /**
     * A real list definition for [type]: an abstract numbering with three levels plus the
     * concrete num paragraphs reference. Applying a list used to create only an empty
     * numbering part, so the paragraph pointed at a num id that did not exist and the list
     * rendered as plain text. The ids are allocated above the document's own, so existing
     * lists keep both their definitions and their paragraphs.
     */
    private fun createNumberingDefinition(doc: XWPFDocument, type: ListType): BigInteger {
        val numbering = doc.numbering ?: doc.createNumbering()
        // Ids above everything the document already uses, so an existing list keeps its own
        // definition and a num id POI would otherwise pick from a count cannot collide.
        val lastAbstractId = numbering.getAbstractNums()
            .maxOfOrNull { it.abstractNum.abstractNumId?.longValue() ?: -1L } ?: -1L
        val lastNumId = numbering.getNums()
            .maxOfOrNull { it.ctNum.numId?.longValue() ?: 0L } ?: 0L
        val abstractId = BigInteger.valueOf(lastAbstractId + 1)
        val numId = BigInteger.valueOf(lastNumId + 1)

        val abstractNum = CTAbstractNum.Factory.newInstance()
        abstractNum.setAbstractNumId(abstractId)
        for (level in 0 until 3) {
            val lvl = abstractNum.addNewLvl()
            lvl.setIlvl(BigInteger.valueOf(level.toLong()))
            lvl.addNewStart().setVal(BigInteger.ONE)
            lvl.addNewNumFmt().setVal(
                if (type == ListType.BULLET) STNumberFormat.BULLET else STNumberFormat.DECIMAL,
            )
            lvl.addNewLvlText().setVal(
                if (type == ListType.BULLET) BULLET_MARKS[level] else "%${level + 1}.",
            )
            lvl.addNewPPr().addNewInd().setLeft(BigInteger.valueOf(720L * (level + 1)))
        }

        numbering.addAbstractNum(XWPFAbstractNum(abstractNum))
        numbering.addNum(abstractId, numId)
        return numId
    }

    private val BULLET_MARKS = listOf("\u2022", "o", "\u25AA")
}
