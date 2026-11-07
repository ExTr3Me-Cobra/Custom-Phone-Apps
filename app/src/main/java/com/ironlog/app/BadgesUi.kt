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

/** Ring colours by tier: bronze, silver, gold, platinum, diamond, champion, legend, then white gold. */
private val TIER_COLORS = listOf(0xFFE0803F, 0xFFB6C2D1, 0xFFF7C743, 0xFF52D8D0, 0xFF529DFF, 0xFFFF6FB5, 0xFFA64DFF, 0xFFFFE9A8).map { Color(it) }
fun badgeColor(tier: Int) = TIER_COLORS[tier.coerceIn(0, TIER_COLORS.lastIndex)]

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

    /** Saves a picture for a family (scaled to 512 px). */
    fun save(id: String, bmp: Bitmap) {
        val s = 512f / maxOf(bmp.width, bmp.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val f = Badges.pictureFile(id).apply { parentFile?.mkdirs() }
        f.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        version++
    }
}

/** A badge: its family's picture (or emoji until there is one) in a ring of its tier's colour, numeral underneath. */
@Composable
fun BadgeIcon(b: Badges.Badge, earned: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val v = BadgePictures.version
    val img = remember(b.id, v) { BadgePictures.get(c, b) }
    val ring = badgeColor(b.tier)
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Card2)
                .border((size.value / 16).dp, if (earned) ring else Color(0xFF48484A), CircleShape)
                .alpha(if (earned) 1f else 0.45f),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) Image(img, b.name, Modifier.size(size * 0.82f), colorFilter = if (earned) null else grey)
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

/** All badges, by category: earned in colour, the rest greyed out. Tap one for its requirement and progress. */
@Composable
fun BadgesScreen() {
    val state = rememberBadges()
    var open by remember { mutableStateOf<Badges.Badge?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Badges")
        if (state == null) { Text("Counting…", color = Sub, modifier = Modifier.padding(16.dp)); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${state.earned.size} of ${Badges.all.size} earned", color = Sub, modifier = Modifier.padding(horizontal = 4.dp))
            }
            for ((cat, fams) in Badges.families.groupBy { it.category }) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(cat, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                }
                for (f in fams) for (i in f.targets.indices) {
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
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (earnedAt != null) badgeColor(b.tier) else Blue))
                }
                Text("${Badges.fmt(minOf(value, b.target).let { if (earnedAt != null) b.target else it })} / ${Badges.fmt(b.target)} ${b.family.unit}",
                    fontWeight = FontWeight.Medium)
                Text(if (earnedAt != null) "Earned ${fmt.format(java.util.Date(earnedAt))}" else "Not earned yet",
                    color = if (earnedAt != null) badgeColor(b.tier) else Sub)
                if (b.family.targets.size > 1) {
                    Text("All levels", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    b.family.targets.indices.forEach { i ->
                        val t = Badges.Badge(b.family, i)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.id in state.earned) "✓" else "·", color = if (t.id in state.earned) badgeColor(i) else Sub,
                                fontWeight = FontWeight.Bold, modifier = Modifier.size(width = 22.dp, height = 20.dp))
                            Text(t.name, Modifier.weight(1f), fontSize = 14.sp)
                            Text("${Badges.fmt(t.target)}", color = Sub, fontSize = 14.sp)
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
                Text(b.name, fontWeight = FontWeight.Bold, color = badgeColor(b.tier))
                Text(b.requirement, color = Sub, fontSize = 13.sp)
            }
        }
    }
    open?.let { BadgeDialog(it, state) { open = null } }
}

// ---------------- Developer: making the badge pictures (temporary) ----------------

/** Makes a family's picture with OpenAI (transparent PNG). Null when it worked, else why not. */
suspend fun makeBadgePicture(b: Badges.Badge): String? = withContext(Dispatchers.IO) {
    val p = Store.profile.value
    if (p.apiKey.isBlank()) return@withContext "Add your OpenAI API key in Settings first."
    runCatching {
        val body = JSONObject().put("model", p.imageModel.ifBlank { "gpt-image-1" }).put("prompt", Badges.prompt(b))
            .put("size", "1024x1024").put("n", 1).put("background", "transparent").put("output_format", "png")
        if (p.imageQuality.isNotBlank()) body.put("quality", p.imageQuality)
        val conn = (URL("https://api.openai.com/v1/images/generations").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 30_000; readTimeout = 240_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${p.apiKey.trim()}"); setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
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
 * Developer (temporary): a picture for every badge, each level grander than the one before. Each has its AI prompt
 * (editable, with Copy), Generate (your OpenAI key, transparent PNG) and Pick (a picture you made elsewhere).
 * Save as zip to send them in to be built in.
 */
@Composable
fun BadgeArtScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = BadgePictures.version
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Badges.Badge?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val b = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }?.let { BadgePictures.save(b.id, it) }
        }.onFailure { status = "Couldn't use that picture: ${it.message}" }
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = runCatching {
            var n = 0
            context.contentResolver.openOutputStream(uri)!!.use { os ->
                java.util.zip.ZipOutputStream(os).use { z ->
                    Badges.pictureFile("x").parentFile?.listFiles { f -> f.name.endsWith(".png") }?.forEach { file ->
                        z.putNextEntry(java.util.zip.ZipEntry("badges/${file.name}")); file.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
                    }
                }
            }
            "Saved $n badge pictures."
        }.getOrElse { "Couldn't save: ${it.message}" }
    }
    val missing = remember(v) { Badges.all.filter { !Badges.pictureFile(it.id).exists() } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Badge pictures")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("A picture for each of the ${Badges.all.size} badges, every level grander than the last (the app adds the numeral). " +
                    "Generate uses your OpenAI key (transparent PNG), or copy a prompt, make it anywhere, and Pick it. " +
                    "${Badges.all.size - missing.size} of ${Badges.all.size} done.", color = Sub, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GreyButton(if (busy != null) "Making…" else "Generate all ${missing.size} missing", Modifier.weight(1f)) {
                        if (busy != null) return@GreyButton
                        scope.launch {
                            var made = 0
                            for (b in missing) {
                                busy = b.id; status = "Making ${b.name}… ($made done)"
                                val err = makeBadgePicture(b)
                                if (err != null) { status = "${b.name}: $err"; if (err.contains("API key")) break } else made++
                            }
                            busy = null; status = "Made $made pictures."
                        }
                    }
                    GreyButton("Save as zip", Modifier.weight(1f)) { zip.launch("loadout-badges.zip") }
                }
                status?.let { Text(it, color = Blue, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            items(Badges.all.size) { i ->
                val b = Badges.all[i]
                var text by remember(b.id) { mutableStateOf(Badges.prompt(b)) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.runtime.key(v) { BadgeIcon(b, true, 72.dp) }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(b.name, fontWeight = FontWeight.Bold)
                            Text("${b.id}.png" + if (Badges.pictureFile(b.id).exists()) " ✓" else "", color = Sub, fontSize = 12.sp)
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(text, { text = it; Badges.setPrompt(b, it) }, Modifier.fillMaxWidth().typing(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White), minLines = 3, maxLines = 8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GreyButton("Copy", Modifier.weight(1f)) {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Badge prompt", text)); status = "Copied the ${b.name} prompt."
                        }
                        GreyButton(if (busy == b.id) "Making…" else "Generate", Modifier.weight(1f)) {
                            if (busy != null) return@GreyButton
                            busy = b.id
                            scope.launch { val err = makeBadgePicture(b); busy = null; status = err?.let { "${b.name}: $it" } ?: "Made ${b.name}." }
                        }
                        GreyButton("Pick", Modifier.weight(1f)) {
                            picking = b; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                }
            }
        }
    }
}
