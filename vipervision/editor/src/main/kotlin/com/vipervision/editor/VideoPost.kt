package com.vipervision.editor

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.t8rin.neural_tools.viper.Rife
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features.Features
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The video editor's second pass, after Media3's export: steadies shaky video (OpenCV tracks the
 * picture from frame to frame, the path is smoothed and each frame moved onto it, a little
 * zoomed so no edges show), and fills the gaps slow motion leaves between frames with frames
 * made by RIFE, so slowed parts play smoothly. Frames are decoded, changed and re-encoded
 * (H.264) here; the sound is copied as it is.
 */
object VideoPost {

    /** [stabilize]: 0 off, 1–3 low to high. [smooth]: fill slow motion's gaps up to [fps] frames a second. */
    class Plan(val stabilize: Int, val smooth: Boolean, val fps: Float)

    suspend fun process(input: File, output: File, plan: Plan, progress: (String, Float) -> Unit) {
        OpenCVLoader.initLocal()
        val probe = MediaExtractor().apply { setDataSource(input.absolutePath) }
        val videoTrack = (0 until probe.trackCount).first { probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val format = probe.getTrackFormat(videoTrack)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
        probe.release()

        val corrections = if (plan.stabilize > 0) analyse(input, plan.stabilize, durationUs) { progress("Steadying… (1/2)", it) } else null
        val zoom = when (plan.stabilize) { 1 -> 1.05; 2 -> 1.08; 3 -> 1.12; else -> 1.0 }
        val rife = if (plan.smooth && Rife.isDownloaded()) Rife.open() else null
        val step = (1_000_000.0 / plan.fps).toLong()
        val label = when {
            corrections != null && rife != null -> "Steadying and smoothing slow motion… (2/2)"
            corrections != null -> "Steadying… (2/2)"
            else -> "Smoothing slow motion…"
        }
        var encoder: Encoder? = null
        try {
            var index = 0
            var previous: Bitmap? = null
            var previousUs = 0L
            decode(input) { frame, ptsUs ->
                currentCoroutineContext().ensureActive()
                val enc = encoder ?: Encoder(output, frame.width, frame.height, plan.fps, rotation, input).also { encoder = it }
                val steady = corrections?.let { c ->
                    warp(frame, c.getOrElse(index) { Correction(0.0, 0.0, 0.0) }, zoom)
                } ?: frame
                val before = previous
                if (rife != null && before != null) {
                    val gap = ptsUs - previousUs
                    val missing = (gap.toDouble() / step).roundToInt() - 1
                    if (gap > step * 1.5 && missing > 0) {
                        val count = min(missing, 7)
                        val (a, b) = small(before) to small(steady)
                        for (k in 1..count) {
                            val t = k / (count + 1f)
                            val mid = rife.between(a, b, t)
                            val full = Bitmap.createScaledBitmap(mid, steady.width, steady.height, true)
                            if (mid !== full) mid.recycle()
                            enc.write(full, previousUs + (gap * t).toLong())
                            full.recycle()
                        }
                        if (a !== before) a.recycle()
                        if (b !== steady) b.recycle()
                    }
                }
                enc.write(steady, ptsUs)
                before?.recycle()
                if (steady !== frame) frame.recycle()
                previous = steady
                previousUs = ptsUs
                index++
                if (durationUs > 0) progress(label, (ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
            }
            previous?.recycle()
            encoder?.finish()
        } finally {
            rife?.close()
            encoder?.release()
        }
    }

    /** A copy for RIFE: at most 960 px on the long side (it's slow on bigger frames). */
    private fun small(b: Bitmap): Bitmap {
        val long = max(b.width, b.height)
        if (long <= 960) return b
        val s = 960f / long
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt() / 2 * 2, (b.height * s).roundToInt() / 2 * 2, true)
    }

    // ---- Steadying ----------------------------------------------------------------------------

    /** How much to move one frame (pixels, radians) onto the smoothed path. */
    private class Correction(val dx: Double, val dy: Double, val da: Double)

    /** Tracks the picture through every frame and works out each frame's correction. */
    private suspend fun analyse(input: File, level: Int, durationUs: Long, progress: (Float) -> Unit): List<Correction> {
        val moves = ArrayList<DoubleArray>()
        var prev: Mat? = null
        var scale = 1.0
        val fullWidth = MediaExtractor().run {
            setDataSource(input.absolutePath)
            val t = (0 until trackCount).first { getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            getTrackFormat(t).getInteger(MediaFormat.KEY_WIDTH).also { release() }
        }
        decode(input, grey = true) { frame, ptsUs ->
            currentCoroutineContext().ensureActive()
            // Moves are measured on the small copy, used on the full frames.
            scale = fullWidth.toDouble() / frame.width
            val rgba = Mat()
            Utils.bitmapToMat(frame, rgba)
            frame.recycle()
            val g = Mat()
            Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val p = prev
            moves += if (p == null) doubleArrayOf(0.0, 0.0, 0.0) else motion(p, g)
            p?.release()
            prev = g
            if (durationUs > 0) progress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
        }
        prev?.release()
        // The path so far, smoothed, and the difference.
        val n = moves.size
        val path = Array(n) { DoubleArray(3) }
        for (i in 1 until n) for (k in 0..2) path[i][k] = path[i - 1][k] + moves[i][k]
        val radius = when (level) { 1 -> 12; 2 -> 24; else -> 45 }
        return List(n) { i ->
            val from = max(0, i - radius)
            val to = min(n - 1, i + radius)
            val avg = DoubleArray(3)
            for (j in from..to) for (k in 0..2) avg[k] += path[j][k]
            for (k in 0..2) avg[k] /= (to - from + 1)
            Correction((avg[0] - path[i][0]) * scale, (avg[1] - path[i][1]) * scale, avg[2] - path[i][2])
        }
    }

    /** How the picture moved from [a] to [b] (small greys): x, y, turn. */
    private fun motion(a: Mat, b: Mat): DoubleArray {
        val corners = MatOfPoint()
        val from = MatOfPoint2f()
        val to = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        try {
            Features.goodFeaturesToTrack(a, corners, 200, 0.01, 15.0)
            if (corners.rows() < 8) return doubleArrayOf(0.0, 0.0, 0.0)
            from.fromArray(*corners.toArray().map { Point(it.x, it.y) }.toTypedArray())
            Video.calcOpticalFlowPyrLK(a, b, from, to, status, err)
            val s = status.toArray()
            val f = from.toArray()
            val t = to.toArray()
            val keep = s.indices.filter { s[it].toInt() == 1 }
            if (keep.size < 6) return doubleArrayOf(0.0, 0.0, 0.0)
            val m = Geometry.estimateAffinePartial2D(
                MatOfPoint2f(*keep.map { f[it] }.toTypedArray()),
                MatOfPoint2f(*keep.map { t[it] }.toTypedArray())
            )
            if (m.empty()) return doubleArrayOf(0.0, 0.0, 0.0)
            val out = doubleArrayOf(m.get(0, 2)[0], m.get(1, 2)[0], atan2(m.get(1, 0)[0], m.get(0, 0)[0]))
            m.release()
            return out
        } finally {
            corners.release(); from.release(); to.release(); status.release(); err.release()
        }
    }

    /** [frame] moved by [c] and zoomed by [zoom] about its middle. */
    private fun warp(frame: Bitmap, c: Correction, zoom: Double): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(frame, src)
        val cx = frame.width / 2.0
        val cy = frame.height / 2.0
        val a = zoom * cos(c.da)
        val b = zoom * sin(c.da)
        // p' = zoom·R·(p − centre) + centre + zoom·d
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, a, -b, cx - a * cx + b * cy + zoom * c.dx)
        m.put(1, 0, b, a, cy - b * cx - a * cy + zoom * c.dy)
        val dst = Mat()
        Imgproc.warpAffine(src, dst, m, Size(frame.width.toDouble(), frame.height.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE)
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        src.release(); dst.release(); m.release()
        return out
    }

    // ---- Decoding and encoding ----------------------------------------------------------------

    /** Every frame of [input]'s video, in order, as a bitmap (at most 480 wide when [grey]). */
    private suspend fun decode(input: File, grey: Boolean = false, onFrame: suspend (Bitmap, Long) -> Unit) {
        val extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(o)
                        val bitmap = image?.let { if (grey) yToBitmap(it) else yuvToBitmap(it) }
                        image?.close()
                        decoder.releaseOutputBuffer(o, false)
                        if (bitmap != null) onFrame(bitmap, info.presentationTimeUs)
                    } else {
                        decoder.releaseOutputBuffer(o, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
    }

    /** A decoded frame to colour pixels (BT.601). */
    private fun yuvToBitmap(image: android.media.Image): Bitmap {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val (yp, up, vp) = image.planes
        val y = yp.buffer
        val u = up.buffer
        val v = vp.buffer
        val px = IntArray(w * h)
        for (row in 0 until h) {
            val sy = row + crop.top
            for (col in 0 until w) {
                val sx = col + crop.left
                val yy = (y.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 255) - 16
                val ci = (sy / 2) * up.rowStride + (sx / 2) * up.pixelStride
                val cv = (sy / 2) * vp.rowStride + (sx / 2) * vp.pixelStride
                val uu = (u.get(ci).toInt() and 255) - 128
                val vv = (v.get(cv).toInt() and 255) - 128
                val c = 1.164f * yy
                px[row * w + col] = rgb(c + 1.596f * vv, c - 0.392f * uu - 0.813f * vv, c + 2.017f * uu)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Just the brightness of a decoded frame, shrunk to about 480 wide (for tracking). */
    private fun yToBitmap(image: android.media.Image): Bitmap {
        val crop = image.cropRect
        val step = max(1, crop.width() / 480)
        val w = crop.width() / step
        val h = crop.height() / step
        val yp = image.planes[0]
        val y = yp.buffer
        val px = IntArray(w * h)
        for (row in 0 until h) for (col in 0 until w) {
            val l = y.get((crop.top + row * step) * yp.rowStride + (crop.left + col * step) * yp.pixelStride).toInt() and 255
            px[row * w + col] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** H.264 encoder and MP4 writer; the sound of [soundFrom] is copied in at the end. */
    private class Encoder(output: File, val width: Int, val height: Int, fps: Float, rotation: Int, private val soundFrom: File) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var videoTrack = -1
        private var audioTrack = -1
        private var audioFormat: MediaFormat? = null
        private var audioSource = -1
        private var started = false
        private val info = MediaCodec.BufferInfo()
        private val pixels = IntArray(width * height)

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.2f).toInt().coerceIn(2_000_000, 40_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val extractor = MediaExtractor().apply { setDataSource(soundFrom.absolutePath) }
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioFormat = f
                    audioSource = t
                    break
                }
            }
            extractor.release()
        }

        fun write(frame: Bitmap, ptsUs: Long) {
            val w = min(width, frame.width)
            val h = min(height, frame.height)
            frame.getPixels(pixels, 0, width, 0, 0, w, h)
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val image = codec.getInputImage(i)!!
                    fill(image)
                    codec.queueInputBuffer(i, 0, width * height * 3 / 2, ptsUs, 0)
                    break
                }
                drain(false)
            }
            drain(false)
        }

        /** Our pixels into the encoder's YUV planes. */
        private fun fill(image: android.media.Image) {
            val (yp, up, vp) = image.planes
            val y = yp.buffer
            val u = up.buffer
            val v = vp.buffer
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val c = pixels[row * width + col]
                    val r = c.red8
                    val g = c.green8
                    val b = c.blue8
                    y.put(row * yp.rowStride + col * yp.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
                    if (row % 2 == 0 && col % 2 == 0) {
                        val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                        val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                        u.put((row / 2) * up.rowStride + (col / 2) * up.pixelStride, uu)
                        v.put((row / 2) * vp.rowStride + (col / 2) * vp.pixelStride, vv)
                    }
                }
            }
        }

        private fun drain(end: Boolean) {
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        audioFormat?.let { audioTrack = muxer.addTrack(it) }
                        muxer.start()
                        started = true
                    }
                    o >= 0 -> {
                        val buffer = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buffer, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            // End of the picture.
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            // The sound, as it was.
            if (audioTrack >= 0 && started) {
                val extractor = MediaExtractor().apply { setDataSource(soundFrom.absolutePath) }
                extractor.selectTrack(audioSource)
                val buffer = ByteBuffer.allocate(1 shl 20)
                val sample = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    sample.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioTrack, buffer, sample)
                    extractor.advance()
                }
                extractor.release()
            }
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (started) muxer.stop() }
            muxer.release()
        }
    }
}
