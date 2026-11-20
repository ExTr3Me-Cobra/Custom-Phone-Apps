package com.labelrescue.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelrescue.app.pipeline.Images
import com.labelrescue.app.pipeline.MlKitBarcode
import com.labelrescue.app.pipeline.MlKitText
import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.barcode.ZxingDecoder
import com.labelrescue.core.candidates.CandidateEngine
import com.labelrescue.core.candidates.HistoryStats
import com.labelrescue.core.fusion.RecoveryPipeline
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.synth.Synth
import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Accuracy benchmark on a device/emulator: renders synthetic USPS labels (service banner,
 * addresses, IMpb barcode, printed number), damages them (blur, smudges, missing ink, tears,
 * rotation, noise), runs the full on-device pipeline (ML Kit + ZXing + partial decoder + OCR)
 * and reports the recovery rate.
 *
 * Run: ./gradlew connectedDebugAndroidTest   (results in logcat tag LabelBenchmark and in the
 * instrumentation status output).
 */
@RunWith(AndroidJUnit4::class)
class LabelBenchmarkTest {

    private fun randomTracking(rnd: Random): String {
        val payload = "92" + listOf("055", "001", "346", "612").random(rnd) + "9" + (1..15).map { '0' + rnd.nextInt(10) }.joinToString("")
        return payload + CheckDigits.mod10(payload)
    }

    private fun renderLabel(tracking: String, zip: String, service: String): Pair<GrayImage, IntRect> {
        val w = 1200; val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(236, 236, 230))
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 46f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        c.drawText("USPS $service", 60f, 90f, bold)
        c.drawText("SHIP TO:", 60f, 180f, text)
        c.drawText("JANE SAMPLE", 60f, 220f, text)
        c.drawText("100 EXAMPLE ST", 60f, 260f, text)
        c.drawText("ANYTOWN ST $zip", 60f, 300f, text)
        c.drawText("USPS TRACKING # EP", 330f, 400f, text)
        // Barcode: 420 + ZIP, then the tracking number.
        val mods = Code128.encodeModules(Code128.impbValues(tracking, zip), 0)
        val module = 3.4f
        val bw = mods.size * module
        val x0 = (w - bw) / 2; val top = 430f; val bh = 170f
        val bar = Paint().apply { color = Color.BLACK }
        for ((i, m) in mods.withIndex()) if (m) c.drawRect(x0 + i * module, top, x0 + (i + 1) * module, top + bh, bar)
        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f; typeface = Typeface.MONOSPACE }
        val printed = TrackingFormat.grouped(tracking)
        val tw = mono.measureText(printed)
        c.drawText(printed, (w - tw) / 2, top + bh + 55f, mono)
        val g = Images.fromBitmap(bmp)
        bmp.recycle()
        return g to IntRect(x0.toInt(), top.toInt(), (x0 + bw).toInt(), (top + bh).toInt())
    }

    private fun damage(img0: GrayImage, barcode: IntRect, rnd: Random, level: Int): GrayImage {
        var img = Synth.blur(img0, 0.6 + 0.5 * level * rnd.nextDouble())
        repeat(level) {
            when (rnd.nextInt(5)) {
                0 -> img = Synth.verticalLoss(img, barcode.left + rnd.nextInt(barcode.width - 40), 30 + rnd.nextInt(30))
                1 -> img = Synth.smudge(img, rnd, 4, 30)
                2 -> img = Synth.inkDropout(img, rnd, 25, 14)
                3 -> img = Synth.tear(img, IntRect(img.width - rnd.nextInt(200, 380), 0, img.width, rnd.nextInt(200, 700)), 40, rnd)
                else -> img = Synth.fade(img, 0.45)
            }
        }
        img = Synth.shade(img, 30)
        img = Synth.noise(img, 4.0 + 3 * level, rnd)
        return Synth.rotate(img, rnd.nextDouble() * 6 - 3)
    }

    @Test
    fun recoveryRate() {
        val rnd = Random(77)
        val engine = CandidateEngine()
        val services = listOf("GROUND ADVANTAGE", "PRIORITY MAIL", "PRIORITY MAIL EXPRESS")
        val cases = 15
        var full = 0; var top1 = 0; var top5 = 0
        MlKitBarcode().use { mb ->
            MlKitText().use { mt ->
                val pipeline = RecoveryPipeline(listOf(mb, ZxingDecoder()), mt)
                for (n in 0 until cases) {
                    val truth = randomTracking(rnd)
                    val (label, barcode) = renderLabel(truth, "%05d".format(rnd.nextInt(100000)), services[n % services.size])
                    val level = 1 + n % 3
                    val frames = List(3) { damage(label, barcode, Random(n * 10 + it), level) }
                    val out = pipeline.run(frames, RecoveryPipeline.Options(maxFrames = 3))
                    val nums = when {
                        out.fullDecode != null -> { full++; listOf(out.fullDecode!!.tracking) }
                        out.format != null -> engine.rank(out.format!!, out.slots, out.prior(HistoryStats.EMPTY), out.exactEvidence()).candidates.map { it.tracking }
                        else -> emptyList()
                    }
                    if (nums.firstOrNull() == truth) top1++
                    if (truth in nums.take(5)) top5++
                    Log.i(TAG, "case $n level $level truth=$truth best=${nums.firstOrNull()} full=${out.fullDecode != null} ${out.stats.millis}ms")
                }
            }
        }
        val summary = "cases=$cases fullDecode=$full top1=$top1 (${100 * top1 / cases}%) top5=$top5 (${100 * top5 / cases}%)"
        Log.i(TAG, "RESULT $summary")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "LabelBenchmark $summary\n") })
        // A floor that catches regressions; the printed rates are the real report.
        assertTrue(summary, top5 >= cases / 2)
    }

    private companion object { const val TAG = "LabelBenchmark" }
}
