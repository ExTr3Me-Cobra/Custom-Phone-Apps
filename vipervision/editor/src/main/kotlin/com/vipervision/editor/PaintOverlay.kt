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
    // Polygon lines: their own thickness, part of the photo (they zoom with it).
    val polygonRadiusPx = with(density) { session.polygonRadiusDp.dp.toPx() }
    val currentPolygonRadius by rememberUpdatedState(polygonRadiusPx)
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
                    // Polygon: touching a corner drags it to a better spot.
                    if (session.shape == BrushShape.Polygon) {
                        val image = fittedImageRect(viewSize.width, viewSize.height, imageWidth, imageHeight)
                        val at = toPhoto(down.position)
                        val grab = session.polygon.indices.minByOrNull { i ->
                            val c = session.polygon[i]
                            Offset((c.x - at.x) * image.width, (c.y - at.y) * image.height).getDistance()
                        }?.takeIf { i ->
                            val c = session.polygon[i]
                            Offset((c.x - at.x) * image.width, (c.y - at.y) * image.height).getDistance() * zoom.scale < 12.dp.toPx()
                        }
                        if (grab != null) {
                            var moved = false
                            var pinch = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) pinch = true
                                if (!pinch) {
                                    val pos = pressed.first().position
                                    if ((pos - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                    if (moved) session.polygon[grab] = toPhoto(pos)
                                }
                                event.changes.forEach { it.consume() }
                            }
                            // A tap on the first corner (not a drag) still closes the shape.
                            if (!moved && !pinch && grab == 0 && session.polygon.size >= 3) {
                                session.finishPolygon(closed = true, radiusPx = currentPolygonRadius)
                            }
                            return@awaitEachGesture
                        }
                    }
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
                            val p = toPhoto(pressed.first().position)
                            current = when (session.shape) {
                                // A straight line from where the finger went down to where it is.
                                BrushShape.Line -> listOf(current.first(), p)
                                else -> current + p
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    val tapped = tapHandler
                    val points = current.distinct()
                    val spread = if (points.isEmpty()) 0f else max(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
                    val isTap = points.isNotEmpty() && spread * viewSize.width < viewConfiguration.touchSlop / zoom.scale * 2f
                    when {
                        pinching -> Unit
                        // Polygon: each tap is a corner; tapping the first corner again closes it.
                        session.shape == BrushShape.Polygon -> if (isTap) {
                            val p = points.first()
                            val first = session.polygon.firstOrNull()
                            val image = fittedImageRect(viewSize.width, viewSize.height, imageWidth, imageHeight)
                            val nearFirst = first != null && session.polygon.size >= 3 &&
                                    Offset((p.x - first.x) * image.width, (p.y - first.y) * image.height).getDistance() * zoom.scale < 14.dp.toPx()
                            if (nearFirst) session.finishPolygon(closed = true, radiusPx = currentPolygonRadius)
                            else insertCorner(session.polygon, p, image.width, image.height, session.polygonOrder)
                        }
                        session.shape == BrushShape.Free && tapped != null && isTap -> tapped(points.first())
                        current.isNotEmpty() -> session.addStroke(ScreenStroke(points, radius, session.excludeMode))
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
        // The polygon being made: its corners and the lines between them.
        val corners = session.polygon.toList()
        if (corners.isNotEmpty()) {
            val path = Path()
            corners.forEachIndexed { i, p -> val s = toScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
            // 3 or more corners: a shape, filled inside. Fill and outline as one even layer (drawn
            // solid, then made see-through together), so the edge isn't darker where they overlap.
            if (corners.size >= 3) path.close()
            evenly(brushColor) { solid ->
                if (corners.size >= 3) drawPath(path, solid)
                drawPath(path, solid, style = Stroke(width = polygonRadiusPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            corners.forEachIndexed { i, p ->
                drawCircle(Color.White, (if (i == 0) 7.dp.toPx() else 5.dp.toPx()) / zoom.scale, toScreen(p))
            }
        }
        for (stroke in session.strokes + listOfNotNull(live)) {
            val color = if (stroke.exclude) ExcludeColor else brushColor
            if (stroke.closed) {
                val fill = Path()
                stroke.points.forEachIndexed { i, p -> val s = toScreen(p); if (i == 0) fill.moveTo(s.x, s.y) else fill.lineTo(s.x, s.y) }
                fill.close()
                evenly(color) { solid ->
                    drawPath(fill, solid)
                    drawPath(fill, solid, style = Stroke(width = stroke.radiusPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                continue
            }
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

/** Draws [block] with [color] made solid, then the whole lot at [color]'s see-through-ness. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.evenly(
    color: Color,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit
) {
    val layer = androidx.compose.ui.graphics.Paint().apply { alpha = color.alpha }
    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), layer)
    block(color.copy(alpha = 1f))
    drawContext.canvas.restore()
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

/**
 * An upright wedge slider for the side of the photo: bottom is [range]'s start, top its end.
 * Out of the way while it isn't used: a thin, faint strip at the edge. Touching it brings back the
 * full slider until a moment after letting go.
 */
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
    var touching by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(touching) {
        if (touching) expanded = true else {
            kotlinx.coroutines.delay(1200)
            expanded = false
        }
    }
    fun adjusting(on: Boolean) {
        touching = on
        onAdjustingNow(on)
    }
    val width by androidx.compose.animation.core.animateDpAsState(if (expanded) 44.dp else 20.dp, label = "slider width")
    val fade by androidx.compose.animation.core.animateFloatAsState(if (expanded) 1f else 0.45f, label = "slider fade")
    fun setFrom(y: Float, height: Float) {
        val f = 1f - (y / height).coerceIn(0f, 1f)
        onChange(range.start + f * (range.endInclusive - range.start))
    }
    Box(
        modifier
            .width(width)
            .height(240.dp)
            .pointerInput(range) {
                detectTapGestures(onPress = {
                    adjusting(true)
                    setFrom(it.y, size.height.toFloat())
                    tryAwaitRelease()
                    adjusting(false)
                })
            }
            .pointerInput(range) {
                detectVerticalDragGestures(
                    onDragStart = { adjusting(true); setFrom(it.y, size.height.toFloat()) },
                    onDragEnd = { adjusting(false) },
                    onDragCancel = { adjusting(false) },
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
            val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val y = bottom - f * (bottom - top)
            // Grows from a thin strip into the full wedge.
            val grow = ((size.width - 20.dp.toPx()) / 24.dp.toPx()).coerceIn(0f, 1f)
            val wide = (2.5f + 6.5f * grow).dp.toPx()
            val narrow = (1f + 0.5f * grow).dp.toPx()
            val track = Path().apply {
                moveTo(cx - wide, top)
                lineTo(cx + wide, top)
                lineTo(cx + narrow, bottom)
                lineTo(cx - narrow, bottom)
                close()
            }
            drawPath(track, Color.Black.copy(alpha = 0.45f * fade))
            drawPath(track, Color.White.copy(alpha = 0.7f * fade), style = Stroke(1.dp.toPx()))
            val knob = (5f + 6f * grow).dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f * fade), knob + 2.dp.toPx(), Offset(cx, y))
            drawCircle(accent.copy(alpha = fade), knob, Offset(cx, y))
            drawCircle(Color.White.copy(alpha = fade), knob, Offset(cx, y), style = Stroke((1f + grow).dp.toPx()))
        }
    }
}
