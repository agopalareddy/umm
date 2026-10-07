package io.github.agopalareddy.umm.linux

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** The app's speech-bubble mark (the Android launcher icon's path) drawn in a color per [TrayState]. */
object TrayIcons {
    private const val MARK_LEFT = 30.0
    private const val MARK_TOP = 32.0
    private const val MARK_WIDTH = 48.0
    private const val MARK_HEIGHT = 47.0

    private val mark: Area = Area(RoundRectangle2D.Double(30.0, 32.0, 48.0, 38.0, 24.0, 24.0)).apply {
        add(
            Area(
                Path2D.Double().apply {
                    moveTo(50.0, 70.0)
                    lineTo(38.0, 79.0)
                    lineTo(41.0, 69.0)
                    closePath()
                },
            ),
        )
        listOf(
            Rectangle2D.Double(41.5, 46.0, 4.0, 10.0),
            Rectangle2D.Double(48.5, 41.0, 4.0, 20.0),
            Rectangle2D.Double(55.5, 44.0, 4.0, 14.0),
            Rectangle2D.Double(62.5, 48.0, 4.0, 6.0),
        ).forEach { subtract(Area(it)) }
    }

    private fun color(state: TrayState) = when (state) {
        TrayState.IDLE -> Color(0x8E, 0x9B, 0xBF)
        TrayState.RECORDING -> Color(0xE5, 0x48, 0x4D)
        TrayState.BUSY -> Color(0xF5, 0xA5, 0x24)
    }

    /** A square [size] x [size] icon as ARGB bytes in network order, the format StatusNotifierItem expects. */
    fun pixmap(state: TrayState, size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val scale = size / MARK_WIDTH
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color(state)
            val offsetY = (size - MARK_HEIGHT * scale) / 2
            g.fill(
                AffineTransform().apply {
                    translate(0.0, offsetY)
                    scale(scale, scale)
                    translate(-MARK_LEFT, -MARK_TOP)
                }.createTransformedShape(mark),
            )
        } finally {
            g.dispose()
        }
        val bytes = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val argb = image.getRGB(x, y)
            val i = (y * size + x) * 4
            bytes[i] = (argb ushr 24).toByte()
            bytes[i + 1] = (argb ushr 16).toByte()
            bytes[i + 2] = (argb ushr 8).toByte()
            bytes[i + 3] = argb.toByte()
        }
        return bytes
    }
}
