package com.labelrescue.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.labelrescue.app.ui.LabelRescueApp
import com.labelrescue.app.ui.LabelRescueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Exports older than 10 minutes are deleted (in case the scheduled cleanup didn't run).
        Thread { runCatching { com.labelrescue.app.data.ExportCleanup.sweep(applicationContext) } }.start()
        setContent { LabelRescueTheme { LabelRescueApp() } }
    }
}
