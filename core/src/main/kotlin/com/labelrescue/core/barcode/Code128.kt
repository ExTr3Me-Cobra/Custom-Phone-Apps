package com.labelrescue.core.barcode

import com.labelrescue.core.usps.CheckDigits

/** Code 128 symbol table and a GS1-128 (code set C) encoder. */
object Code128 {
    const val FNC1 = 102
    const val START_A = 103
    const val START_B = 104
    const val START_C = 105
    const val STOP = 106

    /** Bar/space widths (in modules) for values 0..106. Symbols are 11 modules; stop is 13. */
    val PATTERNS: Array<IntArray> = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    ).map { s -> IntArray(s.length) { s[it] - '0' } }.toTypedArray()

    /** Module pattern (true = bar) for a symbol value. */
    fun modules(value: Int): BooleanArray {
        val p = PATTERNS[value]
        val out = BooleanArray(p.sum())
        var i = 0
        for ((k, w) in p.withIndex()) { repeat(w) { out[i++] = k % 2 == 0 } }
        return out
    }

    /**
     * Symbol values for a GS1-128 barcode in code set C. [segments] are digit strings, each
     * preceded by FNC1 (e.g. ["42012345", "9234..."] for the IMpb layout). Each must have even length.
     */
    fun gs1Values(segments: List<String>): IntArray {
        val v = ArrayList<Int>()
        v += START_C
        for (seg in segments) {
            require(seg.length % 2 == 0 && seg.all { it.isDigit() }) { "segment must be an even number of digits" }
            v += FNC1
            for (i in seg.indices step 2) v += (seg[i] - '0') * 10 + (seg[i + 1] - '0')
        }
        v += CheckDigits.code128(v.toIntArray())
        v += STOP
        return v.toIntArray()
    }

    /** Full module sequence including quiet zones. */
    fun encodeModules(values: IntArray, quietZone: Int = 10): BooleanArray {
        val out = ArrayList<Boolean>()
        repeat(quietZone) { out += false }
        for (v in values) out.addAll(modules(v).toList())
        repeat(quietZone) { out += false }
        return out.toBooleanArray()
    }

    /** The IMpb barcode content for a tracking number with an optional "420"+ZIP routing prefix. */
    fun impbValues(tracking: String, zip: String? = null): IntArray =
        gs1Values(listOfNotNull(zip?.let { "420$it" }, tracking))
}
