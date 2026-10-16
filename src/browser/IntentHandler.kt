package dev.kern.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kern.Destinations
import dev.kern.shared.DocumentFormat
import dev.kern.shared.io.DocumentIo

/**
 * Resolves an inbound Intent (typically ACTION_VIEW / ACTION_EDIT from "Open with")
 * into a NavHost start route, so external launches land directly on the editor.
 *
 * The caller's grant is transient: the platform drops it when the device restarts, while
 * the navigation route — and the URI inside it — outlives the grant in restored state. So
 * the persistable grant is taken here, at the moment the URI arrives. If the provider does
 * not offer one, the editor reports the expired access when it happens and points the user
 * back to the file list instead of showing the platform's permission-denial text.
 */
object IntentHandler {

    data class DeepLink(val format: DocumentFormat, val route: String)

    fun resolve(intent: Intent?, context: Context): DeepLink? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return null

        val uri: Uri = intent.data ?: return null
        val format = DocumentFormat.fromMimeType(intent.type)
            ?: DocumentFormat.fromExtension(uri.lastPathSegment?.substringAfterLast('.') ?: "")
            ?: return null

        DocumentIo.persistAccess(context, uri)

        val encoded = Uri.encode(uri.toString())
        return DeepLink(format, "${Destinations.routeFor(format)}/$encoded")
    }
}
