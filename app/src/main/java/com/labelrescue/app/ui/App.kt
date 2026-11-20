package com.labelrescue.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    HISTORY("Confirmed history"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelRescueApp(vm: AppViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    fun back() {
        screen = when (screen) {
            Screen.CROP -> Screen.HOME
            Screen.PROCESSING -> { vm.cancelRecovery(); Screen.CROP }
            Screen.RESULTS -> Screen.CROP
            else -> Screen.HOME
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { back() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris) { screen = Screen.CROP }
    }
    fun pickPhotos() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportHistory) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importHistory) }
    val cropExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportTrainingCrops) }
    val ctx = LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.messageShown() } }
    LaunchedEffect(screen) { if (screen == Screen.SETTINGS) vm.loadSettings() }
    val manualV by vm.manualVerify.state.collectAsStateWithLifecycle()
    val photoV by vm.photoVerify.state.collectAsStateWithLifecycle()
    fun verifyActions(c: com.labelrescue.app.verify.VerificationController, s: com.labelrescue.app.verify.VerificationController.UiState) =
        VerifyActions(s, c::askContext, c::confirmContext, c::dismissContext, c::cancel)

    Scaffold(
        topBar = {
            if (screen != Screen.HOME && screen != Screen.CAMERA) TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
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
                            verify = verifyActions(vm.manualVerify, manualV),
                        ),
                    )
                }
                Screen.CAMERA -> CameraScreen(onFrame = vm::addFrame, onBurstDone = { screen = Screen.CROP })
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
                    ProcessingScreen(progress, status, onCancel = { back() })
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
                    SettingsScreen(ui, vm::saveSettings, vm::clearVerificationCache, vm::setSaveCrops, { cropExporter.launch("label-rescue-digit-crops.zip") }, vm::clearTrainingCrops)
                }
                Screen.RESULTS -> {
                    val st by vm.photo.state.collectAsStateWithLifecycle()
                    val none by vm.noResult.collectAsStateWithLifecycle()
                    PhotoResultsScreen(
                        st, none,
                        ResultActions(
                            vm.photo::pick, vm.photo::unpick, vm.photo::select,
                            onConfirm = { vm.confirm(it, "photo") },
                            verify = verifyActions(vm.photoVerify, photoV),
                        ),
                        onManual = { screen = Screen.MANUAL },
                        onRetry = { screen = Screen.CROP },
                    )
                }
            }
        }
    }
}
