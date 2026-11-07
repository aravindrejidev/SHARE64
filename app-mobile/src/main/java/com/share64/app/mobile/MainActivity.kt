package com.share64.app.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
import com.share64.app.mobile.ui.HomeScreen
import com.share64.app.mobile.ui.Share64Theme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            Share64Theme {
                HomeScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ShareRuntime.setUiVisible(true)
        ShareService.start(this) // Android 12+: allowed because the Activity is visible
    }

    override fun onStop() {
        ShareRuntime.setUiVisible(false)
        super.onStop()
    }

    /** Files shared into SHARE64 from another app's share sheet. */
    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { vm.onSharedUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streamListExtra(intent)
                if (uris.isNotEmpty()) vm.onSharedUris(uris)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
}
