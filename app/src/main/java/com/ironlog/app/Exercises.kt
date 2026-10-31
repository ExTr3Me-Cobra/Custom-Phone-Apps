package com.ironlog.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

val MUSCLES = Muscles.groups.keys.sorted()
val EQUIPMENT = listOf("Barbell", "Dumbbell", "Cable", "Machine", "Kettlebell", "Bodyweight", "EZ Bar", "Bands", "Medicine Ball", "Exercise Ball", "Foam Roll", "Other")

// ---------------- Exercise list / picker ----------------

/** The searchable exercise list. With a [Route.Picker] it picks exercises; otherwise it's the library. */
@Composable
fun PickerScreen(req: Route.Picker?) {
    val all by Store.exercises.collectAsState()
    // Saveable, so the search is still there when you come back from an exercise.
    var query by rememberSaveable { mutableStateOf("") }
    var equipment by rememberSaveable { mutableStateOf<String?>(null) }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf(0) }
    // Star filters: show exercises with a gold ★, a blue ★, or no star.
    var showGold by rememberSaveable { mutableStateOf(true) }
    var showBlue by rememberSaveable { mutableStateOf(true) }
    var showNone by rememberSaveable { mutableStateOf(true) }
    var showAi by rememberSaveable { mutableStateOf(true) }
    val profile by Store.profile.collectAsState()
    val selected = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    val sorted = remember(all) { all.values.filter { !it.hidden && req?.only?.invoke(it) != false }.sortedBy { it.name.lowercase() } }
    val words = query.lowercase().split(" ").filter { it.isNotBlank() }
    val filtered = sorted.filter { e ->
        (equipment == null || e.equipment == equipment) &&
            (muscle == null || muscle in e.primary) && // only exercises where it's a main muscle
            when (Standards.star(e.id, profile)) {
                Standards.Star.EXACT -> showGold
                Standards.Star.ESTIMATE -> showBlue
                Standards.Star.AI -> showAi
                Standards.Star.NONE -> showNone
            } &&
            words.all { w ->
                e.name.lowercase().contains(w) || e.primary.any { it.contains(w) } || e.equipment.lowercase().contains(w) ||
                    e.muscles.any { it.value >= 0.7f && it.key.lowercase().contains(w) }
            }
    }
    // Order: A–Z, by main muscle group, or yours first (in routines, then logged before, then the rest).
    val sort = profile.exerciseSort
    val routineIds = Store.routines.collectAsState().value.flatMap { r -> r.items.map { it.exerciseId } }.toSet()
    val loggedIds = Store.workouts.collectAsState().value.flatMap { w -> w.items.map { it.exerciseId } }.toSet()
    val sections: List<Pair<String?, List<Exercise>>> = when (sort) {
        "muscle" -> filtered.groupBy { it.mainMuscle }.toSortedMap().map { (m, l) -> m to l }
        "mine" -> listOf(
            "In your routines" to filtered.filter { it.id in routineIds },
            "Logged before" to filtered.filter { it.id !in routineIds && it.id in loggedIds },
            "Everything else" to filtered.filter { it.id !in routineIds && it.id !in loggedIds },
        ).filter { it.second.isNotEmpty() }
        else -> listOf(null to filtered)
    }
    var sortMenu by remember { mutableStateOf(false) }
    val browsing = words.isEmpty() && equipment == null && muscle == null && showGold && showBlue && showAi && showNone
    val recent = if (browsing) Store.recentExerciseIds().mapNotNull { all[it] }.filter { req?.only?.invoke(it) != false } else emptyList()

    // Create: a new exercise from scratch, or a copy of an existing one to edit.
    var createMenu by remember { mutableStateOf(false) }
    if (createMenu) AlertDialog(
        onDismissRequest = { createMenu = false },
        title = { Text("Create exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GreyButton("✚  New exercise from scratch") { createMenu = false; Nav.go(Route.EditExercise(null)) }
                GreyButton("⧉  Duplicate and edit an existing one") {
                    createMenu = false
                    Nav.go(Route.Picker("Duplicate which exercise?", false) { ids -> ids.firstOrNull()?.let { Nav.go(Route.EditExercise(null, it)) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = { createMenu = false }) { Text("Cancel") } },
    )

    fun tap(e: Exercise) {
        when {
            req == null -> Nav.go(Route.ExerciseDetail(e.id))
            req.multi -> if (e.id in selected) selected.remove(e.id) else selected.add(e.id)
            else -> { Nav.back(); req.onPick(listOf(e.id)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(req?.title ?: "Exercises", action = "Create") { createMenu = true }
        // While choosing: stays at the top as you scroll.
        if (req != null) Text(
            "Read Proper Form and How to log (tap ⓘ Info) before choosing, to make sure it's truly the exercise you're looking for and your ranks stay accurate.",
            color = Sub, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().background(Card).padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔍", fontSize = 16.sp)
            HSpacer(10)
            Box(Modifier.weight(1f).typing()) {
                if (query.isEmpty()) Text("Search exercise", color = Sub, fontSize = 17.sp)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Blue))
            }
            if (query.isNotEmpty()) Text("✕", color = Sub, modifier = Modifier.clickable { query = "" }.padding(start = 8.dp))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GreyButton(equipment ?: "All Equipment", Modifier.weight(1f), if (equipment != null) Blue else Color.White) { dialog = 1 }
            GreyButton(muscle?.let(::titleCase) ?: "All Muscles", Modifier.weight(1f), if (muscle != null) Blue else Color.White) { dialog = 2 }
            Box {
                GreyButton("⇅", Modifier.width(52.dp), if (sort != "az") Blue else Color.White) { sortMenu = true }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("az" to "Alphabetical (A–Z)", "muscle" to "By main muscle group", "mine" to "Mine first: routines, then logged")
                        .forEach { (k, label) ->
                            DropdownMenuItem({ Text(label) }, {
                                sortMenu = false; Store.setProfile(Store.profile.value.copy(exerciseSort = k))
                            }, trailingIcon = { if (sort == k) Text("✓") })
                        }
                }
            }
        }
        if (Standards.mode(profile) != Standards.Mode.OFF) Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.FilterChip(showGold, { showGold = !showGold }, { Text("★", color = StarGold); Text(" Gold") })
            if (Standards.mode(profile) >= Standards.Mode.ALL)
                androidx.compose.material3.FilterChip(showBlue, { showBlue = !showBlue }, { Text("★", color = StarEstimate); Text(" Blue") })
            if (Standards.mode(profile) == Standards.Mode.AI)
                androidx.compose.material3.FilterChip(showAi, { showAi = !showAi }, { Text("★", color = StarAi); Text(" AI") })
            androidx.compose.material3.FilterChip(showNone, { showNone = !showNone }, { Text("No star") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (recent.isNotEmpty()) {
                item { SectionTitle("Recent Exercises", Modifier.padding(top = 8.dp)) }
                items(recent, key = { "r_" + it.id }) { e -> ExerciseRow(e, e.id in selected, req != null) { tap(e) } }
            }
            item { SectionTitle(if (browsing) "All Exercises (${filtered.size})" else "${filtered.size} found", Modifier.padding(top = 8.dp)) }
            sections.forEach { (title, list) ->
                if (title != null) item(key = "s_$title") { SectionTitle("$title (${list.size})", Modifier.padding(top = 4.dp)) }
                items(list, key = { it.id }) { e -> ExerciseRow(e, e.id in selected, req != null) { tap(e) } }
            }
        }
        if (req != null && req.multi && selected.isNotEmpty()) {
            BigButton(
                req.confirm?.let { "$it (${selected.size})" } ?: "Add ${selected.size} exercise${if (selected.size == 1) "" else "s"}",
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) {
                val ids = selected.toList()
                Nav.back()
                req.onPick(ids)
            }
        }
    }
    if (dialog != 0) {
        val opts = if (dialog == 1) EQUIPMENT else MUSCLES
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(if (dialog == 1) "Equipment" else "Muscle") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (dialog == 1) "All Equipment" else "All Muscles", fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable {
                        if (dialog == 1) equipment = null else muscle = null; dialog = 0
                    }.padding(vertical = 10.dp))
                    opts.forEach { o ->
                        Text(titleCase(o), fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable {
                            if (dialog == 1) equipment = o else muscle = o; dialog = 0
                        }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = 0 }) { Text("Close") } },
        )
    }
}

@Composable
fun ExerciseRow(e: Exercise, selected: Boolean, picking: Boolean, onClick: () -> Unit) {
    Row(
        // Browsing, the row itself does nothing: the ⓘ button opens the exercise. Picking, tapping the row picks it.
        Modifier.fillMaxWidth().then(if (picking) Modifier.clickable(onClick = onClick) else Modifier).background(if (selected) Blue.copy(alpha = 0.12f) else Bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(80.dp).background(if (selected) Blue else Color.Transparent))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ExerciseThumb(e, 60.dp)
            HSpacer(16)
            Column(Modifier.weight(1f)) {
                NameText(starredName(e), fontSize = 17.sp, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.mainMuscle, color = Sub, fontSize = 15.sp)
                    if (e.custom) { HSpacer(8); Chip("Custom") }
                }
            }
            // While picking, a clear button to read the exercise first (tapping the row picks it).
            if (picking) Text("ⓘ Info", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Card2).clickable { Nav.go(Route.ExerciseDetail(e.id)) }
                    .padding(horizontal = 10.dp, vertical = 8.dp))
            else Text("ⓘ", color = Blue, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Card2)
                .clickable { Nav.go(Route.ExerciseDetail(e.id)) }.padding(horizontal = 9.dp, vertical = 4.dp))
        }
    }
    HorizontalDivider(color = Card, modifier = Modifier.padding(start = 92.dp))
}

// ---------------- Exercise page ----------------

/** Custom exercise -> the exercise picked to link it to, waiting for confirmation (kept outside the screen, which the picker covers). */
private val pendingLink = androidx.compose.runtime.mutableStateMapOf<String, String>()
/** Developer: exercise -> where to move its history, waiting for confirmation. */
private val pendingMove = androidx.compose.runtime.mutableStateMapOf<String, String>()

@Composable
fun ExerciseDetailScreen(id: String) {
    val all by Store.exercises.collectAsState()
    val e = all[id] ?: return closeScreen()
    val workouts by Store.workouts.collectAsState()
    val profile by Store.profile.collectAsState()
    val version by Store.imageVersion.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var askAi by remember { mutableStateOf(false) }
    val hasImage = remember(version) { Store.hasImage(e.id) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) runCatching { Pictures.import(context, e.id, uri) }.onFailure { status = "Couldn't use that picture." }
    }
    val camFile = remember { File(context.cacheDir, "cam").apply { mkdirs() }.let { File(it, "shot.jpg") } }
    val camUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", camFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok || camFile.sizeOk()) runCatching { Pictures.import(context, e.id, camUri) }.onFailure { status = "Couldn't use that picture." }
    }

    val sessions = remember(workouts, id) { Stats.sessions(id, workouts) }
    val metrics = Stats.metricsFor(e.type)
    var metric by remember(id) { mutableStateOf(metrics.first()) }

    var topMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val linkTo = pendingLink[e.id]
    // Ranked and unranked exercise pages each keep their own panel order; your rank mode decides which this one uses.
    val ranked = Standards.covers(e.id, profile) && Standards.levels(e.id, profile) != null
    val layoutKey = if (ranked) "detail.ranked" else "detail.unranked"
    Column(Modifier.fillMaxSize()) {
        Box {
            TopBar("Exercise", action = "⋮") { topMenu = true }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 48.dp)) {
                DropdownMenu(topMenu, { topMenu = false }) {
                    DropdownMenuItem({ Text("Edit name, muscles & directions") }, { topMenu = false; Nav.go(Route.EditExercise(e.id)) })
                    if (e.edited) DropdownMenuItem({ Text("Reset to original") }, { topMenu = false; Store.resetExercise(e.id) })
                    if (e.custom) DropdownMenuItem({ Text("Link to a ranked exercise…") }, {
                        topMenu = false
                        Nav.go(Route.Picker("Link \"${e.name}\" to", false) { ids -> ids.firstOrNull()?.takeIf { it != e.id }?.let { pendingLink[e.id] = it } })
                    })
                    if (profile.devUnlocked) DropdownMenuItem({ Text("Developer: move history to another exercise…") }, {
                        topMenu = false
                        Nav.go(Route.Picker("Move \"${e.name}\" history to", false) { ids -> ids.firstOrNull()?.takeIf { it != e.id }?.let { pendingMove[e.id] = it } })
                    })
                    DropdownMenuItem({ Text("Restore default panel layout") }, {
                        topMenu = false
                        Store.setProfile(Store.profile.value.let { it.copy(layouts = it.layouts - layoutKey) })
                    })
                    if (e.custom) DropdownMenuItem({ Text("Delete exercise", color = Red) }, { topMenu = false; deleting = true })
                }
            }
        }
        linkTo?.let { to ->
            val target = all[to]
            val count = workouts.sumOf { w -> w.items.filter { it.exerciseId == e.id }.sumOf { it.sets.count { s -> s.done } } }
            if (target == null) { pendingLink.remove(e.id); return@let }
            ConfirmText(
                "Move all ${count} logged set${if (count == 1) "" else "s"} of \"${e.name}\" to \"${target.name}\"? " +
                    "Routines that use it switch over too, and \"${e.name}\" is deleted. This can't be undone." +
                    (if (target.type != e.type) "\n\nCareful: \"${target.name}\" is logged differently (${target.type.label}), so some numbers may not carry over." else "") +
                    (if (!Standards.covers(target.id)) "\n\nNote: \"${target.name}\" isn't a ranked exercise." else ""),
                {
                    pendingLink.remove(e.id)
                    Store.mergeExercise(e.id, to)
                    Nav.back(); Nav.go(Route.ExerciseDetail(to))
                },
                { pendingLink.remove(e.id) }, "Link",
            )
        }
        pendingMove[e.id]?.let { to ->
            val target = all[to] ?: run { pendingMove.remove(e.id); return@let }
            val count = workouts.sumOf { w -> w.items.filter { it.exerciseId == e.id }.sumOf { it.sets.count { s -> s.done } } }
            ConfirmText(
                "Move all ${count} logged set${if (count == 1) "" else "s"} of \"${e.name}\" in your past workouts to \"${target.name}\"? " +
                    "\"${e.name}\" stays (with no history) and routines aren't changed. This can't be undone." +
                    (if (target.type != e.type) "\n\nCareful: \"${target.name}\" is logged differently (${target.type.label}), so some numbers may not carry over." else ""),
                {
                    pendingMove.remove(e.id)
                    Store.moveHistory(e.id, to)
                    Nav.back(); Nav.go(Route.ExerciseDetail(to))
                },
                { pendingMove.remove(e.id) }, "Move",
            )
        }
        if (deleting) ConfirmText("Delete \"${e.name}\"? Past workouts keep their numbers. It goes to the Recycle Bin (Settings) for 30 days.", {
            deleting = false; Store.deleteCustomExercise(e.id); Nav.back()
        }, { deleting = false }, "Delete")
        // Hold any panel to move it. Ranked exercises share one order, unranked ones another; which one an exercise
        // uses follows your rank mode, so switching modes in Settings switches its layout too.
        val rankList = if (ranked) rankPanels(e.id, title = true, prefix = "rank.", wrap = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) else emptyList()
        val panels = listOf(
            Panel("picture", "Picture") {
                Column(Modifier.fillMaxWidth()) {
                    ExercisePicture(e, Modifier.fillMaxWidth())
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            GreyButton("📷 Your picture") { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Choose from gallery") }, {
                                    menu = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                })
                                DropdownMenuItem({ Text("Take a photo") }, { menu = false; camFile.delete(); camera.launch(camUri) })
                                if (hasImage && remember(version) { Pictures.isOwn(e.id) }) DropdownMenuItem({ Text("Remove picture", color = Red) }, { menu = false; Pictures.remove(e.id) })
                            }
                        }
                        if (profile.devAi) GreyButton(if (busy) "Generating…" else "✨ AI picture", Modifier.weight(1f), Blue) {
                            if (!busy) askAi = true
                        }
                    }
                    if (askAi) {
                        val p = Store.profile.value
                        // Starts from the standard prompt every time the dialog opens; your edits apply to this picture only.
                        val original = remember(e.id, e.name, e.muscles, e.equipment) { Pictures.prompt(e) }
                        var promptText by remember { mutableStateOf(original) }
                        var useRef by remember { mutableStateOf(false) }
                        AlertDialog(
                            onDismissRequest = { askAi = false },
                            title = { Text("Make an AI picture?") },
                            text = {
                                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (p.apiKey.isBlank()) "First add your OpenAI API key in Settings."
                                        else "One picture of \"${e.name}\" at ${p.imageQuality} quality costs about $" +
                                            "%.3f".format(Pictures.price(p.imageQuality)) + ", taken from your OpenAI API credit." +
                                            if (hasImage) "\n\nIt replaces this exercise's current picture." else "",
                                    )
                                    if (p.apiKey.isNotBlank()) {
                                        OutlinedTextField(promptText, { promptText = it }, Modifier.fillMaxWidth().typing(),
                                            label = { Text("Prompt (edit for this picture)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                                        if (promptText != original) Text("Reset to original prompt", color = Blue, fontSize = 14.sp,
                                            modifier = Modifier.clickable { promptText = original }.padding(vertical = 4.dp))
                                        if (hasImage) Row(Modifier.fillMaxWidth().clickable { useRef = !useRef }, verticalAlignment = Alignment.CenterVertically) {
                                            androidx.compose.material3.Checkbox(useRef, { useRef = it })
                                            Text("Send the current picture as a reference (ChatGPT edits it to match the prompt)", fontSize = 13.sp)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                if (p.apiKey.isNotBlank()) TextButton(onClick = {
                                    val usePrompt = promptText
                                    val withRef = useRef && hasImage
                                    askAi = false
                                    busy = true
                                    status = "Asking OpenAI for a picture – this takes up to a minute…"
                                    scope.launch {
                                        val r = Pictures.generate(e, Store.profile.value, usePrompt, withRef)
                                        busy = false
                                        status = if (r.ok) "Done! Saved as this exercise's picture." else r.message
                                    }
                                }) { Text("Yes, make it") }
                            },
                            dismissButton = { TextButton(onClick = { askAi = false }) { Text("Cancel") } },
                        )
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
                    status?.let { Text(it, color = Sub, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            },
            Panel("name", "Name & equipment") {
                Column(Modifier.fillMaxWidth()) {
                    NameText(starredName(e), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp))
                    Text("Primary: " + e.primary.joinToString { titleCase(it) }, modifier = Modifier.padding(horizontal = 16.dp))
                    Text("${e.equipment} · ${e.type.label}", color = Sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            },
            Panel("howto", "How to log") {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp, 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp)) {
                        Text("⚖", fontSize = 18.sp)
                        HSpacer(10)
                        Column {
                            Text("How to log", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(e.weightHint(), color = Sub, fontSize = 14.sp)
                            StackChip(e, Modifier.padding(top = 6.dp))
                        }
                    }
                }
            },
            Panel("muscles", "Muscles worked") {
                Column(Modifier.fillMaxWidth()) {
                    MusclesCard(e)
                }
            },
        ) + rankList + listOf(
            Panel("records", "Personal Records", visible = sessions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    // Records
                    SectionTitle("Personal Records")
                    metrics.forEach { m ->
                        val best = sessions.mapNotNull { Stats.value(m, it.sets) }.maxOrNull()
                        if (best != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(m.label, Modifier.weight(1f))
                            Text(Units.metric(m, best), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            Panel("progress", "Progress") {
                Column(Modifier.fillMaxWidth()) {
                    // Progress chart
                    SectionTitle("Progress")
                    // One scrollable line of chips: a chip that doesn't fit must not squash its text into a tall column.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        metrics.forEach { m -> FilterChip(metric == m, { metric = m }, { Text(m.label, fontSize = 12.sp, maxLines = 1, softWrap = false) }) }
                    }
                    val pts = sessions.mapNotNull { s -> Stats.value(metric, s.sets)?.let { s.workout.start to it } }
                    LineChart(pts) { Units.metric(metric, it) }
                }
            },
            Panel("history", "History", visible = sessions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    // Like Completed Workouts on Home: your 10 most recent, then the full list.
                    var open by rememberSaveable(e.id) { mutableStateOf(false) }
                    DropDownButton("🕘  History (${sessions.size})", open, Modifier.padding(16.dp, 10.dp, 16.dp, 6.dp)) { open = !open }
                    Reveal(open) {
                        Column {
                            sessions.reversed().take(10).forEach { s -> SessionCard(e, s) }
                            GreyButton("See All History", Modifier.padding(16.dp, 8.dp).fillMaxWidth()) { Nav.go(Route.ExerciseHistory(e.id)) }
                        }
                    }
                }
            },
            Panel("form", "Proper Form", visible = e.instructions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    // Proper form: tap to open the step-by-step directions.
                    var formOpen by rememberSaveable(e.id) { mutableStateOf(false) }
                    Row(Modifier.padding(16.dp, 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
                        .clickable { formOpen = !formOpen }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Proper Form", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(if (formOpen) "▴" else "▾", color = Blue, fontSize = 18.sp)
                    }
                    Reveal(formOpen) { Column { e.instructions.forEachIndexed { i, s -> Text("${i + 1}. $s", modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) } } }
                }
            },
        )
        PanelColumn(
            layoutKey, panels, Modifier.fillMaxSize(),
            bottom = {
                Text("Tip: hold any panel to pick it up and move it – every ${if (ranked) "ranked" else "unranked"} exercise follows. ⋮ → Restore default panel layout undoes it.",
                    color = Sub, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
                Spacer(40)
            },
        )
    }
}

/** One workout's sets of exercise [e]: tap to open the whole workout. */
@Composable
fun SessionCard(e: Exercise, s: Stats.Session) {
    val press = rememberPress(0.97f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).pressScale(press).clip(RoundedCornerShape(10.dp)).background(Card)
        .pressClick(press) { Nav.go(Route.WorkoutDetail(s.workout.id)) }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Units.date(s.workout.start), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(if (s.workout.test) "1 Rep Max test" else s.workout.name, color = Sub, fontSize = 13.sp, maxLines = 1)
        }
        s.sets.forEachIndexed { i, set ->
            Text("${when { set.warmup -> "W"; set.failure -> "F"; else -> "${setNumber(s.sets.map { it.warmup }, i) + 1}" }}   ${Units.set(e.type, set)}",
                color = when { set.warmup -> Orange; set.failure -> Red; else -> Sub })
        }
    }
}

/** Every muscle the exercise works, with how hard compared with the main muscle, and the research behind it. */
@Composable
fun MusclesCard(e: Exercise) {
    var sources by remember { mutableStateOf(false) }
    val personal = Store.profile.collectAsState().value.devPersonal // PERSONAL-USE (removable): sources hidden
    Column(Modifier.padding(16.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(14.dp)) {
        Text("Muscles worked", fontWeight = FontWeight.Bold)
        // Each muscle's share of the total work, adding up to exactly 100% (largest-remainder rounding).
        val list = e.muscles.entries.sortedByDescending { it.value }
        val total = list.sumOf { it.value.toDouble() }.takeIf { it > 0 } ?: 1.0
        val raw = list.map { it.value / total * 100 }
        val shares = raw.map { kotlin.math.floor(it).toInt() }.toIntArray()
        raw.indices.sortedByDescending { raw[it] - shares[it] }.take(100 - shares.sum()).forEach { shares[it]++ }
        val top = shares.maxOrNull()?.coerceAtLeast(1) ?: 1
        list.forEachIndexed { i, (m, v) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Muscles.label(m), Modifier.weight(1f), fontSize = 14.sp)
                Box(Modifier.width(70.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Card2)) {
                    Box(Modifier.fillMaxWidth((shares[i].toFloat() / top).coerceIn(0f, 1f)).fillMaxHeight().background(Color(BodyMap.heat(v))))
                }
                Text("${shares[i]}%", Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 14.sp)
            }
        }
        Text(
            if (e.edited || e.custom) "Your numbers, as each muscle's share of the total work (adds up to 100%)." else "Each muscle's share of the total work (adds up to 100%), estimated from EMG and training studies of this kind of movement.",
            color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        if (e.refs.isNotEmpty() && !e.edited && !e.custom && !personal) {
            Text(if (sources) "Hide sources" else "Sources (${e.refs.size})", color = Blue, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp).clickable { sources = !sources })
            if (sources) e.refs.forEach { r ->
                Text("• " + (Muscles.refs[r] ?: r), color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------------- Create / edit an exercise ----------------

/** One muscle in the editor: [share] is its % of the exercise's total work (all shares add up to 100). */
private class MuscleRow(val muscle: String, main: Boolean, share: Int) {
    var main by mutableStateOf(main)
    var share by mutableStateOf(share)
}

/** Whole-number shares of the total that add up to exactly 100 (largest remainder). */
private fun sharesOf(values: List<Float>): List<Int> {
    val total = values.sum().takeIf { it > 0 } ?: return values.map { 0 }
    val raw = values.map { it / total * 100 }
    val out = raw.map { kotlin.math.floor(it).toInt() }.toIntArray()
    raw.indices.sortedByDescending { raw[it] - out[it] }.take(100 - out.sum()).forEach { out[it]++ }
    return out.toList()
}

/** Edit any exercise (built-in ones are saved as your version and can be reset), or create one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditExerciseScreen(id: String?, copyOf: String? = null) {
    val existing = id?.let { Store.exercise(it) }
    // Duplicating: start from a copy of another exercise, saved as a new one of your own.
    val start = existing ?: copyOf?.let { Store.exercise(it) }
    var name by remember { mutableStateOf(existing?.name ?: start?.let { it.name + " (copy)" } ?: "") }
    var equipment by remember { mutableStateOf(start?.equipment ?: "Barbell") }
    var type by remember { mutableStateOf(start?.type ?: ExType.WEIGHT) }
    var directions by remember { mutableStateOf(start?.instructions?.joinToString("\n") ?: "") }
    val rows = remember {
        mutableStateListOf<MuscleRow>().apply {
            val list = start?.muscles?.entries?.sortedByDescending { it.value }.orEmpty()
            val shares = sharesOf(list.map { it.value })
            list.forEachIndexed { i, (m, v) -> add(MuscleRow(m, v >= 0.999f && Muscles.group(m) in start!!.primary, shares[i])) }
        }
    }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Credits: the % not yet given to any muscle. Lowering a slider adds credits; raising one spends them.
    val credits = 100 - rows.sumOf { it.share }
    // How hard each muscle works compared with the busiest one (what the body map and ranks use).
    val topShare = rows.maxOfOrNull { it.share }?.coerceAtLeast(1) ?: 1
    val levels = rows.filter { it.share > 0 }.associate { it.muscle to it.share.toFloat() / topShare }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (existing != null) "Edit Exercise" else if (start != null) "Duplicate Exercise" else "Create Exercise", action = "Save") {
            when {
                name.isBlank() -> error = "Give it a name."
                credits > 0 -> error = "You have $credits% credits left – give them to muscles so the total is 100%."
                rows.none { it.main } -> error = "Mark at least one muscle as Main."
                else -> {
                    // Groups (for the list filters and the line under the name) follow from the muscles.
                    val primary = rows.filter { it.main }.map { Muscles.group(it.muscle) }.distinct()
                    val sec = levels.entries.filter { Muscles.group(it.key) !in primary }
                        .groupBy { Muscles.group(it.key) }.mapValues { (_, v) -> v.maxOf { it.value } }
                        .entries.sortedByDescending { it.value }
                    val e = (existing ?: Exercise("custom_" + Store.newId(), "", equipment, type, emptyList(), emptyList(), custom = true)).copy(
                        name = name.trim(), equipment = equipment, type = type,
                        primary = primary,
                        secondary = sec.map { it.key },
                        secondaryWeights = sec.map { it.value },
                        detail = levels,
                        instructions = directions.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    )
                    Store.saveExercise(e)
                    Nav.back()
                    if (existing == null) Nav.go(Route.ExerciseDetail(e.id))
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().typing(), label = { Text("Name") }, singleLine = true)
            error?.let { Text(it, color = Red) }

            // Live preview: changes as you move the sliders.
            BodyMapView(levels, Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black))

            Text("Muscles worked", fontWeight = FontWeight.Bold)
            Text("Share out 100% between the muscles by how much of the work each one does. Lowering a slider gives you credits; " +
                "raising one spends them. Mark the main muscles – their groups show under the name and in the list filters.",
                color = Sub, fontSize = 13.sp)
            Text(
                when {
                    credits > 0 -> "Credits: $credits% left to give"
                    else -> "All 100% given out ✓"
                },
                color = if (credits > 0) Green else Sub, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
            rows.forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Muscles.label(r.muscle), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        FilterChip(r.main, { r.main = !r.main }, { Text("Main") })
                        HSpacer(8)
                        Text("${r.share}%", modifier = Modifier.width(44.dp), fontWeight = FontWeight.Medium)
                        Text("✕", color = Sub, modifier = Modifier.clickable { rows.remove(r) }.padding(6.dp))
                    }
                    // Can't go past the credits you have, so the total never tops 100%.
                    androidx.compose.material3.Slider(
                        value = r.share.toFloat(),
                        onValueChange = { r.share = it.roundToInt().coerceIn(0, r.share + (100 - rows.sumOf { x -> x.share })) },
                        valueRange = 0f..100f,
                    )
                }
            }
            GreyButton("+ Add a muscle", textColor = Blue) { adding = true }

            Text("Directions (one step per line)", fontWeight = FontWeight.Bold)
            OutlinedTextField(directions, { directions = it }, Modifier.fillMaxWidth().typing().heightIn(min = 120.dp))

            Text("Equipment", color = Sub)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EQUIPMENT.forEach { o -> FilterChip(equipment == o, { equipment = o }, { Text(o) }) }
            }
            Text("Logged as", color = Sub)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }) }
            }
            if (existing?.edited == true) GreyButton("Reset to original", textColor = Red) { Store.resetExercise(existing.id); Nav.back() }
            Spacer(30)
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a muscle") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Muscles.groups.forEach { (g, ms) ->
                        val options = ms.filter { m -> rows.none { it.muscle == m } }
                        if (options.isEmpty()) return@forEach
                        Text(titleCase(g), color = Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                        options.forEach { m ->
                            Text(Muscles.label(m), fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable {
                                // A new muscle starts at 0% (or all of it, if it's the first) – give it credits with its slider.
                                rows.add(MuscleRow(m, rows.none { it.main }, if (rows.isEmpty()) 100 else 0)); adding = false
                            }.padding(vertical = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Close") } },
        )
    }
}
