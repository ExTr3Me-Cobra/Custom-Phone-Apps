package com.ironlog.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 1 Rep Max mode: test your true max on a ranked exercise. The result is saved as a one-exercise
 * workout marked as a test, and sets your rank for that exercise (later workouts only count if they beat it).
 */
object OneRm {
    class Notes(val prep: List<String>, val form: List<String>, val safety: List<String>)

    private var notes: Map<String, Notes> = emptyMap()
    fun init(c: Context) {
        notes = runCatching {
            val o = JSONObject(c.assets.open("onerm_notes.json").bufferedReader().readText())
            fun JSONArray.strings() = List(length()) { getString(it) }
            o.keys().asSequence().associateWith { k ->
                val n = o.getJSONObject(k)
                Notes(n.optJSONArray("prep")?.strings().orEmpty(), n.optJSONArray("form")?.strings().orEmpty(),
                    n.optJSONArray("safety")?.strings().orEmpty())
            }
        }.getOrDefault(emptyMap())
    }
    fun notes(id: String) = notes[id]

    /** Exercises a max test makes sense for: ranked for you (★) and scored on a max weight, max reps or a dynamometer squeeze. */
    fun canTest(id: String, p: Profile): Boolean {
        if (!Standards.covers(id, p)) return false
        val t = Store.exercise(id)?.type ?: return false
        return t == ExType.WEIGHT || t == ExType.BODYWEIGHT || t == ExType.TIMED
    }

    // Exercises you added to the list before doing them.
    fun added(): List<String> = runCatching {
        JSONArray(Store.readFile("onerm_list.json") ?: "[]").let { a -> List(a.length()) { a.getString(it) } }
    }.getOrDefault(emptyList())
    fun add(id: String) { if (id !in added()) Store.writeFile("onerm_list.json", JSONArray(added() + id).toString()) }
    /** Info cards (Warm-up plan, Form, Safety) you've folded up; remembered between tests and app restarts. */
    fun folded(): Set<String> = runCatching {
        JSONArray(Store.readFile("onerm_folded.json") ?: "[]").let { a -> List(a.length()) { a.getString(it) }.toSet() }
    }.getOrDefault(emptySet())
    fun setFolded(title: String, folded: Boolean) =
        Store.writeFile("onerm_folded.json", JSONArray((if (folded) folded() + title else folded() - title).toList()).toString())

    fun remove(id: String) {
        Store.exercise(id)?.let { RecycleBin.oneRm(it) }
        Store.writeFile("onerm_list.json", JSONArray(added() - id).toString())
    }

    // The test in progress (kept while you look at other screens).
    var exerciseId: String? = null
    var startedAt = 0L
    val rows = mutableStateListOf<DoneSet>()

    /** Rounds to a loadable weight: 5 lb or 2.5 kg. */
    fun loadable(lb: Double): Double {
        val step = if (Units.kg) 2.5 else 5.0
        return Units.fromShown((Units.toShown(lb) / step).roundToInt() * step)
    }

    /** Your best so far for the warm-up plan: your rank's number, or your best from any workout. */
    fun best(id: String, workouts: List<Workout>, p: Profile): Double? =
        Ranks.all(workouts, p)[id]?.value ?: workouts.filter { it.end != null }.maxOfOrNull { w ->
            Standards.bestValue(id, w.items.filter { it.exerciseId == id }.flatMap { it.sets })
        }?.takeIf { it > 0 }

    /** Warm-up and attempt sets as (share of your best, reps, label). */
    val PLAN = listOf(
        Triple(0.50, 5, "warm-up"), Triple(0.70, 3, "warm-up"), Triple(0.85, 1, "warm-up"),
        Triple(0.95, 1, "attempt 1"), Triple(1.00, 1, "attempt 2"),
    )

    /** The logged set your current rank comes from (or your best set ever if you're not ranked), as a fresh, unticked set. */
    fun bestSet(id: String, workouts: List<Workout>, p: Profile): DoneSet? {
        val sets = workouts.filter { it.end != null }.flatMap { w -> w.items.filter { it.exerciseId == id }.flatMap { it.sets } }
            .filter { it.done && !it.warmup }
        if (sets.isEmpty()) return null
        fun v(s: DoneSet) = Standards.oneSet(id, s, true)
        val target = Ranks.all(workouts, p)[id]?.value
        val s = target?.let { t -> sets.filter { kotlin.math.abs(v(it) - t) < 0.01 }.maxByOrNull { v(it) } } ?: sets.maxByOrNull { v(it) }
        return s?.let { DoneSet(weight = it.weight, reps = it.reps, seconds = it.seconds, distance = it.distance) }
    }

    /** Starts a test with one set filled in from the set your rank comes from (add more as you go). */
    fun begin(id: String, start: DoneSet?) {
        if (exerciseId == id && rows.isNotEmpty()) return
        exerciseId = id
        startedAt = System.currentTimeMillis()
        rows.clear()
        rows += start ?: DoneSet(reps = if (Standards.isReps(id) || Standards.isTimed(id)) null else 1)
    }

    fun clear() { exerciseId = null; rows.clear() }

    /** The test so far as a workout, so ticking a set shows rank-ups and rank-downs exactly like a workout does. */
    fun liveWorkout(id: String) = Workout(
        id = "onerm-$startedAt", routineId = null, name = "1RM test", start = startedAt, end = null,
        items = listOf(WorkoutItem(id, rows.toList(), 180)), bodyweight = Store.profile.value.bodyweight, test = true,
    )
}

/** The 1 Rep Max mode button on the Workout tab: darker and more serious than the others. */
@Composable
fun OneRmButton(modifier: Modifier = Modifier) {
    val gold = Color(0xFFC9A94E)
    val press = rememberPress(0.94f)
    val heat = rememberHeat()
    Row(
        Modifier.pressScale(press).then(modifier).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2A0E0E), Color(0xFF1A1A1A))))
            .heatFire(heat).glint(heat).emberBorder(heat, 1.5.dp, gold, RoundedCornerShape(12.dp))
            .pressClick(press) { Nav.go(Route.OneRmList) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🏆", fontSize = 26.sp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("1 REP MAX MODE", color = gold, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Text("Test your true max for your most accurate rank", color = Sub, fontSize = 13.sp)
        }
        Text("›", color = gold, fontSize = 26.sp)
    }
}

@Composable
fun OneRmListScreen() {
    val p by Store.profile.collectAsState()
    val workouts by Store.workouts.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val ranks = remember(workouts, p) { Ranks.all(workouts, p) }
    val list = remember(workouts, p, refresh) {
        val counts = HashMap<String, Int>()
        workouts.filter { it.end != null }.forEach { w -> w.items.map { it.exerciseId }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val ids = (counts.keys + OneRm.added()).filter { OneRm.canTest(it, p) && Store.exercise(it) != null }.distinct()
        ids.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { Store.exercise(it)?.name }).map { it to (counts[it] ?: 0) }
    }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val words = query.lowercase().split(" ").filter { it.isNotBlank() }
    // How many 1 Rep Max tests each exercise has had.
    val tests = remember(workouts) {
        val t = HashMap<String, Int>()
        workouts.filter { it.test && it.end != null }.forEach { w -> w.items.filter { i -> i.sets.any { it.done } }.map { it.exerciseId }.distinct().forEach { t[it] = (t[it] ?: 0) + 1 } }
        t
    }
    var sort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("most") }
    var showTested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var showUntested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    val sorts = listOf("most" to "Most experience", "least" to "Least experience", "high" to "Highest rank", "low" to "Lowest rank",
        "mostTests" to "Most 1RM tested", "leastTests" to "Least 1RM tested")
    // Exercises with no rank yet count as the very lowest rank.
    fun rankOf(id: String) = ranks[id]?.percentile ?: -1.0
    val byName = compareBy<Pair<String, Int>> { Store.exercise(it.first)?.name }
    val shown = list
        .filter { (id, _) -> val n = Store.exercise(id)?.name?.lowercase().orEmpty(); words.all { it in n } }
        .filter { (id, _) -> if ((tests[id] ?: 0) > 0) showTested else showUntested }
        .sortedWith(when (sort) {
            "least" -> compareBy<Pair<String, Int>> { it.second }.then(byName)
            "high" -> compareByDescending<Pair<String, Int>> { rankOf(it.first) }.then(byName)
            "low" -> compareBy<Pair<String, Int>> { rankOf(it.first) }.then(byName)
            "mostTests" -> compareByDescending<Pair<String, Int>> { tests[it.first] ?: 0 }.then(byName)
            "leastTests" -> compareBy<Pair<String, Int>> { tests[it.first] ?: 0 }.then(byName)
            else -> compareByDescending<Pair<String, Int>> { it.second }.then(byName)
        })
    val listFx = remember { FxState() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().fxShake(listFx)) {
        TopBar("1 Rep Max", action = "🔍") { searching = !searching; if (!searching) query = "" }
        if (searching) {
            androidx.compose.material3.OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).focusRequester(focus).typing(),
                placeholder = { Text("Search your 1 Rep Max exercises") }, singleLine = true,
                trailingIcon = { Text("✕", color = Sub, modifier = Modifier.clickable { query = ""; searching = false }.padding(12.dp)) },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
        // Sort and filter, like All Workouts.
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                GreyButton("Sort: ${sorts.first { it.first == sort }.second} ▾") { sortMenu = true }
                androidx.compose.material3.DropdownMenu(sortMenu, { sortMenu = false }) {
                    sorts.forEach { (k, label) ->
                        androidx.compose.material3.DropdownMenuItem({ Text((if (k == sort) "✓  " else "     ") + label) }, { sort = k; sortMenu = false })
                    }
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(0.7f)) {
                val off = listOf(showTested, showUntested).count { !it }
                GreyButton("Filter" + (if (off > 0) " (1 of 2)" else "") + " ▾") { filterMenu = true }
                androidx.compose.material3.DropdownMenu(filterMenu, { filterMenu = false }) {
                    @Composable fun Tick(label: String, on: Boolean, set: (Boolean) -> Unit) = androidx.compose.material3.DropdownMenuItem(
                        { Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(on, set); Text(label) } }, { set(!on) })
                    Tick("1 Rep Max tested", showTested) { showTested = it }
                    Tick("Not tested yet", showUntested) { showUntested = it }
                }
            }
        }
        // Going into 1 Rep Max (not coming back from a test): the opening's effect, and the earthquake test if on.
        // (Read and cleared once the screen is really on, so an extra trial composition can't use it up.)
        val quake = remember { Animatable(1f) }
        val quakeHaptic = LocalHapticFeedback.current
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        LaunchedEffect(Unit) {
            val entering = OneRmFx.enteringList
            OneRmFx.enteringList = false
            if (!entering || !extraAnims()) return@LaunchedEffect
            OneRmFx.entryEffect()?.let { (k, at) -> launch { listFx.play(k, quakeHaptic, at, density) } }
            // The earthquake test, or an opening that includes one (it hits as the screen lands).
            val shakeAt = OneRmFx.entryQuake()
            if (OneRmFx.listQuake || shakeAt != null) {
                quake.snapTo(0f)
                kotlinx.coroutines.delay(shakeAt ?: 150)
                launch { repeat(4) { quakeHaptic.performHapticFeedback(HapticFeedbackType.LongPress); kotlinx.coroutines.delay(150) } }
                quake.animateTo(1f, tween(1100))
            }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (words.isEmpty()) item {
                Text("Pick an exercise to test your true one-rep max (or max reps for bodyweight moves). " +
                    "Your result sets your rank for it – later workouts only change it if they beat it.", color = Sub, fontSize = 14.sp)
                if (Standards.mode(p) == Standards.Mode.OFF) Text("Ranks are turned off in Settings, so there's nothing to rank.",
                    color = Red, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(10)
            }
            if (words.isNotEmpty() && shown.isEmpty()) item {
                Text("No exercise in your list matches \"$query\". Add it below if it's not here yet.", color = Sub, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(shown, key = { it.first }) { (id, n) ->
                val e = Store.exercise(id)!!
                val r = ranks[id]
                val press = rememberPress(0.96f)
                val rowNo = shown.indexOfFirst { it.first == id }
                Row(
                    Modifier.graphicsLayer {
                        val q = quake.value
                        if (q < 1f) {
                            val amp = (1f - q) * (1f - q)
                            translationX = kotlin.math.sin(q * 70f + rowNo * 1.9f) * 26f * density * amp
                            translationY = kotlin.math.cos(q * 55f + rowNo * 2.7f) * 9f * density * amp
                            rotationZ = kotlin.math.sin(q * 60f + rowNo) * 3.5f * amp
                        }
                    }.padding(vertical = 4.dp).pressScale(press).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
                        .pressClick(press) { Nav.go(Route.OneRmTest(id)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExerciseThumb(e, 48.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                        NameText(starredName(e), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        val t = tests[id] ?: 0
                        Text(if (n == 0) "Not completed yet" else "Completed $n time${if (n == 1) "" else "s"}", color = Sub, fontSize = 12.sp, maxLines = 1)
                        if (t > 0) Text("1RM tested $t time${if (t == 1) "" else "s"}", color = Sub, fontSize = 12.sp, maxLines = 1)
                    }
                    // The rank sits in a fixed-width column, so the ⓘ button lines up whatever the rank's name.
                    Box(Modifier.width(66.dp), contentAlignment = Alignment.Center) {
                        if (r != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RankBadge(r.tier, 34.dp)
                            Text(r.tier.label, fontSize = 11.sp, color = Color(r.tier.color), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    // Info: the exercise's own page, as from the Exercises list.
                    Text("ⓘ", color = Blue, fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).background(Card2)
                        .clickable { Nav.go(Route.ExerciseDetail(id)) }.padding(horizontal = 9.dp, vertical = 4.dp))
                    if (n == 0) Text("✕", color = Sub, modifier = Modifier.clickable { OneRm.remove(id); refresh++ }.padding(8.dp))
                }
            }
            item {
                if (list.isNotEmpty() && shown.isEmpty() && words.isEmpty()) Text("No exercises match the filter.", color = Sub, modifier = Modifier.padding(vertical = 8.dp))
                if (list.isEmpty()) Text("No ranked exercises done yet. Add one below, or log a workout first.", color = Sub,
                    modifier = Modifier.padding(vertical = 8.dp))
                GreyButton("+ Add an exercise", Modifier.fillMaxWidth().padding(top = 8.dp), Blue) {
                    Nav.go(Route.Picker("Add to 1 Rep Max", false, only = { OneRm.canTest(it.id, Store.profile.value) }) { ids ->
                        val id = ids.firstOrNull() ?: return@Picker
                        if (OneRm.canTest(id, Store.profile.value)) { OneRm.add(id); refresh++ }
                        else message = "${Store.exercise(id)?.name} can't be ranked for you, so there's nothing to test it against. Pick one with a ★."
                    })
                }
                message?.let { Text(stars(it), color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
    FxOverlay(listFx)
    }
}

@Composable
fun OneRmTestScreen(id: String) {
    val p by Store.profile.collectAsState()
    val workouts by Store.workouts.collectAsState()
    val e = Store.exercise(id) ?: run { closeScreen(); return }
    val context = LocalContext.current
    val timed = Standards.isTimed(id)
    val reps = Standards.isReps(id) || timed // no weight column: reps, or seconds for a hold
    val squeeze = Standards.isFlat(id)
    val best = remember(id) { OneRm.best(id, workouts, p) }
    remember(id) { OneRm.begin(id, OneRm.bestSet(id, workouts, p)); 0 }
    val r = remember(workouts, p) { Ranks.all(workouts, p)[id] }
    val notes = OneRm.notes(id)
    var confirmLeave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val gold = Color(0xFFC9A94E)
    fun fmt(v: Double) = if (reps) Standards.format(id, v) else Units.estU(v)

    // Leaving with a ticked set asks first (the test isn't saved until you finish it).
    // Rank-downs wait until Finish Test.
    androidx.compose.runtime.DisposableEffect(Unit) { Ranks.holdDowns = true; onDispose { if (Ranks.holdDowns) Ranks.dropDowns() } }
    fun leave() { if (OneRm.rows.any { it.done }) confirmLeave = true else { OneRm.clear(); Nav.back() } }
    androidx.activity.compose.BackHandler { leave() }
    // The test screen itself has no transitions or effects: it's a plain working screen.
    val haptic = LocalHapticFeedback.current
    // Opening a test plays the same opening as 1 Rep Max (fire, embers, and the earthquake rattling each panel).
    val fx = remember { FxState() }
    val quake = remember { Animatable(1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(Unit) {
        val entering = OneRmFx.enteringTest
        OneRmFx.enteringTest = false
        if (!entering || !extraAnims()) return@LaunchedEffect
        OneRmFx.entryEffect()?.let { (k, at) -> launch { fx.play(k, haptic, at, density) } }
        OneRmFx.entryQuake()?.let { at ->
            quake.snapTo(0f)
            kotlinx.coroutines.delay(at)
            launch { repeat(4) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); kotlinx.coroutines.delay(150) } }
            quake.animateTo(1f, tween(1100))
        }
    }
    val rattle = { quake.value }
    val heat = rememberHeat()
    val tickScope = androidx.compose.runtime.rememberCoroutineScope()
    val ticks = remember { androidx.compose.runtime.mutableStateListOf<TickFx>() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().fxShake(fx)) {
        TopBar("1RM: ${e.name}", action = "Finish", onBack = { leave() }, extra = { ReferenceButton() }) { finishTest(context, id)?.let { error = it } }
        Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.quakeRattle(rattle, 0), verticalAlignment = Alignment.CenterVertically) {
                // The picture or name opens the exercise's own page (the test carries on when you come back).
                Box(Modifier.clickable { Nav.go(Route.ExerciseDetail(id)) }) { ExerciseThumb(e, 64.dp) }
                Column(Modifier.padding(start = 12.dp)) {
                    NameText(starredName(e), Modifier.clickable { Nav.go(Route.ExerciseDetail(id)) }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(if (r != null) "Now: ${r.tier.label} (${topLabel(r.percentile)}) · ${fmt(r.value)}" else "Not ranked yet", color = Sub, fontSize = 13.sp)
                    Text(e.weightHint(), color = Sub, fontSize = 12.sp)
                    StackChip(e, Modifier.padding(top = 6.dp))
                }
            }

            // Warm-up plan
            Section("Warm-up plan", Blue, Modifier.quakeRattle(rattle, 1)) {
                // Kept to the minimum that does the job: get warm, wake the muscles up, then a few ramp-up sets.
                Bullet("2–3 minutes of easy movement to get warm (a brisk walk, bike or rower).")
                notes?.prep?.firstOrNull { !it.contains(Regex("min\\b|cardio|bike|rowing|row\\b|walk|jog", RegexOption.IGNORE_CASE)) }?.let { Bullet(it) }
                if (squeeze) {
                    Bullet("One practice squeeze with each hand at about half effort.")
                    Bullet("Then 3 all-out squeezes with each hand, resting about a minute before using the same hand again.")
                } else if (timed) {
                    Bullet(best?.let { "One easy hold of about ${(it * 0.35).roundToInt().coerceAtLeast(5)} seconds, then rest 2–3 minutes." }
                        ?: "One easy hold of 10–20 seconds, then rest 2–3 minutes.")
                    Bullet("Then one all-out hold: good form only, stop the clock when your form breaks.")
                } else if (reps) {
                    Bullet(best?.let { "One easy set of about ${(it * 0.35).roundToInt().coerceAtLeast(1)} reps, then rest 2–3 minutes." }
                        ?: "One easy set of a few reps, well short of failure, then rest 2–3 minutes.")
                    Bullet("Then one all-out set: strict reps only, stop at the first rep you can't do with good form.")
                } else if (best != null && !Standards.isOneLeg(id)) {
                    Text("Based on your current best of about ${fmt(best)}:", color = Sub, fontSize = 13.sp)
                    OneRm.PLAN.forEach { (f, n, label) ->
                        Bullet("${Units.weightU(OneRm.loadable(best * f))} × $n  ($label, ${(f * 100).roundToInt()}%)")
                    }
                    Bullet("Optional attempt 3: about ${Units.weightU(OneRm.loadable(best * 1.04))}, only if attempt 2 moved fast and clean.")
                    Bullet("Rest 1–2 minutes between warm-ups and about 3 minutes before each heavy single.")
                } else {
                    Bullet("No previous numbers yet: 5 easy reps, then 3 moderate, then singles in small jumps until the weight slows down a lot.")
                    Bullet("Rest about 3 minutes before each heavy single.")
                }
            }
            notes?.form?.takeIf { it.isNotEmpty() }?.let { f -> Section("Form", Green, Modifier.quakeRattle(rattle, 2)) { f.forEach { Bullet(it) } } }
            Section("Safety", Red, Modifier.quakeRattle(rattle, 3)) {
                notes?.safety?.forEach { Bullet(it) }
                Bullet("Stop straight away for sharp or joint pain, dizziness, or if your form breaks down – a missed or ugly rep doesn't count.")
                Bullet("Don't test when you're injured, ill, or very tired. Leave a few days between max tests on the same lift.")
            }

            // Attempts
            Text(if (squeeze) "Your squeezes" else if (reps) "Your sets" else "Your sets & attempts", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.quakeRattle(rattle, 4))
            Text(if (squeeze) "Enter each reading and tick it. Your best squeeze with either hand counts." else if (reps) "Tick a set when it's done. Your best set counts." else
                "Tick each set when it's done. The 1 Rep Max calculation is more accurate the higher the weight and the lower the max reps you can do with it.",
                color = Sub, fontSize = 13.sp, modifier = Modifier.quakeRattle(rattle, 5))
            Row(Modifier.quakeRattle(rattle, 6)) {
                Text("SET", Modifier.weight(0.6f), color = Sub, fontSize = 12.sp)
                if (!reps) Text(if (squeeze) "SQUEEZE (${Units.w})" else Units.w.uppercase(), Modifier.weight(1.4f), color = Sub, fontSize = 12.sp)
                Text(if (squeeze) "HAND" else if (timed) "SECONDS" else "REPS", Modifier.weight(1.2f), color = Sub, fontSize = 12.sp)
                Text("", Modifier.weight(1.2f))
            }
            // Developer trial: standard vs personal 1 Rep Max estimate, above the next set to do.
            val est = if (p.dev1rm && !squeeze && !reps) remember(workouts, id) { Personal1Rm.estimate(id, workouts, p) } else null
            val next = OneRm.rows.indexOfFirst { !it.done }
            OneRm.rows.forEachIndexed { i, s ->
                if (est != null && i == next) PersonalEstimateCard(est, s.weight, Standards.stackFactor(id))
                val k = "$id:$i:$refresh"
                // Ticking a set plays the effect picked in developer settings (it finishes even if you untick straight away).
                val tfx = remember(k) { TickFx() }
                androidx.compose.runtime.DisposableEffect(tfx) { ticks += tfx; onDispose { ticks -= tfx } }
                var seen by remember(k) { mutableStateOf(s.done) }
                LaunchedEffect(k, s.done) {
                    if (s.done && !seen && extraAnims()) tickScope.launch { OneRmFx.slamBuzz(context); tfx.play(OneRmFx.tick, null, fx) }
                    seen = s.done
                }
                val tickColor by animateColorAsState(if (s.done) Green else Card2, tween(160), label = "tick")
                Row(Modifier.quakeRattle(rattle, 7 + i).tickRow(tfx), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.weight(0.6f), color = Sub)
                    if (!reps) NumField(Units.weight(s.weight), "0", "$k:w", Modifier.weight(1.4f).padding(end = 6.dp), outlined = true) { v ->
                        OneRm.rows[i] = OneRm.rows[i].copy(weight = v.toDoubleOrNull()?.let(Units::fromShown))
                    }
                    if (squeeze) Text(when (Standards.hands(OneRm.rows)[i]) { true -> "Right"; false -> "Left"; null -> "Warm-up" }, Modifier.weight(1.2f), color = Sub)
                    else if (timed) NumField(s.seconds?.toString() ?: "", "0", "$k:s", Modifier.weight(1.2f).padding(end = 6.dp), KeyboardType.Number, outlined = true) { v ->
                        OneRm.rows[i] = OneRm.rows[i].copy(seconds = v.toIntOrNull())
                    }
                    else NumField(s.reps?.toString() ?: "", "0", "$k:r", Modifier.weight(1.2f).padding(end = 6.dp), KeyboardType.Number, outlined = true) { v ->
                        OneRm.rows[i] = OneRm.rows[i].copy(reps = v.toIntOrNull())
                    }
                    Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).tickBox(tfx).clip(RoundedCornerShape(10.dp)).background(tickColor)
                                .clickable {
                                    OneRm.rows[i] = OneRm.rows[i].copy(done = !s.done)
                                    val live = OneRm.liveWorkout(id)
                                    if (!s.done) Ranks.onSetDone(live, id) else Ranks.onSetUndone(context, live, id)
                                }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("✓", fontWeight = FontWeight.Bold) }
                        Text("✕", color = Sub, modifier = Modifier.clickable { OneRm.rows.removeAt(i); refresh++ }.padding(8.dp))
                    }
                }
            }
            GreyButton("+ Add set", Modifier.quakeRattle(rattle, 20).fillMaxWidth(), textColor = Blue) {
                val last = OneRm.rows.lastOrNull()
                OneRm.rows += last?.copy(done = false) ?: DoneSet(reps = if (reps) null else 1)
                refresh++
            }
            error?.let { Text(it, color = Red) }
            // Once a set is in, the button heats up (the Gold buttons style from developer settings).
            val ready = OneRm.rows.any { it.done }
            val finishPress = rememberPress(0.93f)
            Row(
                Modifier.quakeRattle(rattle, 21).pressScale(finishPress).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A0E0E))
                    .then(if (ready) Modifier.heatFire(heat).glint(heat).emberBorder(heat, 2.dp, gold, RoundedCornerShape(12.dp))
                        else Modifier.border(1.5.dp, gold.copy(alpha = 0.7f), RoundedCornerShape(12.dp)))
                    .pressClick(finishPress) { finishTest(context, id)?.let { error = it } }.padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) { Text("FINISH TEST", color = gold, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 1.5.sp) }
            Spacer(300)
        }
    }
    FxOverlay(fx)
    TickOverlay(ticks, OneRmFx.tick)
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Go back without saving?") },
        text = { Text("This test isn't saved yet – your completed sets will be lost. Tap Finish to save it. Are you sure you want to go back?") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; OneRm.clear(); Nav.back() }) { Text("Go back", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep testing") } },
    )
}

/** A 1 Rep Max info card that folds up when you tap its title; open by default, and it stays the way you leave it. */
@Composable
private fun Section(title: String, color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(title !in OneRm.folded()) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open; OneRm.setFolded(title, !open) }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(width = 4.dp, height = 18.dp).background(color))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(if (open) "▾" else "▸", color = color, fontSize = 18.sp)
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Bullet(text: String) = Row {
    Text("•", color = Sub, modifier = Modifier.width(14.dp))
    Text(text, fontSize = 14.sp, lineHeight = 19.sp)
}

/** Saves the test as a one-exercise workout, shows your rank (changed or not), then the chart. Returns an error message, or null. */
private fun finishTest(c: Context, id: String): String? {
    val done = OneRm.rows.filter { it.done }
    val e = Store.exercise(id) ?: return "Exercise not found."
    if (done.isEmpty()) return "Tick at least one completed set first."
    val value = Standards.testValue(id, done)
    if (value <= 0) return if (Standards.isFlat(id)) "Enter your squeeze reading." else if (Standards.isTimed(id)) "Enter how many seconds you held." else if (Standards.isReps(id)) "Enter the reps you did." else "Enter the weight and reps you lifted."
    val p = Store.profile.value
    val w = Workout(
        id = Store.newId(), routineId = null, name = "1RM test: ${e.name}", start = OneRm.startedAt, end = System.currentTimeMillis(),
        items = listOf(WorkoutItem(id, done, 180)), bodyweight = p.bodyweight, test = true,
    )
    Store.saveWorkout(w)
    // Rank-ups already popped up as each set was ticked; any rank-downs show now.
    Ranks.releaseDowns()
    OneRm.clear()
    Nav.back()
    Nav.go(Route.RankDetail(id, afterTest = true))
    return null
}

/** The 1 Rep Max mode's "heat": a glint of light that sweeps across now and then, and a glowing border. */
class Heat(private val sweepState: androidx.compose.runtime.State<Float>, private val emberState: androidx.compose.runtime.State<Float>,
    val glintColor: Color = Color(0x55FFE7A3), val fire: Int = -1, private val timeState: androidx.compose.runtime.State<Float>? = null) {
    val sweep get() = sweepState.value
    val ember get() = emberState.value
    val time get() = timeState?.value ?: 0f
}

/** Real fire inside a gold button (the fire styles), drawn under its text. */
fun Modifier.heatFire(h: Heat) = drawWithContent { buttonFire(h.fire, h.time); drawContent() }

/** 0 glint + ember, 1 ember only, 2 fast glint, 3 none, 4 flame flicker, 5 fire glint (orange), 6 lava pulse. */
@Composable
fun rememberHeat(): Heat {
    val style = if (extraAnimsNow) OneRmFx.shine else 3
    val t = rememberInfiniteTransition(label = "heat")
    val fast = style == 2
    val sweep = t.animateFloat(
        -0.3f, 1.3f,
        infiniteRepeatable(keyframes {
            durationMillis = if (fast) 1400 else 3400
            -0.3f at 0; 1.3f at (if (fast) 550 else 1000) using FastOutSlowInEasing; 1.3f at (if (fast) 1400 else 3400)
        }),
        label = "glint",
    )
    val ember = t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ember")
    val lava = t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lava")
    val off = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    val steady = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.85f) }
    // Flame flicker: the border jumps between bright and dim like a flame.
    val flicker = t.animateFloat(0.4f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 900; 0.5f at 0; 1f at 90; 0.65f at 170; 0.95f at 260; 0.45f at 380; 1f at 470; 0.7f at 600; 0.9f at 720; 0.5f at 900
    }), label = "flicker")
    val time = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "fire")
    return Heat(
        if (style == 0 || style == 2 || style == 5 || style == 10) sweep else off,
        when (style) { 0, 1 -> ember; 4, 5, 7, 8, 10 -> flicker; 6 -> lava; else -> steady },
        if (style == 5 || style == 10) Color(0x77FF7A1A) else Color(0x55FFE7A3),
        style, time,
    )
}

/** A diagonal band of light crossing the surface, like light catching a polished plate. */
fun Modifier.glint(h: Heat) = drawWithContent {
    drawContent()
    val x = h.sweep * (size.width + size.height) - size.height
    drawRect(Brush.linearGradient(
        listOf(Color.Transparent, h.glintColor, Color.Transparent),
        start = Offset(x, 0f), end = Offset(x + size.height * 1.2f, size.height),
    ))
}

/** A border that glows brighter and dimmer with the ember (drawn, so the glow never recomposes the screen). */
fun Modifier.emberBorder(h: Heat, width: androidx.compose.ui.unit.Dp, color: Color, shape: androidx.compose.ui.graphics.Shape) = drawWithContent {
    drawContent()
    val w = width.toPx()
    val outline = shape.createOutline(androidx.compose.ui.geometry.Size(size.width - w, size.height - w), layoutDirection, this)
    translate(w / 2, w / 2) {
        drawOutline(outline, color.copy(alpha = h.ember), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    }
}

/** Developer trial: what the standard formula and the personal estimate each think your 1 Rep Max is. */
@Composable
private fun PersonalEstimateCard(e: Personal1Rm.Estimate, nextWeight: Double?, factor: Double = 1.0) {
    fun w(v: Double) = if (v > 0) Units.estU(v) else "–"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Card).padding(10.dp)) {
        Text("1 Rep Max estimate (developer trial)", color = Sub, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Regular: ${w(e.standard)}", Modifier.weight(1f), fontSize = 15.sp, color = Green, fontWeight = FontWeight.Bold)
            Text("Enhanced: ${w(e.personal)}", Modifier.weight(1f), fontSize = 15.sp, color = Red, fontWeight = FontWeight.Bold)
        }
        // Reps each expects you to get to failure with the weight in the set below (updates as you type).
        val nw = nextWeight?.let { it * factor }
        val fs = nw?.let { Personal1Rm.failureReps(it, e.standard) }
        val fp = nw?.let { Personal1Rm.failureReps(it, e.personal, e.endurance) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reps to failure: ${fs ?: "–"}", Modifier.weight(1f), fontSize = 15.sp, color = Green, fontWeight = FontWeight.Bold)
            Text("Reps to failure: ${fp ?: "–"}", Modifier.weight(1f), fontSize = 15.sp, color = Red, fontWeight = FontWeight.Bold)
        }
        val endurance = when {
            e.fSets == 0 && e.otherSets == 0 -> "no matching sets yet – same as standard"
            e.endurance < 0.995 -> "you get more reps than the formula expects (×${"%.2f".format(e.endurance)})"
            e.endurance > 1.005 -> "you get fewer reps than the formula expects (×${"%.2f".format(e.endurance)})"
            else -> "the formula fits you"
        }
        Text("From ${e.tests} test${if (e.tests == 1) "" else "s"}, ${e.fSets} F set${if (e.fSets == 1) "" else "s"} and ${e.otherSets} other set${if (e.otherSets == 1) "" else "s"}: $endurance" +
            (e.reserve?.let { "; usually ${"%.1f".format(it)} reps left on sets not marked F" } ?: "") + ".", color = Sub, fontSize = 12.sp)
    }
}
