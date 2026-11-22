package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * Desktop and Android TV show the pairing QR and receive sign-ins; an Android phone scans it and
 * sends them.
 */
@Composable
expect fun LoginSyncDialog(onDismiss: () -> Unit)

/** True where [LoginSyncDialog] is the receiving end: the Desktop, and Android on a TV. */
@Composable
expect fun isLoginSyncHost(): Boolean

/** A square QR code: [isDark] says whether the module at (x, y) is drawn. */
class QrMatrix(
    val size: Int,
    val isDark: (x: Int, y: Int) -> Boolean,
)

expect fun encodeQr(text: String): QrMatrix
