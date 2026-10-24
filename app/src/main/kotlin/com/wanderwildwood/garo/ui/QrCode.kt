package com.wanderwildwood.garo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Modules of white round the code, on each side. The QR standard asks for four. */
const val QR_QUIET = 4

/** The dark modules of a QR code for [text], row by row, or null when the text does not fit in one. */
fun qrModules(text: String): List<BooleanArray>? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
    )
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}.getOrNull()

/**
 * A QR code, black on white with the four-module quiet zone around it, as large as the room it
 * is given allows (a square, centred). Drawn in whole device pixels per module so no edge is
 * blurred, and with the white square always painted: a code on the grey of a panel will not scan.
 * Give it a size with the modifier; it fills it.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) } ?: return
    val n = modules.size + 2 * QR_QUIET
    Canvas(modifier = modifier) {
        val cell = kotlin.math.floor(minOf(size.width, size.height) / n).coerceAtLeast(1f)
        val side = cell * n
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        drawRect(Color.White, Offset(left, top), Size(side, side))
        for (y in modules.indices) for (x in modules[y].indices) {
            if (modules[y][x]) {
                drawRect(Color.Black, Offset(left + (x + QR_QUIET) * cell, top + (y + QR_QUIET) * cell), Size(cell, cell))
            }
        }
    }
}
