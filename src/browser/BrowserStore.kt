package dev.kern.browser

import android.content.Context
import dev.kern.shared.io.RecentDocuments

/**
 * Tiny persistence for the browser, backed by SharedPreferences (no extra
 * dependency). Keeps the set of pinned file paths. Recently opened documents live
 * in [RecentDocuments], because an editor records them once it has loaded the file,
 * whatever picked it (scan, system picker, "Open with").
 *
 * SharedPreferences hands back the SAME Set instance for `getStringSet`, so it is
 * never mutated in place - every write builds a fresh set.
 */
class BrowserStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kern_browser", Context.MODE_PRIVATE)

    fun pins(): Set<String> = prefs.getStringSet(KEY_PINS, emptySet()).orEmpty().toSet()

    /** Toggles [path]'s pinned state; returns true if it is now pinned. */
    fun togglePin(path: String): Boolean {
        val set = pins().toMutableSet()
        val pinned = if (!set.add(path)) {
            set.remove(path); false
        } else true
        prefs.edit().putStringSet(KEY_PINS, set).apply()
        return pinned
    }

    /** Unpins [path] and drops it from the recent list (used after a delete). */
    fun forget(path: String) {
        val set = pins().toMutableSet().apply { remove(path) }
        prefs.edit().putStringSet(KEY_PINS, set).apply()
        RecentDocuments.remove(appContext, path)
    }

    private companion object {
        const val KEY_PINS = "pins"
    }
}
