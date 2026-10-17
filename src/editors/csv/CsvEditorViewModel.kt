package dev.kern.editors.csv

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kern.shared.io.DocumentIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the editable CSV grid and drives load/save/export. File I/O runs off the
 * main thread; the grid is Compose snapshot state so cell edits recompose directly.
 */
class CsvEditorViewModel(app: Application) : AndroidViewModel(app) {

    val rows = mutableStateListOf<SnapshotStateList<String>>()

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

    var selectedRow by mutableIntStateOf(0)
        private set
    var selectedCol by mutableIntStateOf(0)
        private set

    private var uri: Uri? = null
    private var started = false

    var colWidths by mutableStateOf<Map<Int, Float>>(emptyMap())
        private set
    var rowHeights by mutableStateOf<Map<Int, Float>>(emptyMap())
        private set

    val columnCount: Int get() = rows.firstOrNull()?.size ?: 0

    /** Loads the document once for the given encoded content URI (safe to call on recomposition). */
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
                    DocumentIo.displayName(ctx, target) to CsvDocument.parse(DocumentIo.readText(ctx, target))
                }
            }
            result.onSuccess { (name, grid) ->
                fileName = name
                rows.clear()
                grid.forEach { rows.add(it.toMutableStateList()) }
                selectedRow = 0
                selectedCol = 0
                loading = false
            }.onFailure { failure ->
                val problem = DocumentIo.loadFailure(
                    failure,
                    "Could not open the file.",
                    DocumentIo.persistedAccess(ctx, target).contains(DocumentIo.UriAccess.READ),
                )
                error = problem.message
                canRetry = problem.canRetry
                loading = false
            }
        }
    }

    fun select(row: Int, col: Int) {
        selectedRow = row
        selectedCol = col
    }

    fun selectedValue(): String = rows.getOrNull(selectedRow)?.getOrNull(selectedCol) ?: ""

    fun editSelected(value: String) {
        val row = rows.getOrNull(selectedRow) ?: return
        if (selectedCol in row.indices && row[selectedCol] != value) {
            row[selectedCol] = value
            dirty = true
        }
    }

    fun resizeColumn(c: Int, dp: Float) {
        colWidths = colWidths + (c to dp)
    }

    fun resizeRow(r: Int, dp: Float) {
        rowHeights = rowHeights + (r to dp)
    }

    fun autoResizeColumn(c: Int) {
        var maxLen = 4
        for (row in rows) {
            val cell = row.getOrNull(c) ?: continue
            if (cell.length > maxLen) maxLen = cell.length
        }
        resizeColumn(c, (maxLen * 8f + 24f).coerceIn(40f, 600f))
    }

    fun autoResizeRow(r: Int) {
        val row = rows.getOrNull(r) ?: return
        var maxLines = 1
        for (cell in row) {
            val lines = cell.count { it == '\n' } + 1
            if (lines > maxLines) maxLines = lines
        }
        resizeRow(r, (maxLines * 20f + 16f).coerceIn(24f, 400f))
    }

    /** Inserts a blank row just below the selected row (appends if nothing useful is selected). */
    fun addRow() {
        val insertAt = (selectedRow + 1).coerceIn(0, rows.size)
        rows.add(insertAt, MutableList(columnCount.coerceAtLeast(1)) { "" }.toMutableStateList())
        dirty = true
    }

    /** Inserts a blank column just to the right of the selected column. */
    fun addColumn() {
        if (rows.isEmpty()) {
            rows.add(mutableStateListOf(""))
            dirty = true
            return
        }
        rows.forEach { row ->
            val insertAt = (selectedCol + 1).coerceIn(0, row.size)
            row.add(insertAt, "")
        }
        dirty = true
    }

    /** Writes back to the original URI. */
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

    /** Writes a copy to a user-chosen URI (Save as). */
    fun exportTo(target: Uri, onResult: (ok: Boolean, message: String?) -> Unit) =
        write(target, snapshotForSave(), onResult)

    /** The grid as one save writes it; a copy, so a later edit cannot leak into the write. */
    private fun snapshotForSave(): List<List<String>> = rows.map { it.toList() }

    private fun write(
        target: Uri,
        snapshot: List<List<String>>,
        onResult: (ok: Boolean, message: String?) -> Unit,
    ) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentIo.writeText(ctx, target, CsvDocument.toCsv(snapshot)) }
            }
            onResult(result.isSuccess, DocumentIo.saveError(result.exceptionOrNull(), "Could not save the document. Use Export to save a copy."))
        }
    }
}
