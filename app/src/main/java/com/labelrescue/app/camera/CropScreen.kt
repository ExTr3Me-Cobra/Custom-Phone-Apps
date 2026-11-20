package com.labelrescue.app.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.labelrescue.core.fusion.NormRect
import kotlin.math.max
import kotlin.math.min

/**
 * Optional crops: drag a box around the barcode and/or around the printed number. Applied to
 * every frame of the burst. Skipping uses the whole photo (the barcode is found automatically).
 */
@Composable
fun CropScreen(
    frame: Bitmap,
    frameCount: Int,
    barcode: NormRect?,
    number: NormRect?,
    onChange: (barcode: NormRect?, number: NormRect?) -> Unit,
    onStart: () -> Unit,
    onAddMore: () -> Unit,
) {
    var mode by remember { mutableStateOf(0) } // 0 = barcode, 1 = number
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$frameCount frames. Optionally drag a box around the barcode and the number, then start.", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 52.dp)) { Text("Barcode area") }
            SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 52.dp)) { Text("Number area") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
            val scale = min(boxW / frame.width, boxH / frame.height)
            val dw = frame.width * scale; val dh = frame.height * scale
            val ox = (boxW - dw) / 2; val oy = (boxH - dh) / 2
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragEnd by remember { mutableStateOf<Offset?>(null) }
            fun norm(a: Offset, b: Offset): NormRect {
                fun nx(x: Float) = ((x - ox) / dw).coerceIn(0f, 1f)
                fun ny(y: Float) = ((y - oy) / dh).coerceIn(0f, 1f)
                return NormRect(nx(min(a.x, b.x)), ny(min(a.y, b.y)), nx(max(a.x, b.x)), ny(max(a.y, b.y)))
            }
            Image(frame.asImageBitmap(), "Captured label", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(
                Modifier.fillMaxSize().pointerInput(mode) {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val s = dragStart; val e = dragEnd
                            if (s != null && e != null) {
                                val r = norm(s, e)
                                if (r.right - r.left > 0.02f && r.bottom - r.top > 0.01f) {
                                    if (mode == 0) onChange(r, number) else onChange(barcode, r)
                                }
                            }
                            dragStart = null; dragEnd = null
                        },
                    )
                },
            ) {
                fun draw(r: NormRect?, color: Color) {
                    r ?: return
                    drawRect(color, Offset(ox + r.left * dw, oy + r.top * dh), Size((r.right - r.left) * dw, (r.bottom - r.top) * dh), style = Stroke(4f))
                }
                draw(barcode, Color(0xFF00E5FF))
                draw(number, Color(0xFFFFC107))
                val s = dragStart; val e = dragEnd
                if (s != null && e != null) draw(norm(s, e), Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(null, null) }, Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Clear boxes") }
            OutlinedButton(onClick = onAddMore, Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Add photos") }
        }
        Button(onClick = onStart, Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(if (barcode == null && number == null) "Recover (whole photo)" else "Recover", style = MaterialTheme.typography.titleMedium)
        }
        Box {}
    }
}
