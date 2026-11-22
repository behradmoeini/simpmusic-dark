package com.maxrave.simpmusic.expect.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun isTelevision(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
}
