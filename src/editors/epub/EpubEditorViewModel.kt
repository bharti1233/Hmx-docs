package dev.kern.editors.epub

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kern.shared.io.DocumentIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EPUB editor state. Chapters are loaded once; the user pages through them (TOC +
 * prev/next) and edits block text. Edits are tracked by (chapter index, block
 * index) so saving applies only them onto the original EPUB (see [EpubDocument]).
 */
class EpubEditorViewModel(app: Application) : AndroidViewModel(app) {

    var fileName by mutableStateOf("")
        private set
    var bookTitle by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True when the failure in [error] is worth another read via [retry]. */
    var canRetry by mutableStateOf(false)
        private set

    var currentChapter by mutableIntStateOf(0)
        private set

    private var chapters by mutableStateOf<List<EpubDocument.Chapter>>(emptyList())
    private val edits = mutableStateMapOf<Pair<Int, Int>, String>()

    private var uri: Uri? = null
    private var originalBytes: ByteArray? = null
    private var started = false

    val chapterCount: Int get() = chapters.size
    val dirty: Boolean get() = edits.isNotEmpty()
    val currentBlocks: List<EpubDocument.Block> get() = chapters.getOrNull(currentChapter)?.blocks ?: emptyList()

    fun chapterTitles(): List<String> = chapters.map { it.title }

    /** Current (possibly edited) text for a block in the current chapter. */
    fun blockText(block: EpubDocument.Block): String = edits[currentChapter to block.index] ?: block.text

    fun editBlock(block: EpubDocument.Block, text: String) {
        val key = currentChapter to block.index
        if (text == block.text) edits.remove(key) else edits[key] = text
    }

    fun goToChapter(index: Int) {
        if (index in chapters.indices) currentChapter = index
    }

    fun nextChapter() = goToChapter(currentChapter + 1)
    fun previousChapter() = goToChapter(currentChapter - 1)

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
                    Triple(DocumentIo.displayName(ctx, target), bytes, EpubDocument.read(bytes))
                }
            }
            result.onSuccess { (name, bytes, parsed) ->
                fileName = name
                bookTitle = parsed.title
                originalBytes = bytes
                chapters = parsed.chapters
                loading = false
            }.onFailure { failure ->
                val problem = DocumentIo.loadFailure(
                    failure,
                    "Could not open the EPUB.",
                    DocumentIo.persistedAccess(ctx, target).contains(DocumentIo.UriAccess.READ),
                )
                error = problem.message
                canRetry = problem.canRetry
                loading = false
            }
        }
    }

    fun save(onResult: (ok: Boolean, message: String?) -> Unit) {
        val target = uri ?: run { onResult(false, "Nothing to save."); return }
        write(target) { ok, msg ->
            if (ok) edits.clear()
            onResult(ok, msg)
        }
    }

    fun exportTo(target: Uri, onResult: (ok: Boolean, message: String?) -> Unit) = write(target, onResult)

    private fun write(target: Uri, onResult: (ok: Boolean, message: String?) -> Unit) {
        val ctx = getApplication<Application>()
        val bytes = originalBytes ?: run { onResult(false, "Nothing to save."); return }
        val snapshot = edits.toMap()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentIo.writeBytes(ctx, target, EpubDocument.applyEditsAndSerialize(bytes, snapshot)) }
            }
            onResult(result.isSuccess, DocumentIo.saveError(result.exceptionOrNull(), "Could not save the document. Use Export to save a copy."))
        }
    }
}
