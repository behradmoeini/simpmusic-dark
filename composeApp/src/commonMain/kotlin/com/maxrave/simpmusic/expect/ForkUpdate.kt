package com.maxrave.simpmusic.expect

import androidx.compose.runtime.Composable

/**
 * Draws the forced-update screen in place of the whole app and returns true while this build is
 * behind the fork's `latest` release. Rechecked every time the app comes to the foreground.
 * Android only; Desktop always returns false.
 */
@Composable
expect fun ForkUpdateScreenIfRequired(): Boolean
