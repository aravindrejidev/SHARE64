package com.share64.app.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.share64.app.mobile.MainViewModel

private enum class Screen { Home, Settings, About }

/** Home -> Settings -> About, with a slide in the direction of travel; system Back goes one level up. */
@Composable
fun Share64App(vm: MainViewModel) {
    var level by rememberSaveable { mutableStateOf(0) }
    val screen = Screen.entries[level.coerceIn(0, Screen.entries.size - 1)]
    BackHandler(enabled = level > 0) { level -= 1 }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
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
            Screen.Home -> HomeScreen(vm = vm, onOpenSettings = { level = 1 })
            Screen.Settings -> SettingsScreen(vm = vm, onBack = { level = 0 }, onOpenAbout = { level = 2 })
            Screen.About -> AboutScreen(onBack = { level = 1 })
        }
    }
}
