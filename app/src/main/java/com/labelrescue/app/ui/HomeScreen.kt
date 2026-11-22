package com.labelrescue.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onScan: (() -> Unit)?,
    onImport: (() -> Unit)?,
    onManual: () -> Unit,
    onHistory: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Label Rescue", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Recover USPS tracking numbers from damaged, smudged or torn labels. Everything runs on this phone, offline.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.size(4.dp))
        onScan?.let { BigButton("Scan label (burst)", AppIcons.CameraAlt, primary = true, onClick = it) }
        onImport?.let { BigButton("Import photos", AppIcons.PhotoLibrary, onClick = it) }
        BigButton("Type digits", AppIcons.Keyboard, onClick = onManual)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onHistory?.let { BigButton("History", AppIcons.History, Modifier.weight(1f), onClick = it) }
            onSettings?.let { BigButton("Settings", AppIcons.Settings, Modifier.weight(1f), onClick = it) }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, modifier: Modifier = Modifier.fillMaxWidth(), primary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (primary) Button(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) { content() }
    else FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 64.dp)) { content() }
}

/**
 * User mode's start screen: the logo at the top, one animated Scan button, nothing else. Twenty
 * taps in the bottom-left corner (none more than 2 s apart) switch developer mode on.
 */
@Composable
fun UserHomeScreen(onScan: () -> Unit, onDeveloperMode: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var lastTap by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // The logo as big as the screen's width allows (at most 62% of the height, for short
        // screens), at the top; the Scan button exactly halfway between the logo's bottom and
        // the bottom of the screen.
        val logo = androidx.compose.ui.res.painterResource(com.labelrescue.app.R.drawable.home_logo)
        val ratio = logo.intrinsicSize.width / logo.intrinsicSize.height
        val logoWidth = minOf(maxWidth - 16.dp, maxHeight * 0.62f * ratio)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(
                logo, "Label Rescue",
                Modifier.padding(top = 8.dp).width(logoWidth).height(logoWidth / ratio),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ScanButton(onScan)
            }
        }
        // The hidden corner (no ripple, nothing shown until the last few taps).
        androidx.compose.foundation.layout.Box(
            Modifier.align(Alignment.BottomStart).size(96.dp)
                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap > 2000) 1 else taps + 1
                    lastTap = now
                    when {
                        taps >= DEV_TAPS -> { taps = 0; onDeveloperMode() }
                        taps >= DEV_TAPS - 5 -> android.widget.Toast.makeText(ctx, "${DEV_TAPS - taps} more", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
        )
    }
}

private const val DEV_TAPS = 20

/** The big Scan button: postal blue with a red edge, gently pulsing, a shine sweeping across. */
@Composable
private fun ScanButton(onClick: () -> Unit) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "scan")
    val pulse by t.animateFloat(
        1f, 1.05f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val shine by t.animateFloat(
        -0.3f, 1.3f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shine",
    )
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth(0.85f).height(84.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(shape)
            .background(androidx.compose.ui.graphics.Color(0xFF004B87))
            .drawWithContent {
                drawContent()
                val x = size.width * shine
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color(0x6680B4FF), androidx.compose.ui.graphics.Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(x - 140f, 0f), end = androidx.compose.ui.geometry.Offset(x + 140f, size.height),
                    ),
                )
            }
            .border(2.dp, androidx.compose.ui.graphics.Color(0xFFE71921), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.CameraAlt, null, Modifier.size(34.dp), tint = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.width(14.dp))
            Text("SCAN", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
        }
    }
}
