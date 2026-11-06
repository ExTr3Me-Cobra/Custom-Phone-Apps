package com.ironlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The routine being edited (kept here while you pick exercises). */
object Drafts {
    var routine by mutableStateOf<Routine?>(null)

    fun openEditor(id: String?) {
        routine = Store.routines.value.firstOrNull { it.id == id } ?: Routine(Store.newId(), "", emptyList())
        Nav.go(Route.RoutineEdit(id))
    }
}

val REST_CHOICES = listOf(0, 15, 30, 45, 60, 75, 90, 105, 120, 150, 180, 210, 240, 300, 360, 420, 480, 600)

// ---------------- Workout tab ----------------

@Composable
fun WorkoutTab() {
    val routines by Store.routines.collectAsState()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var aiFor by remember { mutableStateOf<Routine?>(null) }
    // Hold a routine, then drag: the list shrinks to just names while you move it.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rowH = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var listTop by remember { mutableStateOf(0f) }
    var released by remember { mutableStateOf(0L) }
    fun dragTo(): Int = ((dragY - listTop) / rowH).toInt().coerceIn(0, Store.routines.value.lastIndex)
    fun dragModifier(i: Int) = Modifier.pointerInput(i, routines.size) {
        detectDragGesturesAfterLongPress(
            onDragStart = { o ->
                val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i + 1 }?.offset ?: return@detectDragGesturesAfterLongPress
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                dragY = top + o.y
                listTop = (dragY - rowH * i - rowH / 2).coerceAtLeast(0f)
                dragFrom = i
            },
            onDrag = { change, d -> change.consume(); dragY += d.y },
            onDragEnd = {
                dragFrom?.let { from ->
                    val to = dragTo()
                    if (to != from) Store.setRoutines(Store.routines.value.toMutableList().also { l -> l.add(to, l.removeAt(from)) })
                }
                dragFrom = null; released = System.currentTimeMillis()
            },
            onDragCancel = { dragFrom = null; released = System.currentTimeMillis() },
        )
    }
    fun clicksOk() = dragFrom == null && System.currentTimeMillis() - released > 400
    Column(Modifier.fillMaxSize()) {
        TopBar("Workout", back = false)
      Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                if (Standards.mode(Store.profile.value) != Standards.Mode.OFF)
                    OneRmButton(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                if (Store.profile.value.devResearch) ResearchButton(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                SectionTitle("Quick Start")
                GreyButton("+  Start Empty Freestyle Workout", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // Pick the exercises first; the workout (and its timer) starts when you tap Start.
                    if (Store.active.value != null) Workouts.start(context, null)
                    else Nav.go(Route.Picker("Choose exercises", true, "Start workout") { ids -> Workouts.start(context, null, ids) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Routines (${routines.size})", Modifier.weight(1f))
                    Text("+ New Routine", color = Blue, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { Drafts.openEditor(null) }.padding(16.dp))
                }
                if (routines.isEmpty()) Text(
                    "Build your first routine: pick exercises, sets, failure sets and rest timers, then start it any time.",
                    color = Sub, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(routines.size, key = { routines[it].id }) { i ->
                val r = routines[i]
                var menu by remember { mutableStateOf(false) }
                Column(
                    dragModifier(i).padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Card).clickable { if (clicksOk()) Nav.go(Route.RoutineView(r.id)) }.padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name.ifBlank { "Routine" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Box {
                            Text("•••", color = Sub, modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp))
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Edit") }, { menu = false; Drafts.openEditor(r.id) })
                                DropdownMenuItem({ Text("Duplicate") }, {
                                    menu = false
                                    Store.saveRoutine(r.copy(id = Store.newId(), name = r.name + " (copy)"))
                                })
                                if (Store.profile.value.devAi) DropdownMenuItem({ Text("✨ AI pictures") }, { menu = false; aiFor = r })
                                DropdownMenuItem({ Text("Delete", color = Red) }, { menu = false; deleting = r })
                            }
                        }
                    }
                    Text(
                        r.items.joinToString(", ") { Store.exercise(it.exerciseId)?.name ?: "?" },
                        color = Sub, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    )
                    Spacer(12)
                    BigButton("Start Routine") { if (clicksOk()) Workouts.start(context, r) }
                }
            }
            if (routines.size > 1) item {
                Text("Tip: hold a routine and drag to reorder.", color = Sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        dragFrom?.let { from ->
            val to = dragTo()
            val order = routines.indices.toMutableList().also { it.add(to, it.removeAt(from)) }
            Box(Modifier.matchParentSize().background(Bg)) {
                order.forEachIndexed { pos, idx ->
                    val held = idx == from
                    val y = if (held) dragY - rowH / 2 else listTop + pos * rowH
                    Row(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(0, y.toInt()) }.fillMaxWidth().height(56.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (held) Blue.copy(alpha = 0.35f) else Card).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("≡", color = Sub, fontSize = 20.sp)
                        HSpacer(12)
                        Text(routines[idx].name.ifBlank { "Routine" }, fontSize = 16.sp,
                            fontWeight = if (held) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
      }
    }
    aiFor?.let { r -> GenDialog("AI pictures: ${r.name}", r.items.map { it.exerciseId }.distinct()) { aiFor = null } }
    deleting?.let { r ->
        ConfirmText("Delete \"${r.name}\"? Your logged workouts stay. It goes to the Recycle Bin (Settings) for 30 days.", { Store.deleteRoutine(r.id); deleting = null }, { deleting = null }, "Delete")
    }
}

// ---------------- Routine (read-only, like the reference) ----------------

@Composable
fun RoutineViewScreen(id: String) {
    val routines by Store.routines.collectAsState()
    val r = routines.firstOrNull { it.id == id } ?: return closeScreen()
    val context = LocalContext.current
    var ai by remember { mutableStateOf(false) }
    if (ai) GenDialog("AI pictures: ${r.name}", r.items.map { it.exerciseId }.distinct()) { ai = false }
    Column(Modifier.fillMaxSize()) {
        TopBar("Routine", action = "Edit") { Drafts.openEditor(r.id) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                Text(r.name.ifBlank { "Routine" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                BigButton("Start Routine", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Workouts.start(context, r) }
                Spacer(8)
                if (Store.profile.collectAsState().value.devAi) {
                    GreyButton("✨ Make AI pictures for this routine", Modifier.fillMaxWidth().padding(horizontal = 16.dp), Blue) { ai = true }
                    GenPanel()
                }
            }
            items(r.items.size) { i ->
                val item = r.items[i]
                val ex = Store.exercise(item.exerciseId)
                ExerciseHeader(ex)
                if (item.note.isNotBlank()) Text(item.note, color = Sub, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                RestLine(item.restSec, null)
                // Developer trial: on its own line so the rest timer always fits.
                if (ex != null) DevOneRmBadge(ex.id, item.sets.lastOrNull { it.failure }?.weight, Modifier.fillMaxWidth().padding(end = 16.dp))
                if (ex != null) {
                    TableHeader(columnsFor(ex.type, false))
                    item.sets.forEachIndexed { n, s -> PlanRowView(ex.type, n, s) }
                }
                Spacer(12)
            }
        }
    }
}

@Composable
fun ExerciseHeader(ex: Exercise?, modifier: Modifier = Modifier, noClick: () -> Boolean = { false }, trailing: (@Composable () -> Unit)? = null) {
    fun open() { if (!noClick()) ex?.let { Nav.go(Route.ExerciseDetail(it.id)) } }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable { open() }) { ExerciseThumb(ex, 48.dp) }
        HSpacer(14)
        Column(Modifier.weight(1f).clickable { open() }) {
            NameText(starredName(ex), color = Blue, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (ex?.custom == true) { Spacer(4); Chip("Custom") }
        }
        trailing?.invoke()
    }
}

@Composable
fun RestLine(sec: Int, onClick: (() -> Unit)?) {
    Text(
        "⏱  Rest Timer: ${Units.restLabel(sec)}", color = Blue, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
fun SetLabel(n: Int, failure: Boolean, modifier: Modifier, warmup: Boolean = false) = Text(
    buildAnnotatedString {
        append(when { warmup -> "W"; failure -> "F"; else -> "${n + 1}" })
    },
    color = when { warmup -> Orange; failure -> Red; else -> Color.White }, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = modifier,
)

val Orange = Color(0xFFFF9F0A)

/** Working-set number: warm-ups (W) aren't counted, so the first working set is 1. */
fun setNumber(warmups: List<Boolean>, n: Int) = warmups.take(n + 1).count { !it } - 1

/** Tap a set number: choose Warm-up (W), Failure (F) or a normal set, or delete the set. */
@Composable
fun SetTypeMenu(open: Boolean, onDismiss: () -> Unit, warmup: Boolean, failure: Boolean, allowFailure: Boolean = true, onDelete: (() -> Unit)? = null, onChoose: (warmup: Boolean, failure: Boolean) -> Unit) {
    androidx.compose.material3.DropdownMenu(open, onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            { Text(buildAnnotatedString { withStyle(SpanStyle(color = Orange, fontWeight = FontWeight.Bold)) { append("W  ") }; append("Warm-up set") }) },
            { onDismiss(); onChoose(!warmup, false) },
            trailingIcon = { if (warmup) Text("✓") },
        )
        if (allowFailure || failure) androidx.compose.material3.DropdownMenuItem(
            { Text(buildAnnotatedString { withStyle(SpanStyle(color = Red, fontWeight = FontWeight.Bold)) { append("F  ") }; append("Failure set") }) },
            { onDismiss(); onChoose(false, !failure) },
            trailingIcon = { if (failure) Text("✓") },
        )
        if (warmup || failure) androidx.compose.material3.DropdownMenuItem({ Text("Normal set") }, { onDismiss(); onChoose(false, false) })
        if (onDelete != null) androidx.compose.material3.DropdownMenuItem({ Text("Delete set", color = Red, fontWeight = FontWeight.Bold) }, { onDismiss(); onDelete() })
    }
}

@Composable
private fun PlanRowView(t: ExType, n: Int, s: PlanSet) {
    val cols = columnsFor(t, false)
    Row(
        Modifier.fillMaxWidth().background(if (n % 2 == 1) Card else Bg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetLabel(n, s.failure, Modifier.weight(cols[0].second), s.warmup)
        val values = when (t) {
            ExType.WEIGHT -> listOf(Units.weight(s.weight).ifEmpty { "-" }, s.reps?.toString() ?: if (s.failure) "max" else "-")
            ExType.BODYWEIGHT -> listOf(s.reps?.toString() ?: if (s.failure) "max" else "-")
            ExType.TIMED -> listOf(Units.time(s.seconds).ifEmpty { "-" })
            ExType.CARDIO -> listOf(Units.distance(s.distance).ifEmpty { "-" }, Units.time(s.seconds).ifEmpty { "-" })
            ExType.WEIGHT_TIME -> listOf(Units.weight(s.weight).ifEmpty { "-" }, Units.time(s.seconds).ifEmpty { "-" })
        }
        values.forEachIndexed { i, v -> Text(v, fontSize = 17.sp, modifier = Modifier.weight(cols[i + 1].second)) }
    }
}

// ---------------- Routine editor ----------------

@Composable
fun RoutineEditScreen(id: String?) {
    val draft = Drafts.routine ?: return closeScreen()
    var restFor by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(change: (Routine) -> Routine) { Drafts.routine = change(Drafts.routine ?: draft) }
    fun updateItem(i: Int, change: (RoutineItem) -> RoutineItem) =
        update { r -> r.copy(items = r.items.toMutableList().also { it[i] = change(it[i]) }) }

    // Hold an exercise's header, then drag: the list shrinks to just names while you move it.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rowH = with(density) { 56.dp.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }   // finger, in the list's coordinates
    var listTop by remember { mutableStateOf(0f) } // where the short list starts
    var released by remember { mutableStateOf(0L) }
    fun dragTo(): Int = ((dragY - listTop) / rowH).toInt().coerceIn(0, (Drafts.routine ?: draft).items.lastIndex)
    fun dragModifier(i: Int) = Modifier.pointerInput(i, draft.items.size) {
        detectDragGesturesAfterLongPress(
            onDragStart = { o ->
                val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i + 1 }?.offset ?: return@detectDragGesturesAfterLongPress
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                dragY = top + o.y
                listTop = (dragY - rowH * i - rowH / 2).coerceAtLeast(0f)
                dragFrom = i
            },
            onDrag = { change, d -> change.consume(); dragY += d.y },
            onDragEnd = {
                dragFrom?.let { from ->
                    val to = dragTo()
                    if (to != from) update { r -> r.copy(items = r.items.toMutableList().also { l -> l.add(to, l.removeAt(from)) }) }
                }
                dragFrom = null; released = System.currentTimeMillis()
            },
            onDragCancel = { dragFrom = null; released = System.currentTimeMillis() },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (id == null) "New Routine" else "Edit Routine", action = "Save") {
            val r = Drafts.routine ?: return@TopBar
            when {
                r.name.isBlank() -> error = "Give the routine a name."
                r.items.isEmpty() -> error = "Add at least one exercise."
                else -> { Store.saveRoutine(r); Drafts.routine = null; Nav.back() }
            }
        }
        // Lots of room at the bottom so the last sets can scroll up above the keyboard.
      Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 400.dp)) {
            item {
                if (draft.items.size > 1) Text("Tip: hold an exercise's name and drag to reorder.", color = Sub, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                OutlinedTextField(
                    draft.name, { n -> update { it.copy(name = n) } },
                    Modifier.fillMaxWidth().padding(16.dp).typing(), label = { Text("Routine name") }, singleLine = true,
                )
                error?.let { Text(it, color = Red, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(draft.items.size, key = { "${draft.items[it].exerciseId}#$it" }) { i ->
                val item = draft.items[i]
                val ex = Store.exercise(item.exerciseId)
                var menu by remember { mutableStateOf(false) }
                ExerciseHeader(ex, dragModifier(i), noClick = { dragFrom != null || System.currentTimeMillis() - released < 400 }) {
                    Box {
                        Text("•••", color = Sub, fontSize = 18.sp, modifier = Modifier.clickable { menu = true }.padding(8.dp))
                        DropdownMenu(menu, { menu = false }) {
                            if (i > 0) DropdownMenuItem({ Text("Move up") }, {
                                menu = false
                                update { r -> r.copy(items = r.items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }
                            })
                            if (i < draft.items.lastIndex) DropdownMenuItem({ Text("Move down") }, {
                                menu = false
                                update { r -> r.copy(items = r.items.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }
                            })
                            DropdownMenuItem({ Text("Replace exercise") }, {
                                menu = false
                                Nav.go(Route.Picker("Replace Exercise", false) { ids ->
                                    ids.firstOrNull()?.let { newId -> updateItem(i) { it.copy(exerciseId = newId) } }
                                })
                            })
                            DropdownMenuItem({ Text("Remove", color = Red) }, {
                                menu = false
                                update { r -> r.copy(items = r.items.toMutableList().also { it.removeAt(i) }) }
                            })
                        }
                    }
                }
                OutlinedTextField(
                    item.note, { t -> updateItem(i) { it.copy(note = t) } },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).typing(), placeholder = { Text("Add notes here...") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { RestLine(item.restSec) { restFor = i } }
                    if (ex != null) StackChip(ex, Modifier.padding(end = 16.dp))
                }
                if (ex != null) {
                    val cols = columnsFor(ex.type, false)
                    TableHeader(cols + ("" to 0.5f))
                    item.sets.forEachIndexed { n, s ->
                        Row(
                            Modifier.fillMaxWidth().background(if (n % 2 == 1) Card else Bg).padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(cols[0].second)) {
                                var menu by remember { mutableStateOf(false) }
                                SetLabel(setNumber(item.sets.map { it.warmup }, n), s.failure, Modifier.clickable { menu = true }.padding(vertical = 6.dp, horizontal = 4.dp), s.warmup)
                                SetTypeMenu(menu, { menu = false }, s.warmup, s.failure, !Store.profile.value.dev1rm || n == item.sets.lastIndex,
                                    onDelete = { updateItem(i) { it.copy(sets = it.sets.toMutableList().also { l -> l.removeAt(n) }) } }) { w, f ->
                                    updateItem(i) { it.copy(sets = it.sets.toMutableList().also { l -> l[n] = l[n].copy(warmup = w, failure = f) }) }
                                }
                            }
                            fun setAt(change: (PlanSet) -> PlanSet) =
                                updateItem(i) { it.copy(sets = it.sets.toMutableList().also { l -> l[n] = change(l[n]) }) }
                            val k = "${draft.id}:$i:$n:${item.exerciseId}:${item.sets.size}"
                            when (ex.type) {
                                ExType.BODYWEIGHT -> NumField(s.reps?.toString() ?: "", if (s.failure) "max" else "-", "$k:r", Modifier.weight(cols[1].second), KeyboardType.Number) { v ->
                                    setAt { it.copy(reps = v.toIntOrNull(), weight = null) }
                                }
                                ExType.WEIGHT -> {
                                    NumField(Units.weight(s.weight), "-", "$k:w", Modifier.weight(cols[1].second)) { v ->
                                        setAt { it.copy(weight = v.toDoubleOrNull()?.let(Units::fromShown)) }
                                    }
                                    NumField(s.reps?.toString() ?: "", if (s.failure) "max" else "-", "$k:r", Modifier.weight(cols[2].second), KeyboardType.Number) { v ->
                                        setAt { it.copy(reps = v.toIntOrNull()) }
                                    }
                                }
                                ExType.TIMED -> NumField(Units.time(s.seconds), "0:30", "$k:t", Modifier.weight(cols[1].second), time = true) { v ->
                                    setAt { it.copy(seconds = Units.parseTime(v)) }
                                }
                                ExType.WEIGHT_TIME -> {
                                    NumField(Units.weight(s.weight), "-", "$k:w", Modifier.weight(cols[1].second)) { v ->
                                        setAt { it.copy(weight = v.toDoubleOrNull()?.let(Units::fromShown)) }
                                    }
                                    NumField(Units.time(s.seconds), "0:30", "$k:t", Modifier.weight(cols[2].second), time = true) { v ->
                                        setAt { it.copy(seconds = Units.parseTime(v)) }
                                    }
                                }
                                ExType.CARDIO -> {
                                    NumField(Units.distance(s.distance), "-", "$k:d", Modifier.weight(cols[1].second)) { v ->
                                        setAt { it.copy(distance = v.toDoubleOrNull()?.let(Units::distFromShown)) }
                                    }
                                    NumField(Units.time(s.seconds), "-", "$k:t", Modifier.weight(cols[2].second), time = true) { v ->
                                        setAt { it.copy(seconds = Units.parseTime(v)) }
                                    }
                                }
                            }
                            Text("✕", color = Sub, modifier = Modifier.weight(0.5f).clickable {
                                updateItem(i) { it.copy(sets = it.sets.toMutableList().also { l -> l.removeAt(n) }) }
                            }.padding(6.dp))
                        }
                    }
                    GreyButton("+ Add Set", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        updateItem(i) { it.copy(sets = it.sets + (it.sets.lastOrNull()?.copy() ?: PlanSet())) }
                    }
                }
                Spacer(14)
            }
            item {
                BigButton("+ Add Exercise", modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Nav.go(Route.Picker("Add Exercises", true) { ids ->
                        val rest = Store.profile.value.defaultRest
                        update { r -> r.copy(items = r.items + ids.map { RoutineItem(it, List(3) { PlanSet() }, rest) }) }
                    })
                }
            }
        }
        // While dragging: every exercise as a short row; the held one follows your finger.
        dragFrom?.let { from ->
            val to = dragTo()
            val order = draft.items.indices.toMutableList().also { it.add(to, it.removeAt(from)) }
            Box(Modifier.matchParentSize().background(Bg)) {
                order.forEachIndexed { pos, idx ->
                    val held = idx == from
                    val y = if (held) dragY - rowH / 2 else listTop + pos * rowH
                    Row(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(0, y.toInt()) }.fillMaxWidth().height(56.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (held) Blue.copy(alpha = 0.35f) else Card).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("≡", color = Sub, fontSize = 20.sp)
                        HSpacer(12)
                        Text(Store.exercise(draft.items[idx].exerciseId)?.name ?: "Missing exercise", fontSize = 16.sp,
                            fontWeight = if (held) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
      }
    }
    restFor?.let { i ->
        RestPicker(draft.items.getOrNull(i)?.restSec ?: 90, { restFor = null }) { sec -> updateItem(i) { it.copy(restSec = sec) }; restFor = null }
    }
}

@Composable
fun RestPicker(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest Timer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                REST_CHOICES.forEach { sec ->
                    Text(
                        Units.restLabel(sec), fontSize = 18.sp,
                        color = if (sec == current) Blue else Color.White,
                        fontWeight = if (sec == current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(sec) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Suppress("unused")
private val keepImports = listOf(Arrangement.Center, KeyboardOptions.Default)
