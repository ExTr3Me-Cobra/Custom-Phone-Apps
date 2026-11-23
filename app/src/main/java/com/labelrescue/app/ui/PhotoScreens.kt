package com.labelrescue.app.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
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
fun ProcessingScreen(progress: Progress?, status: String?, onCancel: () -> Unit, live: List<String> = emptyList()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator({ (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(progress?.phase ?: status ?: "Working…", style = MaterialTheme.typography.titleMedium)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // Everything the pipeline reads and decides, scrolling by as it happens.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(live.size, live.lastOrNull()) { if (live.isNotEmpty()) listState.scrollToItem(live.lastIndex) }
        androidx.compose.foundation.layout.Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF0B0F14), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(8.dp),
        ) {
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(live.size) { i ->
                    val l = live[i]
                    val color = when {
                        "FULL DECODE" in l || "early stop" in l -> androidx.compose.ui.graphics.Color(0xFF7CFC9A)
                        "=====" in l || "checkpoint" in l || "adaptive" in l -> androidx.compose.ui.graphics.Color(0xFF7FD4FF)
                        "barcode" in l || "420+" in l || "layout" in l -> androidx.compose.ui.graphics.Color(0xFFFFD27F)
                        else -> androidx.compose.ui.graphics.Color(0xFFB8C4CF)
                    }
                    Text(
                        l, color = color, maxLines = 2,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
            }
        }
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
    reportCount: Int = 0,
    onExportTraining: () -> Unit = {},
    reportPhotos: Int = 0,
    reportPhoto: android.graphics.Bitmap? = null,
    /** A USPS number copied from ups.com, accepted by the user. */
    onUseUspsNumber: (com.labelrescue.core.usps.ParsedNumber) -> Unit = {},
    /** The 1Z number read from a photo of the UPS sticker, if one was taken. */
    stickerUps: com.labelrescue.core.ups.UpsNumber? = null,
    /** The sticker question was already answered for this scan. */
    stickerAsked: Boolean = true,
    stickerBusy: Boolean = false,
    onStickerNo: () -> Unit = {},
    onStickerPhoto: () -> Unit = {},
    /** User mode: the number, USPS.com, the candidates, UPS when needed, and digit picking. Nothing else. */
    simple: Boolean = false,
) {
    // Both numbers unsure: ask for the UPS sticker, which has the 1Z number printed large.
    val out = state?.photo?.output ?: noResult
    val uspsConfNow = when {
        state == null -> 0.0
        state.ranking -> null
        out?.fullDecode != null -> 1.0
        else -> state.ranked?.best?.confidence ?: 0.0
    }
    val upsBest = out?.ups?.maxOfOrNull { it.confidence }
    if (!stickerAsked && stickerUps == null && !stickerBusy && upsBest != null && upsBest < STICKER_BELOW &&
        uspsConfNow != null && uspsConfNow < STICKER_BELOW
    ) {
        UpsStickerDialog(onNo = onStickerNo, onYes = onStickerPhoto)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == null) {
            Text("No tracking number found.", style = MaterialTheme.typography.titleLarge)
            if (!simple) noResult?.notes?.forEach { Text(it) }
            if (stickerBusy) StickerBusyCard()
            noResult?.let { UpsNumberCard(listOfNotNull(stickerUps) + it.ups, uspsConfidence = 0.0, state = null, onUse = onUseUspsNumber, simple = simple) }
            if (simple) {
                Text("Try again with the label filling the corner marks, in good light.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Crop and try again") }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Type the digits I can read") }
            TrainingReportCard(reportCount, reportPhotos, reportPhoto, onExportTraining)
            return@Column
        }
        // (How it was read, frame counts and timing are in the diagnostic log, not on screen.)
        state.photo?.output?.let { out ->
            // How sure the USPS number is: a full decode is certain; otherwise the top candidate's share.
            val uspsConf = if (out.fullDecode != null) 1.0 else state.ranked?.best?.confidence
            if (stickerBusy) StickerBusyCard()
            UpsNumberCard(listOfNotNull(stickerUps) + out.ups, uspsConf, state, onUseUspsNumber, simple = simple)
        }
        ResultsPanel(state, actions, crops = state.photo?.crops.orEmpty(), simple = simple)
        if (!simple) TrainingReportCard(reportCount, reportPhotos, reportPhoto, onExportTraining)
    }
}

/**
 * The training report: a short entry per scan (what the app read, per digit) plus the photo with
 * nothing whited out (training data isn't redacted). One button exports
 * everything for training to Downloads (see Settings for the rest).
 */
@Composable
private fun TrainingReportCard(count: Int, photoCount: Int, preview: android.graphics.Bitmap?, onExport: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Training report · $count scan${if (count == 1) "" else "s"} · $photoCount photo${if (photoCount == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
            Text(
                "One short entry per scan, and its photo as taken (not redacted: it shows names and addresses). " +
                    "Check the photo below. Export saves everything for training as one ZIP in Downloads; the report then clears.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (preview != null) {
                androidx.compose.foundation.Image(
                    preview.asImageBitmap(), "Latest report photo",
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            Button(onClick = onExport, enabled = count > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export training data") }
        }
    }
}

/** Ask for the UPS sticker when both the UPS and the USPS number are below this. */
private const val STICKER_BELOW = 0.96

/** Large popup: "Is there a sticker like this?" with the example sticker. */
@Composable
private fun UpsStickerDialog(onNo: () -> Unit, onYes: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        title = { Text("Is there a sticker like this on the package?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.labelrescue.app.R.drawable.ups_sticker_example),
                    "Example UPS sticker with the 1Z tracking number",
                    Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Text(
                    "Neither number on the label is clear enough. Most UPS packages carry this small sticker with the UPS " +
                        "tracking number (1Z…) printed on it. If you see one, take a photo of just the sticker.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            Button(onClick = onYes, modifier = Modifier.heightIn(min = 56.dp)) { Text("Yes, take a photo", fontSize = 18.sp) }
        },
        dismissButton = {
            OutlinedButton(onClick = onNo, modifier = Modifier.heightIn(min = 56.dp)) { Text("No", fontSize = 18.sp) }
        },
    )
}

@Composable
private fun StickerBusyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reading the UPS sticker…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
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
    /** User mode: only the "Find on UPS.com" button (and pasting what's copied there), when it applies. */
    simple: Boolean = false,
) {
    // Only numbers read as-is (barcode, MaxiCode, or printed text passing the check digit): a
    // number that needed a correction is too likely to be made up to show at all.
    val u = ups.filter { !it.corrected }.maxByOrNull { it.confidence } ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var launched by androidx.compose.runtime.saveable.rememberSaveable(u.tracking) { androidx.compose.runtime.mutableStateOf(false) }
    val showButton = u.confidence > UPS_MIN_CONFIDENCE && uspsConfidence != null && uspsConfidence < USPS_MAX_CONFIDENCE
    if (simple) {
        if (!showButton) return
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FindOnUpsButton {
                launched = true
                openWeb(ctx, "https://www.ups.com/track?loc=en_US&tracknum=" + u.tracking)
            }
            Text("Long-press the USPS tracking number there, tap Copy, then press back.", style = MaterialTheme.typography.bodySmall)
            ClipboardPopup(state, listenAfterReturn = launched, onUse = onUse)
        }
        return
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UPS number on the label", style = MaterialTheme.typography.titleMedium)
            Text(u.grouped, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Text(
                "Read from the " + u.source + " · ${(u.confidence * 100).toInt()}% sure" +
                    (if (u.isUspsHandoff) ". UPS Ground Saver / SurePost: delivered by USPS." else "."),
                style = MaterialTheme.typography.bodySmall,
            )
            if (showButton) {
                FindOnUpsButton {
                    launched = true
                    openWeb(ctx, "https://www.ups.com/track?loc=en_US&tracknum=" + u.tracking)
                }
                Text(
                    "Opens UPS's tracking page over the app. Long-press the USPS tracking number there, tap Copy, then press back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PasteNumberPanel(state, listenAfterReturn = launched, onUse = onUse)
            }
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
