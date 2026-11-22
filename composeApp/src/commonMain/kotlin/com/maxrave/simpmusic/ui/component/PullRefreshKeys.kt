package com.maxrave.simpmusic.ui.component

import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * Puts the pull-to-refresh arrow away when the page is driven by keys (a TV remote, a keyboard).
 *
 * Moving focus up at the top of a list overscrolls it, and pull-to-refresh counts that as a pull. A
 * finger ends a pull by lifting; keys never do, so the arrow stayed on screen for good. A refresh
 * that is really running keeps its spinner.
 */
@Composable
fun HidePullIndicatorOnKeys(
    state: PullToRefreshState,
    isRefreshing: Boolean,
) {
    val inputModeManager = LocalInputModeManager.current
    val refreshing by rememberUpdatedState(isRefreshing)
    LaunchedEffect(state, inputModeManager) {
        snapshotFlow {
            inputModeManager.inputMode == InputMode.Keyboard && !refreshing && state.distanceFraction > 0f
        }.collect { stuck ->
            if (stuck) state.animateToHidden()
        }
    }
}
