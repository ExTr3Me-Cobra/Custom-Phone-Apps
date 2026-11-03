package com.vipervision.editor

import kotlin.math.max
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val BrushColor = Color(0xFFFF3B30).copy(alpha = 0.55f)
private val ExcludeColor = Color(0xFF3B8BFF).copy(alpha = 0.55f)

/** Brush sizes the side slider offers, as a radius in dp. */
internal val BrushRadiusRange = 4f..60f

/** Zoom and pan of the photo while painting: [scale] around the centre, then moved by [offset]. */
class PaintZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /** Same transform for the photo and the strokes, so they stay lined up. */
    fun Modifier.zoomed(): Modifier = graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
    }

    /** A point on screen to the same point on the unzoomed layout. */
    fun toLayout(point: Offset, size: Size): Offset {
        val c = Offset(size.width / 2f, size.height / 2f)
        return (point - c - offset) / scale + c
    }

    /** Zooms by [zoom] keeping the layout point under [centroid] under the fingers, then pans. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, size: Size) {
        // When the last finger lifts there's no point between the fingers ("unspecified", not a
        // number); used, it moved the photo off the screen (black, until the editor was reopened).
        if (!centroid.isSpecified || !centroid.x.isFinite() || !centroid.y.isFinite() ||
            !pan.x.isFinite() || !pan.y.isFinite() || !zoom.isFinite() || zoom <= 0f
        ) return
        val c = Offset(size.width / 2f, size.height / 2f)
        val anchor = toLayout(centroid, size)
        val newScale = (scale * zoom).coerceIn(1f, 8f)
        val raw = centroid + pan - c - (anchor - c) * newScale
        // Keep the photo covering the view: no panning past its edges.
        val maxX = (newScale - 1f) * size.width / 2f
        val maxY = (newScale - 1f) * size.height / 2f
        scale = newScale
        offset = Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Lets the user paint over a photo shown with ContentScale.Fit in the same box. One finger paints;
 * two fingers pinch to zoom and drag to move around (and never paint). The photo must be drawn with
 * [PaintZoom.zoomed] from the same [zoom]. Finished strokes are added to [session] with points as
 * fractions of the photo and the radius in unzoomed layout pixels, so zooming in paints finer.
 */
@Composable
internal fun PaintOverlay(
    imageWidth: Int,
    imageHeight: Int,
    session: PaintSession,
    zoom: PaintZoom,
    modifier: Modifier = Modifier,
    brushColor: Color = BrushColor,
    /** If set, a tap (finger down and up without moving) goes here instead of painting a dot. */
    onTap: ((Offset) -> Unit)? = null
) {
    val tapHandler by rememberUpdatedState(onTap)
    val density = LocalDensity.current
    val radiusPx = with(density) { session.brushRadiusDp.dp.toPx() }
    val currentRadius by rememberUpdatedState(radiusPx)
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier
            .fillMaxSize()
            // Outside the zoom layer, so positions are on-screen; converted with toLayout.
            .pointerInput(imageWidth, imageHeight, session, zoom) {
                val viewSize = Size(size.width.toFloat(), size.height.toFloat())
                fun toPhoto(screen: Offset): Offset {
                    val image = fittedImageRect(viewSize.width, viewSize.height, imageWidth, imageHeight)
                    val p = zoom.toLayout(screen, viewSize)
                    return Offset(
                        ((p.x - image.left) / image.width).coerceIn(0f, 1f),
                        ((p.y - image.top) / image.height).coerceIn(0f, 1f)
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Brush size in layout pixels, so it looks the same on screen at any zoom.
                    val radius = currentRadius / zoom.scale
                    var pinching = false
                    current = listOf(toPhoto(down.position))
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // A second finger: this gesture is a pinch, drop anything painted.
                            pinching = true
                            current = emptyList()
                            zoom.transform(
                                centroid = event.calculateCentroid(useCurrent = true),
                                pan = event.calculatePan(),
                                zoom = event.calculateZoom(),
                                size = viewSize
                            )
                        } else if (!pinching) {
                            current = current + toPhoto(pressed.first().position)
                        }
                        event.changes.forEach { it.consume() }
                    }
                    val tapped = tapHandler
                    val points = current.distinct()
                    val spread = if (points.isEmpty()) 0f else max(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
                    if (!pinching && tapped != null && points.isNotEmpty() && spread * viewSize.width < viewConfiguration.touchSlop / zoom.scale * 2f) {
                        tapped(points.first())
                    } else if (!pinching && current.isNotEmpty()) {
                        session.addStroke(ScreenStroke(points, radius, session.excludeMode))
                    }
                    current = emptyList()
                }
            }
            .then(with(zoom) { Modifier.zoomed() })
    ) {
        val image = fittedImageRect(size.width, size.height, imageWidth, imageHeight)
        session.shownWidthPx = image.width
        fun toScreen(p: Offset) = Offset(image.left + p.x * image.width, image.top + p.y * image.height)

        val live = current.takeIf { it.isNotEmpty() }?.let { ScreenStroke(it.distinct(), radiusPx / zoom.scale, session.excludeMode) }
        for (stroke in session.strokes + listOfNotNull(live)) {
            val color = if (stroke.exclude) ExcludeColor else brushColor
            if (stroke.points.size == 1) {
                drawCircle(color, stroke.radiusPx, toScreen(stroke.points.first()))
            } else {
                val path = Path()
                stroke.points.forEachIndexed { i, p ->
                    val s = toScreen(p)
                    if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                }
                drawPath(
                    path,
                    color,
                    style = Stroke(width = stroke.radiusPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/**
 * Upright slider for the brush size, drawn as a wedge from thin (bottom) to thick (top). Drag or
 * tap it; while it's touched, a circle the size of the brush shows over the photo's centre.
 */
@Composable
internal fun BrushSizeSlider(
    session: PaintSession,
    onAdjusting: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) = SideSlider(
    value = session.brushRadiusDp,
    range = BrushRadiusRange,
    onValueChange = { session.brushRadiusDp = it },
    onAdjusting = onAdjusting,
    modifier = modifier
)

/** An upright wedge slider for the side of the photo: bottom is [range]'s start, top its end. */
@Composable
internal fun SideSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onAdjusting: (Boolean) -> Unit = {}
) {
    val accent = LocalAccent.current
    val onAdjustingNow by rememberUpdatedState(onAdjusting)
    val onChange by rememberUpdatedState(onValueChange)
    fun setFrom(y: Float, height: Float) {
        val f = 1f - (y / height).coerceIn(0f, 1f)
        onChange(range.start + f * (range.endInclusive - range.start))
    }
    Box(
        modifier
            .width(44.dp)
            .height(240.dp)
            .pointerInput(range) {
                detectTapGestures(onPress = {
                    onAdjustingNow(true)
                    setFrom(it.y, size.height.toFloat())
                    tryAwaitRelease()
                    onAdjustingNow(false)
                })
            }
            .pointerInput(range) {
                detectVerticalDragGestures(
                    onDragStart = { onAdjustingNow(true); setFrom(it.y, size.height.toFloat()) },
                    onDragEnd = { onAdjustingNow(false) },
                    onDragCancel = { onAdjustingNow(false) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        setFrom(change.position.y, size.height.toFloat())
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 14.dp.toPx()
            val top = pad
            val bottom = size.height - pad
            val cx = size.width / 2f
            // Wedge: wide at the top, narrow at the bottom.
            val track = Path().apply {
                moveTo(cx - 9.dp.toPx(), top)
                lineTo(cx + 9.dp.toPx(), top)
                lineTo(cx + 1.5.dp.toPx(), bottom)
                lineTo(cx - 1.5.dp.toPx(), bottom)
                close()
            }
            drawPath(track, Color.Black.copy(alpha = 0.45f))
            drawPath(track, Color.White.copy(alpha = 0.7f), style = Stroke(1.dp.toPx()))
            val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val y = bottom - f * (bottom - top)
            drawCircle(Color.Black.copy(alpha = 0.35f), 13.dp.toPx(), Offset(cx, y))
            drawCircle(accent, 11.dp.toPx(), Offset(cx, y))
            drawCircle(Color.White, 11.dp.toPx(), Offset(cx, y), style = Stroke(2.dp.toPx()))
        }
    }
}
