package com.ironlog.app

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The Settings tab: your details, your exercises and every setting. */
@Composable
fun ProfileScreen() = SettingsScreen()

@Composable
fun SettingsScreen() {
    val p by Store.profile.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun set(np: Profile) = Store.setProfile(np)
    var message by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            message = "Saving backup…"
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        val n = context.contentResolver.openOutputStream(uri)!!.use { Store.writeBackup(it) }
                        "Backup saved (with $n of your picture${if (n == 1) "" else "s"})."
                    }.getOrElse { "Couldn't save the backup." }
                }
            }
        }
    }
    // Restore: pick one file, or both auto-save files (A and B) and the newer one is restored.
    fun restoreUris(uris: List<android.net.Uri>) {
        if (uris.isNotEmpty()) {
            message = "Restoring…"
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        val files = uris.map { u -> context.contentResolver.openInputStream(u)!!.use { it.readBytes() } }
                        val bytes = files.maxByOrNull { Store.savedAt(it) ?: 0L }!!
                        val n = Store.readBackup(bytes.inputStream())
                        if (n == -1) {
                            withContext(Dispatchers.Main) { Nav.go(Route.HevyMatch) }
                            return@runCatching "Choose what to import from Hevy…"
                        }
                        if (Store.lastFull) {
                            scope.launch(Dispatchers.Main) { kotlinx.coroutines.delay(1500); Store.restartApp(context) }
                            return@runCatching "Backup restored exactly – restarting Loadout…"
                        }
                        Store.lastImported?.let {
                            "Imported $it workout${if (it == 1) "" else "s"}" + (if (Store.lastHevy) " from Hevy" else "") + " (any already here were skipped)."
                        }
                            ?: ("Backup restored" + if (n > 0) " with $n picture${if (n == 1) "" else "s"}." else ".")
                    }.getOrElse { "That file isn't a Loadout backup." }
                }
            }
        }
    }
    val import = rememberLauncherForActivityResult(AutoSave.OpenBackups()) { uris -> restoreUris(uris) }

    var packMsg by remember { mutableStateOf<String?>(null) }
    var textMsg by remember { mutableStateOf<String?>(null) }
    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) textMsg = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(AppText.export().toString(1).toByteArray()) }
            "Saved ${AppText.count} text change${if (AppText.count == 1) "" else "s"}."
        }.getOrElse { "Couldn't save: ${it.message}" }
    }
    val exportPics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            packMsg = "Exporting…"
            scope.launch {
                packMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val (pics, parts) = Pictures.exportPack(context, uri)
                        "Exported $pics pictures and every exercise's details in $parts zip file${if (parts == 1) "" else "s"}."
                    }.getOrElse { "Export failed: ${it.message}" }
                }
            }
        }
    }
    val importPics = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            packMsg = "Importing…"
            scope.launch {
                packMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { "Imported ${Pictures.importPack(context, uris)} pictures." }.getOrElse { "Import failed: ${it.message}" }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", back = false)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("About you", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Used to rank your exercises against established data from other people with very similar biometrics.", color = Sub, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var bw by remember { mutableStateOf(p.bodyweight?.let { Units.weight(it) } ?: "") }
                OutlinedTextField(bw, { t -> bw = t; set(p.copy(bodyweight = t.toDoubleOrNull()?.let(Units::fromShown))) },
                    Modifier.weight(1f).typing(), label = { Text("Body weight (${Units.w})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                var age by remember { mutableStateOf(p.age?.toString() ?: "") }
                OutlinedTextField(age, { t -> age = t; set(p.copy(age = t.toIntOrNull())) },
                    Modifier.weight(1f).typing(), label = { Text("Age") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                var ft by remember { mutableStateOf(p.heightIn?.let { (it / 12).toString() } ?: "") }
                var inch by remember { mutableStateOf(p.heightIn?.let { (it % 12).toString() } ?: "") }
                fun saveH() { val f = ft.toIntOrNull(); val i = inch.toIntOrNull() ?: 0; set(p.copy(heightIn = f?.let { it * 12 + i })) }
                OutlinedTextField(ft, { ft = it; saveH() }, Modifier.weight(1f).typing(), label = { Text("Height ft") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(inch, { inch = it; saveH() }, Modifier.weight(1f).typing(), label = { Text("in") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(p.male, { set(p.copy(male = true)) }, { Text("Male") })
                FilterChip(!p.male, { set(p.copy(male = false)) }, { Text("Female") })
            }
            Text("Disclaimer: Height doesn't factor into the ranking system.",
                color = Sub, fontSize = 12.sp)
            BodyFatDropdown(p) { set(Store.profile.value.copy(bodyFat = it)) }

            Spacer(10)
            Spacer(6)
            ReferencePanel()
            Spacer(6)
            // App lock: the phone's fingerprint, face or passcode to open Loadout (off by default).
            var lockMsg by remember { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", fontWeight = FontWeight.Bold)
                    Text("Ask for your fingerprint, face or phone passcode when you open Loadout, to keep your data private.", color = Sub, fontSize = 13.sp)
                }
                androidx.compose.material3.Switch(p.appLock, { on ->
                    val a = context as? android.app.Activity
                    if (a == null) lockMsg = "Couldn't change the app lock."
                    else if (on) AppLock.enable(a) { lockMsg = it }
                    else AppLock.prompt(a, "Turn off app lock", { Store.setProfile(Store.profile.value.copy(appLock = false)); lockMsg = null }, { lockMsg = it })
                })
            }
            lockMsg?.let { Text(it, color = Red, fontSize = 13.sp) }
            Spacer(10)
            Text("Units", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!p.kg, { set(p.copy(kg = false)) }, { Text("Pounds (lbs) · miles") })
                FilterChip(p.kg, { set(p.copy(kg = true)) }, { Text("Kilograms · km") })
            }
            Text("Default rest timer for new exercises", fontWeight = FontWeight.Bold)
            var pick by remember { mutableStateOf(false) }
            GreyButton(Units.restLabel(p.defaultRest)) { pick = true }
            if (pick) RestPicker(p.defaultRest, { pick = false }) { set(p.copy(defaultRest = it)); pick = false }

            Text("Rest timer alert", fontWeight = FontWeight.Bold)
            val soundName = remember(p.restSound) {
                when (p.restSound) {
                    "none" -> "Silent"
                    "" -> "Phone's default alarm sound"
                    else -> runCatching {
                        android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(p.restSound))?.getTitle(context)
                    }.getOrNull() ?: "Chosen sound"
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                if (r.resultCode == android.app.Activity.RESULT_OK) {
                    @Suppress("DEPRECATION")
                    val uri = r.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    set(Store.profile.value.copy(restSound = uri?.toString() ?: "none"))
                }
            }
            GreyButton("🔔  Sound: $soundName") {
                val i = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Rest timer sound")
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                if (p.restSound.isNotBlank() && p.restSound != "none") {
                    i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(p.restSound))
                }
                runCatching { picker.launch(i) }
            }
            Row(Modifier.fillMaxWidth().clickable { set(p.copy(restVibrate = !p.restVibrate)) }, verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrate", Modifier.weight(1f))
                androidx.compose.material3.Switch(p.restVibrate, { set(p.copy(restVibrate = it)) })
            }
            GreyButton("Test the alert", textColor = Blue) { Rest.alert(context) }
            Text("Plays at alarm volume, so you hear it even when your phone is on vibrate.", color = Sub, fontSize = 12.sp)

            Spacer(8)
            Text("App accent colour", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            AccentPicker(p) { set(p.copy(accent = it)) }

            Spacer(8)
            Text("Animations", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().clickable { set(p.copy(extraAnims = !p.extraAnims)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Extra animations")
                    Text(if (p.extraAnims) "1 Rep Max fire effects and the ring when you finish a set."
                        else "Off: no 1 Rep Max effects or set ring.",
                        color = Sub, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(p.extraAnims, { set(p.copy(extraAnims = it)) })
            }

            Spacer(8)
            Text("Ranks", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            RankModeChoice(p)

            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = StarGold)) { append("★") }
                    append(" Ranked from published research on that exercise, fitting your biometrics\n")
                    withStyle(SpanStyle(color = StarEstimate)) { append("★") }
                    append(" Ranked from an estimate: worked out from a similar researched lift, a strength model, or data that doesn't fully fit your biometrics\n")
                    withStyle(SpanStyle(color = StarAi)) { append("★") }
                    append(" Ranked from a rough estimate from AI-generated databases")
                },
                color = Sub, fontSize = 13.sp,
            )
            Row(Modifier.fillMaxWidth().clickable { set(p.copy(gripRank = !p.gripRank)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Separate Grip rank")
                    Text(if (p.gripRank) "Grip is ranked as its own standalone muscle group on the Home screen, requiring you to own a dynamometer, and perform a Grip Dynamometer Test."
                        else "Off: no Grip rank – your forearms rank (including the dynamometer) colors your forearms and hands.",
                        color = Sub, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(p.gripRank, { set(p.copy(gripRank = it)) })
            }

            Spacer(8)
            Text("Backup", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Auto-save keeps an exact copy of everything in Loadout – workouts (and one in progress), routines, profile, every setting, your exercise changes, text edits, reference weights, the Recycle Bin and every picture you've added – saved after every finished workout, a couple of minutes after other changes, and at least once a day. Each place keeps two files (A and B) that take turns, so one is always a good copy. Restore puts a copy back (on this phone or a new one) and restarts the app: select both A and B and the newer one is used. Your OpenAI key stays on this phone.", color = Sub, fontSize = 13.sp)
            AutoSaveSettings { message = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GreyButton("Save backup now", Modifier.weight(1f)) {
                    if (AutoSave.active().isEmpty()) export.launch("loadout-backup.zip")
                    else {
                        message = "Saving…"
                        scope.launch { message = withContext(Dispatchers.IO) { AutoSave.saveNow(context) } }
                    }
                }
                RestoreButton("Restore", Modifier.weight(1f), big = false, show = { message = it },
                    pickManually = { import.launch(arrayOf("application/zip", "application/json", "*/*")) }) { restoreUris(it) }
            }
            if (AutoSave.active().isNotEmpty()) Text("Save a one-off copy somewhere else", color = Blue, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { export.launch("loadout-backup.zip") }.padding(vertical = 4.dp))
            Text("📂 Open the folder where Loadout's data is stored", color = Blue, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { DataFiles.open(context) }.padding(vertical = 4.dp))
            Spacer(4)
            Text("Recycle Bin", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Deleted routines, workouts, exercises and pictures wait here for 30 days, so you can put them back.", color = Sub, fontSize = 13.sp)
            RecycleBinButton()
            Spacer(4)
            Text("Import from the Hevy app", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Bring in your workout history from the Hevy app. In Hevy: Profile → Settings → Export & Import Data → " +
                "Export Workouts, then pick that .csv file here. You'll match each Hevy exercise to a Loadout exercise and can turn your " +
                "Hevy workouts into Loadout routines.", color = Sub, fontSize = 13.sp)
            GreyButton("Import Hevy App data (.csv)") { import.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }
            message?.let { Text(it, color = Blue) }
            val info = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
            // Tap the build number 10 times quickly to unlock the developer settings.
            var taps by remember { mutableStateOf(0) }
            var lastTap by remember { mutableStateOf(0L) }
            Text("Loadout ${info?.versionName ?: ""} · build ${info?.longVersionCode ?: "?"}", color = Sub, fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 700) taps + 1 else 1
                    lastTap = now
                    if (p.devUnlocked) {
                        if (taps == 1) android.widget.Toast.makeText(context, "Developer settings are already on", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (taps >= 10) {
                        taps = 0
                        set(p.copy(devUnlocked = true))
                        android.widget.Toast.makeText(context, "Developer settings unlocked", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (taps >= 6) {
                        android.widget.Toast.makeText(context, "${10 - taps} more taps", android.widget.Toast.LENGTH_SHORT).show()
                    }
                })
            if (p.devUnlocked) {
                Spacer(8)
                Text("Developer settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Red)
                // Edit any text in the app where it's shown, then export the changes to have them built in.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("Edit app text")
                        androidx.compose.material3.Text("Tap any text to change its words, font size, position or width. Changes show straight away " +
                            "(on that screen only, wherever that exact text appears there). Pause it from the pill at the bottom to tap around normally.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(AppText.on, { AppText.turn(it) })
                }
                if (AppText.on || AppText.count > 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GreyButton("Export Modified App Text (${AppText.count})", Modifier.weight(1.6f)) { exportText.launch("loadout-modified-app-text.json") }
                    GreyButton("Undo all", Modifier.weight(1f), Red) { AppText.clear(); textMsg = "All text changes undone." }
                }
                textMsg?.let { androidx.compose.material3.Text(it, color = Blue) }
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(dev1rm = !p.dev1rm)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Personal 1 Rep Max estimate (trial)")
                        Text("Shows a regular (green) and enhanced (red) 1 Rep Max estimate on every ranked exercise. A red ★ after an exercise or set number means the enhanced one is learning from your 1 Rep Max tests and failure sets for it. Changes nothing else.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.dev1rm, { set(p.copy(dev1rm = it)) })
                }
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devResearch = !p.devResearch)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Research mode")
                        Text("Adds Research mode under the 1 Rep Max mode button: 1 Rep Max tests on the exercises that help the standards most, with an export.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devResearch, { set(p.copy(devResearch = it)) })
                }
                // Which standards rank you: V1 (normal), V2 (trial) or Gemini-favored. One at a time.
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devV2 = false, devGemini = false, devPersonal = false, devPurged = false)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("V1 rankings")
                        Text("The regular standards (built only from sources free for commercial use).", color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(!p.devV2 && !p.devGemini && !p.devPersonal && !p.devPurged, { set(p.copy(devV2 = false, devGemini = false, devPersonal = false, devPurged = false)) })
                }
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devV2 = !p.devV2, devGemini = false, devPersonal = false, devPurged = false)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("V2 rankings (trial)")
                        Text("Fixes light lifters' standards; women's pull-ups, push-ups, leg press and lat pulldown; kettlebell front squat and Tate press units; squat, bench and dumbbell press variants; counts at most 12 reps on machine and isolation lifts; your own tests count as one vote.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devV2, { set(p.copy(devV2 = it, devGemini = false, devPersonal = false, devPurged = false)) })
                }
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devGemini = !p.devGemini, devV2 = false, devPersonal = false, devPurged = false)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Favor Gemini's data (trial)")
                        Text("Ranks with standards built mostly (80%) from the Gemini database's own numbers, research lifts included. Stars stay the same; bodyweight-rep moves don't change.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devGemini, { set(p.copy(devGemini = it, devV2 = false, devPersonal = false, devPurged = false)) })
                }
                // PERSONAL-USE (removable): the pre-purge standards (tools/personal/build_purged.py).
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devPurged = !p.devPurged, devPersonal = false, devV2 = false, devGemini = false)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Purged rankings")
                        Text("The default. Ranks with the app's standards from before the October 2026 purge (${StandardsData.purgedOwn} exercises), " +
                            "plus ${StandardsData.purgedCount - StandardsData.purgedOwn} more from the personal-use standards that the purged set doesn't have (its own numbers are never replaced).",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devPurged, { set(p.copy(devPurged = it, devPersonal = false, devV2 = false, devGemini = false)) })
                }
                // PERSONAL-USE (removable): see tools/personal/README.md.
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devPersonal = !p.devPersonal, devV2 = false, devGemini = false, devPurged = false)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Personal-use rankings")
                        Text(if (StandardsData.personalCount == 0) "No personal-use data in this build yet."
                            else "Ranks only with the separate personal-use standards (${StandardsData.personalCount} exercises), including sources that aren't free for commercial use. Exercises they don't cover aren't ranked.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devPersonal, { set(p.copy(devPersonal = it, devV2 = false, devGemini = false, devPurged = false)) })
                }
                Row(Modifier.fillMaxWidth().clickable { set(p.copy(devAi = !p.devAi)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI picture tools")
                        Text("OpenAI picture making (per exercise, per routine, every exercise) and picture-pack export/import.",
                            color = Sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.Switch(p.devAi, { set(p.copy(devAi = it)) })
                }
                if (p.devAi) {
            Spacer(8)
            Text("AI exercise pictures (OpenAI)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Paste an API key from platform.openai.com (API keys). Each picture is made once and saved on your phone; " +
                    "OpenAI charges a few cents per picture (low quality is cheapest).",
                color = Sub, fontSize = 13.sp,
            )
            var key by remember { mutableStateOf(p.apiKey) }
            OutlinedTextField(key, { key = it.trim(); set(p.copy(apiKey = key)) }, Modifier.fillMaxWidth().typing(),
                label = { Text("OpenAI API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            var model by remember { mutableStateOf(p.imageModel) }
            OutlinedTextField(model, { model = it.trim(); set(p.copy(imageModel = model.ifBlank { "gpt-image-1" })) }, Modifier.fillMaxWidth().typing(),
                label = { Text("Image model") }, singleLine = true)
            Text("Quality", color = Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low", "medium", "high").forEach { q -> FilterChip(p.imageQuality == q, { set(p.copy(imageQuality = q)) }, { Text(titleCase(q)) }) }
            }
            var allDialog by remember { mutableStateOf(false) }
            val shown = Store.exercises.value.values.filter { !it.hidden }
            GreyButton("✨ Make pictures for every exercise (${shown.size})", textColor = Blue) { allDialog = true }
            if (allDialog) GenDialog("AI pictures: every exercise", shown.sortedBy { it.name }.map { it.id }) { allDialog = false }
            GenPanel()
            Text("For one routine, use the button on that routine (or its ••• menu on the Workout tab).", color = Sub, fontSize = 13.sp)
            Text("You can also make or add a picture on any exercise's page.", color = Sub, fontSize = 13.sp)

            Spacer(8)
            Text("Picture pack", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Export saves all your exercise pictures as zip files (split into parts under 20 MB) in a folder you pick – " +
                    "share them, or upload them to the app's GitHub page to have them built into the app. " +
                    "Import adds pictures from those zip files (pick all the parts at once).",
                color = Sub, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GreyButton("Export pictures", Modifier.weight(1f)) { exportPics.launch(null) }
                GreyButton("Import pictures", Modifier.weight(1f)) { importPics.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            }
            packMsg?.let { Text(it, color = Blue) }

                }
                // Replays the first-run welcome; your answers are already filled in and nothing else is reset.
                GreyButton("Show the welcome screens again") { set(p.copy(onboarded = false)) }
                GreyButton("Hide developer settings", textColor = Red) { set(p.copy(devUnlocked = false, devAi = false, dev1rm = false, devGemini = false, devV2 = false, devPersonal = false, devResearch = false)) }
                Spacer(8)
                Text("Backup log", fontWeight = FontWeight.Bold)
                Text("What Android's Google backup has done with Loadout. Empty means Android hasn't asked Loadout for a backup yet.",
                    color = Sub, fontSize = 13.sp)
                var logTick by remember { mutableStateOf(0) }
                val log = remember(logTick) { CloudBackup.readLog(context) }
                Text(if (log.isEmpty()) "(nothing yet)" else log.takeLast(15).joinToString("\n"), fontSize = 12.sp, color = Sub,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(10.dp))
                GreyButton("Refresh log") { logTick++ }
                Spacer(8)
                Text("Wipe all Loadout data", fontWeight = FontWeight.Bold)
                Text("Deletes everything on this phone – workouts, routines, your exercises and pictures, profile, settings and the API key – " +
                    "and empties the Google backup at its next run. For testing the first-run experience.", color = Sub, fontSize = 13.sp)
                var confirmWipe by remember { mutableStateOf(false) }
                BigButton("Wipe all data", color = Red) { confirmWipe = true }
                if (confirmWipe) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmWipe = false },
                    title = { Text("Wipe everything?") },
                    text = { Text("All your Loadout data on this phone will be permanently deleted, and your Google backup emptied. This can't be undone. " +
                        "Save a backup first if you want to keep anything.") },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmWipe = false; DevWipe.wipe(context) }) {
                        Text("Wipe everything", color = Red) } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
                )
            }
            Spacer(30)
        }
    }
}


/** Off / Accurate only / All on – in Settings and the welcome. */
@Composable
fun RankModeChoice(p: Profile) {
    fun set(mode: String) = Store.setProfile(Store.profile.value.copy(rankMode = mode))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("off", "Off", "No ranks, badges, stars or rank-up pop-ups anywhere."),
            Triple("accurate", "Accurate only", "Only exercises with their own published research, where the data closely fits your body weight, age and sex, are ranked (gold ★)."),
            Triple("all", "All on", "Also ranks estimates: exercises worked out from a similar researched lift, bodyweight moves, and data that doesn't fit you exactly (blue ★)."),
            Triple("ai", "All on + AI (Recommended)", "Also ranks ${StandardsData.all.count { it.aiFor(p.male) }} more exercises as rough estimates from AI-generated databases (purple ★). Technically the least accurate of the three colored stars."),
        ).forEach { (key, label, desc) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (p.rankMode == key) Blue.copy(alpha = 0.15f) else Card)
                    .clickable { set(key) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(p.rankMode == key, { set(key) })
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(stars(desc), color = Sub, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Settings → App accent colour: drag along the rainbow bar; the example text (and the whole app) changes as you drag.
 * The colour keeps the default blue's strength and brightness, only the hue moves.
 */
@Composable
private fun AccentPicker(p: Profile, save: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(Accent.default.toArgb(), it) } }
    fun colorAt(h: Float) = android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2]))
    val start = remember(p.accent) { FloatArray(3).also { android.graphics.Color.colorToHSV(p.accent, it) }[0] }
    var hue by remember(p.accent) { mutableStateOf(start) }
    val rainbow = remember { (0..12).map { Color(colorAt(it * 30f)) } }
    var width by remember { mutableStateOf(1f) }
    fun pick(x: Float) { hue = (x / width).coerceIn(0f, 1f) * 360f; Accent.color = Color(colorAt(hue)) }
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(36.dp).onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pick(it.x) },
                    onDragEnd = { save(colorAt(hue)) },
                    onDragCancel = { save(colorAt(hue)) },
                ) { change, _ -> pick(change.position.x) }
            }
            .pointerInput(Unit) { detectTapGestures { pick(it.x); save(colorAt(hue)) } },
    ) {
        val r = size.height / 2
        drawRoundRect(Brush.horizontalGradient(rainbow), topLeft = Offset(0f, r * 0.5f), size = Size(size.width, r),
            cornerRadius = CornerRadius(r / 2))
        val x = hue / 360f * size.width
        drawCircle(Color.White, radius = r * 0.85f, center = Offset(x, r))
        drawCircle(Color(colorAt(hue)), radius = r * 0.65f, center = Offset(x, r))
    }
    Text("This is example text", color = Accent.color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    if (p.accent != Accent.default.toArgb()) Text("Reset to the default blue", color = Sub, fontSize = 13.sp,
        modifier = Modifier.clickable { Accent.color = Accent.default; save(Accent.default.toArgb()) }.padding(vertical = 4.dp))
}
