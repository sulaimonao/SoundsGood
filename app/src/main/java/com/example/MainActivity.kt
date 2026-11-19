package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.SoundsGoodApp
import com.example.ui.theme.SoundsGoodTheme
import com.example.ui.viewmodel.SoundsGoodViewModel

class MainActivity : ComponentActivity() {

    private val app: SoundsGoodApplication
        get() = application as SoundsGoodApplication

    private val viewModel: SoundsGoodViewModel by viewModels {
        SoundsGoodViewModel.provideFactory(
            repository = app.libraryRepository,
            playbackClient = app.playbackClient,
            localSyncManager = app.localSyncManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairingIntentIfPresent(intent)
        setContent {
            SoundsGoodTheme {
                SoundsGoodApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntentIfPresent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.playbackClient.connect()
        viewModel.syncPermissionState(this, triggerScanIfGranted = true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            app.localSyncManager.cancelActiveSyncOnBackground()
        }
        app.playbackClient.disconnect()
        super.onStop()
    }

    private fun handlePairingIntentIfPresent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "soundsgood" && uri.host == "pair") {
            val rawUri = uri.toString()
            // Scrub the token-bearing URI after consumption so rotation/recreation never replays it.
            intent.data = null
            viewModel.handleIncomingPairingUri(rawUri)
        }
    }
}
