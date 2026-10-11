package io.github.agopalareddy.umm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.min

fun qrMatrix(text: String): Array<BooleanArray> {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.M)
    val bm = qr.matrix
    val width = bm.width
    val height = bm.height
    return Array(height) { y ->
        BooleanArray(width) { x ->
            bm.get(x, y).toInt() == 1
        }
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { qrMatrix(text) }.getOrNull() } ?: return
    val n = matrix.size
    if (n == 0) return
    val quietZone = 2
    val totalModules = n + quietZone * 2
    Canvas(modifier = modifier) {
        val cellSize = min(size.width, size.height) / totalModules
        val offsetX = (size.width - cellSize * totalModules) / 2f + quietZone * cellSize
        val offsetY = (size.height - cellSize * totalModules) / 2f + quietZone * cellSize

        drawRect(Color.White)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[y][x]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offsetX + x * cellSize, offsetY + y * cellSize),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
        }
    }
}
