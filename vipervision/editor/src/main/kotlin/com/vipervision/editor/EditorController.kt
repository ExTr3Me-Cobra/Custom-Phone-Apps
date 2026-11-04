package com.vipervision.editor

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Everything the user has changed. Undo and redo step through these. */
data class EditState(
    val geometry: Geometry = Geometry(),
    val adjustments: Adjustments = Adjustments(),
    /** AI tools and heals applied, in order. They run first, on the upright photo. */
    val pixelSteps: List<PixelStep> = emptyList()
) {
    val isUnchanged: Boolean
        get() = geometry.isIdentity && adjustments.isIdentity && pixelSteps.isEmpty()
}

enum class EditorTab { Ai, Transform, Adjust, Decorate }

/** How the brush paints: freehand, straight lines, or a polygon of tapped corners. */
enum class BrushShape(val label: String) { Free("Free"), Line("Line"), Polygon("Polygon") }

/** What a painting session is for. */
enum class PaintPurpose { Heal, Erase }

/** The photo after a sequence of pixel steps: full size on disk, plus a screen-sized copy. */
class AiResult(val file: File, val preview: Bitmap)

/**
 * Painting over something to remove: the Heal bubble, or the AI Object eraser. Strokes are in
 * screen terms until applied.
 */
class PaintSession(val purpose: PaintPurpose) {
    /** The Object eraser (LaMa AI) rather than Heal. */
    val generative: Boolean get() = purpose == PaintPurpose.Erase

    val strokes = mutableStateListOf<ScreenStroke>()

    /** Brush radius in dp. */
    var brushRadiusDp by mutableFloatStateOf(18f)

    /** How wide the photo is on screen while painting, to convert the brush size. */
    var shownWidthPx = 0f

    /** Cut out's Smart select: the next strokes mark what to leave out. Blemish remover: the eraser. */
    var excludeMode by mutableStateOf(false)

    /** Free painting, straight lines, or a polygon of tapped corners. */
    var shape by mutableStateOf(BrushShape.Free)

    /** Polygon's own line thickness (radius in dp): thinnest to start with. */
    var polygonRadiusDp by mutableFloatStateOf(BrushRadiusRange.start)

    /** Changes Polygon's thickness, also of polygons already finished ([px]: the same on screen). */
    fun setPolygonRadius(dp: Float, px: Float) {
        polygonRadiusDp = dp
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.polygon && s.radiusPx != px) strokes[i] = s.copy(radiusPx = px)
        }
    }

    /** Polygon: the corners tapped so far (fractions of the photo). */
    val polygon = mutableStateListOf<androidx.compose.ui.geometry.Offset>()

    /** Indices of [polygon]'s corners, oldest first (for Undo). */
    val polygonOrder = mutableListOf<Int>()

    /** The polygon so far becomes a stroke (3+ corners and [closed]: filled inside too). */
    fun finishPolygon(closed: Boolean, radiusPx: Float) {
        if (polygon.size >= 2) addStroke(ScreenStroke(polygon.toList(), radiusPx, exclude = false, closed = closed && polygon.size >= 3, polygon = true))
        polygon.clear()
        polygonOrder.clear()
    }

    /**
     * Blemish remover: whole objects picked by tapping, and paintings snapped to the object
     * under them (Auto Borders). Smart select's masks, white on black, the size of the photo as
     * shown ([selectionsOf]).
     */
    val selections = mutableStateListOf<android.graphics.Bitmap>()

    /** The photo the [selections] were made on (its size and look on screen). */
    var selectionsOf: android.graphics.Bitmap? = null

    /** Red tint of the [selections], for showing over the photo. */
    var selectionTint by mutableStateOf<android.graphics.Bitmap?>(null)

    /** What was added, newest last (true: a selection, false: a stroke), for Undo. */
    val history = mutableListOf<Boolean>()

    /** Smart select's prepared photo (opened once, then each tap is quick). */
    internal var sam: com.t8rin.neural_tools.bgremover.Sam2.Session? = null
    internal val samLock = kotlinx.coroutines.sync.Mutex()

    /** Shown over the photo while Smart select works. */
    var busyMessage by mutableStateOf<String?>(null)

    fun addStroke(stroke: ScreenStroke) {
        strokes.add(stroke)
        history.add(false)
        undone.clear()
    }

    fun addSelection(mask: android.graphics.Bitmap) {
        selections.add(mask)
        history.add(true)
        undone.clear()
    }

    /** What Undo took back (a stroke, a selection or a polygon corner), for Redo. */
    private val undone = mutableStateListOf<Any>()

    val canUndo: Boolean get() = strokes.isNotEmpty() || selections.isNotEmpty() || polygon.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** Takes back the last polygon corner, stroke or selection. */
    fun undo() {
        // A polygon being made: its last corner first.
        if (polygon.isNotEmpty()) {
            removeNewestCorner(polygon, polygonOrder)?.let { undone.add(Corner(it)) }
            return
        }
        when (history.removeLastOrNull()) {
            true -> selections.removeLastOrNull()?.let { undone.add(it) }
            else -> strokes.removeLastOrNull()?.let { undone.add(it) }
        }
    }

    /** Puts back what [undo] last took. */
    fun redo() {
        when (val item = undone.removeLastOrNull()) {
            is Corner -> insertCorner(polygon, item.point, order = polygonOrder)
            is ScreenStroke -> { strokes.add(item); history.add(false) }
            is android.graphics.Bitmap -> { selections.add(item); history.add(true) }
        }
    }

    private class Corner(val point: androidx.compose.ui.geometry.Offset)

    val hasSomething: Boolean get() = strokes.isNotEmpty() || selections.isNotEmpty()

    fun close() {
        sam?.close()
        sam = null
    }
}

/** Crop frame shapes. [ratio] is width / height, or null for no fixed shape. */
enum class CropAspect(val label: String, private val fixed: Float?) {
    Free("Free", null),
    Original("Original", null),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f);

    fun ratio(imageWidth: Int, imageHeight: Int): Float? = when (this) {
        Free -> null
        Original -> imageWidth.toFloat() / imageHeight
        else -> fixed
    }

    /** The matching shape after a quarter turn (4:3 becomes 3:4 and so on). */
    fun rotated(): CropAspect = when (this) {
        FourThree -> ThreeFour
        ThreeFour -> FourThree
        SixteenNine -> NineSixteen
        NineSixteen -> SixteenNine
        else -> this
    }
}

/** Holds the editor's state for the screen; survives rotation because the activity handles it. */
class EditorController {
    var state by mutableStateOf(EditState())
        private set
    var tab by mutableStateOf(EditorTab.Adjust)
    var selected by mutableStateOf(Adjustment.Brightness)

    /** Pinch zoom on the photo; stays where it was left through edits, tabs and painting. */
    val viewZoom = PaintZoom()

    /** Screen-sized copy of the photo, upright, before any edits. */
    var basePreview by mutableStateOf<Bitmap?>(null)

    /** [workingPreview] with the current edits applied, for display. */
    var editedPreview by mutableStateOf<Bitmap?>(null)

    var isBusy by mutableStateOf(false)

    /** Set while a dial is being turned, for the quicker live preview. */
    var isInteracting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** What a long-running job (AI download or processing) is doing, shown over the photo. */
    var busyMessage by mutableStateOf<String?>(null)

    /** Results of pixel steps, keyed by the steps that produced them, so undo/redo is instant. */
    val aiResults = mutableStateMapOf<List<PixelStep>, AiResult>()

    /** The photo the geometry and adjustments apply to: the latest pixel-step result, or the original. */
    val workingPreview: Bitmap?
        get() = state.pixelSteps.takeIf { it.isNotEmpty() }?.let { aiResults[it]?.preview } ?: basePreview

    /** Full-size file of the current pixel-step result, if any are applied. */
    val workingFile: File?
        get() = state.pixelSteps.takeIf { it.isNotEmpty() }?.let { aiResults[it]?.file }

    /**
     * Remove background makes transparency, which JPEG can't hold. Only steps after the last
     * Samsung edit count: Samsung's result replaces the whole picture (opaque), so transparency
     * from before it is gone (and saving made a PNG copy instead of saving over the original).
     */
    val needsPng: Boolean
        get() = state.pixelSteps.drop(state.pixelSteps.indexOfLast { it is PixelStep.Samsung } + 1).any {
            (it is PixelStep.Ai && it.tool == AiTool.RemoveBackground) || it is PixelStep.CutOut || (it is PixelStep.AiModel && it.alpha) ||
                    (it is PixelStep.Crop && it.alpha)
        }

    /** Records a finished pixel step (AI tool or heal) and its result. */
    fun addPixelStep(steps: List<PixelStep>, result: AiResult) {
        aiResults[steps] = result
        change(state.copy(pixelSteps = steps))
    }

    /**
     * Records a result that already has every edit in it (Samsung's editor): the rotate/crop,
     * adjustments and curves start over on top of it. Undo brings them back.
     */
    fun addFinishedStep(steps: List<PixelStep>, result: AiResult) {
        aiResults[steps] = result
        change(EditState(pixelSteps = steps))
    }

    /** Set while the user is painting over something to heal or erase. */
    var paint by mutableStateOf<PaintSession?>(null)

    /** Set while comparing an AI tool's models (Try all). */
    var trial by mutableStateOf<AiTrial?>(null)

    /** Set while a full-screen tool (Selective adjust, Relight…) is open. */
    var studio by mutableStateOf<StudioTool?>(null)

    fun closeStudio() {
        studio?.close()
        studio = null
    }

    /** Set while Portrait blur is open. */
    var portraitBlur by mutableStateOf<PortraitBlurSession?>(null)

    /** Set while the Cut out tool is open. */
    var cutOut by mutableStateOf<CutOutSession?>(null)


    /** Set while the Draw tool is open. */
    var draw by mutableStateOf<DrawSession?>(null)

    /** Set while "Save as…" is open. */
    var saveAs by mutableStateOf<SaveAsRequest?>(null)

    /** Set while Advanced crop is open. */
    var cropStudio by mutableStateOf<CropStudioSession?>(null)

    /** Set while Markup layers (text, pictures, emoji, shapes) is open. */
    var markup by mutableStateOf<MarkupSession?>(null)

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Last state pushed to history; slider drags only commit once released. */
    private var committed = EditState()

    /** Live change (e.g. while dragging a slider); call [commit] when the gesture ends. */
    fun preview(newState: EditState) {
        state = newState
    }

    fun commit() {
        if (state == committed) return
        undoStack.addLast(committed)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        committed = state
    }

    fun change(newState: EditState) {
        preview(newState)
        commit()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(committed)
        committed = previous
        state = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(committed)
        committed = next
        state = next
    }

    fun revert() {
        cropAspect = CropAspect.Free
        change(EditState())
    }

    fun setAdjustment(adjustment: Adjustment, value: Int) =
        preview(state.copy(adjustments = state.adjustments.with(adjustment, value)))

    /** Turns the photo (and the crop frame with it) a quarter turn. */
    fun rotate(clockwise: Boolean) {
        val g = state.geometry
        val turns = if (clockwise) 1 else 3
        var crop = g.crop
        repeat(turns) { crop = crop.rotatedClockwise() }
        cropAspect = cropAspect.rotated()
        change(state.copy(geometry = g.copy(quarterTurns = (g.quarterTurns + turns) % 4, crop = crop)))
    }

    /**
     * Mirrors the photo as it's shown. The mirror is applied before the rotation, so mirroring a
     * rotated photo also reverses the rotation (flip then turn back equals turn then flip).
     */
    fun flip() {
        val g = state.geometry
        change(
            state.copy(
                geometry = Geometry(
                    quarterTurns = (4 - g.quarterTurns) % 4,
                    flipped = !g.flipped,
                    crop = g.crop.mirrored()
                )
            )
        )
    }

    /** Aspect ratio the crop frame keeps while dragging (Free = none). */
    var cropAspect by mutableStateOf(CropAspect.Free)

    /** Live crop change while dragging the frame; call [commit] when the drag ends. */
    fun setCrop(crop: CropRect) = preview(state.copy(geometry = state.geometry.copy(crop = crop)))

    /** Picks an aspect ratio and sets the largest centred crop with it. */
    fun selectCropAspect(aspect: CropAspect, imageWidth: Int, imageHeight: Int) {
        cropAspect = aspect
        val ratio = aspect.ratio(imageWidth, imageHeight) ?: return
        val imageRatio = imageWidth.toFloat() / imageHeight
        val crop = if (imageRatio > ratio) {
            val w = ratio / imageRatio
            CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        } else {
            val h = imageRatio / ratio
            CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        }
        change(state.copy(geometry = state.geometry.copy(crop = crop)))
    }

    fun resetCrop() {
        cropAspect = CropAspect.Free
        change(state.copy(geometry = state.geometry.copy(crop = CropRect())))
    }
}
