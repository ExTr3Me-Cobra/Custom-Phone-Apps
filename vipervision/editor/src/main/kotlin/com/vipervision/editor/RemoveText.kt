package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.t8rin.neural_tools.ocr.PaddleOCR
import kotlin.math.hypot
import kotlin.math.max

/**
 * Remove text: PaddleOCR finds every bit of text (shown in red), tap any to keep it, and LaMa
 * fills in where the rest was.
 */
class RemoveTextTool(private val preview: Bitmap, boxes: List<List<Offset>>) : StudioTool {
    override val stepName = "Remove text"

    /** Each bit of text as its four corners (fractions of the photo), and whether it goes. */
    private val texts = boxes
    private var chosen by mutableStateOf(List(boxes.size) { true })

    val count: Int get() = texts.size

    private fun inside(p: Offset, q: List<Offset>): Boolean {
        var inside = false
        var j = q.size - 1
        for (i in q.indices) {
            val a = q[i]
            val b = q[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun tap(p: Offset) {
        val i = texts.indexOfFirst { inside(p, grown(it, 0.25f)) }
        if (i >= 0) chosen = chosen.toMutableList().also { it[i] = !it[i] }
    }

    /** [q] made bigger by [by] of its height all round (text has strokes and shadows past its box). */
    private fun grown(q: List<Offset>, by: Float): List<Offset> {
        val cx = q.sumOf { it.x.toDouble() }.toFloat() / q.size
        val cy = q.sumOf { it.y.toDouble() }.toFloat() / q.size
        // The text's height: the box's shorter side.
        val height = max(1e-4f, minOf(hypot(q[1].x - q[0].x, q[1].y - q[0].y), hypot(q[2].x - q[1].x, q[2].y - q[1].y)))
        return q.map { o ->
            val dx = o.x - cx
            val dy = o.y - cy
            val d = max(1e-6f, hypot(dx, dy))
            Offset(o.x + dx / d * height * by, o.y + dy / d * height * by)
        }
    }

    override suspend fun render(context: Context, full: Bitmap): Bitmap {
        val w = full.width.toFloat()
        val h = full.height.toFloat()
        val mask = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(AColor.BLACK)
        val paint = Paint().apply { color = AColor.WHITE; isAntiAlias = true; style = Paint.Style.FILL }
        texts.forEachIndexed { i, q ->
            if (!chosen[i]) return@forEachIndexed
            val g = grown(q, 0.18f)
            val path = Path().apply {
                moveTo(g[0].x * w, g[0].y * h)
                for (k in 1 until g.size) lineTo(g[k].x * w, g[k].y * h)
                close()
            }
            canvas.drawPath(path, paint)
        }
        ObjectEraser.chooseFillSize(context)
        return ObjectEraser.eraseMask(full, mask).also { mask.recycle() }
    }

    @Composable
    override fun Screen(onCancel: () -> Unit, onApply: () -> Unit) {
        val red = Color(0xFFFF3030)
        StudioFrame(
            title = "Remove text",
            shown = preview,
            photoWidth = preview.width,
            photoHeight = preview.height,
            onCancel = onCancel,
            onApply = onApply,
            onTap = ::tap,
            overlay = { rect ->
                texts.forEachIndexed { i, q ->
                    val g = grown(q, 0.18f)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(rect.left + g[0].x * rect.width, rect.top + g[0].y * rect.height)
                        for (k in 1 until g.size) lineTo(rect.left + g[k].x * rect.width, rect.top + g[k].y * rect.height)
                        close()
                    }
                    if (chosen[i]) drawPath(path, red.copy(alpha = 0.45f), style = Fill)
                    drawPath(path, if (chosen[i]) red else Color.White.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
                }
            }
        ) {
            val going = chosen.count { it }
            StudioHint(
                if (texts.isEmpty()) "No text found in this photo."
                else "Red text is removed: $going of ${texts.size}. Tap any to keep it."
            )
            if (texts.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Remove all", selected = going == texts.size) { chosen = List(texts.size) { true } }
                    Pill("Keep all", selected = going == 0) { chosen = List(texts.size) { false } }
                }
            }
        }
    }

    companion object {
        /** Finds the text in [preview] (PaddleOCR must be downloaded). */
        fun find(preview: Bitmap): RemoveTextTool {
            val image = preview.scaledToFit(1600)
            val w = image.width.toFloat()
            val h = image.height.toFloat()
            val boxes = PaddleOCR.detectText(image, PaddleOCR.Model.English)
                .filter { it.size == 4 }
                .map { q -> q.map { p: PointF -> Offset(p.x / w, p.y / h) } }
            if (image !== preview) image.recycle()
            return RemoveTextTool(preview, boxes)
        }
    }
}
