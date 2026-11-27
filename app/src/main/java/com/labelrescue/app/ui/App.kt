package com.labelrescue.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelrescue.app.AppViewModel
import com.labelrescue.app.camera.CameraScreen
import com.labelrescue.app.camera.CropScreen

enum class Screen(val title: String) {
    HOME("Label Rescue"), MANUAL("Type digits"), CAMERA("Scan label"), CROP("Crop (optional)"), PROCESSING("Recovering"), RESULTS("Results"),
    HISTORY("Confirmed history"), SETTINGS("Settings"), BARCODE("Barcode")
}

/**
 * The crop screen (drag boxes around the barcode and number) is hidden: the app locates both
 * itself, and a hand-drawn barcode box skipped the quick read and could cut the quiet zone the
 * damaged-barcode reader needs. Kept, unreachable, for development; true brings it back.
 */
private const val SHOW_CROP_SCREEN = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelRescueApp(vm: AppViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var barcodeNumber by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * A number copied from USPS.com or ups.com and accepted. Developer mode: it becomes the result
     * (or opens as a typed number if its length differs). User mode: USPS confirmed it, so it goes
     * straight to the barcode screen (the results show it too, for Back).
     */
    fun useCopied(n: com.labelrescue.core.usps.ParsedNumber) {
        val fits = vm.useUspsNumber(n)
        when {
            !vm.devMode.value -> { barcodeNumber = n.tracking; screen = Screen.BARCODE }
            !fits -> { vm.onManualText(n.tracking); screen = Screen.MANUAL }
        }
    }
    val dev by vm.devMode.collectAsStateWithLifecycle()
    var askDiscard by remember { mutableStateOf(false) }
    fun back() {
        if (screen == Screen.BARCODE) { screen = Screen.RESULTS; return }
        // User mode: scan → reading → result, and back to the start (the scan is forgotten).
        if (!dev) {
            if (screen == Screen.PROCESSING) vm.cancelRecovery()
            if (screen == Screen.RESULTS || screen == Screen.PROCESSING) vm.endScan()
            screen = Screen.HOME
            return
        }
        screen = when (screen) {
            Screen.CROP -> Screen.HOME
            Screen.PROCESSING -> { vm.cancelRecovery(); if (SHOW_CROP_SCREEN) Screen.CROP else Screen.HOME }
            Screen.RESULTS -> if (SHOW_CROP_SCREEN) Screen.CROP else Screen.HOME
            else -> Screen.HOME
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { back() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris) {
            if (SHOW_CROP_SCREEN) screen = Screen.CROP
            else { screen = Screen.PROCESSING; vm.startRecovery { screen = Screen.RESULTS } }
        }
    }
    fun pickPhotos() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportHistory) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importHistory) }
    val ctx = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.messageShown() } }
    if (askDiscard) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text("Discard this scan?") },
        text = { Text("Everything from it is forgotten: the photos, the digits it read, your picks, and anything it saved for training. You go back to the start.", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) },
        confirmButton = { androidx.compose.material3.Button(onClick = { askDiscard = false; vm.discardScan { screen = Screen.HOME } }) { Text("Discard") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { askDiscard = false }) { Text("Keep it") } },
    )
    // An export too big for one upload was split: say so plainly, with the number of files.
    val split by vm.splitExport.collectAsStateWithLifecycle()
    split?.let { sp ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Export saved as ${sp.parts} files", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("It was too big for one upload, so it was split into ${sp.parts} files, each under 30 MB.", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    Text("\nSend all ${sp.parts}. They're in ${sp.where}, named …-part1of${sp.parts}.zip to …-part${sp.parts}of${sp.parts}.zip.", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    Text("\nThey delete themselves in 10 minutes.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { androidx.compose.material3.Button(onClick = vm::splitExportShown) { Text("OK, I'll send all ${sp.parts}") } },
        )
    }
    LaunchedEffect(screen) { if (screen == Screen.SETTINGS) vm.loadSettings() }
    val reportCount by vm.reportCount.collectAsStateWithLifecycle()
    val reportPhoto by vm.reportPhoto.collectAsStateWithLifecycle()
    val reportPhotos by vm.reportPhotos.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshReportCount() }

    Scaffold(
        topBar = {
            if (screen != Screen.HOME && screen != Screen.CAMERA && screen != Screen.BARCODE) TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = { IconButton(onClick = { back() }) { Icon(AppIcons.ArrowBack, "Back") } },
                // Forget this scan entirely (photos, digits, picks, anything saved from it).
                actions = {
                    if (screen == Screen.PROCESSING || screen == Screen.RESULTS || screen == Screen.MANUAL)
                        androidx.compose.material3.TextButton(onClick = { askDiscard = true }) { Text("Discard scan", style = androidx.compose.material3.MaterialTheme.typography.titleMedium) }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.HOME -> if (!dev) UserHomeScreen(
                    onScan = { vm.clearFrames(); screen = Screen.CAMERA },
                    onDeveloperMode = { vm.setDevMode(true) },
                ) else HomeScreen(
                    onScan = { vm.clearFrames(); screen = Screen.CAMERA },
                    onImport = { vm.clearFrames(); pickPhotos() },
                    onManual = { screen = Screen.MANUAL },
                    onHistory = { screen = Screen.HISTORY }, onSettings = { screen = Screen.SETTINGS },
                )
                Screen.MANUAL -> {
                    val text by vm.manualText.collectAsStateWithLifecycle()
                    val err by vm.manualError.collectAsStateWithLifecycle()
                    val st by vm.manual.state.collectAsStateWithLifecycle()
                    ManualScreen(
                        text, vm::onManualText, err, st,
                        ResultActions(
                            vm.manual::pick, vm.manual::unpick, vm.manual::select,
                            onConfirm = { vm.confirm(it, "manual") },
                            onUseNumber = { vm.onManualText(it.tracking) },
                        ),
                    )
                }
                Screen.CAMERA -> CameraScreen(onFrame = vm::addFrame, onBurstDone = {
                    // Straight to reading (the crop screen is hidden; see SHOW_CROP_SCREEN).
                    if (dev && SHOW_CROP_SCREEN) screen = Screen.CROP
                    else { screen = Screen.PROCESSING; vm.startRecovery { screen = Screen.RESULTS } }
                })
                Screen.CROP -> {
                    val preview by vm.preview.collectAsStateWithLifecycle()
                    val frames by vm.frames.collectAsStateWithLifecycle()
                    val bc by vm.barcodeCrop.collectAsStateWithLifecycle()
                    val nc by vm.numberCrop.collectAsStateWithLifecycle()
                    val p = preview
                    if (p == null) Text("No photo yet.", Modifier.padding(16.dp))
                    else CropScreen(p, frames.size, bc, nc, vm::setCrops,
                        onStart = { screen = Screen.PROCESSING; vm.startRecovery { screen = Screen.RESULTS } },
                        onAddMore = { pickPhotos() })
                }
                Screen.PROCESSING -> {
                    val status by vm.status.collectAsStateWithLifecycle()
                    val progress by vm.progress.collectAsStateWithLifecycle()
                    val live by vm.liveLog.collectAsStateWithLifecycle()
                    ProcessingScreen(progress, status, onCancel = { back() }, live = live)
                }
                Screen.HISTORY -> {
                    val items by vm.history.collectAsStateWithLifecycle()
                    val learned by vm.learned.collectAsStateWithLifecycle()
                    HistoryScreen(
                        items, learned, vm::deleteConfirmed, vm::clearHistory,
                        onExport = { exporter.launch("label-rescue-history.json") },
                        onImport = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                }
                Screen.SETTINGS -> {
                    val ui by vm.settingsUi.collectAsStateWithLifecycle()
                    SettingsScreen(
                        ui, vm::setSaveCrops,
                        vm::exportTrainingData, vm::diagnosticLog, vm::clearTrainingCrops,
                        onQuickScan = vm::setQuickScan,
                        onLearnFromIntact = vm::setLearnFromIntact,
                        onLeaveDevMode = { vm.setDevMode(false); screen = Screen.HOME },
                    )
                }
                Screen.BARCODE -> {
                    val t = barcodeNumber
                    // The ZIP only when the barcode itself proved it (a guessed ZIP would mis-sort the package).
                    val full = vm.photo.state.collectAsStateWithLifecycle().value?.photo?.output?.fullDecode
                    if (t != null) BarcodeScreen(
                        t, full?.zip?.takeIf { full.tracking == t },
                        onBack = { screen = Screen.RESULTS },
                        onDone = { vm.endScan(); screen = Screen.HOME },
                    )
                }
                Screen.RESULTS -> {
                    val st by vm.photo.state.collectAsStateWithLifecycle()
                    val none by vm.noResult.collectAsStateWithLifecycle()
                    val stickerUps by vm.stickerUps.collectAsStateWithLifecycle()
                    val stickerAsked by vm.stickerAsked.collectAsStateWithLifecycle()
                    val stickerBusy by vm.stickerBusy.collectAsStateWithLifecycle()
                    var stickerUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                    val takeSticker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
                    ) { ok ->
                        val u = stickerUri
                        if (ok && u != null) vm.readUpsSticker(android.net.Uri.parse(u))
                    }
                    PhotoResultsScreen(
                        st, none,
                        ResultActions(
                            vm.photo::pick, vm.photo::unpick, vm.photo::select,
                            // User mode: "This is correct" shows the number as barcodes (nothing is saved).
                            onConfirm = { if (dev) vm.confirm(it, "photo") else { barcodeNumber = it; screen = Screen.BARCODE } },
                            onUseNumber = { n -> useCopied(n) },
                        ),
                        onManual = { screen = Screen.MANUAL },
                        onRetry = if (SHOW_CROP_SCREEN) { { screen = Screen.CROP } } else null,
                        reportCount = reportCount,
                        onExportTraining = vm::exportTrainingData,
                        reportPhotos = reportPhotos,
                        reportPhoto = reportPhoto,
                        onUseUspsNumber = { n -> useCopied(n) },
                        simple = !dev,
                        stickerUps = stickerUps,
                        stickerAsked = stickerAsked,
                        stickerBusy = stickerBusy,
                        onStickerNo = vm::stickerAnswered,
                        onStickerPhoto = {
                            vm.stickerAnswered()
                            val u = vm.stickerPhotoUri()
                            stickerUri = u.toString()
                            runCatching { takeSticker.launch(u) }.onFailure { vm.showMessage("No camera app to take the sticker photo.") }
                        },
                    )
                }
            }
        }
    }
}
