package com.vipervision.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Part of the photo to keep, as fractions (0 to 1) of the rotated and mirrored photo, so the same
 * crop works on the screen-sized preview and on the full-size photo.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0.0005f && top <= 0.0005f && right >= 0.9995f && bottom >= 0.9995f

    /** The same area after the photo is turned 90° clockwise. */
    fun rotatedClockwise() = CropRect(1f - bottom, left, 1f - top, right)

    /** The same area after the photo is mirrored left-to-right. */
    fun mirrored() = CropRect(1f - right, top, 1f - left, bottom)
}

/** Rotation (in quarter turns clockwise), mirroring and crop, applied before the adjustments. */
data class Geometry(
    val quarterTurns: Int = 0,
    val flipped: Boolean = false,
    val crop: CropRect = CropRect()
) {
    val isIdentity: Boolean get() = quarterTurns % 4 == 0 && !flipped && crop.isFull

    /** Width and height of a [width] x [height] photo after the rotation (before cropping). */
    fun turnedSize(width: Int, height: Int): Pair<Int, Int> =
        if (quarterTurns % 2 == 1) height to width else width to height
}

fun Bitmap.transformed(geometry: Geometry): Bitmap {
    val turned = if (geometry.quarterTurns % 4 == 0 && !geometry.flipped) {
        this
    } else {
        val matrix = Matrix().apply {
            if (geometry.flipped) postScale(-1f, 1f)
            postRotate(90f * (geometry.quarterTurns % 4))
        }
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
    val crop = geometry.crop
    if (crop.isFull) return turned
    val x = (crop.left * turned.width).roundToInt().coerceIn(0, turned.width - 1)
    val y = (crop.top * turned.height).roundToInt().coerceIn(0, turned.height - 1)
    val w = (crop.width * turned.width).roundToInt().coerceIn(1, turned.width - x)
    val h = (crop.height * turned.height).roundToInt().coerceIn(1, turned.height - y)
    return Bitmap.createBitmap(turned, x, y, w, h)
}

/**
 * Decodes [uri] upright (EXIF rotation applied) as a software ARGB_8888 bitmap in sRGB, scaled
 * down so its longer side is at most [maxSide] pixels.
 */
fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longSide = max(info.size.width, info.size.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        decodeLegacy(context, uri, maxSide)
    }
    return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
}

private fun decodeLegacy(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Couldn't open the photo")
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val upright = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> decoded.transformed(Geometry(1))
        ExifInterface.ORIENTATION_ROTATE_180 -> decoded.transformed(Geometry(2))
        ExifInterface.ORIENTATION_ROTATE_270 -> decoded.transformed(Geometry(3))
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> decoded.transformed(Geometry(0, true))
        else -> decoded
    }
    val longSide = max(upright.width, upright.height)
    if (longSide <= maxSide) return upright
    val scale = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(
        upright,
        (upright.width * scale).roundToInt(),
        (upright.height * scale).roundToInt(),
        true
    )
}

/**
 * The largest long side the full-size render can use without running out of memory. Saving holds
 * about three copies of the photo at once (decoded, working pixels, result).
 */
fun maxSafeSide(context: Context, uri: Uri): Int {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val width = bounds.outWidth.coerceAtLeast(1)
    val height = bounds.outHeight.coerceAtLeast(1)
    // Photos' pixels live outside Java's heap (Android 8+), so the budget is the phone's free
    // memory, not the heap limit (512 MB), which scaled big photos down and saved them smaller.
    val memory = android.app.ActivityManager.MemoryInfo().also {
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
    }
    val budget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && memory.availMem > 0) {
        minOf(memory.availMem * 0.35, 1.5e9)
    } else {
        Runtime.getRuntime().maxMemory() * 0.55
    }
    val maxPixels = budget / (4.0 * 3.2)
    val scale = minOf(1.0, sqrt(maxPixels / (width.toDouble() * height)))
    return (max(width, height) * scale).toInt()
}

private val exifTagsToKeep = listOf(
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP
)

/** Name, folder and date of the photo being edited, used to save the copy next to it. */
class SourceInfo(val displayName: String, val relativeFolder: String?, val absoluteFolder: File?, val dateTaken: Long?)

fun sourceInfo(context: Context, uri: Uri, realPath: String?): SourceInfo {
    val file = realPath?.let(::File)
    var name = file?.name
    if (name == null) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
    }
    val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
    val folder = file?.parentFile
    val relative = folder?.absolutePath?.let { "$it/" }?.takeIf { it.startsWith(storageRoot) }?.removePrefix(storageRoot)

    var dateTaken: Long? = null
    if (realPath != null) {
        @Suppress("DEPRECATION")
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(realPath),
            null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) dateTaken = it.getLong(0) }
    }
    return SourceInfo(name ?: "photo.jpg", relative, folder?.takeIf { relative != null }, dateTaken)
}

/** How to write the saved photo: the bytes plus their file extension and MIME type. */
class EncodedPhoto(val bytes: ByteArray, val extension: String, val mimeType: String)

/**
 * Renders the edits at full size and saves them as a new photo next to the original, named
 * "<original>_edited" (Save as copy / Save as…). The original file isn't changed. Returns the new photo's uri.
 * [encode] turns the rendered photo into a file (JPEG/PNG by default; "Save as…" picks any format,
 * quality and size); [metadata] then sets the photo details (null keeps the original's).
 */
suspend fun saveEditedCopy(
    context: Context,
    source: Uri,
    realPath: String?,
    geometry: Geometry,
    finish: FinishEdits,
    renderFrom: Uri = source,
    forcePng: Boolean = false,
    encode: (suspend (Bitmap, Boolean) -> EncodedPhoto)? = null,
    keepMetadata: Boolean = true,
    metadata: Map<String, String?>? = null,
    pixelsUnchanged: Boolean = false
): Uri {
    val info = sourceInfo(context, source, realPath)
    // Only turned or flipped: the original's own bytes, untouched (see losslessTurn).
    val lossless = if (pixelsUnchanged && encode == null && !forcePng && renderFrom == source && keepMetadata && metadata == null) {
        losslessTurn(context, source, geometry)
    } else null
    val encoded = if (lossless != null) EncodedPhoto(lossless, "jpg", "image/jpeg") else {
        val rendered = renderFull(context, renderFrom, geometry, finish, pixelsUnchanged)
        val isPng = forcePng || info.displayName.endsWith(".png", ignoreCase = true)
        encode?.invoke(rendered, isPng) ?: java.io.ByteArrayOutputStream().let { out ->
            rendered.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (isPng) EncodedPhoto(out.toByteArray(), "png", "image/png") else EncodedPhoto(out.toByteArray(), "jpg", "image/jpeg")
        }
    }
    val extension = encoded.extension
    val mime = encoded.mimeType
    val baseName = info.displayName.substringBeforeLast('.') + "_edited"
    val resolver = context.contentResolver
    // Android's ExifInterface can write details into these formats.
    val canHoldExif = extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")

    fun writeDetails(target: Uri) {
        // The untouched original already has all its details (and its new orientation).
        if (!canHoldExif || lossless != null) return
        if (keepMetadata) copyExif(context, source, target)
        if (metadata != null) setExif(context, target, metadata)
    }

    val uri: Uri
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            // Android only allows new photos under DCIM/ or Pictures/.
            val folder = info.relativeFolder?.takeIf { it.startsWith("DCIM/") || it.startsWith("Pictures/") }
            put(MediaStore.Images.Media.RELATIVE_PATH, folder ?: "Pictures/Viper Vision/")
            if (keepMetadata) info.dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the new photo")
        resolver.openOutputStream(uri)?.use { it.write(encoded.bytes) }
            ?: error("Couldn't write the new photo")
        writeDetails(uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } else {
        @Suppress("DEPRECATION")
        val folder = info.absoluteFolder
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Viper Vision")
        folder.mkdirs()
        var file = File(folder, "$baseName.$extension")
        var n = 1
        while (file.exists()) file = File(folder, "$baseName (${n++}).$extension")
        file.writeBytes(encoded.bytes)
        uri = Uri.fromFile(file)
        writeDetails(uri)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
    }
    return uri
}

/**
 * The edits rendered and encoded in the original's own format (JPEG, PNG or WebP), ready to go
 * over the original file, or null when they can't (another format, or a JPEG that now has
 * transparency): those are saved as a copy instead.
 */
suspend fun encodeForOverwrite(
    context: Context,
    source: Uri,
    realPath: String?,
    geometry: Geometry,
    finish: FinishEdits,
    renderFrom: Uri,
    needsTransparency: Boolean,
    pixelsUnchanged: Boolean = false
): ByteArray? {
    if (pixelsUnchanged && renderFrom == source && !needsTransparency) losslessTurn(context, source, geometry)?.let { return it }
    val name = sourceInfo(context, source, realPath).displayName.lowercase()
    val format = when {
        name.endsWith(".png") -> Bitmap.CompressFormat.PNG
        needsTransparency -> return null
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> Bitmap.CompressFormat.JPEG
        name.endsWith(".webp") -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> return null
    }
    val rendered = renderFull(context, renderFrom, geometry, finish, pixelsUnchanged)
    return java.io.ByteArrayOutputStream().also { rendered.compress(format, JPEG_QUALITY, it) }.toByteArray()
        .also { rendered.recycle() }
}

/**
 * Replaces the photo's file with [bytes]: through its file path when the app can write there,
 * otherwise through Android's media library. Throws [SecurityException] when Android wants the
 * user's OK first (see [mediaStoreUri] and MediaStore.createWriteRequest).
 * [detailsFrom] is the original (kept copy), whose photo details are copied over.
 */
fun writeOverOriginal(context: Context, source: Uri, realPath: String?, bytes: ByteArray, detailsFrom: File?) {
    val file = realPath?.let(::File)
    val wroteFile = file != null && file.canWrite() && runCatching {
        file.outputStream().use { it.write(bytes) }
    }.isSuccess
    val target: Uri = if (wroteFile) Uri.fromFile(file) else {
        val uri = realPath?.let { mediaStoreUri(context, it) } ?: source
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Couldn't write the photo")
        uri
    }
    if (detailsFrom != null) copyExif(context, Uri.fromFile(detailsFrom), target)
    if (realPath != null) MediaScannerConnection.scanFile(context, arrayOf(realPath), null, null)
}

/** The media library's entry for a photo file, for asking permission to change it. */
fun mediaStoreUri(context: Context, realPath: String): Uri? {
    @Suppress("DEPRECATION")
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DATA} = ?",
        arrayOf(realPath),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
    }
    return null
}

/** Sets (or, for null values, removes) photo details in a saved photo. */
private fun setExif(context: Context, target: Uri, tags: Map<String, String?>) {
    runCatching {
        if (target.scheme == "file") {
            ExifInterface(target.path!!).apply {
                tags.forEach { (key, value) -> setAttribute(key, value) }
                saveAttributes()
            }
        } else {
            context.contentResolver.openFileDescriptor(target, "rw")?.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    tags.forEach { (key, value) -> setAttribute(key, value) }
                    saveAttributes()
                }
            }
        }
    }
}

/**
 * Everything after rotate/crop (adjustments, effects): gets the rotated and cropped photo and the
 * size of the upright photo it came from (for masks painted on it).
 */
typealias FinishEdits = suspend (turned: Bitmap, uprightWidth: Int, uprightHeight: Int) -> Bitmap

/** The photo with every edit applied, full size (what Save would write). */
suspend fun renderEdited(context: Context, renderFrom: Uri, geometry: Geometry, finish: FinishEdits): Bitmap =
    renderFull(context, renderFrom, geometry, finish)

private suspend fun renderFull(
    context: Context,
    source: Uri,
    geometry: Geometry,
    finish: FinishEdits,
    pixelsUnchanged: Boolean = false
): Bitmap {
    val full = decodeImage(context, source, maxSafeSide(context, source))
    val (width, height) = full.width to full.height
    // An HDR photo's brightness map (Ultra HDR) has to match the picture exactly: turned and
    // cropped with it when only those changed, and left out when the picture itself changed
    // (an erased object would still glow on HDR screens).
    val hdr = if (pixelsUnchanged && Build.VERSION.SDK_INT >= 34) full.gainmap?.let { transformedGainmap(it, geometry) } else null
    val turned = full.transformed(geometry)
    if (turned !== full) full.recycle()
    val result = finish(turned, width, height)
    if (result !== turned) turned.recycle()
    if (Build.VERSION.SDK_INT >= 34 && (hdr != null || result.gainmap != null)) {
        // Normal photos have no brightness map, so this only runs for HDR ones.
        val target = if (result.isMutable) result else result.copy(Bitmap.Config.ARGB_8888, true).also { result.recycle() }
        target.gainmap = hdr
        return target
    }
    return result
}

/** [gainmap] turned and cropped like the photo by [geometry], with the same settings. */
@androidx.annotation.RequiresApi(34)
private fun transformedGainmap(gainmap: android.graphics.Gainmap, geometry: Geometry): android.graphics.Gainmap {
    val contents = gainmap.gainmapContents.transformed(geometry)
    return android.graphics.Gainmap(contents).apply {
        gainmap.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
        gainmap.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
        gainmap.gamma.let { setGamma(it[0], it[1], it[2]) }
        gainmap.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
        gainmap.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
        displayRatioForFullHdr = gainmap.displayRatioForFullHdr
        minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition
    }
}

/**
 * A JPEG only turned or flipped: the original file's bytes with its orientation setting changed,
 * so nothing is re-compressed (no quality lost, and an HDR photo's brightness map stays right).
 * Null when it isn't a JPEG or can't be done this way.
 */
fun losslessTurn(context: Context, source: Uri, geometry: Geometry): ByteArray? = runCatching {
    if (!geometry.crop.isFull) return null
    val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return null
    // JPEG files start with FF D8.
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    val temp = File.createTempFile("turn", ".jpg", context.cacheDir)
    try {
        temp.writeBytes(bytes)
        val exif = ExifInterface(temp.absolutePath)
        val old = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, turnedOrientation(old, geometry).toString())
        exif.saveAttributes()
        temp.readBytes()
    } finally {
        temp.delete()
    }
}.getOrNull()

/**
 * The EXIF orientation after [geometry]'s flip (first) and quarter turns clockwise are applied to
 * the photo as shown with [orientation]. Each orientation is a flip (or not) then turns.
 */
internal fun turnedOrientation(orientation: Int, geometry: Geometry): Int {
    val (flip, turns) = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> true to 0
        ExifInterface.ORIENTATION_ROTATE_180 -> false to 2
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> true to 2
        ExifInterface.ORIENTATION_TRANSPOSE -> true to 3
        ExifInterface.ORIENTATION_ROTATE_90 -> false to 1
        ExifInterface.ORIENTATION_TRANSVERSE -> true to 1
        ExifInterface.ORIENTATION_ROTATE_270 -> false to 3
        else -> false to 0
    }
    // Flipping after a turn is the turn the other way after a flip.
    val newFlip = flip xor geometry.flipped
    val newTurns = (((if (geometry.flipped) -turns else turns) + geometry.quarterTurns) % 4 + 4) % 4
    return when (newFlip to newTurns) {
        (false to 0) -> ExifInterface.ORIENTATION_NORMAL
        (false to 1) -> ExifInterface.ORIENTATION_ROTATE_90
        (false to 2) -> ExifInterface.ORIENTATION_ROTATE_180
        (false to 3) -> ExifInterface.ORIENTATION_ROTATE_270
        (true to 0) -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        (true to 1) -> ExifInterface.ORIENTATION_TRANSVERSE
        (true to 2) -> ExifInterface.ORIENTATION_FLIP_VERTICAL
        else -> ExifInterface.ORIENTATION_TRANSPOSE
    }
}

private fun copyExif(context: Context, from: Uri, to: Uri) {
    runCatching {
        val original = context.contentResolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
        val target = if (to.scheme == "file") {
            ExifInterface(to.path!!)
        } else {
            context.contentResolver.openFileDescriptor(to, "rw")?.use { return writeExif(original, ExifInterface(it.fileDescriptor)) }
            return
        }
        writeExif(original, target)
    }
}

private fun writeExif(original: ExifInterface, target: ExifInterface) {
    for (tag in exifTagsToKeep) original.getAttribute(tag)?.let { target.setAttribute(tag, it) }
    target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    target.setAttribute(ExifInterface.TAG_SOFTWARE, "Viper Vision")
    target.saveAttributes()
}

/** Quality of edited photos saved as JPEG (high, so smooth skies don't band). */
private const val JPEG_QUALITY = 97
