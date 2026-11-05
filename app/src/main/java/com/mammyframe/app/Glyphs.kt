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
enum class Glyph { PLAY, PAUSE, BACK, FORWARD, CLOSE }

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
            Glyph.CLOSE -> {
                drawLine(color, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), w * 0.12f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), w * 0.12f, StrokeCap.Round)
            }
        }
    }
}
