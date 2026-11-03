package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import com.t8rin.imagetoolbox.core.resources.icons.Eraser
import com.t8rin.imagetoolbox.core.resources.icons.Fullscreen
import com.t8rin.neural_tools.bgremover.EdgeSAM
import com.t8rin.neural_tools.bgremover.MobileSAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** AI inpaint's modes (its bottom bubbles). */
enum class InpaintMode(val label: String, val title: String, val hint: String) {
    Erase("Erase", "Object Erase", "Tap what to remove. Tap it again to unselect. Brush adds what the tap missed, like a shadow."),
    Move("Move", "Object Move", "Tap what to move, then drag it to its new spot."),
    Expand("Expand", "Expand Image", "Pick the sides to grow the photo on. Works best with sky, water, grass, sand or plain walls.")
}

/** What a tap does to the selection. */
enum class TapMode(val label: String) { New("New object"), Add("Add to it"), Remove("Take away") }

/** One thing selected with Smart select: its taps, and its mask (white on black, preview size). */
class SelectedObject(val points: List<MobileSAM.Point>, val mask: Bitmap)

/**
 * One use of AI inpaint, on the upright photo as edited so far ([preview], screen size). Smart
 * select (EdgeSAM) turns taps into exact outlines; LaMa fills (see [InpaintEngine]).
 */
class InpaintSession(val preview: Bitmap, initialMode: InpaintMode) {
    /** Each tool (Object Erase, Object Move, Expand Image) is its own bubble; the mode stays put. */
    var mode by mutableStateOf(initialMode)
    var tapMode by mutableStateOf(TapMode.New)

    /** Erase: painting extra area instead of tapping. */
    var brushing by mutableStateOf(false)
    val brush = PaintSession(PaintPurpose.Erase).apply { brushRadiusDp = 16f }

    val objects = mutableStateListOf<SelectedObject>()

    /** Red see-through tint of everything selected, for showing over the photo. */
    var overlay by mutableStateOf<Bitmap?>(null)
        internal set

    // Move: where the object goes (fractions of the photo) and its size.
    var moveOffset by mutableStateOf(Offset.Zero)
    var moveScale by mutableFloatStateOf(1f)
    var piece by mutableStateOf<Bitmap?>(null)
        internal set
    var pieceBounds by mutableStateOf<Rect?>(null)
        internal set

    // Expand.
    var sides by mutableStateOf(setOf(Side.Left, Side.Right))
    var amount by mutableFloatStateOf(0.2f)

    var busyMessage by mutableStateOf<String?>(null)

    internal var sam: EdgeSAM.Session? = null
    internal val samLock = Mutex()

    /** The brush strokes as a mask at preview size, or null. */
    fun brushMask(): Bitmap? {
        val strokes = brush.strokes.toList()
        if (strokes.isEmpty() || brush.shownWidthPx <= 0f) return null
        val long = max(preview.width, preview.height).toFloat()
        val perScreenPx = preview.width / brush.shownWidthPx
        return renderMask(strokes.map { BrushStroke(it.points, it.radiusPx * perScreenPx / long) }, preview.width, preview.height)
    }

    /** What Apply works on: one mask per object (plus the brush), white on black. */
    fun selections(): List<Bitmap> = objects.map { it.mask } + listOfNotNull(brushMask())

    /** Everything selected in one mask, or null if nothing is. */
    fun combined(): Bitmap? {
        val all = selections()
        if (all.isEmpty()) return null
        val out = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val lighten = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.LIGHTEN) }
        all.forEach { canvas.drawBitmap(it, 0f, 0f, lighten) }
        return out
    }

    val hasSelection: Boolean get() = objects.isNotEmpty() || brush.strokes.isNotEmpty()

    fun clear() {
        objects.clear()
        brush.strokes.clear()
        moveOffset = Offset.Zero
        moveScale = 1f
    }

    fun close() {
        sam?.close()
        sam = null
    }
}

/** The AI inpaint screen, in the app's own colour. */
@Composable
internal fun InpaintScreen(
    session: InpaintSession,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val context = LocalContext.current
    val accent = remember { appAccentColor(context) }
    CompositionLocalProvider(LocalAccent provides accent) {
        InpaintContent(session, onCancel, onApply)
    }
}

@Composable
private fun InpaintContent(session: InpaintSession, onCancel: () -> Unit, onApply: () -> Unit) {
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preview = session.preview
    BackHandler(onBack = onCancel)

    // Smart select's model looks at the photo once (a second or two), then every tap is quick.
    LaunchedEffect(session.mode) {
        if (session.mode == InpaintMode.Expand || session.sam != null) return@LaunchedEffect
        session.busyMessage = "Getting Smart select ready…"
        runCatching { withContext(Dispatchers.Default) { session.samLock.withLock { session.sam ?: EdgeSAM.openSession(preview).also { session.sam = it } } } }
            .onFailure {
                ViperLog.error("INPAINT", "Smart select failed to start", it)
                Toast.makeText(context, "Smart select couldn't start: ${it.message}", Toast.LENGTH_LONG).show()
            }
        session.busyMessage = null
    }

    // The red tint and Move's piece follow the selection.
    LaunchedEffect(session) {
        snapshotFlow { session.objects.toList() to session.brush.strokes.toList() }.collect {
            val combined = withContext(Dispatchers.Default) { session.combined() }
            session.overlay = combined?.let { mask ->
                withContext(Dispatchers.Default) { tint(mask).also { mask.recycle() } }
            }
            val single = session.objects.lastOrNull()
            if (single != null) {
                val (piece, bounds) = withContext(Dispatchers.Default) { cutPiece(preview, single.mask) } ?: (null to null)
                session.piece = piece
                session.pieceBounds = bounds
            } else {
                session.piece = null
                session.pieceBounds = null
            }
        }
    }

    /** A tap on the photo at [p] (fractions): Smart select's prompt. */
    fun onTap(p: Offset) {
        val sam = session.sam ?: return
        if (session.busyMessage != null) return
        val objects = session.objects
        val hit = objects.indexOfLast { obj ->
            val x = (p.x * obj.mask.width).toInt().coerceIn(0, obj.mask.width - 1)
            val y = (p.y * obj.mask.height).toInt().coerceIn(0, obj.mask.height - 1)
            android.graphics.Color.red(obj.mask.getPixel(x, y)) > 128
        }
        val mode = if (objects.isEmpty()) TapMode.New else session.tapMode
        if (mode == TapMode.New && hit >= 0 && session.mode == InpaintMode.Erase) {
            objects.removeAt(hit)
            return
        }
        val points = when (mode) {
            TapMode.New -> listOf(MobileSAM.Point(p.x, p.y, positive = true))
            TapMode.Add -> objects.last().points + MobileSAM.Point(p.x, p.y, positive = true)
            TapMode.Remove -> objects.last().points + MobileSAM.Point(p.x, p.y, positive = false)
        }
        scope.launch {
            session.busyMessage = "Selecting…"
            runCatching {
                withContext(Dispatchers.Default) {
                    val white = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                    val seg = session.samLock.withLock { sam.segment(points = points, outputImage = white) }
                    white.recycle()
                    toMask(seg).also { seg.recycle() }
                }
            }.onSuccess { mask ->
                val obj = SelectedObject(points, mask)
                when {
                    mode == TapMode.New && session.mode == InpaintMode.Move -> { objects.clear(); objects.add(obj); session.moveOffset = Offset.Zero; session.moveScale = 1f }
                    mode == TapMode.New -> objects.add(obj)
                    else -> objects[objects.lastIndex] = obj
                }
                // After picking something, the next tap usually fixes it.
                if (mode == TapMode.New && session.mode == InpaintMode.Move) session.tapMode = TapMode.New
            }.onFailure {
                ViperLog.error("INPAINT", "Smart select failed", it)
                Toast.makeText(context, "Couldn't select that: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            session.busyMessage = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Text(session.mode.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            val ready = when (session.mode) {
                InpaintMode.Erase -> session.hasSelection
                InpaintMode.Move -> session.objects.isNotEmpty() && (session.moveOffset != Offset.Zero || session.moveScale != 1f)
                InpaintMode.Expand -> session.sides.isNotEmpty()
            }
            IconButton(onClick = { if (ready && session.busyMessage == null) onApply() }) {
                Icon(Icons.Rounded.Done, "Apply", tint = if (ready) accent else Color.Gray)
            }
        }

        val zoom = remember { PaintZoom() }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            when (session.mode) {
                InpaintMode.Expand -> ExpandPreview(session)
                else -> {
                    val moving = session.mode == InpaintMode.Move
                    Image(
                        bitmap = remember(preview) { preview.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = with(zoom) { Modifier.fillMaxSize().zoomed() }
                    )
                    // Move: the object at its new spot, and a dark hole where it was.
                    val piece = session.piece
                    val bounds = session.pieceBounds
                    if (moving && piece != null && bounds != null) {
                        MovedPiece(preview, piece, bounds, session.moveOffset, session.moveScale, session.overlay, zoom)
                    } else {
                        session.overlay?.let { tinted ->
                            Image(
                                bitmap = remember(tinted) { tinted.asImageBitmap() },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = with(zoom) { Modifier.fillMaxSize().zoomed() }
                            )
                        }
                    }
                    if (session.mode == InpaintMode.Erase && session.brushing) {
                        PaintOverlay(
                            imageWidth = preview.width,
                            imageHeight = preview.height,
                            session = session.brush,
                            zoom = zoom
                        )
                        BrushSizeSlider(
                            session = session.brush,
                            onAdjusting = {},
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                        )
                    } else {
                        TapOverlay(
                            imageWidth = preview.width,
                            imageHeight = preview.height,
                            zoom = zoom,
                            onTap = ::onTap,
                            onDrag = if (moving && session.objects.isNotEmpty()) { delta ->
                                session.moveOffset += delta
                            } else null
                        )
                    }
                }
            }
            session.busyMessage?.let { message ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(20.dp)
                ) {
                    CircularProgressIndicator(color = accent)
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // The current mode's settings.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(session.mode.hint, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            when (session.mode) {
                InpaintMode.Erase -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Tap", !session.brushing) { session.brushing = false }
                        Pill("Brush", session.brushing) { session.brushing = true }
                        Pill("Clear", false) { session.clear() }
                    }
                    if (!session.brushing && session.objects.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        TapModes(session)
                    }
                }
                InpaintMode.Move -> {
                    if (session.objects.isNotEmpty()) {
                        TapModes(session)
                        Spacer(Modifier.height(4.dp))
                        LabeledSlider("Size ${(session.moveScale * 100).roundToInt()}%", session.moveScale, 0.5f..2f) { session.moveScale = it }
                    }
                }
                InpaintMode.Expand -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Side.entries.forEach { side ->
                            Pill(side.label, side in session.sides) {
                                session.sides = if (side in session.sides) session.sides - side else session.sides + side
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LabeledSlider("Each side ${(session.amount * 100).roundToInt()}%", session.amount, 0.05f..0.5f) { session.amount = it }
                }
            }
        }

    }
}

@Composable
private fun TapModes(session: InpaintSession) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TapMode.entries.forEach { mode -> Pill(mode.label, session.tapMode == mode) { session.tapMode = mode } }
    }
}

internal val InpaintMode.icon: ImageVector
    get() = when (this) {
        InpaintMode.Erase -> Icons.Rounded.Eraser
        InpaintMode.Move -> EditorIcons.Move
        InpaintMode.Expand -> Icons.Rounded.Fullscreen
    }

/**
 * Taps (a finger down and up without moving) go to [onTap] as fractions of the photo; with
 * [onDrag], one finger dragging moves by fractions of the photo; two fingers zoom and pan.
 */
@Composable
private fun TapOverlay(
    imageWidth: Int,
    imageHeight: Int,
    zoom: PaintZoom,
    onTap: (Offset) -> Unit,
    onDrag: ((Offset) -> Unit)?
) {
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight, zoom) {
                val viewSize = Size(size.width.toFloat(), size.height.toFloat())
                val image = fittedImageRect(viewSize.width, viewSize.height, imageWidth, imageHeight)
                fun toPhoto(screen: Offset): Offset {
                    val p = zoom.toLayout(screen, viewSize)
                    return Offset((p.x - image.left) / image.width, (p.y - image.top) / image.height)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var pinching = false
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinching = true
                            zoom.transform(event.calculateCentroid(useCurrent = true), event.calculatePan(), event.calculateZoom(), viewSize)
                        } else if (!pinching) {
                            val now = pressed.first().position
                            if ((now - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) drag?.let { d ->
                                val delta = (now - last) / zoom.scale
                                d(Offset(delta.x / image.width, delta.y / image.height))
                            }
                            last = now
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (!pinching && !moved) {
                        val p = toPhoto(down.position)
                        if (p.x in 0f..1f && p.y in 0f..1f) tap(p)
                    }
                }
            }
    )
}

/** Move's preview: the hole darkened where the object was, and the object at its new spot and size. */
@Composable
private fun MovedPiece(
    preview: Bitmap,
    piece: Bitmap,
    bounds: Rect,
    offset: Offset,
    scale: Float,
    overlay: Bitmap?,
    zoom: PaintZoom
) {
    val pieceImage = remember(piece) { piece.asImageBitmap() }
    val holeImage = remember(overlay) { overlay?.asImageBitmap() }
    Canvas(with(zoom) { Modifier.fillMaxSize().zoomed() }) {
        val image = fittedImageRect(size.width, size.height, preview.width, preview.height)
        val sx = image.width / preview.width
        val sy = image.height / preview.height
        holeImage?.let {
            drawImage(
                it,
                dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()),
                dstSize = IntSize(image.width.roundToInt(), image.height.roundToInt()),
                alpha = 0.8f
            )
        }
        val cx = image.left + (bounds.exactCenterX() + offset.x * preview.width) * sx
        val cy = image.top + (bounds.exactCenterY() + offset.y * preview.height) * sy
        val dw = bounds.width() * sx * scale
        val dh = bounds.height() * sy * scale
        drawImage(
            pieceImage,
            dstOffset = IntOffset((cx - dw / 2f).roundToInt(), (cy - dh / 2f).roundToInt()),
            dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1))
        )
    }
}

/** Expand's preview: the photo inside the bigger frame, the new area in grey. */
@Composable
private fun ExpandPreview(session: InpaintSession) {
    val preview = session.preview
    val image = remember(preview) { preview.asImageBitmap() }
    val a = session.amount
    val l = if (Side.Left in session.sides) a else 0f
    val r = if (Side.Right in session.sides) a else 0f
    val t = if (Side.Top in session.sides) a else 0f
    val b = if (Side.Bottom in session.sides) a else 0f
    Canvas(Modifier.fillMaxSize().padding(16.dp)) {
        val totalW = preview.width * (1f + l + r)
        val totalH = preview.height * (1f + t + b)
        val frame = fittedImageRect(size.width, size.height, totalW.roundToInt().coerceAtLeast(1), totalH.roundToInt().coerceAtLeast(1))
        val k = frame.width / totalW
        drawRect(Color(0xFF3A3A3C), topLeft = Offset(frame.left, frame.top), size = Size(frame.width, frame.height))
        drawImage(
            image,
            dstOffset = IntOffset((frame.left + l * preview.width * k).roundToInt(), (frame.top + t * preview.height * k).roundToInt()),
            dstSize = IntSize((preview.width * k).roundToInt(), (preview.height * k).roundToInt())
        )
    }
}

/** Smart select's result (white, see-through outside) as white on black. */
private fun toMask(seg: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(seg.width, seg.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).apply {
        drawColor(android.graphics.Color.BLACK)
        drawBitmap(seg, 0f, 0f, null)
    }
    return out
}

/** The mask as a red, half see-through tint. */
private fun tint(mask: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(mask, 0f, 0f, Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 0f, 59f,
                    0f, 0f, 0f, 0f, 48f,
                    0.5f, 0f, 0f, 0f, 0f
                )
            )
        )
    })
    return out
}

/** The object [mask] covers, cut out of [preview] (see-through around it), and where it was. */
private fun cutPiece(preview: Bitmap, mask: Bitmap): Pair<Bitmap, Rect>? {
    val bounds = InpaintEngine.maskBounds(mask, 16) ?: return null
    val piece = Bitmap.createBitmap(preview, bounds.left, bounds.top, bounds.width(), bounds.height()).copy(Bitmap.Config.ARGB_8888, true)
    piece.setHasAlpha(true)
    val part = Bitmap.createBitmap(mask, bounds.left, bounds.top, bounds.width(), bounds.height())
    android.graphics.Canvas(piece).drawBitmap(part, 0f, 0f, Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f
                )
            )
        )
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    })
    if (part !== mask) part.recycle()
    return piece to bounds
}

/** The move's offset as Android's point type, for [InpaintEngine.move]. */
internal fun Offset.toPointF() = PointF(x, y)
