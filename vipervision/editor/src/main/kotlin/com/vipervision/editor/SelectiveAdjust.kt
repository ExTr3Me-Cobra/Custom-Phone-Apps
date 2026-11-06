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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/** The Adjust tab's dials that make sense for part of a photo (not Vignette). */
private val SelectiveAdjustments = Adjustment.entries.filter { it != Adjustment.Vignette }

/**
 * Selective adjust: tap things in the photo (SAM 2.1 finds the whole object, shown in red), then
 * use the Adjust tab's dials on only those (or of everything else).
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
    /** The same dials as the Adjust tab, for the selection only. */
    var inside by mutableStateOf(Adjustments())
    /** Everything else's own dials (separate from the selection's). */
    var outside by mutableStateOf(Adjustments())

    /** The dials being changed: the selection's, or everything else's. */
    var adjustments: Adjustments
        get() = if (invert) outside else inside
        set(value) {
            if (invert) outside = value else inside = value
        }
    var picked by mutableStateOf(Adjustment.Brightness)
    private var shown by mutableStateOf<Bitmap?>(null)

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
                        SoftMask.ofAlpha(seg).also {
                            seg.recycle()
                            if (closing) sam.close()
                        }
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

    override suspend fun render(context: Context, full: Bitmap): Bitmap {
        val mask = selection
        val w = full.width.toFloat()
        val h = full.height.toFloat()
        return render(context, full, inside, outside) { x, y -> mask?.at((x + 0.5f) / w, (y + 0.5f) / h) ?: 0f }
    }

    /**
     * [image] with [inA] (the Adjust tab's own filters) where the selection is and [outA]
     * everywhere else; [weight] is how much each pixel is selection.
     */
    private suspend fun render(context: Context, image: Bitmap, inA: Adjustments, outA: Adjustments, weight: (Int, Int) -> Float): Bitmap {
        if (inA.isIdentity && outA.isIdentity) return image
        val ins = if (inA.isIdentity) null else withContext(Dispatchers.Default) { applyAdjustments(context, image, inA) }
        val outs = if (outA.isIdentity) null else withContext(Dispatchers.Default) { applyAdjustments(context, image, outA) }
        val w = image.width
        val h = image.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // A band of rows at a time, so even a very big photo needs only a few small buffers
        // (whole-photo pixel copies of three versions ran phones out of memory).
        val band = 64
        val c = IntArray(w * band)
        val a = IntArray(w * band)
        val o = IntArray(w * band)
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(band, h - y0)
            image.getPixels(c, 0, w, 0, y0, w, rows)
            ins?.getPixels(a, 0, w, 0, y0, w, rows)
            outs?.getPixels(o, 0, w, 0, y0, w, rows)
            val top = y0
            forRows(rows) { r ->
                var i = r * w
                for (x in 0 until w) {
                    val m = weight(x, top + r)
                    val p = c[i]
                    val inside = if (ins != null) a[i] else p
                    val outside = if (outs != null) o[i] else p
                    c[i] = when {
                        m >= 0.998f -> inside
                        m <= 0.002f -> outside
                        else -> rgb(
                            outside.red8 + (inside.red8 - outside.red8) * m,
                            outside.green8 + (inside.green8 - outside.green8) * m,
                            outside.blue8 + (inside.blue8 - outside.blue8) * m
                        )
                    }
                    i++
                }
            }
            result.setPixels(c, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        ins?.takeIf { it !== image }?.recycle()
        outs?.takeIf { it !== image }?.recycle()
        return result
    }

    /** Set when closed while a selection was still being worked out: it closes SAM when done. */
    @Volatile
    private var closing = false

    override fun close() {
        // Never while SAM is busy (closing it mid-run crashes in native code).
        if (samLock.tryLock()) {
            try { sam.close() } finally { samLock.unlock() }
        } else {
            closing = true
        }
    }

    @Composable
    override fun Screen(onCancel: () -> Unit, onApply: () -> Unit) {
        val scope = rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        val small = remember { preview.scaledToFit(1080) }
        LaunchedEffect(Unit) {
            // The selection as a weight per preview pixel, remade only when it changes.
            var weights = FloatArray(0)
            var weightsFor: SoftMask? = null
            var made = false
            // Each change is shown as soon as the one before is done (none are thrown away half made).
            snapshotFlow { Triple(selection, inside, outside) }.conflate().collect { (mask, inA, outA) ->
                shown = withContext(Dispatchers.Default) {
                    if (!made || weightsFor !== mask) {
                        val w = small.width
                        val h = small.height
                        weights = FloatArray(w * h) { i -> mask?.at(((i % w) + 0.5f) / w, ((i / w) + 0.5f) / h) ?: 0f }
                        weightsFor = mask
                        made = true
                    }
                    val ws = weights
                    render(context, small, inA, outA) { x, y -> ws[y * small.width + x] }
                }
            }
        }
        val tint = highlight
        val tintImage = remember(tint) { tint?.asImageBitmap() }
        StudioFrame(
            title = "Selective adjust",
            shown = shown,
            original = small,
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
                Pill("Show red", selected = showHighlight) { showHighlight = !showHighlight }
                Pill("Selection", selected = !invert) { invert = false }
                Pill("Everything else", selected = invert) { invert = true }
                if (objects.isNotEmpty()) Pill("Clear", selected = false) { clear() }
            }
            // The Adjust tab's bubbles and wheel; moving one hides the red so the change can be seen.
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(SelectiveAdjustments.size) { i ->
                    val adjustment = SelectiveAdjustments[i]
                    AdjustBubble(
                        adjustment = adjustment,
                        value = adjustments[adjustment],
                        selected = adjustment == picked,
                        onClick = {
                            if (adjustment == picked && adjustments[adjustment] != 0) adjustments = adjustments.with(adjustment, 0)
                            else picked = adjustment
                        }
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            ValueWheel(
                value = adjustments[picked],
                onValueChange = {
                    showHighlight = false
                    adjustments = adjustments.with(picked, it)
                },
                onValueChangeFinished = {},
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}
