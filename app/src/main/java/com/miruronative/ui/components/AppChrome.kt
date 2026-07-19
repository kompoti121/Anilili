package com.miruronative.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Shared visibility signal for screen chrome while the user is actively browsing a list. */
val LocalAppChromeVisible = compositionLocalOf { true }

/** Keeps each screen's own top bar in sync with the root navigation bar. */
@Composable
fun ScrollAwareTopBar(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = LocalAppChromeVisible.current,
        enter = slideInVertically(tween(180)) { -it } + fadeIn(tween(140)),
        exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(110)),
    ) {
        content()
    }
}
