package com.mammyframe.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private val Ink2 = Color(0xFF4A3A3F)

/** A few words for a clockwise turn. */
fun turnText(deg: Int) = when (((deg % 360) + 360) % 360) {
    90 -> "turn right"
    180 -> "turn upside down"
    270 -> "turn left"
    else -> "leave as it is"
}

/** One line for the home screen card. */
fun aiSummary(status: AiRotate.Status, recs: List<AiRotate.Rec>): String {
    if (status.running) return "Checking\n${status.done} of ${status.total}"
    if (recs.isEmpty()) return "Not checked yet"
    val kinds = recs.map { AiRotate.kind(it, status.threshold) }
    val review = kinds.count { it == AiRotate.Kind.REVIEW }
    return "${recs.size - review} sorted\n$review to review" + if (!status.rotationsOn) " (off)" else ""
}

@Composable
fun RotateScreen(settings: Settings, onBack: () -> Unit, onReview: () -> Unit) {
    val context = LocalContext.current
    val status by AiRotate.status.collectAsState()
    var recs by remember { mutableStateOf<List<AiRotate.Rec>>(emptyList()) }
    LaunchedEffect(status.revision, settings.folderUri) {
        recs = AiRotate.recordsIn(context, settings.folderUri).sortedBy { it.name.lowercase() }
    }
    LaunchedEffect(Unit) { AiRotate.load(context) }

    // The slider shows the effect straight away; files are only changed when you let go.
    var threshold by remember(status.threshold) { mutableFloatStateOf(status.threshold) }
    var showTurned by remember { mutableStateOf(false) }

    val review = recs.filter { AiRotate.kind(it, threshold) == AiRotate.Kind.REVIEW }
    val turned = recs.filter { AiRotate.target(it, threshold) != 0 }
    // Photos you turned in the list below stay there, even if you turn one back to how it was.
    var touched by remember { mutableStateOf(setOf<String>()) }
    val turnedShown = recs.filter { AiRotate.target(it, threshold) != 0 || it.uri in touched }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AiRotate.start(context, settings.folderUri)
    }
    fun startChecking() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else AiRotate.start(context, settings.folderUri)
    }

    val thumbs = remember { LruCache<String, Bitmap>(150) }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFFFF9F2))
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack).padding(12.dp)
                ) { GlyphIcon(Glyph.BACK, Rose, Modifier.fillMaxSize()) }
                Text("AI Rotate", fontFamily = Script, fontSize = 44.sp, color = Rose, modifier = Modifier.padding(start = 4.dp))
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Controls(
                        settings, status, recs.size, threshold,
                        onThreshold = { threshold = it },
                        onThresholdDone = { AiRotate.setThreshold(context, threshold) },
                        onStart = ::startChecking,
                        turnedCount = turned.size, reviewCount = review.size,
                        aiSureCount = recs.count {
                            val k = AiRotate.kind(it, threshold)
                            k == AiRotate.Kind.AUTO || k == AiRotate.Kind.UPRIGHT
                        },
                        reviewedCount = recs.count { AiRotate.kind(it, threshold) == AiRotate.Kind.MANUAL },
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("For review (${review.size})", fontFamily = Script, fontSize = 36.sp, color = Rose)
                        Text(
                            if (review.isEmpty()) "Nothing to look at right now."
                            else "The AI wasn't sure enough about these. Review shows them one at a time, full " +
                                "screen, the way the AI thinks is right (most sure first). Drag round in a circle " +
                                "to turn a photo; Next saves it as shown.",
                            fontSize = 15.sp, color = Ink2.copy(alpha = 0.8f)
                        )
                        if (review.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Show them turned the AI's way", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (status.reviewShowsAi) "Each photo starts the way the AI thinks is right."
                                        else "Each photo starts the way it is now (original).",
                                        fontSize = 14.sp, color = Ink2.copy(alpha = 0.7f)
                                    )
                                }
                                Switch(status.reviewShowsAi, { AiRotate.setReviewShowsAi(context, it) })
                            }
                            Button(onClick = onReview, modifier = Modifier.padding(top = 10.dp)) {
                                Text("Start review (${review.size})", fontSize = 18.sp)
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Turned photos (${turned.size})", fontFamily = Script, fontSize = 36.sp, color = Rose,
                            modifier = Modifier.weight(1f))
                        if (turnedShown.isNotEmpty()) TextButton(onClick = { showTurned = !showTurned }) {
                            Text(if (showTurned) "Hide" else "Show", fontSize = 17.sp)
                        }
                    }
                }
                if (showTurned) items(turnedShown, key = { "t" + it.uri }) { r ->
                    val t = AiRotate.target(r, threshold)
                    PhotoTile(r, current = t, thumbs = thumbs,
                        caption = (if (r.decision >= 0) "You chose: " else "AI (${(r.certainty * 100).roundToInt()}% sure): ") +
                            turnText(t) + if (!status.rotationsOn) " (AI rotations are off)" else "",
                    ) { deg ->
                        touched = touched + r.uri
                        AiRotate.decide(context, listOf(r.uri)) { deg }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    settings: Settings,
    status: AiRotate.Status,
    checked: Int,
    threshold: Float,
    onThreshold: (Float) -> Unit,
    onThresholdDone: () -> Unit,
    onStart: () -> Unit,
    turnedCount: Int,
    reviewCount: Int,
    aiSureCount: Int,
    reviewedCount: Int,
) {
    val context = LocalContext.current
    Surface(color = Color.White.copy(alpha = 0.85f), shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (settings.folderUri == null) {
                Text("Choose a photos folder first (the Photos folder card on the home screen).", fontSize = 17.sp)
                return@Column
            }
            Text(
                "Checks every photo in \"${settings.folderName ?: "the folder"}\". The camera's own rotation " +
                    "is looked at first, then an AI looks at the picture itself. Photos it's sure about " +
                    "are turned and saved straight away; the rest wait below for you.",
                fontSize = 15.sp, color = Ink2.copy(alpha = 0.8f)
            )

            // Live counts: update with every photo checked and every photo you review.
            if (checked > 0 || status.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CountBox(
                        "Sorted", aiSureCount + reviewedCount,
                        "AI was sure: $aiSureCount · you reviewed: $reviewedCount", Color(0xFF3E7D5A), Modifier.weight(1f)
                    )
                    CountBox("Waiting for review", reviewCount, "the AI wasn't sure enough", Rose, Modifier.weight(1f))
                }
            }

            if (status.running) {
                Text("Checking photo ${status.done} of ${status.total}…", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(
                    progress = { if (status.total == 0) 0f else status.done / status.total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("You can leave this screen; it keeps going in the background.", fontSize = 14.sp, color = Ink2.copy(alpha = 0.7f))
                OutlinedButton(onClick = { AiRotate.stop() }) { Text("Stop", fontSize = 17.sp) }
            } else {
                Text(
                    if (checked == 0) "Not checked yet."
                    else "$checked photos checked · $turnedCount turned",
                    fontSize = 18.sp, fontWeight = FontWeight.Medium
                )
                Button(onClick = onStart) {
                    Text(if (checked == 0) "Check all photos" else "Check new photos", fontSize = 17.sp)
                }
            }
            status.message?.let { msg ->
                Surface(color = Color(0xFFFFF1E0), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { AiRotate.clearMessage() }) { Text("OK") }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Turn photos automatically when the AI is at least ${(threshold * 100).roundToInt()}% sure",
                fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Slider(
                value = threshold, onValueChange = onThreshold, onValueChangeFinished = onThresholdDone,
                valueRange = 0.5f..0.99f,
            )
            Text("Higher = fewer mistakes, more photos to review. Lower = fewer to review.",
                fontSize = 14.sp, color = Ink2.copy(alpha = 0.7f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep AI rotations", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (status.rotationsOn) "Turn off to put every photo back exactly as it was."
                        else "Every photo is back to its original. Turn on to turn them again.",
                        fontSize = 14.sp, color = Ink2.copy(alpha = 0.7f)
                    )
                }
                if (status.saving) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 3.dp)
                Switch(status.rotationsOn, { AiRotate.setRotationsOn(context, it) })
            }
        }
    }
}

@Composable
private fun CountBox(title: String, count: Int, detail: String, color: Color, modifier: Modifier) {
    Surface(color = color.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%,d".format(count), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink2, textAlign = TextAlign.Center)
            Text(detail, fontSize = 13.sp, color = Ink2.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PhotoTile(
    r: AiRotate.Rec,
    current: Int,
    thumbs: LruCache<String, Bitmap>,
    caption: String,
    onTurn: (Int) -> Unit,
) {
    val context = LocalContext.current
    // Shown straight away when you tap an arrow; the file is saved in the background.
    var preview by remember(r.uri) { mutableIntStateOf(current) }
    // The picture as it was saved when loaded (photos turned inside the app only aren't changed on disk).
    val onDisk = if (r.method == AiRotate.Method.APP) 0 else r.applied
    val key = "${r.uri}|${r.modified}|${r.size}|$onDisk"
    var shown by remember(r.uri) { mutableStateOf<Pair<Bitmap, Int>?>(thumbs.get(key)?.let { it to onDisk }) }
    LaunchedEffect(key) {
        val cached = thumbs.get(key)
        if (cached != null) shown = cached to onDisk
        else Photos.decode(context, Uri.parse(r.uri), 360)?.let { thumbs.put(key, it); shown = it to onDisk }
    }
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2ECE8)),
                contentAlignment = Alignment.Center
            ) {
                shown?.let { (bmp, savedTurn) ->
                    Image(
                        bmp.asImageBitmap(), contentDescription = r.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().rotate((preview - savedTurn).toFloat())
                    )
                }
            }
            Text(caption, fontSize = 13.sp, color = Ink2, textAlign = TextAlign.Center, lineHeight = 16.sp,
                modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallRound(Glyph.TURN_LEFT) { preview = (preview + 270) % 360; onTurn(preview) }
                SmallRound(Glyph.TURN_RIGHT) { preview = (preview + 90) % 360; onTurn(preview) }
            }
        }
    }
}

@Composable
private fun SmallRound(glyph: Glyph, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Rose.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(9.dp)
    ) { GlyphIcon(glyph, Rose, Modifier.fillMaxSize()) }
}
