package com.lightcheck.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Grey = Color(0xFFB8B8C0)
private val Card = Color(0xFF1C1C21)
private val Bad = Color(0xFFFF6B6B)
private val Good = Color(0xFF5FD38D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckService.ensureChannels(this)
        Scheduler.schedule(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Color.Black, surface = Color.Black)) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    // Bumped whenever the screen comes back (e.g. from a permission page), to re-read everything.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refresh++
                delay(3_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Text("Light Check", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp))
        TabRow(selectedTabIndex = tab, containerColor = Color.Black, contentColor = Amber) {
            listOf("Status", "Find lights", "Settings", "Diagnostics").forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name, fontSize = 13.sp, maxLines = 1) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (tab) {
                0 -> StatusTab(refresh) { tab = 1 }
                1 -> FindTab { tab = 0 }
                2 -> SettingsTab()
                else -> DiagnosticsTab(refresh)
            }
        }
    }
}

// ---------- Status ----------

@Composable
private fun StatusTab(refresh: Int, onFind: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val next = Scheduler.nextCheck(context)
    Section("Next check")
    Text(
        next?.let { DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(it)) } ?: "Off (no days chosen in Settings)",
        color = Color.White, fontSize = 17.sp
    )
    Spacer(Modifier.height(6.dp))
    Text("Last: ${prefs.lastResult}", color = Grey, fontSize = 14.sp)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { CheckService.start(context, CheckService.ACTION_MANUAL) }, colors = amberButton()) { Text("Check now") }

    Section("Living-room lights")
    val bulbs = prefs.bulbs
    if (bulbs.isEmpty()) {
        Text("None saved yet.", color = Grey)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onFind) { Text("Find my lights", color = Color.White) }
    } else {
        bulbs.forEach { b ->
            Text("• ${b.label}  (${b.ip}${if (b.brand.isNotEmpty()) ", ${b.brand}" else ""})", color = Color.White, fontSize = 15.sp)
        }
    }

    Section("Setup")
    SetupChecklist()
}

@Composable
private fun SetupChecklist() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val alarms = context.getSystemService(AlarmManager::class.java)
    val nm = context.getSystemService(NotificationManager::class.java)

    Item("Notifications allowed", has(Manifest.permission.POST_NOTIFICATIONS)) {
        notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Item("Exact time for the check", alarms.canScheduleExactAlarms()) {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }
    if (Build.VERSION.SDK_INT >= 34) {
        Item("Alarm can show full screen", nm.canUseFullScreenIntent()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
        }
    }
    Item("Location (to read the Wi-Fi's name)", has(Manifest.permission.ACCESS_FINE_LOCATION)) {
        location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
        Item("Location \"Allow all the time\" (for the 10 PM check)", has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    Item("Home Wi-Fi set (Settings)", prefs.homeSsid.isNotEmpty() || prefs.homeRouter.isNotEmpty(), fix = null)
    Item("Lights saved (Find lights)", prefs.bulbs.isNotEmpty(), fix = null)
}

@Composable
private fun Item(text: String, ok: Boolean, fix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✗", color = if (ok) Good else Bad, fontSize = 18.sp, modifier = Modifier.width(28.dp))
        Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!ok && fix != null) OutlinedButton(onClick = fix) { Text("Fix", color = Amber) }
    }
}

// ---------- Find lights ----------

@Composable
private fun FindTab(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) } // 0 start, 1 first scan, 2 switch off, 3 second scan, 4 pick
    var progress by remember { mutableStateOf(0f) }
    var withOn by remember { mutableStateOf<List<Found>>(emptyList()) }
    val candidates = remember { mutableStateListOf<Found>() }
    val chosen = remember { mutableStateListOf<String>() }
    var countdown by remember { mutableIntStateOf(0) }

    fun scan(then: (List<Found>) -> Unit) {
        progress = 0f
        scope.launch {
            val found = Net.scan(context) { done, total -> progress = done.toFloat() / total }
            then(found)
        }
    }

    Text(
        "Finds your living-room lights by what disappears from the Wi-Fi when the wall switch is turned off. " +
                "Stay on the home Wi-Fi the whole time.",
        color = Grey, fontSize = 14.sp
    )
    Spacer(Modifier.height(16.dp))
    when (step) {
        0 -> {
            Section("Step 1: switch ON")
            Text("Turn the living-room switch on and wait until the lights are on, then:", color = Color.White)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                step = 1
                scan { found ->
                    withOn = found
                    prefs.log("Find lights: ${found.size} devices with the switch on")
                    step = 2
                    countdown = 0
                }
            }, colors = amberButton()) { Text("Scan with the lights on") }
        }
        1, 3 -> {
            Section(if (step == 1) "Scanning (switch on)…" else "Scanning (switch off)…")
            LinearProgressIndicator(progress = { progress }, color = Amber, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}%", color = Grey, fontSize = 13.sp)
        }
        2 -> {
            Section("Step 2: switch OFF")
            Text("Found ${withOn.size} devices. Now turn the switch off. The lights need about 30 seconds to drop off the Wi-Fi.", color = Color.White)
            Spacer(Modifier.height(10.dp))
            if (countdown > 0) {
                Text("Waiting… $countdown s", color = Amber, fontSize = 18.sp)
            } else {
                Button(onClick = {
                    scope.launch {
                        countdown = 30
                        while (countdown > 0) {
                            delay(1000)
                            countdown--
                        }
                        step = 3
                        scan { found ->
                            val stillThere = found.map { it.ip }.toSet()
                            candidates.clear()
                            candidates += withOn.filter { it.ip !in stillThere }
                            chosen.clear()
                            chosen += candidates.map { it.ip }
                            prefs.log("Find lights: ${candidates.size} disappeared with the switch off: ${candidates.joinToString { it.ip }}")
                            step = 4
                        }
                    }
                }, colors = amberButton()) { Text("Switch is off: wait 30 s and scan") }
            }
        }
        else -> {
            Section("Step 3: your lights")
            if (candidates.isEmpty()) {
                Text(
                    "Nothing disappeared. The lights may connect through a hub (like a Philips Hue Bridge) instead of " +
                            "the Wi-Fi, or they took longer to drop off. Try again, waiting longer, or pick from all devices below.",
                    color = Color.White
                )
            } else {
                Text("These answered with the switch on and vanished with it off:", color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            val shown = if (candidates.isEmpty()) withOn else candidates
            shown.forEach { f -> DeviceRow(f, f.ip in chosen) { on -> if (on) chosen += f.ip else chosen -= f.ip } }
            if (withOn.any { it.brand == "Philips Hue Bridge" }) {
                Spacer(Modifier.height(8.dp))
                Text("A Philips Hue Bridge is on your Wi-Fi: Hue bulbs talk to it, not the Wi-Fi, so they can't be found this way.", color = Amber, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    prefs.bulbs = shown.filter { it.ip in chosen }.map { Bulb(it.ip, it.name, it.how, it.brand) }
                    prefs.log("Saved lights: ${prefs.bulbs.joinToString { it.label }}")
                    // Remember the home Wi-Fi while here, if it isn't yet.
                    if (prefs.homeSsid.isEmpty()) Net.wifiState(context)?.ssid?.let { prefs.homeSsid = it }
                    scope.launch { if (prefs.homeRouter.isEmpty()) prefs.homeRouter = Net.routerFingerprint(context) }
                    step = 0
                    onDone()
                }, enabled = chosen.isNotEmpty(), colors = amberButton()) { Text("Save ${chosen.size} light(s)") }
                OutlinedButton(onClick = { step = 0 }) { Text("Start over", color = Color.White) }
            }
        }
    }
}

@Composable
private fun DeviceRow(f: Found, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = Amber))
        Column(Modifier.weight(1f)) {
            Text(f.name.ifEmpty { f.ip }, color = Color.White, fontSize = 15.sp)
            Text("${f.ip} · ${f.how}${if (f.brand.isNotEmpty()) " · ${f.brand}" else ""}", color = Grey, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
}

// ---------- Settings ----------

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var minute by remember { mutableIntStateOf(prefs.minute) }
    var days by remember { mutableStateOf(prefs.days) }
    var ssid by remember { mutableStateOf(prefs.homeSsid) }
    var router by remember { mutableStateOf(prefs.homeRouter) }
    var all by remember { mutableStateOf(prefs.alarmWhenAll) }
    var sound by remember { mutableStateOf(prefs.soundUri) }
    var volume by remember { mutableStateOf(prefs.volume.toFloat()) }
    var vibrate by remember { mutableStateOf(prefs.vibrate) }
    var untilDismissed by remember { mutableStateOf(prefs.ringUntilDismissed) }
    var silent by remember { mutableStateOf(prefs.ringInSilent) }
    var snooze by remember { mutableIntStateOf(prefs.snoozeMinutes) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        @Suppress("DEPRECATION")
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            sound = uri.toString()
            prefs.soundUri = sound
        }
    }

    Section("When")
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, h, m ->
            hour = h; minute = m
            prefs.hour = h; prefs.minute = m
            Scheduler.schedule(context)
        }, hour, minute, false).show()
    }) {
        Text("Check at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute) }.time)}", color = Color.White, fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(1 to "S", 2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S").forEach { (day, label) ->
            FilterChip(selected = day in days, onClick = {
                days = if (day in days) days - day else days + day
                prefs.days = days
                Scheduler.schedule(context)
            }, label = { Text(label) })
        }
    }

    Section("Home Wi-Fi")
    Text(
        when {
            ssid.isNotEmpty() -> "\"$ssid\""
            router.isNotEmpty() -> "Recognised by its router ($router)"
            else -> "Not set"
        },
        color = Color.White
    )
    Text("Away from it at check time, you get a \"couldn't check\" notification instead of the alarm.", color = Grey, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = {
        val state = Net.wifiState(context)
        ssid = state?.ssid.orEmpty()
        prefs.homeSsid = ssid
        scope.launch {
            router = Net.routerFingerprint(context)
            prefs.homeRouter = router
        }
    }) { Text("Use the Wi-Fi I'm on now", color = Amber) }

    Section("Alarm")
    Toggle("Only when all lights are off (else: any)", all) { all = it; prefs.alarmWhenAll = it }
    OutlinedButton(onClick = {
        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound.takeIf { it.isNotEmpty() }?.let(Uri::parse))
        })
    }) {
        val name = sound.takeIf { it.isNotEmpty() }?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it)).getTitle(context) }.getOrNull() }
        Text("Sound: ${name ?: "default alarm"}", color = Color.White)
    }
    Spacer(Modifier.height(8.dp))
    Text("Volume ${volume.toInt()}%", color = Color.White)
    Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { prefs.volume = volume.toInt() },
        valueRange = 10f..100f, colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber))
    Toggle("Vibrate", vibrate) { vibrate = it; prefs.vibrate = it }
    Toggle("Ring until dismissed (else about a minute)", untilDismissed) { untilDismissed = it; prefs.ringUntilDismissed = it }
    Toggle("Ring in silent mode and Do Not Disturb", silent) { silent = it; prefs.ringInSilent = it }
    Spacer(Modifier.height(6.dp))
    Text("Snooze (checks the lights again after)", color = Color.White)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 10, 15, 30).forEach { m ->
            FilterChip(selected = snooze == m, onClick = { snooze = m; prefs.snoozeMinutes = m }, label = { Text("$m min") })
        }
    }

    Section("Try it")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { CheckService.start(context, CheckService.ACTION_TEST) }, colors = amberButton()) { Text("Test alarm") }
        OutlinedButton(onClick = { CheckService.start(context, CheckService.ACTION_MANUAL) }) { Text("Check now", color = Color.White) }
    }
}

@Composable
private fun Toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Color.Black))
    }
}

// ---------- Diagnostics ----------

@Composable
private fun DiagnosticsTab(refresh: Int) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var router by remember { mutableStateOf("") }
    @Suppress("UNUSED_EXPRESSION") refresh
    val state = Net.wifiState(context)
    LaunchedEffect(Unit) { router = Net.routerFingerprint(context) }

    Section("Wi-Fi now")
    Text(
        if (state == null) "Not on Wi-Fi" else
            "Name: ${state.ssid ?: "(hidden: needs location access)"}\nPhone: ${state.ip}/${state.prefix}\nRouter: ${state.gateway}\nRouter fingerprint: ${router.ifEmpty { "…" }}",
        color = Color.White, fontSize = 14.sp
    )

    Section("Saved lights now")
    val bulbs = prefs.bulbs
    if (bulbs.isEmpty()) Text("None", color = Grey)
    bulbs.forEach { b ->
        var answers by remember(b) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(b, refresh / 5) { answers = Net.answers(context, b) }
        Text(
            "${when (answers) { true -> "● answering"; false -> "○ not answering"; null -> "… checking" }}  ${b.label} (${b.ip}, by ${b.how})",
            color = when (answers) { true -> Good; false -> Bad; null -> Grey }, fontSize = 14.sp
        )
    }

    Section("Everything on the Wi-Fi")
    Button(onClick = {
        scanning = true
        scope.launch {
            found = Net.scan(context) { d, t -> progress = d.toFloat() / t }
            prefs.log("Diagnostics scan: ${found.size} devices")
            scanning = false
        }
    }, enabled = !scanning, colors = amberButton()) { Text(if (scanning) "Scanning… ${(progress * 100).toInt()}%" else "Scan network") }
    Spacer(Modifier.height(8.dp))
    found.forEach { f ->
        Column(Modifier.fillMaxWidth().background(Card).padding(8.dp)) {
            Text(f.name.ifEmpty { f.ip }, color = Color.White, fontSize = 14.sp)
            Text("${f.ip} · ${f.how}${if (f.brand.isNotEmpty()) " · ${f.brand}" else ""}", color = Grey, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
    }

    Section("Log")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = {
            val text = prefs.log.joinToString("\n")
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Light Check log", text))
        }) { Text("Copy", color = Amber) }
        OutlinedButton(onClick = { prefs.clearLog() }) { Text("Clear", color = Color.White) }
    }
    Spacer(Modifier.height(6.dp))
    prefs.log.reversed().forEach { Text(it, color = Grey, fontSize = 12.sp) }
}

// ---------- Shared ----------

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(color = Color(0xFF2A2A30), modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun amberButton() = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)

@Suppress("unused")
private fun Context.hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
