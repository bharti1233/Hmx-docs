package dev.kern.shared.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Reads and writes documents directly at their content:// URI. Kern never copies
 * files into an app folder (see ADR 003); everything is edited in place.
 *
 * Used by every editor. URIs arrive either from an "Open with" intent or from the
 * SAF document picker; both yield a content:// URI the ContentResolver can open.
 *
 * Access lifetime: the grant the picker or "Open with" hands over is transient — the
 * platform drops it when the device restarts — so [persistAccess] takes the persistable
 * grant the provider offers. Failures are reported in words the user can act on
 * ([loadFailure] / [saveError]) rather than the raw platform exception text, which names
 * providers and PIDs the user has no use for.
 */
object DocumentIo {

    private const val TAG = "DocumentIo"

    /** No long-lived grant is held (or it was dropped): re-picking the file is the fix. */
    private const val EXPIRED_ACCESS = "Access to this file has expired. Open it again to continue."

    /** A provider that failed, stalled or refused a stream for a file it may still have. */
    private const val PROVIDER_FAILURE = "The file could not be opened just now. Try again."

    /** [readBytes]'s own text for a provider that returned no stream; classified as retryable. */
    internal const val NO_STREAM = "Provider returned no input stream"

    /**
     * A replacement was refused before anything was opened, so the document on disk still
     * holds its last valid version. Thrown by [writeBytes] when [verifyZipPackage] rejects
     * the bytes; [saveError] turns it into the matching user-facing message.
     */
    internal class DocumentNotWrittenException(message: String) : IOException(message)

    /** Long-lived access modes for a document URI. */
    enum class UriAccess { READ, WRITE }

    // ---- Reading & writing --------------------------------------------------

    /** Reads the whole document as bytes. Throws IOException if the URI can't be opened. */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        // Files from the in-app browser scan arrive as file:// URIs (MANAGE_EXTERNAL_STORAGE);
        // read them directly. SAF content:// URIs go through the resolver.
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).readBytes() }
                ?: throw IOException("Invalid file URI")
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw FileNotFoundException(NO_STREAM)
        } catch (failure: Throwable) {
            // Provider and failure kind only: no document name, path or content.
            Log.w(TAG, "Read failed (${failure.classChain()}) from ${uri.authority}")
            throw failure
        }
    }

    /** Reads the whole document as UTF-8 text. */
    fun readText(context: Context, uri: Uri): String =
        String(readBytes(context, uri), Charsets.UTF_8)

    /**
     * Replaces the document with [bytes].
     *
     * The replacement is serialized in full by the caller before this runs, and — when
     * [verify] is given — it has to accept those bytes first ([verifyZipPackage] is the
     * check the editors pass), so an incomplete document never opens the destination.
     *
     * A local file is replaced by renaming a fully written temporary file over it: a
     * failure anywhere leaves the previous document in place and needs no rollback copy.
     * A content:// destination has no such rename — the platform offers no atomic replace
     * — so the previous content is kept as a rollback copy and written back when the write
     * fails. That rollback only runs while this process is alive: a kill mid-write can
     * leave the document damaged, and the caller still reports the save as failed rather
     * than as a success.
     */
    fun writeBytes(
        context: Context,
        uri: Uri,
        bytes: ByteArray,
        verify: ((ByteArray) -> Boolean)? = null,
    ) {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI")
            replaceFileAtomically(File(path), bytes, verify)
            return
        }
        // Without the previous content there is nothing to restore; say so instead of
        // silently writing a document that cannot be rolled back.
        val previous = runCatching { readBytes(context, uri) }.getOrNull()
        if (previous == null) {
            Log.i(TAG, "No rollback copy could be read for this ${uri.scheme} document (${uri.authority})")
        }
        writeChecked(bytes, previous, verify) { data ->
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException("Provider returned no output stream")
            out.use {
                it.write(data)
                it.flush()
            }
        }
    }

    /** Overwrites the document with [text] as UTF-8. */
    fun writeText(context: Context, uri: Uri, text: String) =
        writeBytes(context, uri, text.toByteArray(Charsets.UTF_8))

    /**
     * Checks [bytes] with [verify] and only then writes them through [write], restoring
     * [previous] if that write fails. A failed check throws [DocumentNotWrittenException]
     * before the destination is touched, so a replacement that would not survive being read
     * back never replaces the last valid copy.
     */
    internal fun writeChecked(
        bytes: ByteArray,
        previous: ByteArray?,
        verify: ((ByteArray) -> Boolean)?,
        write: (ByteArray) -> Unit,
    ) {
        requireIntact(bytes, verify)
        writeGuarded(bytes, previous, write)
    }

    /**
     * Replaces a local file with [bytes] by writing a temporary file next to it and renaming
     * that over the document: a reader sees either the previous file or the complete new
     * one, never a half-written document, and a failure before the rename leaves the file
     * untouched. The temporary lives in the same directory, because a rename only replaces
     * within one filesystem.
     */
    internal fun replaceFileAtomically(
        target: File,
        bytes: ByteArray,
        verify: ((ByteArray) -> Boolean)? = null,
    ) {
        requireIntact(bytes, verify)
        val directory = target.parentFile
            ?: throw IOException("No directory to replace the document in")
        val temp = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                // Reach the storage before the rename: the new name must not end up
                // pointing at content that never landed.
                out.fd.sync()
            }
            moveOver(temp, target)
        } catch (failure: Throwable) {
            // The document still holds its previous content; drop the half-written copy.
            temp.delete()
            throw failure
        }
    }

    /** Renames [temp] over [target], replacing it. */
    private fun moveOver(temp: File, target: File) {
        val atomic = runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.exceptionOrNull()
        if (atomic == null) return
        // Some filesystems cannot rename over an existing file atomically. A plain replace
        // is still a rename (old document or new one, never half-written), just not
        // guaranteed atomic.
        Log.w(TAG, "Atomic replace not available (${atomic.javaClass.simpleName}); replacing directly")
        val plain = runCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.exceptionOrNull()
        if (plain != null && !temp.renameTo(target)) {
            throw IOException("Could not replace the document", plain)
        }
    }

    /**
     * Whether [bytes] is a complete zip package: it has entries, every entry inflates to its
     * end, and the end-of-central-directory record is present.
     *
     * This is the check the editors run before replacing a document, and it catches exactly
     * what would otherwise be written over a good file: a truncated or partially generated
     * package. It is not a semantic check — a package that passes can still differ from what
     * the editor showed, which is what the preservation suite and docs/docx-preservation.md
     * are for.
     */
    fun verifyZipPackage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val entries = runCatching {
            var count = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(16 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    // Reading to the end of the entry is what detects a truncated stream.
                    while (zip.read(buffer) != -1) {
                        // The entry's content only matters here through reaching its end.
                    }
                    zip.closeEntry()
                    count++
                    entry = zip.nextEntry
                }
            }
            count
        }.getOrDefault(-1)
        // ZipInputStream reads the local headers only, so a package whose central directory
        // was never written would pass that walk but still fail in Word and other readers.
        return entries > 0 && hasCentralDirectory(bytes)
    }

    /** Whether the end-of-central-directory record is there; a zip comment can push it back. */
    private fun hasCentralDirectory(bytes: ByteArray): Boolean {
        val from = (bytes.size - 65_536).coerceAtLeast(0)
        for (i in bytes.size - 22 downTo from) {
            if (bytes[i] == 'P'.code.toByte() &&
                bytes[i + 1] == 'K'.code.toByte() &&
                bytes[i + 2] == 0x05.toByte() &&
                bytes[i + 3] == 0x06.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun requireIntact(bytes: ByteArray, verify: ((ByteArray) -> Boolean)?) {
        if (verify == null || verify(bytes)) return
        throw DocumentNotWrittenException("Serialized document did not pass its integrity check")
    }

    /**
     * Writes [bytes] through [write], restoring [previous] if that write fails.
     * The original failure is rethrown either way, so callers never report success
     * for a write that did not complete.
     */
    internal fun writeGuarded(bytes: ByteArray, previous: ByteArray?, write: (ByteArray) -> Unit) {
        try {
            write(bytes)
        } catch (failure: Throwable) {
            if (previous != null) {
                try {
                    write(previous)
                } catch (rollback: Throwable) {
                    // Nothing more we can do; the caller reports the save as failed.
                    Log.w(TAG, "Rollback after a failed write also failed (${rollback.javaClass.simpleName})")
                }
            }
            throw failure
        }
    }

    // ---- Naming -------------------------------------------------------------

    /** The user-facing file name for a URI, falling back to the last path segment. */
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let { File(it).name } ?: "document"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    // ---- Long-lived access --------------------------------------------------

    /**
     * Takes the persistable URI grant the provider offers and returns the modes that stuck.
     * Safe to call for any URI: a plain file path needs no grant, and a provider that does
     * not offer persisted access simply yields an empty result.
     */
    fun persistAccess(context: Context, uri: Uri): Set<UriAccess> {
        if (uri.scheme == "file") return setOf(UriAccess.READ, UriAccess.WRITE)
        val modes = persistAccess(ResolverGrants(context.contentResolver), uri.toString())
        if (modes.isEmpty()) {
            // No long-lived access: this document is readable only while the transient
            // grant lives. Logged without the document name or path.
            Log.i(TAG, "No persistable grant for this ${uri.scheme} document (${uri.authority})")
        }
        return modes
    }

    /** The resolver operations [persistAccess] needs, so a test double can stand in for it. */
    internal interface GrantOps {
        /** Persists [modes] for [uri]; throws SecurityException when there is no grant to persist. */
        fun takePersistable(uri: String, modes: Set<UriAccess>)

        /** The modes currently persisted for [uri]. */
        fun persisted(uri: String): Set<UriAccess>
    }

    /**
     * Asks for the modes a persisted grant can carry and reports what the platform kept.
     *
     * The platform is strict about the request: `UriPermission#takePersistableModes()`
     * refuses the *whole* call when any requested mode was not offered as persistable —
     * it returns false, logs, and throws nothing. Asking for read+write on a grant that
     * only carries read therefore persists nothing at all, which is how a document ends
     * up readable only until the next device restart. So: read first, then read+write.
     *
     * A SecurityException means there was no grant record to persist (a non-persistable
     * "Open with" hand-off, or a grant that already expired) — expected, and handled by
     * the caller through [loadFailure] when the transient access is gone too.
     */
    internal fun persistAccess(ops: GrantOps, uri: String): Set<UriAccess> {
        for (modes in listOf(setOf(UriAccess.READ), setOf(UriAccess.READ, UriAccess.WRITE))) {
            try {
                ops.takePersistable(uri, modes)
            } catch (_: SecurityException) {
                // Nothing to persist for this mode set; the read-back below says what we hold.
            }
        }
        return ops.persisted(uri)
    }

    private class ResolverGrants(private val resolver: ContentResolver) : GrantOps {

        override fun takePersistable(uri: String, modes: Set<UriAccess>) {
            var flags = 0
            if (UriAccess.READ in modes) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (UriAccess.WRITE in modes) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(Uri.parse(uri), flags)
        }

        override fun persisted(uri: String): Set<UriAccess> {
            val modes = mutableSetOf<UriAccess>()
            for (permission in resolver.persistedUriPermissions) {
                if (permission.uri.toString() != uri) continue
                if (permission.isReadPermission) modes.add(UriAccess.READ)
                if (permission.isWritePermission) modes.add(UriAccess.WRITE)
            }
            return modes
        }
    }

    // ---- Diagnostics --------------------------------------------------------

    /**
     * One line about a document that could not be opened: the [stage] it failed at and the
     * exception classes behind it. An exception's own message can name the document, its
     * path or its provider, so only the classes are logged — enough to tell a parser
     * refusal from a provider or storage failure without recording anything about the file.
     *
     * A failed read is already logged by [readBytes]; this covers the stages after it, so
     * one failure never produces two lines.
     */
    fun logLoadFailure(stage: String, failure: Throwable) {
        Log.w(TAG, "Could not open the document at $stage: ${failure.classChain()}")
    }

    /** The exception classes of [this] failure's cause chain, outermost first. */
    private fun Throwable.classChain(): String =
        generateSequence(this) { it.cause }
            .take(4)
            .joinToString(" <- ") { it.javaClass.simpleName }

    // ---- User-facing failure text -------------------------------------------

    /** Long-lived modes currently persisted for [uri]; empty when none are held. */
    fun persistedAccess(context: Context, uri: Uri): Set<UriAccess> {
        if (uri.scheme == "file") return setOf(UriAccess.READ, UriAccess.WRITE)
        return ResolverGrants(context.contentResolver).persisted(uri.toString())
    }

    /** A failed load: what the user is told, and whether another read is worth offering. */
    data class LoadFailure(val message: String, val canRetry: Boolean)

    /**
     * Classifies a failed document read into a message and a retry decision.
     *
     * [readGrantHeld] is whether the app still holds a persisted READ grant for this URI
     * (see [persistedAccess]). It separates a revoked grant from a provider that refused a
     * read it was told to allow: calling the second one "your access expired" tells the user
     * to fix a permission that was never broken, so the wording differs and only the cases a
     * second read can fix offer one.
     *
     * No message includes provider names, URIs or paths: the platform's own text ("Permission
     * Denial: reading ... requires that you obtain access using ACTION_OPEN_DOCUMENT") names
     * things the user has no use for.
     */
    fun loadFailure(
        failure: Throwable?,
        fallback: String,
        readGrantHeld: Boolean = false,
    ): LoadFailure = when {
        failure is SecurityException && readGrantHeld -> LoadFailure(
            "This file could not be opened just now, although the app still has permission for it. Try again, or open it again.",
            canRetry = true,
        )
        failure is SecurityException -> LoadFailure(EXPIRED_ACCESS, canRetry = true)
        failure is FileNotFoundException && failure.message?.contains("EACCES") == true ->
            LoadFailure("Files access is off. Turn it on in Settings to open this file.", canRetry = true)
        // Providers report a revoked read as a denial or as "not found", depending on the
        // provider. Both mean the grant is gone, not that the document moved.
        failure is FileNotFoundException && failure.message?.contains("Permission") == true ->
            LoadFailure(EXPIRED_ACCESS, canRetry = true)
        // A provider that died, stalled or held no stream for the document: the resolver
        // reports it as an unopenable file. The document itself may be perfectly fine.
        failure is FileNotFoundException &&
            (failure.message == NO_STREAM || failure.message?.startsWith("Unable to open content") == true) ->
            LoadFailure(PROVIDER_FAILURE, canRetry = true)
        failure is FileNotFoundException ->
            LoadFailure("This file is no longer available. It may have been moved or deleted.", canRetry = false)
        else -> LoadFailure(fallback, canRetry = true)
    }

    /** A message for a failed document write; the wording matches the editors' save dialog. */
    fun saveError(failure: Throwable?, fallback: String): String = when (failure) {
        // Refused before anything was written: the file still holds its last valid content.
        is DocumentNotWrittenException ->
            "The updated document could not be written safely, so the file was left unchanged. Use Export to save a copy."
        is SecurityException, is FileNotFoundException -> "This file can't be changed. Use Export to save a copy."
        else -> fallback
    }
}
