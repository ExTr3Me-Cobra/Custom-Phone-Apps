package com.labelrescue.app.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.common.BitMatrix
import com.labelrescue.core.usps.BarcodeImages
import com.labelrescue.core.usps.TrackingFormat

/**
 * The confirmed number as scannable USPS barcodes on a white screen: the square GS1 DataMatrix
 * (IMmb) at the top and the bottom, the IMpb barcode with the printed number in the middle, the
 * three equally spaced. Full brightness while shown, so a scanner reads it off the phone. Back
 * returns to the results, Done to the start screen.
 */
@Composable
fun BarcodeScreen(tracking: String, zip: String?, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        val before = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = before ?: -1f }
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val impb = remember(tracking, zip) { bitmap(BarcodeImages.impb(tracking, zip)) }
    val immb = remember(tracking, zip) { bitmap(BarcodeImages.immb(tracking, zip)) }
    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            Matrix(immb)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("USPS TRACKING #", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Crisp(impb, "USPS barcode for $tracking", maxHeight = 120.dp, stretchHeight = true)
                Text(TrackingFormat.grouped(tracking), color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Matrix(immb)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 130.dp)) { Text("Back", color = Color(0xFF004B87), fontSize = 18.sp) }
            Button(
                onClick = onDone, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 130.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004B87), contentColor = Color.White),
            ) { Text("Done", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun Matrix(b: Bitmap) = Crisp(b, "USPS 2D barcode", maxHeight = 150.dp, stretchHeight = false)

/**
 * A barcode drawn at a whole number of screen pixels per module (as wide as fits), so every bar
 * and space keeps its exact width: scanners need the widths to be true multiples of a module.
 * [stretchHeight]: a 1D code is one row high, drawn [maxHeight] tall.
 */
@Composable
private fun Crisp(b: Bitmap, description: String, maxHeight: androidx.compose.ui.unit.Dp, stretchHeight: Boolean) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxW = with(density) { maxWidth.roundToPx() }
        val maxH = with(density) { maxHeight.roundToPx() }
        val k = maxOf(1, if (stretchHeight) maxW / b.width else minOf(maxW / b.width, maxH / b.height))
        val wPx = b.width * k
        val hPx = if (stretchHeight) maxH else b.height * k
        val scaled = remember(b, k, hPx) { Bitmap.createScaledBitmap(b, wPx, hPx, false) }
        Image(
            scaled.asImageBitmap(), description,
            Modifier.androidxSize(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }),
            contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
        )
    }
}

private fun Modifier.androidxSize(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) =
    this.then(Modifier.size(w, h))

/** One pixel per module; drawn scaled up without smoothing, so the edges stay sharp. */
private fun bitmap(m: BitMatrix): Bitmap {
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
