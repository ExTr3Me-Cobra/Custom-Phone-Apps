package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Portrait blur: the background behind the subject blurred like a portrait-mode photo. The
 * subject comes from the background remover's cut-out; the blur only mixes background pixels
 * (weighted by how much each is background), so the subject's colours don't bleed into it.
 */
object PortraitBlur {

    /**
     * [image] with its background blurred by [strength] (0..1); [subject] is the cut-out's
     * see-through-ness (any size: opaque = subject).
     */
    fun apply(image: Bitmap, subject: Bitmap, strength: Float): Bitmap {
        val w = image.width
        val h = image.height
        val alpha = Bitmap.createScaledBitmap(subject, w, h, true)
        // Background only: the photo, see-through where the subject is (so the blur below
        // averages only background colours).
        val background = image.copy(Bitmap.Config.ARGB_8888, true)
        background.setHasAlpha(true)
        Canvas(background).drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        // About 0.5% to 4% of the photo's long side.
        val radius = max(w, h) * (0.005f + 0.035f * strength.coerceIn(0f, 1f))
        val blurred = blur(background, radius)
        background.recycle()
        // Blurred background made solid again, the sharp photo over it where the subject is.
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(image, 0f, 0f, null)
        val solid = opaque(blurred)
        blurred.recycle()
        // The blur where it isn't the subject.
        val inverse = solid
        Canvas(inverse).drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        canvas.drawBitmap(inverse, 0f, 0f, null)
        inverse.recycle()
        alpha.recycle()
        return result
    }

    /**
     * A smooth blur of about [radius] px: halved again and again (each a 2×2 average), then
     * doubled back up with filtering. See-through pixels count for less, as they should.
     */
    private fun blur(bitmap: Bitmap, radius: Float): Bitmap {
        val steps = max(1, (kotlin.math.ln(max(2f, radius)) / kotlin.math.ln(2f)).roundToInt())
        val sizes = mutableListOf(bitmap.width to bitmap.height)
        var current = bitmap
        repeat(steps) {
            val nw = max(1, current.width / 2)
            val nh = max(1, current.height / 2)
            val next = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== bitmap) current.recycle()
            current = next
            sizes += nw to nh
        }
        for (i in sizes.size - 2 downTo 0) {
            val (nw, nh) = sizes[i]
            val next = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== bitmap) current.recycle()
            current = next
        }
        return if (current === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, true) else current
    }

    /** The colours of [bitmap] at full opacity (blurred background has thin spots near the subject). */
    private fun opaque(bitmap: Bitmap): Bitmap {
        val out = if (bitmap.isMutable) bitmap.copy(Bitmap.Config.ARGB_8888, true) else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(out.width)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) row[x] = row[x] or (0xFF shl 24).toInt()
            out.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        return out
    }
}

/** One use of Portrait blur: the photo as edited so far (screen size), its subject, the strength. */
class PortraitBlurSession(val preview: Bitmap, val subject: Bitmap) {
    var strength by mutableFloatStateOf(0.5f)
    var shown by mutableStateOf<Bitmap?>(null)
}

@Composable
internal fun PortraitBlurScreen(session: PortraitBlurSession, onCancel: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    val accent = remember { appAccentColor(context) }
    BackHandler(onBack = onCancel)
    // The preview follows the slider (a smaller copy while it moves).
    LaunchedEffect(session) {
        val small = withContext(Dispatchers.Default) { session.preview.scaledToFit(1280) }
        snapshotFlow { session.strength }.collectLatest { strength ->
            delay(60)
            session.shown = withContext(Dispatchers.Default) { PortraitBlur.apply(small, session.subject, strength) }
        }
    }
    CompositionLocalProvider(LocalAccent provides accent) {
        Column(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = androidx.compose.ui.graphics.Color.White) }
                Text(
                    "Portrait blur", color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                )
                IconButton(onClick = onApply) { Icon(Icons.Rounded.Done, "Apply", tint = accent) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = session.shown
                if (shown != null) {
                    Image(
                        bitmap = remember(shown) { shown.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f)).padding(20.dp)
                    ) { CircularProgressIndicator(color = accent) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    "The subject stays sharp, the background is blurred.",
                    color = androidx.compose.ui.graphics.Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LabeledSlider("Blur ${(session.strength * 100).roundToInt()}%", session.strength, 0f..1f) { session.strength = it }
            }
        }
    }
}
