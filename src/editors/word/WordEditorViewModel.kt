package dev.kern.editors.word

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kern.shared.DocumentFormat
import dev.kern.shared.io.DocumentIo
import dev.kern.shared.io.RecentDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One step of the indentation/level controls and of the OOXML default, in twips (0.25 in). */
private const val INDENT_STEP_TWIPS = 360

/** Shown when a table's rows or columns are left alone because its cells are merged. */
private const val MERGED_TABLE_NOTICE =
    "This table has merged cells, so its rows and columns are left as they are. Its text can still be edited."

/** The EMU of one device pixel of a picture at 96 dpi. */
private const val EMU_PER_PIXEL = 9525

/** The widest a picture is placed: 6.5 in, the text column of a letter page. */
private const val MAX_IMAGE_WIDTH_EMU = 5_943_600

/**
 * Word editor state with full rich-text character formatting, paragraph/list styles,
 * interactive table mutations, find & replace, undo/redo history, and word counts.
 */
class WordEditorViewModel(app: Application) : AndroidViewModel(app) {

    var fileName by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var dirty by mutableStateOf(false)
        private set

    /** True while a save of this document is running; a second save is refused meanwhile. */
    var saving by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** True when the failure in [error] is worth another read via [retry]. */
    var canRetry by mutableStateOf(false)
        private set

    /** Body blocks in order. */
    var blocks by mutableStateOf<List<WordDocument.Block>>(emptyList())
        private set

    /** Editable paragraph values, keyed by block index. */
    val fields = mutableStateMapOf<Int, TextFieldValue>()

    /** Paragraph properties (kind, alignment, list type, indent), keyed by block index. */
    val paraProps = mutableStateMapOf<Int, WordDocument.ParaProps>()

    /** Editable table cell states, keyed by block index. */
    val tableStates = mutableStateMapOf<Int, List<List<String>>>()

    var focusedIndex by mutableStateOf<Int?>(null)
        private set

    /** Most-recently-used colours (6-hex), newest first. */
    var recentColors by mutableStateOf<List<String>>(emptyList())
        private set

    /** User-editable custom colour slots. */
    val customSlots = mutableStateListOf<String?>(null, null)

    // ---- Undo / Redo --------------------------------------------------------

    /**
     * One step of editing history, with the document version it belongs to. A text edit shares
     * [bytes] and [blocks] with its neighbours (the array is kept by reference, not copied);
     * a structural change replaces them with the document it produced. Undo therefore always
     * restores a model that matches the bytes the next save would write.
     */
    private data class Snapshot(
        val fields: Map<Int, TextFieldValue>,
        val paraProps: Map<Int, WordDocument.ParaProps>,
        val tableStates: Map<Int, List<List<String>>>,
        val bytes: ByteArray?,
        val blocks: List<WordDocument.Block>,
        val focused: Int?,
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val maxHistory = 30

    /** How many whole document versions editing history keeps alive (one per structural change). */
    private val maxDocumentVersions = 4

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // ---- Find & Replace -----------------------------------------------------

    var isFindOpen by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
    var replaceQuery by mutableStateOf("")
    var currentMatchIndex by mutableIntStateOf(0)
        private set

    data class SearchMatch(
        val blockIndex: Int,
        val matchIndexInBlock: Int,
        val isTable: Boolean = false,
        val row: Int = 0,
        val col: Int = 0,
    )

    val matches by derivedStateOf {
        val q = searchQuery
        if (q.isBlank()) return@derivedStateOf emptyList<SearchMatch>()
        val list = ArrayList<SearchMatch>()
        blocks.forEachIndexed { i, block ->
            when (block) {
                is WordDocument.ParagraphBlock -> {
                    val text = fields[i]?.text ?: block.text
                    var start = 0
                    var matchCount = 0
                    while (start < text.length) {
                        val pos = text.indexOf(q, start, ignoreCase = true)
                        if (pos < 0) break
                        list.add(SearchMatch(blockIndex = i, matchIndexInBlock = matchCount))
                        matchCount++
                        start = pos + q.length
                    }
                }
                is WordDocument.TableBlock -> {
                    val grid = tableStates[i] ?: block.rows
                    for (r in grid.indices) {
                        for (c in grid[r].indices) {
                            val cellText = grid[r][c]
                            var start = 0
                            var matchCount = 0
                            while (start < cellText.length) {
                                val pos = cellText.indexOf(q, start, ignoreCase = true)
                                if (pos < 0) break
                                list.add(SearchMatch(blockIndex = i, matchIndexInBlock = matchCount, isTable = true, row = r, col = c))
                                matchCount++
                                start = pos + q.length
                            }
                        }
                    }
                }
                WordDocument.OpaqueBlock, is WordDocument.ImageBlock -> Unit
            }
        }
        list
    }

    // ---- Statistics ---------------------------------------------------------

    val totalWordCount by derivedStateOf {
        var count = 0
        blocks.forEachIndexed { i, block ->
            when (block) {
                is WordDocument.ParagraphBlock -> {
                    val t = fields[i]?.text ?: block.text
                    count += countWords(t)
                }
                is WordDocument.TableBlock -> {
                    val grid = tableStates[i] ?: block.rows
                    grid.forEach { row -> row.forEach { cell -> count += countWords(cell) } }
                }
                WordDocument.OpaqueBlock, is WordDocument.ImageBlock -> Unit
            }
        }
        count
    }

    val totalCharCount by derivedStateOf {
        var count = 0
        blocks.forEachIndexed { i, block ->
            when (block) {
                is WordDocument.ParagraphBlock -> {
                    val t = fields[i]?.text ?: block.text
                    count += t.length
                }
                is WordDocument.TableBlock -> {
                    val grid = tableStates[i] ?: block.rows
                    grid.forEach { row -> row.forEach { cell -> count += cell.length } }
                }
                WordDocument.OpaqueBlock, is WordDocument.ImageBlock -> Unit
            }
        }
        count
    }

    private var uri: Uri? = null
    private var originalBytes: ByteArray? = null
    private val edits = mutableMapOf<Int, List<WordDocument.Run>>()
    private val paraPropOps = mutableMapOf<Int, WordDocument.ParaPropsUpdate>()
    private val tableEdits = mutableMapOf<Int, List<List<String>>>()
    private var started = false

    fun start(encodedUri: String?) {
        if (started) return
        started = true
        // The navigation graph decodes this route argument once already (the route
        // builders encode), so decoding again here would corrupt URIs whose IDs carry
        // escapes of their own.
        val decoded = encodedUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (decoded == null) {
            loading = false
            error = "No file was provided."
            return
        }
        uri = decoded
        load(decoded)
    }

    /** Re-reads the document on the user's say-so: after access was renewed, or a provider recovered. */
    fun retry() {
        val target = uri ?: return
        if (loading || error == null) return
        error = null
        canRetry = false
        loading = true
        load(target)
    }

    private fun load(target: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentIo.persistAccess(ctx, target)
                    val bytes = DocumentIo.readBytes(ctx, target)
                    Triple(DocumentIo.displayName(ctx, target), bytes, WordDocument.read(bytes))
                }
            }
            result.onSuccess { (name, bytes, parsed) ->
                fileName = name
                originalBytes = bytes
                adopt(parsed)
                // The document opened: it belongs in the home screen's Recent list from here on.
                RecentDocuments.recordOpen(ctx, target, name, DocumentFormat.WORD, bytes.size.toLong())
                loading = false
            }.onFailure { failure ->
                val problem = DocumentIo.loadFailure(
                    failure,
                    "Could not open the document.",
                    DocumentIo.persistedAccess(ctx, target).contains(DocumentIo.UriAccess.READ),
                )
                error = problem.message
                canRetry = problem.canRetry
                loading = false
            }
        }
    }

    fun setFocus(index: Int) {
        focusedIndex = index
    }

    fun onValueChange(index: Int, value: TextFieldValue) {
        // The text field's own value is a plain-text buffer: it emits the text and the
        // selection but never the formatting, so only those two are taken from it. The
        // stored value stays the authority for styles and the text change is applied to it.
        val stored = fields[index]
            ?: TextFieldValue(WordRichText.toAnnotated(paragraphRuns(index)))
        val merged = WordRichText.mergeEmission(stored, value)
        val textChanged = stored.annotatedString.text != merged.annotatedString.text
        if (textChanged) pushSnapshot()
        fields[index] = merged
        focusedIndex = index
        if (textChanged) {
            edits[index] = WordRichText.toRuns(merged.annotatedString)
            dirty = true
        }
    }

    /** The runs a paragraph was read with; the fallback is for a field not yet touched. */
    private fun paragraphRuns(index: Int): List<WordDocument.Run> =
        (blocks.getOrNull(index) as? WordDocument.ParagraphBlock)?.runs
            ?: listOf(WordDocument.Run(""))

    fun canFormat(): Boolean {
        val idx = focusedIndex ?: return false
        return (blocks.getOrNull(idx) as? WordDocument.ParagraphBlock)?.editable == true
    }

    fun activeStyle(): WordDocument.RunStyle? =
        focusedIndex?.let { fields[it] }?.let { WordRichText.styleAt(it) }

    fun activeParaProps(): WordDocument.ParaProps? =
        focusedIndex?.let { paraProps[it] }

    /**
     * The property changes to write for a paragraph: the difference from what it was read with.
     * Setting a property back to the value the document already had writes nothing, and a
     * history step is rebuilt from this same rule, so undoing a change cannot leave the write
     * behind while the editor shows the old value.
     */
    private fun propOpsFor(
        index: Int,
        props: WordDocument.ParaProps,
        from: List<WordDocument.Block> = blocks,
    ): WordDocument.ParaPropsUpdate {
        val read = (from.getOrNull(index) as? WordDocument.ParagraphBlock)?.props
            ?: return WordDocument.ParaPropsUpdate(
                kind = props.kind,
                align = props.align,
                listType = props.listType,
                listLevel = props.listLevel,
                indentTwips = props.indentTwips,
            )
        return WordDocument.ParaPropsUpdate(
            kind = props.kind.takeIf { it != read.kind },
            align = props.align.takeIf { it != read.align },
            listType = props.listType.takeIf { it != read.listType },
            // A level means nothing without the list it belongs to.
            listLevel = props.listLevel
                .takeIf { props.listType != WordDocument.ListType.NONE && it != read.listLevel },
            indentTwips = props.indentTwips.takeIf { it != read.indentTwips },
        )
    }

    /** Records a paragraph's new properties and the change that has to be written for them. */
    private fun setParaProps(index: Int, props: WordDocument.ParaProps) {
        paraProps[index] = props
        paraPropOps[index] = propOpsFor(index, props)
        dirty = true
    }

    // ---- Character Formatting -----------------------------------------------

    fun toggleBold() {
        mutateFocused { v ->
            val target = !WordRichText.styleAt(v).bold
            WordRichText.restyleSelection(v) { it.copy(bold = target) }
        }
    }

    fun toggleItalic() {
        mutateFocused { v ->
            val target = !WordRichText.styleAt(v).italic
            WordRichText.restyleSelection(v) { it.copy(italic = target) }
        }
    }

    fun toggleUnderline() {
        mutateFocused { v ->
            val target = !WordRichText.styleAt(v).underline
            WordRichText.restyleSelection(v) { it.copy(underline = target) }
        }
    }

    fun toggleStrike() {
        mutateFocused { v ->
            val target = !WordRichText.styleAt(v).strike
            WordRichText.restyleSelection(v) { it.copy(strike = target) }
        }
    }

    fun setColor(hex: String?) {
        val applied = mutateFocused { v -> WordRichText.restyleSelection(v) { it.copy(colorHex = hex) } }
        if (applied && hex != null) pushRecent(hex)
    }

    fun setFontSize(points: Float?) {
        mutateFocused { v -> WordRichText.restyleSelection(v) { it.copy(sizePt = points) } }
    }

    fun setCustomSlot(slot: Int, input: String): Boolean {
        val hex = WordRichText.normalizeHex(input) ?: return false
        if (slot in customSlots.indices) customSlots[slot] = hex
        setColor(hex)
        return true
    }

    // ---- Paragraph & List Formatting ----------------------------------------

    fun setParagraphKind(kind: WordDocument.Kind) {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        setParaProps(idx, current.copy(kind = kind))
    }

    fun setAlignment(align: WordDocument.Align) {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        setParaProps(idx, current.copy(align = align))
    }

    fun toggleBulletList() {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        val target = if (current.listType == WordDocument.ListType.BULLET) WordDocument.ListType.NONE else WordDocument.ListType.BULLET
        // Leaving a list puts the paragraph back at its outermost level.
        setParaProps(idx, current.copy(listType = target, listLevel = if (target == WordDocument.ListType.NONE) 0 else current.listLevel))
    }

    fun toggleNumberedList() {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        val target = if (current.listType == WordDocument.ListType.NUMBER) WordDocument.ListType.NONE else WordDocument.ListType.NUMBER
        setParaProps(idx, current.copy(listType = target, listLevel = if (target == WordDocument.ListType.NONE) 0 else current.listLevel))
    }

    fun increaseIndent() {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        // A real list item moves one level deeper: the position is the list's own, not a
        // direct indentation, so Word (and the next save) keeps the nesting. Everything else,
        // and a list whose definition has no deeper level, moves its indentation as before.
        val deeper = current.listType != WordDocument.ListType.NONE && current.listLevel < current.maxListLevel
        setParaProps(
            idx,
            if (deeper) current.copy(listLevel = current.listLevel + 1)
            else current.copy(indentTwips = current.indentTwips + INDENT_STEP_TWIPS),
        )
    }

    fun decreaseIndent() {
        val idx = focusedIndex ?: return
        val current = paraProps[idx] ?: return
        pushSnapshot()
        val shallower = current.listType != WordDocument.ListType.NONE && current.listLevel > 0
        setParaProps(
            idx,
            if (shallower) current.copy(listLevel = current.listLevel - 1)
            else current.copy(indentTwips = (current.indentTwips - INDENT_STEP_TWIPS).coerceAtLeast(0)),
        )
    }

    // ---- Table Operations & Dedicated Modal --------------------------------

    var activeTableBlockIndex by mutableStateOf<Int?>(null)
        private set
    var activeTableData by mutableStateOf<List<List<String>>>(emptyList())
        private set
    var activeSelectedRow by mutableIntStateOf(0)
    var activeSelectedCol by mutableIntStateOf(0)

    /**
     * Whether the grid editor may change a table's rows and columns. A table with merged cells
     * is edited cell by cell only: the grid model cannot describe a span, and rewriting the
     * rows from it would replace the merged cells with ordinary ones.
     */
    fun isTableEditable(blockIndex: Int): Boolean =
        (blocks.getOrNull(blockIndex) as? WordDocument.TableBlock)?.merged != true

    fun openTableEditor(blockIndex: Int) {
        val grid = tableStates[blockIndex] ?: (blocks.getOrNull(blockIndex) as? WordDocument.TableBlock)?.rows ?: return
        activeTableBlockIndex = blockIndex
        activeTableData = grid.map { it.toList() }
        activeSelectedRow = 0
        activeSelectedCol = 0
    }

    fun closeTableEditor() {
        activeTableBlockIndex = null
        activeTableData = emptyList()
    }

    fun updateActiveTableCell(row: Int, col: Int, text: String) {
        if (row !in activeTableData.indices || col !in activeTableData[row].indices) return
        activeTableData = activeTableData.mapIndexed { r, rList ->
            if (r == row) {
                rList.mapIndexed { c, cellText -> if (c == col) text else cellText }
            } else {
                rList
            }
        }
    }

    /** Whether the open table may have rows or columns changed; merged cells cannot be expressed. */
    fun activeTableStructureEditable(): Boolean =
        activeTableBlockIndex?.let(::isTableEditable) ?: false

    fun insertTableRow(atIndex: Int) {
        if (!activeTableStructureEditable()) {
            notice = MERGED_TABLE_NOTICE
            return
        }
        val colCount = activeTableData.firstOrNull()?.size ?: 2
        val newRow = List(colCount) { "" }
        val insertPos = atIndex.coerceIn(0, activeTableData.size)
        val list = activeTableData.toMutableList()
        list.add(insertPos, newRow)
        activeTableData = list
        activeSelectedRow = insertPos
    }

    fun deleteTableRow(atIndex: Int) {
        if (!activeTableStructureEditable()) {
            notice = MERGED_TABLE_NOTICE
            return
        }
        if (activeTableData.size <= 1 || atIndex !in activeTableData.indices) return
        val list = activeTableData.toMutableList()
        list.removeAt(atIndex)
        activeTableData = list
        if (activeSelectedRow >= activeTableData.size) {
            activeSelectedRow = (activeTableData.size - 1).coerceAtLeast(0)
        }
    }

    fun insertTableColumn(atIndex: Int) {
        if (!activeTableStructureEditable()) {
            notice = MERGED_TABLE_NOTICE
            return
        }
        val insertPos = atIndex.coerceIn(0, activeTableData.firstOrNull()?.size ?: 0)
        activeTableData = activeTableData.map { row ->
            val rList = row.toMutableList()
            rList.add(insertPos, "")
            rList
        }
        activeSelectedCol = insertPos
    }

    fun deleteTableColumn(atIndex: Int) {
        if (!activeTableStructureEditable()) {
            notice = MERGED_TABLE_NOTICE
            return
        }
        val colCount = activeTableData.firstOrNull()?.size ?: 0
        if (colCount <= 1 || atIndex !in 0 until colCount) return
        activeTableData = activeTableData.map { row ->
            val rList = row.toMutableList()
            if (atIndex in rList.indices) rList.removeAt(atIndex)
            rList
        }
        val maxCol = (activeTableData.firstOrNull()?.size ?: 1) - 1
        if (activeSelectedCol > maxCol) {
            activeSelectedCol = maxCol.coerceAtLeast(0)
        }
    }

    fun saveTableEditor() {
        val blockIndex = activeTableBlockIndex ?: return
        val newGrid = activeTableData
        pushSnapshot()
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
        closeTableEditor()
    }

    fun editTableCell(blockIndex: Int, row: Int, col: Int, text: String) {
        val grid = tableStates[blockIndex] ?: return
        if (row !in grid.indices || col !in grid[row].indices) return
        if (grid[row][col] == text) return
        pushSnapshot()
        val newGrid = grid.mapIndexed { r, rList ->
            if (r == row) {
                rList.mapIndexed { c, cellText -> if (c == col) text else cellText }
            } else {
                rList
            }
        }
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
    }

    fun addTableRow(blockIndex: Int) {
        if (!isTableEditable(blockIndex)) return
        val grid = tableStates[blockIndex] ?: return
        pushSnapshot()
        val colCount = grid.firstOrNull()?.size ?: 2
        val newRow = List(colCount) { "" }
        val newGrid = grid + listOf(newRow)
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
    }

    fun deleteTableRow(blockIndex: Int, row: Int) {
        if (!isTableEditable(blockIndex)) return
        val grid = tableStates[blockIndex] ?: return
        if (grid.size <= 1 || row !in grid.indices) return
        pushSnapshot()
        val newGrid = grid.filterIndexed { index, _ -> index != row }
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
    }

    fun addTableColumn(blockIndex: Int) {
        if (!isTableEditable(blockIndex)) return
        val grid = tableStates[blockIndex] ?: return
        pushSnapshot()
        val newGrid = grid.map { row -> row + "" }
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
    }

    fun deleteTableColumn(blockIndex: Int, col: Int) {
        if (!isTableEditable(blockIndex)) return
        val grid = tableStates[blockIndex] ?: return
        if ((grid.firstOrNull()?.size ?: 0) <= 1) return
        pushSnapshot()
        val newGrid = grid.map { row -> row.filterIndexed { index, _ -> index != col } }
        tableStates[blockIndex] = newGrid
        tableEdits[blockIndex] = newGrid
        dirty = true
    }

    // ---- Block Structure (paragraphs and pictures) --------------------------

    /**
     * True while a structural change is being written out and read back. The editor pauses for
     * that moment: the change renumbers blocks, so a keystroke racing it would land in the
     * wrong paragraph.
     */
    var busy by mutableStateOf(false)
        private set

    /** A one-shot message for an action that could not be carried out. */
    var notice by mutableStateOf<String?>(null)

    /** Bumped when the editor places the caret itself, so the screen can take focus for it. */
    var focusTick by mutableIntStateOf(0)
        private set

    /** The block a structural action applies to: the paragraph under the caret. */
    private fun focusedParagraph(): Int? =
        focusedIndex?.takeIf { blocks.getOrNull(it) is WordDocument.ParagraphBlock }

    fun canInsertParagraph(): Boolean = !busy && !loading && blocks.isNotEmpty()

    fun canEditStructure(): Boolean = !busy && !loading && focusedParagraph() != null

    fun canDeleteFocused(): Boolean = canEditStructure() && blocks.size > 1

    fun canMergeWithPrevious(): Boolean {
        val index = focusedParagraph() ?: return false
        return !busy && !loading && index > 0 && blocks.getOrNull(index - 1) is WordDocument.ParagraphBlock
    }

    fun canInsertImage(): Boolean = canInsertParagraph()

    /** Adds a paragraph after the caret's block, carrying the formatting of the nearest paragraph. */
    fun insertParagraphAfterFocused() {
        if (!canInsertParagraph()) return
        val after = focusedIndex ?: blocks.indexOfLast { it is WordDocument.ParagraphBlock }
        if (after < 0) return
        runBlockOp(
            WordDocument.BlockOp.InsertParagraph(after = after, inheritFrom = inheritanceSource(after)),
            focusBlock = { (after + 1).coerceIn(0, it.size - 1) },
        )
    }

    /** Enter: the text from [offset] on moves into a new paragraph that keeps this one's formatting. */
    fun splitFocusedParagraph(offset: Int) {
        val index = focusedParagraph() ?: return
        if (busy || loading) return
        val length = fields[index]?.text?.length ?: return
        runBlockOp(
            WordDocument.BlockOp.SplitParagraph(index, offset.coerceIn(0, length)),
            focusBlock = { (index + 1).coerceIn(0, it.size - 1) },
        )
    }

    /** Removes the paragraph under the caret; the last block of a document is kept. */
    fun deleteFocusedBlock() {
        if (!canDeleteFocused()) return
        val index = focusedParagraph() ?: return
        runBlockOp(
            WordDocument.BlockOp.DeleteBlock(index),
            focusBlock = { minOf(index, it.size - 1) },
        )
    }

    /** Joins the paragraph under the caret to the one before it, as deleting a paragraph mark does. */
    fun mergeFocusedWithPrevious() {
        if (!canMergeWithPrevious()) return
        val index = focusedParagraph() ?: return
        val caret = fields[index - 1]?.text?.length ?: 0
        runBlockOp(
            WordDocument.BlockOp.MergeWithPrevious(index),
            focusBlock = { index - 1 },
            caret = caret,
        )
    }

    /** Removes one picture; the text around it and every other picture stay where they are. */
    fun deleteImage(blockIndex: Int, ordinal: Int) {
        if (busy || loading || saving) return
        if (blocks.getOrNull(blockIndex) !is WordDocument.ImageBlock) return
        runBlockOp(
            WordDocument.BlockOp.DeleteImage(blockIndex, ordinal),
            focusBlock = { new ->
                val before = focusedIndex
                val moved = when {
                    before == null -> null
                    before > blockIndex -> before - 1
                    else -> before
                }
                moved?.takeIf { new.getOrNull(it) is WordDocument.ParagraphBlock }
            },
            caret = null,
        )
    }

    /** Places a picture picked from the device after the caret's block. */
    fun insertImage(source: Uri) {
        if (!canInsertImage()) return
        val ctx = getApplication<Application>()
        val after = focusedIndex ?: blocks.indexOfLast { it is WordDocument.ParagraphBlock }
        // The snapshot is taken here, before the reading starts, because the operation itself
        // runs with busy already set (there is no way to un-read a file once picked).
        pushSnapshot()
        busy = true
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = DocumentIo.readBytes(ctx, source)
                    // Bounds only: the picture is stored as it is, never re-encoded.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    PreparedPicture(bytes, DocumentIo.displayName(ctx, source), bounds.outWidth, bounds.outHeight)
                }
            }
            prepared.onSuccess { picture ->
                if (picture.bytes.isEmpty() || picture.widthPx <= 0 || picture.heightPx <= 0) {
                    busy = false
                    notice = "That file is not a picture this editor can place."
                    return@onSuccess
                }
                // Scaled down to the text column when it is wider, in proportion.
                val scale = (MAX_IMAGE_WIDTH_EMU.toDouble() / (picture.widthPx * EMU_PER_PIXEL)).coerceAtMost(1.0)
                runBlockOp(
                    WordDocument.BlockOp.InsertImage(
                        after = after,
                        bytes = picture.bytes,
                        fileName = picture.name,
                        widthEmu = (picture.widthPx * EMU_PER_PIXEL * scale).toInt().coerceAtLeast(1),
                        heightEmu = (picture.heightPx * EMU_PER_PIXEL * scale).toInt().coerceAtLeast(1),
                    ),
                    focusBlock = { (after + 1).coerceIn(0, it.size - 1) },
                    alreadyBusy = true,
                )
            }.onFailure { failure ->
                busy = false
                notice = failure.message ?: "The picture could not be read."
            }
        }
    }

    /** The nearest paragraph a new block can take its formatting from. */
    private fun inheritanceSource(after: Int): Int {
        for (index in after downTo 0) {
            if (blocks.getOrNull(index) is WordDocument.ParagraphBlock) return index
        }
        for (index in after + 1 until blocks.size) {
            if (blocks.getOrNull(index) is WordDocument.ParagraphBlock) return index
        }
        return -1
    }

    /**
     * Carries out one block-level change: the pending edits and the change are written with the
     * document, and the result is read back as the new model. A block index therefore never has
     * to survive a structural change, and a change can never be applied twice.
     */
    private fun runBlockOp(
        op: WordDocument.BlockOp,
        focusBlock: (List<WordDocument.Block>) -> Int?,
        caret: Int? = 0,
        alreadyBusy: Boolean = false,
    ) {
        if ((!alreadyBusy && busy) || loading || saving) return
        val bytes = originalBytes ?: return
        val input = snapshotForSave()
        if (!alreadyBusy) pushSnapshot()
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val serialized = WordDocument.applyEditsAndSerialize(
                        originalBytes = bytes,
                        edits = input.edits,
                        paraProps = input.paraProps,
                        tableEdits = input.tables,
                        blockOp = op,
                    )
                    serialized to WordDocument.read(serialized)
                }
            }
            result.onSuccess { (serialized, parsed) ->
                originalBytes = serialized
                adopt(parsed)
                // The edits that were folded into the change must not be written a second time.
                edits.clear()
                paraPropOps.clear()
                tableEdits.clear()
                busy = false
                dirty = true
                val target = focusBlock(parsed.blocks)
                focusedIndex = target
                if (target != null && caret != null) {
                    fields[target]?.let { field -> fields[target] = field.copy(selection = TextRange(caret)) }
                }
                focusTick++
            }.onFailure { failure ->
                // Nothing was changed: the edits are still pending, so the document on disk and
                // the editor still agree apart from them.
                busy = false
                notice = failure.message ?: "The change could not be applied."
            }
        }
    }

    /** Takes on a freshly read document: blocks, paragraph fields and table grids. */
    private fun adopt(parsed: WordDocument.Parsed) {
        blocks = parsed.blocks
        fields.clear()
        paraProps.clear()
        tableStates.clear()
        parsed.blocks.forEachIndexed { i, block ->
            when (block) {
                is WordDocument.ParagraphBlock -> {
                    paraProps[i] = block.props
                    if (block.editable) {
                        fields[i] = TextFieldValue(WordRichText.toAnnotated(block.runs))
                    }
                }
                is WordDocument.TableBlock -> {
                    tableStates[i] = block.rows
                }
                WordDocument.OpaqueBlock, is WordDocument.ImageBlock -> Unit
            }
        }
    }

    /** One picture on its way into the document. */
    private data class PreparedPicture(
        val bytes: ByteArray,
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    // ---- Find & Replace Actions ---------------------------------------------

    fun toggleFind() {
        isFindOpen = !isFindOpen
        if (!isFindOpen) {
            searchQuery = ""
            replaceQuery = ""
        }
    }

    fun nextMatch() {
        val count = matches.size
        if (count > 0) {
            currentMatchIndex = (currentMatchIndex + 1) % count
        }
    }

    fun prevMatch() {
        val count = matches.size
        if (count > 0) {
            currentMatchIndex = (currentMatchIndex - 1 + count) % count
        }
    }

    fun replaceOne() {
        val mList = matches
        if (currentMatchIndex !in mList.indices || searchQuery.isEmpty()) return
        val match = mList[currentMatchIndex]
        pushSnapshot()
        if (match.isTable) {
            val grid = tableStates[match.blockIndex] ?: return
            val cell = grid.getOrNull(match.row)?.getOrNull(match.col) ?: return
            val replaced = replaceNth(cell, searchQuery, replaceQuery, match.matchIndexInBlock)
            editTableCell(match.blockIndex, match.row, match.col, replaced)
        } else {
            val field = fields[match.blockIndex] ?: return
            val runs = WordRichText.replaceRuns(
                WordRichText.toRuns(field.annotatedString),
                searchQuery,
                replaceQuery,
                only = match.matchIndexInBlock,
            )
            fields[match.blockIndex] = field.copy(annotatedString = WordRichText.toAnnotated(runs))
            edits[match.blockIndex] = runs
            dirty = true
        }
        if (currentMatchIndex >= matches.size && matches.isNotEmpty()) {
            currentMatchIndex = 0
        }
    }

    fun replaceAll() {
        if (searchQuery.isEmpty()) return
        pushSnapshot()
        blocks.forEachIndexed { i, block ->
            when (block) {
                is WordDocument.ParagraphBlock -> {
                    val field = fields[i] ?: return@forEachIndexed
                    if (field.text.contains(searchQuery, ignoreCase = true)) {
                        // Rebuilt from the paragraph's own runs: a replacement keeps the
                        // formatting of the text around it instead of flattening the block.
                        val runs = WordRichText.replaceRuns(
                            WordRichText.toRuns(field.annotatedString),
                            searchQuery,
                            replaceQuery,
                        )
                        fields[i] = field.copy(annotatedString = WordRichText.toAnnotated(runs))
                        edits[i] = runs
                        dirty = true
                    }
                }
                is WordDocument.TableBlock -> {
                    val grid = tableStates[i] ?: return@forEachIndexed
                    var changed = false
                    val newGrid = grid.map { row ->
                        row.map { cell ->
                            if (cell.contains(searchQuery, ignoreCase = true)) {
                                changed = true
                                cell.replace(searchQuery, replaceQuery, ignoreCase = true)
                            } else {
                                cell
                            }
                        }
                    }
                    if (changed) {
                        tableStates[i] = newGrid
                        tableEdits[i] = newGrid
                        dirty = true
                    }
                }
                WordDocument.OpaqueBlock, is WordDocument.ImageBlock -> Unit
            }
        }
        currentMatchIndex = 0
    }

    private fun replaceNth(text: String, target: String, replacement: String, n: Int): String {
        var start = 0
        var count = 0
        while (start < text.length) {
            val pos = text.indexOf(target, start, ignoreCase = true)
            if (pos < 0) break
            if (count == n) {
                return text.substring(0, pos) + replacement + text.substring(pos + target.length)
            }
            count++
            start = pos + target.length
        }
        return text
    }

    // ---- History Management -------------------------------------------------

    private fun pushSnapshot() {
        undoStack.addLast(snapshot())
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        // ponytail: one document version per structural change, four versions kept; a longer
        // undo chain over structural edits would hold that many copies of the file in memory.
        while (undoStack.size > 1 && undoStack.map { it.bytes }.distinct().size > maxDocumentVersions) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        updateHistoryState()
    }

    private fun snapshot() = Snapshot(
        fields = fields.toMap(),
        paraProps = paraProps.toMap(),
        tableStates = tableStates.toMap(),
        bytes = originalBytes,
        blocks = blocks,
        focused = focusedIndex,
    )

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        applySnapshot(undoStack.removeLast())
        updateHistoryState()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        applySnapshot(redoStack.removeLast())
        updateHistoryState()
    }

    private fun applySnapshot(snap: Snapshot) {
        originalBytes = snap.bytes
        blocks = snap.blocks
        fields.clear()
        fields.putAll(snap.fields)
        paraProps.clear()
        paraProps.putAll(snap.paraProps)
        tableStates.clear()
        tableStates.putAll(snap.tableStates)
        // The pending writes are rebuilt from the restored state, not left over from the
        // change that was undone, or the next save would write the undone change back.
        edits.clear()
        snap.fields.forEach { (i, v) -> edits[i] = WordRichText.toRuns(v.annotatedString) }
        paraPropOps.clear()
        snap.paraProps.forEach { (i, props) -> paraPropOps[i] = propOpsFor(i, props, snap.blocks) }
        tableEdits.clear()
        snap.tableStates.forEach { (i, grid) -> tableEdits[i] = grid }
        focusedIndex = snap.focused?.takeIf { snap.blocks.getOrNull(it) is WordDocument.ParagraphBlock }
        focusTick++
        dirty = true
    }

    private fun updateHistoryState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun pushRecent(hex: String) {
        recentColors = (listOf(hex) + recentColors).distinct().take(6)
    }

    private fun mutateFocused(block: (TextFieldValue) -> TextFieldValue): Boolean {
        val idx = focusedIndex ?: return false
        val current = fields[idx] ?: return false
        pushSnapshot()
        val updated = block(current)
        fields[idx] = updated
        edits[idx] = WordRichText.toRuns(updated.annotatedString)
        dirty = true
        return true
    }

    private fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    // ---- Save & Export ------------------------------------------------------

    fun save(onResult: (ok: Boolean, message: String?) -> Unit) {
        val target = uri ?: run { onResult(false, "Nothing to save."); return }
        if (saving) { onResult(false, "A save is already running."); return }
        val written = snapshotForSave()
        saving = true
        write(target, written) { ok, msg ->
            saving = false
            // Only the revision that was written may clear the flag: edits made while the
            // save ran are still unsaved.
            if (ok && snapshotForSave() == written) dirty = false
            onResult(ok, msg)
        }
    }

    fun exportTo(target: Uri, onResult: (ok: Boolean, message: String?) -> Unit) =
        write(target, snapshotForSave(), onResult)

    /** Everything one save serializes, captured together so a later edit cannot leak into it. */
    private data class SaveInput(
        val edits: Map<Int, List<WordDocument.Run>>,
        val paraProps: Map<Int, WordDocument.ParaPropsUpdate>,
        val tables: Map<Int, List<List<String>>>,
    )

    private fun snapshotForSave() = SaveInput(
        edits = edits.toMap(),
        paraProps = paraPropOps.toMap(),
        tables = tableEdits.toMap(),
    )

    private fun write(
        target: Uri,
        input: SaveInput,
        onResult: (ok: Boolean, message: String?) -> Unit,
    ) {
        val ctx = getApplication<Application>()
        val bytes = originalBytes ?: run { onResult(false, "Nothing to save."); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val serialized = WordDocument.applyEditsAndSerialize(
                        originalBytes = bytes,
                        edits = input.edits,
                        paraProps = input.paraProps,
                        tableEdits = input.tables,
                    )
                    // Checked before the destination is opened: a package that would not
                    // read back never replaces the document on disk.
                    DocumentIo.writeBytes(ctx, target, serialized, DocumentIo::verifyZipPackage)
                    serialized.size.toLong()
                }
            }
            // A failed save never reaches this line, so the Recent list cannot report one as
            // successful. Only the entry of the document that was actually written changes.
            result.getOrNull()?.let { RecentDocuments.noteSaved(ctx, target, it) }
            onResult(result.isSuccess, DocumentIo.saveError(result.exceptionOrNull(), "Could not save the document. Use Export to save a copy."))
        }
    }
}
