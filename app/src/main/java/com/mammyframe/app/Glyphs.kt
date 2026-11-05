package com.mammyframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Simple hand-drawn button symbols (no icon library needed). */
enum class Glyph { PLAY, PAUSE, BACK, FORWARD, CLOSE, TURN_LEFT, TURN_RIGHT, CHECK }

@androidx.compose.runtime.Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.PLAY -> drawPath(Path().apply {
                moveTo(w * 0.27f, h * 0.16f)
                lineTo(w * 0.86f, h * 0.5f)
                lineTo(w * 0.27f, h * 0.84f)
                close()
            }, color)
            Glyph.PAUSE -> {
                val bw = w * 0.22f
                val r = CornerRadius(bw * 0.3f)
                drawRoundRect(color, Offset(w * 0.22f, h * 0.16f), Size(bw, h * 0.68f), r)
                drawRoundRect(color, Offset(w * 0.56f, h * 0.16f), Size(bw, h * 0.68f), r)
            }
            Glyph.BACK -> drawPath(Path().apply {
                moveTo(w * 0.64f, h * 0.14f)
                lineTo(w * 0.3f, h * 0.5f)
                lineTo(w * 0.64f, h * 0.86f)
            }, color, style = stroke)
            Glyph.FORWARD -> drawPath(Path().apply {
                moveTo(w * 0.36f, h * 0.14f)
                lineTo(w * 0.7f, h * 0.5f)
                lineTo(w * 0.36f, h * 0.86f)
            }, color, style = stroke)
            Glyph.TURN_LEFT, Glyph.TURN_RIGHT -> {
                // Three quarters of a circle with an arrow head at the bottom. "Right" goes
                // clockwise (arrow pointing left at the bottom); "left" is its mirror image.
                val right = glyph == Glyph.TURN_RIGHT
                fun mx(x: Float) = if (right) x else w - x
                val inset = w * 0.2f
                val r = (w - 2 * inset) / 2
                drawArc(
                    color, startAngle = if (right) 180f else 0f, sweepAngle = if (right) 270f else -270f,
                    useCenter = false, topLeft = Offset(inset, inset), size = Size(2 * r, 2 * r),
                    style = Stroke(w * 0.1f, cap = StrokeCap.Round)
                )
                val by = inset + 2 * r
                drawPath(Path().apply {
                    moveTo(mx(w * 0.5f - w * 0.17f), by)
                    lineTo(mx(w * 0.5f + w * 0.03f), by - h * 0.15f)
                    lineTo(mx(w * 0.5f + w * 0.03f), by + h * 0.15f)
                    close()
                }, color)
            }
            Glyph.CHECK -> drawPath(Path().apply {
                moveTo(w * 0.18f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.26f)
            }, color, style = stroke)
            Glyph.CLOSE -> {
                drawLine(color, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), w * 0.12f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), w * 0.12f, StrokeCap.Round)
            }
        }
    }
}
