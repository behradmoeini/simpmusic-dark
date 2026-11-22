package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/** True when the app runs on a TV: driven by a remote's arrow keys, seen from across the room. */
@Composable
expect fun isTelevision(): Boolean
