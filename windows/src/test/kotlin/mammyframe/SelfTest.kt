package mammyframe

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/** Checks run on the build machine: `./gradlew selfTest -PtestArgs="ai <folder> [n]"`, "exif <jpg>", "shots <folder>". */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "ai" -> aiTest(File(args[1]), args.getOrNull(2)?.toInt() ?: 200)
        "exif" -> exifTest(File(args[1]))
        "shots" -> shots(File(args[1]))
        "e2e" -> e2e(File(args[1]), File(args[2]))
        "window" -> { windowTest(File(args[1])); return }
        else -> println("usage: ai <folder> [n] | exif <jpg> | shots <folder>")
    }
    exitProcess(0)
}

/** Every photo turned 0/90/180/270 anticlockwise; the AI should say to turn it back by the same amount. */
private fun aiTest(dir: File, n: Int) {
    val files = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }!!.sortedBy { it.name }
    // An even spread across the folder (it's sorted by kind: "other_..." then "people_...").
    val step = maxOf(1, files.size / n)
    val pick = files.filterIndexed { i, _ -> i % step == 0 }.take(n)
    val model = OrientationModel.open()
    val right = HashMap<String, Int>()
    val count = HashMap<String, Int>()
    val t0 = System.currentTimeMillis()
    for (f in pick) {
        val raw = Photos.read(f, OrientationModel.RESIZE * 2) ?: continue
        val (flip, rot) = Orientation.toFlipRotate(Photos.exifOrientation(f))
        val base = Photos.transform(raw, flip, rot)
        val group = f.name.substringBefore('_', "other")
        for (r in 0 until 4) {
            val turned = Photos.transform(base, false, (360 - 90 * r) % 360)
            val res = model.predict(Photos.scaleTo(turned, OrientationModel.RESIZE, OrientationModel.RESIZE))
            count.merge(group, 1, Int::plus)
            if (res.fixCw == 90 * r) right.merge(group, 1, Int::plus)
        }
    }
    val ms = System.currentTimeMillis() - t0
    for (g in count.keys.sorted()) println("AI $g: ${right[g] ?: 0}/${count[g]} = ${"%.1f".format(100.0 * (right[g] ?: 0) / count[g]!!)}%")
    val all = count.values.sum()
    println("AI all: ${right.values.sum()}/$all = ${"%.1f".format(100.0 * right.values.sum() / all)}%  (${ms / all} ms per photo incl. 4 looks)")
}

/** Turning a JPEG must only change its rotation tag: the picture data stays exactly the same. */
private fun exifTest(src: File) {
    val tmp = File.createTempFile("mammy", ".jpg").also { src.copyTo(it, overwrite = true) }
    val before = ImageIO.read(tmp)
    val sizeBefore = tmp.length()
    for (tag in listOf(6, 3, 8, 1)) {
        AiRotate.writeJpegOrientation(tmp, tag)
        val after = ImageIO.read(tmp) // ImageIO ignores the tag, so this is the stored picture itself
        var same = before.width == after.width && before.height == after.height
        if (same) loop@ for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before.getRGB(x, y) != after.getRGB(x, y)) { same = false; break@loop }
        }
        val shown = Photos.decode(tmp.path, 4000)!!
        println("tag $tag: read back ${Photos.exifOrientation(tmp)}, picture identical=$same, " +
            "shown ${shown.width}x${shown.height} (stored ${before.width}x${before.height}), size ${sizeBefore}->${tmp.length()}")
    }
    tmp.delete()
}

private fun shot(out: File, w: Int, h: Int, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(w, h, Density(1f)) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Rose, secondary = Rose)) { content() }
    }
    var t = 0L
    repeat(10) { scene.render(t); t += 100_000_000L }
    val img = scene.render(t)
    out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("wrote $out")
}

private fun shots(dir: File) {
    dir.mkdirs()
    val s = Settings(folder = "C:\\Users\\Mammy\\Pictures\\Family", scheduleOn = true)
    shot(File(dir, "home.png"), 1280, 800) {
        HomeScreen(s, 1234, HomeDialog.NONE, {}, {}, "", {}, {}, {})
    }
    shot(File(dir, "home-dev.png"), 1280, 800) {
        HomeScreen(s.copy(developer = true), 1234, HomeDialog.NONE, {}, {}, "359 sorted\n12 to review", {}, {}, {})
    }
    shot(File(dir, "home-dev-small.png"), 900, 620) {
        HomeScreen(s.copy(developer = true), 1234, HomeDialog.NONE, {}, {}, "359 sorted\n12 to review", {}, {}, {})
    }
    shot(File(dir, "schedule.png"), 1280, 800) {
        HomeScreen(s.copy(developer = true), 1234, HomeDialog.SCHEDULE, {}, {}, "", {}, {}, {})
    }
    shot(File(dir, "transitions.png"), 1280, 800) {
        HomeScreen(s.copy(developer = true), 1234, HomeDialog.TRANSITIONS, {}, {}, "", {}, {}, {})
    }
    shot(File(dir, "airotate.png"), 1280, 800) {
        RotateScreen(s, onBack = {}, onReview = {})
    }
}

/** The whole thing on a real folder: some photos turned sideways on purpose, then AI rotate, then the screens. */
private fun e2e(src: File, out: File) {
    out.mkdirs()
    val folder = File(System.getProperty("java.io.tmpdir"), "mammy-e2e").apply { deleteRecursively(); mkdirs() }
    val photos = src.listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name }.take(16)
    val turnedOnPurpose = HashMap<String, Int>()
    photos.forEachIndexed { i, f ->
        val dst = File(folder, f.name)
        if (i % 3 == 1) {
            // Saved sideways (pixels turned 90° anticlockwise, no rotation tag): AI should turn it 90° clockwise.
            val img = Photos.transform(ImageIO.read(f), false, 270)
            ImageIO.write(img, "jpg", dst); turnedOnPurpose[dst.path] = 90
        } else f.copyTo(dst)
    }
    println("folder ${folder.path}: ${photos.size} photos, ${turnedOnPurpose.size} saved sideways on purpose")
    kotlinx.coroutines.runBlocking {
        AiRotate.load()
        AiRotate.start(folder.path)
        while (AiRotate.status.value.running) kotlinx.coroutines.delay(200)
        val s = AiRotate.status.value
        println("check finished=${s.lastRunFinished} in ${s.lastRunMs} ms, ${s.lastRunAnalyzed} photos; message=${s.message}")
        val recs = AiRotate.recordsIn(folder.path)
        for (r in recs.sortedBy { it.name }) {
            val k = AiRotate.kind(r, s.threshold)
            val expected = turnedOnPurpose[r.path] ?: 0
            println("  ${r.name}: AI says turn ${r.fix}° (${(r.certainty * 100).toInt()}% sure) -> $k, applied ${r.applied}°, " +
                "tag now ${Photos.exifOrientation(File(r.path))}  [expected ${expected}°] ${if (r.fix == expected) "OK" else "WRONG"}")
        }
    }
    fun live(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) {
            MaterialTheme(colorScheme = lightColorScheme(primary = Rose, secondary = Rose)) { content() }
        }
        var t = 0L
        repeat(40) { scene.render(t); t += 100_000_000L; Thread.sleep(150) }
        File(out, name).writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name")
    }
    val settings = Settings(folder = folder.path, developer = true, transitionMs = 300)
    live("e2e-rotate.png", 1280, 800) { RotateScreen(settings, onBack = {}, onReview = {}) }
    live("e2e-review.png", 1280, 800) { ReviewScreen(settings, onBack = {}) }
    live("e2e-slideshow.png", 1280, 800) { SlideshowScreen(settings, onExit = {}) }
}

/** The real app window, driven by simulated clicks, with a screenshot after each step. */
private fun windowTest(out: File) {
    out.mkdirs()
    Thread { Class.forName("mammyframe.MainKt").getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>()) }.apply { isDaemon = true }.start()
    val robot = java.awt.Robot()
    fun snap(name: String) {
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        ImageIO.write(robot.createScreenCapture(java.awt.Rectangle(size)), "png", File(out, name))
        println("snap $name")
    }
    fun click(x: Int, y: Int) {
        robot.mouseMove(x, y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
    }
    Thread.sleep(6000); snap("w1-home.png")
    repeat(10) { click(640, 270); Thread.sleep(150) }
    Thread.sleep(1500); snap("w2-dev.png")
    val (fx, fy) = (System.getProperty("folderX") ?: "500").toInt() to (System.getProperty("folderY") ?: "500").toInt()
    click(fx, fy)
    Thread.sleep(2500); snap("w3-picker.png")
    // More clicks from -Dclicks="x,y;x,y", with a picture after each.
    (System.getProperty("clicks") ?: "").split(";").filter { it.contains(",") }.forEachIndexed { i, c ->
        val (x, y) = c.split(",").map { it.trim().toInt() }
        click(x, y); Thread.sleep(2500); snap("w${4 + i}-click.png")
    }
    println("----- log -----")
    println(Log.text().takeLast(3000))
    exitProcess(0)
}
