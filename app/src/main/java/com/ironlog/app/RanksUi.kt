package com.ironlog.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** The rank badge pictures (assets/ranks). */
object RankImages {
    private val cache = HashMap<Tier, ImageBitmap>()
    fun get(c: Context, t: Tier): ImageBitmap? = cache[t] ?: runCatching {
        c.assets.open("ranks/${t.badge}.webp").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }.getOrNull()?.also { cache[t] = it }
}

@Composable
fun RankBadge(t: Tier, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val img = remember(t) { RankImages.get(c, t) }
    if (img != null) Image(img, t.label, modifier.size(size)) else Box(modifier.size(size))
}

val StarGold = Color(0xFFC9A94E)
val StarEstimate = Color(0xFF7FA3C6)
val StarAi = Color(0xFFB48CE8)
fun starColor(s: Standards.Star) = when (s) { Standards.Star.EXACT -> StarGold; Standards.Star.AI -> StarAi; else -> StarEstimate }

/** An exercise name with a small ★: gold = ranked accurately, silver-blue = ranked as an estimate. */
@Composable
fun starredName(e: Exercise?, missing: String = "Missing exercise"): AnnotatedString {
    val p by Store.profile.collectAsState()
    val star = e?.let { Standards.star(it.id, p) } ?: Standards.Star.NONE
    val dev = devOneRmActive(e?.id)
    return buildAnnotatedString {
        append(e?.name ?: missing)
        if (star != Standards.Star.NONE) withStyle(
            SpanStyle(color = starColor(star), fontSize = 0.7.em),
        ) { append("\u00A0\u00A0★") }
        // Developer trial: red ★ when the enhanced 1 Rep Max estimate is learning from this exercise.
        if (dev) withStyle(SpanStyle(color = Red, fontSize = 0.7.em)) { append("\u00A0★") }
    }
}

fun rankText(pct: Double) = "${Tier.of(pct).label} (${topLabel(pct)})"

// ---------------- Home: overall rank, rank heatmap and legend ----------------

@Composable
fun HomeRankCard() {
    val workouts by Store.workouts.collectAsState()
    val profile by Store.profile.collectAsState()
    val exercises by Store.exercises.collectAsState()
    if (Standards.mode(profile) == Standards.Mode.OFF) return
    val ranks = remember(workouts, profile, exercises) { Ranks.all(workouts, profile) }
    val overall = Ranks.overallOf(ranks, profile)
    var open by homeRanksOpen
    var picking by remember { mutableStateOf<String?>(null) }
    picking?.let { g -> GroupPickDialog(g, Ranks.groupCandidates(ranks)[g].orEmpty(), profile) { picking = null } }
    Column(Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
        if (overall == null) {
            Text("Your rank", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    profile.bodyweight == null -> stars("Add your body weight (and age) in Settings, then log an exercise marked ★ to get ranked against other lifters.")
                    Standards.mode(profile) == Standards.Mode.ACCURATE && StandardsData.all.none { Standards.covers(it.id, profile) } ->
                        AnnotatedString("The research data doesn't cover your exact body weight/age closely enough for Accurate-only ranks. " +
                            "Switch Ranks to \"All on\" in Settings to rank anyway.")
                    else -> stars("Log an exercise marked ★ to get ranked against other lifters your size, age and sex.")
                },
                color = Sub, fontSize = 14.sp,
            )
            if (profile.bodyweight == null) { Spacer(8); GreyButton("Open Profile") { Nav.home(2) } }
            return@Column
        }
        val t = Tier.of(overall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankBadge(t, 84.dp)
            HSpacer(12)
            Column {
                Text("Current overall rank", color = Sub, fontSize = 13.sp)
                Text(t.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(t.color))
                Text(topLabel(overall), fontSize = 15.sp)
                val groupCount = Ranks.groupRanks(ranks, profile.picksNow).size
                Text("Average of your $groupCount muscle group${if (groupCount == 1) "" else "s"}", color = Sub, fontSize = 12.sp)
            }
        }
        // Each ranked muscle is coloured by its group's rank: the average of your top two exercises for it (as in the list below).
        // Every muscle of a ranked group is coloured with that group's rank (the hands only by the Grip rank when it's on).
        RankHeatmap(ranks, profile)
        Text(
            if (open) "Hide Muscle Group Rankings ▴" else "Show Muscle Group Rankings ▾",
            color = Blue, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
        )
        Reveal(open) { Column {
            Text("Each muscle group's rank is the average of two ranked exercises; your top two unless you choose (tap ✎). " +
                "These directly influence your overall rank.", color = Sub, fontSize = 12.sp)
            Ranks.groupRanks(ranks, profile.picksNow).entries.sortedByDescending { it.value.first }.forEachIndexed { gi, (g, v) ->
                val (pct, top) = v
                // A line between muscle groups.
                if (gi > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 10.dp), color = Card2)
                val tier = Tier.of(pct)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(tier.color)))
                        HSpacer(8)
                        // The rank is measured first and always fits on one line; a long muscle name shortens with "…" instead.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            NameText(AnnotatedString(titleCase(g)), Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, minScale = 0.72f)
                            Text("  ✎", color = Blue, fontSize = 15.sp, maxLines = 1, softWrap = false, modifier = Modifier.clickable { picking = g }.padding(horizontal = 4.dp))
                        }
                        RankBadge(tier, 22.dp)
                        HSpacer(4)
                        Text(rankText(pct), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(tier.color), maxLines = 1, softWrap = false)
                    }
                    // The group's exercises hang under it; each has its own piece of the line on the left, in its rank colour.
                    Column(Modifier.padding(start = 6.dp, top = 4.dp).fillMaxWidth()) {
                    top.forEach { r ->
                        Row(Modifier.fillMaxWidth().drawBehind {
                            drawLine(Color(r.tier.color).copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, 3.dp.toPx()),
                                androidx.compose.ui.geometry.Offset(0f, size.height - 3.dp.toPx()), 2.dp.toPx())
                        }.clip(RoundedCornerShape(8.dp)).clickable { Nav.go(Route.RankDetail(r.exercise.id)) }
                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            NameText(starredName(r.exercise), color = Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(rankText(r.percentile), fontSize = 13.sp, color = Color(r.tier.color), maxLines = 1, softWrap = false)
                            Text(" ›", color = Sub, fontSize = 16.sp)
                        }
                    }
                    }
                    if (g == Ranks.GRIP) GripTestButton()
                    else if (top.size == 1) Text("Rank one more exercise for this muscle to average two.", color = Sub, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 22.dp))
                }
            }
            // Muscle groups with rankable exercises that you don't have a rank for yet (e.g. calves, forearms).
            val rankedGroups = Ranks.groupRanks(ranks, profile.picksNow).keys
            Muscles.groups.keys.filter { g -> g !in rankedGroups && g !in Ranks.NOT_ON_HOME }.forEach { g ->
                val rankable = StandardsData.all.filter { (!it.aiFor(profile.male) || Standards.mode(profile) == Standards.Mode.AI) && !(profile.gripRank && it.flat) }.mapNotNull { Store.exercise(it.id) }.filter { g in it.primary && !it.hidden }
                if (rankable.isEmpty()) return@forEach
                val forYou = rankable.filter { Standards.covers(it.id, profile) }
                if (forYou.isEmpty()) return@forEach // nothing for this muscle is ranked in your mode: leave it off
                androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 10.dp), color = Card2)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Card2))
                        HSpacer(8)
                        Text(titleCase(g), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Sub, modifier = Modifier.weight(1f))
                        Text("Not ranked yet", fontSize = 13.sp, color = Sub)
                    }
                    Text(
                        stars("Log " + forYou.take(3).joinToString(", ") { it.name } + (if (forYou.size > 3) " or another ★ exercise" else "") + " to rank it."),
                        color = Sub, fontSize = 11.sp, modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
            if (profile.gripRank && Ranks.GRIP !in rankedGroups) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(bottom = 8.dp), color = Card2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Card2))
                    HSpacer(8)
                    Text("Grip", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Sub, modifier = Modifier.weight(1f))
                    Text("Not ranked yet", fontSize = 13.sp, color = Sub)
                }
                Text("Do a Grip Dynamometer Test to rank your grip and color your hands. (Turn the Grip rank off in Settings.)",
                    color = Sub, fontSize = 11.sp, modifier = Modifier.padding(start = 22.dp))
                GripTestButton()
            }
            Spacer(10)
            Text("Rank Guide", fontWeight = FontWeight.Bold)
            Tier.entries.reversed().forEach { tier ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(tier.color)))
                    HSpacer(8)
                    RankBadge(tier, 26.dp)
                    HSpacer(8)
                    Text("${tier.label} (Top ${tier.topPct}%)", fontSize = 14.sp)
                }
            }
            Text("Uncolored muscles have no ranked exercise yet.", color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}

/** Every muscle of a ranked group coloured with that group's rank, with the Wood → Legend colour bar under it. */
@Composable
fun RankHeatmap(ranks: Map<String, Ranks.Ranked>, profile: Profile, checkpoints: Boolean = false) {
    val muscles = remember(ranks, profile) {
        val groups = Ranks.groupRanks(ranks, profile.picksNow)
        val m = HashMap<String, Double>()
        for ((g, v) in groups) {
            if (g == Ranks.GRIP) continue
            Muscles.groups[g].orEmpty().forEach { h -> if (!(profile.gripRank && h == Ranks.HANDS)) m[h] = v.first }
        }
        if (profile.gripRank) groups[Ranks.GRIP]?.let { m[Ranks.HANDS] = it.first }
        m
    }
    val colors = muscles.mapValues { Tier.of(it.value).color.toInt() }
    val levels = muscles.mapValues { (it.value / 100).toFloat().coerceAtLeast(0.01f) }
    BodyMapView(levels, Modifier.padding(top = 10.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
        colors = colors)
    // Every rank colour in order, blended: Wood 1 on the left to Legend on the right.
    if (checkpoints) LevelMarks(top = true)
    Box(Modifier.padding(top = if (checkpoints) 0.dp else 10.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(Tier.entries.map { Color(it.color) })))
    if (checkpoints) LevelMarks(top = false)
    else Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text("Wood", color = Sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("Legend", color = Sub, fontSize = 11.sp)
    }
}

/**
 * Tick marks at the five levels where each sits on the colour bar: the rank there above the bar (in its colour),
 * and the plain words (Beginner … Elite) below it, so anyone can tell roughly what a colour means.
 */
@Composable
private fun LevelMarks(top: Boolean) {
    val sub = Sub
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5.sp.toPx() }
        val gap = 6.dp.toPx()
        val pcts = Standards.LEVEL_PCT
        val texts = Standards.LEVEL_NAMES.indices.map { if (top) Tier.of(pcts[it]).label else Standards.LEVEL_NAMES[it] }
        // All on one line: smaller text if they'd otherwise not fit side by side.
        val total = texts.sumOf { paint.measureText(it).toDouble() }.toFloat() + gap * (texts.size - 1)
        if (total > size.width) paint.textSize *= size.width / total
        val w = texts.map { paint.measureText(it) }
        val xs = pcts.indices.map { (pcts[it] / 100.0).toFloat() * size.width }
        // Each word as close to its mark as it can be, nudged sideways so none touch.
        val left = FloatArray(texts.size) { xs[it] - w[it] / 2 }
        for (i in left.indices) left[i] = maxOf(left[i], if (i == 0) 0f else left[i - 1] + w[i - 1] + gap)
        for (i in left.indices.reversed()) left[i] = minOf(left[i], if (i == left.lastIndex) size.width - w[i] else left[i + 1] - gap - w[i])
        val fm = paint.fontMetrics
        val baseline = if (top) size.height - 9.dp.toPx() - fm.descent else 9.dp.toPx() - fm.ascent
        texts.indices.forEach { i ->
            paint.color = if (top) Tier.of(pcts[i]).color.toInt() else sub.toArgb()
            drawContext.canvas.nativeCanvas.drawText(texts[i], left[i], baseline, paint)
            // The mark starts at its true spot on the bar and leans over to its word if the word had to move.
            val to = xs[i].coerceIn(left[i] + 3.dp.toPx(), left[i] + w[i] - 3.dp.toPx())
            val bar = if (top) size.height else 0f
            val word = if (top) baseline + fm.descent + 1.dp.toPx() else baseline + fm.ascent - 1.dp.toPx()
            drawLine(sub.copy(alpha = 0.8f), Offset(xs[i], bar), Offset(to, word), strokeWidth = 1.2.dp.toPx())
        }
    }
}

/** Home, under Grip: straight into a 1 Rep Max mode dynamometer test, until you've logged one. */
@Composable
private fun GripTestButton() {
    val id = StandardsData.all.firstOrNull { it.flat }?.id ?: return
    val workouts by Store.workouts.collectAsState()
    if (workouts.any { w -> w.items.any { it.exerciseId == id && it.sets.any { s -> s.done && !s.warmup } } }) return
    Text("🏆 Test your grip (1 Rep Max mode) ›", color = StarGold, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 4.dp).clip(RoundedCornerShape(8.dp))
            .clickable { OneRm.add(id); Nav.go(Route.OneRmTest(id)) }.padding(vertical = 4.dp))
}

// ---------------- Exercise page ----------------


// ---------------- Rank-up celebration ----------------

/** Shows queued rank-ups one at a time over everything. Tap to continue. */
@Composable
fun RankUpOverlay() {
    val c = Ranks.celebrations.firstOrNull() ?: return
    if (c.down) { RankDownView(c); return }
    val context = LocalContext.current
    val pop = remember(c) { Animatable(0f) }
    val burst = remember(c) { Animatable(0f) }
    LaunchedEffect(c) {
        runCatching {
            val v = context.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 120), -1))
        }
        kotlinx.coroutines.coroutineScope {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }
            launch { burst.animateTo(1f, tween(1400)) }
        }
        delay(3500)
        Ranks.celebrations.remove(c)
    }
    val spin = rememberInfiniteTransition(label = "rays")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val color = Color(c.tier.color)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))
            .clickable(remember { MutableInteractionSource() }, null) { Ranks.celebrations.remove(c) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(360.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            // Turning rays.
            rotate(angle, center) {
                for (i in 0 until 16) {
                    val a = Math.toRadians(i * 22.5).toFloat()
                    val a2 = a + 0.12f
                    val r = size.minDimension / 2
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(center.x, center.y)
                        lineTo(center.x + r * cos(a), center.y + r * sin(a))
                        lineTo(center.x + r * cos(a2), center.y + r * sin(a2))
                        close()
                    }
                    drawPath(path, color.copy(alpha = 0.18f * pop.value))
                }
            }
            // Sparkles flying out.
            val t = burst.value
            for (i in 0 until 24) {
                val a = i * 15.0 * Math.PI / 180 + (i % 3) * 0.2
                val dist = (60 + (i % 5) * 22) * density * t + 40 * density
                drawCircle(
                    if (i % 2 == 0) color else Color.White,
                    radius = (5 - (i % 3)) * density * (1 - t * 0.6f),
                    center = Offset(center.x + (dist * cos(a)).toFloat(), center.y + (dist * sin(a)).toFloat()),
                    alpha = (1 - t).coerceIn(0f, 1f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(c.subtitle.uppercase(), color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.graphicsLayer { alpha = pop.value.coerceIn(0f, 1f) })
            Spacer(8)
            RankBadge(c.tier, 170.dp, Modifier.graphicsLayer {
                scaleX = pop.value; scaleY = pop.value; rotationZ = (1 - pop.value) * -40f
            })
            Spacer(8)
            Text(c.tier.label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(topLabel(c.percentile), color = Color.White, fontSize = 18.sp)
            Spacer(6)
            Text(c.title, color = Sub, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
            c.note?.let { Spacer(6); Text(it, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp)) }
            Spacer(18)
            Text("Tap to continue", color = Sub, fontSize = 12.sp)
        }
    }
}

/** A rank going down: the old badge sinks and fades, the new one drops in below a red arrow. */
@Composable
private fun RankDownView(c: Ranks.Celebration) {
    val context = LocalContext.current
    val drop = remember(c) { Animatable(0f) }   // 0 = old badge, 1 = new badge
    val arrow = remember(c) { Animatable(0f) }
    LaunchedEffect(c) {
        runCatching {
            context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(250, 120))
        }
        delay(700)
        kotlinx.coroutines.coroutineScope {
            launch { arrow.animateTo(1f, tween(500)) }
            launch { drop.animateTo(1f, tween(1100)) }
        }
        delay(4000)
        Ranks.celebrations.remove(c)
    }
    val red = Color(0xFFFF453A)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
            .clickable(remember { MutableInteractionSource() }, null) { Ranks.celebrations.remove(c) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("RANK DOWN", color = red, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(8)
            Box(Modifier.size(width = 220.dp, height = 210.dp), contentAlignment = Alignment.Center) {
                val t = drop.value
                // The old rank sinks, shrinks and greys out.
                c.from?.let { old ->
                    RankBadge(old, 150.dp, Modifier.graphicsLayer {
                        translationY = t * 160f; alpha = 1f - t; scaleX = 1f - t * 0.4f; scaleY = 1f - t * 0.4f
                    })
                }
                // The new rank falls into place.
                RankBadge(c.tier, 150.dp, Modifier.graphicsLayer {
                    translationY = (1f - t) * -160f; alpha = t
                })
                Text("▼", color = red, fontSize = 40.sp, modifier = Modifier.align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = arrow.value; translationY = arrow.value * 20f })
            }
            Spacer(8)
            Text(
                buildAnnotatedString {
                    c.from?.let { withStyle(SpanStyle(color = Sub)) { append(it.label + "  →  ") } }
                    append(c.tier.label)
                },
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            )
            Text(topLabel(c.percentile), color = Color.White, fontSize = 18.sp)
            Spacer(6)
            Text(c.title, color = Sub, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.width(300.dp))
            Spacer(6)
            Text(c.note ?: "Your best from your latest session is now lower than your old record.", color = Sub, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.width(280.dp))
            Spacer(18)
            Text("Tap to continue", color = Sub, fontSize = 12.sp)
        }
    }
}

/** Choose which two ranked exercises stand for a muscle group on Home (default: your top two). */
@Composable
private fun GroupPickDialog(group: String, candidates: List<Ranks.Ranked>, profile: Profile, onDone: () -> Unit) {
    val current = Ranks.groupRanks(candidates.associateBy { it.exercise.id }, profile.picksNow)[group]?.second.orEmpty().map { it.exercise.id }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(current) } }
    fun save(picks: List<String>) {
        Store.setProfile(Store.profile.value.withPicks(group, picks))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text(titleCase(group)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Pick up to two exercises to average for this muscle.", color = Sub, fontSize = 13.sp)
                candidates.forEach { r ->
                    val on = r.exercise.id in chosen
                    Row(Modifier.fillMaxWidth().clickable {
                        if (on) chosen.remove(r.exercise.id) else { if (chosen.size >= 2) chosen.removeAt(0); chosen.add(r.exercise.id) }
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(on, null)
                        NameText(starredName(r.exercise), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(rankText(r.percentile), fontSize = 12.sp, color = Color(r.tier.color), maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { save(chosen.toList()); onDone() }, enabled = chosen.isNotEmpty()) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { save(emptyList()); onDone() }) { Text("Use my top two") } },
    )
}

/** Text whose ★ is coloured only where it names a colour ("gold ★", "blue ★", "purple ★"); general mentions stay plain. */
fun stars(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val j = s.indexOf('★', i)
        if (j < 0) { append(s.substring(i)); break }
        append(s.substring(i, j))
        val before = s.substring(maxOf(0, j - 10), j).lowercase()
        // Only a star named by its colour gets that colour; a star talked about in general stays plain.
        val c = when { "gold" in before -> StarGold; "blue" in before -> StarEstimate; "purple" in before -> StarAi; else -> null }
        if (c != null) withStyle(SpanStyle(color = c)) { append("★") } else append("★")
        i = j + 1
    }
}

/**
 * An exercise (or muscle) name that never leaves its ★ alone on a line: if it's only a little too long for one
 * line it shrinks slightly to fit; if it really needs more lines, the lines are balanced to about the same length.
 * The size is worked out before the first frame is drawn, so nothing jumps or wiggles while it settles.
 */
@Composable
fun NameText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    minScale: Float = 0.88f,
) {
    val style = androidx.compose.material3.LocalTextStyle.current
    val base = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize else style.fontSize
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        // 0 = doesn't fit on one line even at the smallest size: wrap instead.
        val scale = remember(text, base, maxW, fontWeight) {
            if (maxW == androidx.compose.ui.unit.Constraints.Infinity) 1f else {
                var s = 1f
                var fits = false
                while (s >= minScale - 0.001f) {
                    val r = measurer.measure(text, style.merge(androidx.compose.ui.text.TextStyle(fontSize = base * s, fontWeight = fontWeight)),
                        maxLines = 1, softWrap = false, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW))
                    if (!r.hasVisualOverflow) { fits = true; break }
                    s -= 0.03f
                }
                if (fits) s else 0f
            }
        }
        val wrap = scale == 0f
        Text(
            text, color = color, fontSize = if (wrap) base else base * scale, fontWeight = fontWeight,
            maxLines = if (wrap) maxLines else 1, softWrap = wrap,
            overflow = if (wrap) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
            style = style.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
        )
    }
}

/** Kept outside the screen so the muscle-group list is still open when you come back from an exercise. */
private val homeRanksOpen = mutableStateOf(false)
