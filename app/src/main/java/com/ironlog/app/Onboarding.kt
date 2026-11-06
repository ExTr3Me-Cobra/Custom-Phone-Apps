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
import kotlinx.coroutines.launch
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
    val pages = listOf("welcome", "start", "you", "data", "ranks", "ready")
    // New to Loadout, or bringing data over (null until you pick).
    var importing by rememberSaveable { mutableStateOf<Boolean?>(null) }
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
                    "start" -> StartPage(importing) { importing = it }
                    "you" -> AboutYouPage(p)
                    "data" -> YourDataPage(p) {
                        Store.setProfile(Store.profile.value.copy(cloud = "local"))
                        page++
                    }
                    "ranks" -> RanksPage(p)
                    else -> ReadyPage()
                }
            }
            val missing = when (pages[page]) {
                "start" -> if (importing == null) "Choose one to continue." else null
                "you" -> biometricsMissing(p)
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
                        if (page < pageCount - 1) page++ else {
                            Store.setProfile(Store.profile.value.copy(onboarded = true))
                            // Hevy history picked during setup: match its exercises now.
                            if (HevyImport.pending != null) Nav.go(Route.HevyMatch)
                        }
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

/** First thing after the welcome: start fresh, or restore your data (from auto-save or a backup file). */
@Composable
private fun StartPage(importing: Boolean?, set: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    fun restoreUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        msg = "Restoring…"
        scope.launch {
            msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val n = Store.restoreFiles(context, uris)
                    when {
                        n == -1 -> "Hevy history ready ✓ – right after setup you'll choose how its exercises match Loadout's."
                        Store.lastFull -> {
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) { kotlinx.coroutines.delay(1500); Store.restartApp(context) }
                            "Restored ✓ – restarting Loadout…"
                        }
                        else -> "Restored ✓"
                    }
                }.getOrElse { "That file isn't a Loadout backup. Pick loadout-autosave-A.zip and -B.zip (or a backup you saved)." }
            }
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(AutoSave.OpenBackups()) { uris -> restoreUris(uris) }
    // Hevy: read now, matched right after setup (the matching screen needs the app open).
    val hevy = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = "Reading…"
        scope.launch {
            msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (!HevyImport.looksLikeHevy(text)) return@runCatching "That isn't a Hevy export. In Hevy: Profile → Settings → Export & Import Data → Export Workouts."
                    HevyImport.pending = HevyImport.prepare(text)
                    "Hevy history ready ✓ – right after setup you'll choose how its exercises match Loadout's."
                }.getOrElse { "Couldn't read that file." }
            }
        }
    }
    Title("Are you new to Loadout?")
    @Composable fun Choice(value: Boolean, icon: String, title: String, text: String) {
        val on = importing == value
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Blue.copy(alpha = 0.18f) else Card)
                .clickable { set(value) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, fontSize = 26.sp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text, color = Sub, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) btnBg(Blue) else Card2), contentAlignment = Alignment.Center) {
                if (on) OutlinedLabel("✓", 13.sp, bg = btnBg(Blue))
            }
        }
    }
    Choice(false, "👋", "I'm new to Loadout", "Set up from scratch.")
    Choice(true, "📦", "I have data to import", "A Loadout backup (auto-save or one you saved, e.g. moving to a new phone), or your Hevy history.")
    if (importing == true) {
        RestoreButton("Find my backup", Modifier.fillMaxWidth(), big = true, show = { msg = it },
            pickManually = { pick.launch(arrayOf("application/zip", "application/json", "*/*")) }) { restoreUris(it) }
        DriveRestoreButton { msg = it }
        GreyButton("Import from Hevy (.csv)") { hevy.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }
    }
    msg?.let { Text(it, color = Blue, fontSize = 14.sp) }
}

@Composable
private fun YourDataPage(p: Profile, skip: () -> Unit) {
    Title("Your data")
    Body("Loadout keeps everything on this phone. To keep a copy safe, set up auto-save: it saves an exact copy of your data to a file on this phone, Google Drive, or both, after every workout.")
    LaunchedEffect(Unit) { if (Store.profile.value.cloud.isEmpty()) Store.setProfile(Store.profile.value.copy(cloud = "local")) }
    var msg by remember { mutableStateOf<String?>(null) }
    AutoSaveSettings { msg = it }
    msg?.let { Text(it, color = Blue, fontSize = 13.sp) }
    Text("You can set this up or change it any time in Settings → Backup.", color = Sub, fontSize = 13.sp)
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


