package com.labelrescue.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.labelrescue.app.ResultState
import com.labelrescue.core.candidates.Candidate
import com.labelrescue.core.candidates.SlotDist
import com.labelrescue.core.usps.ImpbParser
import com.labelrescue.core.usps.TrackingFormat
import kotlin.math.roundToInt

/** Actions available on a result. */
class ResultActions(
    val onPick: (Int, Char) -> Unit,
    val onUnpick: (Int) -> Unit,
    val onSelect: (Int) -> Unit,
    val onConfirm: ((String) -> Unit)?,
    /** A USPS number pasted from elsewhere (copied on USPS.com or ups.com): use it as the result. */
    val onUseNumber: ((com.labelrescue.core.usps.ParsedNumber) -> Unit)? = null,
)

/** Certainty of a digit, for coloring. */
enum class DigitState { CERTAIN, LIKELY, GUESSED, PICKED }

fun digitState(state: ResultState, position: Int, c: Char): DigitState {
    if (position in state.overrides) return DigitState.PICKED
    val slot = state.baseSlots[position]
    // Prefer the whole-result probability: a digit that reads cleanly can still be in doubt when
    // the number as a whole is unlikely (check digit, barcode or prior disagree).
    val p = state.marginal(position, c) ?: slot.p(c)
    if (p >= SlotDist.CERTAIN) return DigitState.CERTAIN
    return if (p >= 0.6) DigitState.LIKELY else DigitState.GUESSED
}

@Composable
fun digitColor(s: DigitState): Color {
    val c = LocalDigitColors.current
    return when (s) {
        DigitState.CERTAIN -> MaterialTheme.colorScheme.onSurface
        DigitState.LIKELY -> c.likely
        DigitState.GUESSED -> c.guessed
        DigitState.PICKED -> c.picked
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsPanel(
    state: ResultState,
    actions: ResultActions,
    crops: Map<Int, Bitmap> = emptyMap(),
    /** User mode: the number (digits can still be picked), USPS.com and the candidates. */
    simple: Boolean = false,
) {
    val ctx = LocalContext.current
    val cand = state.current
    var picking by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.ranking) LinearProgressIndicator(Modifier.fillMaxWidth())
        val ranked = state.ranked
        if (cand == null) {
            if (ranked != null && !state.ranking) {
                Text(
                    "No number with a valid check digit fits these digits. Check the digits you typed or tapped.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }
        val format = state.format
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.selected == 0) "Best guess" else "Candidate ${state.selected + 1}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (format.isRouted) {
                    Text(
                        "420 " + cand.number.substring(3, format.routingLength) + "  (routing)",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                BigNumber(state, cand) { picking = it }
                Spacer(Modifier.height(8.dp))
                val pct = (cand.confidence * 100)
                Text(
                    "${formatPct(pct)} likely" + if (simple) "" else (ranked?.let { r ->
                        "  ·  " + if (r.totalValid <= 1.0) "only valid number" else "${formatCount(r.totalValid)} valid numbers"
                    } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!simple) ImpbParser.parse(cand.tracking)?.let { p ->
                    val parts = listOfNotNull(
                        p.channel?.let { "Channel $it" },
                        p.serviceType?.let { "Service $it" },
                        p.mailerId?.let { "Mailer ID $it" },
                        p.zip?.let { "ZIP $it" },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                if (cand.guessed.isNotEmpty() || simple) {
                    Text(if (simple) "Tap any digit to change it if you can read it better." else "Tap a colored digit to check it or pick a different one.", style = MaterialTheme.typography.bodySmall)
                }
                if (!simple) Legend()
            }
        }

        if (simple) actions.onConfirm?.let { confirm ->
            Button(onClick = { confirm(cand.tracking) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(AppIcons.Check, null); Spacer(Modifier.width(8.dp)); Text("This is correct", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (!simple) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { copy(ctx, cand.tracking) }, modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(AppIcons.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
            }
            FilledTonalButton(onClick = { share(ctx, cand.tracking) }, modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(AppIcons.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
            }
            actions.onConfirm?.let { confirm ->
                Button(onClick = { confirm(cand.tracking) }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(AppIcons.Check, null); Spacer(Modifier.width(6.dp)); Text("Mark as confirmed")
                }
            }
        }

        // Uncertain digits with zoomed crops.
        val uncertain = cand.guessed.filter { it >= format.routingLength }.sorted()
        if (uncertain.isNotEmpty() && !simple) {
            Text("Uncertain digits", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pos in uncertain) {
                    UncertainChip(state, cand, pos, crops[pos - format.routingLength]) { picking = pos }
                }
            }
        }

        // Also for a single, certain number: a look at USPS.com confirms it's the right package.
        ranked?.takeIf { it.candidates.isNotEmpty() }?.let { r ->
            var launched by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            CheckOnUspsButton(r.candidates.size) { launched = true; openWeb(ctx, uspsComUrl(r.candidates.map { it.tracking })) }
            if (!simple) actions.onUseNumber?.let { use -> PasteNumberPanel(state, listenAfterReturn = launched, onUse = use) }
        }
        ranked?.let { r ->
            Text(
                if (simple) "Likely numbers, most likely first" else "Top candidates" + if (r.complete) " (all ${r.candidates.size})" else " (${r.candidates.size} of ${formatCount(r.totalValid)})",
                style = MaterialTheme.typography.titleMedium,
            )
            for ((i, c) in r.candidates.take(50).withIndex()) {
                CandidateRow(state, c, i == state.selected) { actions.onSelect(i) }
            }
        }
    }

    picking?.let { pos ->
        DigitPicker(
            state = state, position = pos, crop = crops[pos - state.format.routingLength],
            onPick = { c -> actions.onPick(pos, c); picking = null },
            onClear = { actions.onUnpick(pos); picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun Legend() {
    val c = LocalDigitColors.current
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("sure", MaterialTheme.colorScheme.onSurface)
        LegendItem("likely", c.likely)
        LegendItem("guessed", c.guessed)
        LegendItem("you picked", c.picked)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BigNumber(state: ResultState, cand: Candidate, onTap: (Int) -> Unit) {
    val format = state.format
    val t = cand.tracking
    val groups = TrackingFormat.groups(t.length, format == TrackingFormat.S10)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var i = 0
        for (g in groups) {
            Row {
                for (k in 0 until g) {
                    val pos = format.routingLength + i + k
                    val c = t[i + k]
                    val st = digitState(state, pos, c)
                    val color = digitColor(st)
                    Box(
                        Modifier
                            .widthIn(min = 30.dp).height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .then(if (st != DigitState.CERTAIN) Modifier.background(color.copy(alpha = 0.14f)) else Modifier)
                            .clickable { onTap(pos) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.toString(), fontFamily = FontFamily.Monospace, fontSize = 32.sp,
                            fontWeight = if (st == DigitState.CERTAIN) FontWeight.Normal else FontWeight.Bold,
                            color = color,
                        )
                    }
                }
            }
            i += g
        }
    }
}

@Composable
private fun UncertainChip(state: ResultState, cand: Candidate, pos: Int, crop: Bitmap?, onTap: () -> Unit) {
    val c = cand.number[pos]
    val st = digitState(state, pos, c)
    Card(
        modifier = Modifier.clickable(onClick = onTap),
        border = BorderStroke(2.dp, digitColor(st)),
    ) {
        Column(Modifier.padding(8.dp).width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (crop != null) CropImage(crop, Modifier.size(80.dp))
            else Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) { Text("no photo", style = MaterialTheme.typography.labelSmall) }
            Text("#${pos - state.format.routingLength + 1}: $c", fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = digitColor(st))
            val alts = state.baseSlots[pos].ranked().take(3).joinToString(" ") { "${it.first}" }
            Text(alts, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun CropImage(bmp: Bitmap, modifier: Modifier) {
    Image(
        bitmap = bmp.asImageBitmap(), contentDescription = "Zoomed label crop",
        modifier = modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
    )
}

@Composable
private fun CandidateRow(state: ResultState, c: Candidate, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDigitColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val text: AnnotatedString = buildAnnotatedString {
        val groups = TrackingFormat.groups(c.tracking.length, state.format == TrackingFormat.S10)
        var i = 0
        for ((gi, g) in groups.withIndex()) {
            if (gi > 0) append(' ')
            for (k in 0 until g) {
                val pos = state.format.routingLength + i + k
                val ch = c.number[pos]
                val col = when (digitState(state, pos, ch)) {
                    DigitState.CERTAIN -> onSurface
                    DigitState.LIKELY -> colors.likely
                    DigitState.GUESSED -> colors.guessed
                    DigitState.PICKED -> colors.picked
                }
                withStyle(SpanStyle(color = col, fontWeight = if (col == onSurface) FontWeight.Normal else FontWeight.Bold)) { append(ch) }
            }
            i += g
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(formatPct(c.confidence * 100), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigitPicker(
    state: ResultState,
    position: Int,
    crop: Bitmap?,
    onPick: (Char) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val slot = state.baseSlots[position]
    val probs = slot.ranked().toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digit ${position - state.format.routingLength + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (crop != null) CropImage(crop, Modifier.fillMaxWidth().height(160.dp))
                Text("What does the label show here?", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in slot.alphabet) {
                        val p = probs[c] ?: 0.0
                        if (slot.alphabet == SlotDist.LETTERS && p <= 0.0) continue
                        val selected = state.overrides[position] == c
                        val btnColors = if (selected) androidx.compose.material3.ButtonDefaults.buttonColors()
                        else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                        Button(
                            onClick = { onPick(c) },
                            colors = btnColors,
                            modifier = Modifier.size(width = 64.dp, height = 64.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(c.toString(), fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                                if (p > 0.0) Text(formatPct(p * 100), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (position in state.overrides) ({ TextButton(onClick = onClear) { Text("Undo my pick") } }) else null,
    )
}

fun formatPct(p: Double): String = when {
    p >= 99.95 -> "100%"
    p >= 10 -> "${p.roundToInt()}%"
    p >= 0.1 -> "%.1f%%".format(p)
    else -> "<0.1%"
}

fun formatCount(n: Double): String = when {
    n < 1e6 -> "%,d".format(n.toLong())
    else -> "%.1e".format(n)
}

/**
 * Opens [url] in a Custom Tab (the browser shown over the app: back returns to exactly where you
 * were), or in the browser if no Custom Tabs browser is installed. The app itself has no network
 * access; the browser does the browsing.
 */
fun openWeb(ctx: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, uri)
    } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

/** USPS.com tracking for several numbers at once (its tLabels takes up to 35, comma-separated). */
fun uspsComUrl(numbers: List<String>): String =
    "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + numbers.take(USPS_COM_MAX).joinToString("%2C")

const val USPS_COM_MAX = 35

/** USPS Brand Blue (#004B87) and USPS Digital Red (#E71921), from the USPS.com style guide. */
private val PostalBlue = Color(0xFF004B87)
private val PostalRed = Color(0xFFE71921)

/** "Check all guesses on USPS.com": postal blue, thin red border, white lettering, pulsing with a blue shine. */
@Composable
fun CheckOnUspsButton(count: Int, onClick: () -> Unit) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "usps")
    val pulse by t.animateFloat(
        1f, 1.03f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val shine by t.animateFloat(
        -0.3f, 1.3f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shine",
    )
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(PostalBlue)
            .drawWithContent {
                drawContent()
                val x = size.width * shine
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color.Transparent, Color(0x6680B4FF), Color.Transparent),
                        start = Offset(x - 120f, 0f), end = Offset(x + 120f, size.height),
                    ),
                )
            }
            .border(1.5.dp, PostalRed, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (count == 1) "Check on USPS.com" else "Check all guesses on USPS.com", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (count >= USPS_COM_MAX) Text("top $USPS_COM_MAX, the most USPS.com takes at once", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The first valid USPS number on the clipboard, or null. Only works while the app is in front. */
fun clipboardUsps(ctx: Context): com.labelrescue.core.usps.ParsedNumber? {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull().orEmpty()
    return com.labelrescue.core.usps.LabelFit.findUsps(text)
}

/**
 * Paste a USPS number copied elsewhere (USPS.com, ups.com): a "Paste tracking number" button, and
 * when [listenAfterReturn], an automatic look at the clipboard each time the app comes back to the
 * front. The number is checked (check digit, and against the label's clearly read digits) before
 * it's offered.
 */
@Composable
fun PasteNumberPanel(state: ResultState?, listenAfterReturn: Boolean, onUse: (com.labelrescue.core.usps.ParsedNumber) -> Unit) {
    val ctx = LocalContext.current
    var pasted by remember { mutableStateOf<com.labelrescue.core.usps.ParsedNumber?>(null) }
    var empty by remember { mutableStateOf(false) }
    var used by remember { mutableStateOf<String?>(null) }
    fun look(manual: Boolean) {
        val n = clipboardUsps(ctx)
        when {
            n == null -> if (manual) empty = true
            n.tracking == used -> if (manual) { pasted = n; empty = false }
            else -> { pasted = n; empty = false }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, listenAfterReturn) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            // Shortly after the window has focus: Android only lets the app in front read the clipboard.
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && listenAfterReturn) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ look(manual = false) }, 400)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val p = pasted
    if (p != null) {
        PastedNumberCard(p, state, onUse = { onUse(p); used = p.tracking; pasted = null }, onDismiss = { pasted = null })
    } else {
        OutlinedButton(onClick = { look(manual = true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Paste tracking number")
        }
        if (empty) Text("No USPS tracking number on the clipboard. Long-press the number on the website and tap Copy first.", style = MaterialTheme.typography.bodySmall)
    }
    used?.let { Text("Using ${TrackingFormat.grouped(it)}.", style = MaterialTheme.typography.bodySmall) }
}

/** A pasted USPS number, how it fits the label, and Use / Not this. */
@Composable
fun PastedNumberCard(p: com.labelrescue.core.usps.ParsedNumber, state: ResultState?, onUse: () -> Unit, onDismiss: () -> Unit) {
    val fit = state?.let { com.labelrescue.core.usps.LabelFit.check(p, it.format, it.baseSlots) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Copied tracking number", style = MaterialTheme.typography.labelLarge)
            Text(TrackingFormat.grouped(p.tracking), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    fit == null -> "Valid USPS number (check digit OK). The label's own USPS number wasn't readable, so it can't be compared."
                    !fit.sameFormat -> "Valid USPS number, but a different length than the label's (${p.tracking.length} vs ${state.format.trackingLength} digits). Check you copied the right number."
                    fit.conflicts.isNotEmpty() -> "Doesn't match the label: ${fit.conflicts.size} digit(s) the label shows clearly are different (positions ${fit.conflicts.joinToString { (it + 1).toString() }}). Check you copied the right number."
                    else -> "Fits the label: no clearly printed digit disagrees, check digit OK."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (fit != null && !fit.fits) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUse, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Use this number") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Not this") }
            }
        }
    }
}

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Tracking number", text))
    Toast.makeText(ctx, "Copied $text", Toast.LENGTH_SHORT).show()
}

fun share(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Share tracking number"))
}
