package com.clockout.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the photo several ways and combines what each one finds. A photo of a screen is full of moiré
 * (the wavy rainbow pattern) that can hide whole rows from one reading; a smoothed, smaller or high-contrast
 * copy often reads those rows fine. A ring found in any reading counts; where readings disagree slightly
 * on a time, the one most of them agree on wins.
 */
object Ocr {
    class Result(val rings: List<Ring>, val raw: String, val decimal: Boolean, val switched: Boolean)

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private suspend fun recognize(img: InputImage): Text = suspendCancellableCoroutine { c ->
        client.process(img).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
    }

    private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    /** Grey, extra contrast, and softened (down then up) to wash out the screen's pixel grid. */
    private fun cleaned(b: Bitmap): Bitmap {
        val small = scaled(b, 900)
        val soft = Bitmap.createScaledBitmap(small, small.width * 2, small.height * 2, true)
        val out = Bitmap.createBitmap(soft.width, soft.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val c = 1.7f; val t = -0.35f * 255
        cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(soft, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); isFilterBitmap = true })
        return out
    }

    /** Reads one or more pictures of the same rings; every reading of every picture votes. */
    suspend fun read(context: Context, uris: List<Uri>, s: Settings): Result {
        val texts = mutableListOf<Text>()
        for (uri in uris) readInto(context, uri, texts)
        var decimal = s.decimal
        var rings = merge(texts, decimal)
        var switched = false
        // Nothing in the chosen time style: see if the picture uses the other one.
        if (rings.isEmpty()) merge(texts, !decimal).takeIf { it.isNotEmpty() }?.let { rings = it; decimal = !decimal; switched = true }
        // The phone's own AI reads it too, where it can: its answer wins unless the text reading found more rings.
        val aiRaw = StringBuilder()
        val ai = runCatching { AiReader.read(context, uris, decimal, aiRaw) }.getOrNull()
        if (ai != null && ai.size >= rings.size) return Result(ai, "Read by the phone's AI:\n$aiRaw", decimal, switched)
        return Result(rings, texts.first().text, decimal, switched)
    }

    private suspend fun readInto(context: Context, uri: Uri, texts: MutableList<Text>) {
        texts += recognize(InputImage.fromFilePath(context, uri))
        // The extra readings (the picture upright, as decoded).
        runCatching {
            val bmp = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            val variants = withContext(Dispatchers.Default) { listOf(scaled(bmp, 1600), scaled(bmp, 1100), cleaned(bmp)) }
            for (v in variants) runCatching { texts += recognize(InputImage.fromBitmap(v, 0)) }
        }
    }

    /** Rings from every reading, combined. */
    private fun merge(texts: List<Text>, decimal: Boolean): List<Ring> {
        class Vote(val ring: Ring, val pass: Int)
        val votes = texts.flatMapIndexed { i, t -> RingReader.read(t, decimal).map { Vote(it, i) } }
        // Group readings of the same ring (within a minute and a half of each other).
        val groups = mutableListOf<MutableList<Vote>>()
        for (v in votes.sortedBy { it.ring.time }) {
            val g = groups.lastOrNull()
            if (g != null && Duration.between(g.first().ring.time, v.ring.time).abs().seconds <= 90) g += v else groups += mutableListOf(v)
        }
        return groups.mapNotNull { g ->
            val passes = g.map { it.pass }.toSet().size
            val code = g.map { it.ring.code }.filter { it != "?" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "?"
            // A one-off time with no code in only one reading is more likely a misread than a real ring.
            if (passes < 2 && code == "?" && texts.size > 1) return@mapNotNull null
            val time = g.groupingBy { it.ring.time }.eachCount().maxByOrNull { it.value }!!.key
            Ring(code, time)
        }
    }
}
