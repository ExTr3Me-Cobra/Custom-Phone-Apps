package com.vipervision.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Auto Borders' edge snapping: the painting's edge moved to the object's real edge, only within a
 * thin rim around it. Deep inside the painting stays selected (whatever its colour, e.g. a dark
 * shadowed side), far outside never is. In the rim, OpenCV's GrabCut decides by colour, learning
 * the object from inside the painting and the background from around it, with Smart select's
 * outline as a hint for where the object goes on past the painting.
 */
object EdgeSnap {

    /**
     * [painted] and [hint] are white on black at [image]'s size; [radius] is the brush's radius
     * in [image] pixels. Returns the snapped selection, white on black at [image]'s size.
     */
    fun snap(image: Bitmap, painted: Bitmap, hint: Bitmap?, radius: Float): Bitmap {
        OpenCVLoader.initLocal()
        val inner = max(2, (radius * INNER).roundToInt())
        val outer = max(3, (radius * OUTER).roundToInt())
        val bounds = bounds(painted) ?: return painted.copy(Bitmap.Config.ARGB_8888, true)
        val pad = outer * 2 + 8
        val crop = android.graphics.Rect(
            (bounds.left - pad).coerceAtLeast(0), (bounds.top - pad).coerceAtLeast(0),
            (bounds.right + pad).coerceAtMost(image.width), (bounds.bottom + pad).coerceAtMost(image.height)
        )
        val mats = mutableListOf<Mat>()
        fun <T : Mat> T.kept(): T = also { mats += it }
        try {
            val rgb = Mat().kept().also { out ->
                val rgba = Mat().kept()
                Utils.bitmapToMat(Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height()).copy(Bitmap.Config.ARGB_8888, false), rgba)
                Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2RGB)
            }
            fun gray(mask: Bitmap): Mat {
                val rgba = Mat().kept()
                Utils.bitmapToMat(Bitmap.createBitmap(mask, crop.left, crop.top, crop.width(), crop.height()).copy(Bitmap.Config.ARGB_8888, false), rgba)
                val g = Mat().kept()
                Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.threshold(g, g, 127.0, 255.0, Imgproc.THRESH_BINARY)
                return g
            }
            val paint = gray(painted)
            val core = Mat().kept().also { Imgproc.erode(paint, it, disk(inner).kept()) }
            val reach = Mat().kept().also { Imgproc.dilate(paint, it, disk(outer).kept()) }
            // Labels: sure background outside the reach, the rim "probably" by what was painted,
            // Smart select adding "probably object" past the painting, sure object in the core.
            val labels = Mat(rgb.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble())).kept()
            labels.setTo(Scalar(Imgproc.GC_PR_BGD.toDouble()), reach)
            labels.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), paint)
            if (hint != null) {
                val h = gray(hint)
                Core.bitwise_and(h, reach, h)
                labels.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), h)
            }
            labels.setTo(Scalar(Imgproc.GC_FGD.toDouble()), core)
            val bgModel = Mat().kept()
            val fgModel = Mat().kept()
            Imgproc.grabCut(rgb, labels, Rect(), bgModel, fgModel, ITERATIONS, Imgproc.GC_INIT_WITH_MASK)
            // Object = sure or probable object.
            val fg = Mat().kept()
            val probable = Mat().kept()
            Core.compare(labels, Scalar(Imgproc.GC_FGD.toDouble()), fg, Core.CMP_EQ)
            Core.compare(labels, Scalar(Imgproc.GC_PR_FGD.toDouble()), probable, Core.CMP_EQ)
            Core.bitwise_or(fg, probable, fg)
            // Clean lines: no specks or pinholes, a smooth edge.
            val small = disk(2).kept()
            Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, small)
            Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_CLOSE, small)
            Imgproc.medianBlur(fg, fg, 5)
            // The core always stays, whatever GrabCut thought.
            Core.bitwise_or(fg, core, fg)
            val rgbaOut = Mat().kept()
            Imgproc.cvtColor(fg, rgbaOut, Imgproc.COLOR_GRAY2RGBA)
            val piece = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaOut, piece)
            val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(piece, crop.left.toFloat(), crop.top.toFloat(), null)
            }
            piece.recycle()
            return out
        } finally {
            mats.forEach { it.release() }
        }
    }

    private fun disk(radius: Int): Mat =
        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0 * radius + 1, 2.0 * radius + 1))

    private fun bounds(mask: Bitmap): android.graphics.Rect? {
        val row = IntArray(mask.width)
        var l = mask.width
        var t = mask.height
        var r = -1
        var b = -1
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
            for (x in row.indices) {
                if (Color.red(row[x]) >= 128) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    b = y
                }
            }
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    /** Inside the painting, this much of the brush's radius from its edge can still change. */
    private const val INNER = 0.45f

    /** Outside it, how far (of the brush's radius) the edge can still grow. */
    private const val OUTER = 0.9f

    private const val ITERATIONS = 4
}
