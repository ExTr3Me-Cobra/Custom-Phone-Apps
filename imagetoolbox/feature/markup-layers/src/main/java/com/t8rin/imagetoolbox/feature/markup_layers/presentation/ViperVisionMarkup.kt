/*
 * Viper Vision: Markup Layers' canvas, layer menu and "add layer" pickers as separate pieces, so
 * Viper Vision's photo editor can host this tool in its own layout. The code is the same as in
 * MarkupLayersContent (which still uses its own copy).
 */

package com.t8rin.imagetoolbox.feature.markup_layers.presentation

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.t8rin.imagetoolbox.core.resources.icons.Eyedropper
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import net.engawapg.lib.zoomable.toggleScale
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.ClickableTile
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Delete
import com.t8rin.imagetoolbox.core.resources.icons.ArrowDropDown
import com.t8rin.imagetoolbox.core.resources.icons.ArrowDropUp
import com.t8rin.imagetoolbox.core.resources.icons.ArrowRight
import com.t8rin.imagetoolbox.core.resources.icons.ArrowLeft
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.zIndex
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.emoji.Emoji
import com.t8rin.imagetoolbox.core.resources.icons.EmojiSticky
import com.t8rin.imagetoolbox.core.ui.theme.outlineVariant
import com.t8rin.imagetoolbox.core.ui.theme.toColor
import com.t8rin.imagetoolbox.core.ui.utils.content_pickers.rememberImagePicker
import com.t8rin.imagetoolbox.core.ui.utils.helper.toBrush
import com.t8rin.imagetoolbox.core.ui.widget.image.Picture
import com.t8rin.imagetoolbox.core.ui.widget.modifier.ShapeDefaults
import com.t8rin.imagetoolbox.core.ui.widget.modifier.transparencyChecker
import com.t8rin.imagetoolbox.core.ui.widget.sheets.EmojiSelectionSheet
import com.t8rin.imagetoolbox.feature.markup_layers.domain.LayerType
import com.t8rin.imagetoolbox.feature.markup_layers.domain.withPreferredInitialGeometryFor
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.AddShapeLayerDialog
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.AddTextLayerDialog
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.Layer
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.LayerSnapGuides
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.LayerSnapResult
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.MarkupLayersSideMenu
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.activeLayerGestures
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.model.BackgroundBehavior
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.model.UiMarkupLayer
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.screenLogic.MarkupLayersComponent
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/** What the host's buttons ask Markup Layers to show. */
@Stable
class ViperMarkupUiState {
    var snappingEnabled by mutableStateOf(true)
    var showLayersMenu by mutableStateOf(false)
    var showTextDialog by mutableStateOf(false)
    var showEmojiPicker by mutableStateOf(false)
    var showShapePicker by mutableStateOf(false)
    /** Eyedropper: the next tap on the photo picks the selected shape's colour. */
    var pickingColor by mutableStateOf(false)
    /** The small see-through bar (delete, nudge) shown after holding a layer. */
    var showQuickActions by mutableStateOf(false)
    internal var pickImageRequests by mutableIntStateOf(0)
    internal var isContextOptionsVisible by mutableStateOf(false)
    internal var shouldOpenContextOptions by mutableStateOf(false)

    fun pickImage() {
        pickImageRequests++
    }

    /** Opens the layer menu with the selected layer's options. */
    fun showLayerOptions() {
        showLayersMenu = true
        shouldOpenContextOptions = true
    }
}

/** The layer canvas over the photo: tap a layer to select it, then drag, pinch and rotate it. */
@Composable
fun ViperMarkupCanvas(
    component: MarkupLayersComponent,
    uiState: ViperMarkupUiState,
    modifier: Modifier = Modifier
) {
    val activeLayer by remember(component) {
        derivedStateOf { component.layers.firstOrNull { it.state.isActive } }
    }
    var snapResult by remember(activeLayer, uiState.snappingEnabled) {
        mutableStateOf<LayerSnapResult?>(null)
    }
    val colorBackground = component.backgroundBehavior as? BackgroundBehavior.Color
    val imageBitmap = component.bitmap
    val canvasAspectRatio = when {
        imageBitmap != null -> imageBitmap.width / imageBitmap.height.toFloat()
        colorBackground != null -> colorBackground.width / colorBackground.height.toFloat()
        else -> 1f
    }

    LaunchedEffect(uiState.showLayersMenu, activeLayer, uiState.shouldOpenContextOptions) {
        if (uiState.showLayersMenu && uiState.shouldOpenContextOptions && activeLayer != null) {
            withFrameNanos { }
            uiState.isContextOptionsVisible = true
            uiState.shouldOpenContextOptions = false
        }
    }

    // Holding a layer shows the quick bar; it goes when nothing is selected.
    LaunchedEffect(activeLayer) { if (activeLayer == null) uiState.showQuickActions = false }

    val zoomState = rememberZoomState(maxScale = 10f)
    // Eyedropper: a picture of the canvas exactly as shown (every layer over the photo), taken
    // when it's turned on, so the colour comes from whatever is on top where you touch.
    val canvasLayer = rememberGraphicsLayer()
    var composite by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pickPoint by remember { mutableStateOf<Offset?>(null) }
    var canvasPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(uiState.pickingColor) {
        composite = if (uiState.pickingColor) {
            // Hide the selection look for a couple of frames while the picture is taken.
            com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.SelectionLook.hidden = true
            try {
                withFrameNanos { }
                withFrameNanos { }
                runCatching {
                    canvasLayer.toImageBitmap().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            } finally {
                com.t8rin.imagetoolbox.feature.markup_layers.presentation.components.SelectionLook.hidden = false
            }
        } else null
        if (!uiState.pickingColor) pickPoint = null
    }
    // Dragging a layer to an edge of the zoomed-in photo scrolls the photo that way, slowly, for
    // as long as it's held there (the layer stays under the finger).
    var draggingLayer by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val maxSpeed = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(draggingLayer) {
        while (draggingLayer) {
            withFrameNanos { }
            val layer = component.layers.firstOrNull { it.state.isActive } ?: continue
            val at = finger ?: continue
            if (zoomState.scale <= 1.01f || viewport.width == 0) continue
            fun speed(position: Float, length: Int): Float = when {
                position < edge -> maxSpeed * (1f - position / edge).coerceIn(0f, 1f)
                position > length - edge -> -maxSpeed * (1f - (length - position) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            val want = Offset(speed(at.x, viewport.width), speed(at.y, viewport.height))
            if (want == Offset.Zero) continue
            val moved = zoomState.panBy(want)
            if (moved != Offset.Zero) {
                component.moveLayerBy(layer = layer, offsetChange = -moved / zoomState.scale, commitToHistory = false)
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                // Where the finger is on screen (only watched, never used up).
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        finger = event.changes.firstOrNull { it.pressed }?.position
                    }
                }
            }
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Pinching anywhere but on the selected layer zooms the whole photo (the layer's own
            // gestures take touches that start on it). Double-tap zoom only with nothing selected,
            // since double-tapping a layer edits it.
            .zoomable(
                zoomState = zoomState,
                zoomEnabled = true,
                onDoubleTap = { position ->
                    if (!component.layers.fastAny { it.state.isActive }) zoomState.toggleScale(5f, position)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                // As big as the space allows, edge to edge (no margin or frame).
                .aspectRatio(canvasAspectRatio)
                .fillMaxSize()
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .zIndex(-1f)
                    .matchParentSize()
                    .clipToBounds()
                    .transparencyChecker()
            )
            BoxWithConstraints(
                modifier = Modifier
                    .matchParentSize()
                    .activeLayerGestures(
                        component = component,
                        activeLayer = activeLayer,
                        snappingEnabled = uiState.snappingEnabled,
                        canvasScale = zoomState.scale,
                        onSnap = { snapResult = it },
                        onDragging = { draggingLayer = it },
                        onLayerTouch = { zoomState.gesturesPaused = it }
                    )
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        canvasLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(canvasLayer)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (colorBackground != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clipToBounds()
                            .drawWithCache {
                                val brush = colorBackground.gradient?.toBrush()
                                onDrawBehind {
                                    if (brush != null) drawRect(brush)
                                    else drawRect(colorBackground.color.toColor())
                                }
                            }
                    )
                }
                component.layers.forEachIndexed { index, layer ->
                    if (layer.isBackground) {
                        if (layer.state.isVisible) {
                            Picture(
                                model = imageBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clipToBounds()
                                    .graphicsLayer { alpha = layer.state.alpha },
                                showTransparencyChecker = false
                            )
                        }
                        return@forEachIndexed
                    }
                    Layer(
                        component = component,
                        layer = layer,
                        onActivate = { component.activateLayer(layer) },
                        onUpdateLayer = { updatedLayer, commitToHistory ->
                            component.updateLayerAt(index = index, layer = updatedLayer, commitToHistory = commitToHistory)
                        },
                        onShowContextOptions = { uiState.showQuickActions = true }
                    )
                }
                LayerSnapGuides(snapResult)
                if (uiState.pickingColor) {
                    // Eyedropper: touch and drag over the picture (everything as shown, top layer
                    // first); the magnifier shows the exact spot, and lifting the finger uses it.
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    pickPoint = down.position
                                    canvasPx = size
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                        if (change.pressed) pickPoint = change.position
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    val picked = pickedColor(composite, pickPoint, size)
                                    if (picked != null) setShapeColor(component, picked, commit = true)
                                    pickPoint = null
                                    uiState.pickingColor = false
                                }
                            }
                    )
                }
            }
        }
    }
    // The eyedropper's magnifier, docked in a top corner (the other one if the finger is there).
    val loupeBitmap = composite
    val loupePoint = pickPoint
    if (uiState.pickingColor && loupeBitmap != null && loupePoint != null && canvasPx.width > 0) {
        val image = remember(loupeBitmap) { loupeBitmap.asImageBitmap() }
        val picked = pickedColor(loupeBitmap, loupePoint, canvasPx)
        Canvas(Modifier.matchParentSize()) {
            val px = (loupePoint.x / canvasPx.width * loupeBitmap.width).toInt().coerceIn(0, loupeBitmap.width - 1)
            val py = (loupePoint.y / canvasPx.height * loupeBitmap.height).toInt().coerceIn(0, loupeBitmap.height - 1)
            val r = 64.dp.toPx()
            val m = 12.dp.toPx()
            val f = finger
            val leftSide = f == null || f.x > size.width / 2 || f.y > 2 * r + 2 * m
            val cx = if (leftSide) m + r else size.width - m - r
            val cy = m + r
            val zoomPixels = 15
            val half = zoomPixels / 2
            val src = androidx.compose.ui.unit.IntOffset(
                (px - half).coerceIn(0, maxOf(0, loupeBitmap.width - zoomPixels)),
                (py - half).coerceIn(0, maxOf(0, loupeBitmap.height - zoomPixels))
            )
            val srcSize = androidx.compose.ui.unit.IntSize(minOf(zoomPixels, loupeBitmap.width), minOf(zoomPixels, loupeBitmap.height))
            val circle = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
            clipPath(circle) {
                drawRect(Color.Black, Offset(cx - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                drawImage(
                    image, srcOffset = src, srcSize = srcSize,
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - r).toInt(), (cy - r).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((2 * r).toInt(), (2 * r).toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                )
                val cell = 2 * r / zoomPixels
                drawRect(
                    Color.White,
                    Offset(cx - r + (px - src.x) * cell, cy - r + (py - src.y) * cell),
                    androidx.compose.ui.geometry.Size(cell, cell),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
                )
            }
            drawCircle(picked?.let { Color(it) } ?: Color.White, r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx()))
            drawCircle(Color.White, r + 3.dp.toPx(), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }
    val quickLayer = activeLayer
    if (uiState.showQuickActions && quickLayer != null) {
        ViperQuickLayerActions(
            onDelete = {
                uiState.showQuickActions = false
                component.removeLayer(quickLayer)
            },
            onMove = { dx, dy -> component.moveLayerBy(layer = quickLayer, offsetChange = Offset(dx, dy)) },
            onClose = { uiState.showQuickActions = false },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        )
    }
    }
}

/** Holding a layer: a small see-through bar with delete and the nudge arrows (hold to repeat). */
@Composable
private fun ViperQuickLayerActions(
    onDelete: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tile = Color.Black.copy(alpha = 0.28f)
    val tileModifier = Modifier.size(38.dp)
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        ClickableTile(onClick = onDelete, icon = Icons.Rounded.Delete, text = null, containerColor = tile, modifier = Modifier.size(30.dp))
        Spacer(Modifier.size(4.dp))
        ClickableTile(onClick = { onMove(-1f, 0f) }, onHoldStep = { onMove(-1f, 0f) }, icon = Icons.Rounded.ArrowLeft, text = null, containerColor = tile, modifier = tileModifier)
        ClickableTile(onClick = { onMove(0f, -1f) }, onHoldStep = { onMove(0f, -1f) }, icon = Icons.Rounded.ArrowDropUp, text = null, containerColor = tile, modifier = tileModifier)
        ClickableTile(onClick = { onMove(0f, 1f) }, onHoldStep = { onMove(0f, 1f) }, icon = Icons.Rounded.ArrowDropDown, text = null, containerColor = tile, modifier = tileModifier)
        ClickableTile(onClick = { onMove(1f, 0f) }, onHoldStep = { onMove(1f, 0f) }, icon = Icons.Rounded.ArrowRight, text = null, containerColor = tile, modifier = tileModifier)
        Spacer(Modifier.size(4.dp))
        ClickableTile(onClick = onClose, icon = Icons.Rounded.Close, text = null, containerColor = tile, modifier = Modifier.size(30.dp))
    }
}

/** The layer menu (every layer's options) and the pickers for adding text, images, emoji and shapes. */
@Composable
fun ViperMarkupSheets(
    component: MarkupLayersComponent,
    uiState: ViperMarkupUiState
) {
    val layerImagePicker = rememberImagePicker { uri: android.net.Uri ->
        component.addLayer(UiMarkupLayer(type = LayerType.Picture.Image(uri)))
    }
    LaunchedEffect(uiState.pickImageRequests) {
        if (uiState.pickImageRequests > 0) layerImagePicker.pickImage()
    }

    MarkupLayersSideMenu(
        component = component,
        visible = uiState.showLayersMenu,
        onDismiss = {
            uiState.showLayersMenu = false
            uiState.isContextOptionsVisible = false
            uiState.shouldOpenContextOptions = false
            component.cancelGroupingSelection()
        },
        isContextOptionsVisible = uiState.isContextOptionsVisible,
        onContextOptionsVisibleChange = { uiState.isContextOptionsVisible = it }
    )

    EmojiSelectionSheet(
        selectedEmojiIndex = null,
        onEmojiPicked = {
            component.addLayer(UiMarkupLayer(type = LayerType.Picture.Sticker(Emoji.allIcons[it])))
            uiState.showEmojiPicker = false
        },
        visible = uiState.showEmojiPicker,
        onDismiss = { uiState.showEmojiPicker = false },
        icon = Icons.Outlined.EmojiSticky,
        useAnimatedEmojis = false
    )

    AddShapeLayerDialog(
        visible = uiState.showShapePicker,
        onDismiss = { uiState.showShapePicker = false },
        onShapePicked = { mode ->
            component.addLayer(UiMarkupLayer(type = LayerType.Shape.Default.withPreferredInitialGeometryFor(mode)))
            uiState.showShapePicker = false
        }
    )

    AddTextLayerDialog(
        visible = uiState.showTextDialog,
        onDismiss = { uiState.showTextDialog = false },
        onAddLayer = component::addLayer
    )
}

/** The selected layer, if it's a shape. */
private fun selectedShape(component: MarkupLayersComponent): Pair<Int, LayerType.Shape>? {
    val index = component.layers.indexOfFirst { it.state.isActive }
    val type = component.layers.getOrNull(index)?.type as? LayerType.Shape ?: return null
    return index to type
}

private fun setShapeColor(component: MarkupLayersComponent, color: Int, commit: Boolean) {
    val (index, type) = selectedShape(component) ?: return
    val layer = component.layers[index]
    component.updateLayerAt(index = index, layer = layer.copy(type = type.copy(color = color)), commitToHistory = commit)
}

/** Black, the rainbow, then white: what the shape colour slider runs through. */
private val ColorStops = intArrayOf(
    0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFF8000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
    0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFFFF.toInt()
)

private fun colorAt(t: Float): Int {
    val f = t.coerceIn(0f, 1f) * (ColorStops.size - 1)
    val i = f.toInt().coerceAtMost(ColorStops.size - 2)
    val k = f - i
    val a = ColorStops[i]
    val b = ColorStops[i + 1]
    fun ch(shift: Int) = (((a shr shift) and 255) + (((b shr shift) and 255) - ((a shr shift) and 255)) * k).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Where on the slider [color] is (closest match). */
private fun positionOf(color: Int): Float {
    var best = 0f
    var bestDistance = Int.MAX_VALUE
    for (step in 0..400) {
        val t = step / 400f
        val c = colorAt(t)
        val d = listOf(16, 8, 0).sumOf { s -> val x = ((c shr s) and 255) - ((color shr s) and 255); x * x }
        if (d < bestDistance) { bestDistance = d; best = t }
    }
    return best
}

/**
 * Viper Vision: with a shape selected, a thin colour slider (black, the rainbow, white) and an
 * eyedropper that picks a colour from the photo. Nothing shows for other layers.
 */
@Composable
fun ViperShapeColorBar(
    component: MarkupLayersComponent,
    uiState: ViperMarkupUiState,
    modifier: Modifier = Modifier
) {
    val selected = selectedShape(component) ?: run {
        if (uiState.pickingColor) uiState.pickingColor = false
        return
    }
    val layerId = component.layers[selected.first].id
    var position by remember(layerId) { mutableFloatStateOf(positionOf(selected.second.color)) }
    // Follows the eyedropper (and undo) when the colour changes from elsewhere.
    LaunchedEffect(selected.second.color) {
        if (colorAt(position) != selected.second.color) position = positionOf(selected.second.color)
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(selected.second.color))
                .border(1.dp, Color.White.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.size(10.dp))
        val brush = remember { androidx.compose.ui.graphics.Brush.horizontalGradient(ColorStops.map { Color(it) }) }
        Box(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(layerId) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    detectTapGestures { p ->
                        position = at(p.x)
                        setShapeColor(component, colorAt(position), commit = true)
                    }
                }
                .pointerInput(layerId) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    detectHorizontalDragGestures(
                        onDragStart = { p ->
                            component.beginHistoryTransaction()
                            position = at(p.x)
                            setShapeColor(component, colorAt(position), commit = false)
                        },
                        onDragEnd = { component.commitHistoryTransaction() },
                        onDragCancel = { component.commitHistoryTransaction() }
                    ) { change, _ ->
                        change.consume()
                        position = at(change.position.x)
                        setShapeColor(component, colorAt(position), commit = false)
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // A thin bar with a round thumb.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(brush)
            )
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val thumb = 20.dp
                Box(
                    Modifier
                        .offset(x = (maxWidth - thumb) * position)
                        .size(thumb)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(colorAt(position)))
                        .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (uiState.pickingColor) accent else Color.White.copy(alpha = 0.12f))
                .pointerInput(Unit) { detectTapGestures { uiState.pickingColor = !uiState.pickingColor } },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Eyedropper,
                contentDescription = "Pick a colour from the photo",
                tint = if (uiState.pickingColor) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The colour of [picture] (the canvas as shown) at [point] in a canvas of [canvas] pixels. */
private fun pickedColor(picture: android.graphics.Bitmap?, point: Offset?, canvas: androidx.compose.ui.unit.IntSize): Int? {
    if (picture == null || point == null || canvas.width <= 0 || canvas.height <= 0) return null
    val x = (point.x / canvas.width * picture.width).toInt().coerceIn(0, picture.width - 1)
    val y = (point.y / canvas.height * picture.height).toInt().coerceIn(0, picture.height - 1)
    return runCatching { picture.getPixel(x, y) or (0xFF shl 24) }.getOrNull()
}
