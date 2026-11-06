package mammyframe

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.imageio.ImageIO

/** One picture in the folder, with what's needed to notice if it changes. */
data class PhotoFile(val path: String, val name: String, val ext: String, val modified: Long, val size: Long)

object Photos {
    val extensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    /** Every picture in the folder and its subfolders (hidden folders and files are skipped). */
    suspend fun files(folder: String?): List<PhotoFile> = withContext(Dispatchers.IO) {
        if (folder == null) return@withContext emptyList()
        val out = ArrayList<PhotoFile>()
        runCatching {
            Files.walkFileTree(File(folder).toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val n = dir.fileName?.toString().orEmpty()
                    return if (dir != File(folder).toPath() && (n.startsWith(".") || n.startsWith("$")))
                        FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val n = file.fileName.toString()
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (ext in extensions && !n.startsWith(".")) {
                        out.add(PhotoFile(file.toString(), n, ext, attrs.lastModifiedTime().toMillis(), attrs.size()))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
        }
        out
    }

    /** A fresh look through the folder; also saved for next time. */
    suspend fun list(folder: String?): List<String> {
        val paths = files(folder).map { it.path }
        if (folder != null) remember(folder, paths)
        return paths
    }

    // ------------------------------------------------ the saved list of photos
    // Looking through tens of thousands of photos takes a while, so the last list is kept and
    // used straight away; a fresh look only happens when the saved one is getting old.

    private const val FRESH_MS = 60 * 60 * 1000L
    private val saved = HashMap<String, Pair<Long, List<String>>>()
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts

    private fun listFile(folder: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(folder.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dataDir, "photolists").apply { mkdirs() }, "$h.txt")
    }

    @Synchronized
    fun remember(folder: String, paths: List<String>) {
        saved[folder] = System.currentTimeMillis() to paths
        _counts.value = _counts.value + (folder to paths.size)
        runCatching {
            val f = listFile(folder)
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { w -> paths.forEach { w.write(it); w.write("\n") } }
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    private fun savedList(folder: String): Pair<Long, List<String>>? {
        saved[folder]?.let { return it }
        val f = listFile(folder)
        if (!f.exists()) return null
        val paths = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return null
        val entry = f.lastModified() to paths
        saved[folder] = entry
        _counts.value = _counts.value + (folder to paths.size)
        return entry
    }

    /** The photos to show: the saved list straight away (refresh = true if over an hour old), else a fresh look. */
    suspend fun listQuick(folder: String?): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
        if (folder == null) return@withContext emptyList<String>() to false
        val s = savedList(folder)
        if (s == null || s.second.isEmpty()) list(folder) to false
        else s.second to (System.currentTimeMillis() - s.first > FRESH_MS)
    }

    suspend fun ensureCount(folder: String?) = withContext(Dispatchers.IO) {
        if (folder != null && savedList(folder) == null) list(folder)
    }

    // ------------------------------------------------ reading pictures

    /** The camera's rotation tag (1..8), or 0 if there isn't one. */
    fun exifOrientation(file: File): Int = runCatching {
        ImageMetadataReader.readMetadata(file).getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.takeIf { it.containsTag(ExifIFD0Directory.TAG_ORIENTATION) }
            ?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 0
    }.getOrDefault(0)

    /**
     * Reads a picture, only as finely as needed: [maxSide] is roughly the size wanted, so big
     * camera photos are read at a fraction of their size (much faster, much less memory).
     */
    fun read(file: File, maxSide: Int): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@runCatching null
            try {
                reader.input = input
                val longest = maxOf(reader.getWidth(0), reader.getHeight(0))
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                val param = reader.defaultReadParam
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** A picture the right way up for its camera tag, then turned [turnCw] more degrees clockwise. */
    fun decode(path: String, maxSide: Int, turnCw: Int = 0): BufferedImage? {
        val file = File(path)
        val img = read(file, maxSide) ?: return null
        val (flip, rot) = Orientation.toFlipRotate(exifOrientation(file))
        return transform(img, flip, (rot + turnCw) % 360)
    }

    /** Mirror (left-right) first if [flip], then turn [degreesCw] clockwise. */
    fun transform(img: BufferedImage, flip: Boolean, degreesCw: Int): BufferedImage {
        val d = ((degreesCw % 360) + 360) % 360
        if (!flip && d == 0) return img
        val w = img.width
        val h = img.height
        val swap = d == 90 || d == 270
        val out = BufferedImage(if (swap) h else w, if (swap) w else h,
            if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val t = AffineTransform()
        when (d) {
            90 -> { t.translate(h.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            180 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            270 -> { t.translate(0.0, w.toDouble()); t.rotate(-Math.PI / 2) }
        }
        if (flip) { t.translate(w.toDouble(), 0.0); t.scale(-1.0, 1.0) }
        val g = out.createGraphics()
        g.drawImage(img, t, null)
        g.dispose()
        return out
    }

    /** Good quality shrink to exactly [w] x [h] (halving step by step, then one last smooth step). */
    fun scaleTo(img: BufferedImage, w: Int, h: Int): BufferedImage {
        var cur = img
        while (cur.width / 2 >= w && cur.height / 2 >= h) cur = draw(cur, cur.width / 2, cur.height / 2)
        return draw(cur, w, h)
    }

    private fun draw(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }
}

/** EXIF rotation tags as (mirror first?, then turn clockwise), and back. */
object Orientation {
    fun toFlipRotate(tag: Int): Pair<Boolean, Int> = when (tag) {
        2 -> true to 0
        3 -> false to 180
        4 -> true to 180
        5 -> true to 270
        6 -> false to 90
        7 -> true to 90
        8 -> false to 270
        else -> false to 0
    }

    fun fromFlipRotate(flip: Boolean, degreesCw: Int): Int = when ((((degreesCw % 360) + 360) % 360) to flip) {
        0 to false -> 1
        90 to false -> 6
        180 to false -> 3
        270 to false -> 8
        0 to true -> 2
        90 to true -> 7
        180 to true -> 4
        else -> 5
    }

    /** The tag for a photo with tag [tag] turned [degreesCw] more. */
    fun turned(tag: Int, degreesCw: Int): Int {
        val (flip, rot) = toFlipRotate(tag)
        return fromFlipRotate(flip, rot + degreesCw)
    }
}

/**
 * Shuffled order with memory, so "back" goes to the photo shown before. Every photo is shown
 * once before any repeats; then it reshuffles (never repeating the last photo straight away).
 */
class Playlist(private val all: List<String>) {
    private val history = ArrayList<String>()
    private val bag = ArrayList<String>()
    var position = -1
        private set

    val isEmpty get() = all.isEmpty()
    val current: String? get() = history.getOrNull(position)
    val canGoBack get() = position > 0

    private fun draw(): String {
        if (bag.isEmpty()) {
            bag.addAll(all.shuffled())
            val last = history.lastOrNull()
            if (bag.size > 1 && bag.last() == last) bag.add(0, bag.removeAt(bag.size - 1))
        }
        return bag.removeAt(bag.size - 1)
    }

    fun peekNext(): String? {
        if (all.isEmpty()) return null
        if (position + 1 >= history.size) history.add(draw())
        return history[position + 1]
    }

    fun peekPrevious(): String? = history.getOrNull(position - 1)

    fun forward(): String? {
        peekNext() ?: return null
        position++
        if (position > 300) {
            history.subList(0, 100).clear()
            position -= 100
        }
        return current
    }

    fun back(): String? {
        if (position > 0) position--
        return current
    }
}
