package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.t8rin.neural_tools.bgremover.MobileSAM
import com.t8rin.neural_tools.bgremover.Sam2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/** Selective adjust's dials (each -1..1, 0 = unchanged). */
data class LocalAdjust(val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f) {
    val isIdentity get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f

    /** [c] with the dials applied. */
    fun apply(c: Int): Int {
        var r = c.red8.toFloat()
        var g = c.green8.toFloat()
        var b = c.blue8.toFloat()
        r += warmth * 25f
        b -= warmth * 25f
        if (brightness != 0f) {
            val e = 2f.pow(-brightness * 0.9f)
            r = 255f * (r.coerceIn(0f, 255f) / 255f).pow(e)
            g = 255f * (g.coerceIn(0f, 255f) / 255f).pow(e)
            b = 255f * (b.coerceIn(0f, 255f) / 255f).pow(e)
        }
        if (contrast != 0f) {
            val k = 1f + contrast * 0.8f
            r = (r - 128f) * k + 128f
            g = (g - 128f) * k + 128f
            b = (b - 128f) * k + 128f
        }
        if (saturation != 0f) {
            val grey = 0.299f * r + 0.587f * g + 0.114f * b
            val k = 1f + saturation
            r = grey + (r - grey) * k
            g = grey + (g - grey) * k
            b = grey + (b - grey) * k
        }
        return rgb(r, g, b)
    }
}

/**
 * Selective adjust: tap things in the photo (SAM 2.1 finds the whole object, shown in red), then
 * change brightness, contrast, saturation and warmth of only those (or of everything else).
 */
class SelectiveAdjustTool(private val preview: Bitmap, private val sam: Sam2.Session) : StudioTool {
    override val stepName = "Selective adjust"

    /** The photo the masks are made at (a screen-sized copy). */
    private val work: Bitmap = preview.scaledToFit(1280)
    private val samLock = Mutex()

    private var objects by mutableStateOf<List<SoftMask>>(emptyList())
    private var selection by mutableStateOf<SoftMask?>(null)
    private var highlight by mutableStateOf<Bitmap?>(null)
    var showHighlight by mutableStateOf(true)
    var invert by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var brightness by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var warmth by mutableFloatStateOf(0f)
    private var shown by mutableStateOf<Bitmap?>(null)

    private fun adjust() = LocalAdjust(brightness, contrast, saturation, warmth)

    /** A tap: unselects the object there, or selects the object there. */
    suspend fun tap(point: Offset) {
        if (busy) return
        busy = true
        try {
            val current = objects
            val hit = current.filter { it.at(point.x, point.y) >= 0.5f }
            objects = if (hit.isNotEmpty()) {
                current - hit.toSet()
            } else {
                val mask = withContext(Dispatchers.Default) {
                    samLock.withLock {
                        val white = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888).apply { eraseColor(AColor.WHITE) }
                        val seg = sam.segment(points = listOf(MobileSAM.Point(point.x, point.y, true)), outputImage = white)
                        white.recycle()
                        SoftMask.ofAlpha(seg).also { seg.recycle() }
                    }
                }
                if (mask.isEmpty) current else current + mask
            }
            rebuild()
            showHighlight = true
        } catch (e: Exception) {
            ViperLog.error("SELECT", "Selecting failed", e)
        } finally {
            busy = false
        }
    }

    fun clear() {
        objects = emptyList()
        rebuild()
    }

    private fun rebuild() {
        val list = objects
        if (list.isEmpty()) {
            selection = null
            highlight = null
            return
        }
        val w = list[0].width
        val h = list[0].height
        val union = SoftMask(w, h)
        for (m in list) for (i in union.values.indices) {
            if ((m.values[i].toInt() and 255) > (union.values[i].toInt() and 255)) union.values[i] = m.values[i]
        }
        selection = union
        highlight = union.tint(0xFF3030, 0.5f)
    }

    override suspend fun render(context: Context, full: Bitmap): Bitmap = render(full, selection, invert, adjust())

    private suspend fun render(image: Bitmap, mask: SoftMask?, invert: Boolean, a: LocalAdjust): Bitmap {
        if (a.isIdentity || (mask == null && !invert)) return image.copy(Bitmap.Config.ARGB_8888, false)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        return image.mapPixels { x, y, c ->
            val m0 = mask?.at((x + 0.5f) / w, (y + 0.5f) / h) ?: 0f
            val m = if (invert) 1f - m0 else m0
            if (m <= 0.002f) c else {
                val d = a.apply(c)
                if (m >= 0.998f) d else rgb(
                    c.red8 + (d.red8 - c.red8) * m, c.green8 + (d.green8 - c.green8) * m, c.blue8 + (d.blue8 - c.blue8) * m
                )
            }
        }
    }

    override fun close() = sam.close()

    @Composable
    override fun Screen(onCancel: () -> Unit, onApply: () -> Unit) {
        val scope = rememberCoroutineScope()
        val small = remember { preview.scaledToFit(1080) }
        LaunchedEffect(Unit) {
            snapshotFlow { Triple(selection, invert, adjust()) }.collectLatest { (mask, inv, a) ->
                delay(40)
                shown = withContext(Dispatchers.Default) { render(small, mask, inv, a) }
            }
        }
        val tint = highlight
        val tintImage = remember(tint) { tint?.asImageBitmap() }
        StudioFrame(
            title = "Selective adjust",
            shown = shown,
            photoWidth = preview.width,
            photoHeight = preview.height,
            onCancel = onCancel,
            onApply = onApply,
            onTap = { p -> scope.launch { tap(p) } },
            overlay = { rect ->
                if (showHighlight && tintImage != null) {
                    drawImage(
                        tintImage,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
                    )
                }
            },
            photoExtra = {
                if (busy) CircularProgressIndicator(color = LocalAccent.current, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
        ) {
            StudioHint(if (objects.isEmpty()) "Tap something to select it." else "Tap more things to add them, or a selected one to unselect it.")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Pill("Selection", selected = !invert) { invert = false }
                Pill("Everything else", selected = invert) { invert = true }
                Pill("Show red", selected = showHighlight) { showHighlight = !showHighlight }
                if (objects.isNotEmpty()) Pill("Clear", selected = false) { clear() }
            }
            // Moving a dial hides the red so the change can be seen.
            fun hide() { showHighlight = false }
            LabeledSlider("Brightness ${(brightness * 100).roundToInt()}", brightness, -1f..1f) { brightness = it; hide() }
            LabeledSlider("Contrast ${(contrast * 100).roundToInt()}", contrast, -1f..1f) { contrast = it; hide() }
            LabeledSlider("Saturation ${(saturation * 100).roundToInt()}", saturation, -1f..1f) { saturation = it; hide() }
            LabeledSlider("Warmth ${(warmth * 100).roundToInt()}", warmth, -1f..1f) { warmth = it; hide() }
        }
    }
}
