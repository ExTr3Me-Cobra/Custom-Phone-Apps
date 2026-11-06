package com.ironlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where the text boxes on screen are, so a tap anywhere else can put the keyboard away
 * (see Root in App.kt). Add [typing] to every text box.
 */
object TextBoxes {
    val rects = HashMap<Any, androidx.compose.ui.geometry.Rect>()
    fun contains(p: androidx.compose.ui.geometry.Offset) = rects.values.any { it.contains(p) }
}

fun Modifier.typing(): Modifier = composed {
    val id = remember { Any() }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { TextBoxes.rects.remove(id) } }
    onGloballyPositioned { TextBoxes.rects[id] = it.boundsInRoot() }
}

/**
 * A small number box for set tables. Keeps what you type while you type it, and reports the
 * parsed value (null when empty). [placeholder] shows in grey when empty.
 */
@Composable
fun NumField(
    initial: String,
    placeholder: String,
    key: Any,
    modifier: Modifier,
    keyboard: KeyboardType = KeyboardType.Decimal,
    background: Color = Color.Transparent,
    outlined: Boolean = false,
    /** A duration: type digits only and the colons appear as you go (130 → 1:30, 12345 → 1:23:45). */
    time: Boolean = false,
    /** Tapping in empties the box so you can type straight away; leave it empty and the old number comes back. */
    clearOnFocus: Boolean = false,
    /** A stopwatch running for this box: shown in place of the number, in the accent colour, counting up. */
    running: String? = null,
    onChange: (String) -> Unit,
) {
    var text by remember(key) { mutableStateOf(if (time) initial.filter { it.isDigit() }.trimStart('0') else initial) }
    var before by remember(key) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(if (outlined) 10.dp else 6.dp)
    Box(
        modifier.typing().clip(shape).background(background)
            .then(if (outlined) Modifier.border(1.dp, Color(0xFF3A3A3C), shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = if (outlined) 12.dp else 4.dp),
        contentAlignment = if (outlined) Alignment.Center else Alignment.CenterStart,
    ) {
        val align = if (outlined) TextAlign.Center else TextAlign.Start
        if (running != null) {
            Text(running, color = Blue, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = align, modifier = if (outlined) Modifier.fillMaxWidth() else Modifier)
            return@Box
        }
        if (text.isEmpty()) Text(placeholder, color = Sub, fontSize = 17.sp, textAlign = align, modifier = if (outlined) Modifier.fillMaxWidth() else Modifier)
        BasicTextField(
            value = text,
            onValueChange = { t ->
                if (time) {
                    val digits = t.filter { it.isDigit() }.trimStart('0').take(6)
                    text = digits
                    onChange(timeText(digits))
                } else {
                    val clean = t.filter { it.isDigit() || it == '.' || it == ':' }.take(8)
                    text = clean
                    onChange(clean)
                }
            },
            visualTransformation = if (time) TimeTransformation else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = align),
            cursorBrush = SolidColor(Blue),
            keyboardOptions = KeyboardOptions(keyboardType = if (time) KeyboardType.Number else keyboard),
            modifier = (if (outlined) Modifier.fillMaxWidth() else Modifier).then(
                if (!clearOnFocus) Modifier else Modifier.onFocusChanged { f ->
                    if (f.isFocused && before == null) { before = text; text = "" }
                    else if (!f.isFocused && before != null) {
                        // Nothing typed: put the old number back (the set never lost it).
                        if (text.isEmpty()) text = before!!
                        before = null
                    }
                }
            ),
        )
    }
}

/** Digits typed into a time box as m:ss or h:mm:ss ("" stays empty). */
fun timeText(digits: String): String {
    if (digits.isEmpty()) return ""
    val d = digits.padStart(3, '0')
    return if (d.length <= 4) d.dropLast(2) + ":" + d.takeLast(2)
    else d.dropLast(4) + ":" + d.takeLast(4).take(2) + ":" + d.takeLast(2)
}

/** Shows a time box's digits with the colons in; the cursor stays at the end, where digits are typed and deleted. */
private object TimeTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val shown = timeText(text.text)
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(shown),
            object : androidx.compose.ui.text.input.OffsetMapping {
                override fun originalToTransformed(offset: Int) = shown.length
                override fun transformedToOriginal(offset: Int) = text.length
            },
        )
    }
}
