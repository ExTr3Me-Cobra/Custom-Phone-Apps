package com.mammyframe.app

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

val Script = FontFamily(Font(R.font.great_vibes))
val Rose = Color(0xFFB4506F)
private val Gold = Color(0xFFC9973B)
private val Ink = Color(0xFF4A3A3F)

enum class HomeDialog { NONE, SCHEDULE, TRANSITIONS }

private enum class HomeLayout { WIDE, NARROW, SIDE_BY_SIDE }

@Composable
fun HomeScreen(
    settings: Settings,
    photoCount: Int?,
    dialog: HomeDialog,
    onDialog: (HomeDialog) -> Unit,
    onChooseFolder: () -> Unit,
    onPlay: () -> Unit,
    onChange: (Settings) -> Unit,
    resumeTick: Int,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF9F2), Color(0xFFFBEDE8), Color(0xFFF3DDE2))))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val w = maxWidth
            val h = maxHeight
            // Pick a layout from the real screen size: tablets, phones held upright, and phones on their side.
            val layout = when {
                h < 520.dp && w > h -> HomeLayout.SIDE_BY_SIDE
                w >= 760.dp -> HomeLayout.WIDE
                else -> HomeLayout.NARROW
            }
            val context = LocalContext.current
            val folderText = when {
                settings.folderUri == null -> "Tap to choose"
                photoCount == null -> settings.folderName ?: "Chosen"
                else -> "${settings.folderName ?: "Folder"}\n$photoCount photo${if (photoCount == 1) "" else "s"}"
            }
            val scheduleText =
                if (settings.scheduleOn) "${timeText(settings.startMinute)} to\n${timeText(settings.endMinute)}"
                else "Off"
            val transitionText = "${settings.transition.label}\n${secondsText(settings.photoSeconds)} each"

            val logoWidth = if (layout == HomeLayout.SIDE_BY_SIDE) w / 2 - 32.dp else w - 48.dp
            val titleSize = (logoWidth.value / 4.6f).coerceIn(40f, if (layout == HomeLayout.SIDE_BY_SIDE) 84f else 120f)
                .coerceAtMost(if (layout == HomeLayout.SIDE_BY_SIDE) h.value * 0.26f else h.value * 0.16f).sp
            val playSize = (if (layout == HomeLayout.SIDE_BY_SIDE) h * 0.34f else minOf(w, h) * 0.28f)
                .coerceIn(84.dp, 150.dp)
            val narrowCardWidth = minOf(320.dp, w - 48.dp)

            val logo: @Composable () -> Unit = {
                Logo(titleSize, minOf(320.dp, logoWidth * 0.8f)) {
                    if (!settings.developer) {
                        onChange(settings.copy(developer = true))
                        android.widget.Toast.makeText(context, "Developer settings on", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val cardsColumn: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { cardWidth ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(cardWidth)) {
                    SettingCard("Photos folder", folderText, Modifier.fillMaxWidth(), onChooseFolder)
                    if (settings.developer) {
                        SettingCard("Schedule", scheduleText, Modifier.fillMaxWidth()) { onDialog(HomeDialog.SCHEDULE) }
                        SettingCard("Transitions", transitionText, Modifier.fillMaxWidth()) { onDialog(HomeDialog.TRANSITIONS) }
                    }
                }
            }
            val devOff: @Composable () -> Unit = {
                if (settings.developer) {
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = { onChange(settings.copy(developer = false)) }) {
                        Text("Turn off developer settings", fontSize = 16.sp)
                    }
                }
            }

            if (layout == HomeLayout.SIDE_BY_SIDE) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        logo()
                        Spacer(Modifier.height(6.dp))
                        PlayButton(playSize, onPlay)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        cardsColumn(minOf(320.dp, w / 2 - 32.dp))
                        devOff()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = h).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    logo()
                    Spacer(Modifier.height(12.dp))
                    PlayButton(playSize, onPlay)
                    Spacer(Modifier.height(if (h < 700.dp) 18.dp else 28.dp))
                    if (layout == HomeLayout.WIDE && settings.developer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            SettingCard("Photos folder", folderText, Modifier.width(220.dp), onChooseFolder)
                            SettingCard("Schedule", scheduleText, Modifier.width(220.dp)) { onDialog(HomeDialog.SCHEDULE) }
                            SettingCard("Transitions", transitionText, Modifier.width(220.dp)) { onDialog(HomeDialog.TRANSITIONS) }
                        }
                    } else {
                        cardsColumn(if (layout == HomeLayout.WIDE) 260.dp else narrowCardWidth)
                    }
                    devOff()
                }
            }
        }
    }

    when (dialog) {
        HomeDialog.SCHEDULE -> ScheduleDialog(settings, onChange, resumeTick) { onDialog(HomeDialog.NONE) }
        HomeDialog.TRANSITIONS -> TransitionsDialog(settings, onChange) { onDialog(HomeDialog.NONE) }
        HomeDialog.NONE -> {}
    }
}

@Composable
private fun Logo(size: androidx.compose.ui.unit.TextUnit, flourishWidth: androidx.compose.ui.unit.Dp, onSecretTaps: () -> Unit) {
    // Shrinks a little more if the name still doesn't fit on one line (e.g. large system text).
    var shrink by remember(size) { mutableStateOf(1f) }
    // Ten quick taps on the name (each within 0.7 s of the last) open the developer settings.
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) {
            val now = android.os.SystemClock.elapsedRealtime()
            taps = if (now - lastTap < 700) taps + 1 else 1
            lastTap = now
            if (taps >= 10) { taps = 0; onSecretTaps() }
        }
    ) {
        Text(
            "Mammy Frame",
            style = TextStyle(
                fontFamily = Script,
                fontSize = size * shrink,
                brush = Brush.linearGradient(listOf(Color(0xFF9E3D5C), Rose, Gold, Color(0xFFA8455F))),
                shadow = Shadow(Color(0x33803040), Offset(2f, 4f), 10f),
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow && shrink > 0.4f) shrink *= 0.9f },
        )
        Flourish(Modifier.width(flourishWidth).height(28.dp))
    }
}

/** A thin gold line with a little heart in the middle, under the name. */
@Composable
private fun Flourish(modifier: Modifier) {
    Canvas(modifier) {
        val cy = size.height / 2
        val cx = size.width / 2
        val gap = size.height * 0.9f
        val line = Brush.horizontalGradient(listOf(Color.Transparent, Gold, Gold, Color.Transparent))
        drawLine(line, Offset(0f, cy), Offset(cx - gap, cy), 2.5f)
        drawLine(line, Offset(cx + gap, cy), Offset(size.width, cy), 2.5f)
        val r = size.height * 0.28f
        val heart = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, cy + r * 1.3f)
            cubicTo(cx - r * 2.2f, cy - r * 0.2f, cx - r * 1.1f, cy - r * 1.9f, cx, cy - r * 0.6f)
            cubicTo(cx + r * 1.1f, cy - r * 1.9f, cx + r * 2.2f, cy - r * 0.2f, cx, cy + r * 1.3f)
            close()
        }
        drawPath(heart, Rose)
    }
}

@Composable
private fun PlayButton(size: androidx.compose.ui.unit.Dp, onPlay: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .shadow(size * 0.09f, CircleShape)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFCB6A88), Color(0xFFA13F5F))))
                .clickable(onClick = onPlay)
                .padding(start = size * 0.31f, end = size * 0.23f, top = size * 0.27f, bottom = size * 0.27f)
        ) {
            GlyphIcon(Glyph.PLAY, Color.White, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text("Play", fontSize = if (size < 120.dp) 20.sp else 26.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 120.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.82f),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontFamily = Script, fontSize = 34.sp, color = Rose)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 17.sp, color = Ink, textAlign = TextAlign.Center, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun timeText(minute: Int): String {
    val c = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(LocalContext.current).format(c.time)
}

private fun secondsText(s: Int) = when {
    s < 60 -> "$s seconds"
    s % 60 == 0 -> "${s / 60} minute${if (s == 60) "" else "s"}"
    else -> "${s / 60} min ${s % 60} sec"
}

@Composable
private fun ScheduleDialog(s: Settings, onChange: (Settings) -> Unit, resumeTick: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    fun pick(minute: Int, set: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> set(h * 60 + m) }, minute / 60, minute % 60,
            DateFormat.is24HourFormat(context)).show()
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done", fontSize = 18.sp) } },
        title = { Text("Schedule", fontFamily = Script, fontSize = 44.sp, color = Rose) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use the schedule", fontSize = 19.sp, modifier = Modifier.weight(1f))
                    Switch(s.scheduleOn, { onChange(s.copy(scheduleOn = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start the slideshow at", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { pick(s.startMinute) { onChange(s.copy(startMinute = it, scheduleOn = true)) } }) {
                        Text(timeText(s.startMinute), fontSize = 17.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Black screen from", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { pick(s.endMinute) { onChange(s.copy(endMinute = it, scheduleOn = true)) } }) {
                        Text(timeText(s.endMinute), fontSize = 17.sp)
                    }
                }
                if (s.startMinute == s.endMinute) {
                    Text("The start and end times are the same, so the schedule won't do anything.",
                        color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
                Text(
                    "At the start time the slideshow begins by itself. At the end time the screen goes black " +
                        "until the next start time. Tapping the black screen brings back this menu; if nobody " +
                        "starts a slideshow, it goes black again after a minute.",
                    fontSize = 14.sp, color = Ink.copy(alpha = 0.75f)
                )

                // Re-checked every time you come back from the phone's settings.
                val canOpen = remember(resumeTick) { Schedule.canOpenByItself(context) }
                val onTime = remember(resumeTick) { Schedule.canWakeOnTime(context) }
                if (!canOpen) {
                    PermissionRow(
                        "So the frame can switch on by itself even if the app was closed, allow " +
                            "\"Display over other apps\" for Mammy Frame."
                    ) {
                        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    }
                }
                if (!onTime) {
                    PermissionRow("So it switches right on time, allow \"Alarms & reminders\" for Mammy Frame.") {
                        context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }
    )
}

@Composable
private fun PermissionRow(text: String, onAllow: () -> Unit) {
    Surface(color = Color(0xFFFFF1E0), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAllow) { Text("Allow", fontSize = 16.sp) }
        }
    }
}

private val photoTimes = listOf(5, 8, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600)

@Composable
private fun TransitionsDialog(s: Settings, onChange: (Settings) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done", fontSize = 18.sp) } },
        title = { Text("Transitions", fontFamily = Script, fontSize = 44.sp, color = Rose) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Style", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Transition.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.transition == t, onClick = { onChange(s.copy(transition = t)) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.transition == t, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(t.label, fontSize = 17.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Transition length: ${"%.1f".format(s.transitionMs / 1000f)} seconds",
                    fontSize = 17.sp, fontWeight = FontWeight.Medium)
                var len by remember(s.transitionMs) { mutableStateOf(s.transitionMs.toFloat()) }
                Slider(
                    value = len,
                    onValueChange = { len = it },
                    onValueChangeFinished = { onChange(s.copy(transitionMs = ((len / 100f).roundToInt() * 100))) },
                    valueRange = 300f..4000f,
                    enabled = s.transition != Transition.NONE,
                )

                Spacer(Modifier.height(8.dp))
                Text("Each photo shows for: ${secondsText(s.photoSeconds)}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                val startIdx = photoTimes.indexOfFirst { it >= s.photoSeconds }.let { if (it < 0) photoTimes.lastIndex else it }
                var idx by remember(s.photoSeconds) { mutableStateOf(startIdx.toFloat()) }
                Slider(
                    value = idx,
                    onValueChange = { idx = it; onChange(s.copy(photoSeconds = photoTimes[it.roundToInt()])) },
                    valueRange = 0f..photoTimes.lastIndex.toFloat(),
                    steps = photoTimes.size - 2,
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gentle zoom", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text("Each photo drifts in very slowly while it shows.", fontSize = 14.sp, color = Ink.copy(alpha = 0.7f))
                    }
                    Switch(s.gentleZoom, { onChange(s.copy(gentleZoom = it)) })
                }
            }
        }
    )
}
