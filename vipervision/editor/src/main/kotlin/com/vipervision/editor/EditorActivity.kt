package com.vipervision.editor

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.t8rin.cropper.model.OutlineType
import com.t8rin.imagetoolbox.feature.crop.presentation.screenLogic.CropComponent
import com.t8rin.imagetoolbox.feature.draw.presentation.screenLogic.DrawComponent
import com.t8rin.imagetoolbox.feature.markup_layers.presentation.screenLogic.MarkupLayersComponent
import com.t8rin.imagetoolbox.feature.erase_background.presentation.screenLogic.EraseBackgroundComponent
import com.t8rin.neural_tools.bgremover.Sam2
import com.t8rin.neural_tools.depth.DepthAnything
import com.t8rin.neural_tools.ocr.PaddleOCR
import com.t8rin.neural_tools.viper.FaceParser
import com.t8rin.neural_tools.viper.YuNet
import com.t8rin.imagetoolbox.core.domain.image.ImageCompressor
import com.t8rin.imagetoolbox.core.settings.presentation.model.toUiState
import com.t8rin.imagetoolbox.core.ui.utils.ComposeActivity
import com.t8rin.imagetoolbox.core.ui.utils.provider.ImageToolboxCompositionLocals
import com.t8rin.imagetoolbox.feature.ai_tools.domain.AiToolsRepository
import com.t8rin.neural_tools.inpaint.LaMaProcessor
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Viper Vision's photo editor: opened by Gallery's Edit button with the photo's uri as data and
 * its file path in [EXTRA_REAL_PATH]. Save writes the edits over the original (a private copy of
 * the untouched photo is kept for Revert to original); Save as copy / Save as… add a new photo.
 */
@AndroidEntryPoint
class EditorActivity : ComposeActivity() {

    private val controller = EditorController()





    /** Image Toolbox's Background Remover, driven by the Cut out tool. */
    @Inject
    lateinit var eraseBackgroundFactory: EraseBackgroundComponent.Factory

    /** Image Toolbox's Draw screen logic, driven by the Draw tool. */
    @Inject
    lateinit var drawFactory: DrawComponent.Factory

    /** Image Toolbox's Markup Layers screen logic, driven by "Text & layers". */
    @Inject
    lateinit var markupFactory: MarkupLayersComponent.Factory

    /** Image Toolbox's image encoder (every format it supports), for "Save as…". */
    @Inject
    lateinit var imageCompressor: ImageCompressor<Bitmap>

    /** Image Toolbox's Crop screen logic, driven by Advanced crop. */
    @Inject
    lateinit var cropFactory: CropComponent.Factory

    /** Set while asking whether to download Sam2 for Cut out's Smart select. */
    private var pendingSamDownload by mutableStateOf(false)

    /** Adjustments and curves, shared by the preview and saving. */
    private val finisher = EditFinisher()

    private lateinit var source: Uri
    private var realPath: String? = null

    /** Image Toolbox's AI engine (the same one its AI Tools screen uses). */
    @Inject
    lateinit var aiRepository: AiToolsRepository<Bitmap>

    private val aiRunner by lazy { AiRunner(aiRepository) }


    /** Set while asking whether to download the Object eraser's LaMa model. */
    private var pendingLamaDownload by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val data = intent.data
        if (data == null) {
            super.onCreate(savedInstanceState)
            Toast.makeText(this, "No photo to edit", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        source = data
        realPath = intent.getStringExtra(EXTRA_REAL_PATH)
        // Opened from the screenshot toolbar's Crop or Draw.
        pendingStart = intent.getStringExtra(EXTRA_START)
        if (pendingStart == START_CROP) controller.tab = EditorTab.Transform
        ViperLog.i("EDITOR", "Opened the photo editor: $data (${intent.type ?: contentResolver.getType(data)})")
        super.onCreate(savedInstanceState)
        ViperLog.i("SAMSUNG", "Photo editors on this phone: ${SamsungHandoff.describeEditors(this)}")
        ViperLog.i("SAMSUNG", "Samsung apps: ${SamsungHandoff.describeSamsungApps(this)}")
        // Upscale 4×, Colorize and Dehaze were removed: free the space their models took if they
        // were downloaded.
        for (name in listOf(
            "4xPurePhoto-span.onnx", "4xNomosWebPhoto_RealPLKSR.onnx",
            "ddcolor_paper_tiny.ort", "ddcolor_modelscope.onnx", "dehazeformer_t.onnx"
        )) {
            java.io.File(java.io.File(filesDir, "ai_models"), name).delete()
        }
        lifecycleScope.launch {
            hasOriginal = withContext(Dispatchers.IO) {
                Originals.prune(this@EditorActivity)
                Originals.backup(this@EditorActivity, data, realPath) != null
            }
        }
    }

    /**
     * The editor, inside Image Toolbox's theme and settings (forced dark) so its pickers, sheets
     * and controls work here as they do in its own screens.
     */
    @Composable
    override fun Content() {
        if (!::source.isInitialized) return
        val source = source
        val realPath = realPath
        val appAccent = remember { appAccentColor(this) }
        ImageToolboxCompositionLocals(
            settingsState = settingsState.forEditor(appAccent).toUiState()
        ) {
          // Draw, Text & layers and the other tools opened here use the app's colour (Settings).
          androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides appAccent) {
            run {
                val scope = rememberCoroutineScope()

                LaunchedEffect(source) {
                    if (controller.basePreview != null) return@LaunchedEffect
                    val metrics = resources.displayMetrics
                    val side = min(2048, max(metrics.widthPixels, metrics.heightPixels))
                    runCatching { withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, source, side) } }
                        .onSuccess {
                            controller.basePreview = it
                            if (pendingStart == START_DRAW) startDraw(source)
                            pendingStart = null
                        }
                        .onFailure { ViperLog.error("EDITOR", "opening the photo failed", it);  controller.error = "Couldn't open this photo." }
                }

                // Re-read which models are downloaded whenever that list changes.
                val downloaded by aiRunner.downloadedModels.collectAsState()
                val lamaReady by LaMaProcessor.isDownloaded.collectAsState()

                EditorScreen(
                    controller = controller,
                    finisher = finisher,
                    aiReady = { choice -> downloaded.let { aiRunner.isReady(choice) } },
                    onRunAi = { tool, choice -> scope.runAi(source, tool, choice) },
                    lamaReady = lamaReady,
                    onStartObjectEraser = ::startObjectEraser,
                    onStartCutOut = { startCutOut(source) },
                    samsungAvailable = SamsungHandoff.enabled(this@EditorActivity) &&
                            (samsungEditor != null || SamsungHandoff.isSamsung),
                    onSamsungTool = { tool -> scope.sendToSamsung(source, tool) },
                    onStartPortraitBlur = { scope.startPortraitBlur() },
                    onApplyPortraitBlur = { scope.applyPortraitBlur(source) },
                    onStartStudio = { kind -> scope.startStudio(kind, source) },
                    onApplyStudio = { scope.applyStudio(source) },
                    onTryAll = { tool -> scope.tryAll(tool) },
                    onKeepTrial = { choice ->
                        val tool = controller.trial?.tool
                        controller.trial = null
                        if (tool != null) scope.runAi(source, tool, choice)
                    },
                    onApplyCutOut = { scope.applyCutOut() },
                    onCancelCutOut = ::closeCutOut,
                    onNeedSamDownload = { pendingSamDownload = true },
                    onStartDraw = { startDraw(source) },
                    onApplyDraw = { scope.applyDraw() },
                    onCancelDraw = ::closeDraw,
                    onStartMarkup = { startMarkup(source) },
                    onApplyMarkup = { scope.applyMarkup() },
                    onCancelMarkup = ::closeMarkup,
                    onStartCropStudio = { startCropStudio(source) },
                    onApplyCropStudio = { scope.applyCropStudio() },
                    onCancelCropStudio = ::closeCropStudio,
                    onApplyPaint = { scope.applyPaint(source) },
                    onSave = { scope.saveOverOriginal(source, realPath) },
                    onSaveCopy = { scope.save(source, realPath) },
                    onSaveAs = { openSaveAs(source) },
                    canRevert = hasOriginal,
                    onRevert = { scope.revertToOriginal(source, realPath) },
                    onSaveWithOptions = {
                        val options = controller.saveAs?.options
                        controller.saveAs = null
                        scope.save(source, realPath, options)
                    },
                    onClose = ::finish
                )

                if (pendingSamDownload) {
                    AlertDialog(
                        onDismissRequest = {
                            pendingSamDownload = false
                            controller.cutOut?.tool = CutTool.Erase
                        },
                        title = { Text("Download Smart select?") },
                        text = {
                            Text(
                                "Smart select uses the SAM 2.1 (Segment Anything) AI model: roughly paint over " +
                                        "what to keep and it finds the exact edge.\n\nIt needs a one-time download of " +
                                        "about 130 MB. After that it works offline."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingSamDownload = false
                                scope.downloadSam()
                            }) { Text("Download") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingSamDownload = false
                                controller.cutOut?.tool = CutTool.Erase
                            }) { Text("Cancel") }
                        }
                    )
                }

                if (pendingLamaDownload) {
                    AlertDialog(
                        onDismissRequest = { pendingLamaDownload = false },
                        title = { Text("Download Blemish remover?") },
                        text = {
                            Text(
                                "Paint over something and the LaMa AI model fills in what was behind it.\n\n" +
                                        "It needs a one-time download of about 200 MB. After that it works offline."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingLamaDownload = false
                                scope.downloadLama()
                            }) { Text("Download") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingLamaDownload = false }) { Text("Cancel") }
                        }
                    )
                }

            }
          }
        }
    }

    /** Opens "Save as…", working out the saved photo's size from the edits. */
    private fun openSaveAs(source: Uri) {
        val input = controller.workingFile?.let(Uri::fromFile) ?: source
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { contentResolver.openInputStream(input)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
        val geometry = controller.state.geometry
        val upright = if (controller.workingFile != null) bounds.outWidth to bounds.outHeight else {
            // Photos from the camera are often stored sideways with an EXIF rotation.
            val preview = controller.basePreview
            if (preview != null && (preview.width > preview.height) != (bounds.outWidth > bounds.outHeight)) {
                bounds.outHeight to bounds.outWidth
            } else bounds.outWidth to bounds.outHeight
        }
        val (turnedWidth, turnedHeight) = geometry.turnedSize(upright.first.coerceAtLeast(1), upright.second.coerceAtLeast(1))
        controller.saveAs = SaveAsRequest(
            options = SaveOptions(),
            source = source,
            width = (turnedWidth * geometry.crop.width).toInt().coerceAtLeast(1),
            height = (turnedHeight * geometry.crop.height).toInt().coerceAtLeast(1)
        )
    }

    /** Draw to open once the editor is up (screenshot toolbar), see [EXTRA_START]. */
    private var pendingStart: String? = null

    /** Whether a kept original exists for this photo (Revert to original). */
    private var hasOriginal by mutableStateOf(false)

    /** Bytes waiting for Android's OK to change the photo, and what to do once written. */
    private var pendingWrite: Pair<ByteArray, () -> Unit>? = null

    private val writeRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val (bytes, after) = pendingWrite ?: return@registerForActivityResult
        pendingWrite = null
        if (result.resultCode != RESULT_OK) {
            ViperLog.i("SAVE", "Permission to change the photo was refused")
            Toast.makeText(this, "Not saved: Android wasn't allowed to change the photo", Toast.LENGTH_LONG).show()
            controller.isBusy = false
            return@registerForActivityResult
        }
        lifecycleScope.launch { writeThen(bytes, after) }
    }

    /**
     * Writes [bytes] over the photo (keeping the untouched original first), then runs [after].
     * When Android asks, shows its "Allow Viper Vision to modify this photo?" and continues after.
     */
    private suspend fun writeThen(bytes: ByteArray, after: () -> Unit) {
        val source = source
        val realPath = realPath
        runCatching {
            withContext(Dispatchers.IO) {
                Originals.keep(this@EditorActivity, source, realPath)
                writeOverOriginal(this@EditorActivity, source, realPath, bytes, Originals.backup(this@EditorActivity, source, realPath))
            }
        }.onSuccess {
            controller.isBusy = false
            after()
        }.onFailure { error ->
            val mediaUri = realPath?.let { mediaStoreUri(this, it) }
            if (error is SecurityException && mediaUri != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                ViperLog.i("SAVE", "Asking Android for permission to change the photo")
                pendingWrite = bytes to after
                val request = android.provider.MediaStore.createWriteRequest(contentResolver, listOf(mediaUri))
                writeRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                ViperLog.error("SAVE", "writing over the photo failed", error)
                Toast.makeText(this, "Couldn't save: ${error.message}", Toast.LENGTH_LONG).show()
                controller.isBusy = false
            }
        }
    }

    /** Save: the edits go over the original photo (a private copy of the untouched one is kept). */
    private fun CoroutineScope.saveOverOriginal(source: Uri, realPath: String?) = launch {
        if (controller.isBusy) return@launch
        controller.isBusy = true
        val state = controller.state
        val renderFrom = controller.workingFile?.let(Uri::fromFile) ?: source
        ViperLog.i("SAVE", "Saving over the original (${state.pixelSteps.size} AI/paint steps). ${ViperLog.memory()}")
        val bytes = runCatching {
            withContext(Dispatchers.IO) {
                encodeForOverwrite(this@EditorActivity, source, realPath, state.geometry, finishFor(state), renderFrom, controller.needsPng)
            }
        }.getOrElse {
            ViperLog.error("EDITOR", "save failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
            controller.isBusy = false
            return@launch
        }
        if (bytes == null) {
            // Its format can't take these edits (or the transparency): keep the original, add a copy.
            controller.isBusy = false
            ViperLog.i("SAVE", "Original's format can't hold the edits; saving a copy instead")
            save(source, realPath, copyNote = if (controller.needsPng) "Saved as a new PNG to keep the transparency" else "This format can't be saved over, so it was saved as a new photo")
            return@launch
        }
        writeThen(bytes) {
            Toast.makeText(this@EditorActivity, "Saved", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().setData(source))
            finish()
        }
    }

    /** Revert to original: puts the kept untouched photo back and closes the editor. */
    private fun CoroutineScope.revertToOriginal(source: Uri, realPath: String?) = launch {
        if (controller.isBusy) return@launch
        val backup = Originals.backup(this@EditorActivity, source, realPath) ?: return@launch
        controller.isBusy = true
        ViperLog.i("SAVE", "Reverting to the original")
        val bytes = withContext(Dispatchers.IO) { backup.readBytes() }
        writeThen(bytes) {
            Originals.forget(this@EditorActivity, source, realPath)
            Toast.makeText(this@EditorActivity, "Back to the original photo", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().setData(source))
            finish()
        }
    }

    private fun CoroutineScope.save(
        source: Uri,
        realPath: String?,
        options: SaveOptions? = null,
        copyNote: String = "Saved as a new photo"
    ) = launch {
        if (controller.isBusy) return@launch
        controller.isBusy = true
        val state = controller.state
        val renderFrom = controller.workingFile?.let(Uri::fromFile) ?: source
        val forcePng = controller.needsPng
        ViperLog.i("SAVE", "Saving (${if (forcePng) "PNG" else "JPEG/original format"}, ${state.pixelSteps.size} AI/paint steps). ${ViperLog.memory()}")
        runCatching {
            withContext(Dispatchers.IO) {
                saveEditedCopy(
                    context = this@EditorActivity,
                    source = source,
                    realPath = realPath,
                    geometry = state.geometry,
                    finish = finishFor(state),
                    renderFrom = renderFrom,
                    forcePng = forcePng,
                    encode = options?.encoder(imageCompressor),
                    keepMetadata = options?.keepMetadata ?: true,
                    metadata = options?.metadataTags()
                )
            }
        }.onSuccess {
            Toast.makeText(this@EditorActivity, copyNote, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().setData(it))
            finish()
        }.onFailure {
            ViperLog.error("EDITOR", "save failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.isBusy = false
    }

    /**
     * Runs an AI tool on the full-size photo (downloading its model first if needed), then keeps
     * the result as a new step: the rotate, crop and adjustments apply on top of it, and undo
     * steps back to the photo before it.
     */
    private fun CoroutineScope.runAi(source: Uri, tool: AiTool, choice: AiChoice) = launch {
        if (controller.isBusy) return@launch
        controller.isBusy = true
        val steps = controller.state.pixelSteps + PixelStep.Ai(tool, choice.modelName)
        ViperLog.i("AI", "${tool.label}: ${choice.title} (${choice.modelName}), after ${controller.state.pixelSteps.size} earlier AI/paint steps. ${ViperLog.memory()}")
        runCatching {
            if (!aiRunner.isReady(choice) || choice.isBundled) {
                controller.busyMessage = "Downloading ${choice.title}…"
                aiRunner.download(choice) { progress ->
                    controller.busyMessage = "Downloading ${choice.title}… ${(progress * 100).toInt()}%"
                }
            }
            controller.busyMessage = "${tool.label}…"
            val input = controller.workingFile?.let(Uri::fromFile) ?: source
            // The AI tools work on the photo as big as memory allows. Upscaling: small enough that
            // the result is at most about 48 MP (a 4× of a full-size photo wouldn't fit).
            val maxSide = if (tool == AiTool.Upscale) {
                val factor = if ("x2" in choice.modelName) 2 else 4
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { contentResolver.openInputStream(input)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
                val w = bounds.outWidth.coerceAtLeast(1)
                val h = bounds.outHeight.coerceAtLeast(1)
                val fit = kotlin.math.sqrt(48_000_000.0 / (w.toDouble() * h * factor * factor)).coerceAtMost(1.0)
                (max(w, h) * fit).toInt().coerceIn(64, maxSafeSide(this@EditorActivity, input))
            } else maxSafeSide(this@EditorActivity, input)
            val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, input, maxSide) }
            ViperLog.i("AI", "Input ${ViperLog.describe(full)} (memory cap ${maxSide}px), from ${if (input == source) "the original" else "the previous step"}")
            val output = ViperLog.step("AI", "running ${choice.title}") {
                aiRunner.run(choice, full) { done, total ->
                    if (total > 1) controller.busyMessage = "${tool.label}… ${min(done + 1, total)} of $total"
                }
            }
            ViperLog.i("AI", "Output ${ViperLog.describe(output)}" + if (tool == AiTool.RemoveBackground) ", ${ViperLog.transparency(output)}" else "")
            controller.busyMessage = "Finishing…"
            storeResult(output)
        }.onSuccess { result ->
            ViperLog.i("AI", "${tool.label} applied. Preview ${ViperLog.describe(result.preview)}" +
                    if (tool == AiTool.RemoveBackground) ", ${ViperLog.transparency(result.preview)}" else "")
            controller.addPixelStep(steps, result)
        }.onFailure {
            ViperLog.error("EDITOR", "runAi failed", it)
            Toast.makeText(this@EditorActivity, "${tool.label} failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        withContext(Dispatchers.IO) { aiRunner.release() }
        ViperLog.i("AI", "Models released. ${ViperLog.memory()}")
        controller.busyMessage = null
        controller.isBusy = false
    }

    /** Try all: every model of [tool] on a screen-sized copy of the photo, to compare (see AiTrial). */
    private fun CoroutineScope.tryAll(tool: AiTool) = launch {
        if (controller.isBusy) return@launch
        val preview = controller.workingPreview ?: return@launch
        controller.isBusy = true
        runCatching {
            // Upscaling makes the copy 2–4× bigger, so it starts smaller.
            val small = withContext(Dispatchers.Default) { preview.scaledToFit(if (tool == AiTool.Upscale) 540 else 1080) }
            val results = ArrayList<Pair<AiChoice, Bitmap>>()
            tool.choices.forEachIndexed { i, choice ->
                val n = "${i + 1} of ${tool.choices.size}"
                runCatching {
                    if (!aiRunner.isReady(choice) || choice.isBundled) {
                        controller.busyMessage = "Downloading ${choice.title} ($n)…"
                        aiRunner.download(choice) { controller.busyMessage = "Downloading ${choice.title} ($n)… ${(it * 100).toInt()}%" }
                    }
                    controller.busyMessage = "Trying ${choice.title} ($n)…"
                    val out = withContext(Dispatchers.Default) { aiRunner.run(choice, small) { _, _ -> } }
                    results += choice to out
                }.onFailure { ViperLog.error("AI", "Try all: ${choice.title} failed", it) }
                withContext(Dispatchers.IO) { aiRunner.release() }
            }
            check(results.isNotEmpty()) { "none of the models worked" }
            AiTrial(tool, small, results)
        }.onSuccess { controller.trial = it }.onFailure {
            ViperLog.error("EDITOR", "tryAll failed", it)
            Toast.makeText(this@EditorActivity, "Try all failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /** Samsung's photo editor, if this phone has it (for the Samsung AI bubbles). */
    private val samsungEditor by lazy { SamsungHandoff.editor(this) }

    /** Set while this editor has a photo out in Samsung's editor. */
    private var sentToSamsung = false

    /**
     * Samsung AI Eraser / Move / Create: renders the photo with every edit so far (saved or not)
     * and opens it in Samsung's editor (see SamsungHandoff). It comes back when this editor shows
     * again (onResume).
     */
    private fun CoroutineScope.sendToSamsung(source: Uri, tool: SamsungTool) = launch {
        if (controller.isBusy) return@launch
        val editor = samsungEditor ?: run {
            ViperLog.i("SAMSUNG", "No Samsung photo editor found. Photo editors: ${SamsungHandoff.describeEditors(this@EditorActivity)}")
            Toast.makeText(
                this@EditorActivity,
                "Couldn't find Samsung's photo editor (Settings → Diagnostic log lists the photo editors found)",
                Toast.LENGTH_LONG
            ).show()
            return@launch
        }
        controller.isBusy = true
        controller.busyMessage = "Sending to Samsung's editor…"
        ViperLog.i("SAMSUNG", "${tool.label}: sending to ${editor.component} (${editor.action}). ${ViperLog.memory()}")
        runCatching {
            val state = controller.state
            val renderFrom = controller.workingFile?.let(Uri::fromFile) ?: source
            val (uri, modified) = withContext(Dispatchers.IO) {
                val bitmap = renderEdited(this@EditorActivity, renderFrom, state.geometry, finishFor(state))
                ViperLog.i("SAMSUNG", "Rendered ${ViperLog.describe(bitmap)}")
                SamsungHandoff.export(this@EditorActivity, bitmap).also { bitmap.recycle() }
            }
            SamsungHandoff.active = SamsungHandoff.Session(
                tool = tool,
                exported = uri,
                exportedId = android.content.ContentUris.parseId(uri),
                startedAtSec = System.currentTimeMillis() / 1000,
                exportedModifiedSec = modified
            )
            sentToSamsung = true
            ViperLog.i("SAMSUNG", "Temporary picture $uri; opening Samsung's editor")
            SamsungHandoff.open(this@EditorActivity, editor, uri)
            if (!cameraServiceOn()) {
                Toast.makeText(
                    this@EditorActivity,
                    "Tip: turn on Viper Vision in Settings → Accessibility to have the tool opened for you and a Back to Viper Vision button",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure {
            ViperLog.error("SAMSUNG", "sending to Samsung failed", it)
            SamsungHandoff.active?.let { session -> SamsungHandoff.cleanUp(this@EditorActivity, session) }
            SamsungHandoff.active = null
            sentToSamsung = false
            Toast.makeText(this@EditorActivity, "Couldn't open Samsung's editor: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    override fun onResume() {
        super.onResume()
        if (sentToSamsung && SamsungHandoff.active != null) lifecycleScope.launch { importFromSamsung() }
    }

    /** Takes in what Samsung's editor saved, as a step with every edit already in it. */
    private suspend fun importFromSamsung() {
        val session = SamsungHandoff.active ?: return
        SamsungHandoff.active = null
        sentToSamsung = false
        controller.isBusy = true
        controller.busyMessage = "Bringing back Samsung's edit…"
        ViperLog.i("SAMSUNG", "Back in Viper Vision (Back button used: ${session.returnRequested}); looking for Samsung's result")
        runCatching {
            // Samsung may still be writing the file; wait longer if the Back button asked it to save.
            var found: Uri? = null
            repeat(if (session.returnRequested) 16 else 4) {
                if (found == null) {
                    found = withContext(Dispatchers.IO) { SamsungHandoff.result(this@EditorActivity, session) }
                    if (found == null) kotlinx.coroutines.delay(500)
                }
            }
            val result = found
            if (result == null) {
                ViperLog.i("SAMSUNG", "Nothing came back")
                Toast.makeText(this@EditorActivity, "Nothing came back from Samsung's editor (it wasn't saved there)", Toast.LENGTH_LONG).show()
            } else {
                val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, result, maxSafeSide(this@EditorActivity, result)) }
                ViperLog.i("SAMSUNG", "Result $result: ${ViperLog.describe(full)}")
                val stored = storeResult(full)
                controller.addFinishedStep(controller.state.pixelSteps + PixelStep.Samsung(session.tool), stored)
                // Samsung's saved copy: its edit is in the editor now, and Save puts it in the original.
                if (result != session.exported) withContext(Dispatchers.IO) {
                    SamsungHandoff.deletePicture(this@EditorActivity, result, "Samsung's copy")
                }
                Toast.makeText(this@EditorActivity, "${session.tool.label} edit brought back", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            ViperLog.error("SAMSUNG", "bringing back Samsung's edit failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't bring back Samsung's edit: ${it.message}", Toast.LENGTH_LONG).show()
        }
        SamsungHandoff.cleanUp(this, session)
        controller.busyMessage = null
        controller.isBusy = false
    }

    private fun cameraServiceOn(): Boolean {
        val service = "$packageName/${CameraThumbnailService::class.java.name}"
        return android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(service, ignoreCase = true) }
    }

    /** Keeps a pixel step's full-size result on disk, plus a screen-sized copy for the preview. */
    private suspend fun storeResult(output: Bitmap): AiResult = withContext(Dispatchers.IO) {
        val folder = File(cacheDir, "vv_ai").apply { mkdirs() }
        val file = File(folder, "step_${System.nanoTime()}.png")
        file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val metrics = resources.displayMetrics
        val side = min(2048, max(metrics.widthPixels, metrics.heightPixels))
        AiResult(file, output.scaledToFit(side))
    }

    /** Opens the Object eraser's painting mode, downloading the LaMa model first if needed (after asking). */
    private fun startObjectEraser() {
        if (LaMaProcessor.isDownloaded.value) {
            controller.paint = PaintSession(PaintPurpose.Erase)
        } else {
            pendingLamaDownload = true
        }
    }

    private fun CoroutineScope.downloadLama() = launch {
        if (controller.isBusy) return@launch
        controller.isBusy = true
        controller.busyMessage = "Downloading Blemish remover…"
        runCatching {
            LaMaProcessor.startDownload().collect {
                controller.busyMessage = "Downloading Blemish remover… ${(it.currentPercent * 100).toInt()}%"
            }
            check(LaMaProcessor.isDownloaded.value) { "the download didn't finish" }
        }.onSuccess {
            controller.paint = PaintSession(PaintPurpose.Erase)
        }.onFailure {
            ViperLog.error("EDITOR", "downloadLama failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't download the Blemish remover: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /**
     * Fills in what the user painted over, on the full-size photo: with Viper Vision's patch
     * healing (Heal, OpenCV; see PatchHealer) or Image Toolbox's LaMa AI model (Object eraser).
     * The result becomes an undoable step.
     */
    private fun CoroutineScope.applyPaint(source: Uri) = launch {
        val session = controller.paint ?: return@launch
        val working = controller.workingPreview ?: return@launch
        if (controller.isBusy || !session.hasSomething) return@launch
        controller.isBusy = true
        val label = if (session.generative) "Blemish remover" else "Heal"
        val strokes = controller.state.geometry.toSourceStrokes(
            strokes = session.strokes.toList(),
            shownWidthPx = session.shownWidthPx,
            sourceWidth = working.width,
            sourceHeight = working.height
        )
        // Tapped objects and Auto Borders (masks on the photo as shown).
        val selections = if (session.generative) session.selections.toList() else emptyList()
        val geometry = controller.state.geometry
        controller.paint = null
        session.close()
        val steps = controller.state.pixelSteps +
            PixelStep.Heal(strokes, session.generative, if (selections.isEmpty()) 0L else System.nanoTime())
        ViperLog.i("PAINT", "$label: ${strokes.size} strokes. ${ViperLog.memory()}")
        controller.busyMessage = "$label…"
        runCatching {
            val input = controller.workingFile?.let(Uri::fromFile) ?: source
            val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, input, maxSafeSide(this@EditorActivity, input)) }
            if (session.generative) ObjectEraser.chooseFillSize(this@EditorActivity)
            val output = withContext(Dispatchers.Default) {
                if (session.generative && selections.isNotEmpty()) {
                    // The selections plus anything still painted, as one mask on the full photo.
                    val mask = BlemishSelect.toSourceMask(selections, geometry, full.width, full.height)
                    // Painting adds, the eraser takes away (also from tapped selections).
                    drawStrokes(android.graphics.Canvas(mask), strokes, full.width, full.height)
                    ObjectEraser.eraseMask(full, mask).also { mask.recycle() }
                } else if (session.generative) {
                    ObjectEraser.erase(full, strokes)
                } else {
                    PatchHealer.heal(image = full, mask = renderMask(strokes, full.width, full.height))
                }
            }
            controller.busyMessage = "Finishing…"
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(steps, result)
        }.onFailure {
            ViperLog.error("EDITOR", "applyPaint failed", it)
            Toast.makeText(this@EditorActivity, "$label failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        if (session.generative) withContext(Dispatchers.IO) { aiRunner.release() }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /**
     * Opens Portrait blur: Depth Anything works out how near each part of the photo (as edited so
     * far) is, and the background remover (BiRefNet if downloaded, else the built-in U2NetP) finds
     * the subject, whose cut-out keeps its edges crisp.
     */
    private fun CoroutineScope.startPortraitBlur() = launch {
        if (controller.isBusy || controller.portraitBlur != null) return@launch
        val preview = controller.workingPreview ?: return@launch
        controller.isBusy = true
        runCatching {
            if (!DepthAnything.isDownloaded()) {
                controller.busyMessage = "Downloading the depth model…"
                DepthAnything.download { controller.busyMessage = "Downloading the depth model… ${(it * 100).toInt()}%" }
                check(DepthAnything.isDownloaded()) { "the download didn't finish" }
            }
            controller.busyMessage = "Working out depth…"
            val depth = withContext(Dispatchers.Default) { DepthAnything.estimate(preview) }
            controller.busyMessage = "Finding the subject…"
            val choices = AiTool.RemoveBackground.choices
            val choice = choices.firstOrNull { !it.isBundled && aiRunner.isReady(it) } ?: choices.first { it.isBundled }
            if (choice.isBundled) aiRunner.download(choice) {}
            val subject = runCatching { withContext(Dispatchers.Default) { aiRunner.run(choice, preview) { _, _ -> } } }
                .onFailure { ViperLog.error("BLUR", "Couldn't find the subject (depth only)", it) }
                .getOrNull()
            ViperLog.i("BLUR", "Depth ${depth.width}x${depth.height}; subject with ${choice.title}: ${subject?.let(ViperLog::transparency)}")
            withContext(Dispatchers.Default) { PortraitBlurSession(preview, subject, depth) }
        }.onSuccess { controller.portraitBlur = it }.onFailure {
            ViperLog.error("EDITOR", "startPortraitBlur failed", it)
            Toast.makeText(this@EditorActivity, "Portrait blur couldn't start: ${it.message}", Toast.LENGTH_LONG).show()
        }
        withContext(Dispatchers.IO) { aiRunner.release() }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /**
     * Opens one of the AI tab's newer tools (see StudioKind), downloading its AI models first if
     * they aren't here yet. Red eye has no screen: it fixes the photo straight away.
     */
    private fun CoroutineScope.startStudio(kind: StudioKind, source: Uri) = launch {
        if (controller.isBusy || controller.studio != null) return@launch
        val preview = controller.workingPreview ?: return@launch
        controller.isBusy = true
        suspend fun need(name: String, here: () -> Boolean, get: suspend (suspend (Float) -> Unit) -> Unit) {
            if (here()) return
            controller.busyMessage = "Downloading $name…"
            get { controller.busyMessage = "Downloading $name… ${(it * 100).toInt()}%" }
            check(here()) { "the $name download didn't finish" }
        }
        runCatching {
            when (kind) {
                StudioKind.Selective -> {
                    need("Smart select", { Sam2.isDownloaded() }) { Sam2.download(it) }
                    controller.busyMessage = "Getting Smart select ready…"
                    val sam = withContext(Dispatchers.Default) { Sam2.openSession(preview) }
                    SelectiveAdjustTool(preview, sam)
                }
                StudioKind.RemoveText -> {
                    need("the text finder", { PaddleOCR.isModelDownloaded(PaddleOCR.Model.English) }) { p ->
                        PaddleOCR.startDownload(PaddleOCR.Model.English).collect { p(it.currentPercent) }
                    }
                    need("the eraser", { LaMaProcessor.isDownloaded.value }) { p ->
                        LaMaProcessor.startDownload().collect { p(it.currentPercent) }
                    }
                    controller.busyMessage = "Finding text…"
                    withContext(Dispatchers.Default) { RemoveTextTool.find(preview) }
                }
                StudioKind.Relight -> {
                    need("the depth model", { DepthAnything.isDownloaded() }) { DepthAnything.download(it) }
                    controller.busyMessage = "Working out depth…"
                    val depth = withContext(Dispatchers.Default) { DepthAnything.estimate(preview) }
                    // Faces, for Face shadow (fetched if missing; without them there's no Face shadow).
                    val faces = runCatching {
                        need("the face finder", { YuNet.isDownloaded() }) { YuNet.download(it) }
                        need("the face model", { FaceParser.isDownloaded() }) { FaceParser.download(it) }
                        controller.busyMessage = "Finding faces…"
                        withContext(Dispatchers.Default) { Faces.detect(preview).let { if (it.isEmpty()) emptyList() else Faces.parse(preview, it) } }
                    }.onFailure { ViperLog.error("EDITOR", "Relight: no faces (Face shadow off)", it) }.getOrDefault(emptyList())
                    withContext(Dispatchers.Default) { RelightTool(preview, depth, faces) }
                }
                StudioKind.FaceRetouch -> {
                    need("the face finder", { YuNet.isDownloaded() }) { YuNet.download(it) }
                    need("the face model", { FaceParser.isDownloaded() }) { FaceParser.download(it) }
                    controller.busyMessage = "Finding faces…"
                    withContext(Dispatchers.Default) {
                        val faces = Faces.detect(preview)
                        check(faces.isNotEmpty()) { "no faces found" }
                        FaceRetouchTool(preview, Faces.parse(preview, faces))
                    }
                }
                StudioKind.RedEye -> {
                    need("the face finder", { YuNet.isDownloaded() }) { YuNet.download(it) }
                    controller.busyMessage = "Fixing red eye…"
                    val faces = withContext(Dispatchers.Default) { Faces.detect(preview) }
                    check(faces.isNotEmpty()) { "no faces found" }
                    val input = controller.workingFile?.let(Uri::fromFile) ?: source
                    val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, input, maxSafeSide(this@EditorActivity, input)) }
                    val (output, fixed) = withContext(Dispatchers.Default) { RedEye.fix(full, faces) }
                    if (fixed == 0) {
                        Toast.makeText(this@EditorActivity, "No red eyes found", Toast.LENGTH_SHORT).show()
                    } else {
                        controller.busyMessage = "Finishing…"
                        val result = storeResult(output)
                        controller.addPixelStep(controller.state.pixelSteps + PixelStep.Tool(kind.label), result)
                        Toast.makeText(this@EditorActivity, "Fixed $fixed eye${if (fixed == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                    }
                    null
                }
            }
        }.onSuccess { tool -> controller.studio = tool }.onFailure {
            ViperLog.error("EDITOR", "startStudio ${kind.name} failed", it)
            Toast.makeText(this@EditorActivity, "${kind.label}: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /** A full-screen tool's Apply, on the full-size photo. */
    private fun CoroutineScope.applyStudio(source: Uri) = launch {
        val tool = controller.studio ?: return@launch
        if (controller.isBusy) return@launch
        controller.isBusy = true
        controller.busyMessage = "${tool.stepName}…"
        runCatching {
            val input = controller.workingFile?.let(Uri::fromFile) ?: source
            val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, input, maxSafeSide(this@EditorActivity, input)) }
            val output = withContext(Dispatchers.Default) { tool.render(this@EditorActivity, full) }
            controller.busyMessage = "Finishing…"
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.Tool(tool.stepName), result)
            controller.closeStudio()
        }.onFailure {
            ViperLog.error("EDITOR", "applyStudio ${tool.stepName} failed", it)
            Toast.makeText(this@EditorActivity, "${tool.stepName} failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        if (tool is RemoveTextTool) withContext(Dispatchers.IO) { aiRunner.release() }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /** Portrait blur's Apply, on the full-size photo. */
    private fun CoroutineScope.applyPortraitBlur(source: Uri) = launch {
        val session = controller.portraitBlur ?: return@launch
        if (controller.isBusy) return@launch
        controller.isBusy = true
        controller.busyMessage = "Blurring the background…"
        runCatching {
            val input = controller.workingFile?.let(Uri::fromFile) ?: source
            val full = withContext(Dispatchers.IO) { decodeImage(this@EditorActivity, input, maxSafeSide(this@EditorActivity, input)) }
            val output = withContext(Dispatchers.Default) { PortraitBlur.apply(full, session.depth, session.matte, session.settings()) }
            controller.busyMessage = "Finishing…"
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.PortraitBlur(), result)
            controller.portraitBlur = null
        }.onFailure {
            ViperLog.error("EDITOR", "applyPortraitBlur failed", it)
            Toast.makeText(this@EditorActivity, "Portrait blur failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    private fun startDraw(source: Uri) {
        if (controller.isBusy || controller.draw != null) return
        val input = controller.workingFile?.let(Uri::fromFile) ?: source
        val lifecycle = LifecycleRegistry()
        val component = drawFactory(
            componentContext = DefaultComponentContext(lifecycle),
            initialUri = input,
            onGoBack = ::closeDraw,
            onNavigate = {}
        )
        lifecycle.resume()
        controller.draw = DrawSession(
            component = component,
            lifecycle = lifecycle,
            defaultColor = androidx.compose.ui.graphics.Color(settingsState.defaultDrawColor.colorInt),
            defaultWidth = settingsState.defaultDrawLineWidth
        )
    }

    private fun closeDraw() {
        controller.draw?.close()
        controller.draw = null
    }

    private fun CoroutineScope.applyDraw() = launch {
        val session = controller.draw ?: return@launch
        if (controller.isBusy) return@launch
        if (!session.component.havePaths) {
            closeDraw()
            return@launch
        }
        controller.isBusy = true
        controller.busyMessage = "Drawing…"
        runCatching {
            val output = session.component.getDrawingBitmap() ?: error("nothing was drawn")
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.Drawing(), result)
            closeDraw()
        }.onFailure {
            ViperLog.error("EDITOR", "applyDraw failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't apply the drawing: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    private fun startMarkup(source: Uri) {
        if (controller.isBusy || controller.markup != null) return
        val input = controller.workingFile?.let(Uri::fromFile) ?: source
        val lifecycle = LifecycleRegistry()
        val component = markupFactory(
            componentContext = DefaultComponentContext(lifecycle),
            initialUri = input,
            onGoBack = ::closeMarkup,
            onNavigate = {}
        )
        lifecycle.resume()
        controller.markup = MarkupSession(component, lifecycle)
    }

    private fun closeMarkup() {
        controller.markup?.close()
        controller.markup = null
    }

    private fun CoroutineScope.applyMarkup() = launch {
        val session = controller.markup ?: return@launch
        if (controller.isBusy) return@launch
        if (session.component.layers.none { !it.isBackground }) {
            closeMarkup()
            return@launch
        }
        controller.isBusy = true
        controller.busyMessage = "Adding layers…"
        runCatching {
            val output = session.component.renderLayers(resources.configuration.fontScale) ?: error("nothing to add")
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.Drawing(), result)
            closeMarkup()
        }.onFailure {
            ViperLog.error("EDITOR", "applyMarkup failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't add the layers: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    private fun startCropStudio(source: Uri) {
        if (controller.isBusy || controller.cropStudio != null) return
        val input = controller.workingFile?.let(Uri::fromFile) ?: source
        val lifecycle = LifecycleRegistry()
        val component = cropFactory(
            componentContext = DefaultComponentContext(lifecycle),
            initialUri = input,
            onGoBack = ::closeCropStudio,
            onNavigate = {}
        )
        lifecycle.resume()
        controller.cropStudio = CropStudioSession(component, lifecycle)
    }

    private fun closeCropStudio() {
        controller.cropStudio?.close()
        controller.cropStudio = null
    }

    private fun CoroutineScope.applyCropStudio() = launch {
        val session = controller.cropStudio ?: return@launch
        val cropped = session.component.currentUri ?: return@launch
        if (controller.isBusy || !session.component.isBitmapChanged) return@launch
        controller.isBusy = true
        controller.busyMessage = "Cropping…"
        runCatching {
            val output = withContext(Dispatchers.IO) {
                decodeImage(this@EditorActivity, cropped, maxSafeSide(this@EditorActivity, cropped))
            }
            storeResult(output)
        }.onSuccess { result ->
            // Shaped crops leave transparent corners, so keep PNG for them.
            val shaped = session.component.cropProperties.cropOutlineProperty.outlineType != OutlineType.Rect
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.Crop(alpha = shaped), result)
            closeCropStudio()
        }.onFailure {
            ViperLog.error("EDITOR", "applyCropStudio failed", it)
            Toast.makeText(this@EditorActivity, "Couldn't crop: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    /** Opens Cut out on the upright photo, including earlier AI/heal steps. */
    private fun startCutOut(source: Uri) {
        if (controller.isBusy || controller.cutOut != null) return
        val input = controller.workingFile?.let(Uri::fromFile) ?: source
        val lifecycle = LifecycleRegistry()
        val component = eraseBackgroundFactory(
            componentContext = DefaultComponentContext(lifecycle),
            initialUri = input,
            onGoBack = ::closeCutOut,
            onNavigate = {}
        )
        lifecycle.resume()
        controller.cutOut = CutOutSession(component, lifecycle)
    }

    private fun closeCutOut() {
        controller.cutOut?.close()
        controller.cutOut = null
    }

    private fun CoroutineScope.applyCutOut() = launch {
        val session = controller.cutOut ?: return@launch
        if (controller.isBusy) return@launch
        controller.isBusy = true
        controller.busyMessage = "Cutting out…"
        runCatching {
            val output = session.component.getErasedBitmap(session.component.trimImage) ?: error("nothing to save")
            ViperLog.i("CUTOUT", "Applying the manual background remover: ${ViperLog.describe(output)}, ${ViperLog.transparency(output)}")
            storeResult(output)
        }.onSuccess { result ->
            controller.addPixelStep(controller.state.pixelSteps + PixelStep.CutOut(), result)
            closeCutOut()
        }.onFailure {
            ViperLog.error("EDITOR", "applyCutOut failed", it)
            Toast.makeText(this@EditorActivity, "Cut out failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
        controller.busyMessage = null
        controller.isBusy = false
    }

    private fun CoroutineScope.downloadSam() = launch {
        val session = controller.cutOut ?: return@launch
        session.busyMessage = "Downloading Smart select…"
        runCatching {
            Sam2.download { session.busyMessage = "Downloading Smart select… ${(it * 100).toInt()}%" }
            check(Sam2.isDownloaded()) { "the download didn't finish" }
        }.onSuccess {
            session.busyMessage = null
            // Re-run Smart select's setup now that the model is here.
            session.tool = CutTool.Erase
            session.tool = CutTool.Smart
        }.onFailure {
            ViperLog.error("EDITOR", "downloadSam failed", it)
            session.busyMessage = null
            session.tool = CutTool.Erase
            Toast.makeText(this@EditorActivity, "Couldn't download Smart select: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishFor(state: EditState): FinishEdits = { turned, width, height ->
        finisher.finish(this, turned, state, width, height)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            File(cacheDir, "vv_ai").deleteRecursively()
            controller.cutOut?.close()
            controller.draw?.close()
            controller.markup?.close()
            controller.cropStudio?.close()
            // Nothing keeps AI models loaded once the editor is closed.
            Thread { aiRunner.release() }.start()
        }
    }

    companion object {
        /** Absolute file path of the photo, so the edited copy can be saved in the same folder. */
        const val EXTRA_REAL_PATH = "com.vipervision.editor.REAL_PATH"

        /** Which tool to open on: [START_CROP] (Rotate & crop tab) or [START_DRAW]. */
        const val EXTRA_START = "com.vipervision.editor.START"
        const val START_CROP = "crop"
        const val START_DRAW = "draw"
    }
}
