package com.labelrescue.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelrescue.app.AppViewModel

enum class Screen(val title: String) { HOME("Label Rescue"), MANUAL("Type digits") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelRescueApp(vm: AppViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    Scaffold(
        topBar = {
            if (screen != Screen.HOME) TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = { IconButton(onClick = { screen = Screen.HOME }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        androidx.compose.foundation.layout.Box(m) {
            when (screen) {
                Screen.HOME -> HomeScreen(onScan = null, onImport = null, onManual = { screen = Screen.MANUAL }, onHistory = null, onSettings = null)
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
            }
        }
    }
}
