package dev.kern.shared.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

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
 * ([loadError] / [saveError]) rather than the raw platform exception text, which names
 * providers and PIDs the user has no use for.
 */
object DocumentIo {

    private const val TAG = "DocumentIo"

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
                ?: throw FileNotFoundException("Provider returned no input stream")
        } catch (failure: Throwable) {
            // Provider and failure kind only: no document name, path or content.
            Log.w(TAG, "Read failed (${failure.javaClass.simpleName}) from ${uri.authority}")
            throw failure
        }
    }

    /** Reads the whole document as UTF-8 text. */
    fun readText(context: Context, uri: Uri): String =
        String(readBytes(context, uri), Charsets.UTF_8)

    /**
     * Overwrites the document with [bytes].
     *
     * The replacement is serialized in full by the caller before this runs, and the
     * previous content is kept as a rollback copy so a write that fails part-way through
     * (provider error, disk full, revoked access) cannot leave the document truncated.
     * Neither SAF nor the platform file APIs guarantee an atomic replace, so this is the
     * strongest guarantee available without a provider-specific copy step: if the rollback
     * also fails, the save is reported as failed and the user is told.
     */
    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        val previous = runCatching { readBytes(context, uri) }.getOrNull()
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI")
            writeGuarded(bytes, previous) { File(path).writeBytes(it) }
            return
        }
        writeGuarded(bytes, previous) { data ->
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
     * the caller through [loadError] when the transient access is gone too.
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

    // ---- User-facing failure text -------------------------------------------

    /**
     * A message for a failed document read. Never includes provider names, URIs or paths:
     * the platform's own text ("Permission Denial: reading ... requires that you obtain
     * access using ACTION_OPEN_DOCUMENT") tells the user nothing they can act on.
     */
    fun loadError(failure: Throwable?, fallback: String): String = when (failure) {
        is SecurityException -> "Access to this file has expired. Open it again to continue."
        is FileNotFoundException ->
            if (failure.message?.contains("EACCES") == true) {
                "Files access is off. Turn it on in Settings to open this file."
            } else {
                "This file is no longer available. It may have been moved or deleted."
            }
        else -> fallback
    }

    /** A message for a failed document write; the wording matches the editors' save dialog. */
    fun saveError(failure: Throwable?, fallback: String): String = when (failure) {
        is SecurityException, is FileNotFoundException -> "This file can't be changed. Use Export to save a copy."
        else -> fallback
    }
}
