package com.labelrescue.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colors for digit certainty, readable in both themes. */
data class DigitColors(val certain: Color, val likely: Color, val guessed: Color, val picked: Color)

val LocalDigitColors = staticCompositionLocalOf {
    DigitColors(Color.Unspecified, Color(0xFFB26A00), Color(0xFFC62828), Color(0xFF1565C0))
}

@Composable
fun LabelRescueTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val digits = if (dark) DigitColors(scheme.onSurface, Color(0xFFFFC94D), Color(0xFFFF7A70), Color(0xFF82B1FF))
    else DigitColors(scheme.onSurface, Color(0xFF9A5B00), Color(0xFFC62828), Color(0xFF1565C0))
    androidx.compose.runtime.CompositionLocalProvider(LocalDigitColors provides digits) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
