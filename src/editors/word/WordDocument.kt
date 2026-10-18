package dev.kern.editors.word

import org.apache.poi.common.usermodel.PictureType
import org.apache.poi.xwpf.usermodel.IBodyElement
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
import org.apache.poi.xwpf.usermodel.XWPFTableRow
import org.apache.xmlbeans.XmlCursor
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        /** The `w:ilvl` of a numbered or bulleted paragraph: 0 is the outermost level. */
        val listLevel: Int = 0,
        /** The highest list level the paragraph's own numbering definition offers; 0 when there is none. */
        val maxListLevel: Int = 0,
        val indentTwips: Int = 0,
    )

    /** Patch updates for paragraph properties. */
    data class ParaPropsUpdate(
        val kind: Kind? = null,
        val align: Align? = null,
        val listType: ListType? = null,
        val listLevel: Int? = null,
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
     *
     * [merged] marks a table whose cells span columns or are merged vertically. The flat
     * grid cannot describe that, and rewriting a grid from it would drop the spans, so such
     * a table can only be edited cell by cell (the structure is left alone).
     */
    data class TableBlock(val rows: List<List<String>>, val merged: Boolean = false) : Block

    /**
     * An embedded raster image from the document. [ordinal] is the picture's position among
     * the pictures of the paragraph it lives in, which is how a single one of them can be
     * addressed when the editor removes it.
     */
    data class ImageBlock(val bytes: ByteArray, val fileName: String? = null, val ordinal: Int = 0) : Block {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageBlock) return false
            return bytes.contentEquals(other.bytes) && fileName == other.fileName && ordinal == other.ordinal
        }

        override fun hashCode(): Int =
            (31 * bytes.contentHashCode() + (fileName?.hashCode() ?: 0)) * 31 + ordinal
    }

    /** Passthrough for unmodeled XML structures. Never rewritten. */
    data object OpaqueBlock : Block

    /**
     * One block-level change, applied to the document in the same pass as the text edits of a
     * save. The result is read back into the model, so no index has to survive a structural
     * change and no operation is ever replayed twice; the indices name the layout that was
     * parsed, with content edits of the same pass already applied.
     */
    sealed interface BlockOp {

        /**
         * Split the paragraph block at [at] at character [offset]. The two halves keep the
         * paragraph's formatting (including its list), the text before the caret stays in the
         * original element, and the text from [offset] on moves into a new paragraph.
         */
        data class SplitParagraph(val at: Int, val offset: Int) : BlockOp

        /**
         * Insert an empty paragraph after block [after], carrying the formatting of block
         * [inheritFrom] (-1 for the document defaults). [after] of -1 inserts at the top.
         */
        data class InsertParagraph(val after: Int, val inheritFrom: Int = after) : BlockOp

        /**
         * Append the runs of block [at] to the paragraph before it and remove the block; the
         * properties of the first paragraph stay in force, as when a paragraph mark is deleted.
         */
        data class MergeWithPrevious(val at: Int) : BlockOp

        /** Remove block [at] (and, for a paragraph, everything inline in it). */
        data class DeleteBlock(val at: Int) : BlockOp

        /** Remove drawing [ordinal] of the paragraph behind block [at]. */
        data class DeleteImage(val at: Int, val ordinal: Int) : BlockOp

        /**
         * Insert an inline picture after block [after] (-1 for the top), sized
         * [widthEmu] x [heightEmu]; the picture data becomes a package part of its own.
         */
        data class InsertImage(
            val after: Int,
            val bytes: ByteArray,
            val fileName: String?,
            val widthEmu: Int,
            val heightEmu: Int,
        ) : BlockOp
    }

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

    /**
     * What the bytes turned out to be when they could not be read as a Word document.
     * Decided from the content itself, never from a file name or a provider's MIME type:
     * an "Open with" hand-off can name a Word format the bytes are not.
     */
    enum class UnreadableKind {
        /** No bytes at all: an empty file, or a provider that returned nothing. */
        EMPTY,

        /** The OLE2 container of Word 97-2003: a .doc, which this document model cannot read. */
        LEGACY_DOC,

        /** Not a package at all (text, RTF, PDF, ...), so not a document this editor can read. */
        NOT_WORD,

        /** An OOXML package the parser refused: a truncation, a missing part, damaged XML. */
        DAMAGED,
    }

    /**
     * Bytes [read] refused: [kind] is what they actually are, [userMessage] says what the
     * user can act on, and [canRetry] is false when re-reading cannot change the outcome
     * because the bytes — not the access to them — are the problem. The parser's own
     * failure stays as the cause, so diagnostics keep the exception chain.
     */
    class UnreadableContent(
        val kind: UnreadableKind,
        val userMessage: String,
        val canRetry: Boolean,
        cause: Throwable,
    ) : IOException(userMessage, cause)

    fun read(bytes: ByteArray): Parsed =
        try {
            XWPFDocument(ByteArrayInputStream(bytes)).use { readDocument(it) }
        } catch (failure: Exception) {
            // An Error (out of memory on a huge package) is not a statement about the
            // bytes; it falls through to the caller's generic failure handling.
            throw unreadableContent(bytes, failure)
        }

    /**
     * Classifies a parser refusal by what the bytes are: the four container signatures are
     * cheap to check, so a legacy .doc, a file that never came through, or a truncation gets
     * a message that names the problem instead of the same generic failure for all of them.
     */
    private fun unreadableContent(bytes: ByteArray, failure: Exception): UnreadableContent =
        when {
            bytes.isEmpty() -> UnreadableContent(
                UnreadableKind.EMPTY,
                "The file came back empty. It may still be downloading, or it may hold no content.",
                canRetry = true,
                cause = failure,
            )
            bytes.hasSignature(0xD0, 0xCF, 0x11, 0xE0) -> UnreadableContent(
                UnreadableKind.LEGACY_DOC,
                "This is an older Word file (.doc). This editor opens .docx documents.",
                canRetry = false,
                cause = failure,
            )
            bytes.hasSignature(0x50, 0x4B) -> UnreadableContent(
                UnreadableKind.DAMAGED,
                "This document could not be read. It may be damaged or not fully downloaded.",
                canRetry = true,
                cause = failure,
            )
            else -> UnreadableContent(
                UnreadableKind.NOT_WORD,
                "This file is not a .docx document.",
                canRetry = false,
                cause = failure,
            )
        }

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
        blockOp: BlockOp? = null,
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
                    // After the list reference, so a paragraph that was just put into a list
                    // can also be moved to one of its levels in the same pass.
                    update.listLevel?.let { level -> writeListLevel(doc, paragraph, level) }
                }
            }

            // Apply table cell and structural edits
            for ((index, newGrid) in tableEdits) {
                val elementIndex = elementIndices.getOrNull(index)
                    ?: throw IllegalStateException("Table block $index does not exist in this document")
                val table = elements.getOrNull(elementIndex) as? XWPFTable
                    ?: throw IllegalStateException("Table block $index does not resolve to a table")
                // A merged cell is a span, which a flat grid of strings cannot put back. Text
                // edits keep the shape and go through; a row or column change is refused
                // rather than written over the spans.
                if (hasMergedCells(table) && !sameShape(table, newGrid)) {
                    throw IllegalStateException("A table with merged cells cannot have rows or columns changed")
                }
                writeTable(doc, table, newGrid)
            }

            // A block-level change comes last: it sees the text and property edits above,
            // which is what makes an Enter press split the paragraph at the caret's text.
            blockOp?.let { applyBlockOp(doc, elements, elementIndices, edits, it) }

            ByteArrayOutputStream().use { out ->
                doc.write(out)
                return out.toByteArray()
            }
        }
    }

    // ---- Block-Level Operations ---------------------------------------------

    /**
     * Applies one block-level change to the open document. It runs after the text and property
     * edits of the same pass, so a split at a caret offset sees the text as edited.
     *
     * The document is serialized straight after, so the work is done through the objects of
     * this parse; the caller re-reads the bytes and no index has to survive the change.
     */
    private fun applyBlockOp(
        doc: XWPFDocument,
        elements: List<IBodyElement>,
        elementIndices: List<Int>,
        edits: Map<Int, List<Run>>,
        op: BlockOp,
    ) {
        val objects = bodyObjects(doc)

        fun elementIndexOf(block: Int): Int = elementIndices.getOrNull(block)
            ?: throw IllegalStateException("Block $block does not exist in this document")

        fun paragraphAt(block: Int): XWPFParagraph {
            val elementIndex = elementIndexOf(block)
            return elements.getOrNull(elementIndex) as? XWPFParagraph
                ?: throw IllegalStateException("Block $block does not resolve to a paragraph")
        }

        when (op) {
            is BlockOp.SplitParagraph -> {
                val paragraph = paragraphAt(op.at)
                val runs = edits[op.at] ?: readParagraph(paragraph, doc).runs
                val length = runs.sumOf { it.text.length }
                val offset = op.offset.coerceIn(0, length)
                val added = insertParagraph(doc, objects, elementIndexOf(op.at))
                copyParagraphProperties(paragraph, added)
                writeRuns(paragraph, sliceRuns(runs, 0, offset))
                writeRuns(added, sliceRuns(runs, offset, length))
            }

            is BlockOp.InsertParagraph -> {
                val added = insertParagraph(doc, objects, if (op.after < 0) -1 else elementIndexOf(op.after))
                val source = elementIndices.getOrNull(op.inheritFrom)
                    ?.takeIf { op.inheritFrom >= 0 }
                    ?.let { elements.getOrNull(it) as? XWPFParagraph }
                if (source != null) copyParagraphProperties(source, added)
            }

            is BlockOp.MergeWithPrevious -> {
                val paragraph = paragraphAt(op.at)
                val previous = paragraphAt(op.at - 1)
                val previousRuns = edits[op.at - 1] ?: readParagraph(previous, doc).runs
                val runs = edits[op.at] ?: readParagraph(paragraph, doc).runs
                writeRuns(previous, previousRuns + runs)
                // What the editor cannot write as text (a picture, a field) would go down with
                // the paragraph mark, so it is moved into the paragraph that survives.
                moveUnmodelledRuns(paragraph, previous)
                removeElement(doc, elementIndexOf(op.at))
            }

            is BlockOp.DeleteBlock -> removeElement(doc, elementIndexOf(op.at))

            is BlockOp.DeleteImage -> {
                val run = drawingsOf(paragraphAt(op.at)).getOrNull(op.ordinal)
                    ?: throw IllegalStateException("Picture ${op.ordinal} does not exist in block ${op.at}")
                run.first.getCTR()?.removeDrawing(run.second)
            }

            is BlockOp.InsertImage -> {
                if (op.bytes.isEmpty()) throw IllegalStateException("The picture has no data")
                val type = pictureTypeOf(op.bytes)
                    ?: throw IllegalStateException("That file is not a picture this editor can place")
                val holder = insertParagraph(doc, objects, if (op.after < 0) -1 else elementIndexOf(op.after))
                holder.createRun().addPicture(
                    ByteArrayInputStream(op.bytes),
                    type,
                    op.fileName?.takeIf { it.isNotBlank() } ?: "image",
                    op.widthEmu.coerceAtLeast(1),
                    op.heightEmu.coerceAtLeast(1),
                )
            }
        }
    }

    /**
     * The body's element objects in document order, matching [XWPFDocument.getBodyElements]:
     * paragraphs, tables and content controls. The body's trailing section properties are not
     * a body element and are left out, so both lists line up index for index.
     */
    private fun bodyObjects(doc: XWPFDocument): List<XmlObject> {
        val objects = ArrayList<XmlObject>()
        doc.getDocument().getBody().newCursor().use { cursor ->
            cursor.selectPath("./*")
            while (cursor.toNextSelection()) {
                val child = cursor.getObject()
                if (child is CTP || child is CTTbl || child is CTSdtBlock) objects.add(child)
            }
        }
        return objects
    }

    /**
     * Inserts an empty paragraph into the body: in front of the body element after
     * [elementIndex], in front of the trailing section properties when that element was the
     * last one, and at the end of the body when it has neither. -1 inserts at the very top.
     * The section properties must stay the body's last child, or Word sees a damaged document.
     */
    private fun insertParagraph(doc: XWPFDocument, objects: List<XmlObject>, elementIndex: Int): XWPFParagraph {
        val body = doc.getDocument().getBody()
        val anchor = if (elementIndex < 0) {
            objects.firstOrNull() ?: body.getSectPr()
        } else {
            objects.getOrNull(elementIndex + 1) ?: body.getSectPr()
        }
        return if (anchor != null) doc.insertNewParagraph(anchor.newCursor()) else doc.createParagraph()
    }

    /**
     * Copies the paragraph properties an inserted or split paragraph inherits: style,
     * alignment, indentation and the list the paragraph is part of, so a list item stays in
     * its list with the same numbering. A paragraph-level section break is left out of the
     * copy — two paragraphs claiming to end a section is not a structure Word expects, and
     * the paragraph that had the break keeps it.
     */
    private fun copyParagraphProperties(from: XWPFParagraph, to: XWPFParagraph) {
        val source = from.getCTP().getPPr() ?: return
        val copy = source.copy() as? CTPPr ?: return
        // Unsetting an element that is not set throws in XmlBeans on a detached copy.
        if (copy.isSetSectPr()) copy.unsetSectPr()
        to.getCTP().setPPr(copy)
    }

    /**
     * Moves the runs holding content the run model does not own (pictures, fields, references)
     * from one paragraph to the end of another. A merge that deleted them would destroy content
     * the editor never showed.
     */
    private fun moveUnmodelledRuns(from: XWPFParagraph, to: XWPFParagraph) {
        val moving = from.runs.filter(::hasUnmodelledContent)
        if (moving.isEmpty()) return
        to.getCTP().newCursor().use { target ->
            target.toEndToken()
            for (run in moving) {
                run.getCTR()?.newCursor()?.use { cursor -> cursor.moveXml(target) }
            }
        }
    }

    /** The paragraph's drawings as (run, index within that run) pairs, in run order. */
    private fun drawingsOf(p: XWPFParagraph): List<Pair<XWPFRun, Int>> {
        val drawings = ArrayList<Pair<XWPFRun, Int>>()
        for (run in p.runs) {
            val count = run.getCTR()?.sizeOfDrawingArray() ?: 0
            for (index in 0 until count) drawings.add(run to index)
        }
        return drawings
    }

    /**
     * Removes one body element. The last block of a document is kept: a body without any
     * block-level content is not a document Word will open.
     */
    private fun removeElement(doc: XWPFDocument, elementIndex: Int) {
        if (doc.bodyElements.size <= 1) {
            throw IllegalStateException("The last block of a document cannot be removed")
        }
        if (!doc.removeBodyElement(elementIndex)) {
            throw IllegalStateException("Block $elementIndex could not be removed")
        }
    }

    /**
     * The OOXML picture type of an image, taken from its file signature, or null when the bytes
     * are not one of the formats this editor stores. The type decides the content type and the
     * extension of the package part the bytes are stored in, so the bytes decide it, not the
     * name the file happened to be picked under.
     */
    private fun pictureTypeOf(bytes: ByteArray): PictureType? = when {
        bytes.hasSignature(0x89, 0x50, 0x4E, 0x47) -> PictureType.PNG
        bytes.hasSignature(0xFF, 0xD8, 0xFF) -> PictureType.JPEG
        bytes.hasSignature(0x47, 0x49, 0x46, 0x38) -> PictureType.GIF
        bytes.hasSignature(0x42, 0x4D) -> PictureType.BMP
        // Nothing else: a file name can lie, and storing bytes under a format they are not
        // would put a picture Word cannot render into the document.
        else -> null
    }

    private fun ByteArray.hasSignature(vararg signature: Int): Boolean =
        size >= signature.size && signature.indices.all { (this[it].toInt() and 0xFF) == signature[it] }

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
            listLevel = listLevelOf(p),
            maxListLevel = maxListLevelOf(p, doc),
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

    /** The list levels a numbering definition actually defines, ascending; empty when unreadable. */
    private fun definitionLevels(doc: XWPFDocument, numId: BigInteger): List<Int> = try {
        val numbering = doc.numbering
        val abstractNumId = numbering?.getNum(numId)?.getCTNum()?.getAbstractNumId()?.getVal()
        numbering?.getAbstractNum(abstractNumId)?.getAbstractNum()?.getLvlList()
            ?.mapIndexed { index, lvl -> (lvl.getIlvl() as? Number)?.toInt() ?: index }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** `w:ilvl` of a paragraph's numbering reference, 0 when it is absent or unreadable. */
    private fun listLevelOf(p: XWPFParagraph): Int {
        val value = p.getCTP().getPPr()?.getNumPr()?.getIlvl()?.getVal() as? Number ?: return 0
        return value.toInt().coerceIn(0, MAX_LIST_LEVEL)
    }

    /** The deepest level the paragraph's own list offers, 0 when it is not in a readable list. */
    private fun maxListLevelOf(p: XWPFParagraph, doc: XWPFDocument): Int {
        val numId = p.numID ?: return 0
        return definitionLevels(doc, numId).maxOrNull()?.coerceAtMost(MAX_LIST_LEVEL) ?: 0
    }

    /**
     * Moves a list paragraph to another numbering level. The requested level is used when the
     * paragraph's own definition has it; a definition without that level falls back to the
     * deepest one it does have, so a paragraph can never point at a level that does not
     * exist. A paragraph with no numbering reference is left alone: a level without a list
     * means nothing in OOXML.
     */
    private fun writeListLevel(doc: XWPFDocument, p: XWPFParagraph, level: Int) {
        val numId = p.numID ?: return
        val available = definitionLevels(doc, numId)
        if (available.isEmpty()) return
        val target = when {
            level in available -> level
            available.any { it <= level } -> available.filter { it <= level }.max()
            else -> available.min()
        }
        p.setNumILvl(BigInteger.valueOf(target.toLong().coerceIn(0, MAX_LIST_LEVEL.toLong())))
    }

    /** The URL behind a hyperlink run; an internal (bookmark) link has no relationship id. */
    private fun linkUrlOf(run: XWPFRun, doc: XWPFDocument): String? =
        (run as? XWPFHyperlinkRun)?.getHyperlinkId()?.let { doc.getHyperlinkByID(it)?.getURL() }

    private fun readTable(t: XWPFTable): TableBlock {
        val rows = t.rows.map { row -> row.tableCells.map(::readCellText) }
        return TableBlock(rows, merged = hasMergedCells(t))
    }

    /**
     * Whether any cell of the table spans columns or takes part in a vertical merge. The grid
     * model is flat, so a structural edit of such a table would replace the merged cells with
     * ordinary ones; the table is then edited cell by cell only.
     */
    private fun hasMergedCells(t: XWPFTable): Boolean = t.rows.any { row ->
        row.tableCells.any { cell ->
            val pr = cell.getCTTc().getTcPr()
            val span = (pr?.getGridSpan()?.getVal() as? Number)?.toInt() ?: 1
            span > 1 || pr?.isSetVMerge() == true
        }
    }

    /** Whether a grid still has the same rows with the same number of cells per row as the table. */
    private fun sameShape(table: XWPFTable, grid: List<List<String>>): Boolean {
        if (table.rows.size != grid.size) return false
        return table.rows.withIndex().all { (index, row) -> row.tableCells.size == grid[index].size }
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
                    // The ordinal counts the paragraph's drawings in run order, the same order
                    // POI reads them in, so removing one addresses exactly the picture shown.
                    list.add(ImageBlock(data, pic.pictureData?.fileName, ordinal = list.size))
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
        val columnsBefore = table.rows.firstOrNull()?.tableCells?.size ?: 0
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
                copyCellProperties(row, row.tableCells.size - 1)
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

        // A column inserted or deleted in the editor also changes how many columns the table's
        // grid definition describes. Left stale, the saved table keeps the old column widths
        // and Word lays the new column out over a grid that no longer matches its cells.
        val columnsAfter = table.rows.firstOrNull()?.tableCells?.size ?: 0
        if (columnsAfter != columnsBefore) syncGrid(table, columnsAfter)
    }

    /** Gives a cell added by the editor the look of its left neighbour, minus any merge. */
    private fun copyCellProperties(row: XWPFTableRow, index: Int) {
        if (index <= 0) return
        val properties = row.getCell(index - 1)?.getCTTc()?.getTcPr()?.copy() as? CTTcPr ?: return
        // A copied span would merge the new cell into the column it was meant to extend. Both
        // checks are needed: unsetting an element that the copied properties do not carry
        // throws out of XmlBeans on the detached copy.
        if (properties.isSetGridSpan()) properties.unsetGridSpan()
        if (properties.isSetVMerge()) properties.unsetVMerge()
        row.getCell(index)?.getCTTc()?.setTcPr(properties)
    }

    /**
     * Makes the table's grid definition describe [columns] columns, keeping the widths that are
     * already defined and giving a new column the width of the last known one (or an equal
     * share of the table's width). A table built by POI has no grid at all, so one is added.
     */
    private fun syncGrid(table: XWPFTable, columns: Int) {
        if (columns <= 0) return
        val ctTbl = table.getCTTbl()
        val grid = ctTbl.getTblGrid() ?: ctTbl.addNewTblGrid()
        while (grid.sizeOfGridColArray() > columns) grid.removeGridCol(grid.sizeOfGridColArray() - 1)
        if (grid.sizeOfGridColArray() >= columns) return
        val known = grid.getGridColList().mapNotNull { (it.getW() as? Number)?.toLong() }.filter { it > 0 }
        val tableWidth = (ctTbl.getTblPr()?.getTblW()?.getW() as? Number)?.toLong()?.takeIf { it > 0 }
        val width = known.lastOrNull() ?: tableWidth?.div(columns) ?: DEFAULT_COLUMN_WIDTH
        while (grid.sizeOfGridColArray() < columns) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width.coerceAtLeast(1)))
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
        // These two are decimal attributes of a union schema type, which XmlBeans types as
        // Object (the setter next to them takes a BigInteger just the same), hence the
        // read-as-a-number dance.
        val lastAbstractId = numbering.getAbstractNums()
            .mapNotNull { (it.abstractNum.abstractNumId as? Number)?.toLong() }
            .maxOrNull() ?: -1L
        val lastNumId = numbering.getNums()
            .mapNotNull { (it.ctNum.numId as? Number)?.toLong() }
            .maxOrNull() ?: 0L
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

    /** The deepest list level an editor action will move a paragraph to. */
    private const val MAX_LIST_LEVEL = 8

    /** The width given to a column of a table whose own widths cannot be read, in twips. */
    private const val DEFAULT_COLUMN_WIDTH = 2400L
}
