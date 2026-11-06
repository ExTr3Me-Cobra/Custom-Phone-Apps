package com.ironlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Developer: edit any text in the app right where it's shown. Changes show straight away (on the screen where you made them, wherever that exact text
 * appears) and can be exported as a list of "was → now" to build into the app.
 */
/** The screen a text is on, so developer edits made on one screen don't change the same words on another. */
val LocalTextScope = androidx.compose.runtime.staticCompositionLocalOf { "" }

object AppText {
    private const val FILE = "app_text.json"
    /** Original text → your version. */
    val edits = mutableStateMapOf<String, String>()
    /** Original text → your size, position and width changes. */
    val styles = mutableStateMapOf<String, Look>()
    /** The setting: tap any text to edit it. */
    var on by mutableStateOf(false)
        private set
    /** Paused from the floating pill, so you can tap around normally. */
    var paused by mutableStateOf(false)
    val active get() = on && !paused
    /** The text being edited in the editor panel (its key: screen + original), or null; and its original words. */
    var editing by mutableStateOf<String?>(null)
    var editingText by mutableStateOf("")
    private const val SEP = "\u0001"
    /** Edits are kept per screen: "screen<SEP>text". Older edits without a screen still apply everywhere. */
    fun key(scope: String, text: String) = if (scope.isEmpty()) text else scope + SEP + text
    fun words(key: String, text: String) = edits[key] ?: edits[text] ?: text
    private fun lookOf(key: String, text: String) = styles[key] ?: styles[text]
    private var loaded = false

    /** Size in sp (null = as designed), moved by dx/dy dp, box width in dp (null = as designed). */
    data class Look(val size: Float? = null, val dx: Float = 0f, val dy: Float = 0f, val width: Float? = null) {
        val isDefault get() = size == null && dx == 0f && dy == 0f && width == null
    }

    /** How many texts you've changed (words, look or both). */
    val count get() = (edits.keys + styles.keys).size

    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(Store.readFile(FILE) ?: "{}")
            on = o.optBoolean("on")
            o.optJSONArray("edits")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                    val k = j.optString("screen", "").let { sc -> key(sc, j.getString("was")) }
                    j.optString("now", "").takeIf { j.has("now") }?.let { edits[k] = it }
                    val look = Look(if (j.has("size")) j.getDouble("size").toFloat() else null, j.optDouble("dx", 0.0).toFloat(),
                        j.optDouble("dy", 0.0).toFloat(), if (j.has("width")) j.getDouble("width").toFloat() else null)
                    if (!look.isDefault) styles[k] = look
                }
            }
        }
    }

    private fun save() = Store.writeFile(FILE, JSONObject().put("on", on).put("edits", export()).toString())

    fun turn(v: Boolean) { on = v; paused = false; editing = null; save() }

    fun set(key: String, original: String, now: String) {
        if (now == original) edits.remove(key) else edits[key] = now
        save()
    }

    fun setLook(key: String, look: Look) {
        if (look.isDefault) styles.remove(key) else styles[key] = look
        save()
    }

    /** Back to as designed here; also drops an older all-screens edit of the same words, if there is one. */
    fun reset(key: String, original: String) { edits.remove(key); styles.remove(key); edits.remove(original); styles.remove(original); save() }

    fun clear() { edits.clear(); styles.clear(); save() }

    /** Every change: what it said, what you want it to say, and any size / move / width change. */
    fun export(): JSONArray = JSONArray((edits.keys + styles.keys).sorted().map { k ->
        JSONObject().apply { if (SEP in k) { put("screen", k.substringBefore(SEP)); put("was", k.substringAfter(SEP)) } else put("was", k) }.apply {
            edits[k]?.let { put("now", it) }
            styles[k]?.let { l -> l.size?.let { put("size", it.toDouble()) }; if (l.dx != 0f) put("dx", l.dx.toDouble()); if (l.dy != 0f) put("dy", l.dy.toDouble()); l.width?.let { put("width", it.toDouble()) } }
        }
    })

    /** Your look changes applied to a text's modifier: moved, and a fixed box width. */
    fun Modifier.look(key: String, original: String): Modifier {
        val l = lookOf(key, original) ?: return this
        return this.offset(l.dx.dp, l.dy.dp).then(if (l.width != null) Modifier.width(l.width.dp) else Modifier)
    }
    fun size(key: String, original: String, designed: TextUnit): TextUnit = lookOf(key, original)?.size?.sp ?: designed

    /** While editing: an outline and a tap that opens the editor (the innermost text wins over a button around it). */
    fun Modifier.editable(key: String, original: String): Modifier {
        if (!active) return this
        val changed = key in edits || key in styles || original in edits || original in styles
        val tint = if (editing == key) Color(0xFFFFCC00) else if (changed) Green else Blue
        return this.border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).background(tint.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .clickable { editing = key; editingText = original }
    }
}

/** Text shown everywhere in the app: your edited version if you changed it, and editable in place while editing is on. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val k = AppText.key(LocalTextScope.current, text)
    val shown = AppText.words(k, text)
    with(AppText) {
    androidx.compose.material3.Text(text = shown, modifier = modifier.look(k, text).editable(k, text), color = color, fontSize = size(k, text, fontSize), fontStyle = fontStyle, fontWeight = fontWeight,
            fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout ?: {}, style = style)
    }
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val key = AppText.key(LocalTextScope.current, text.text)
    val edited = AppText.words(key, text.text).takeIf { it != text.text }
    with(AppText) {
    val m = modifier.look(key, text.text).editable(key, text.text)
    val fs = size(key, text.text, fontSize)
    // Your version is plain text (the original's coloured parts, like stars, aren't kept).
    if (edited != null) androidx.compose.material3.Text(text = edited, modifier = m, color = color, fontSize = fs, fontStyle = fontStyle, fontWeight = fontWeight,
            fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)
    else androidx.compose.material3.Text(text = text, modifier = m, color = color, fontSize = fs, fontStyle = fontStyle, fontWeight = fontWeight,
            fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
            overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style)
    }
}

/** While text editing is on: a small pill to pause it (so taps work normally), and the editor for the text you tapped. */
@Composable
fun AppTextPill() {
    if (!AppText.on) return
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 76.dp, start = 12.dp), contentAlignment = Alignment.BottomStart) {
        val pill = Modifier.clip(RoundedCornerShape(16.dp)).background(if (AppText.paused) Card2 else Blue.copy(alpha = 0.92f))
            .clickable { AppText.paused = !AppText.paused; AppText.editing = null }.padding(horizontal = 12.dp, vertical = 6.dp)
        if (AppText.paused) androidx.compose.material3.Text("⏸ Text editing paused · tap to edit", color = Color.White, fontSize = 11.sp,
            fontWeight = FontWeight.Medium, modifier = pill)
        else OutlinedLabel("✏️ Tap any text (${AppText.count} changed) · tap here to pause", 11.sp, pill, FontWeight.Medium, editable = false, bg = Blue)
    }
    AppText.editing?.takeIf { AppText.active }?.let { TextEditorPanel(it, AppText.editingText) }
}

/** The editor: the words, font size, move and box width, all live; Reset puts this text back as designed. */
@Composable
private fun TextEditorPanel(key: String, original: String) {
    var words by remember(key) { mutableStateOf(AppText.words(key, original)) }
    val look = AppText.styles[key] ?: AppText.styles[original] ?: AppText.Look()
    fun setLook(l: AppText.Look) = AppText.setLook(key, l)
    @Composable fun Btn(label: String, onClick: () -> Unit) = androidx.compose.material3.Text(label, color = Color.White, fontSize = 15.sp,
        textAlign = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Card2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
    @Composable fun Label(t: String) = androidx.compose.material3.Text(t, color = Sub, fontSize = 13.sp, modifier = Modifier.width(64.dp))
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter, onDismissRequest = { AppText.editing = null },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color(0xFF1C1C1E)).border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text("Edit text", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Btn("Reset") { AppText.reset(key, original); words = original }
                Spacer(Modifier.width(8.dp))
                Btn("Done") { AppText.editing = null }
            }
            BasicTextField(words, { words = it; AppText.set(key, original, it) },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(10.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Blue))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Size")
                Btn("−") { setLook(look.copy(size = ((look.size ?: 14f) - 1f).coerceAtLeast(6f))) }
                androidx.compose.material3.Text(look.size?.let { "${it.toInt()} sp" } ?: "as designed", color = Color.White, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
                Btn("+") { setLook(look.copy(size = ((look.size ?: 14f) + 1f).coerceAtMost(60f))) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Move")
                Btn("←") { setLook(look.copy(dx = look.dx - 2f)) }
                Btn("↑") { setLook(look.copy(dy = look.dy - 2f)) }
                Btn("↓") { setLook(look.copy(dy = look.dy + 2f)) }
                Btn("→") { setLook(look.copy(dx = look.dx + 2f)) }
                androidx.compose.material3.Text("${look.dx.toInt()}, ${look.dy.toInt()}", color = Sub, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Width")
                Btn("−") { setLook(look.copy(width = ((look.width ?: 200f) - 10f).coerceAtLeast(20f))) }
                androidx.compose.material3.Text(look.width?.let { "${it.toInt()} dp" } ?: "as designed", color = Color.White, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
                Btn("+") { setLook(look.copy(width = ((look.width ?: 200f) + 10f).coerceAtMost(600f))) }
                if (look.width != null) Btn("Auto") { setLook(look.copy(width = null)) }
            }
            androidx.compose.material3.Text("Changes show straight away, on this screen only.", color = Sub, fontSize = 11.sp)
        }
    }
}
