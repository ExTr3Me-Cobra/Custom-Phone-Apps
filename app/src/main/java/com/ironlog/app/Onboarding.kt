package com.ironlog.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/**
 * First-run welcome. Covers the whole app until you've entered your body weight, age and sex
 * (saved straight into your profile) and stepped through the intro. It can't be skipped or closed:
 * the back button and taps behind it do nothing.
 */
@Composable
fun Onboarding() {
    val p by Store.profile.collectAsState()
    if (p.onboarded) return
    var page by rememberSaveable { mutableStateOf(0) }
    // The backup check page only appears if you chose Google backup.
    val pages = listOfNotNull("welcome", "you", "data", "backup".takeIf { p.cloud == "google" }, "ranks", "ready")
    val pageCount = pages.size
    if (page >= pageCount) page = pageCount - 1
    val focus = LocalFocusManager.current
    LaunchedEffect(page) { focus.clearFocus() }
    BackHandler { if (page > 0) page-- } // never closes it

    Box(
        Modifier.fillMaxSize().background(Bg)
            // Swallow every tap so nothing behind it can be used.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null && !TextBoxes.contains(up.position)) focus.clearFocus()
                }
            }
            .systemBarsPadding().imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pageCount) { i ->
                    Box(Modifier.padding(4.dp).size(if (i == page) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i <= page) Blue else Card2))
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (pages[page]) {
                    "welcome" -> WelcomePage()
                    "you" -> AboutYouPage(p)
                    "data" -> YourDataPage(p) {
                        Store.setProfile(Store.profile.value.copy(cloud = "local"))
                        page++
                    }
                    "backup" -> BackupCheckPage()
                    "ranks" -> RanksPage(p)
                    else -> ReadyPage()
                }
            }
            val missing = when (pages[page]) {
                "you" -> biometricsMissing(p)
                "data" -> if (p.cloud.isEmpty()) "Choose one to continue." else null
                else -> null
            }
            val ok = missing == null
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                missing?.let { Text(it, color = Sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (page > 0) GreyButton("Back", Modifier.weight(1f)) { page-- }
                    BigButton(
                        when (page) { 0 -> "Get started"; pageCount - 1 -> "Start lifting"; else -> "Next" },
                        color = if (ok) Blue else Card2, modifier = Modifier.weight(2f),
                    ) {
                        if (!ok) return@BigButton
                        if (page < pageCount - 1) page++ else Store.setProfile(Store.profile.value.copy(onboarded = true))
                    }
                }
            }
        }
    }
}

/** What still needs filling in on the About you page, or null when it's complete. */
private fun biometricsMissing(p: Profile): String? {
    val bw = p.bodyweight
    val age = p.age
    return when {
        bw == null -> "Enter your body weight to continue."
        bw < 60 || bw > 700 -> "That body weight doesn't look right."
        age == null -> "Enter your age to continue."
        age < 13 || age > 100 -> "Age must be between 13 and 100."
        else -> null
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String) = Text(text, color = Color(0xFFD1D1D6), fontSize = 15.sp, lineHeight = 21.sp)

@Composable
private fun InfoCard(content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) { content() }

@Composable
private fun WelcomePage() {
    Spacer(40)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RankBadge(Tier.LEGEND, 130.dp) }
    Text("Welcome to Loadout", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Log your lifts. See how you rank.", color = Sub, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AboutYouPage(p: Profile) {
    fun set(np: Profile) = Store.setProfile(np)
    Title("About you")
    Body("Used to rank your exercises against established data from other people with very similar biometrics. You can change it later in Settings.")

    var unitsTick by remember { mutableStateOf(0) } // refreshes the weight box when units change
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(p.male, { set(p.copy(male = true)) }, { Text("Male") })
        FilterChip(!p.male, { set(p.copy(male = false)) }, { Text("Female") })
        Box(Modifier.weight(1f))
        FilterChip(!p.kg, { set(p.copy(kg = false)); unitsTick++ }, { Text("lbs") })
        FilterChip(p.kg, { set(p.copy(kg = true)); unitsTick++ }, { Text("kg") })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        var bw by remember(unitsTick) { mutableStateOf(p.bodyweight?.let { Units.weight(it) } ?: "") }
        OutlinedTextField(bw, { t -> bw = t; set(Store.profile.value.copy(bodyweight = t.toDoubleOrNull()?.let(Units::fromShown))) },
            Modifier.weight(1f).typing(), label = { Text("Body weight (${Units.w})") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        var age by remember { mutableStateOf(p.age?.toString() ?: "") }
        OutlinedTextField(age, { t -> age = t; set(Store.profile.value.copy(age = t.toIntOrNull())) },
            Modifier.weight(1f).typing(), label = { Text("Age") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        var ft by remember { mutableStateOf(p.heightIn?.let { (it / 12).toString() } ?: "") }
        var inch by remember { mutableStateOf(p.heightIn?.let { (it % 12).toString() } ?: "") }
        fun saveH() { val f = ft.toIntOrNull(); val i = inch.toIntOrNull() ?: 0; set(Store.profile.value.copy(heightIn = f?.let { it * 12 + i })) }
        OutlinedTextField(ft, { ft = it; saveH() }, Modifier.weight(1f).typing(), label = { Text("Height ft (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(inch, { inch = it; saveH() }, Modifier.weight(1f).typing(), label = { Text("in") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    BodyFatPicker(p) { set(Store.profile.value.copy(bodyFat = it)) }
}

@Composable
private fun YourDataPage(p: Profile, skip: () -> Unit) {
    Title("Your data")
    Body("Loadout keeps everything on this phone unless you choose Google backup. You can change this any time in Settings.")
    DataChoice(p, showStatus = false)
}

@Composable
private fun BackupCheckPage() {
    Title("Make sure your data is backing up")
    Body("Make sure \"Back up data\" is on in your phone's backup settings. Loadout is included automatically.")
    val c = androidx.compose.ui.platform.LocalContext.current
    GreyButton("Open backup settings") { CloudBackup.openSettings(c) }
    Text("You can check that Loadout is backing up any time in Settings.", color = Sub, fontSize = 13.sp)
}

@Composable
private fun RanksPage(p: Profile) {
    Title("Ranks")
    Body("Exercises with strength data get a rank against people your size, sex and age – from Wood to Legend. The star after a name shows how solid that data is.")
    InfoCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            listOf(Tier.WOOD_1, Tier.BRONZE_2, Tier.SILVER_2, Tier.GOLD_2, Tier.PLATINUM_2, Tier.DIAMOND_2, Tier.CHAMPION, Tier.LEGEND)
                .forEachIndexed { i, t -> RankBadge(t, (26 + i * 3).dp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            RankBadge(Tier.GOLD_2, 44.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(buildAnnotatedString {
                    append("Bench Press ")
                    withStyle(SpanStyle(color = StarGold)) { append("★") }
                }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Gold 2 (top 42%)", color = Color(Tier.GOLD_2.color), fontSize = 14.sp)
            }
        }
        Text("Top 42% = stronger than 58% of them.", color = Sub, fontSize = 13.sp)
    }
    Text("A star after the name means it's ranked:", fontWeight = FontWeight.Medium)
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = StarGold, fontWeight = FontWeight.Bold)) { append("★ Gold") }
        append(" – Ranked from published research that measured that exercise in your sex, fitting your biometrics.")
    }, fontSize = 14.sp, color = Color(0xFFD1D1D6))
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = StarEstimate, fontWeight = FontWeight.Bold)) { append("★ Blue") }
        append(" – Ranked from an estimate: worked out from a similar researched lift, a strength model (bodyweight moves), or data that doesn't fully fit your biometrics.")
    }, fontSize = 14.sp, color = Color(0xFFD1D1D6))
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = StarAi, fontWeight = FontWeight.Bold)) { append("★ Purple") }
        append(" – Ranked from a rough estimate from AI-generated databases.")
    }, fontSize = 14.sp, color = Color(0xFFD1D1D6))
    Text("Show ranks", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    RankModeChoice(p)
    Text("You can change this any time in Settings.", color = Sub, fontSize = 13.sp)
}

@Composable
private fun ReadyPage() {
    Spacer(30)
    Text("💪", fontSize = 64.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("You're all set", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Start a workout from the Workout tab.", color = Sub, fontSize = 15.sp,
        lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** "Keep on this phone" / "Back up to my Google account" – used in the welcome and in Settings. */
@Composable
fun DataChoice(p: Profile, showStatus: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Leaving Google backup asks first: the copy in your Google account gets replaced with nothing.
    var confirmLocal by remember { mutableStateOf(false) }
    if (confirmLocal) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmLocal = false },
        title = { Text("Switch to this phone only?") },
        text = {
            Text("Are you sure? Your Loadout data that's backed up to your Google account will be deleted the next time Android " +
                "backs up, and nothing will be saved there from now on.\n\nIt will be your sole responsibility to keep a copy of " +
                "your app data safe – use Settings → Backup → Save backup and keep that file somewhere other than this phone.")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirmLocal = false
                Store.setProfile(Store.profile.value.copy(cloud = "local"))
                // Ask Android to back up soon, so the empty backup replaces the old one.
                runCatching { android.app.backup.BackupManager(context).dataChanged() }
            }) { Text("Keep my data locally on my device", color = Red) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmLocal = false }) { Text("Keep Google backup") } },
    )
    @Composable
    fun Option(value: String, icon: String, title: String, text: String) {
        val on = p.cloud == value
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Blue.copy(alpha = 0.18f) else Card)
                .clickable {
                    if (value == "local" && p.cloud == "google") { confirmLocal = true; return@clickable }
                    // Switching to Google starts the check over, so the green tick means a backup since then.
                    if (value == "google" && p.cloud != "google") CloudBackup.resetStatus()
                    Store.setProfile(Store.profile.value.copy(cloud = value))
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, fontSize = 26.sp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text, color = Sub, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Blue else Card2), contentAlignment = Alignment.Center) {
                if (on) OutlinedLabel("✓", 13.sp)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Option("local", "📱", "Keep my data locally on my device", "Nothing leaves your phone.")
        if (showStatus) Text("📂 Open the folder where Loadout's data is stored", color = Blue, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { DataFiles.open(context) }.padding(horizontal = 4.dp, vertical = 4.dp))
        Option("google", "☁️", "Back up to my Google account", "Restores automatically on a new phone or if you reinstall.")
        if (showStatus && p.cloud == "google") {
            Text("Make sure your data is backing up", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            BackupStatus()
        }
    }
}

/** Is Google backup working? Green tick once a backup has run since you chose it. */
@Composable
fun BackupStatus() {
    val c = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    // Re-check every few seconds (e.g. after coming back from the phone's settings).
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }
    val last = remember(tick) { CloudBackup.lastBackup(c) }
    val green = Color(0xFF34C759)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, color) = when {
                last != null -> "✓" to green
                else -> "…" to Card2
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                if (last != null) OutlinedLabel(icon, 18.sp) else Text(icon, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Column(Modifier.padding(start = 12.dp)) {
                when {
                    last != null -> {
                        Text("Backup is working", fontWeight = FontWeight.Bold, color = green)
                        Text("Last backed up " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(last)),
                            color = Sub, fontSize = 13.sp)
                    }
                    else -> {
                        Text("Waiting for the first backup", fontWeight = FontWeight.Bold)
                        Text("A green tick shows here once Loadout has been backed up.", color = Sub, fontSize = 13.sp)
                    }
                }
            }
        }
        Text("Your phone backs up on its own when idle. To do it now, open backup settings and tap Back up now, then come back here.",
            color = Sub, fontSize = 13.sp, lineHeight = 18.sp)
        GreyButton("Open backup settings") { CloudBackup.openSettings(c) }
    }
}
