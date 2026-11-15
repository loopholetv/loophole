package io.github.loopholetv.ui.phone

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test fun encodesTheUrl() {
        val url = "http://192.168.1.24:8765/"
        val m = qrMatrix(url, 240)
        assertEquals(240, m.width)
        assertEquals(240, m.height)
        val pixels = IntArray(m.width * m.height) { i ->
            if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, pixels)))
        val text = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(url, text)
    }

    @Test fun smallestSizeGivesOneCellPerModuleWithAOneModuleMargin() {
        val m = qrMatrix("http://192.168.1.24:8765/", 0)
        // Version 2 (25 modules) at level M, plus one module of margin each side.
        assertEquals(27, m.width)
        assertFalse(m[0, 0])
        assertTrue(m[1, 1])
    }
}
