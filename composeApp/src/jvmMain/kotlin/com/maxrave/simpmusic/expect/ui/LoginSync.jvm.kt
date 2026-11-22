package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.maxrave.simpmusic.ui.component.LoginSyncHostDialog

@Composable
actual fun LoginSyncDialog(onDismiss: () -> Unit) = LoginSyncHostDialog(onDismiss)

@Composable
actual fun isLoginSyncHost(): Boolean = true

/** ZXing is a plain JVM library, so each platform encodes for itself. */
actual fun encodeQr(text: String): QrMatrix {
    val bits =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    return QrMatrix(bits.width) { x, y -> bits[x, y] }
}
