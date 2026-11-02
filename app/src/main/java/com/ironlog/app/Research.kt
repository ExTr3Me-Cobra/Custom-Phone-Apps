package com.ironlog.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Research mode (developer setting): a never-ending list of 1 Rep Max tests on the exercises that tell us most about the
 * standards: the research anchor lifts first, then the machine and isolation lifts whose standards are only estimates.
 * Each test is a normal 1 Rep Max mode test, so it's saved with your workouts; Export writes every result to a CSV.
 */
object Research {
    /** Section -> exercise names, in testing order. */
    val SECTIONS = listOf(
        "Anchors (test these first)" to listOf(
            "Barbell Bench Press - Medium Grip", "Barbell Squat", "Barbell Deadlift", "Standing Military Press",
            "Wide-Grip Lat Pulldown", "Seated Cable Rows"),
        "Legs" to listOf(
            "Leg Press", "Hack Squat", "Leg Extensions", "Lying Leg Curls", "Seated Leg Curl", "Romanian Deadlift",
            "Barbell Hip Thrust", "Trap Bar Deadlift", "Front Barbell Squat", "Goblet Squat", "Dumbbell Lunges",
            "Standing Calf Raises", "Seated Calf Raise"),
        "Chest & shoulders" to listOf(
            "Dumbbell Bench Press", "Incline Dumbbell Press", "Barbell Incline Bench Press - Medium Grip", "Smith Machine Bench Press",
            "Machine Bench Press", "Butterfly", "Cable Crossover", "Dumbbell Shoulder Press", "Machine Shoulder (Military) Press",
            "Leverage Shoulder Press", "Side Lateral Raise", "Reverse Machine Flyes", "Face Pull"),
        "Back" to listOf("Bent Over Barbell Row", "One-Arm Dumbbell Row", "Barbell Shrug", "Dumbbell Shrug"),
        "Arms" to listOf(
            "Barbell Curl", "EZ-Bar Curl", "Dumbbell Bicep Curl", "Hammer Curls", "Preacher Curl", "Two-Arm Dumbbell Preacher Curl",
            "Machine Bicep Curl", "Standing Biceps Cable Curl", "Triceps Pushdown", "Triceps Pushdown - Rope Attachment",
            "Cable Rope Overhead Triceps Extension", "EZ-Bar Skullcrusher", "Close-Grip Barbell Bench Press",
            "Standing Dumbbell Triceps Extension"),
        "Bodyweight & grip" to listOf("Pullups", "Chin-Up", "Dips - Triceps Version", "Pushups", "Grip Dynamometer Test"),
        // Test each Forearm Finisher move and its dumbbell wrist curl on the same day: the ratio ranks the Finisher.
        "Forearms" to listOf("Seated Dumbbell Palms-Up Wrist Curl", "Forearm Finisher Curl",
            "Seated Dumbbell Palms-Down Wrist Curl", "Forearm Finisher Extension"),
    )

    /** The exercises in the list that exist in your library. */
    fun exercises(): List<Pair<String, List<Exercise>>> {
        val byName = Store.exercises.value.values.associateBy { it.name }
        return SECTIONS.map { (title, names) -> title to names.mapNotNull { byName[it] } }
    }

    class Result(val exercise: Exercise, val best: Double?, val tests: List<Pair<Workout, Double>>)

    /** Each exercise's best estimate from all your logging, and every finished 1 Rep Max test of it (newest first). */
    fun results(workouts: List<Workout>, p: Profile): List<Result> = exercises().flatMap { it.second }.map { e ->
        val tests = workouts.filter { it.test && it.end != null }.sortedByDescending { it.start }.mapNotNull { w ->
            val sets = w.items.filter { it.exerciseId == e.id }.flatMap { it.sets }
            if (sets.none { it.done && !it.warmup }) null else w to Standards.testValue(e.id, sets)
        }.filter { it.second > 0 }
        Result(e, OneRm.best(e.id, workouts, p), tests)
    }

    /** Everything as CSV: one row per exercise, then every test set. Weights in lb. */
    fun csv(workouts: List<Workout>, p: Profile, version: String): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        fun n(v: Double?) = v?.let { "%.1f".format(java.util.Locale.US, it) } ?: ""
        val sb = StringBuilder()
        sb.append("# Loadout research mode export, ${date.format(java.util.Date())}, app $version\n")
        sb.append("# sex=${if (p.male) "male" else "female"}, bodyweight_lb=${n(p.bodyweight)}, age=${p.age ?: ""}, height_in=${p.heightIn ?: ""}\n")
        sb.append("exercise_id,exercise,equipment,unit,latest_test,latest_test_date,best_test,tests,best_estimate_all_logging,stacks,pulley_ratio\n")
        val res = results(workouts, p)
        for (r in res) {
            val e = r.exercise
            val unit = when { Standards.isTimed(e.id) -> "seconds"; Standards.isReps(e.id) -> "reps"; Standards.isFlat(e.id) -> "grip_lb"; Standards.info(e.id)?.perDumbbell == true -> "lb_each"; else -> "lb" }
            val latest = r.tests.firstOrNull()
            sb.append(listOf(q(e.id), q(e.name), q(e.equipment), unit, n(latest?.second), latest?.let { date.format(java.util.Date(it.first.start)) } ?: "",
                n(r.tests.maxOfOrNull { it.second }), r.tests.size.toString(), n(r.best),
                if (e.hasStackChoice()) p.stacksFor(e).toString() else "", if (e.equipment == "Cable") p.pulleyFor(e).toString() else "").joinToString(",")).append('\n')
        }
        sb.append("\n# every set of every test\ntest_date,exercise,set,weight_lb,reps,done,warmup,bodyweight_lb_then\n")
        for (r in res) for ((w, _) in r.tests) w.items.filter { it.exerciseId == r.exercise.id }.flatMap { it.sets }.forEachIndexed { i, s ->
            sb.append(listOf(date.format(java.util.Date(w.start)), q(r.exercise.name), (i + 1).toString(), n(s.weight), s.reps?.toString() ?: "",
                s.done.toString(), s.warmup.toString(), n(w.bodyweight)).joinToString(",")).append('\n')
        }
        return sb.toString()
    }
}

/** Under the 1 Rep Max mode button, while the Research mode developer setting is on. */
@Composable
fun ResearchButton(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).clickable { Nav.go(Route.Research) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔬", fontSize = 22.sp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("RESEARCH MODE", color = Red, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Text("Developer: 1 Rep Max tests that help the standards", color = Sub, fontSize = 13.sp)
        }
        Text("›", color = Red, fontSize = 24.sp)
    }
}

@Composable
fun ResearchScreen() {
    val p by Store.profile.collectAsState()
    val workouts by Store.workouts.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val results = remember(workouts, p) { Research.results(workouts, p).associateBy { it.exercise.id } }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) message = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Research.csv(workouts, p, version).toByteArray()) }
            "Saved ${results.values.count { it.tests.isNotEmpty() }} tested exercises."
        }.getOrElse { "Couldn't save: ${it.message}" }
    }
    fun fmt(id: String, v: Double) = if (Standards.isReps(id) || Standards.isTimed(id)) Standards.format(id, v) else Units.weightU(v)
    val exercises by Store.exercises.collectAsState()
    val sections = remember(exercises) { Research.exercises() }
    val done = results.values.count { it.tests.isNotEmpty() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Research mode")
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                Text("Test your true 1 Rep Max on each of these, any order, as often as you like. Each one opens a normal 1 Rep Max test " +
                    "that starts from your best logged set; results are saved with your workouts and stay here. Anchors first: the other " +
                    "lifts are compared with them. Cable lifts: set Pulley and stacks before you test.",
                    Modifier.padding(16.dp), color = Sub, fontSize = 13.sp)
                Text("$done of ${results.size} tested", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold)
            }
            for ((title, list) in sections) {
                item { SectionTitle(title) }
                items(list, key = { it.id }) { e ->
                    val r = results[e.id]
                    val latest = r?.tests?.firstOrNull()
                    Row(
                        Modifier.fillMaxWidth().clickable { OneRm.add(e.id); Nav.go(Route.OneRmTest(e.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            NameText(starredName(e), fontSize = 16.sp)
                            Text("Best estimate: " + (r?.best?.let { fmt(e.id, it) } ?: "none yet"), color = Sub, fontSize = 13.sp)
                            Text(latest?.let { "Tested: ${fmt(e.id, it.second)} · " + java.text.DateFormat.getDateInstance().format(java.util.Date(it.first.start)) +
                                if ((r?.tests?.size ?: 0) > 1) " (${r?.tests?.size} tests)" else "" } ?: "Not tested yet",
                                color = if (latest != null) Color(0xFF34C759) else Sub, fontSize = 13.sp)
                        }
                        Text("›", color = Sub, fontSize = 22.sp)
                    }
                }
            }
            item {
                Spacer(16)
                GreyButton("Export research data (.csv)", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { export.launch("loadout-research.csv") }
                message?.let { Text(it, Modifier.padding(16.dp), color = Blue) }
                Spacer(32)
            }
        }
    }
}
