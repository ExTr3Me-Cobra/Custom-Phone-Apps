// In ZXing's own package: its DataMatrix codeword layout, block interleaving and payload parser
// are package-private, and re-implementing them would only add places to get them wrong.
package com.google.zxing.datamatrix.decoder

import com.google.zxing.common.BitMatrix

/** ZXing's DataMatrix internals that [com.labelrescue.core.barcode.DataMatrixRescue] needs. */
object ZxingDataMatrixAccess {
    private val maps = HashMap<Pair<Int, Int>, IntArray>()

    /**
     * For a symbol of [rows]×[cols] modules: for each module (row·cols + col), codeword·8 + bit
     * (bit 0 = least significant) it carries, or -1 (finder, timing, alignment). Found by setting one
     * module at a time and reading the codewords back, so it follows ZXing's placement exactly.
     */
    @Synchronized fun moduleMap(rows: Int, cols: Int): IntArray = maps.getOrPut(rows to cols) {
        val out = IntArray(rows * cols) { -1 }
        for (r in 0 until rows) for (c in 0 until cols) {
            val m = BitMatrix(cols, rows); m.set(c, r)
            val cw = try { BitMatrixParser(m).readCodewords() } catch (e: Exception) { continue }
            for ((i, b) in cw.withIndex()) {
                val v = b.toInt() and 0xFF
                if (v != 0) { out[r * cols + c] = i * 8 + Integer.numberOfTrailingZeros(v); break }
            }
        }
        out
    }

    fun totalCodewords(rows: Int, cols: Int): Int = Version.getVersionForDimensions(rows, cols).totalCodewords

    /** The interleaved blocks: for each, the codeword indices (data first, then check) and its data count. */
    fun blocks(rows: Int, cols: Int): List<Pair<IntArray, Int>> {
        val v = Version.getVersionForDimensions(rows, cols)
        val total = v.totalCodewords
        // DataBlock takes bytes; run it on codeword indices split into low and high bytes.
        val lo = DataBlock.getDataBlocks(ByteArray(total) { (it and 0xFF).toByte() }, v)
        val hi = DataBlock.getDataBlocks(ByteArray(total) { ((it shr 8) and 0xFF).toByte() }, v)
        return lo.indices.map { b ->
            val a = lo[b].codewords; val h = hi[b].codewords
            IntArray(a.size) { (a[it].toInt() and 0xFF) or ((h[it].toInt() and 0xFF) shl 8) } to lo[b].numDataCodewords
        }
    }

    /** The payload text of corrected data codewords, or null if they don't parse. */
    fun parse(data: ByteArray): String? = try { DecodedBitStreamParser.decode(data).text } catch (e: Exception) { null }
}
