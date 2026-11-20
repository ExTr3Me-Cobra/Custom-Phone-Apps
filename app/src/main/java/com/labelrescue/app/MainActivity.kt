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
        setContent { LabelRescueTheme { LabelRescueApp() } }
    }
}
