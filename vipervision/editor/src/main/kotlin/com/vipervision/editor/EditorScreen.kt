package com.vipervision.editor

import kotlinx.coroutines.launch
import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Tune
import com.t8rin.imagetoolbox.core.resources.icons.Draw
import com.t8rin.imagetoolbox.core.resources.icons.TextFields
import com.t8rin.imagetoolbox.core.resources.icons.Scissors
import com.t8rin.imagetoolbox.core.resources.icons.AutoAwesome
import com.t8rin.imagetoolbox.core.resources.icons.Flip
import com.t8rin.imagetoolbox.core.resources.icons.Eraser
import com.t8rin.imagetoolbox.core.resources.icons.LightMode
import com.t8rin.imagetoolbox.core.resources.icons.MoreVert
import com.t8rin.imagetoolbox.core.resources.icons.Redo
import com.t8rin.imagetoolbox.core.resources.icons.RotateLeft
import com.t8rin.imagetoolbox.core.resources.icons.RotateRight
import com.t8rin.imagetoolbox.core.resources.icons.Undo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val PanelGrey = Color(0xFF1C1C1E)

@Composable
internal fun EditorScreen(
    controller: EditorController,
    finisher: EditFinisher,
    aiReady: (AiChoice) -> Boolean,
    onRunAi: (AiTool, AiChoice) -> Unit,
    lamaReady: Boolean,
    onStartObjectEraser: () -> Unit,
    onStartCutOut: () -> Unit,
    samsungAvailable: Boolean,
    onSamsungTool: (SamsungTool) -> Unit,
    onApplyCutOut: () -> Unit,
    onCancelCutOut: () -> Unit,
    onNeedSamDownload: () -> Unit,
    onStartDraw: () -> Unit,
    onApplyDraw: () -> Unit,
    onCancelDraw: () -> Unit,
    onStartMarkup: () -> Unit,
    onApplyMarkup: () -> Unit,
    onCancelMarkup: () -> Unit,
    onStartCropStudio: () -> Unit,
    onApplyCropStudio: () -> Unit,
    onCancelCropStudio: () -> Unit,
    onApplyPaint: () -> Unit,
    onSave: () -> Unit,
    onSaveCopy: () -> Unit,
    onSaveAs: () -> Unit,
    canRevert: Boolean,
    onRevert: () -> Unit,
    onSaveWithOptions: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The photo the edits apply to: the original, or the result of any AI steps.
    val base = controller.workingPreview

    // In the Rotate & crop tab the whole photo is shown with the crop frame over it; elsewhere,
    // the cropped result.
    val cropMode = controller.tab == EditorTab.Transform

    // Re-render the preview whenever the edits change; a newer change cancels an older render.
    // Dragging the crop frame doesn't re-render (the frame is drawn on top instead).
    // While a dial is moving, a smaller copy is rendered so the photo keeps up with the finger;
    // the full-quality preview follows as soon as it stops.
    LaunchedEffect(base) {
        if (base == null) return@LaunchedEffect
        ViperLog.i("PREVIEW", "Photo shown: ${ViperLog.describe(base)}" + if (base.hasAlpha()) ", ${ViperLog.transparency(base)}" else "")
        val live = withContext(Dispatchers.Default) { base.scaledToFit(LIVE_PREVIEW_SIDE) }
        // Rotating and cropping only changes when the geometry does, so keep the last result.
        var turnedKey: Pair<Geometry, Boolean>? = null
        var turned: Bitmap? = null
        snapshotFlow {
            val state = controller.state
            val geometry = if (controller.tab == EditorTab.Transform) {
                state.geometry.copy(crop = CropRect())
            } else {
                state.geometry
            }
            state.copy(geometry = geometry, pixelSteps = emptyList()) to controller.isInteracting
        }.collectLatest { (state, interacting) ->
            val source = if (interacting) live else base
            val edited = withContext(Dispatchers.Default) {
                val key = state.geometry to interacting
                val input = turned.takeIf { turnedKey == key } ?: source.transformed(state.geometry).also {
                    turned = it
                    turnedKey = key
                }
                finisher.finish(context, input, state, source.width, source.height)
            }
            controller.editedPreview = edited
        }
    }

    var showOriginal by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRevertDialog by remember { mutableStateOf(false) }
    val changed = !controller.state.isUnchanged

    BackHandler(enabled = changed) { showDiscardDialog = true }

    controller.cropStudio?.let { session ->
        CropStudioScreen(session = session, onCancel = onCancelCropStudio, onApply = onApplyCropStudio)
        if (controller.isBusy) BusyOverlay(controller.busyMessage)
        return
    }

    controller.markup?.let { session ->
        MarkupScreen(session = session, onCancel = onCancelMarkup, onApply = onApplyMarkup)
        if (controller.isBusy) BusyOverlay(controller.busyMessage)
        return
    }

    controller.draw?.let { session ->
        DrawScreen(session = session, onCancel = onCancelDraw, onApply = onApplyDraw)
        if (controller.isBusy) BusyOverlay(controller.busyMessage)
        return
    }

    controller.cutOut?.let { session ->
        CutOutScreen(
            session = session,
            onCancel = onCancelCutOut,
            onApply = onApplyCutOut,
            onNeedSamDownload = onNeedSamDownload
        )
        if (controller.isBusy) BusyOverlay(controller.busyMessage)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopBar(
            controller = controller,
            changed = changed,
            onSave = onSave,
            onSaveCopy = onSaveCopy,
            onSaveAs = onSaveAs,
            canRevert = canRevert,
            onRevert = { showRevertDialog = true },
            onDiscard = { if (changed) showDiscardDialog = true else onClose() }
        )

        // The photo, as large as the space allows. Pinch to zoom (one finger moves around once
        // zoomed in); the zoom stays while editing. Press and hold still to see the original.
        // Not while cropping, where dragging moves the crop frame, or painting (PaintOverlay
        // handles the same zoom itself).
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // A zoomed-in photo stays inside its area, under the top bar and the panels. (Not
                // while cropping: no zoom then, and the corner handles may reach just past it.)
                .then(if (cropMode) Modifier else Modifier.clipToBounds())
                .pointerInput(cropMode, controller.paint) {
                    if (cropMode || controller.paint != null) return@pointerInput
                    val zoom = controller.viewZoom
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Held still for a moment: show the original until the finger lifts.
                        val gesture = withTimeoutOrNull(HOLD_FOR_ORIGINAL_MS) {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) return@withTimeoutOrNull false
                                val moved = event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                                if (event.changes.count { it.pressed } > 1 || moved) return@withTimeoutOrNull true
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        when (gesture) {
                            null -> {
                                showOriginal = true
                                do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                                showOriginal = false
                            }
                            true -> {
                                val viewSize = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                                do {
                                    val event = awaitPointerEvent()
                                    // Only while fingers are down (see PaintZoom.transform).
                                    if (event.changes.any { it.pressed }) {
                                        zoom.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom(), viewSize)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                            else -> Unit
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val shown = if (showOriginal) controller.basePreview else controller.editedPreview ?: base
            // The photo is the same size as on the other tabs (unless it's wider than the screen
            // allows, where the 8dp sides keep the handles off the very edge).
            val imageModifier = if (cropMode) Modifier.fillMaxSize().padding(horizontal = 8.dp) else Modifier.fillMaxSize()
            val paintZoom = controller.viewZoom
            if (shown != null) {
                val shownModifier = if (!cropMode) with(paintZoom) { imageModifier.zoomed() } else imageModifier
                // Transparent parts (after Remove background or Cut out) show the usual grey checks, not black.
                if (shown.hasAlpha()) Checkerboard(shownModifier, shown.width, shown.height)
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = shownModifier
                )
            }
            val uncroppedSize = base?.let { controller.state.geometry.turnedSize(it.width, it.height) }
            // Only draw the frame once the uncropped preview is showing, so the two line up.
            if (cropMode && shown != null && uncroppedSize != null &&
                abs(shown.width.toFloat() / shown.height - uncroppedSize.first.toFloat() / uncroppedSize.second) < 0.02f
            ) {
                CropOverlay(
                    imageWidth = uncroppedSize.first,
                    imageHeight = uncroppedSize.second,
                    crop = controller.state.geometry.crop,
                    aspectRatio = controller.cropAspect.ratio(uncroppedSize.first, uncroppedSize.second),
                    onCropChange = controller::setCrop,
                    onCropChangeFinished = controller::commit,
                    modifier = imageModifier
                )
            }
            // Painting over something to heal or erase.
            val paint = controller.paint
            if (paint != null && shown != null && !cropMode) {
                // Blemish remover: what's been tapped or snapped (Smart select), tinted red.
                if (paint.generative) {
                    LaunchedEffect(paint) {
                        snapshotFlow { paint.selections.toList() }.collect { list ->
                            paint.selectionTint = withContext(Dispatchers.Default) { BlemishSelect.tint(list) }
                        }
                    }
                    paint.selectionTint?.let { tinted ->
                        Image(
                            bitmap = remember(tinted) { tinted.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = with(paintZoom) { imageModifier.zoomed() }
                        )
                    }
                }
                val samReady = paint.generative && com.t8rin.neural_tools.bgremover.EdgeSAM.isDownloaded()
                PaintOverlay(
                    imageWidth = shown.width,
                    imageHeight = shown.height,
                    session = paint,
                    zoom = paintZoom,
                    modifier = imageModifier,
                    // Tap: Smart select picks the whole object (tap it again to unselect).
                    onTap = if (samReady) { point ->
                        if (paint.busyMessage == null) scope.launch {
                            paint.busyMessage = if (paint.sam == null) "Getting Smart select ready…" else "Selecting…"
                            runCatching { withContext(Dispatchers.Default) { BlemishSelect.tap(paint, shown, point) } }
                                .onFailure {
                                    ViperLog.error("PAINT", "tap select failed", it)
                                    Toast.makeText(context, "Couldn't select that: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            paint.busyMessage = null
                        }
                    } else null
                )
                paint.busyMessage?.let { message ->
                    Text(
                        message,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                var adjustingBrush by remember { mutableStateOf(false) }
                if (adjustingBrush) {
                    // How big the brush is, at the same size it paints on screen.
                    val radius = paint.brushRadiusDp.dp
                    Box(
                        Modifier
                            .size(radius * 2)
                            .border(2.dp, Color.White, CircleShape)
                            .background(Color(0x55FF3B30), CircleShape)
                    )
                }
                BrushSizeSlider(
                    session = paint,
                    onAdjusting = { adjustingBrush = it },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                )
            }
            if (showOriginal) {
                Text(
                    "Original",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (base == null || controller.isBusy) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(20.dp)
                ) {
                    CircularProgressIndicator(color = Accent)
                    controller.busyMessage?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            controller.error?.let {
                Text(it, color = Color.White, modifier = Modifier.padding(24.dp))
            }
        }

        // The four tabs share one shorter panel with their bubbles at its top: the photo is the
        // same (larger) size on each, and the bubbles sit at the same height.
        val compactPanel = controller.paint == null
        Box(
            Modifier
                .fillMaxWidth()
                // While painting the tabs are hidden: keep the panel above the phone's own
                // back / home buttons.
                .then(if (compactPanel) Modifier else Modifier.navigationBarsPadding())
                .height(if (compactPanel) COMPACT_PANEL_HEIGHT else PANEL_HEIGHT)
                .padding(top = if (compactPanel) BUBBLES_TOP else 0.dp),
            contentAlignment = if (compactPanel) Alignment.TopCenter else Alignment.Center
        ) {
            val paint = controller.paint
            if (paint != null) {
                PaintPanel(
                    session = paint,
                    onCancel = { paint.close(); controller.paint = null },
                    onApply = onApplyPaint,
                    onAutoBorders = {
                        val shownNow = controller.editedPreview ?: controller.workingPreview
                        if (shownNow != null && paint.busyMessage == null && paint.strokes.isNotEmpty()) scope.launch {
                            if (!com.t8rin.neural_tools.bgremover.EdgeSAM.isDownloaded()) {
                                Toast.makeText(context, "Auto Borders needs Smart select: download it in Settings → AI models", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            paint.busyMessage = "Snapping to the edges…"
                            runCatching { withContext(Dispatchers.Default) { BlemishSelect.autoBorders(paint, shownNow) } }
                                .onFailure {
                                    ViperLog.error("PAINT", "Auto Borders failed", it)
                                    Toast.makeText(context, "Auto Borders failed: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            paint.busyMessage = null
                        }
                    }
                )
            } else {
                when (controller.tab) {
                    EditorTab.Adjust -> AdjustPanel(controller)
                    EditorTab.Ai -> AiPanel(controller, aiReady, onRunAi, lamaReady, onStartObjectEraser, onStartCutOut, samsungAvailable, onSamsungTool)
                    EditorTab.Transform -> TransformPanel(controller, onStartCropStudio)
                    EditorTab.Decorate -> DecoratePanel(controller, onStartDraw, onStartMarkup)
                }
            }
        }

        if (controller.paint == null) BottomTabs(controller)
    }

    controller.saveAs?.let { request ->
        SaveOptionsSheet(
            options = request.options,
            source = request.source,
            photoWidth = request.width,
            photoHeight = request.height,
            onSave = onSaveWithOptions,
            onDismiss = { controller.saveAs = null }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this photo haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onClose() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            }
        )
    }

    if (showRevertDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false },
            title = { Text("Revert to original?") },
            text = { Text("The photo goes back to how it was before it was first edited or rotated. Every saved change to it is undone.") },
            confirmButton = {
                TextButton(onClick = { showRevertDialog = false; onRevert() }) { Text("Revert") }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TopBar(
    controller: EditorController,
    changed: Boolean,
    onSave: () -> Unit,
    onSaveCopy: () -> Unit,
    onSaveAs: () -> Unit,
    canRevert: Boolean,
    onRevert: () -> Unit,
    onDiscard: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(PanelGrey)
        ) {
            // While painting (Heal, Blemish remover), these step through the painting.
            val paint = controller.paint
            val canUndo = paint?.canUndo ?: controller.canUndo
            val canRedo = paint?.canRedo ?: controller.canRedo
            IconButton(onClick = { if (paint != null) paint.undo() else controller.undo() }, enabled = canUndo) {
                Icon(Icons.Rounded.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = { if (paint != null) paint.redo() else controller.redo() }, enabled = canRedo) {
                Icon(Icons.Rounded.Redo, "Redo", tint = if (canRedo) Color.White else Color.Gray)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(PanelGrey)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = controller::revert, enabled = changed) {
                Text("Revert", color = if (changed) Color.White else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onSave, enabled = changed && !controller.isBusy) {
                Text("Save", color = if (changed) Color.White else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, "More options", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save as copy") },
                        enabled = changed && !controller.isBusy,
                        onClick = { menuOpen = false; onSaveCopy() }
                    )
                    DropdownMenuItem(
                        text = { Text("Save as… (format, size, details)") },
                        onClick = { menuOpen = false; onSaveAs() }
                    )
                    if (canRevert) {
                        DropdownMenuItem(
                            text = { Text("Revert to original") },
                            enabled = !controller.isBusy,
                            onClick = { menuOpen = false; onRevert() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (changed) "Discard changes and close" else "Close") },
                        onClick = { menuOpen = false; onDiscard() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustPanel(controller: EditorController) {
    val selected = controller.selected
    val adjustments = controller.state.adjustments
    val listState = rememberLazyListState()

    LaunchedEffect(selected) {
        listState.animateScrollToItem(Adjustment.entries.indexOf(selected).coerceAtLeast(0))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Each dial's name is under its bubble (as on the AI tab).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            LazyRow(
                state = listState,
                // The bubbles are 72dp wide with their names (64dp circles), so the circles stay 10dp apart.
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                itemsIndexed(Adjustment.entries) { _, adjustment ->
                    AdjustBubble(
                        adjustment = adjustment,
                        value = adjustments[adjustment],
                        selected = adjustment == selected,
                        onClick = {
                            // Tapping the dial that's already picked sets it back to 0.
                            if (adjustment == selected && adjustments[adjustment] != 0) {
                                controller.setAdjustment(adjustment, 0)
                                controller.commit()
                            } else {
                                controller.selected = adjustment
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ValueWheel(
            value = adjustments[selected],
            onValueChange = {
                controller.isInteracting = true
                controller.setAdjustment(selected, it)
            },
            onValueChangeFinished = {
                controller.isInteracting = false
                controller.commit()
            },
            modifier = Modifier.padding(horizontal = 36.dp)
        )
    }
}

@Composable
private fun AiPanel(
    controller: EditorController,
    aiReady: (AiChoice) -> Boolean,
    onRunAi: (AiTool, AiChoice) -> Unit,
    lamaReady: Boolean,
    onStartObjectEraser: () -> Unit,
    onStartCutOut: () -> Unit,
    samsungAvailable: Boolean,
    onSamsungTool: (SamsungTool) -> Unit
) {
    var selected by remember { mutableStateOf<AiTool?>(null) }
    var choosing by remember { mutableStateOf<AiTool?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // One AI tool's bubble: runs it straight away when its only model is here, otherwise shows the choice.
        val aiToolBubble: @Composable (AiTool) -> Unit = { tool ->
            val single = tool.choices.singleOrNull()
            AiBubble(
                icon = tool.icon,
                label = tool.label,
                download = when {
                    tool.choices.any(aiReady) -> null
                    single != null -> "${single.downloadSizeMb} MB"
                    else -> "${tool.choices.size} choices"
                },
                applied = controller.state.pixelSteps.any { it is PixelStep.Ai && it.tool == tool },
                selected = selected == tool,
                enabled = !controller.isBusy,
                onClick = {
                    selected = tool
                    // One model that's already here: just run it. Otherwise show the choice
                    // (which also says how big the download is).
                    if (single != null && aiReady(single)) onRunAi(tool, single) else choosing = tool
                }
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            // The two background removers first (Auto, then Manual), then the Blemish remover (Object eraser) and the
            // other AI tools.
            item { aiToolBubble(AiTool.RemoveBackground) }
            // Manual background remover (Cut out): Image Toolbox's full background remover, with Smart select (paint roughly,
            // EdgeSAM finds the edge), all its models and erase/restore brushes.
            item {
                AiBubble(
                    icon = Icons.Rounded.Scissors,
                    label = "Manual background remover",
                    download = null,
                    applied = controller.state.pixelSteps.any { it is PixelStep.CutOut },
                    selected = false,
                    enabled = !controller.isBusy,
                    onClick = onStartCutOut
                )
            }
            // Object eraser: paint over something and the LaMa AI model fills in what was behind it.
            item {
                AiBubble(
                    icon = Icons.Rounded.Eraser,
                    label = "Blemish remover",
                    download = if (lamaReady) null else "~200 MB",
                    applied = false,
                    selected = false,
                    enabled = !controller.isBusy,
                    onClick = onStartObjectEraser
                )
            }
            // Samsung's own Galaxy AI tools, with the photo as edited so far (see SamsungHandoff).
            if (samsungAvailable) {
                items(SamsungTool.entries) { tool ->
                    AiBubble(
                        icon = when (tool) {
                            SamsungTool.Eraser -> Icons.Rounded.Eraser
                            SamsungTool.Move -> EditorIcons.Move
                            SamsungTool.Create -> Icons.Rounded.Draw
                        },
                        label = tool.label,
                        download = null,
                        applied = controller.state.pixelSteps.any { it is PixelStep.Samsung && it.tool == tool },
                        selected = false,
                        enabled = !controller.isBusy,
                        onClick = { onSamsungTool(tool) }
                    )
                }
            }
            items(AiTool.entries.filter { it != AiTool.RemoveBackground }) { tool -> aiToolBubble(tool) }
        }
    }

    choosing?.let { tool ->
        AiChoiceSheet(
            tool = tool,
            ready = aiReady,
            onPick = { choice ->
                choosing = null
                onRunAi(tool, choice)
            },
            onDismiss = { choosing = null }
        )
    }
}

/** The models offered for an AI tool, with what each is best at, its size and whether it's here. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AiChoiceSheet(
    tool: AiTool,
    ready: (AiChoice) -> Boolean,
    onPick: (AiChoice) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PanelGrey
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(tool.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(tool.description, color = Color.Gray, fontSize = 14.sp)
            tool.choices.forEach { choice ->
                val here = ready(choice)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(BubbleGrey)
                        .clickable { onPick(choice) }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            choice.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (here) "Ready ✓" else "${choice.downloadSizeMb} MB",
                            color = if (here) Accent else Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(choice.about, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (here) "Use this" else "Download and use (one time, then works offline)",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One AI tool: a round button with its full name (up to two lines, centred) and download size. */
@Composable
private fun AiBubble(
    icon: ImageVector,
    label: String,
    download: String?,
    applied: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (applied) Color.White else BubbleGrey)
                .border(2.dp, if (selected) Accent else Color.Transparent, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (applied) Color.Black else Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            // Up to three lines for the long names ("Manual background remover").
            minLines = 3,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(download ?: "", color = Color.Gray, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ShapePill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = when { selected -> Color.Black; enabled -> Color.White; else -> Color.Gray },
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else BubbleGrey)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun PaintPanel(session: PaintSession, onCancel: () -> Unit, onApply: () -> Unit, onAutoBorders: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (session.purpose) {
                PaintPurpose.Erase -> "Blemish remover — paint over it, or tap it"
                PaintPurpose.Heal -> "Heal — paint over spots and small marks (bigger things: AI Blemish remover)"
            },
            color = Color.White,
            fontSize = 14.sp
        )
        Text(
            when {
                session.shape == BrushShape.Polygon -> "Tap the corners · tap the first one to close"
                session.generative -> "Tap selects the whole object · Auto Borders snaps edges"
                else -> "Pinch to zoom · brush size on the right"
            },
            color = Color.Gray,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        // Brush shapes and the eraser (not for polygons).
        val density = androidx.compose.ui.platform.LocalDensity.current
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BrushShape.entries.forEach { shape ->
                ShapePill(shape.label, session.shape == shape) {
                    if (session.shape == BrushShape.Polygon && shape != BrushShape.Polygon) session.polygon.clear()
                    session.shape = shape
                    if (shape == BrushShape.Polygon) session.excludeMode = false
                }
            }
            if (session.shape == BrushShape.Polygon) {
                val canFinish = session.polygon.size >= 2
                ShapePill(if (canFinish) "Finish" else "Tap corners", false, enabled = canFinish) {
                    session.finishPolygon(closed = false, radiusPx = with(density) { session.brushRadiusDp.dp.toPx() })
                }
            } else {
                ShapePill("Eraser", session.excludeMode) { session.excludeMode = !session.excludeMode }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(if (session.generative) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            if (session.generative) {
                val canSnap = session.strokes.isNotEmpty() && session.busyMessage == null
                Text(
                    "Auto Borders",
                    color = if (canSnap) Color.White else Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BubbleGrey)
                        .clickable(enabled = canSnap, onClick = onAutoBorders)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            val ready = session.hasSomething && session.busyMessage == null
            Text(
                if (session.generative) "Erase" else "Heal",
                color = if (ready) Color.Black else Color.DarkGray,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (ready) Accent else BubbleGrey)
                    .clickable(enabled = ready, onClick = onApply)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun TransformPanel(controller: EditorController, onStartCropStudio: () -> Unit) {
    val base = controller.workingPreview
    val size = base?.let { controller.state.geometry.turnedSize(it.width, it.height) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // The same spots as the Adjust tab's dials: 14dp in, 72dp wide with a 2dp gap.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.width(14.dp))
            ToolBubble(Icons.Rounded.RotateLeft, "Rotate left") { controller.rotate(clockwise = false) }
            Spacer(Modifier.width(2.dp))
            ToolBubble(Icons.Rounded.RotateRight, "Rotate right") { controller.rotate(clockwise = true) }
            Spacer(Modifier.width(2.dp))
            ToolBubble(Icons.Outlined.Flip, "Flip") { controller.flip() }
            Spacer(Modifier.width(2.dp))
            ToolBubble(EditorIcons.CropRotate, "Reset crop") { controller.resetCrop() }
            Spacer(Modifier.width(2.dp))
            // Straighten by any angle, perspective crop and crop shapes (Image Toolbox's Crop).
            ToolBubble(Icons.Rounded.Tune, "Advanced") { onStartCropStudio() }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            itemsIndexed(CropAspect.entries) { _, aspect ->
                val selected = controller.cropAspect == aspect
                Text(
                    aspect.label,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Accent else BubbleGrey)
                        .clickable {
                            if (size != null) controller.selectCropAspect(aspect, size.first, size.second)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolBubble(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(BubbleGrey)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        // Same size as the Adjust dials' names.
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BottomTabs(controller: EditorController) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // AI tools, styled like the sparkle button in Samsung's editor.
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF8E7CF0), Color(0xFF5B8CFF))))
                .border(2.dp, if (controller.tab == EditorTab.Ai) Color.White else Color.Transparent, CircleShape)
                .clickable { controller.tab = EditorTab.Ai },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.AutoAwesome, "AI tools", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        TabIcon(EditorIcons.CropRotate, "Rotate and crop", controller.tab == EditorTab.Transform) {
            controller.tab = EditorTab.Transform
        }
        TabIcon(Icons.Outlined.LightMode, "Adjust", controller.tab == EditorTab.Adjust) {
            controller.tab = EditorTab.Adjust
        }
        TabIcon(Icons.Rounded.Draw, "Draw and decorate", controller.tab == EditorTab.Decorate) {
            controller.tab = EditorTab.Decorate
        }
    }
}

@Composable
private fun TabIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(icon, label, tint = if (selected) Accent else Color.White, modifier = Modifier.size(30.dp))
    }
}

/** Draw and decorate: Image Toolbox's Draw tool (and more decorating tools). */
@Composable
private fun DecoratePanel(controller: EditorController, onStartDraw: () -> Unit, onStartMarkup: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            item {
                AiBubble(
                    icon = Icons.Rounded.Draw,
                    label = "Draw",
                    download = null,
                    applied = controller.state.pixelSteps.any { it is PixelStep.Drawing },
                    selected = false,
                    enabled = !controller.isBusy,
                    onClick = onStartDraw
                )
            }
            item {
                AiBubble(
                    icon = Icons.Rounded.TextFields,
                    label = "Text & layers",
                    download = null,
                    applied = false,
                    selected = false,
                    enabled = !controller.isBusy,
                    onClick = onStartMarkup
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay(message: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(20.dp)
        ) {
            CircularProgressIndicator(color = Accent)
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** Longest side of the quick preview shown while a dial is moving. */
private const val LIVE_PREVIEW_SIDE = 900

internal fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return this
    val scale = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

/** How long a still press takes to show the original (shorter would flash it at every pinch). */
private const val HOLD_FOR_ORIGINAL_MS = 220L

/** Height of the panel under the photo while painting. */
private val PANEL_HEIGHT = 184.dp

/** The shorter panel of the four tabs, and the gap above their bubbles. */
private val COMPACT_PANEL_HEIGHT = 152.dp
private val BUBBLES_TOP = 6.dp
