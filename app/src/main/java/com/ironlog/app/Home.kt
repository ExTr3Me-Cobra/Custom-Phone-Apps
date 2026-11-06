package com.ironlog.app

import androidx.compose.ui.text.style.TextAlign


import androidx.compose.foundation.layout.aspectRatio

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.border

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen() {
    val workouts by Store.workouts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Home", back = false, lead = "?", onLead = { Nav.go(Route.RankHelp) }, action = "Share") { Nav.go(Route.Share) }
        LazyColumn(Modifier.fillMaxSize(), homeList, contentPadding = PaddingValues(bottom = 24.dp)) {
            item { HomeRankCard() }
            item {
                val profile by Store.profile.collectAsState()
                val range = profile.homeRange
                val since = when (range) {
                    "all" -> Long.MIN_VALUE
                    "today" -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    else -> System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                }
                val shown = workouts.filter { it.start >= since }
                var calendar by remember { mutableStateOf(false) }
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Tap to switch between today, this week and all time (remembered).
                        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Card2)) {
                            listOf("today" to "Today", "week" to "This week", "all" to "All time").forEach { (v, label) ->
                                val m = Modifier.clip(RoundedCornerShape(16.dp)).background(if (range == v) Blue else Color.Transparent)
                                    .clickable { Store.setProfile(profile.copy(homeRange = v)) }.padding(horizontal = 12.dp, vertical = 6.dp)
                                if (range == v) OutlinedLabel(label, 13.sp, m, FontWeight.Normal, bg = Blue) else Text(label, fontSize = 13.sp, color = Sub, modifier = m)
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text(if (calendar) "📅 ▴" else "📅 ▾", fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { calendar = !calendar }.padding(6.dp))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    Row {
                        StatBox("Workouts", "${shown.size}", Modifier.weight(1f))
                        StatBox("Time", Units.duration(shown.sumOf { (it.end ?: it.start) - it.start }), Modifier.weight(1f))
                        StatBox("Volume", "${Units.volume(shown.sumOf { Stats.volume(it) })} ${Units.w}", Modifier.weight(1f))
                    }
                    Reveal(calendar) { WorkoutCalendar(workouts) }
                }
                val exerciseCount = Store.exercises.collectAsState().value.values.count { !it.hidden }
                GreyButton("💪  Exercises ($exerciseCount)", Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Nav.go(Route.Library) }
                if (workouts.isEmpty()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Welcome to Loadout 💪", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(6)
                        Text("Build a routine on the Workout tab, start it, and log your sets. Your workouts show up here.", color = Sub)
                        Spacer(12)
                        BigButton("Go to Workout") { Nav.home(1) }
                    }
                } else {
                    // Tap to show your 10 most recent workouts (closed each time the app starts).
                    var open by homeWorkoutsOpen
                    // Styled like the Exercises button, with an arrow showing it opens.
                    DropDownButton("✅  Completed Workouts (${workouts.size})", open, Modifier.padding(16.dp, 10.dp, 16.dp, 6.dp)) { open = !open }
                }
            }
            if (homeWorkoutsOpen.value) {
                items(workouts.sortedByDescending { it.start }.take(10), key = { it.id }) { w -> Box(Modifier.animateItem()) { WorkoutCard(w) } }
                item(key = "seeAll") {
                    GreyButton("See All Workouts", Modifier.padding(16.dp, 8.dp).fillMaxWidth()) { Nav.go(Route.AllWorkouts) }
                }
            }
        }
    }
}

/** Home's Workouts drop-down: starts closed every time the app opens, and stays as you left it while it's open. */
private val homeWorkoutsOpen = mutableStateOf(false)
/** Home's scroll position, kept while you open an exercise or workout and come back. */
private val homeList = androidx.compose.foundation.lazy.LazyListState()

@Composable
fun WorkoutCard(w: Workout) {
    val press = rememberPress(0.97f)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).pressScale(press).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)
            .pressClick(press) { Nav.go(Route.WorkoutDetail(w.id)) }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(Units.date(w.start), color = Sub, fontSize = 13.sp)
            }
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                BodyMapView(remember(w.id) { Stats.muscleLoad(w) }, Modifier.fillMaxSize())
            }
        }
        Spacer(8)
        Row {
            StatBox("Time", Units.duration((w.end ?: w.start) - w.start), Modifier.weight(1f))
            StatBox("Volume", "${Units.volume(Stats.volume(w))} ${Units.w}", Modifier.weight(1f))
            StatBox("Sets", "${w.doneSets}", Modifier.weight(1f))
        }
        Spacer(8)
        w.items.take(4).forEach { i ->
            Text("${i.sets.size} × ${Store.exercise(i.exerciseId)?.name ?: "?"}", color = Sub, fontSize = 14.sp, maxLines = 1)
        }
        if (w.items.size > 4) Text("+ ${w.items.size - 4} more", color = Sub, fontSize = 14.sp)
    }
}

@Composable
fun WorkoutDetailScreen(id: String) {
    val w = Store.workouts.collectAsState().value.firstOrNull { it.id == id } ?: return closeScreen()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box {
            TopBar("Workout", action = "•••") { menu = true }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp)) {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Save as routine") }, {
                        menu = false
                        val r = Routine(Store.newId(), w.name, w.items.map { i ->
                            RoutineItem(i.exerciseId, i.sets.map { PlanSet(it.reps, it.weight, it.seconds, it.distance, it.failure, it.warmup) }, i.restSec)
                        })
                        Store.saveRoutine(r)
                        Nav.go(Route.RoutineView(r.id))
                    })
                    DropdownMenuItem({ Text("Delete workout", color = Red) }, { menu = false; deleting = true })
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(w.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(Units.date(w.start), color = Sub)
            Spacer(10)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
                StatBox("Duration", Units.duration((w.end ?: w.start) - w.start), Modifier.weight(1f))
                StatBox("Volume", "${Units.volume(Stats.volume(w))} ${Units.w}", Modifier.weight(1f))
                StatBox("Sets", "${w.doneSets}", Modifier.weight(1f))
            }
            Spacer(10)
            WorkoutHeatmap(w)
            Spacer(10)
            w.items.forEach { item ->
                val ex = Store.exercise(item.exerciseId)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp).clickable {
                    ex?.let { Nav.go(Route.ExerciseDetail(it.id)) }
                }) {
                    ExerciseThumb(ex, 44.dp)
                    HSpacer(12)
                    NameText(starredName(ex), color = Blue, fontSize = 17.sp)
                }
                item.sets.forEachIndexed { n, s ->
                    Row(Modifier.fillMaxWidth().background(if (n % 2 == 1) Card else Bg).padding(horizontal = 8.dp, vertical = 8.dp)) {
                        SetLabel(setNumber(item.sets.map { it.warmup }, n), s.failure, Modifier.height(22.dp).padding(end = 24.dp), s.warmup)
                        Text(ex?.let { Units.set(it.type, s) } ?: "")
                    }
                }
            }
            Spacer(30)
        }
    }
    if (deleting) ConfirmText("Delete this workout from your history? It goes to the Recycle Bin (Settings) for 30 days.", {
        deleting = false; Store.deleteWorkout(w.id); Nav.back()
    }, { deleting = false }, "Delete")
}


/** A month calendar; days with a logged workout are highlighted blue. */
@Composable
fun WorkoutCalendar(workouts: List<Workout>) {
    val zone = java.time.ZoneId.systemDefault()
    val days = remember(workouts) {
        workouts.map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSet()
    }
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    val today = java.time.LocalDate.now()
    Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 12.dp))
            Text(
                month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + month.year,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text("›", fontSize = 26.sp, modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(it, color = Sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val first = month.atDay(1)
        val blanks = first.dayOfWeek.value % 7 // Sunday first
        val cells = blanks + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val n = week * 7 + d - blanks + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val date = month.atDay(n)
                            val logged = date in days
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape)
                                    .background(if (logged) Blue else Color.Transparent)
                                    .then(if (date == today && !logged) Modifier.border(1.dp, Blue, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (logged) OutlinedLabel("$n", 14.sp, bg = Blue) else Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        val count = days.count { java.time.YearMonth.from(it) == month }
        Text("$count workout day${if (count == 1) "" else "s"} this month", color = Sub, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally))
    }
}
