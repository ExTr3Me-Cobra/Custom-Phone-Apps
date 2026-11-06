package com.ironlog.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One exercise's rank: a chart of where your number sits among comparable lifters, and exactly who they are. */
@Composable
fun RankDetailScreen(id: String, afterTest: Boolean = false) {
    val e = Store.exercise(id) ?: run { closeScreen(); return }
    Column(Modifier.fillMaxSize()) {
        if (afterTest) androidx.activity.compose.BackHandler { Nav.leaveTestToHome() }
        TopBar(e.name, onBack = { if (afterTest) Nav.leaveTestToHome() else Nav.back() })
        // Hold any panel to move it; the order is the same on every exercise's rank screen.
        PanelColumn(
            "rank", rankPanels(id), Modifier.fillMaxSize().navigationBarsPadding(), Modifier.padding(16.dp), spacing = 12.dp,
            top = { NameText(starredName(e), fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            bottom = { Text("Tip: hold any panel and drag to move it – every rank screen follows.", color = Sub, fontSize = 12.sp); Spacer(30) },
        )
    }
}

/**
 * The rank, chart, levels, who you're compared with and the set that earned it, as movable panels – on the rank screen
 * and on an exercise's page. [prefix] keeps the keys apart from the page's own panels; [wrap] pads each one.
 */
@Composable
fun rankPanels(id: String, title: Boolean = false, prefix: String = "", wrap: Modifier = Modifier, share: Boolean = false): List<Panel> {
    val p by Store.profile.collectAsState()
    val workouts by Store.workouts.collectAsState()
    val info = Standards.info(id)
    val fit = Standards.fit(id, p)
    val lv = Standards.levels(id, p)
    val r = remember(workouts, p, id) { Ranks.all(workouts, p)[id] }
    val reps = info?.reps == true
    val flat = info?.flat == true
    val oneLeg = info?.oneLeg == true
    val timed = Standards.isTimed(id)
    fun fmt(v: Double) = Standards.format(id, v)

    if (info == null || fit == null || lv == null)
        return listOf(Panel(prefix + "header", "Your rank") { Text("No rank data for this exercise with your current profile.", color = Sub, modifier = wrap) })
    val mine = r?.value
    val source = remember(workouts, p, id, r?.value) { r?.let { Ranks.source(id, it.value, workouts) } }
    return listOf(
        Panel(prefix + "header", "Your rank") {
            Column(wrap.fillMaxWidth()) {
                if (title) Text("Your rank", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                if (r != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    RankBadge(r.tier, 64.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(r.tier.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(r.tier.color))
                        Text(topLabel(r.percentile))
                        Text((if (reps || timed) "Your best: " else if (flat) "Your best squeeze: " else if (oneLeg) "Your two-leg equivalent: " else "Your est. 1-rep max: ") + fmt(r.value), color = Sub, fontSize = 14.sp)
                    }
                } else Text("Log this exercise to see where you sit.", color = Sub)
            }
        },
        Panel(prefix + "chart", "Where you sit") {
            Column(wrap.fillMaxWidth()) {
                // Chart: % of lifters you're stronger than, against the number you lift.
                val xMax = maxOf(lv[4] * 1.2, (mine ?: 0.0) * 1.1)
                val curve = remember(lv.toList(), xMax) { (0..80).map { i -> val x = xMax * i / 80; x to (Standards.percentile(id, x, p) ?: 0.0) } }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp)) {
                    Text("Where you sit", fontWeight = FontWeight.Bold)
                    Text(if (timed) "Across: longest hold." else if (reps) "Across: reps." else if (flat) "Across: your best squeeze (either hand)." else if (oneLeg) "Across: two-leg equivalent load (body weight + 2 × your one-leg est. max)." else "Across: est. 1-rep max.",
                        color = Sub, fontSize = 12.sp)
                    Text(if (flat) "Up: est. % of ${if (p.male) "men" else "women"} aged 20–39 that you are stronger than with this particular exercise."
                        else "Up: est. % of people with your bodyweight, sex, and age who lift weights that you are stronger than with this particular exercise.",
                        color = Sub, fontSize = 12.sp)
                    val line = Color.White
                    val myColor = r?.let { Color(it.tier.color) } ?: StarGold
                    val youX = mine ?: 0.0 // no record yet: your line sits at zero
                    val youPct = r?.percentile ?: 0.0
                    // Drag sideways on the chart to read any point; let go and it snaps back to you.
                    var probe by remember(id) { mutableStateOf<Double?>(null) }
                    Canvas(Modifier.fillMaxWidth().height(230.dp).padding(top = 8.dp).pointerInput(id, xMax) {
                        fun xAt(px: Float): Double {
                            val left = 34.dp.toPx(); val right = size.width - 6.dp.toPx()
                            return ((px - left) / (right - left) * xMax).coerceIn(0.0, xMax)
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { o -> probe = xAt(o.x) },
                            onDragEnd = { probe = null }, onDragCancel = { probe = null },
                        ) { change, _ -> change.consume(); probe = xAt(change.position.x) }
                    }) {
                        val left = 34.dp.toPx(); val bottom = size.height - 22.dp.toPx(); val top = 6.dp.toPx(); val right = size.width - 6.dp.toPx()
                        fun px(x: Double) = (left + (right - left) * (x / xMax)).toFloat()
                        fun py(pct: Double) = (bottom - (bottom - top) * (pct / 100)).toFloat()
                        // Rank bands: each 5% in its rank colour.
                        Tier.entries.forEach { t ->
                            drawRect(Color(t.color).copy(alpha = 0.13f), Offset(left, py(t.ordinal * 5.0 + 5)),
                                androidx.compose.ui.geometry.Size(right - left, py(t.ordinal * 5.0) - py(t.ordinal * 5.0 + 5)))
                        }
                        val text = android.graphics.Paint().apply { color = Sub.toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true }
                        listOf(0, 25, 50, 75, 100).forEach { y ->
                            drawLine(Color.White.copy(alpha = 0.08f), Offset(left, py(y.toDouble())), Offset(right, py(y.toDouble())))
                            drawContext.canvas.nativeCanvas.drawText("$y%", 0f, py(y.toDouble()) + 4.dp.toPx(), text)
                        }
                        // The curve.
                        val path = Path()
                        curve.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y)) }
                        drawPath(path, line, style = Stroke(2.dp.toPx()))
                        // The five published levels.
                        lv.forEachIndexed { k, v ->
                            drawCircle(line, 4.dp.toPx(), Offset(px(v), py(Standards.percentile(id, v, p) ?: Standards.LEVEL_PCT[k])))
                            drawContext.canvas.nativeCanvas.drawText(fmt(v).substringBefore(" "), px(v) - 10.dp.toPx(), size.height - 4.dp.toPx(), text)
                        }
                        // You (a faint ghost while you drag).
                        val youAlpha = if (probe != null) 0.35f else 1f
                        drawLine(myColor.copy(alpha = youAlpha), Offset(px(youX), top), Offset(px(youX), bottom), 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                        drawCircle(myColor.copy(alpha = youAlpha), 7.dp.toPx(), Offset(px(youX), py(youPct)))
                        drawCircle(Color.Black.copy(alpha = youAlpha), 3.dp.toPx(), Offset(px(youX), py(youPct)))
                        // The line you're dragging.
                        probe?.let { x ->
                            val pc = Standards.percentile(id, x, p) ?: 0.0
                            val c = Color(Tier.of(pc).color)
                            drawLine(c, Offset(px(x), top), Offset(px(x), bottom), 2.dp.toPx())
                            drawCircle(c, 7.dp.toPx(), Offset(px(x), py(pc)))
                            drawCircle(Color.Black, 3.dp.toPx(), Offset(px(x), py(pc)))
                        }
                    }
                    val px = probe
                    if (px != null) {
                        val pc = Standards.percentile(id, px, p) ?: 0.0
                        val t = Tier.of(pc)
                        Text("● ${fmt(px)} – stronger than ${betterThan(pc)}% · ${t.label}", color = Color(t.color), fontSize = 13.sp)
                    } else if (mine != null && r != null) Text("● You: ${fmt(mine)} – stronger than ${betterThan(r.percentile)}%", color = myColor, fontSize = 13.sp)
                    else Text("● You: No record on file", color = myColor, fontSize = 13.sp)
                    if (!share) Text("Drag sideways on the chart to check any point.", color = Sub, fontSize = 11.sp)
                }
            }
        },
        Panel(prefix + "levels", "The levels for you") {
            Column(wrap.fillMaxWidth()) {
                // The numbers behind it.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The levels for you", fontWeight = FontWeight.Bold)
                    Standards.LEVEL_NAMES.forEachIndexed { k, name ->
                        val reached = mine != null && mine >= lv[k]
                        val pct = Standards.percentile(id, lv[k], p) ?: Standards.LEVEL_PCT[k]
                        val tier = Tier.of(pct)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (reached) "✓ " else "   ") + name, color = if (reached) Color.White else Sub)
                                // The rank this level puts you at.
                                Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RankBadge(tier, 18.dp)
                                    Text(" " + tier.label, color = Color(tier.color), fontSize = 12.sp)
                                }
                            }
                            Text("beats ${betterThan(pct)}%", Modifier.width(90.dp), color = Sub, fontSize = 13.sp)
                            Text(fmt(lv[k]), Modifier.width(80.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                    if (r != null && r.tier != Tier.entries.last()) {
                        val next = Tier.entries[r.tier.ordinal + 1]
                        Standards.valueFor(id, next.ordinal * 5.0, p)?.let {
                            Text("Next rank (${next.label}): ${fmt(it)}" + if (!reps) (if (flat) "" else " est. 1-rep max") else "", color = Color(next.color),
                                fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        Panel(prefix + "compared", "Who you're compared with") {
            Column(wrap.fillMaxWidth()) {
                // Exactly who you're compared with.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Who you're compared with", fontWeight = FontWeight.Bold)
                    @Composable fun Line(k: String, v: String) = Row {
                        Text(k, Modifier.width(110.dp), color = Sub, fontSize = 14.sp)
                        Text(v, Modifier.weight(1f), fontSize = 14.sp)
                    }
                    if (flat) {
                        Line("People", (if (p.male) "Men" else "Women") + " aged 20–39, everyone (not just lifters)")
                        Line("Body weight", "not used")
                        Line("Age", "not used")
                        if (!p.devPersonal) Line("Data", "International grip-strength norms, 2.4 million adults (Tomkinson et al. 2025)")
                    } else {
                    Line("People", (if (p.male) "Men" else "Women") + " who lift")
                    Line("Body weight", p.bodyweight?.let { Units.weightU(it) } ?: "not set")
                    Line("Body fat", BodyFat.label(p)?.let { b -> p.bodyweight?.let { w -> val c = BodyFat.compareWeight(p, w, info.reps || info.timed)
                        if (c == w) b else "$b (compared as ${Units.estU(c)})" } ?: b } ?: "not set (no adjustment)")
                    Line("Age", p.age?.let { a -> val f = Standards.ageFactor(a, p.male); "$a" + if (f >= 0.995) " (no adjustment)" else " (levels × ${"%.2f".format(f)})" } ?: "not set")
                    Line("Data", when {
                        p.devPersonal -> "Personal-use data" + if (info.aiFor(p.male)) ", rough estimate" else if (info.approxFor(p.male) != null) ", estimate" else ""
                        info.aiFor(p.male) -> "Rough estimate from AI-generated databases"
                        info.approxFor(p.male) != null -> "Estimate (see Details)"
                        else -> "Published research"
                    })
                    if (info.perDumbbell) Line("Weight", if (Store.exercise(id)?.equipment == "Kettlebell") "per kettlebell" else "per dumbbell")
                    }
                    // The full explanation, folded away under the Data row.
                    var details by remember(id) { mutableStateOf(false) }
                    Text(if (details) "Details: ▴" else "Details: ▾", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { details = !details }.padding(vertical = 2.dp))
                    Reveal(details) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(fit.compared, color = Sub, fontSize = 12.sp)
                        if (!fit.exact) {
                            Text("Why it's an estimate:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            fit.problems.forEach { Text("• $it", color = Sub, fontSize = 12.sp) }
                        }
                    } }
                }
            }
        },
        Panel(prefix + "source", "The set that earned this rank", visible = source != null) {
            Column(wrap.fillMaxWidth()) {
                // The set your rank comes from.
                source?.let { (w, set) -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ex = Store.exercise(id)
                    Text("The set that earned this rank", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👑 ", fontSize = 18.sp)
                        Text(ex?.let { Units.set(it.type, set) } ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (set.failure) Text("  (to failure)", color = Red, fontSize = 13.sp)
                    }
                    Text((if (w.test) "1 Rep Max test" else w.name.ifBlank { "Workout" }) + " · " +
                        java.text.SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(w.start)),
                        color = Sub, fontSize = 13.sp)
                    val bw = p.bodyweight
                    if (oneLeg && bw != null) {
                        // Show the conversion step by step, so the body weight that's added is visible.
                        val vest = (set.weight ?: 0.0).coerceAtLeast(0.0)
                        val n = set.reps ?: 1
                        val two = Standards.twoLegLoad(vest, bw)
                        Text("How it's counted:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("• On one leg, your calf carried your body weight + vest: ${Units.weightU(bw)} + ${Units.weightU(vest)} = ${Units.weightU(bw + vest)}", color = Sub, fontSize = 13.sp)
                        Text("• The same work per calf on two legs is a ${Units.estU(two)} two-leg calf raise (body weight + 2 × vest)", color = Sub, fontSize = 13.sp)
                        Text("• ${Units.estU(two)} × $n " + (if (n == 1) "rep is your 1-rep max" else "reps ≈ a ${fmt(r!!.value)} 1-rep max") + ", scored like the two-leg version", color = Sub, fontSize = 13.sp)
                    } else if (!reps && !flat && (set.reps ?: 1) > 1) Text("Est. 1-rep max from this set: ${fmt(r!!.value)}", color = Sub, fontSize = 13.sp)
                } }
            }
        },
    )
}
