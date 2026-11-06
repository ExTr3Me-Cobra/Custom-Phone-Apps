package com.ironlog.app

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.ui.draw.drawBehind

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Starting, changing and finishing the workout in progress. */
object Workouts {
    /** Bumped when numbers are filled in for you, so the boxes show them. */
    var refresh by androidx.compose.runtime.mutableIntStateOf(0)
    /** [exercises]: for a freestyle workout, the exercises you picked (3 empty sets each). */
    fun start(c: Context, r: Routine?, exercises: List<String> = emptyList()) {
        val current = Store.active.value
        if (current != null) {
            // Already going: just go back to it.
            Nav.go(Route.Active)
            return
        }
        val w = Workout(
            id = Store.newId(),
            routineId = r?.id,
            name = r?.name ?: "Workout",
            start = System.currentTimeMillis(),
            items = r?.items?.map { i ->
                WorkoutItem(i.exerciseId, i.sets.map { p -> DoneSet(p.weight, p.reps, p.seconds, p.distance, p.failure, false, p.warmup) }, i.restSec, i.note)
            } ?: exercises.map { WorkoutItem(it, List(3) { DoneSet() }, Store.profile.value.defaultRest) },
            bodyweight = Store.profile.value.bodyweight,
        )
        Store.setActive(w)
        Rest.stop(c)
        WorkoutService.start(c)
        Nav.go(Route.Active)
    }

    fun update(change: (Workout) -> Workout) {
        touched()
        val w = Store.active.value ?: return
        Store.setActive(change(w))
    }

    /** Idle this long and the time after it isn't counted in the saved duration (the first 5 minutes still are). */
    const val IDLE_MS = 5 * 60 * 1000L

    /**
     * A tap, scroll or swipe anywhere in the app (or typing): ends any idle stretch. Screen off, app in the background
     * or closed all count as idle, because only the time since your last touch matters.
     */
    fun touched(at: Long = System.currentTimeMillis()) {
        val w = Store.active.value ?: return
        val last = if (w.lastTouch > 0) w.lastTouch else w.start
        // Saved at most every 10 s (a gap that short can't be over 5 minutes).
        if (at - last < 10_000) return
        Store.setActive(w.copy(lastTouch = at, idleMs = w.idleMs + (at - last - IDLE_MS).coerceAtLeast(0)))
    }

    /** Stopwatches running in timed sets: set key -> when it started. Kept here so scrolling away doesn't stop them. */
    val timers = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    /** Sets whose stopwatch has already chimed for passing last time. */
    val chimed = mutableSetOf<String>()
    /** Last time's hold for each running stopwatch (seconds), to chime when you pass it. */
    val timerTargets = HashMap<String, Int>()
    /** Ticks while a workout is open, for the stopwatches. */
    var clock by androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())

    /**
     * A set (or, with [set] null, a whole exercise) was removed: stopwatches move with their own set rather than
     * jumping to whichever set slides into its place; the removed one's stopwatch stops.
     */
    fun shiftTimers(wid: String, item: Int, set: Int?) {
        fun parse(k: String): Triple<String, Int, Int>? {
            val p = k.split(":"); if (p.size != 3) return null
            return Triple(p[0], p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
        }
        fun remap(m: MutableMap<String, *>) = m.keys.toList().mapNotNull { k -> parse(k)?.let { k to it } }.filter { it.second.first == wid }
        val moves = remap(timers).mapNotNull { (k, t) ->
            val (_, i, n) = t
            val to = when {
                set == null && i == item -> null
                set == null && i > item -> "$wid:${i - 1}:$n"
                set != null && i == item && n == set -> null
                set != null && i == item && n > set -> "$wid:$i:${n - 1}"
                else -> k
            }
            Triple(k, to, timers[k])
        }
        val targets = HashMap(timerTargets)
        val wasChimed = chimed.toSet()
        moves.forEach { (k, _, _) -> timers.remove(k); timerTargets.remove(k); chimed.remove(k) }
        moves.forEach { (k, to, started) ->
            if (to != null && started != null) {
                timers[to] = started
                targets[k]?.let { timerTargets[to] = it }
                if (k in wasChimed) chimed += to
            }
        }
    }

    /** Checks every running stopwatch: passing last time's hold plays the level-up chime, once per set. */
    fun checkTimers(c: Context, now: Long) {
        for ((k, started) in timers) {
            val target = timerTargets[k] ?: continue
            if (target > 0 && k !in chimed && (now - started) / 1000 > target) { chimed += k; Chime.play(c) }
        }
    }

    fun updateItem(i: Int, change: (WorkoutItem) -> WorkoutItem) = update { w ->
        w.copy(items = w.items.toMutableList().also { if (i in it.indices) it[i] = change(it[i]) })
    }

    fun updateSet(i: Int, n: Int, change: (DoneSet) -> DoneSet) = updateItem(i) { item ->
        item.copy(sets = item.sets.toMutableList().also { if (n in it.indices) it[n] = change(it[n]) })
    }

    fun saveNoteToRoutine(routineId: String?, exerciseId: String, note: String) {
        val r = Store.routines.value.firstOrNull { it.id == routineId } ?: return
        if (r.items.none { it.exerciseId == exerciseId }) return
        Store.saveRoutine(r.copy(items = r.items.map { if (it.exerciseId == exerciseId) it.copy(note = note) else it }))
    }

    /** Ends the workout: keeps finished sets, saves it, and returns its id (null if nothing was done). */
    fun finish(c: Context): String? {
        val now = System.currentTimeMillis()
        touched(now)
        val w = Store.active.value ?: return null
        Rest.stop(c)
        WorkoutService.stop(c)
        timers.clear(); chimed.clear()
        val kept = w.copy(
            // The saved duration is your active time: long idle stretches (beyond 5 minutes each) are left out.
            end = (now - w.idleMs).coerceAtLeast(w.start),
            items = w.items.map { it.copy(sets = it.sets.filter { s -> s.done }) }.filter { it.sets.isNotEmpty() },
        )
        Store.setActive(null)
        if (kept.items.isEmpty()) return null
        Store.saveWorkout(kept)
        // Auto-save (Settings → Backup) after every finished workout.
        AutoSave.soon(c)
        return kept.id
    }

    fun discard(c: Context) {
        // Kept in the Recycle Bin if any set was ticked.
        Store.active.value?.takeIf { w -> w.items.any { i -> i.sets.any { it.done } } }?.let { RecycleBin.discarded(it) }
        Rest.stop(c)
        WorkoutService.stop(c)
        timers.clear(); chimed.clear()
        Store.setActive(null)
    }
}

// ---------------- The workout in progress ----------------

@Composable
fun ActiveWorkoutScreen() {
    val w = Store.active.collectAsState().value
    val context = LocalContext.current
    if (w == null) { closeScreen(); return }
    val workouts by Store.workouts.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis(); Workouts.clock = now
            Workouts.checkTimers(context, now)
            delay(250)
        }
    }
    var restFor by remember { mutableStateOf<Int?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Top bar: calculator, reference weights, Discard, Finish (the phone's back button leaves the workout running).
        var bigMap by remember { mutableStateOf(false) }
        var calc by remember { mutableStateOf(false) }
        // The four buttons spaced evenly across the bar.
        Row(Modifier.fillMaxWidth().background(Card).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CalculatorIcon(Modifier.clip(RoundedCornerShape(8.dp)).clickable { calc = true }.padding(horizontal = 8.dp, vertical = 6.dp))
            ReferenceButton()
            OutlinedLabel("Discard", 17.sp, Modifier.clip(RoundedCornerShape(8.dp)).background(Red).clickable { confirmDiscard = true }
                .padding(horizontal = 14.dp, vertical = 9.dp), bg = Red)
            OutlinedLabel("Finish", 17.sp, Modifier.clip(RoundedCornerShape(8.dp)).background(Blue).clickable { confirmEnd = true }
                .padding(horizontal = 18.dp, vertical = 9.dp), bg = Blue)
        }
        if (calc) Calculator { calc = false }
        // Stats row with the live muscle map (tap it to enlarge).
        val doneSets = w.items.flatMap { it.sets }.filter { it.done }
        val load = remember(w.items) { Stats.muscleLoad(w) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) { Text("Duration", color = Sub, fontSize = 13.sp); Text(Units.clock(now - w.start), color = Blue, fontSize = 17.sp) }
            StatBox("Volume", "${Units.volume(Stats.volume(w))} ${Units.w}", Modifier.weight(1.3f))
            StatBox("Sets", "${doneSets.size}", Modifier.weight(0.8f))
            StatBox("Reps", "${doneSets.sumOf { it.reps ?: 0 }}", Modifier.weight(0.8f))
            Box(Modifier.size(width = 64.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).clickable { bigMap = !bigMap }) {
                BodyMapView(load, Modifier.fillMaxSize())
            }
        }
        if (bigMap) Box(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable { bigMap = false }) {
            BodyMapView(load, Modifier.fillMaxSize())
            if (load.isEmpty()) Text("Tick off a set to see the muscles you've worked", color = Sub, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
        }
        androidx.compose.material3.HorizontalDivider(color = Card2)
        // Lots of room at the bottom so the last sets can scroll up above the keyboard.
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 400.dp)) {
            items(w.items.size) { i ->
                val item = w.items[i]
                val ex = Store.exercise(item.exerciseId)
                var menu by remember { mutableStateOf(false) }
                ExerciseHeader(ex) {
                    Box {
                        Text("•••", color = Sub, fontSize = 18.sp, modifier = Modifier.clickable { menu = true }.padding(8.dp))
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Replace exercise") }, {
                                menu = false
                                Nav.go(Route.Picker("Replace Exercise", false) { ids ->
                                    ids.firstOrNull()?.let { id ->
                                        // A different exercise: its stopwatches stop.
                                        Workouts.timers.keys.filter { it.startsWith("${w.id}:$i:") }.forEach { Workouts.timers.remove(it) }
                                        Workouts.updateItem(i) { it.copy(exerciseId = id) }
                                    }
                                })
                            })
                            DropdownMenuItem({ Text("Remove exercise", color = Red) }, {
                                menu = false
                                Workouts.shiftTimers(w.id, i, null)
                                Workouts.update { ww -> ww.copy(items = ww.items.toMutableList().also { it.removeAt(i) }) }
                            })
                        }
                    }
                }
                // Notes (kept with the routine, so they're there next time).
                var note by remember(w.id, i) { mutableStateOf(item.note) }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).typing()) {
                    if (note.isEmpty()) Text("Add notes here...", color = Sub, fontSize = 17.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        note, { t ->
                            note = t
                            Workouts.updateItem(i) { it.copy(note = t) }
                            Workouts.saveNoteToRoutine(w.routineId, item.exerciseId, t)
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFB0B0B5), fontSize = 17.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { RestLine(item.restSec) { restFor = i } }
                    if (ex != null) StackChip(ex, Modifier.padding(end = 16.dp))
                }
                // Developer trial: 1 Rep Max estimates and reps to failure at the weight in your F set (own line, so the
                // rest timer always fits).
                if (ex != null) DevOneRmBadge(ex.id, item.sets.lastOrNull { it.failure }?.weight, Modifier.fillMaxWidth().padding(end = 16.dp))
                if (ex != null) {
                    val prev = remember(workouts, ex.id) { Stats.previous(ex.id, workouts) }
                    val cols = columnsFor(ex.type, true)
                    // 👑 on the ticked set that set a new best (moves to a later set that beats it). Ranked (★) exercises use
                    // the number they're ranked on; others: est. 1-rep max, most reps, longest time, furthest distance,
                    // or for weight & time the heaviest weight (longer time breaks a tie).
                    val profileNow = Store.profile.collectAsState().value
                    val ranked = Standards.covers(ex.id, profileNow)
                    fun setValue(s: DoneSet): Double = when {
                        !s.done || s.warmup -> 0.0
                        ranked -> Standards.oneSet(ex.id, s, false)
                        else -> when (ex.type) {
                            ExType.WEIGHT -> Stats.e1rm(s.weight, s.reps)
                            ExType.BODYWEIGHT -> (s.reps ?: 0).toDouble()
                            ExType.TIMED -> (s.seconds ?: 0).toDouble()
                            ExType.CARDIO -> s.distance ?: 0.0
                            ExType.WEIGHT_TIME -> if ((s.seconds ?: 0) <= 0) 0.0 else (s.weight ?: 0.0) * 100_000 + (s.seconds ?: 0)
                        }
                    }
                    val bestBefore = remember(workouts, ex.id, w.id, ranked) {
                        workouts.filter { it.id != w.id && it.end != null }.flatMap { o -> o.items.filter { it.exerciseId == ex.id }.flatMap { it.sets } }
                            .maxOfOrNull(::setValue) ?: 0.0
                    }
                    val crown = run {
                        var best = bestBefore; var at = -1
                        item.sets.forEachIndexed { n, s -> val v = setValue(s); if (v > best) { best = v; at = n } }
                        at
                    }
                    TableHeader(cols + ("✓" to 0.8f))
                    item.sets.forEachIndexed { n, s ->
                        ActiveSetRow(ex, i, n, s, prev.getOrNull(n) ?: prev.lastOrNull(), cols, item.restSec, w.id, crown = n == crown)
                    }
                    GreyButton("+ Add Set", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Workouts.updateItem(i) { it.copy(sets = it.sets + (it.sets.lastOrNull()?.copy(done = false) ?: DoneSet())) }
                    }
                }
                Spacer(16)
            }
            item {
                BigButton("+ Add Exercise", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Nav.go(Route.Picker("Add Exercises", true) { ids ->
                        val rest = Store.profile.value.defaultRest
                        Workouts.update { ww -> ww.copy(items = ww.items + ids.map { WorkoutItem(it, List(3) { DoneSet() }, rest) }) }
                    })
                }
                BigButton("Finish Workout", Green, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { confirmEnd = true }
                GreyButton("Discard Workout", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), Red) { confirmDiscard = true }
            }
        }
        RestBar()
    }
    restFor?.let { i ->
        RestPicker(w.items.getOrNull(i)?.restSec ?: 90, { restFor = null }) { sec -> Workouts.updateItem(i) { it.copy(restSec = sec) }; restFor = null }
    }
    if (confirmEnd) {
        val unfinished = w.items.sumOf { it.sets.count { s -> !s.done } }
        ConfirmText(
            if (unfinished > 0) "End the workout? $unfinished set${if (unfinished == 1) "" else "s"} not ticked off won't be saved." else "End the workout and save it?",
            {
                confirmEnd = false
                val id = Workouts.finish(context)
                Nav.home(0)
                if (id != null) Nav.go(Route.Summary(id))
            },
            { confirmEnd = false }, "End Routine",
        )
    }
    if (confirmDiscard) ConfirmText(
        "Throw away this workout? It won't be saved, but if you ticked any sets it waits in the Recycle Bin (Settings) for 30 days.",
        { confirmDiscard = false; Workouts.discard(context); Nav.home(1) }, { confirmDiscard = false }, "Discard",
    )
}

@Composable
private fun ActiveSetRow(ex: Exercise, i: Int, n: Int, s: DoneSet, prev: DoneSet?, cols: List<Pair<String, Float>>, rest: Int, wid: String, crown: Boolean = false) {
    val context = LocalContext.current
    val bg = if (s.done) Green.copy(alpha = 0.15f) else Bg
    val k = "$wid:$i:${ex.id}:$n"
    var typeMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(cols[0].second)) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Card2)
                    .clickable { typeMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                val item = Store.active.value?.items?.getOrNull(i)
                SetLabel(setNumber(item?.sets?.map { it.warmup } ?: emptyList(), n).coerceAtLeast(0), s.failure, Modifier, s.warmup)
            }
            // Grip dynamometer: squeezes take turns, right then left.
            if (Standards.isFlat(ex.id)) Standards.hands(Store.active.value?.items?.getOrNull(i)?.sets.orEmpty()).getOrNull(n)?.let { right ->
                Text(if (right) "R" else "L", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).offset(x = 33.dp, y = 2.dp))
            }
            if (crown) Text("👑", fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = 30.dp, y = (-6).dp))
            // Developer trial: only the last set of an exercise can be F.
            val lastSet = n == (Store.active.value?.items?.getOrNull(i)?.sets?.lastIndex ?: n)
            SetTypeMenu(typeMenu, { typeMenu = false }, s.warmup, s.failure, !Store.profile.value.dev1rm || lastSet, onDelete = {
                Workouts.shiftTimers(wid, i, n)
                Workouts.updateItem(i) { it.copy(sets = it.sets.toMutableList().also { l -> if (n in l.indices) l.removeAt(n) }) }
                Store.active.value?.let { if (s.done) Ranks.onSetUndone(context, it, ex.id) }
            }) { w, f ->
                Workouts.updateSet(i, n) { it.copy(warmup = w, failure = f) }
                // A set that's already ticked changes what counts for ranks.
                Store.active.value?.let { if (s.done) { if (w) Ranks.onSetUndone(context, it, ex.id) else Ranks.onSetDone(it, ex.id) } }
            }
        }
        // Previous: tap to copy it in.
        Text(
            prev?.let { Units.set(ex.type, it) } ?: "-", color = Sub, fontSize = 16.sp, maxLines = 1,
            modifier = Modifier.weight(cols[1].second).clickable(enabled = prev != null) {
                prev?.let { p ->
                    Workouts.updateSet(i, n) { it.copy(weight = p.weight, reps = p.reps, seconds = p.seconds, distance = p.distance) }
                    Workouts.refresh++
                }
            },
        )
        // Refreshes the boxes when numbers are filled in for you (not while you type).
        val vk = "$k:${Workouts.refresh}:${s.done}"
        // A running stopwatch counts up from 0:00 inside the time box.
        val tk = "$wid:$i:$n"
        val running = Workouts.timers[tk]?.let { Units.time(((Workouts.clock - it) / 1000).toInt().coerceAtLeast(0)) }
        when (ex.type) {
            ExType.BODYWEIGHT -> NumField(s.reps?.toString() ?: "", prev?.reps?.toString() ?: if (s.failure) "max" else "0", "$vk:r",
                Modifier.weight(cols[2].second).padding(end = 6.dp), KeyboardType.Number, outlined = true, clearOnFocus = true) { v ->
                Workouts.updateSet(i, n) { it.copy(reps = v.toIntOrNull(), weight = null) }
            }
            ExType.WEIGHT -> {
                NumField(Units.weight(s.weight).takeIf { s.weight != null } ?: "", prev?.weight?.let { Units.weight(it) } ?: "0", "$vk:w",
                    Modifier.weight(cols[2].second).padding(end = 6.dp), outlined = true, clearOnFocus = true) { v ->
                    Workouts.updateSet(i, n) { it.copy(weight = v.toDoubleOrNull()?.let(Units::fromShown)) }
                }
                NumField(s.reps?.toString() ?: "", prev?.reps?.toString() ?: if (s.failure) "max" else "0", "$vk:r",
                    Modifier.weight(cols[3].second).padding(end = 6.dp), KeyboardType.Number, outlined = true, clearOnFocus = true) { v ->
                    Workouts.updateSet(i, n) { it.copy(reps = v.toIntOrNull()) }
                }
            }
            ExType.TIMED -> {
                Row(Modifier.weight(cols[2].second), verticalAlignment = Alignment.CenterVertically) {
                    NumField(Units.time(s.seconds), prev?.seconds?.let { Units.time(it) } ?: "0:30", "$vk:t",
                        Modifier.weight(1f).padding(end = 6.dp), outlined = true, time = true, running = running) { v ->
                        Workouts.updateSet(i, n) { it.copy(seconds = Units.parseTime(v)) }
                    }
                    Stopwatch(tk, prev?.seconds) { sec -> Workouts.updateSet(i, n) { it.copy(seconds = sec) }; Workouts.refresh++ }
                }
            }
            ExType.WEIGHT_TIME -> {
                NumField(Units.weight(s.weight).takeIf { s.weight != null } ?: "", prev?.weight?.let { Units.weight(it) } ?: "0", "$vk:w",
                    Modifier.weight(cols[2].second).padding(end = 6.dp), outlined = true) { v ->
                    Workouts.updateSet(i, n) { it.copy(weight = v.toDoubleOrNull()?.let(Units::fromShown)) }
                }
                Row(Modifier.weight(cols[3].second), verticalAlignment = Alignment.CenterVertically) {
                    NumField(Units.time(s.seconds), prev?.seconds?.let { Units.time(it) } ?: "0:30", "$vk:t",
                        Modifier.weight(1f).padding(end = 6.dp), outlined = true, time = true, running = running) { v ->
                        Workouts.updateSet(i, n) { it.copy(seconds = Units.parseTime(v)) }
                    }
                    Stopwatch(tk, prev?.seconds) { sec -> Workouts.updateSet(i, n) { it.copy(seconds = sec) }; Workouts.refresh++ }
                }
            }
            ExType.CARDIO -> {
                NumField(Units.distance(s.distance), prev?.distance?.let { Units.distance(it) } ?: "0", "$vk:d",
                    Modifier.weight(cols[2].second).padding(end = 6.dp), outlined = true) { v ->
                    Workouts.updateSet(i, n) { it.copy(distance = v.toDoubleOrNull()?.let(Units::distFromShown)) }
                }
                NumField(Units.time(s.seconds), prev?.seconds?.let { Units.time(it) } ?: "0:00", "$vk:t",
                    Modifier.weight(cols[3].second).padding(end = 6.dp), outlined = true, time = true) { v ->
                    Workouts.updateSet(i, n) { it.copy(seconds = Units.parseTime(v)) }
                }
            }
        }
        // Finishing a set sends a small green ring out from the ✓.
        val ring = remember { androidx.compose.animation.core.Animatable(0f) }
        val ringScope = rememberCoroutineScope()
        val setHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        Box(
            Modifier.weight(0.8f).drawBehind {
                val b = ring.value
                if (b > 0f && b < 1f) drawCircle(Green.copy(alpha = 1f - b), radius = size.minDimension / 2 * (1f + b * 1.8f),
                    center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx() * (1.5f - b)))
            }.clip(RoundedCornerShape(10.dp)).background(if (s.done) Green else Card2)
                .clickable(
                    onClick = {
                        if (!s.done) {
                            // Empty boxes take the greyed-out numbers (last time's).
                            Workouts.updateSet(i, n) {
                                it.copy(
                                    weight = it.weight ?: prev?.weight,
                                    reps = it.reps ?: prev?.reps,
                                    seconds = it.seconds ?: prev?.seconds,
                                    distance = it.distance ?: prev?.distance,
                                    done = true,
                                )
                            }
                            Store.active.value?.let { Ranks.onSetDone(it, ex.id) }
                            // Two quick thuds, like a 1 Rep Max set.
                            ringScope.launch {
                                setHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                kotlinx.coroutines.delay(70)
                                setHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            if (extraAnims()) ringScope.launch {
                                ring.snapTo(0f); ring.animateTo(1f, androidx.compose.animation.core.tween(480, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                            }
                            if (rest > 0) Rest.start(context, rest) else Rest.stop(context)
                        } else {
                            Workouts.updateSet(i, n) { it.copy(done = false) }
                            Store.active.value?.let { Ranks.onSetUndone(context, it, ex.id) }
                        }
                    },
                )
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { if (s.done) OutlinedLabel("✓", 18.sp, bg = Green) else Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

/**
 * Start/stop for a timed hold: ▶ starts the count (shown in the time box), ■ stops it and keeps the time. Passing
 * [previous] (last time's hold, seconds) plays the level-up chime once.
 */
@Composable
private fun Stopwatch(key: String, previous: Int?, onStop: (Int) -> Unit) {
    val started = Workouts.timers[key]
    Text(
        if (started != null) "■" else "▶",
        color = if (started != null) Red else Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
            val now = System.currentTimeMillis()
            if (started != null) {
                Workouts.timers.remove(key); Workouts.timerTargets.remove(key)
                onStop(((now - started) / 1000).toInt())
            } else {
                Workouts.chimed.remove(key)
                Workouts.timerTargets[key] = previous ?: 0
                Workouts.timers[key] = now
                Workouts.clock = now
            }
        }.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** The rest countdown along the bottom while a rest is running. */
@Composable
private fun RestBar() {
    val context = LocalContext.current
    val endsAt by Rest.endsAt.collectAsState()
    val total by Rest.total.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) { while (endsAt > 0) { now = System.currentTimeMillis(); delay(200) } }
    if (endsAt <= 0) return
    val left = ((endsAt - now) / 1000).coerceAtLeast(0)
    if (endsAt - now <= 0) { LaunchedEffect(endsAt) { Rest.finished() }; return }
    Column(Modifier.fillMaxWidth().background(Card2).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            Box(Modifier.fillMaxWidth(((endsAt - now) / (total * 1000f)).coerceIn(0f, 1f)).fillMaxHeight().background(Blue))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GreyButton("-15", Modifier.weight(1f)) { Rest.add(context, -15) }
            Text(Units.time(left.toInt()), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f))
            GreyButton("+15", Modifier.weight(1f)) { Rest.add(context, 15) }
            HSpacer(8)
            BigButton("Skip", modifier = Modifier.weight(1f)) { Rest.stop(context) }
        }
    }
}

// ---------------- After the workout ----------------

@Composable
fun SummaryScreen(id: String) {
    val w = Store.workouts.collectAsState().value.firstOrNull { it.id == id } ?: return closeScreen()
    val history by Store.workouts.collectAsState()
    val profile by Store.profile.collectAsState()
    val context = LocalContext.current
    val cheer = androidx.compose.runtime.saveable.rememberSaveable(id) { Cheers.pick(context) }
    val changes = remember(id, history) { Stats.changes(w, history) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Summary", back = false, action = "Done", lead = "Share", onLead = { Nav.go(Route.ShareWorkout(id)) }) { Nav.home(0) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cheer, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
            Text("${w.name} · ${Units.date(w.start)}", color = Sub)
            WorkoutStats(w)
            WorkoutHeatmap(w)
            MuscleBreakdown(w)
            // Compared with last time (best set each time); first-time exercises aren't shown.
            val (up, down, compared) = changes
            if (compared) {
                ChangePanel("What improved", up, "Nothing beat last time today – it happens. Next time.")
                ChangePanel("What got worse", down, "Nothing dropped since last time. 👌")
            }
            RankChanges(w, history, profile)
            BigButton("Done") { Nav.home(0) }
            Spacer(24)
        }
    }
}

/** Duration, volume and sets in one box. */
@Composable
fun WorkoutStats(w: Workout) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
        StatBox("Duration", Units.duration((w.end ?: w.start) - w.start), Modifier.weight(1f))
        StatBox("Volume", "${Units.volume(Stats.volume(w))} ${Units.w}", Modifier.weight(1f))
        StatBox("Sets", "${w.doneSets}", Modifier.weight(1f))
    }
}

/** What improved / what got worse since last time: one card per exercise, each line "was → now". */
@Composable
private fun ChangePanel(title: String, lines: List<Stats.Change>, empty: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (lines.isEmpty()) Text(empty, color = Sub)
    lines.groupBy { it.exercise }.forEach { (name, ls) ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp)) {
            Text(name, color = Blue, fontWeight = FontWeight.Medium)
            ls.forEach { c ->
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append("${c.label}: ${c.was} ")
                    withStyle(androidx.compose.ui.text.SpanStyle(color = if (c.up) Green else Red, fontWeight = FontWeight.Bold)) { append("→") }
                    append(" ${c.now}")
                }, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Only ranks this workout changed: "old → new" (green arrow up, red down), or "New" for a first rank. */
@Composable
private fun RankChanges(w: Workout, history: List<Workout>, profile: Profile) {
    val ranked = w.items.map { it.exerciseId }.distinct().filter { Standards.covers(it, profile) }
    if (ranked.isEmpty()) return
    if (profile.bodyweight == null) {
        Text("Your ranks", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Add your body weight (and age) in Settings to see how you rank against other lifters.", color = Sub)
        GreyButton("Open Profile") { Nav.home(2) }
        return
    }
    val rows = remember(w.id, history, profile) {
        val before = Ranks.all(history.filter { it.id != w.id && it.start < w.start }, profile)
        val after = Ranks.all(history.filter { it.id == w.id || it.start < w.start }, profile)
        ranked.mapNotNull { id ->
            val a = after[id] ?: return@mapNotNull null
            val b = before[id]
            if (b != null && b.tier == a.tier) null else Triple(a.exercise, b?.tier, a.tier)
        }
    }
    if (rows.isEmpty()) return
    Text("Rank changes", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    rows.forEach { (e, from, to) ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(to, 44.dp)
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                NameText(starredName(e), color = Blue, fontWeight = FontWeight.Medium)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    if (from == null) {
                        append("New: ")
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color(to.color), fontWeight = FontWeight.Bold)) { append(to.label) }
                    } else {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color(from.color), fontWeight = FontWeight.Bold)) { append(from.label) }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = if (to.ordinal > from.ordinal) Green else Red, fontWeight = FontWeight.Bold)) { append("  →  ") }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color(to.color), fontWeight = FontWeight.Bold)) { append(to.label) }
                    }
                })
            }
        }
    }
}

/** Every individual muscle's share of the workout (adds up to 100%), each exercise counting the same. */
@Composable
fun MuscleBreakdown(w: Workout) {
    val shares = remember(w.id, w.items) { Stats.muscleShares(w) }
    if (shares.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp)) {
        Text("Muscle breakdown", fontWeight = FontWeight.Bold)
        val top = shares.first().second.coerceAtLeast(1)
        shares.forEach { (m, pct) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Muscles.label(m), Modifier.weight(1f), fontSize = 14.sp)
                Box(Modifier.size(width = 70.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Card2)) {
                    Box(Modifier.fillMaxWidth(pct.toFloat() / top).fillMaxHeight().background(Color(BodyMap.heat(pct.toFloat() / top))))
                }
                Text("$pct%", Modifier.size(width = 48.dp, height = 20.dp), textAlign = TextAlign.End, fontSize = 14.sp)
            }
        }
        Text("Each muscle's share of the whole workout. Every exercise counts the same, however many sets.",
            color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun WorkoutHeatmap(w: Workout) = Heatmap(remember(w.id, w.end, w.items) { Stats.muscleLoad(w) }, "Muscles worked")

/** Front and back body picture shaded by how hard each muscle worked, with a key. */
@Composable
fun Heatmap(load: Map<String, Float>, title: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        BodyMapView(load, Modifier.fillMaxWidth().height(340.dp))
        HeatKey()
    }
}

/** The colour key under a muscle-work heatmap: not worked → hardest. */
@Composable
fun HeatKey(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("Not worked", color = Sub, fontSize = 12.sp)
        HSpacer(6)
        (0..8).forEach { i ->
            Box(Modifier.height(12.dp).fillMaxWidth(0.035f).background(Color(BodyMap.heat(i / 8f))))
        }
        HSpacer(6)
        Text("Hardest", color = Sub, fontSize = 12.sp)
    }
}
