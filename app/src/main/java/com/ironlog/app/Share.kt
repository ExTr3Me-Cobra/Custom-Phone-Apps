package com.ironlog.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Home's Share: tick one, two or all three pictures, watch the previews change, then save them or send them together as JPGs. */
@Composable
fun ShareScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workouts by Store.workouts.collectAsState()
    val profile by Store.profile.collectAsState()
    val exercises by Store.exercises.collectAsState()
    val ranks = remember(workouts, profile, exercises) { Ranks.all(workouts, profile) }
    val overall = Ranks.overallOf(ranks, profile)
    val ranked = ranks.values.sortedByDescending { it.percentile }
    // Each ticked thing becomes its own picture.
    var pickOverall by rememberSaveable { mutableStateOf(true) }
    var pickGroups by rememberSaveable { mutableStateOf(false) }
    var pickExercise by rememberSaveable { mutableStateOf(false) }
    // The exercises, one picture each (kept as one line per exercise id so it survives rotation).
    var exSaved by rememberSaveable { mutableStateOf("") }
    val exIds = exSaved.split("\n").filter { it.isNotEmpty() }
    fun setExIds(l: List<String>) { exSaved = l.joinToString("\n") }
    val exList = exIds.mapNotNull { id -> ranked.firstOrNull { it.exercise.id == id } }.ifEmpty { listOfNotNull(ranked.firstOrNull()) }
    var exMenu by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    // Each preview's drawing, by its place in the list of pictures.
    val layers = remember { mutableMapOf<Int, androidx.compose.ui.graphics.layer.GraphicsLayer>() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Share")
        if (Standards.mode(profile) == Standards.Mode.OFF || overall == null || ranked.isEmpty()) {
            Text(stars(
                if (Standards.mode(profile) == Standards.Mode.OFF) "Ranks are off. Turn them on in Settings to share your ranks."
                else "Nothing to share yet – log an exercise marked ★ to get a rank first."),
                color = Sub, modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pictures to make", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Each one you tick is its own picture.", color = Sub, fontSize = 13.sp)
            val count = listOf(pickOverall, pickGroups, pickExercise).count { it }
            @Composable fun Pick(on: Boolean, label: String, set: (Boolean) -> Unit) {
                // At least one always stays ticked.
                val flip = { if (!on || count > 1) set(!on) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { flip() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { flip() })
                    Text(label)
                }
            }
            Pick(pickOverall, "Overall rank + heatmap") { pickOverall = it }
            Pick(pickGroups, "Muscle group ranks") { pickGroups = it }
            Pick(pickExercise, "An exercise's rank + where you sit chart") { pickExercise = it }
            if (pickExercise) Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                exList.forEachIndexed { i, ex ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f, fill = false)) {
                            GreyButton(ex.exercise.name + "  ▾") { exMenu = i }
                            DropdownMenu(exMenu == i, { exMenu = -1 }) {
                                ranked.forEach { r ->
                                    DropdownMenuItem({
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RankBadge(r.tier, 20.dp)
                                            Text("  " + r.exercise.name, Modifier.weight(1f, fill = false))
                                            Text("  " + r.tier.label, color = Color(r.tier.color), fontSize = 12.sp)
                                        }
                                    }, {
                                        setExIds(exList.map { it.exercise.id }.toMutableList().also { l -> l[i] = r.exercise.id })
                                        exMenu = -1
                                    })
                                }
                            }
                        }
                        if (exList.size > 1) Text("✕", color = Sub, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable { setExIds(exList.map { it.exercise.id }.filterIndexed { j, _ -> j != i }) }.padding(10.dp))
                    }
                }
                if (exList.size < ranked.size) Text("+  Add another exercise", color = Blue, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        val have = exList.map { it.exercise.id }
                        // The next best-ranked one you haven't added yet.
                        val next = ranked.firstOrNull { it.exercise.id !in have } ?: return@clickable
                        setExIds(have + next.exercise.id)
                    }.padding(vertical = 8.dp, horizontal = 4.dp))
            }
            // (kind, exercise) for each picture.
            val picked = listOfNotNull("overall".takeIf { pickOverall }, "groups".takeIf { pickGroups }).map { it to null } +
                (if (pickExercise) exList.map { "exercise" to it.exercise.id } else emptyList())
            Text(if (picked.size == 1) "Preview" else "Preview (${picked.size} pictures)", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            picked.forEachIndexed { i, (what, id) -> androidx.compose.runtime.key(i, what, id) {
                val layer = rememberGraphicsLayer()
                layers[i] = layer
                // Each picture is exactly what's drawn here.
                Box(Modifier.fillMaxWidth().padding(top = if (i > 0) 12.dp else 0.dp).drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                    ShareCard(ranks, profile, overall, what, id)
                }
            } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fun make(then: suspend (List<Bitmap>) -> Unit) {
                    if (busy) return
                    busy = true
                    scope.launch {
                        runCatching {
                            val bmps = picked.indices.map { layers.getValue(it).toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false) }
                            then(bmps)
                        }.onFailure { Toast.makeText(context, "Couldn't make the picture: ${it.message}", Toast.LENGTH_LONG).show() }
                        busy = false
                    }
                }
                GreyButton("⬇  Save to gallery", Modifier.weight(1f)) {
                    make { bmps ->
                        val saved = withContext(Dispatchers.IO) { bmps.count { ShareImage.save(context, it) } }
                        Toast.makeText(context, when {
                            saved == bmps.size && saved == 1 -> "Saved to Pictures/Loadout"
                            saved == bmps.size -> "Saved $saved pictures to Pictures/Loadout"
                            else -> "Couldn't save ${bmps.size - saved} of the pictures"
                        }, Toast.LENGTH_SHORT).show()
                    }
                }
                BigButton("Share", modifier = Modifier.weight(1f)) {
                    make { bmps ->
                        val files = withContext(Dispatchers.IO) { ShareImage.files(context, bmps) }
                        ShareImage.send(context, files)
                    }
                }
            }
            Spacer(30)
        }
    }
}

/** A finished workout's Share (top left of its Summary): pick one or both pictures, then save or send them. */
@Composable
fun WorkoutShareScreen(id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val w = Store.workouts.collectAsState().value.firstOrNull { it.id == id } ?: return closeScreen()
    var pickSummary by rememberSaveable { mutableStateOf(true) }
    var pickMuscles by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val layers = remember { mutableMapOf<Int, androidx.compose.ui.graphics.layer.GraphicsLayer>() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Share workout")
        Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pictures to make", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Each one you tick is its own picture.", color = Sub, fontSize = 13.sp)
            val count = listOf(pickSummary, pickMuscles).count { it }
            @Composable fun Pick(on: Boolean, label: String, set: (Boolean) -> Unit) {
                val flip = { if (!on || count > 1) set(!on) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { flip() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { flip() })
                    Text(label)
                }
            }
            Pick(pickSummary, "Workout + muscles worked map") { pickSummary = it }
            Pick(pickMuscles, "Muscle breakdown") { pickMuscles = it }
            val picked = listOfNotNull("summary".takeIf { pickSummary }, "muscles".takeIf { pickMuscles })
            Text(if (picked.size == 1) "Preview" else "Preview (${picked.size} pictures)", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            picked.forEachIndexed { i, what -> androidx.compose.runtime.key(i, what) {
                val layer = rememberGraphicsLayer()
                layers[i] = layer
                Box(Modifier.fillMaxWidth().padding(top = if (i > 0) 12.dp else 0.dp).drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                    CardFrame {
                        Text(w.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(Units.date(w.start), color = Sub, modifier = Modifier.padding(bottom = 10.dp))
                        if (what == "summary") {
                            WorkoutStats(w)
                            Box(Modifier.padding(top = 10.dp)) { WorkoutHeatmap(w) }
                        } else MuscleBreakdown(w)
                    }
                }
            } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fun make(then: suspend (List<Bitmap>) -> Unit) {
                    if (busy) return
                    busy = true
                    scope.launch {
                        runCatching {
                            val bmps = picked.indices.map { layers.getValue(it).toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false) }
                            then(bmps)
                        }.onFailure { Toast.makeText(context, "Couldn't make the picture: ${it.message}", Toast.LENGTH_LONG).show() }
                        busy = false
                    }
                }
                GreyButton("⬇  Save to gallery", Modifier.weight(1f)) {
                    make { bmps ->
                        val saved = withContext(Dispatchers.IO) { bmps.count { ShareImage.save(context, it) } }
                        Toast.makeText(context, if (saved == bmps.size) "Saved to Pictures/Loadout" else "Couldn't save ${bmps.size - saved} of the pictures",
                            Toast.LENGTH_SHORT).show()
                    }
                }
                BigButton("Share", modifier = Modifier.weight(1f)) {
                    make { bmps ->
                        val files = withContext(Dispatchers.IO) { ShareImage.files(context, bmps) }
                        ShareImage.send(context, files, "Share your workout")
                    }
                }
            }
            Spacer(30)
        }
    }
}

/** A 9:16 share picture: [content] fills it (shrunk to fit if needed), with the Loadout logo at the bottom. */
@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color(0xFF0B0B0D)).padding(16.dp)) {
        FitInside(Modifier.weight(1f).fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { content() } }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(64.dp))
            Text("Loadout", fontWeight = FontWeight.Bold, fontSize = 26.sp)
        }
    }
}

/** The picture itself: a 9:16 card (story-sized) with the one thing you picked, and the Loadout logo at the bottom. */
@Composable
private fun ShareCard(ranks: Map<String, Ranks.Ranked>, profile: Profile, overall: Double, what: String, exerciseId: String?) {
    Column(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color(0xFF0B0B0D)).padding(16.dp)) {
        // Whatever's picked fills the space; if it's ever too tall, it shrinks to fit rather than being cut off.
        FitInside(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                when (what) {
                    "groups" -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Muscle ranks", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Ranks.groupRanks(ranks, profile.picksNow).entries.sortedByDescending { it.value.first }.forEach { (g, v) ->
                            val tier = Tier.of(v.first)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(tier.color)))
                                HSpacer(8)
                                // The rank always fits; a long muscle name shrinks a little instead of being cut off.
                                NameText(androidx.compose.ui.text.AnnotatedString(titleCase(g)), Modifier.weight(1f), fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1, minScale = 0.6f)
                                HSpacer(6)
                                RankBadge(tier, 22.dp)
                                HSpacer(4)
                                Text(rankText(v.first), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(tier.color), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    "exercise" -> if (exerciseId != null) {
                        val e = Store.exercise(exerciseId)
                        val panels = rankPanels(exerciseId, share = true)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NameText(starredName(e), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            panels.firstOrNull { it.key == "header" }?.content?.invoke()
                            panels.firstOrNull { it.key == "chart" }?.let { pn -> Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0B0D))) { pn.content() } }
                        }
                    }
                    else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
                        val t = Tier.of(overall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RankBadge(t, 84.dp)
                            HSpacer(12)
                            Column {
                                Text("Overall rank", color = Sub, fontSize = 13.sp)
                                Text(t.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(t.color))
                                Text(topLabel(overall), fontSize = 15.sp)
                            }
                        }
                        // In plain words, for whoever you share it with.
                        Text("(Overall body rank is better than ${betterThan(overall)}% of weight lifters with comparable sex, bodyweight, and age.)",
                            color = Sub, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                        RankHeatmap(ranks, profile, checkpoints = true)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(64.dp))
            Text("Loadout", fontWeight = FontWeight.Bold, fontSize = 26.sp)
        }
    }
}

/** Centres its content; if the content is taller than the space, it's scaled down to fit (never cropped). */
@Composable
private fun FitInside(modifier: Modifier, content: @Composable () -> Unit) = androidx.compose.ui.layout.Layout(content, modifier) { ms, c ->
    val p = ms.first().measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val s = if (p.height > c.maxHeight) c.maxHeight.toFloat() / p.height else 1f
    layout(c.maxWidth, c.maxHeight) {
        p.placeWithLayer((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) { scaleX = s; scaleY = s }
    }
}

/** Turning the picture into a JPG in your gallery, or handing it to another app. */
object ShareImage {
    /** Saves into Pictures/Loadout (no permission needed). */
    fun save(context: Context, bmp: Bitmap): Boolean {
        val r = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Loadout_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Loadout")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        return runCatching {
            r.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse { r.delete(uri, null, null); false }
    }

    /** One JPG per picture in the share folder; old ones are cleared first. */
    fun files(context: Context, bmps: List<Bitmap>): List<File> {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        return bmps.mapIndexed { i, bmp ->
            File(dir, "Loadout_${stamp}_${i + 1}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        }
    }

    /** Android's share menu with every JPG attached, sent together. */
    fun send(context: Context, files: List<File>, title: String = "Share your ranks") {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Loadout", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        context.startActivity(Intent.createChooser(send, title))
    }
}
