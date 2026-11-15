package io.github.loopholetv.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/**
 * [text] as a QR code, [size] pixels square with a one-module margin. A [size] smaller than the
 * code gives one cell per module.
 */
fun qrMatrix(text: String, size: Int): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    size,
    size,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
)

/**
 * [text] as a QR code, black on white with a white border. Each module is drawn as a whole number
 * of pixels, so the code stays sharp at any size.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrMatrix(text, 0) }
    Box(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = modules.width
            val cell = floor(minOf(size.width, size.height) / n).coerceAtLeast(1f)
            val left = floor((size.width - cell * n) / 2)
            val top = floor((size.height - cell * n) / 2)
            for (y in 0 until n) {
                var x = 0
                while (x < n) {
                    if (!modules[x, y]) {
                        x++
                        continue
                    }
                    // One rectangle per run of dark modules in the row.
                    val start = x
                    while (x < n && modules[x, y]) x++
                    drawRect(Color.Black, Offset(left + start * cell, top + y * cell), Size((x - start) * cell, cell))
                }
            }
        }
    }
}
