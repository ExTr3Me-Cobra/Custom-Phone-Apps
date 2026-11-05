package com.mammyframe.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full screen review: the "for review" photos one at a time, most confident first, each shown
 * the way the AI thinks is right. Spin a photo by dragging round in a circle; it snaps to the
 * nearest quarter turn when you let go. Next saves the photo as shown and moves on.
 */
@Composable
fun ReviewScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxSide = with(context.resources.displayMetrics) { maxOf(widthPixels, heightPixels) }

    // The list is fixed when review starts, so saving a photo doesn't reshuffle what's left.
    var queue by remember { mutableStateOf<List<AiRotate.Rec>?>(null) }
    LaunchedEffect(Unit) {
        val threshold = AiRotate.status.value.threshold
        queue = AiRotate.recordsIn(context, settings.folderUri)
            .filter { AiRotate.kind(it, threshold) == AiRotate.Kind.REVIEW }
            .sortedByDescending { it.certainty }
    }
    var index by remember { mutableIntStateOf(0) }
    val images = remember { mutableStateMapOf<String, Bitmap>() }

    val list = queue
    val current = list?.getOrNull(index)
    // Load this photo and the next one, and let go of the rest.
    LaunchedEffect(list, index) {
        list ?: return@LaunchedEffect
        val wanted = listOfNotNull(list.getOrNull(index), list.getOrNull(index + 1)).map { it.uri }
        images.keys.filter { it !in wanted }.forEach { images.remove(it) }
        for (u in wanted) if (u !in images) Photos.decode(context, Uri.parse(u), maxSide)?.let { images[u] = it }
    }

    // Angle the photo is shown at, in degrees clockwise from the original photo.
    var angle by remember(current?.uri) { mutableFloatStateOf((current?.fix ?: 0).toFloat()) }
    var spinning by remember { mutableStateOf(false) }

    // Finished: say so briefly, then go back.
    if (list != null && current == null) {
        LaunchedEffect(Unit) { delay(1500); onBack() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val center = Offset(boxW / 2, boxH / 2)

        if (current != null) {
            val bmp = images[current.uri]
            // The decoded picture is how the file is saved right now; turn it the rest of the way.
            val onDisk = if (current.method == AiRotate.Method.APP) 0 else current.applied
            val shownTurn = angle - onDisk
            if (bmp != null) {
                // Shrink smoothly while turning so the whole photo always fits on the screen.
                val rad = Math.toRadians(shownTurn.toDouble())
                val c = abs(cos(rad)).toFloat()
                val s = abs(sin(rad)).toFloat()
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                val scale = minOf(boxW / (w * c + h * s), boxH / (w * s + h * c))
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    image, contentDescription = current.name, contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(with(density) { (w * scale).toDp() }, with(density) { (h * scale).toDp() })
                        .graphicsLayer { rotationZ = shownTurn }
                )
            }

            // Drag round in a circle to spin; let go to snap to the nearest quarter turn.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(current.uri, center) {
                        var last = 0f
                        fun angleAt(p: Offset) = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()
                        detectDragGestures(
                            onDragStart = { p -> spinning = true; last = angleAt(p) },
                            onDrag = { change, _ ->
                                val a = angleAt(change.position)
                                var d = a - last
                                if (d > 180) d -= 360
                                if (d < -180) d += 360
                                angle += d
                                last = a
                                change.consume()
                            },
                            onDragEnd = {
                                spinning = false
                                val target = (angle / 90f).roundToInt() * 90f
                                scope.launch { animate(angle, target, animationSpec = spring(dampingRatio = 0.75f)) { v, _ -> angle = v } }
                            },
                            onDragCancel = {
                                spinning = false
                                val target = (angle / 90f).roundToInt() * 90f
                                scope.launch { animate(angle, target) { v, _ -> angle = v } }
                            },
                        )
                    }
            )

            // Which photo this is (just a quiet label, not a button).
            Text(
                "${index + 1} of ${list!!.size}",
                color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
            )

            // Translucent Next button: saves the photo as it's shown now (the AI's turn, or yours).
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .clickable(enabled = !spinning) {
                        val deg = (((angle / 90f).roundToInt() * 90) % 360 + 360) % 360
                        AiRotate.decide(context, listOf(current.uri)) { deg }
                        index++
                    }
                    .padding(horizontal = 30.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Next", color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                GlyphIcon(Glyph.FORWARD, Color.White.copy(alpha = 0.9f), Modifier.size(20.dp))
            }
        } else if (list != null) {
            Text(
                if (list.isEmpty()) "Nothing to review." else "All reviewed.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 26.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Very small "leave review" button in the top left corner.
        Box(
            Modifier
                .padding(10.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onBack)
                .padding(9.dp)
        ) {
            GlyphIcon(Glyph.CLOSE, Color.White.copy(alpha = 0.75f), Modifier.fillMaxSize())
        }
    }
}
