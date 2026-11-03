package com.vipervision.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.t8rin.imagetoolbox.core.resources.icons.Polygon
import com.t8rin.imagetoolbox.core.resources.icons.Line
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.t8rin.imagetoolbox.core.domain.model.GradientGeometry
import com.t8rin.imagetoolbox.core.domain.model.GradientPalette
import com.t8rin.imagetoolbox.core.domain.model.Pt
import com.t8rin.imagetoolbox.core.domain.model.pt
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import com.t8rin.imagetoolbox.core.resources.icons.Eraser
import com.t8rin.imagetoolbox.core.resources.icons.Highlighter
import com.t8rin.imagetoolbox.core.resources.icons.NeonBrush
import com.t8rin.imagetoolbox.core.resources.icons.Pen
import com.t8rin.imagetoolbox.core.resources.icons.Redo
import com.t8rin.imagetoolbox.core.resources.icons.Tune
import com.t8rin.imagetoolbox.core.resources.icons.Undo
import com.t8rin.imagetoolbox.core.settings.presentation.provider.LocalSettingsState
import com.t8rin.imagetoolbox.feature.draw.domain.DrawMode
import com.t8rin.imagetoolbox.feature.draw.presentation.components.BitmapDrawer
import com.t8rin.imagetoolbox.feature.draw.presentation.components.controls.DrawContentControls
import com.t8rin.imagetoolbox.feature.draw.presentation.screenLogic.DrawComponent

/**
 * One use of the Draw tool: Image Toolbox's Draw screen ([component]) in the editor's style. Its
 * canvas, all its brush modes and shapes, and its full settings sheet; the result becomes a step.
 */
class DrawSession internal constructor(
    val component: DrawComponent,
    private val lifecycle: LifecycleRegistry,
    defaultColor: Color,
    defaultWidth: Float
) {
    var strokeWidth by mutableStateOf(defaultWidth.pt)
    var drawColor by mutableStateOf(defaultColor)
    var alpha by mutableFloatStateOf(1f)
    var brushSoftness by mutableStateOf(0.pt)
    var gradientPalette by mutableStateOf<GradientPalette>(GradientPalette.SoftRainbow)
    var gradientGeometry by mutableStateOf(GradientGeometry())
    var gradientLength by mutableFloatStateOf(1f)
    var isGradientMirrored by mutableStateOf(false)
    var isGradientEnabled by mutableStateOf(false)
    var isEraserOn by mutableStateOf(false)
    var panEnabled by mutableStateOf(false)
    var showLineAngle by mutableStateOf(false)
    var showSettings by mutableStateOf(false)

    /** Polygon: tap corners to draw straight connected lines (not with the eraser). */
    var polygonOn by mutableStateOf(false)
    val corners = androidx.compose.runtime.mutableStateListOf<androidx.compose.ui.geometry.Offset>()

    /** The canvas size the drawer works in (the drawing area's size on screen). */
    var canvasSize = androidx.compose.ui.unit.IntSize.Zero

    /** The polygon so far as one drawn path ([closed]: back to the first corner). */
    fun finishPolygon(closed: Boolean) {
        if (corners.size >= 2 && canvasSize.width > 0) {
            // 3 or more corners: a filled shape (Image Toolbox's Lasso fills its path).
            val filled = closed && corners.size >= 3
            val path = androidx.compose.ui.graphics.Path().apply {
                corners.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                if (filled) close()
            }
            component.addPath(
                com.t8rin.imagetoolbox.feature.draw.presentation.components.UiPathPaint(
                    path = path,
                    strokeWidth = strokeWidth,
                    brushSoftness = brushSoftness,
                    drawColor = drawColor.copy(alpha),
                    isErasing = false,
                    drawMode = component.drawMode,
                    canvasSize = com.t8rin.imagetoolbox.core.domain.model.IntegerSize(canvasSize.width, canvasSize.height),
                    drawPathMode = if (filled) com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Lasso
                    else com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Free,
                    drawLineStyle = component.drawLineStyle
                )
            )
        }
        corners.clear()
    }

    /** The canvas as last drawn (for face detection). */
    internal var drawingBitmap: Bitmap? = null

    fun close() = lifecycle.destroy()
}

/** The brush modes, as quick bubbles (the only ones offered, also in the settings sheet). */
private val DrawModes: List<Triple<String, ImageVector, () -> DrawMode>> = listOf(
    Triple("Pen", Icons.Outlined.Pen) { DrawMode.Pen },
    Triple("Highlighter", Icons.Outlined.Highlighter) { DrawMode.Highlighter },
    Triple("Neon", Icons.Outlined.NeonBrush) { DrawMode.Neon }
)

/** Quick colours; "More" in the settings sheet has the full colour picker and gradients. */
private val QuickColors = listOf(
    Color.White, Color.Black, Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF2D55)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawScreen(
    session: DrawSession,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val component = session.component
    val settings = LocalSettingsState.current
    val drawMode = component.drawMode

    BackHandler(onBack = onCancel)

    // Image Toolbox's defaults per mode: highlighters are see-through, neon is soft.
    LaunchedEffect(drawMode) {
        session.alpha = if (drawMode is DrawMode.Highlighter) 0.4f else 1f
        session.brushSoftness = if (drawMode is DrawMode.Neon) 35.pt else 0.pt
    }
    LaunchedEffect(drawMode, session.strokeWidth) {
        session.strokeWidth = if (drawMode is DrawMode.Image) {
            session.strokeWidth.coerceIn(10.pt, 120.pt)
        } else session.strokeWidth.coerceIn(1.pt, 100.pt)
    }

    val drawLineStyle = component.drawLineStyle
    val isGradientAvailable = drawLineStyle.supportsGradient &&
            (drawMode is DrawMode.Pen || drawMode is DrawMode.Highlighter || drawMode is DrawMode.Text)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Text("Draw", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val canUndo = component.paths.isNotEmpty() || component.lastPaths.isNotEmpty()
            IconButton(onClick = component::undo, enabled = canUndo) {
                Icon(Icons.Rounded.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = component::redo, enabled = component.undonePaths.isNotEmpty()) {
                Icon(Icons.Rounded.Redo, "Redo", tint = if (component.undonePaths.isNotEmpty()) Color.White else Color.Gray)
            }
            IconButton(onClick = { session.isEraserOn = !session.isEraserOn }, enabled = !session.polygonOn) {
                Icon(
                    Icons.Rounded.Eraser, "Eraser",
                    tint = when { session.polygonOn -> Color.DarkGray; session.isEraserOn -> LocalAccent.current; else -> Color.White }
                )
            }
            IconButton(onClick = {
                if (component.pendingSpotHealPaths.isNotEmpty()) component.applySpotHeal() else onApply()
            }) { Icon(Icons.Rounded.Done, "Apply", tint = LocalAccent.current) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val imageBitmap: ImageBitmap? = component.imageBitmap
            if (imageBitmap != null) {
                BitmapDrawer(
                    imageBitmap = imageBitmap,
                    renderCache = component.renderCache,
                    sourceKey = imageBitmap.asAndroidBitmap(),
                    paths = component.appliedPaths,
                    pendingSpotHealPaths = component.pendingSpotHealPaths,
                    applySpotHealManually = settings.applySpotHealManually,
                    onDraw = { session.drawingBitmap = it },
                    strokeWidth = session.strokeWidth,
                    brushSoftness = session.brushSoftness,
                    drawColor = session.drawColor.copy(session.alpha),
                    gradientPalette = session.gradientPalette.takeIf {
                        session.isGradientEnabled && isGradientAvailable && !session.isEraserOn
                    },
                    gradientGeometry = session.gradientGeometry.takeIf {
                        component.drawPathMode.isFilled && (drawMode is DrawMode.Pen || drawMode is DrawMode.Highlighter)
                    },
                    gradientLength = session.gradientLength,
                    isGradientMirrored = session.isGradientMirrored,
                    onAddPath = component::addPath,
                    isEraserOn = session.isEraserOn,
                    drawMode = drawMode,
                    modifier = Modifier
                        .padding(16.dp)
                        .aspectRatio(imageBitmap.width.toFloat() / imageBitmap.height)
                        .fillMaxSize(),
                    panEnabled = session.panEnabled,
                    onRequestFiltering = component::filter,
                    drawPathMode = component.drawPathMode,
                    backgroundColor = component.backgroundColor,
                    backgroundGradient = component.backgroundGradient,
                    drawLineStyle = drawLineStyle,
                    helperGridParams = component.helperGridParams,
                    showLineAngle = session.showLineAngle,
                    onRemovePath = component::removePath
                )
                if (session.polygonOn) {
                    PolygonOverlay(
                        session = session,
                        modifier = Modifier
                            .padding(16.dp)
                            .aspectRatio(imageBitmap.width.toFloat() / imageBitmap.height)
                            .fillMaxSize()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    ) {
                        val canFinish = session.corners.size >= 2
                        Pill(if (session.corners.isEmpty()) "Tap the corners" else "Undo corner", false) {
                            session.corners.removeLastOrNull()
                        }
                        if (canFinish) Pill("Finish", true, filled = true) { session.finishPolygon(closed = true) }
                    }
                }
                if (component.drawPathMode.canChangeStrokeWidth) {
                    SideSlider(
                        value = session.strokeWidth.value,
                        range = if (drawMode is DrawMode.Image) 10f..120f else 1f..100f,
                        onValueChange = { session.strokeWidth = it.pt },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                    )
                }
            }
        }

        // Quick colours, then the brush modes.
        if (drawMode !is DrawMode.PathEffect && drawMode !is DrawMode.SpotHeal && drawMode !is DrawMode.Warp && drawMode !is DrawMode.Image) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(QuickColors) { color ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (session.drawColor == color) 3.dp else 1.dp,
                                color = if (session.drawColor == color) LocalAccent.current else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable { session.drawColor = color }
                    )
                }
                item {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(session.drawColor)
                            .border(2.dp, Color.White, CircleShape)
                            .clickable { session.showSettings = true },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = if (session.drawColor.luminance() > 0.5f) Color.Black else Color.White, fontSize = 16.sp) }
                }
            }
        } else Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            items(DrawModes) { (label, icon, make) ->
                val selected = drawMode::class == make()::class
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (selected) LocalAccent.current else BubbleGrey)
                            .clickable { if (!selected) component.updateDrawMode(make()) else session.showSettings = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, label, tint = if (selected) LocalAccent.current.contentOn() else Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
            // Straight lines (also for the eraser), and polygons of tapped corners.
            item {
                val on = component.drawPathMode is com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Line && !session.polygonOn
                ShapeBubble("Line", Icons.Rounded.Line, on) {
                    session.polygonOn = false
                    session.corners.clear()
                    component.updateDrawPathMode(
                        if (on) com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Free
                        else com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Line
                    )
                }
            }
            item {
                ShapeBubble("Polygon", Icons.Rounded.Polygon, session.polygonOn) {
                    if (session.polygonOn) {
                        session.finishPolygon(closed = true)
                        session.polygonOn = false
                    } else {
                        session.polygonOn = true
                        session.isEraserOn = false
                        component.updateDrawPathMode(com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode.Free)
                    }
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(BubbleGrey)
                            .clickable { session.showSettings = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Tune, "All settings", tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(3.dp))
                    Text("Settings", color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(6.dp))
    }

    if (session.showSettings) {
        ModalBottomSheet(
            onDismissRequest = { session.showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Image Toolbox's Draw settings: colour picker and gradients, size, softness,
                // opacity, the brush modes above, every shape, line styles,
                // line angle, helper grid and magnifier.
                DrawContentControls(
                    component = component,
                    onDetectFaces = { useYuNet ->
                        session.drawingBitmap?.let { component.detectFaces(it, useYuNet) {} }
                        session.showSettings = false
                    },
                    isFaceDetectionEnabled = component.pendingSpotHealPaths.isEmpty(),
                    secondaryControls = {},
                    drawColor = session.drawColor,
                    onDrawColorChange = { session.drawColor = it },
                    gradientPalette = session.gradientPalette,
                    onGradientPaletteChange = { session.gradientPalette = it },
                    gradientGeometry = session.gradientGeometry,
                    onGradientGeometryChange = { session.gradientGeometry = it },
                    gradientLength = session.gradientLength,
                    onGradientLengthChange = { session.gradientLength = it },
                    isGradientMirrored = session.isGradientMirrored,
                    onGradientMirroredChange = { session.isGradientMirrored = it },
                    isGradientAvailable = isGradientAvailable,
                    isGradientEnabled = session.isGradientEnabled && isGradientAvailable,
                    onGradientEnabledChange = { session.isGradientEnabled = it },
                    strokeWidth = session.strokeWidth,
                    onStrokeWidthChange = { session.strokeWidth = it },
                    brushSoftness = session.brushSoftness,
                    onBrushSoftnessChange = { session.brushSoftness = it },
                    alpha = session.alpha,
                    onAlphaChange = { session.alpha = it },
                    showLineAngle = session.showLineAngle,
                    onShowLineAngleChange = { session.showLineAngle = it },
                    showSaveOptions = false,
                    drawModes = remember { DrawModes.map { it.third() } }
                )
                Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
        }
    }
}

@Composable
private fun ShapeBubble(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) LocalAccent.current else BubbleGrey)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (selected) LocalAccent.current.contentOn() else Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * Polygon: each tap is a corner joined to the last by a straight line; tapping the first corner
 * again closes the shape. Drawn in the drawer's own canvas pixels (same box as the drawer).
 */
@Composable
private fun PolygonOverlay(session: DrawSession, modifier: Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.Canvas(
        modifier
            .then(Modifier.onSizeChanged { session.canvasSize = it })
            .pointerInput(session) {
                detectTapGestures { p ->
                    val first = session.corners.firstOrNull()
                    if (first != null && session.corners.size >= 3 && (p - first).getDistance() < with(density) { 28.dp.toPx() }) {
                        session.finishPolygon(closed = true)
                    } else {
                        session.corners.add(p)
                    }
                }
            }
    ) {
        val pts = session.corners.toList()
        if (pts.isEmpty()) return@Canvas
        val path = androidx.compose.ui.graphics.Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        if (pts.size >= 3) {
            path.close()
            drawPath(path, session.drawColor.copy(session.alpha * 0.6f))
        }
        drawPath(path, session.drawColor.copy(session.alpha), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        pts.forEachIndexed { i, p -> drawCircle(Color.White, if (i == 0) 7.dp.toPx() else 5.dp.toPx(), p) }
    }
}

private fun Pt.coerceIn(min: Pt, max: Pt): Pt = Pt(value.coerceIn(min.value, max.value))

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
