package com.labelrescue.app.data

import android.content.Context
import com.labelrescue.core.usps.LayoutSample
import com.labelrescue.core.usps.LearnedLayout
import java.io.File

/**
 * Barcode positions learned from intact labels in learn mode (see [LearnedLayout]): only offsets
 * between barcodes and printed lines, no text or images. Local only; exported with the training data.
 */
class BarcodeLayouts(ctx: Context) {
    val file = File(ctx.filesDir, "barcode_layout.txt")

    @Synchronized
    fun load(): LearnedLayout = LearnedLayout.decode(runCatching { file.readText() }.getOrDefault(""))

    /** Adds [samples]; returns how many positions are now known. */
    @Synchronized
    fun add(samples: List<LayoutSample>): Int {
        val all = (load().samples + samples).takeLast(LearnedLayout.MAX_SAMPLES)
        val l = LearnedLayout(all)
        file.writeText(l.encode())
        return l.size
    }

    fun count(): Int = load().size
}
