package com.share64.app.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.AppInfo
import com.share64.app.core.UpdateManager
import com.share64.app.core.isActionable
import com.share64.app.mobile.MainViewModel

private enum class Screen(val depth: Int) { Home(0), Settings(1), About(2), Scan(1) }

/**
 * Home -> Settings -> About slide in the direction of travel; the QR scanner rises from the bottom
 * like a sheet. System Back goes one level up.
 */
@Composable
fun Share64App(vm: MainViewModel) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Screen.Home.name) }
    // Kept here, not in the Home screen, so the chosen tab survives a trip to Settings or the scanner.
    var tabName by rememberSaveable { mutableStateOf(MainTab.Send.name) }
    val tab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.Send
    val screen = Screen.entries.firstOrNull { it.name == route } ?: Screen.Home
    fun go(to: Screen) { route = to.name }
    BackHandler(enabled = screen != Screen.Home) {
        go(if (screen == Screen.About) Screen.Settings else Screen.Home)
    }
    Box(modifier = Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.Scan) {
                (slideInVertically(spring(0.85f, Spring.StiffnessMediumLow)) { it / 5 } + fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 0.96f)) togetherWith
                    fadeOut(tween(160))
            } else if (initialState == Screen.Scan) {
                fadeIn(tween(240)) togetherWith
                    (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(200)) + scaleOut(tween(280), targetScale = 0.96f))
            } else if (targetState.depth > initialState.depth) {
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(240))) togetherWith
                    fadeOut(tween(160))
            } else {
                fadeIn(tween(240)) togetherWith
                    (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(200)))
            }
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.Home -> HomeScreen(
                vm = vm,
                tab = tab,
                onTabChange = { tabName = it.name },
                onOpenSettings = { go(Screen.Settings) },
                onOpenScan = { go(Screen.Scan) },
            )
            Screen.Settings -> SettingsScreen(vm = vm, onBack = { go(Screen.Home) }, onOpenAbout = { go(Screen.About) })
            Screen.About -> AboutScreen(onBack = { go(Screen.Settings) })
            Screen.Scan -> QrScanScreen(vm = vm, onBack = { go(Screen.Home) })
        }
    }
    // Receiving a "share with anyone" link covers every screen.
    WebDownloadOverlay(vm)
    }

    // In-app updater: opens by itself when a new release is found at launch.
    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    val updateSheetVisible by UpdateManager.sheetVisible.collectAsStateWithLifecycle()
    val installedVersion = remember { AppInfo.read(context).versionName }
    if (updateSheetVisible && updateState.isActionable) {
        UpdateSheet(
            state = updateState,
            currentVersion = installedVersion,
            onDismiss = { UpdateManager.dismissSheet() },
        )
    }
}
