package com.voxcrypt.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voxcrypt.app.ui.VoxcryptNavHost
import com.voxcrypt.app.ui.theme.VoxcryptTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<Uri?>(null)
    private val container get() = (application as VoxcryptApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the screenshot policy before the first frame is drawn.
        applyScreenPolicy(container.settings.values.value.blockScreenshots)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.values.collect { applyScreenPolicy(it.blockScreenshots) }
            }
        }

        setContent {
            VoxcryptTheme {
                val uri by incoming.collectAsStateWithLifecycle()
                VoxcryptNavHost(incoming = uri, onIncomingConsumed = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Android blocks microphone access for background apps: pause cleanly
        // instead of silently recording nothing. Not on rotation.
        if (!isChangingConfigurations) {
            container.recorder.pause("Mis en pause : l'application est passée en arrière-plan.")
        }
    }

    /** .vxc received through "Open with" or "Share to VOXCRYPT". */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) incoming.value = uri
    }

    private fun applyScreenPolicy(block: Boolean) {
        if (block) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!block)
    }
}
