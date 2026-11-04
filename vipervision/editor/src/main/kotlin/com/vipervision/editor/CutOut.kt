package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.t8rin.imagetoolbox.core.domain.model.pt
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.AutoAwesome
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Done
import com.t8rin.imagetoolbox.core.resources.icons.Eraser
import com.t8rin.imagetoolbox.core.resources.icons.MoreVert
import com.t8rin.imagetoolbox.core.resources.icons.Redo
import com.t8rin.imagetoolbox.core.resources.icons.SettingsBackupRestore
import com.t8rin.imagetoolbox.core.resources.icons.TouchApp
import com.t8rin.imagetoolbox.core.resources.icons.Undo
import com.t8rin.imagetoolbox.core.settings.presentation.provider.LocalSettingsState
import com.t8rin.imagetoolbox.core.settings.presentation.provider.LocalSimpleSettingsInteractor
import com.t8rin.imagetoolbox.core.ui.widget.modifier.HelperGridParams
import com.t8rin.imagetoolbox.feature.draw.domain.DrawPathMode
import com.t8rin.imagetoolbox.feature.erase_background.domain.model.BgModelType
import com.t8rin.imagetoolbox.feature.erase_background.presentation.components.BitmapEraser
import com.t8rin.imagetoolbox.feature.erase_background.presentation.screenLogic.EraseBackgroundComponent
import com.t8rin.neural_tools.bgremover.BgRemover
import com.t8rin.neural_tools.bgremover.Sam2
import com.t8rin.neural_tools.bgremover.MobileSAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The Cut out tool's modes (its bottom bubbles). */
enum class CutTool(val label: String) {
    Smart("Smart select"),
    Erase("Erase"),
    Restore("Restore")
}

/** Brush shapes for the Erase and Restore brushes, as in Image Toolbox's Background Remover. */
private val CutShapes: List<Pair<String, DrawPathMode>> = listOf(
    "Free" to DrawPathMode.Free,
    "Line" to DrawPathMode.Line,
    "Lasso" to DrawPathMode.Lasso,
    "Rectangle" to DrawPathMode.Rect(),
    "Oval" to DrawPathMode.Oval,
    "Fill" to DrawPathMode.FloodFill(),
    "Spray" to DrawPathMode.Spray()
)

/**
 * One use of the Cut out tool. It runs Image Toolbox's Background Remover ([component]) on the
 * upright photo: its auto models, brushes and trimming, plus Smart select, which turns rough
 * painting into SAM 2.1 (Segment Anything) prompts, optionally with BiRefNet refining the edge.
 */
class CutOutSession internal constructor(
    val component: EraseBackgroundComponent,
    private val lifecycle: LifecycleRegistry
) {
    var tool by mutableStateOf(CutTool.Smart)
    var shape by mutableStateOf<DrawPathMode>(DrawPathMode.Free)
    var strokeWidth by mutableFloatStateOf(20f)
    var softness by mutableFloatStateOf(0f)
    var ghostAlpha by mutableFloatStateOf(0.2f)
    var panEnabled by mutableStateOf(false)
    var busyMessage by mutableStateOf<String?>(null)

    /** Smart select: what's painted (include, or exclude with [PaintSession.excludeMode]). */
    val smartPaint = PaintSession(PaintPurpose.Heal).apply { brushRadiusDp = 22f }

    /** The photo Smart select works on (the current cut-out state), full size and screen size. */
    internal var samSource: Bitmap? = null
    var samPreviewSource by mutableStateOf<Bitmap?>(null)
        internal set

    /** Smart select's latest result at screen size (background removed), or null. */
    var samPreview by mutableStateOf<Bitmap?>(null)
        internal set
    internal var samSession: Sam2.Session? = null

    /** Smart select: let BiRefNet redraw the edge when cutting out (see [EdgeRefiner]). */
    var refineEdges by mutableStateOf(false)
    internal val samLock = Mutex()

    /** Forget Smart select's prepared photo (after the cut-out changed). */
    internal fun resetSmart() {
        samSession?.close()
        samSession = null
        samSource = null
        samPreviewSource = null
        samPreview = null
        smartPaint.strokes.clear()
    }

    fun close() {
        resetSmart()
        lifecycle.destroy()
    }
}

/** Turns Smart select's painting into Segment Anything prompts: a box around what's painted, and points. */
internal fun samPrompt(
    strokes: List<ScreenStroke>,
    shownWidthPx: Float,
    aspect: Float
): Pair<List<MobileSAM.Point>, RectF?> {
    val points = mutableListOf<MobileSAM.Point>()
    var box: RectF? = null
    for (stroke in strokes) {
        val pts = stroke.points
        val picks = if (pts.size <= 3) pts else listOf(pts.first(), pts[pts.size / 4], pts[pts.size / 2], pts[pts.size * 3 / 4], pts.last())
        picks.forEach { points += MobileSAM.Point(it.x, it.y, positive = !stroke.exclude) }
        if (!stroke.exclude && pts.size > 1 && shownWidthPx > 0f) {
            val rx = stroke.radiusPx / shownWidthPx
            val ry = rx * aspect
            val b = RectF(
                pts.minOf { it.x } - rx, pts.minOf { it.y } - ry,
                pts.maxOf { it.x } + rx, pts.maxOf { it.y } + ry
            )
            box = box?.apply { union(b) } ?: b
        }
    }
    box?.apply { set(max(0f, left), max(0f, top), min(1f, right), min(1f, bottom)) }
    // Keep the prompt small: the decoder works best with a handful of points.
    val include = points.filter { it.positive }.takeLast(12)
    val exclude = points.filterNot { it.positive }.takeLast(12)
    return (include + exclude) to box
}

/** The Manual background remover, highlighted in the app's own colour. */
@Composable
internal fun CutOutScreen(
    session: CutOutSession,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onNeedSamDownload: () -> Unit
) {
    val context = LocalContext.current
    val accent = remember { appAccentColor(context) }
    CompositionLocalProvider(LocalAccent provides accent) {
        CutOutContent(session, onCancel, onApply, onNeedSamDownload)
    }
}

@Composable
private fun CutOutContent(
    session: CutOutSession,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onNeedSamDownload: () -> Unit
) {
    val accent = LocalAccent.current
    val component = session.component
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /** Applies Smart select's selection to the photo (the "Cut out" button). False if it didn't. */
    suspend fun cutOutSmart(): Boolean {
        val source = session.samSource ?: return false
        val sam = session.samSession ?: return false
        val preview = session.samPreviewSource ?: return false
        val strokes = session.smartPaint.strokes.toList()
        if (strokes.none { !it.exclude }) return false
        session.busyMessage = "Cutting out…"
        val (points, box) = samPrompt(strokes, session.smartPaint.shownWidthPx, preview.width.toFloat() / preview.height)
        val refine = session.refineEdges && EdgeRefiner.isDownloaded
        val done = runCatching {
            withContext(Dispatchers.Default) {
                val selection = session.samLock.withLock { sam.segment(points = points, box = box, outputImage = source) }
                if (!refine) return@withContext selection
                withContext(Dispatchers.Main) { session.busyMessage = "Refining edges… (this can take a minute)" }
                EdgeRefiner.refine(source, selection).also { if (it !== selection) selection.recycle() }
            }
        }.onSuccess {
            component.applySubjectSelection(it)
            session.resetSmart()
            session.tool = CutTool.Erase
        }.onFailure {
            ViperLog.error("CUTOUT", "cutOutSmart failed", it)
            Toast.makeText(context, "Cut out failed: ${it.message}", Toast.LENGTH_LONG).show()
        }.isSuccess
        session.busyMessage = null
        return done
    }
    var askRefineDownload by remember { mutableStateOf(false) }
    val settings = LocalSettingsState.current
    val settingsInteractor = LocalSimpleSettingsInteractor.current

    BackHandler(onBack = onCancel)

    if (askRefineDownload) {
        AlertDialog(
            onDismissRequest = { askRefineDownload = false },
            title = { Text("Download Refine edges?") },
            text = {
                Text(
                    "Refine edges uses the BiRefNet AI model (the same one as Auto background remover) to redraw " +
                            "the edge of what you select: hair and fur strands, no background fringe, no " +
                            "shadows.\n\nIt needs a one-time download of about 250 MB, and takes a while " +
                            "each time you cut out."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askRefineDownload = false
                    scope.launch {
                        session.busyMessage = "Downloading Refine edges…"
                        runCatching {
                            BgRemover.downloadModel(EdgeRefiner.model).collect {
                                session.busyMessage = "Downloading Refine edges… ${(it.currentPercent * 100).toInt()}%"
                            }
                        }.onSuccess {
                            session.refineEdges = true
                        }.onFailure {
                            ViperLog.error("CUTOUT", "Refine edges download failed", it)
                            Toast.makeText(context, "Couldn't download Refine edges: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                        session.busyMessage = null
                    }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { askRefineDownload = false }) { Text("Cancel") }
            }
        )
    }

    LaunchedEffect(component) {
        component.bgModelState.checkModels()
        // In the editor the photo keeps its size unless asked (see Options).
        if (component.trimImage) component.setTrimImage(false)
    }

    // Smart select: prepare SAM 2.1 on the current cut-out, then re-segment after every stroke.
    LaunchedEffect(session.tool, component.bitmap) {
        if (session.tool != CutTool.Smart || component.bitmap == null) return@LaunchedEffect
        if (!Sam2.isDownloaded()) {
            onNeedSamDownload()
            return@LaunchedEffect
        }
        if (session.samSession == null) {
            session.busyMessage = "Preparing Smart select…"
            runCatching {
                withContext(Dispatchers.Default) {
                    // Big camera photos are capped at 16 MP so the extra copies Smart select
                    // needs don't run the phone out of memory.
                    val full = component.getErasedBitmap(false) ?: error("no photo")
                    val source = full.scaledToFit(SMART_MAX_SIDE)
                    session.samSource = source
                    session.samPreviewSource = source.scaledToFit(1536)
                    session.samSession = Sam2.openSession(source)
                }
            }.onFailure {
                session.resetSmart()
                // Left before it was ready (e.g. switched tools): nothing went wrong.
                if (it is kotlinx.coroutines.CancellationException) {
                    session.busyMessage = null
                    throw it
                }
                ViperLog.error("CUTOUT", "Smart select setup failed", it)
                Toast.makeText(context, "Smart select couldn't start: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            session.busyMessage = null
        }
        snapshotFlow { session.smartPaint.strokes.toList() }.collectLatest { strokes ->
            val preview = session.samPreviewSource ?: return@collectLatest
            val sam = session.samSession ?: return@collectLatest
            if (strokes.none { !it.exclude }) {
                session.samPreview = null
                return@collectLatest
            }
            delay(120)
            val (points, box) = samPrompt(strokes, session.smartPaint.shownWidthPx, preview.width.toFloat() / preview.height)
            session.samPreview = runCatching {
                withContext(Dispatchers.Default) {
                    session.samLock.withLock { sam.segment(points = points, box = box, outputImage = preview) }
                }
            }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top bar: cancel, undo/redo for brush strokes, pan, apply.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Text("Manual background remover", color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (session.tool == CutTool.Smart) {
                IconButton(
                    onClick = { session.smartPaint.strokes.removeLastOrNull() },
                    enabled = session.smartPaint.strokes.isNotEmpty()
                ) { Icon(Icons.Rounded.Undo, "Undo", tint = if (session.smartPaint.strokes.isNotEmpty()) Color.White else Color.Gray) }
            } else {
                val canUndo = component.paths.isNotEmpty() || component.lastPaths.isNotEmpty()
                IconButton(onClick = component::undo, enabled = canUndo) {
                    Icon(Icons.Rounded.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
                }
                IconButton(onClick = component::redo, enabled = component.undonePaths.isNotEmpty()) {
                    Icon(Icons.Rounded.Redo, "Redo", tint = if (component.undonePaths.isNotEmpty()) Color.White else Color.Gray)
                }
            }
            // ✓ also applies a Smart select that was painted but not cut out yet, so the green
            // preview isn't silently thrown away.
            IconButton(onClick = {
                if (session.busyMessage != null) return@IconButton
                if (session.tool == CutTool.Smart && session.smartPaint.strokes.any { !it.exclude }) {
                    scope.launch { if (cutOutSmart()) onApply() }
                } else {
                    onApply()
                }
            }) { Icon(Icons.Rounded.Done, "Apply", tint = accent) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = component.bitmap
            if (session.tool == CutTool.Smart) {
                val shown = session.samPreview ?: session.samPreviewSource
                if (shown != null) {
                    val zoom = remember(session.samPreviewSource) { PaintZoom() }
                    Checkerboard(Modifier.fillMaxSize().then(with(zoom) { Modifier.zoomed() }), shown.width, shown.height)
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = with(zoom) { Modifier.fillMaxSize().zoomed() }
                    )
                    PaintOverlay(
                        imageWidth = shown.width,
                        imageHeight = shown.height,
                        session = session.smartPaint,
                        zoom = zoom,
                        brushColor = Color(0xFF34C759).copy(alpha = 0.5f)
                    )
                    var adjusting by remember { mutableStateOf(false) }
                    if (adjusting) {
                        Box(
                            Modifier
                                .size((session.smartPaint.brushRadiusDp * 2).dp)
                                .border(2.dp, Color.White, CircleShape)
                        )
                    }
                    BrushSizeSlider(
                        session = session.smartPaint,
                        onAdjusting = { adjusting = it },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                    )
                }
            } else if (session.tool == CutTool.Smart && bitmap != null) {
                // Smart select isn't ready (still preparing, not downloaded, or it failed):
                // still show the photo.
                val plain = remember(bitmap) { bitmap.scaledToFit(1536).asImageBitmap() }
                Image(
                    bitmap = plain,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            } else if (bitmap != null) {
                val imageBitmap = remember(bitmap) { bitmap.copy(Bitmap.Config.ARGB_8888, true).asImageBitmap() }
                Checkerboard(
                    Modifier
                        .padding(16.dp)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .fillMaxSize(),
                    bitmap.width, bitmap.height
                )
                BitmapEraser(
                    imageBitmap = imageBitmap,
                    imageBitmapForShader = component.internalBitmap?.asImageBitmap(),
                    paths = component.paths,
                    brushSoftness = session.softness.pt,
                    originalImagePreviewAlpha = session.ghostAlpha,
                    onAddPath = component::addPath,
                    drawPathMode = session.shape,
                    strokeWidth = session.strokeWidth.pt,
                    isRecoveryOn = session.tool == CutTool.Restore,
                    modifier = Modifier
                        .padding(16.dp)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .fillMaxSize(),
                    panEnabled = session.panEnabled,
                    helperGridParams = component.helperGridParams
                )
                if ((session.tool == CutTool.Erase || session.tool == CutTool.Restore) && session.shape.canChangeStrokeWidth) {
                    SideSlider(
                        value = session.strokeWidth,
                        range = 1f..100f,
                        onValueChange = { session.strokeWidth = it },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                    )
                }
            }
            val busy = session.busyMessage ?: if (component.isErasingBG) "Removing background…" else null
            if (busy != null || component.isImageLoading || bitmap == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(20.dp)
                ) {
                    CircularProgressIndicator(color = accent)
                    busy?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // Settings for the current mode.
        Column(
            Modifier
                .fillMaxWidth()
                // At least the usual height, taller when Smart select's extra row needs it.
                .heightIn(min = 132.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (session.tool) {
                CutTool.Smart -> {
                    Text(
                        "Roughly paint over what to keep — the AI finds its exact edge.",
                        color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Keep", !session.smartPaint.excludeMode) { session.smartPaint.excludeMode = false }
                        Pill("Leave out", session.smartPaint.excludeMode) { session.smartPaint.excludeMode = true }
                        Pill("Clear", false) { session.smartPaint.strokes.clear() }
                        Pill("Cut out", session.samPreview != null, filled = true) {
                            scope.launch { cutOutSmart() }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Pill(if (session.refineEdges) "Refine edges ✓" else "Refine edges", session.refineEdges) {
                        when {
                            session.refineEdges -> session.refineEdges = false
                            EdgeRefiner.isDownloaded -> session.refineEdges = true
                            else -> askRefineDownload = true
                        }
                    }
                }

                CutTool.Erase, CutTool.Restore -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CutShapes) { (label, mode) ->
                            Pill(label, session.shape::class == mode::class) { session.shape = mode }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LabeledSlider("Softness", session.softness, 0f..100f) { session.softness = it }
                }

            }
        }

        // Mode bubbles.
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CutTool.entries.forEach { tool ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (session.tool == tool) accent else BubbleGrey)
                            .clickable { session.tool = tool },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tool.icon, tool.label, tint = if (session.tool == tool) accent.contentOn() else Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tool.label, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

private val CutTool.icon: ImageVector
    get() = when (this) {
        CutTool.Smart -> Icons.Rounded.TouchApp
        CutTool.Erase -> Icons.Rounded.Eraser
        CutTool.Restore -> Icons.Rounded.SettingsBackupRestore
    }

@Composable
internal fun Pill(text: String, selected: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        text,
        color = if (selected) accent.contentOn() else Color.White,
        fontSize = 13.sp,
        fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else BubbleGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = LocalAccent.current, activeTrackColor = LocalAccent.current)
        )
    }
}

/** Grey and white squares behind transparent areas, sized to a photo shown with ContentScale.Fit. */
@Composable
internal fun Checkerboard(modifier: Modifier, imageWidth: Int, imageHeight: Int) {
    Canvas(modifier) {
        val rect = fittedImageRect(size.width, size.height, imageWidth, imageHeight)
        val cell = 12.dp.toPx()
        drawRect(Color(0xFFDDDDDD), topLeft = Offset(rect.left, rect.top), size = Size(rect.width, rect.height))
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left + if (row % 2 == 0) 0f else cell
            while (x < rect.right) {
                drawRect(
                    Color(0xFFAAAAAA),
                    topLeft = Offset(x, y),
                    size = Size(min(cell, rect.right - x), min(cell, rect.bottom - y))
                )
                x += cell * 2
            }
            y += cell
            row++
        }
    }
}

/** Longest side Smart select works on (about 16 MP). */
private const val SMART_MAX_SIDE = 4096
