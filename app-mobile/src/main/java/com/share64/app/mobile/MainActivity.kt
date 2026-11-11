package com.share64.app.mobile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.AppFlavor
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
import com.share64.app.core.UpdateManager
import com.share64.app.mobile.ui.Share64App
import com.share64.app.mobile.ui.Share64Theme
import com.share64.app.mobile.ui.resolveDark

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.init(applicationContext, AppFlavor.PHONE)
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            val settings by vm.settingsState.collectAsStateWithLifecycle()
            val dark = resolveDark(settings.themeMode, isSystemInDarkTheme())

            // Keep status/navigation bar icon colors in sync with the in-app theme choice,
            // which may differ from the system setting.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }

            Share64Theme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                amoled = settings.amoled,
            ) {
                Share64App(vm)
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
        UpdateManager.checkOnLaunch()
        UpdateManager.onForeground() // continues an update after the person allowed installs in Settings
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

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
