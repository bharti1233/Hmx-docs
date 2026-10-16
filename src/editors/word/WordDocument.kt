package dev.kern.editors.word

import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFFieldRun
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
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
 *   rebuilt, so editing a caption never deletes the picture or field beside it.
 * - Hyperlink runs are rewritten as hyperlink runs, keeping their target URL.
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

    /** An editable table represented as a 2D grid of cell strings. */
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

    fun read(bytes: ByteArray): Parsed {
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
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
        val elementIndices = read(originalBytes).elementIndices
        XWPFDocument(ByteArrayInputStream(originalBytes)).use { doc ->
            val elements = doc.bodyElements

            // Apply paragraph run edits and property changes
            val allParaIndices = (edits.keys + paraProps.keys).distinct()
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
                        when (listType) {
                            ListType.NONE -> paragraph.numID = null
                            ListType.BULLET -> {
                                ensureNumbering(doc)
                                paragraph.setNumID(BigInteger.valueOf(1))
                                paragraph.setNumILvl(BigInteger.ZERO)
                            }
                            ListType.NUMBER -> {
                                ensureNumbering(doc)
                                paragraph.setNumID(BigInteger.valueOf(2))
                                paragraph.setNumILvl(BigInteger.ZERO)
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
                writeTable(table, newGrid)
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
            // The relationship id is not a URL: resolve the target so a rewrite can re-link.
            // An internal (bookmark) link resolves to null and is then kept as-is on write.
            val linkUrl = (r as? XWPFHyperlinkRun)?.getHyperlinkId()
                ?.let { doc.getHyperlinkByID(it)?.getURL() }
            runs.add(Run(r.text() ?: "", styleOfRun(r, linkUrl)))
        }
        val merged = collapse(runs).ifEmpty { listOf(Run("")) }
        val align = when (p.alignment) {
            ParagraphAlignment.CENTER -> Align.CENTER
            ParagraphAlignment.RIGHT, ParagraphAlignment.END -> Align.END
            ParagraphAlignment.BOTH, ParagraphAlignment.DISTRIBUTE -> Align.JUSTIFY
            else -> Align.START
        }
        val listType = when {
            p.numID == null -> ListType.NONE
            p.numFmt.equals("bullet", ignoreCase = true) -> ListType.BULLET
            else -> ListType.NUMBER
        }
        val indent = p.indentationLeft.coerceAtLeast(0)
        val props = ParaProps(
            kind = kindOf(p.style),
            styleId = p.style,
            align = align,
            listType = listType,
            indentTwips = indent,
        )
        return ParagraphBlock(props, merged, editable = true)
    }

    private fun readTable(t: XWPFTable): TableBlock {
        val rows = t.rows.map { row ->
            row.tableCells.map { cell -> cell.text ?: "" }
        }
        return TableBlock(rows)
    }

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
     * True when [run] carries element content the run model cannot rebuild: pictures
     * (inline or floating), simple and complex fields, footnote and endnote references,
     * comment marks, symbols, or an internal (bookmark) hyperlink. Rewriting such a run
     * from the model would delete that content, so the run is kept instead.
     */
    private fun hasUnmodelledContent(run: XWPFRun): Boolean {
        if (run is XWPFFieldRun) return true
        if (run is XWPFHyperlinkRun && run.getHyperlinkId() == null) return true
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

    private fun writeRuns(p: XWPFParagraph, runs: List<Run>) {
        // Keep the runs holding content the model cannot express: rebuilding them would
        // silently delete the picture, field or footnote reference beside the edited text.
        val preserved = p.runs.filter(::hasUnmodelledContent)
        for (i in p.runs.indices.reversed()) {
            if (p.runs[i] in preserved) continue
            p.removeRun(i)
        }
        preserved.forEach(::dropTextChildren)

        val effective = if (runs.isEmpty()) listOf(Run("")) else runs
        for (run in effective) {
            // A linked run has to be rewritten as a link, or the URL is lost on the next save.
            val r = run.style.linkUrl?.let { p.createHyperlinkRun(it) } ?: p.createRun()
            applyStyle(r, run.style)
            writeRunText(r, run.text)
        }
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

    private fun writeTable(table: XWPFTable, newGrid: List<List<String>>) {
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
                if (cell.paragraphs.isEmpty()) {
                    cell.addParagraph().createRun().setText(text)
                } else {
                    val p = cell.paragraphs[0]
                    for (i in p.runs.indices.reversed()) p.removeRun(i)
                    p.createRun().setText(text)
                }
            }
        }
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

    private fun ensureNumbering(doc: XWPFDocument) {
        try {
            if (doc.numbering == null) {
                doc.createNumbering()
            }
        } catch (_: Exception) {
            // Ignore numbering creation if already present or unsupported in isolated doc
        }
    }
}
