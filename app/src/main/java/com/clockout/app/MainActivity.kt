package com.clockout.app

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        Store.load(this)
        Store.clearOldDays(this)
        Reminder.ensureChannel(this)
        // Get the phone's AI model downloaded early, so it's ready for the first photo.
        lifecycleScope.launch { AiReader.prepare() }
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private val MEANINGS = mapOf(
    "BT" to "Start of day", "MV" to "Job change", "OL" to "Out to lunch", "IL" to "In from lunch",
    "ET" to "End of day", "OB" to "Out on break", "IB" to "In from break",
)

@Composable
private fun Screen() {
    val context = LocalContext.current
    val rings by Store.rings.collectAsState()
    val s by Store.settings.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now().withNano(0)) }
    var reading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var rawText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Ring?>(null) }
    var adding by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Scan?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            val before = now.toLocalDate()
            now = LocalDateTime.now().withNano(0)
            if (now.toLocalDate() != before && Store.clearOldDays(context)) {
                scan = null
                rawText = ""
                message = "New day – yesterday's rings were cleared."
            }
            delay(1000)
        }
    }

    fun readImage(vararg uris: Uri) {
        reading = true
        message = null
        scan = null
        val photo = thumbnail(context, uris.first())
        scope.launch {
            val r = runCatching { Ocr.read(context, uris.toList(), Store.settings.value) }
            reading = false
            r.onSuccess { res ->
                rawText = res.raw
                val ignore = Store.settings.value.ignoreCodes
                scan = Scan(res.rings.filter { it.code.uppercase() !in ignore }, res.raw, res.decimal, res.switched, photo)
            }.onFailure {
                Crash.note(context, it)
                scan = Scan(emptyList(), "", Store.settings.value.decimal, false, photo, error = "${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    // Two pictures from different angles: glare and the screen's pixel pattern land differently, so readings that
    // agree across both are much more likely right.
    val photoFiles = remember { File(context.cacheDir, "photos").apply { mkdirs() }.let { listOf(File(it, "rings.jpg"), File(it, "rings2.jpg")) } }
    val photoUris = remember { photoFiles.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) } }
    var launchedAt by remember { mutableStateOf(0L) }
    var shot by remember { mutableStateOf(0) }          // which picture the camera is taking (0 = first, 1 = second)
    var askSecond by remember { mutableStateOf(false) }  // first picture taken: ask for the second
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // Some camera apps save the picture but don't say so: use it if it's fresh.
        val f = photoFiles[shot]
        val fresh = f.exists() && f.length() > 0 && f.lastModified() >= launchedAt - 2_000
        when {
            !(ok || fresh) && shot == 0 -> message = "No picture taken."
            !(ok || fresh) -> readImage(photoUris[0])   // skipped the second: read the first on its own
            shot == 0 -> askSecond = true
            else -> readImage(photoUris[0], photoUris[1])
        }
    }
    fun launchShot(i: Int) {
        shot = i
        photoFiles[i].delete()
        launchedAt = System.currentTimeMillis()
        camera.launch(photoUris[i])
    }
    fun takePicture() = launchShot(0)
    if (askSecond) AlertDialog(
        onDismissRequest = { askSecond = false; readImage(photoUris[0]) },
        title = { Text("Now one more") },
        text = { Text("Take a second picture of the same rings from a slightly different angle (tilt or step to the side). Reading both makes mistakes much less likely.") },
        confirmButton = { Button(onClick = { askSecond = false; launchShot(1) }) { Text("📷  Second picture") } },
        dismissButton = { TextButton(onClick = { askSecond = false; readImage(photoUris[0]) }) { Text("Use just the first") } },
    )
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) readImage(uri) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminder.update(context) }
    // Puts the "Clock out at …" notification back if it's missing (e.g. after the phone restarted the app).
    LaunchedEffect(Unit) { Reminder.update(context) }
    LaunchedEffect(s.remind) {
        if (s.remind && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Clock Out", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        CrashCard()

        ResultCard(rings, s, now)
        if (rings.isNotEmpty()) Text(
            "Clears itself at midnight.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { takePicture() }, enabled = !reading, modifier = Modifier.weight(1f)) { Text("📷  Scan clock rings") }
            OutlinedButton(onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !reading) { Text("From photos") }
        }
        if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reading…")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        // The rings, oldest first.
        Text("Today's rings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (rings.isEmpty()) {
            Text("Take a picture of your clock rings, or add them by hand.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rings.forEachIndexed { i, r ->
            val next = rings.getOrNull(i + 1)?.time
            val off = Calc.isOff(r.code, s)
            Card(
                Modifier.fillMaxWidth().clickable { editing = r },
                colors = CardDefaults.cardColors(
                    containerColor = if (off) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.code, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.show(r.time, s), fontSize = 18.sp)
                        Text(
                            (MEANINGS[r.code.uppercase()] ?: if (r.code == "?") "Unknown code – tap to fix" else "Code ${r.code}") +
                                if (off) " · off the clock" else " · on the clock",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val span = Duration.between(r.time, next ?: if (off) r.time else now)
                    if (next != null || !off) Text(
                        Fmt.dur(span) + if (next == null) " so far" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("+ Add a ring") }
            if (rings.isNotEmpty() || rawText.isNotBlank()) TextButton(onClick = {
                Store.setRings(context, emptyList())
                Store.clearPhotos(context)
                rawText = ""
                scan = null
                message = "Cleared. Ready for a new picture."
            }) { Text("Clear picture & rings") }
        }

        HorizontalDivider()
        SettingsPanel(s)

        if (rawText.isNotBlank()) {
            var show by remember { mutableStateOf(false) }
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide what the camera read" else "Show what the camera read") }
            if (show) Text(rawText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(32.dp))
    }

    scan?.let { sc ->
        ScanDialog(
            sc, s, now,
            onUse = {
                if (sc.decimal != s.decimal) Store.setSettings(context, s.copy(decimal = sc.decimal))
                Store.setRings(context, sc.rings)
                message = null
                scan = null
            },
            onRetake = { scan = null; takePicture() },
            onDismiss = { scan = null },
        )
    }

    editing?.let { r ->
        RingDialog(
            title = "Edit ring", initial = r, s = s,
            onDismiss = { editing = null },
            onDelete = { Store.setRings(context, rings - r); editing = null },
            onSave = { new -> Store.setRings(context, rings - r + new); editing = null },
        )
    }
    if (adding) {
        val last = rings.lastOrNull()
        RingDialog(
            title = "Add a ring", s = s,
            initial = Ring(if (last == null) "BT" else if (Calc.isOff(last.code, s)) "IL" else "OL", now.withNano(0)),
            onDismiss = { adding = false },
            onDelete = null,
            onSave = { new -> Store.setRings(context, rings + new); adding = false },
        )
    }
}

@Composable
private fun ResultCard(rings: List<Ring>, s: Settings, now: LocalDateTime) {
    val r = Calc.compute(rings, s, now)
    val target = Duration.ofSeconds(s.targetSeconds)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                r == null -> {
                    Text("Scan your clock rings to see when to clock out.", fontSize = 18.sp)
                }
                r.finished -> {
                    Text("Day finished", style = MaterialTheme.typography.titleMedium)
                    Text(Fmt.dur(r.worked), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("on the clock today (${if (r.worked >= target) "${Fmt.dur(r.worked - target)} over" else "${Fmt.dur(target - r.worked)} short"})")
                }
                r.onClock && r.clockOut != null -> {
                    val reached = r.remaining.isZero
                    Text(if (reached) "You reached ${Fmt.dur(target)} at" else "Clock out at", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.decimal) Fmt.decimal(r.clockOut.toLocalTime()) else Fmt.time(r.clockOut),
                        fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    )
                    if (s.decimal) Text(Fmt.time(r.clockOut), fontSize = 18.sp)
                    Text(
                        if (reached) "You're ${Fmt.dur(r.worked - target)} over – go home!"
                        else "${Fmt.dur(r.remaining)} to go · ${Fmt.dur(r.worked)} worked",
                        fontSize = 18.sp,
                    )
                }
                else -> {
                    // Off the clock (lunch): the clock-out time depends on when you come back.
                    Text("Off the clock", style = MaterialTheme.typography.titleMedium)
                    Text("${Fmt.dur(r.remaining)} left to work", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("${Fmt.dur(r.worked)} worked so far.")
                    Text("Clock back in now → clock out at ${Fmt.show(now + r.remaining, s)}", fontSize = 18.sp)
                }
            }
            if (r != null) {
                val f = (r.worked.seconds.toFloat() / target.seconds.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun SettingsPanel(s: Settings) {
    val context = LocalContext.current
    fun set(new: Settings) = Store.setSettings(context, new)
    Text("Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

    Text("Times on my clock screen look like")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !s.decimal, onClick = { set(s.copy(decimal = false)) }, label = { Text("2:41:06 PM (12-hour)") })
        FilterChip(selected = s.decimal, onClick = { set(s.copy(decimal = true)) }, label = { Text("14.68 (military + decimal)") })
    }
    if (s.decimal) Text("14.68 means 14 hours and 0.68 of an hour = 2:40:48 PM.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    var targetText by remember { mutableStateOf(Fmt.dur(Duration.ofSeconds(s.targetSeconds)).removeSuffix(":00")) }
    OutlinedTextField(
        value = targetText,
        onValueChange = { t ->
            targetText = t
            val p = t.trim().split(":", ".")
            val h = p.getOrNull(0)?.toIntOrNull()
            val m = p.getOrNull(1)?.toIntOrNull() ?: 0
            val sec = p.getOrNull(2)?.toIntOrNull() ?: 0
            if (h != null && h in 0..23 && m in 0..59 && sec in 0..59) set(s.copy(targetSeconds = h * 3600L + m * 60L + sec))
        },
        label = { Text("Time on the clock (hours:minutes)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    var offText by remember { mutableStateOf(s.offCodes.sorted().joinToString(", ")) }
    OutlinedTextField(
        value = offText,
        onValueChange = { t ->
            offText = t
            set(s.copy(offCodes = t.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()))
        },
        label = { Text("Codes that take you OFF the clock") },
        supportingText = { Text("Time after these rings isn't counted (OL = out to lunch, ET = end). Every other code counts as working.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    var ignoreText by remember { mutableStateOf(s.ignoreCodes.sorted().joinToString(", ")) }
    OutlinedTextField(
        value = ignoreText,
        onValueChange = { t ->
            ignoreText = t
            set(s.copy(ignoreCodes = t.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()))
        },
        label = { Text("Codes to ignore") },
        supportingText = { Text("Left out completely, e.g. MV (job moves) – they don't change whether you're on the clock.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(Modifier.fillMaxWidth().clickable { set(s.copy(remind = !s.remind)) }, verticalAlignment = Alignment.CenterVertically) {
        Text("Remind me when it's time", Modifier.weight(1f))
        Switch(s.remind, { set(s.copy(remind = it)) })
    }
    if (s.remind) {
        Text(if (s.remindBefore == 0) "Only at clock-out time" else "Also ${s.remindBefore} min before")
        Slider(
            value = s.remindBefore.toFloat(),
            onValueChange = { set(s.copy(remindBefore = it.roundToInt())) },
            valueRange = 0f..30f,
            steps = 29,
        )
    }
}

@Composable
private fun RingDialog(title: String, initial: Ring, s: Settings, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Ring) -> Unit) {
    var code by remember { mutableStateOf(initial.code) }
    var time by remember { mutableStateOf(if (s.decimal) Fmt.decimal(initial.time.toLocalTime()) else Fmt.time(initial.time)) }
    val parsed = Fmt.parseAny(time, s)
    val example = if (s.decimal) "14.68" else "2:41:06 PM"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    code, { code = it.uppercase().take(4) }, label = { Text("Code (BT, MV, OL, IL, ET…)") }, singleLine = true,
                )
                OutlinedTextField(
                    time, { time = it }, label = { Text("Time, e.g. $example") }, singleLine = true,
                    isError = parsed == null,
                    supportingText = { Text(if (parsed == null) "Type it like $example" else Fmt.time(parsed)) },
                )
                Text(initial.time.toLocalDate().toString(), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onSave(Ring(code.trim().ifEmpty { "?" }, initial.time.toLocalDate().atTime(it))) } },
                enabled = parsed != null,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** What one scan found, waiting for you to confirm. */
private class Scan(
    val rings: List<Ring>,
    val raw: String,
    /** The time style the rings were read in. */
    val decimal: Boolean,
    /** Read in the other style than the one chosen in Settings. */
    val switched: Boolean,
    val photo: ImageBitmap?,
    val error: String? = null,
)

private fun thumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        val scale = (maxOf(info.size.width, info.size.height) / 900).coerceAtLeast(1)
        d.setTargetSize((info.size.width / scale).coerceAtLeast(1), (info.size.height / scale).coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}.getOrNull()

/** Shows what was read and when that means you clock out, before anything is saved. */
@Composable
private fun ScanDialog(sc: Scan, current: Settings, now: LocalDateTime, onUse: () -> Unit, onRetake: () -> Unit, onDismiss: () -> Unit) {
    val s = current.copy(decimal = sc.decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sc.rings.isEmpty()) "Couldn't find your rings" else "Here's what I read") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sc.photo?.let {
                    Image(
                        it, contentDescription = "Your picture",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (sc.rings.isEmpty()) {
                    Text(sc.error?.let { "Reading failed: $it" } ?: "I didn't see any codes with times (like BT: 08.87, or 013 IL  09-OCT-26 02.41.06 PM) in this picture.")
                    Text("Try again with your rings filling most of the picture, held straight on, without glare.")
                    if (sc.raw.isNotBlank()) {
                        Text("What I could read:", fontWeight = FontWeight.Bold)
                        Text(sc.raw.take(600), style = MaterialTheme.typography.bodySmall)
                    }
                    return@Column
                }
                val days = sc.rings.map { it.time.toLocalDate() }.distinct()
                if (days.any { it != java.time.LocalDate.now() }) {
                    Text(
                        "These rings are dated ${days.joinToString { it.toString() }}, not today – " +
                            "they'll be cleared at the next day change.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (sc.switched) {
                    Text(
                        "These look like ${if (sc.decimal) "military + decimal" else "12-hour"} times, so I read them that way " +
                            "(and will switch the setting).",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                sc.rings.forEach { r ->
                    Row {
                        Text(r.code, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                        Text(Fmt.show(r.time, s), Modifier.weight(1f))
                        Text(if (Calc.isOff(r.code, s)) "off" else "on", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val unknown = sc.rings.count { it.code == "?" }
                if (unknown > 0) Text("$unknown time${if (unknown == 1) "" else "s"} without a code – you can fix that after.",
                    color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                val r = Calc.compute(sc.rings, s, now)
                val target = Duration.ofSeconds(s.targetSeconds)
                when {
                    r == null -> Unit
                    r.finished -> Text("Day finished: ${Fmt.dur(r.worked)} on the clock.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    r.onClock && r.clockOut != null -> {
                        Text("You need to clock out at", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (s.decimal) Fmt.decimal(r.clockOut.toLocalTime()) else Fmt.time(r.clockOut),
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        )
                        if (s.decimal) Text(Fmt.time(r.clockOut))
                        Text(
                            if (r.remaining.isZero) "You've already reached ${Fmt.dur(target)}."
                            else "${Fmt.dur(r.remaining)} still to work · ${Fmt.dur(r.worked)} worked so far",
                        )
                    }
                    else -> {
                        Text("You're off the clock right now.", fontWeight = FontWeight.Bold)
                        Text("${Fmt.dur(r.worked)} worked · ${Fmt.dur(r.remaining)} left.")
                        Text("Clock back in now → clock out at ${Fmt.show(now + r.remaining, s)}")
                    }
                }
            }
        },
        confirmButton = {
            if (sc.rings.isNotEmpty()) TextButton(onClick = onUse) { Text("Looks right – use these") }
            else TextButton(onClick = onRetake) { Text("Try again") }
        },
        dismissButton = {
            Row {
                if (sc.rings.isNotEmpty()) TextButton(onClick = onRetake) { Text("Retake") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Shows what went wrong last time the app crashed, so it can be fixed. */
@Composable
private fun CrashCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(Crash.last(context)) }
    val text = report ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Clock Out hit a problem last time", fontWeight = FontWeight.Bold)
            Text("Screenshot this and send it to me so it can be fixed:", style = MaterialTheme.typography.bodySmall)
            Text(text.lines().take(14).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Crash.clear(context); report = null }) { Text("Dismiss") }
        }
    }
}
