package dev.kern

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.kern.browser.IntentHandler
import dev.kern.shared.settings.KernSettings
import dev.kern.shared.settings.LocalKernSettings
import dev.kern.shared.theme.KernTheme

/**
 * The single Activity. Hosts the Compose NavHost and resolves any inbound
 * "Open with" intent into a direct-to-editor start destination — both when it
 * launches the app and when it arrives on an app that is already running.
 */
class MainActivity : ComponentActivity() {

    /**
     * A document opened while the app is already running. Held here rather than navigated
     * straight from [onNewIntent] because the controller belongs to the composition: one
     * built outside it would lose its saved back stack on process death.
     */
    private var pendingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If launched via "Open with", jump straight to the right editor.
        val deepLink = IntentHandler.resolve(intent, this)
        val settings = KernSettings.create(applicationContext)

        setContent {
            CompositionLocalProvider(LocalKernSettings provides settings) {
                KernTheme(
                    themeMode = settings.themeMode,
                    accent = settings.accent,
                    density = settings.density,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val navController = rememberNavController()
                        LaunchedEffect(pendingDeepLink) {
                            val route = pendingDeepLink ?: return@LaunchedEffect
                            pendingDeepLink = null
                            // Pushed on top of the stack, not popped back to the start
                            // destination: the editor already there keeps its ViewModel, so a
                            // second "Open with" cannot silently discard its unsaved edits.
                            // Back returns to it (see ADR 0003).
                            navController.navigate(route)
                        }
                        KernNavHost(
                            startDestination = deepLink?.route ?: Destinations.BROWSER,
                            navController = navController,
                        )
                    }
                }
            }
        }
    }

    /** A second "Open with" opens that document instead of being ignored. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val deepLink = IntentHandler.resolve(intent, this) ?: return
        pendingDeepLink = deepLink.route
    }
}
