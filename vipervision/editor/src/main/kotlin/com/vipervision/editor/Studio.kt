package com.vipervision.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/**
 * A full-screen AI tool on the photo as edited so far (Selective adjust, Remove text, Relight,
 * Face retouch): it shows its own screen, then [render] makes the result on the full-size photo
 * when Apply is tapped, which becomes one undoable step named [stepName].
 */
interface StudioTool {
    val stepName: String

    @Composable
    fun Screen(onCancel: () -> Unit, onApply: () -> Unit)

    /** The result on [full] (the photo as edited so far, full size). */
    suspend fun render(context: Context, full: Bitmap): Bitmap

    fun close() {}
}

/** The common layout: a top bar, the photo (tappable), then the tool's controls. */
@Composable
internal fun StudioFrame(
    title: String,
    shown: Bitmap?,
    photoWidth: Int,
    photoHeight: Int,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onTap: ((Offset) -> Unit)? = null,
    overlay: (DrawScope.(androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    photoExtra: @Composable BoxScope.() -> Unit = {},
    controls: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val accent = remember { appAccentColor(context) }
    BackHandler(onBack = onCancel)
    CompositionLocalProvider(LocalAccent provides accent) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
                Text(
                    title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
                IconButton(onClick = onApply) { Icon(Icons.Rounded.Done, "Apply", tint = accent) }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (onTap == null) Modifier else Modifier.pointerInput(photoWidth, photoHeight) {
                            detectTapGestures { tap ->
                                val rect = fittedImageRect(size.width.toFloat(), size.height.toFloat(), photoWidth, photoHeight)
                                if (rect.contains(tap)) onTap(Offset((tap.x - rect.left) / rect.width, (tap.y - rect.top) / rect.height))
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (shown != null) {
                    Image(
                        bitmap = remember(shown) { shown.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (overlay != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            overlay(fittedImageRect(size.width, size.height, photoWidth, photoHeight))
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(20.dp)
                    ) { CircularProgressIndicator(color = accent) }
                }
                photoExtra()
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), content = controls)
        }
    }
}

/** A hint line under the photo. */
@Composable
internal fun StudioHint(text: String) {
    Text(text, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
}

/** Runs [body] for every row in 0 until [count], spread over the phone's cores. */
internal suspend fun forRows(count: Int, body: (Int) -> Unit) = coroutineScope {
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val chunk = max(1, (count + cores * 4 - 1) / (cores * 4))
    (0 until count step chunk).map { start ->
        async(Dispatchers.Default) {
            for (y in start until min(count, start + chunk)) {
                if (y % 8 == 0) ensureActive()
                body(y)
            }
        }
    }.awaitAll()
    Unit
}

/**
 * A soft mask (0..1 per pixel, stored as bytes) at its own size, sampled by fractions of the
 * photo so it works on the preview and the full-size photo alike.
 */
class SoftMask(val width: Int, val height: Int, val values: ByteArray = ByteArray(width * height)) {
    operator fun get(i: Int): Float = (values[i].toInt() and 255) / 255f

    fun at(fx: Float, fy: Float): Float {
        val x = (fx * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (fy * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = x - x0
        val ay = y - y0
        val top = this[y0 * width + x0] * (1 - ax) + this[y0 * width + x1] * ax
        val bottom = this[y1 * width + x0] * (1 - ax) + this[y1 * width + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    val isEmpty: Boolean get() = values.none { (it.toInt() and 255) >= 128 }

    /** [color] see-through by the mask (for showing what's selected). */
    fun tint(color: Int, opacity: Float): Bitmap {
        val px = IntArray(width * height) {
            val a = ((values[it].toInt() and 255) * opacity).toInt().coerceIn(0, 255)
            (a shl 24) or (color and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** The see-through-ness of [bitmap] as a mask (opaque = 1). */
        fun ofAlpha(bitmap: Bitmap): SoftMask {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SoftMask(bitmap.width, bitmap.height, ByteArray(px.size) { (px[it] ushr 24).toByte() })
        }
    }
}

/** Unpacked channels, for the per-pixel tools. */
internal val Int.red8: Int get() = this shr 16 and 255
internal val Int.green8: Int get() = this shr 8 and 255
internal val Int.blue8: Int get() = this and 255
internal fun rgb(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

/** The pixels of [bitmap], changed per pixel by [change] (x, y, colour → colour), as a new bitmap. */
internal suspend fun Bitmap.mapPixels(change: (Int, Int, Int) -> Int): Bitmap {
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    forRows(h) { y ->
        var i = y * w
        for (x in 0 until w) {
            px[i] = change(x, y, px[i])
            i++
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
}
