package com.labelrescue.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    HOME("Label Rescue"), MANUAL("Type digits"), CAMERA("Scan label"), CROP("Crop (optional)"), PROCESSING("Recovering"), RESULTS("Results")
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
                    onHistory = null, onSettings = null,
                )
                Screen.MANUAL -> {
                    val text by vm.manualText.collectAsStateWithLifecycle()
                    val err by vm.manualError.collectAsStateWithLifecycle()
                    val st by vm.manual.state.collectAsStateWithLifecycle()
                    ManualScreen(
                        text, vm::onManualText, err, st,
                        ResultActions(vm.manual::pick, vm.manual::unpick, vm.manual::select, onConfirm = null, onVerify = null),
                        verifyStatus = null,
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
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(status ?: "Working…", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Screen.RESULTS -> {
                    val st by vm.photo.state.collectAsStateWithLifecycle()
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        st?.let {
                            ResultsPanel(it, ResultActions(vm.photo::pick, vm.photo::unpick, vm.photo::select, onConfirm = null, onVerify = null))
                        }
                    }
                }
            }
        }
    }
}
