package com.ironlog.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ring colours, the rank tiers' colours: wood, bronze, silver, gold, platinum, diamond, champion, legend. */
private val TIER_COLORS = listOf(Tier.WOOD_2, Tier.BRONZE_2, Tier.SILVER_2, Tier.GOLD_2, Tier.PLATINUM_2, Tier.DIAMOND_2,
    Tier.CHAMPION, Tier.LEGEND).map { Color(it.color) }
/** A badge's colour: its level spread over wood to legend (see Badges.step). */
fun badgeColor(b: Badges.Badge) = TIER_COLORS[Badges.step(b).coerceIn(0, TIER_COLORS.lastIndex)]

/** Picture cache: a family's picture as it is now (made, picked, or built in). */
object BadgePictures {
    private val cache = HashMap<String, Pair<Long, ImageBitmap?>>()
    /** Changes whenever a picture is made or replaced, so screens redraw. */
    var version by mutableIntStateOf(0)

    /** A badge's picture: its own, else its family's (made, picked, or built in). */
    fun get(c: android.content.Context, b: Badges.Badge): ImageBitmap? = one(c, b.id) ?: one(c, b.family.id)

    private fun one(c: android.content.Context, id: String): ImageBitmap? {
        val f = Badges.pictureFile(id)
        val stamp = if (f.exists()) f.lastModified() else -1L
        cache[id]?.takeIf { it.first == stamp }?.let { return it.second }
        val img = runCatching {
            if (f.exists()) BitmapFactory.decodeFile(f.path)
            else c.assets.open("badges/$id.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap()
        cache[id] = stamp to img
        return img
    }

    /** Earlier pictures of a badge, newest last (Undo steps back through them, then to the emoji). */
    private fun historyDir(id: String) = java.io.File(Badges.pictureFile(id).parentFile, "history/$id")
    private fun history(id: String) = historyDir(id).listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()

    /** The picture before the current one (shown on the left), or null. */
    fun previous(id: String): ImageBitmap? = history(id).lastOrNull()?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
    fun hasPicture(id: String) = Badges.pictureFile(id).exists()

    /** Saves a picture for a badge (scaled to 512 px); the one it replaces goes into its history. */
    fun save(id: String, bmp: Bitmap) {
        val s = 512f / maxOf(bmp.width, bmp.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val f = Badges.pictureFile(id).apply { parentFile?.mkdirs() }
        if (f.exists()) historyDir(id).apply { mkdirs() }.let { d -> f.copyTo(java.io.File(d, "${System.currentTimeMillis()}.png"), overwrite = true) }
        f.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        version++
    }

    /** Back one step: the previous picture, or (with none left) no picture, so the emoji shows. */
    fun undo(id: String) {
        val f = Badges.pictureFile(id)
        val last = history(id).lastOrNull()
        if (last != null) { last.copyTo(f, overwrite = true); last.delete() } else f.delete()
        version++
    }

    /** Every badge picture and its history gone: all back to emojis. */
    fun undoAll() {
        Badges.pictureFile("x").parentFile?.deleteRecursively()
        cache.clear()
        version++
    }
}

/** A badge: its family's picture (or emoji until there is one) in a ring of its tier's colour, numeral underneath. */
@Composable
fun BadgeIcon(b: Badges.Badge, earned: Boolean, size: Dp, modifier: Modifier = Modifier, picture: ImageBitmap? = null, usePicture: Boolean = false) {
    val c = LocalContext.current
    val v = BadgePictures.version
    val img = if (usePicture) picture else remember(b.id, v) { BadgePictures.get(c, b) }
    val ring = badgeColor(b)
    val silhouette = remember { ColorFilter.tint(Color(0xFF5A5A5E), androidx.compose.ui.graphics.BlendMode.SrcIn) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Card2)
                .border((size.value / 16).dp, if (earned) ring else Color(0xFF48484A), CircleShape)
                .alpha(if (earned) 1f else 0.45f),
            contentAlignment = Alignment.Center,
        ) {
            // Not earned yet: just its silhouette.
            if (img != null) Image(img, b.name, Modifier.size(size * 0.82f), colorFilter = if (earned) null else silhouette)
            else Text(b.family.emoji, fontSize = (size.value * 0.42f).sp)
        }
        if (b.family.targets.size > 1) Text(
            Badges.roman(b.tier + 1), fontSize = (size.value * 0.16f).coerceAtLeast(10f).sp, fontWeight = FontWeight.Bold,
            color = if (earned) Color(0xFF111114) else Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(6.dp))
                .background(if (earned) ring else Color(0xFF48484A)).padding(horizontal = 5.dp),
        )
    }
}

/** Every badge's progress, worked out off the main thread. */
@Composable
fun rememberBadges(): Badges.State? {
    val workouts by Store.workouts.collectAsState()
    val profile by Store.profile.collectAsState()
    val routines by Store.routines.collectAsState()
    return produceState<Badges.State?>(null, workouts, profile, routines) {
        value = withContext(Dispatchers.Default) { runCatching { Badges.compute(workouts, profile) }.getOrNull() }
    }.value
}

/** All badges, a heading per set: earned in colour, the rest as silhouettes. Tap one for its requirement and progress. */
@Composable
fun BadgesScreen() {
    val state = rememberBadges()
    var open by remember { mutableStateOf<Badges.Badge?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // Export: every badge's progress as text, through the share menu (or copy it).
        TopBar("Badges", action = "Export") {
            state?.let { s ->
                val text = Badges.report(s)
                context.startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
                    "Export badge progress"))
            }
        }
        if (state == null) { Text("Counting…", color = Sub, modifier = Modifier.padding(16.dp)); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${state.earned.size} of ${Badges.all.size} earned", color = Sub, modifier = Modifier.padding(horizontal = 4.dp))
            }
            // One heading per badge set.
            for (f in Badges.families) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(f.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                }
                for (i in f.targets.indices) {
                    val b = Badges.Badge(f, i)
                    item(key = b.id) {
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = b }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgeIcon(b, b.id in state.earned, 64.dp)
                            Text(b.name, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp,
                                color = if (b.id in state.earned) Color.White else Sub, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    open?.let { b -> BadgeDialog(b, state!!) { open = null } }
}

/** A badge's pop-up: what it takes, how far along you are, when you earned it, and the family's other tiers. */
@Composable
fun BadgeDialog(b: Badges.Badge, state: Badges.State, onClose: () -> Unit) {
    val value = b.family.value(state.progress)
    val earnedAt = state.earned[b.id]
    val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgeIcon(b, earnedAt != null, 110.dp)
                Text(b.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(b.requirement, color = Sub, textAlign = TextAlign.Center)
                val frac = (value / b.target).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Card2)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (earnedAt != null) badgeColor(b) else Blue))
                }
                val lab = { x: Double -> with(Badges) { b.family.label(x) } }
                Text("${lab(if (earnedAt != null) b.target else minOf(value, b.target))} / ${lab(b.target)} ${b.family.unit}".trim(),
                    fontWeight = FontWeight.Medium)
                Text(if (earnedAt != null) "Earned ${fmt.format(java.util.Date(earnedAt))}" else "Not earned yet",
                    color = if (earnedAt != null) badgeColor(b) else Sub)
                if (b.family.targets.size > 1) {
                    Text("All levels", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    b.family.targets.indices.forEach { i ->
                        val t = Badges.Badge(b.family, i)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.id in state.earned) "✓" else "·", color = if (t.id in state.earned) badgeColor(t) else Sub,
                                fontWeight = FontWeight.Bold, modifier = Modifier.size(width = 22.dp, height = 20.dp))
                            Text(t.name, Modifier.weight(1f), fontSize = 14.sp)
                            Text(with(Badges) { b.family.label(t.target) }, color = Sub, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
    )
}

/** On the Summary: badges this workout earned. */
@Composable
fun BadgesEarned(w: Workout) {
    val state = rememberBadges() ?: return
    val new = remember(state, w.id) { Badges.earnedBy(w, state) }
    if (new.isEmpty()) return
    var open by remember { mutableStateOf<Badges.Badge?>(null) }
    Text("Badges earned", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    new.forEach { b ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).clickable { open = b }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BadgeIcon(b, true, 48.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(b.name, fontWeight = FontWeight.Bold, color = badgeColor(b))
                Text(b.requirement, color = Sub, fontSize = 13.sp)
            }
        }
    }
    open?.let { BadgeDialog(it, state) { open = null } }
}

// ---------------- Developer: making the badge pictures (temporary) ----------------

/**
 * Makes a badge's picture with OpenAI (transparent PNG). Level I from its prompt; higher levels from level I's picture
 * plus the prompt (an image edit), so the whole set shares one design. Null when it worked, else why not.
 */
suspend fun makeBadgePicture(b: Badges.Badge): String? = withContext(Dispatchers.IO) {
    val p = Store.profile.value
    if (p.apiKey.isBlank()) return@withContext "Add your OpenAI API key in Settings first."
    val first = Badges.pictureFile("${b.family.id}_1")
    if (b.tier > 0 && !first.exists()) return@withContext "Make ${b.family.name} I first – the other levels are based on it."
    runCatching {
        val model = p.imageModel.ifBlank { "gpt-image-1" }
        val conn: HttpURLConnection
        if (b.tier == 0) {
            val body = JSONObject().put("model", model).put("prompt", Badges.prompt(b))
                .put("size", "1024x1024").put("n", 1).put("background", "transparent").put("output_format", "png")
            if (p.imageQuality.isNotBlank()) body.put("quality", p.imageQuality)
            conn = (URL("https://api.openai.com/v1/images/generations").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 240_000; doOutput = true
                setRequestProperty("Authorization", "Bearer ${p.apiKey.trim()}"); setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        } else {
            // Level I's picture goes along as the reference.
            val boundary = "----badge" + System.currentTimeMillis()
            conn = (URL("https://api.openai.com/v1/images/edits").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 240_000; doOutput = true
                setRequestProperty("Authorization", "Bearer ${p.apiKey.trim()}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { os ->
                fun field(name: String, value: String) =
                    os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                field("model", model); field("prompt", Badges.prompt(b)); field("size", "1024x1024"); field("n", "1")
                field("background", "transparent"); field("output_format", "png")
                if (p.imageQuality.isNotBlank()) field("quality", p.imageQuality)
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"level1.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
                os.write(first.readBytes())
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) return@withContext "OpenAI said: " + (runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: "error $code")
        val item = JSONObject(text).getJSONArray("data").getJSONObject(0)
        val bytes = if (item.has("b64_json")) Base64.decode(item.getString("b64_json"), Base64.DEFAULT) else URL(item.getString("url")).openStream().use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext "Couldn't read the picture."
        withContext(Dispatchers.Main) { BadgePictures.save(b.id, bmp) }
        null
    }.getOrElse { "Couldn't reach OpenAI (${it.message})" }
}

/**
 * Developer (temporary): a picture for every badge. Level I of each set first; the higher levels are made from it
 * (same design, grander material). Each badge shows its previous picture (left) and the current one (right), with
 * Undo; Undo all (asked twice) takes every badge back to its emoji. Save as zip to send them in to be built in.
 */
@Composable
fun BadgeArtScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = BadgePictures.version
    var local by remember { mutableStateOf<String?>(null) }
    // Work runs in the background (keeps going if you leave); its progress shows here.
    val status = local ?: BadgeGen.message
    val busy = BadgeGen.current
    var picking by remember { mutableStateOf<Badges.Badge?>(null) }
    var confirmAll by remember { mutableIntStateOf(0) }
    // Level I was just remade: offer to redo the rest of its set to match.
    val redoSet = BadgeGen.askRedo?.let { id -> Badges.families.firstOrNull { it.id == id } }
    fun generate(b: Badges.Badge) { local = null; BadgeGen.start(context, listOf(b)) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val b = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }?.let { BadgePictures.save(b.id, it) }
        }.onFailure { local = "Couldn't use that picture: ${it.message}" }
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        local = runCatching {
            var n = 0
            context.contentResolver.openOutputStream(uri)!!.use { os ->
                java.util.zip.ZipOutputStream(os).use { z ->
                    // Shrunk to 384 px (badges never show bigger than 110 dp), as WebP: small enough to send.
                    Badges.pictureFile("x").parentFile?.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }?.forEach { file ->
                        val full = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@forEach
                        val small = if (full.width > 384) android.graphics.Bitmap.createScaledBitmap(full, 384, 384 * full.height / full.width, true) else full
                        z.putNextEntry(java.util.zip.ZipEntry("badges/${file.nameWithoutExtension}.webp"))
                        small.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 92, z)
                        z.closeEntry(); n++
                        if (small !== full) small.recycle(); full.recycle()
                    }
                }
            }
            "Saved $n badge pictures (small copies, ready to send)."
        }.getOrElse { "Couldn't save: ${it.message}" }
    }
    val missing = remember(v) { Badges.all.filter { !BadgePictures.hasPicture(it.id) } }

    redoSet?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { BadgeGen.askRedo = null },
            text = { Text("Redo the other ${f.name} levels so they match the new ${f.name} I?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                BadgeGen.askRedo = null
                BadgeGen.start(context, f.targets.indices.drop(1).map { Badges.Badge(f, it) })
            }) { Text("Redo them", color = Blue) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { BadgeGen.askRedo = null }) { Text("Keep them", color = Sub) } },
        )
    }
    if (confirmAll > 0) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmAll = 0 },
        text = { Text(if (confirmAll == 1) "Undo all? Every badge picture you've made goes, and all badges show their emoji again."
            else "Are you sure? This deletes all ${Badges.all.size - missing.size} badge pictures and their history. It can't be undone.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            if (confirmAll == 1) confirmAll = 2 else { confirmAll = 0; BadgeGen.stop(context); BadgePictures.undoAll(); local = "All badges are back to their emoji." }
        }) { Text(if (confirmAll == 1) "Yes, undo all" else "Delete them all", color = Red) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAll = 0 }) { Text("Cancel", color = Sub) } },
    )

    Column(Modifier.fillMaxSize()) {
        TopBar("Badge pictures")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Make level I of a set first; the higher levels are made from it (same design, grander material). " +
                    "${Badges.all.size - missing.size} of ${Badges.all.size} done.", color = Sub, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GreyButton(if (BadgeGen.running) "Stop making" else "Generate all ${missing.size} missing", Modifier.weight(1f)) {
                        if (BadgeGen.running) { BadgeGen.stop(context); return@GreyButton }
                        // Each set's level I first, then the rest of that set from it.
                        local = null
                        BadgeGen.start(context, missing.sortedWith(compareBy({ Badges.families.indexOf(it.family) }, { it.tier })))
                    }
                    GreyButton("Save as zip", Modifier.weight(1f)) { zip.launch("loadout-badges-small.zip") }
                }
                GreyButton("Undo all", Modifier.fillMaxWidth().padding(top = 8.dp), Red) { confirmAll = 1 }
                status?.let { Text(it, color = Blue, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            items(Badges.all.size) { i ->
                val b = Badges.all[i]
                var text by remember(b.id) { mutableStateOf(Badges.prompt(b)) }
                val firstMissing = b.tier > 0 && !BadgePictures.hasPicture("${b.family.id}_1")
                val prev = remember(b.id, v) { BadgePictures.previous(b.id) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.name, fontWeight = FontWeight.Bold)
                    // Before (left) and now (right).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgeIcon(b, true, 64.dp, picture = prev, usePicture = true)
                            Text("Before", color = Sub, fontSize = 11.sp)
                        }
                        Text("→", color = Sub, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.runtime.key(v) { BadgeIcon(b, true, 64.dp) }
                            Text("Now", color = Sub, fontSize = 11.sp)
                        }
                        Text("${b.id}.png" + if (BadgePictures.hasPicture(b.id)) " ✓" else "", color = Sub, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 14.dp).weight(1f))
                    }
                    androidx.compose.material3.OutlinedTextField(text, { text = it; Badges.setPrompt(b, it) }, Modifier.fillMaxWidth().typing(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White), minLines = 3, maxLines = 8)
                    if (firstMissing) Text("Make ${b.family.name} I first – this level is based on it.", color = Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GreyButton("Copy", Modifier.weight(1f)) {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Badge prompt", text)); local = "Copied the ${b.name} prompt."
                        }
                        GreyButton(if (busy == b.id) "Making…" else "Generate", Modifier.weight(1.3f), if (firstMissing) Sub else Color.White) {
                            if (firstMissing) local = "Make ${b.family.name} I first." else generate(b)
                        }
                        GreyButton("Pick", Modifier.weight(1f)) {
                            picking = b; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        GreyButton("Undo", Modifier.weight(1f), if (BadgePictures.hasPicture(b.id)) Color.White else Sub) {
                            if (BadgePictures.hasPicture(b.id)) BadgePictures.undo(b.id)
                        }
                    }
                }
            }
        }
    }
}
