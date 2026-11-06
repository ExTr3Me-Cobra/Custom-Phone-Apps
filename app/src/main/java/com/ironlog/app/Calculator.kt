package com.ironlog.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** The calculator button's picture: a small calculator, in white like the reference-weights button next to it. */
@Composable
fun CalculatorIcon(modifier: Modifier = Modifier) = Box(modifier) {
    Canvas(Modifier.size(width = 22.dp, height = 26.dp)) {
        val s = 2.dp.toPx()
        drawRoundRect(Color.White, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), CornerRadius(4.dp.toPx()), style = Stroke(s))
        val pad = 4.5f.dp.toPx()
        drawRoundRect(Blue, Offset(pad, pad), Size(size.width - 2 * pad, 5.dp.toPx()), CornerRadius(1.dp.toPx()))
        val top = pad + 8.dp.toPx()
        val gx = (size.width - 2 * pad) / 2; val gy = (size.height - top - pad) / 2
        for (r in 0..2) for (c in 0..2) drawCircle(Color.White, 1.4f.dp.toPx(), Offset(pad + c * gx, top + r * gy))
    }
}

/**
 * A plain calculator for the gym (plate maths, percentages), laid out like the phone's own: C ⌫ % ÷ / 7 8 9 × /
 * 4 5 6 − / 1 2 3 + / ( ) 0 . =, with the answer shown live in grey as you type. Accent colours follow Settings.
 */
@Composable
fun Calculator(onClose: () -> Unit) {
    var expr by remember { mutableStateOf("") }
    // After "=", a digit starts a new sum; an operator carries on from the answer.
    var answered by remember { mutableStateOf(false) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xFF111113))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}.navigationBarsPadding().padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Calculator", color = Sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = Sub, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
                }
                val live = Calc.eval(expr)
                Text(Calc.pretty(expr).ifEmpty { "0" }, fontSize = if (expr.length > 14) 30.sp else 42.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), lineHeight = 46.sp)
                Text(if (live != null && Calc.hasOp(expr)) Calc.format(live) else "", color = Sub, fontSize = 26.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), maxLines = 1)
                fun press(k: String) {
                    if (k == "=") { live?.let { expr = Calc.format(it).replace(",", ""); answered = true }; return }
                    if (answered && (k[0].isDigit() || k == ".")) expr = ""
                    answered = false
                    expr = Calc.press(expr, k)
                }
                val rows = listOf(
                    listOf("C", "⌫", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"),
                    listOf("1", "2", "3", "+"), listOf("( )", "0", ".", "="),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { k ->
                                val op = k in setOf("⌫", "%", "÷", "×", "−", "+", "( )")
                                Box(
                                    Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(50))
                                        .background(if (k == "=") Blue else Card2).clickable { press(k) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (k == "=") OutlinedLabel("=", 30.sp, bg = Blue)
                                    else Text(k, fontSize = if (k == "( )") 24.sp else 30.sp, fontWeight = FontWeight.Medium,
                                        color = when { k == "C" -> Red; op -> Blue; else -> Color.White })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The calculator's sums: the typed expression uses the keys' own symbols (× ÷ − +, % after a number, brackets). */
object Calc {
    private val OPS = setOf('+', '−', '×', '÷')

    fun hasOp(e: String) = e.drop(1).any { it in OPS || it == '%' || it == '(' }

    /** What a key does to the expression. */
    fun press(e: String, k: String): String {
        val last = e.lastOrNull()
        return when (k) {
            "C" -> ""
            "⌫" -> e.dropLast(1)
            "." -> {
                val num = e.takeLastWhile { it.isDigit() || it == '.' }
                when {
                    '.' in num -> e
                    num.isEmpty() -> (if (last == ')' || last == '%') "$e×" else e) + "0."
                    else -> "$e."
                }
            }
            "%" -> if (last != null && (last.isDigit() || last == ')')) "$e%" else e
            "( )" -> {
                val open = e.count { it == '(' } - e.count { it == ')' }
                if (open > 0 && last != null && (last.isDigit() || last == ')' || last == '%')) "$e)"
                else if (last != null && (last.isDigit() || last == ')' || last == '%' || last == '.')) "$e×("
                else "$e("
            }
            "+", "−", "×", "÷" -> when {
                e.isEmpty() -> if (k == "−") "−" else e
                last == '(' -> if (k == "−") "$e−" else e
                last in OPS -> if (e.length == 1) (if (k == "−") e else "") else e.dropLast(1) + k
                last == '.' -> e.dropLast(1) + k
                else -> e + k
            }
            else -> { // a digit
                val num = e.takeLastWhile { it.isDigit() || it == '.' }
                when {
                    num.count { it.isDigit() } >= 15 -> e
                    last == ')' || last == '%' -> "$e×$k"
                    num == "0" -> e.dropLast(1) + k
                    else -> e + k
                }
            }
        }
    }

    /** The answer, or null while the expression isn't a complete sum (or divides by zero). Open brackets close themselves. */
    fun eval(e: String): BigDecimal? {
        if (e.isEmpty()) return null
        val p = Parser(e.trimEnd { it in OPS || it == '(' || it == '.' }.ifEmpty { return null })
        return runCatching { p.expr().also { if (p.i < p.s.length) error("left over") } }.getOrNull()
    }

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    private class Parser(val s: String) {
        var i = 0
        fun peek() = s.getOrNull(i)
        fun expr(): BigDecimal {
            var v = term()
            while (true) when (peek()) {
                '+' -> { i++; v = v.add(term(), MC) }
                '−' -> { i++; v = v.subtract(term(), MC) }
                else -> return v
            }
        }
        fun term(): BigDecimal {
            var v = factor()
            while (true) when (peek()) {
                '×' -> { i++; v = v.multiply(factor(), MC) }
                '÷' -> { i++; val d = factor(); if (d.signum() == 0) error("÷0"); v = v.divide(d, MC) }
                '(' -> v = v.multiply(factor(), MC)
                else -> return v
            }
        }
        fun factor(): BigDecimal {
            if (peek() == '−') { i++; return factor().negate() }
            var v = when (peek()) {
                '(' -> { i++; val x = expr(); if (peek() == ')') i++; x }
                null -> error("end")
                else -> {
                    val start = i
                    while (peek()?.let { it.isDigit() || it == '.' } == true) i++
                    if (start == i) error("number")
                    BigDecimal(s.substring(start, i))
                }
            }
            while (peek() == '%') { i++; v = v.divide(BigDecimal(100), MC) }
            return v
        }
    }

    /** An answer for showing: up to 10 decimals, no trailing zeros, thousands separated. */
    fun format(v: BigDecimal): String {
        val r = v.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = r.toPlainString()
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val int = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = int.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "−" else "") + grouped + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    /** The expression as typed, with thousands separators in long numbers. */
    fun pretty(e: String): String = Regex("\\d+").replace(e) { m ->
        // Only the whole-number part of a number (not digits after a decimal point).
        val before = e.getOrNull(m.range.first - 1)
        if (before == '.') m.value else m.value.reversed().chunked(3).joinToString(",").reversed()
    }
}
