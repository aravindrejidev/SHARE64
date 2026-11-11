package com.share64.app.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.share64.app.core.AppFlavor
import com.share64.app.core.ShareRuntime
import com.share64.app.core.ShareService
import com.share64.app.core.UpdateManager
import com.share64.app.tv.ui.TvHomeScreen
import com.share64.app.tv.ui.TvTheme

class TvMainActivity : ComponentActivity() {
    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.init(applicationContext, AppFlavor.TV)
        setContent {
            TvTheme {
                TvHomeScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ShareRuntime.setUiVisible(true)
        ShareService.start(this)
        UpdateManager.checkOnLaunch()
        UpdateManager.onForeground()
    }

    override fun onStop() {
        ShareRuntime.setUiVisible(false)
        super.onStop()
    }
}
