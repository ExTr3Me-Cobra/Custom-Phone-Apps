package com.labelrescue.app.ui

import androidx.compose.ui.graphics.asImageBitmap
import com.labelrescue.app.data.TrainingReportStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labelrescue.app.ResultState
import com.labelrescue.core.fusion.Progress
import com.labelrescue.core.fusion.RecoveryOutput
import com.labelrescue.core.fusion.Source

@Composable
fun ProcessingScreen(progress: Progress?, status: String?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Running every decoder on every frame and image variant. Accuracy first, so this can take a while.", style = MaterialTheme.typography.bodyLarge)
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator({ (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(progress?.phase ?: status ?: "Working…", style = MaterialTheme.typography.titleMedium)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

@Composable
fun PhotoResultsScreen(
    state: ResultState?,
    noResult: RecoveryOutput?,
    actions: ResultActions,
    onManual: () -> Unit,
    onRetry: () -> Unit,
    diagnosticLog: (Boolean) -> String? = { null },
    reportCount: Int = 0,
    takeReport: () -> TrainingReportStore.Export? = { null },
    reportPhotos: Int = 0,
    reportPhoto: android.graphics.Bitmap? = null,
    /** A USPS number copied from ups.com, accepted by the user. */
    onUseUspsNumber: (com.labelrescue.core.usps.ParsedNumber) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null) {
            Text("No tracking number found.", style = MaterialTheme.typography.titleLarge)
            noResult?.notes?.forEach { Text(it) }
            noResult?.let { UpsNumberCard(it.ups, uspsConfidence = 0.0, state = null, onUse = onUseUspsNumber) }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Crop and try again") }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Type the digits I can read") }
            TrainingReportCard(reportCount, reportPhotos, reportPhoto, takeReport)
            DiagnosticsCard(diagnosticLog)
            return@Column
        }
        state.photo?.output?.let { EvidenceSummary(it) }
        state.photo?.output?.let { out ->
            // How sure the USPS number is: a full decode is certain; otherwise the top candidate's share.
            val uspsConf = if (out.fullDecode != null) 1.0 else state.ranked?.best?.confidence
            UpsNumberCard(out.ups, uspsConf, state, onUseUspsNumber)
        }
        ResultsPanel(state, actions, crops = state.photo?.crops.orEmpty())
        TrainingReportCard(reportCount, reportPhotos, reportPhoto, takeReport)
        DiagnosticsCard(diagnosticLog)
    }
}

/**
 * The training report: a short entry per scan (what the app read, per digit) plus, optionally, the
 * photo with names and addresses whited out. Copying or sharing it clears it.
 */
@Composable
private fun TrainingReportCard(count: Int, photoCount: Int, preview: android.graphics.Bitmap?, take: () -> TrainingReportStore.Export?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var withPhotos by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Training report · $count scan${if (count == 1) "" else "s"} · $photoCount photo${if (photoCount == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
            Text(
                "One short entry per scan: what the app read and why, digit by digit. Photos have every line of text " +
                    "whited out except the tracking number and label wording (service, \"Mailed from\", dates). " +
                    "Check the photo below before you send it. Everything clears after you copy or share.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (preview != null && withPhotos) {
                androidx.compose.foundation.Image(
                    preview.asImageBitmap(), "Latest report photo, names and addresses whited out",
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            if (photoCount > 0) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(withPhotos, { withPhotos = it })
                    Text("Include photos", style = MaterialTheme.typography.bodyMedium)
                }
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    take()?.let { ex ->
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Label Rescue training report", ex.text.take(LOG_LIMIT)))
                        val saved = if (withPhotos) savePhotosToGallery(ctx, ex.photos) else 0
                        android.widget.Toast.makeText(
                            ctx,
                            "Training report copied" + (if (saved > 0) "; $saved photo${if (saved == 1) "" else "s"} saved to Pictures/Label Rescue" else "") + ". Cleared.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }, enabled = count > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Copy & clear") }
                OutlinedButton(onClick = {
                    take()?.let { ex -> shareReport(ctx, ex.text.take(LOG_LIMIT), if (withPhotos) ex.photos else emptyList()) }
                }, enabled = count > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("Share & clear")
                }
            }
        }
    }
}

/** Shares the report text with its photos attached (through the app's FileProvider). */
private fun shareReport(ctx: android.content.Context, text: String, photos: List<java.io.File>) {
    val uris = ArrayList(photos.map { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".share", it) })
    val i = if (uris.isEmpty()) {
        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    i.putExtra(android.content.Intent.EXTRA_TEXT, text)
    if (uris.isNotEmpty()) {
        i.clipData = android.content.ClipData.newRawUri("photo", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    }
    ctx.startActivity(android.content.Intent.createChooser(i, "Share training report"))
}

/** Copies the (already redacted) photos to Pictures/Label Rescue so they can be attached anywhere. Android 10+. */
private fun savePhotosToGallery(ctx: android.content.Context, photos: List<java.io.File>): Int {
    if (android.os.Build.VERSION.SDK_INT < 29) return 0
    var n = 0
    for (f in photos) runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Label Rescue")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        n++
    }
    return n
}

/** Show "Find USPS tracking on UPS.com" when the UPS number is above this confidence ... */
private const val UPS_MIN_CONFIDENCE = 0.89
/** ... and the USPS number is below this one. */
private const val USPS_MAX_CONFIDENCE = 0.76

/** Close to UPS's brown and gold, not their exact trade dress. */
private val UpsBrown = androidx.compose.ui.graphics.Color(0xFF3B2016)
private val UpsGold = androidx.compose.ui.graphics.Color(0xFFFFB71B)

/**
 * The UPS 1Z number on the label, read offline (barcode or printed text, check digit valid).
 * When it is clearly readable and the USPS number isn't, a button opens ups.com's tracking page
 * for it in the browser (the app itself has no network access). Copy the USPS number there and
 * come back: the app finds it on the clipboard, checks it against the label, and offers to use it.
 */
@Composable
private fun UpsNumberCard(
    ups: List<com.labelrescue.core.ups.UpsNumber>,
    uspsConfidence: Double?,
    state: ResultState?,
    onUse: (com.labelrescue.core.usps.ParsedNumber) -> Unit,
) {
    val u = ups.firstOrNull { !it.corrected } ?: ups.firstOrNull() ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var launched by androidx.compose.runtime.saveable.rememberSaveable(u.tracking) { androidx.compose.runtime.mutableStateOf(false) }
    var pasted by androidx.compose.runtime.remember(u.tracking) { androidx.compose.runtime.mutableStateOf<com.labelrescue.core.usps.ParsedNumber?>(null) }
    var noNumber by androidx.compose.runtime.remember(u.tracking) { androidx.compose.runtime.mutableStateOf(false) }
    var used by androidx.compose.runtime.remember(u.tracking) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val showButton = u.confidence > UPS_MIN_CONFIDENCE && uspsConfidence != null && uspsConfidence < USPS_MAX_CONFIDENCE

    fun readClipboard() {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull().orEmpty()
        val n = com.labelrescue.core.usps.LabelFit.findUsps(text)
        if (n != null && n.tracking != used) { pasted = n; noNumber = false } else if (n == null) noNumber = true
    }
    // Back from the browser: look for the copied USPS number. (Android only lets an app read the
    // clipboard while it's in front, so this runs on return, shortly after the window has focus.)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, launched) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && launched) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ readClipboard() }, 400)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UPS number on the label", style = MaterialTheme.typography.titleMedium)
            Text(u.grouped, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Text(
                "Read from the " + u.source + (if (u.corrected) " (one character corrected)" else "") +
                    " · ${(u.confidence * 100).toInt()}% sure" +
                    (if (u.isUspsHandoff) ". UPS Ground Saver / SurePost: delivered by USPS." else "."),
                style = MaterialTheme.typography.bodySmall,
            )
            if (showButton) {
                FindOnUpsButton {
                    launched = true; pasted = null; noNumber = false
                    val url = "https://www.ups.com/track?loc=en_US&tracknum=" + u.tracking
                    openWeb(ctx, url)
                }
                Text(
                    "Opens UPS's tracking page over the app. Long-press the USPS tracking number there, tap Copy, then press back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val p = pasted
            if (p != null) {
                val fit = state?.let { com.labelrescue.core.usps.LabelFit.check(p, it.format, it.baseSlots) }
                Text("Copied from UPS.com:", style = MaterialTheme.typography.labelLarge)
                Text(com.labelrescue.core.usps.TrackingFormat.grouped(p.tracking), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        fit == null -> "Valid USPS number (check digit OK). The label's own USPS number wasn't readable, so it can't be compared."
                        !fit.sameFormat -> "Valid USPS number, but a different length than the label's (${p.tracking.length} vs ${state.format.trackingLength} digits). Check you copied the right number."
                        fit.conflicts.isNotEmpty() -> "Doesn't match the label: ${fit.conflicts.size} digit(s) the label shows clearly are different (positions ${fit.conflicts.joinToString { (it + 1).toString() }}). Check you copied the right number."
                        else -> "Fits the label: no clearly printed digit disagrees, check digit OK."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fit != null && !fit.fits) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUse(p); used = p.tracking; pasted = null }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Use this number") }
                    OutlinedButton(onClick = { pasted = null }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Not this") }
                }
            } else if (launched) {
                if (noNumber) Text("No USPS number on the clipboard yet.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { readClipboard() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Paste USPS number") }
            }
            used?.let { Text("Using ${com.labelrescue.core.usps.TrackingFormat.grouped(it)} from UPS.com.", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { copy(ctx, u.tracking) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy UPS number") }
        }
    }
}

/** "Find USPS tracking on UPS.com": brown with gold lettering, gently pulsing, with a sweeping shine. */
@Composable
private fun FindOnUpsButton(onClick: () -> Unit) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ups")
    val pulse by t.animateFloat(
        1f, 1.035f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val shine by t.animateFloat(
        -0.3f, 1.3f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shine",
    )
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(UpsBrown)
            .drawWithContent {
                drawContent()
                val x = size.width * shine
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent, UpsGold.copy(alpha = 0.35f), androidx.compose.ui.graphics.Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(x - 120f, 0f), end = androidx.compose.ui.geometry.Offset(x + 120f, size.height),
                    ),
                )
            }
            .border(2.dp, UpsGold.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text("Find USPS tracking on UPS.com", color = UpsGold, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

/** Clipboard and share intents have size limits (the Binder buffer is about 1 MB). */
private const val LOG_LIMIT = 400_000

/** Copy or share the detailed log (for development). Names and addresses hidden by default. */
@Composable
private fun DiagnosticsCard(diagnosticLog: (Boolean) -> String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var redact by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostic log", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything the app read and decided for this photo: OCR reads with per-character confidence, barcode measurements and layouts, the per-digit evidence and the ranked candidates. For troubleshooting; nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(redact, { redact = it })
                Text("Hide names and addresses", style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    diagnosticLog(redact)?.let {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Label Rescue diagnostic log", it.take(LOG_LIMIT)))
                        android.widget.Toast.makeText(ctx, "Diagnostic log copied (${it.length / 1024} KB)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Copy log") }
                OutlinedButton(onClick = { diagnosticLog(redact)?.let { share(ctx, it.take(LOG_LIMIT)) } }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Share log") }
            }
        }
    }
}

@Composable
private fun EvidenceSummary(out: RecoveryOutput) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val full = out.fullDecode
            if (full != null) {
                Text("Read directly from the ${if (out.fullDecodeBy?.contains("data matrix") == true) "2D code" else "barcode"}", style = MaterialTheme.typography.titleMedium)
                Text("Check digit valid · ${out.fullDecodeBy}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Recovered from: " + out.perSource.keys.joinToString(" + ") { it.label }, style = MaterialTheme.typography.titleMedium)
                val rec = out.recovered.filter { it.rawConfidence < 0.9 }
                if (rec.isNotEmpty()) {
                    Text(
                        "Barcode checksum filled in digit " + rec.joinToString(", ") { "${it.position + 1} = ${it.digit} (${formatPct(it.confidence * 100)})" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (Source.BARCODE_PARTIAL !in out.perSource) Text("No usable barcode signal; using the printed digits.", style = MaterialTheme.typography.bodySmall)
            }
            out.zip?.let { Text("Destination ZIP from barcode: $it", style = MaterialTheme.typography.bodySmall) }
            val hints = out.serviceHints
            if (hints.isNotEmpty()) Text("Label says: " + hints.joinToString { it.service }, style = MaterialTheme.typography.bodySmall)
            val s = out.stats
            Text(
                "${s.frames} frames · ${s.variants} image variants · ${s.ocrReads} OCR reads · ${s.barcodeMeasurements} barcode scans · ${s.millis / 1000.0}s",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
