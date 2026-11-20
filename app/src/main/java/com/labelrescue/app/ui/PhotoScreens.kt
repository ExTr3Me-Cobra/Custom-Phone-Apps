package com.labelrescue.app.ui

import androidx.compose.ui.graphics.asImageBitmap
import com.labelrescue.app.data.TrainingReportStore
import com.labelrescue.app.verify.UpsController
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
    ups: UpsController.UiState = UpsController.UiState(),
    onUseUspsNumber: (String) -> Unit = {},
    onRetryUps: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null) {
            Text("No tracking number found.", style = MaterialTheme.typography.titleLarge)
            noResult?.notes?.forEach { Text(it) }
            UpsCard(ups, null, onUseUspsNumber, onRetryUps)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Crop and try again") }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Type the digits I can read") }
            TrainingReportCard(reportCount, reportPhotos, reportPhoto, takeReport)
            DiagnosticsCard(diagnosticLog)
            return@Column
        }
        state.photo?.output?.let { EvidenceSummary(it) }
        UpsCard(ups, state, onUseUspsNumber, onRetryUps)
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

/**
 * UPS numbers on the label and what UPS says about them: for packages UPS hands to USPS, the USPS
 * tracking number. Hidden when the label has no 1Z number.
 */
@Composable
private fun UpsCard(ups: UpsController.UiState, state: ResultState?, onUse: (String) -> Unit, onRetry: () -> Unit) {
    if (ups.items.isEmpty()) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ranked = state?.ranked?.candidates.orEmpty()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UPS number on the label", style = MaterialTheme.typography.titleMedium)
            val shown = ups.items.filter { it.status !is UpsController.Status.Skipped }
                .let { l -> if (l.any { it.status is UpsController.Status.Linked }) l.filter { it.status is UpsController.Status.Linked } else l }
            for (item in shown.take(4)) {
                val u = item.ups
                Text(
                    u.grouped + "  ·  " + u.source + (if (u.corrected) " (corrected)" else "") + (if (u.isUspsHandoff) " · Ground Saver / SurePost, delivered by USPS" else ""),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                )
                when (val s = item.status) {
                    is UpsController.Status.Linked -> for (n in s.usps) {
                        val rank = ranked.indexOfFirst { it.tracking == n }
                        Text("USPS number from UPS:", style = MaterialTheme.typography.labelLarge)
                        Text(com.labelrescue.core.usps.TrackingFormat.grouped(n), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            when {
                                state == null -> "The damaged USPS barcode couldn't be read, so this can't be checked against the label."
                                rank == 0 -> "Matches the label's evidence: it is the top candidate."
                                rank > 0 -> "Fits the label's evidence (candidate ${rank + 1})."
                                else -> "Doesn't fit what the damaged USPS barcode and digits show. Check the label before using it."
                            } + (s.upsStatus?.let { " UPS status: $it." } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onUse(n) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Use this number") }
                            OutlinedButton(onClick = {
                                ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("USPS tracking", n))
                                android.widget.Toast.makeText(ctx, "Copied $n", android.widget.Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Copy") }
                        }
                    }
                    is UpsController.Status.NoLink -> Text("UPS knows this package but lists no USPS number for it" + (s.upsStatus?.let { " (status: $it)" } ?: "") + ". USPS may not have scanned it yet.", style = MaterialTheme.typography.bodySmall)
                    is UpsController.Status.NotFound -> Text("UPS has no record of this number" + (s.message?.let { ": $it" } ?: "") + ".", style = MaterialTheme.typography.bodySmall)
                    is UpsController.Status.Failed -> Text("Couldn't ask UPS: ${s.message}", style = MaterialTheme.typography.bodySmall)
                    UpsController.Status.Looking -> Text("Asking UPS for the USPS number…", style = MaterialTheme.typography.bodySmall)
                    UpsController.Status.Waiting -> Text(
                        when {
                            !ups.enabled || !ups.hasCredentials -> "Add your UPS API keys in Settings to look up the USPS number automatically."
                            else -> "Waiting…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    UpsController.Status.Skipped -> {}
                }
            }
            if (!ups.running && ups.items.any { it.status is UpsController.Status.Failed }) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ask UPS again") }
            }
        }
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
