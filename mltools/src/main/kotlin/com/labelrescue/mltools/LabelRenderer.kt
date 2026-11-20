package com.labelrescue.mltools

import com.labelrescue.core.barcode.Code128
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import com.labelrescue.core.ml.DetectedRegion.Kind
import com.labelrescue.core.usps.CheckDigits
import com.labelrescue.core.usps.TrackingFormat
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.random.Random

/** Fonts that look like label printers: sans, mono, condensed; whichever are installed. */
object LabelFonts {
    private val WANTED = listOf(
        "Liberation Sans", "Liberation Mono", "Liberation Sans Narrow", "DejaVu Sans", "DejaVu Sans Mono", "DejaVu Sans Condensed",
        "FreeSans", "FreeMono", "Nimbus Sans", "Nimbus Mono PS", "Noto Sans", "Noto Sans Mono", "Inter", "JetBrains Mono",
        "Carlito", "Courier 10 Pitch", "Bitstream Charter", "Ubuntu", "Ubuntu Mono", "Roboto", "Roboto Mono", "Arial", "Helvetica",
    )

    val available: List<String> by lazy {
        val have = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        WANTED.filter { it in have }.ifEmpty { listOf(Font.SANS_SERIF, Font.MONOSPACED) }
    }

    fun random(rnd: Random, size: Float, bold: Boolean = rnd.nextInt(3) == 0): Font =
        Font(available.random(rnd), if (bold) Font.BOLD else Font.PLAIN, 12).deriveFont(size)
}

/** A synthetic label with ground truth for every element. */
class RenderedLabel(
    val image: GrayImage,
    val tracking: String,
    val zip: String,
    /** Ink bounds of each printed tracking digit. */
    val digitBoxes: List<IntRect>,
    val regions: Map<Kind, List<IntRect>>,
    /** The label's text lines (what an OCR would read), excluding the tracking number. */
    val textLines: List<String>,
    val numberLine: IntRect,
)

object LabelRenderer {
    private val SERVICES = listOf("PRIORITY MAIL", "GROUND ADVANTAGE", "PRIORITY MAIL EXPRESS", "FIRST-CLASS")
    private val CITIES = listOf("BARRE VT", "SEWELL NJ", "AUSTIN TX", "DENVER CO", "SALEM OR", "MACON GA", "TROY NY", "OGDEN UT")
    private val STREETS = listOf("100 MAIN ST", "5 ELM ST", "42 OAK AVE", "7 PINE RD", "1200 LAKE DR", "88 HILL CT")

    fun randomTracking(rnd: Random): String {
        val payload = "92" + listOf("055", "001", "346", "612", "021").random(rnd) + "9" + (1..15).map { '0' + rnd.nextInt(10) }.joinToString("")
        return payload + CheckDigits.mod10(payload)
    }

    fun render(tracking: String, zip: String, rnd: Random): RenderedLabel {
        val w = 1000 + rnd.nextInt(300); val h = 820 + rnd.nextInt(180)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = bi.createGraphics()
        val thermalAa = rnd.nextInt(4) != 0
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, if (thermalAa) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        val paper = 225 + rnd.nextInt(25)
        g.color = Color(paper, paper, paper); g.fillRect(0, 0, w, h)
        val ink = Color(rnd.nextInt(10, 50), rnd.nextInt(10, 50), rnd.nextInt(10, 50))
        val regions = HashMap<Kind, MutableList<IntRect>>()
        val lines = ArrayList<String>()
        fun add(k: Kind, r: IntRect) { regions.getOrPut(k) { ArrayList() } += r }

        fun text(s: String, x: Int, y: Int, font: Font): IntRect {
            g.font = font; g.color = ink
            g.drawString(s, x, y)
            val fm = g.fontMetrics
            lines += s
            return IntRect(x, y - fm.ascent, x + fm.stringWidth(s), y + fm.descent)
        }
        /** Bounds of [sub] within [s] drawn at x,y. */
        fun subBounds(s: String, sub: String, x: Int, y: Int, font: Font): IntRect {
            g.font = font
            val fm = g.fontMetrics
            val i = s.indexOf(sub)
            val x0 = x + fm.stringWidth(s.substring(0, i))
            return IntRect(x0, y - fm.ascent + fm.descent / 2, x0 + fm.stringWidth(sub), y + fm.descent / 2)
        }

        // Service banner: plain bold or inverted bar.
        val service = SERVICES.random(rnd)
        val bannerFont = LabelFonts.random(rnd, 34f + rnd.nextInt(16), bold = true)
        val bannerText = "USPS $service"
        if (rnd.nextBoolean()) {
            g.font = bannerFont
            val fm = g.fontMetrics
            val bw = fm.stringWidth(bannerText) + 30
            g.color = ink; g.fillRect(30, 30, bw, fm.height + 16)
            g.color = Color(paper, paper, paper); g.drawString(bannerText, 45, 38 + fm.ascent)
            lines += bannerText
            add(Kind.SERVICE_BANNER, IntRect(30, 30, 30 + bw, 46 + fm.height))
        } else add(Kind.SERVICE_BANNER, text(bannerText, 40, 80, bannerFont))

        // Sender.
        val small = LabelFonts.random(rnd, 22f + rnd.nextInt(8), bold = false)
        val originZip = "%05d".format(rnd.nextInt(100000))
        val sy = 160
        text("JOHN SENDER", 40, sy, small)
        text(STREETS.random(rnd), 40, sy + 30, small)
        val oLine = "${CITIES.random(rnd)} $originZip"
        text(oLine, 40, sy + 60, small)
        add(Kind.ZIP, subBounds(oLine, originZip, 40, sy + 60, small))
        val date = "%02d/%02d/2026".format(1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
        if (rnd.nextBoolean()) text("SHIP DATE: $date", w - 330, sy, small) else text("MAILED FROM ZIP $originZip", w - 420, sy, small)

        // Recipient.
        val big = LabelFonts.random(rnd, 26f + rnd.nextInt(10))
        val ry = 290
        text("SHIP TO:", 40, ry, small)
        text("JANE SAMPLE", 120, ry + 36, big)
        text(STREETS.random(rnd), 120, ry + 72, big)
        val dLine = "${CITIES.random(rnd)} $zip"
        text(dLine, 120, ry + 108, big)
        add(Kind.ZIP, subBounds(dLine, zip, 120, ry + 108, big))

        // Barcode.
        val trackingCaption = text("USPS TRACKING # EP", w / 2 - 150, ry + 170, small)
        val mods = Code128.encodeModules(Code128.impbValues(tracking, zip), 0)
        val module = 2.6 + rnd.nextDouble() * 1.4
        val bw = (mods.size * module).toInt()
        val bx = (w - bw) / 2; val by = trackingCaption.bottom + 14; val bh = 120 + rnd.nextInt(70)
        g.color = ink
        for ((i, m) in mods.withIndex()) if (m) {
            val x0 = (bx + i * module).toInt(); val x1 = (bx + (i + 1) * module).toInt()
            g.fillRect(x0, by, maxOf(1, x1 - x0), bh)
        }
        add(Kind.BARCODE, IntRect(bx, by, bx + bw, by + bh))

        // Printed tracking number, grouped 4-4-4-4-4-2.
        val numFont = LabelFonts.random(rnd, 30f + rnd.nextInt(14), bold = rnd.nextInt(4) == 0)
        g.font = numFont
        val printed = TrackingFormat.grouped(tracking)
        val fm = g.fontMetrics
        val tx = (w - fm.stringWidth(printed)) / 2; val ty = by + bh + 16 + fm.ascent
        g.drawString(printed, tx, ty)
        val gv = numFont.createGlyphVector(g.fontRenderContext, printed)
        val boxes = ArrayList<IntRect>()
        for (i in printed.indices) {
            if (printed[i] == ' ') continue
            val b = gv.getGlyphPixelBounds(i, g.fontRenderContext, tx.toFloat(), ty.toFloat())
            boxes += IntRect(b.x, b.y, b.x + b.width, b.y + b.height)
        }
        val numberLine = boxes.reduce { a, b -> a.union(b) }
        add(Kind.TRACKING_TEXT, numberLine)
        g.dispose()

        val data = (bi.raster.getDataElements(0, 0, w, h, null) as ByteArray)
        return RenderedLabel(GrayImage(w, h, data.copyOf()), tracking, zip, boxes, regions, lines, numberLine)
    }
}
