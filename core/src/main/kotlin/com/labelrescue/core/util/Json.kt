package com.labelrescue.core.util

/**
 * Minimal JSON reader/writer (objects → Map, arrays → List, numbers → Double), so the core
 * module needs no JSON library on either the JVM or Android.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> { val d = v.toDouble(); if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d) }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; quote(k.toString(), sb); sb.append(':'); write(x, sb) }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); var first = true; for (x in v) { if (!first) sb.append(','); first = false; write(x, sb) }; sb.append(']') }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out += value(); ws(); val c = s[i++]; if (c == ']') return out; require(c == ',') { "expected , at ${i - 1}" } }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); require(s[i++] == ':') { "expected : at ${i - 1}" }
                out[k] = value(); ws()
                val c = s[i++]; if (c == '}') return out; require(c == ',') { "expected , at ${i - 1}" }
            }
        }
    }
}
