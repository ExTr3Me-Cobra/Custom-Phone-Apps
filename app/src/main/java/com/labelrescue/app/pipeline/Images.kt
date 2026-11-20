package com.labelrescue.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.labelrescue.core.image.GrayImage
import com.labelrescue.core.image.IntRect
import kotlin.math.max
import kotlin.math.roundToInt

/** Conversions between Android images and the core [GrayImage]. */
object Images {
    const val MAX_SIDE = 4000

    /** Luma plane of a YUV camera frame, rotated upright. */
    fun fromYuv(image: ImageProxy): GrayImage {
        val plane = image.planes[0]
        val w = image.width; val h = image.height
        val buf = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride; val pixStride = plane.pixelStride
        val out = GrayImage(w, h)
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            val n = minOf(rowStride, buf.remaining())
            buf.get(row, 0, n)
            if (pixStride == 1) System.arraycopy(row, 0, out.data, y * w, w)
            else for (x in 0 until w) out.data[y * w + x] = row[x * pixStride]
        }
        return out.rotated90(image.imageInfo.rotationDegrees / 90).first
    }

    /** A JPEG still from ImageCapture, rotated upright. */
    fun fromJpeg(image: ImageProxy): GrayImage? {
        val buf = image.planes[0].buffer.duplicate().apply { rewind() }
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val sample = sampleSize(opts.outWidth, opts.outHeight)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val g = fromBitmap(bmp)
        bmp.recycle()
        return g.rotated90(image.imageInfo.rotationDegrees / 90).first
    }

    fun fromUri(ctx: Context, uri: Uri): GrayImage? {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val m = max(s.width, s.height)
                if (m > MAX_SIDE) {
                    val f = MAX_SIDE.toDouble() / m
                    dec.setTargetSize((s.width * f).roundToInt(), (s.height * f).roundToInt())
                }
            }
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val sample = sampleSize(opts.outWidth, opts.outHeight)
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val orientation = ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true)
        }
        val g = fromBitmap(bmp)
        return g
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= MAX_SIDE) s *= 2
        return s
    }

    fun fromBitmap(bmp: Bitmap): GrayImage {
        val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return GrayImage.fromArgb(b.width, b.height, px)
    }

    fun toBitmap(img: GrayImage): Bitmap {
        val px = IntArray(img.width * img.height) {
            val v = img.data[it].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
    }

    /** Zoomed crop around [box] for showing a digit, scaled to about [targetHeight] px tall. */
    fun zoomCrop(img: GrayImage, box: IntRect, targetHeight: Int = 180): Bitmap? {
        val pad = max(box.width, box.height) / 3 + 4
        val r = box.inflate(pad, pad).intersect(IntRect(0, 0, img.width, img.height))
        if (r.isEmpty) return null
        val crop = img.crop(r)
        val f = targetHeight.toDouble() / crop.height
        val scaled = if (f > 1.2 || f < 0.8) crop.scaled((crop.width * f).roundToInt().coerceAtLeast(1), targetHeight) else crop
        return toBitmap(scaled)
    }

    /** NV21 bytes for ML Kit: the gray image as luma with neutral chroma. */
    fun toNv21(img: GrayImage): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))
        System.arraycopy(img.data, 0, out, 0, w * h)
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())
        return out
    }
}
