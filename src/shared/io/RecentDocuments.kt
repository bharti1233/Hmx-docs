package dev.kern.shared.io

import android.content.Context
import android.net.Uri
import dev.kern.shared.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One document the user has opened, as the home screen's Recent list remembers it. */
data class RecentDocument(
    /**
     * Identity of "the same document": the content:// URI string for provider documents
     * (system picker, "Open with"), the absolute path for local files. Used to de-duplicate,
     * to reopen, and to find the file again — never treated as a filesystem path unless it
     * actually is one.
     */
    val identity: String,
    val name: String,
    val format: DocumentFormat,
    val size: Long,
    val modified: Long,
) {
    /** True when [identity] is a provider document rather than a local file path. */
    val isProviderDocument: Boolean get() = identity.startsWith("content://")
}

/**
 * The documents the user has opened, newest first, so the home screen can offer them again —
 * including files opened through the system picker or "Open with", which have no path of
 * their own.
 *
 * Backed by the same SharedPreferences the browser has always used ("kern_browser",
 * "recents"); entries written by older versions (one bare path per line, no metadata) are
 * still read. An entry is written when an editor has finished loading a document, so a file
 * that fails to open never enters the list; a successful save refreshes the entry's
 * metadata. Adding a document that is already listed moves it back to the top.
 *
 * One file can be listed twice if it is reachable both ways: the system picker identifies a
 * document by its provider URI, the scan by its path, and nothing maps one to the other
 * reliably. The two entries are two identities of the same document, not one entry lost.
 */
object RecentDocuments {

    private const val PREFS = "kern_browser"
    private const val KEY = "recents"
    private const val MAX = 50

    /** The remembered documents, newest first. Never touches the files themselves. */
    fun list(context: Context): List<RecentDocument> {
        val raw = prefs(context).getString(KEY, null).orEmpty()
        if (raw.isEmpty()) return emptyList()
        // Anything that is not this version's JSON is read as the old path-per-line format.
        return runCatching { decode(raw) }.getOrElse { legacy(raw) }
    }

    /** Records (or moves to the front) the document at [uri], which has just been opened. */
    fun recordOpen(context: Context, uri: Uri, name: String, format: DocumentFormat, size: Long) {
        val identity = identityOf(uri)
        if (identity.isEmpty() || name.isEmpty()) return
        val entry = RecentDocument(identity, name, format, size, localModified(identity))
        val rest = list(context).filterNot { it.identity == identity }
        write(context, (listOf(entry) + rest).take(MAX))
    }

    /**
     * Refreshes the entry for [uri] after a successful write of [size] bytes. A document that
     * is not in the list is left alone — Export writes copies that were never opened, and a
     * failed save never reaches this call.
     */
    fun noteSaved(context: Context, uri: Uri, size: Long) {
        val identity = identityOf(uri)
        val entries = list(context)
        val index = entries.indexOfFirst { it.identity == identity }
        if (index < 0) return
        val updated = entries[index].copy(size = size, modified = localModified(identity))
        write(context, entries.toMutableList().also { it[index] = updated })
    }

    /** Drops [identity] from the list; also used when a file is deleted. */
    fun remove(context: Context, identity: String) {
        write(context, list(context).filterNot { it.identity == identity })
    }

    /**
     * The identity a URI is remembered under: a local file stays a path (that is what the
     * scan and the file actions work with), anything a provider serves is kept as its URI.
     */
    internal fun identityOf(uri: Uri): String =
        if (uri.scheme == "file") uri.path.orEmpty() else uri.toString()

    /** For a local file, its own modification time; for a provider, all we know is now. */
    private fun localModified(identity: String): Long =
        if (identity.startsWith("content://")) System.currentTimeMillis()
        else File(identity).lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(context: Context, entries: List<RecentDocument>) {
        val encoded = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("identity", entry.identity)
                        put("name", entry.name)
                        put("format", entry.format.name)
                        put("size", entry.size)
                        put("modified", entry.modified)
                    },
                )
            }
        }.toString()
        prefs(context).edit().putString(KEY, encoded).apply()
    }

    private fun decode(raw: String): List<RecentDocument> {
        val array = JSONArray(raw)
        val out = ArrayList<RecentDocument>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val identity = obj.optString("identity")
            val name = obj.optString("name")
            val format = DocumentFormat.entries.firstOrNull { it.name == obj.optString("format") }
                ?: DocumentFormat.fromExtension(name.substringAfterLast('.', ""))
                ?: continue
            if (identity.isEmpty() || name.isEmpty()) continue
            out.add(RecentDocument(identity, name, format, obj.optLong("size"), obj.optLong("modified")))
        }
        return out
    }

    /** Older entries: one bare file path per line, no metadata stored with them. */
    private fun legacy(raw: String): List<RecentDocument> = raw.split('\n').mapNotNull { path ->
        if (path.isEmpty()) return@mapNotNull null
        val file = File(path)
        val name = file.name
        val format = DocumentFormat.fromExtension(name.substringAfterLast('.', "")) ?: return@mapNotNull null
        // Whatever the file itself can say now; zero for one that is gone (the row still shows).
        RecentDocument(path, name, format, file.length(), file.lastModified())
    }
}
