package mammyframe

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key as keyOf
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.swing.UIManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import kotlinx.coroutines.launch

enum class Screen { HOME, SLIDESHOW, BLACK, ROTATE, REVIEW }

/** How long the menu stays up after clicking the black screen, before it goes black again. */
private const val BLACK_RETURN_MS = 60_000L

/** AI rotate goes back to the frame after this long without being touched. */
private const val ROTATE_IDLE_MS = 10 * 60_000L

/** The mouse pointer is hidden over photos after this long without moving. */
private const val POINTER_HIDE_MS = 3_000L

/** The longest side of the biggest screen, in pixels (photos are read at about this size). */
fun screenMaxSide(): Int = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.maxOf {
        maxOf(it.displayMode.width, it.displayMode.height)
    }
}.getOrDefault(1920).coerceAtLeast(800)

private val blankPointer: PointerIcon by lazy {
    runCatching {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none"))
    }.getOrDefault(PointerIcon.Default)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Drawing waits for each screen refresh (smooth, and no wasted work). MAMMYFRAME_VSYNC=0 turns that off.
    if (System.getenv("MAMMYFRAME_VSYNC") == "0") System.setProperty("skiko.vsync.enabled", "false")
    // The drawing library's own frame-rate counter and slow-frame reports, every 10 s, into the log.
    System.setProperty("skiko.fps.enabled", "true")
    System.setProperty("skiko.fps.periodSeconds", "10")
    System.setProperty("skiko.fps.longFrames.show", "true")
    System.setProperty("skiko.fps.longFrames.millis", "40")
    Log.startup(args)
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { Log.e("look and feel", it) }
    // Opened by Windows at startup: behave like a photo frame straight away.
    val fromStartup = "--startup" in args
    // Automatic test on the build machine: --autotest <folder for results> <folder of test photos>
    val autotest = args.indexOf("--autotest").takeIf { it >= 0 && args.size > it + 2 }
        ?.let { java.io.File(args[it + 1]) to java.io.File(args[it + 2]) }
    application {
        // The window has no Windows title bar and is placed by hand: full screen is simply
        // "cover the whole screen" (switching Windows' full screen on and off proved unreliable).
        val windowState = rememberWindowState(placement = WindowPlacement.Floating, size = DpSize(1280.dp, 820.dp))
        // The screen to use: the one Mammy Frame was on last time, if it's still connected.
        var device by remember { mutableStateOf(ScreenMemory.lastDevice()) }
        var screen by remember { mutableStateOf(Screen.HOME) }
        // If something goes wrong while drawing: write it in the log and start again from the home
        // screen, instead of closing (gives up if it keeps happening).
        var restarts by remember { mutableIntStateOf(0) }
        var recent by remember { mutableStateOf(listOf<Long>()) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Mammy Frame",
            state = windowState,
            undecorated = true,
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) Log.i("Key: ${e.keyOf} on $screen")
                false
            },
        ) {
            // Every window event goes in the log.
            LaunchedEffect(Unit) {
                window.addWindowStateListener { e -> Log.i("Window state: ${e.oldState} -> ${e.newState} (placement ${windowState.placement})") }
                window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent) = Log.i("Window got focus")
                    override fun windowLostFocus(e: java.awt.event.WindowEvent) = Log.i("Window lost focus")
                })
                window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) = Log.i("Window moved to ${window.x},${window.y}")
                    override fun componentResized(e: java.awt.event.ComponentEvent) = Log.i("Window resized to ${window.width}x${window.height}")
                    override fun componentShown(e: java.awt.event.ComponentEvent) = Log.i("Window shown")
                    override fun componentHidden(e: java.awt.event.ComponentEvent) = Log.i("Window hidden")
                })
                window.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowIconified(e: java.awt.event.WindowEvent) = Log.i("Window minimised")
                    override fun windowDeiconified(e: java.awt.event.WindowEvent) = Log.i("Window restored")
                    override fun windowClosing(e: java.awt.event.WindowEvent) = Log.i("Window close button pressed")
                })
            }
            LaunchedEffect(Unit) {
                Log.i("Window open: drawing with ${runCatching { window.renderApi }.getOrNull()}, " +
                    "size ${window.width}x${window.height}, vsync ${System.getProperty("skiko.vsync.enabled") ?: "on"}")
                // Which screen the window is on (and how fast that screen refreshes), whenever it changes.
                var lastScreen: String? = null
                while (true) {
                    val here = runCatching {
                        val m = window.graphicsConfiguration.device.displayMode
                        "${window.graphicsConfiguration.device.iDstring} ${m.width}x${m.height} @ ${m.refreshRate} Hz"
                    }.getOrNull()
                    if (here != lastScreen) {
                        Log.i("Window is on screen $here")
                        lastScreen = here
                        runCatching { ScreenMemory.remember(window.graphicsConfiguration.device.iDstring) }
                    }
                    delay(5_000)
                }
            }
            // Photos, review and the black screen cover the whole screen (above the taskbar too);
            // the menus fill the screen apart from the taskbar.
            val frame = screen == Screen.SLIDESHOW || screen == Screen.BLACK || screen == Screen.REVIEW
            LaunchedEffect(screen, device) {
                val d = device ?: window.graphicsConfiguration.device
                placeWindow(window, d, full = frame)
                window.isAlwaysOnTop = frame
                if (device != d) device = d
                ScreenMemory.remember(d.iDstring)
                if (screen == Screen.SLIDESHOW || screen == Screen.BLACK) {
                    // Brought forward by the schedule even if minimised or behind other windows.
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            CompositionLocalProvider(
                LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
                    WindowExceptionHandler { t ->
                        Log.e("while drawing the screen (${screen})", t)
                        val now = AiRotate.now()
                        recent = (recent + now).filter { now - it < 15_000 }
                        if (recent.size > 3) {
                            Log.i("Too many errors in a row; closing.")
                            exitApplication()
                        } else {
                            screen = Screen.HOME
                            restarts++
                        }
                    }
                }
            ) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    if (!frame) TopBar(
                        screens = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size }.getOrDefault(1),
                        onNextScreen = {
                            val all = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                            val cur = all.indexOfFirst { it.iDstring == (device ?: window.graphicsConfiguration.device).iDstring }
                            device = all[(cur + 1) % all.size]
                            Log.i("Move to next screen: ${device?.iDstring}")
                        },
                        onMinimise = { Log.i("Minimise button"); windowState.isMinimized = true },
                        onClose = { Log.i("Close button"); exitApplication() },
                    )
                    Box(Modifier.weight(1f).fillMaxSize()) {
                key(restarts) {
                    App(
                        screen = screen,
                        setScreen = { Log.i("Screen: $screen -> $it"); screen = it },
                        fromStartup = fromStartup,
                        autotest = autotest,
                        window = window,
                    )
                }
                    }
                }
            }
        }
    }
}

@Composable
private fun App(
    screen: Screen, setScreen: (Screen) -> Unit, fromStartup: Boolean, window: java.awt.Window,
    autotest: Pair<java.io.File, java.io.File>? = null,
) {
    var settings by remember { mutableStateOf(Prefs.load()) }
    var dialog by remember { mutableStateOf(HomeDialog.NONE) }
    var slideshowRun by remember { mutableIntStateOf(0) }
    var lastTouch by remember { mutableLongStateOf(AiRotate.now()) }
    // Not a screen state on purpose: the mouse moves constantly and nothing needs redrawing for it.
    val lastMove = remember { longArrayOf(AiRotate.now()) }
    var lastPhase by remember { mutableStateOf(Schedule.phaseNow(settings)) }
    val aiStatus by AiRotate.status.collectAsState()

    fun enter(phase: Phase) {
        if (screen == Screen.ROTATE || screen == Screen.REVIEW) return // finish what you're doing first
        dialog = HomeDialog.NONE
        when (phase) {
            Phase.ON -> if (screen != Screen.SLIDESHOW) { slideshowRun++; setScreen(Screen.SLIDESHOW) }
            Phase.OFF -> setScreen(Screen.BLACK)
        }
    }

    fun update(s: Settings) {
        if (s.developer != settings.developer) Log.i("Developer settings ${if (s.developer) "on" else "off"}")
        val scheduleChanged = s.scheduleOn != settings.scheduleOn ||
            s.startMinute != settings.startMinute || s.endMinute != settings.endMinute
        settings = s
        Prefs.save(s)
        if (scheduleChanged) lastPhase = Schedule.phaseNow(s)
    }

    // The photos folder is chosen inside Mammy Frame (Windows' own folder window could crash it).
    var picking by remember { mutableStateOf(false) }
    fun chooseFolder() {
        Log.i("Choose folder: opening the folder picker")
        picking = true
    }

    // Opened at startup: straight into the slideshow, or black, as the schedule says.
    LaunchedEffect(Unit) {
        if (fromStartup) Schedule.phaseNow(settings)?.let(::enter)
    }

    // Keep the PC awake while it's a photo frame, and while the AI is checking photos.
    LaunchedEffect(screen, aiStatus.running) {
        KeepAwake.set(
            display = screen == Screen.SLIDESHOW || screen == Screen.BLACK,
            system = aiStatus.running,
        )
    }

    // Watch the clock for the start and end times.
    LaunchedEffect(Unit) {
        while (true) {
            val now = Schedule.phaseNow(settings)
            if (now != null && now != lastPhase) enter(now)
            lastPhase = now
            delay(3_000)
        }
    }

    // Outside the schedule's hours, the menu goes back to black after a minute of not being used.
    LaunchedEffect(screen, dialog, lastTouch, settings, picking) {
        if (screen != Screen.HOME || dialog != HomeDialog.NONE || picking) return@LaunchedEffect
        if (Schedule.phaseNow(settings) != Phase.OFF) return@LaunchedEffect
        val wait = BLACK_RETURN_MS - (AiRotate.now() - lastTouch)
        if (wait > 0) delay(wait)
        if (Schedule.phaseNow(settings) == Phase.OFF) setScreen(Screen.BLACK)
    }

    // Left on AI rotate: go back to the frame (slideshow if it's that time).
    LaunchedEffect(screen, lastTouch) {
        if (screen != Screen.ROTATE && screen != Screen.REVIEW) return@LaunchedEffect
        val wait = ROTATE_IDLE_MS - (AiRotate.now() - lastTouch)
        if (wait > 0) delay(wait)
        setScreen(Screen.HOME)
        when (Schedule.phaseNow(settings)) {
            Phase.ON -> { slideshowRun++; setScreen(Screen.SLIDESHOW) }
            Phase.OFF -> setScreen(Screen.BLACK)
            null -> {}
        }
    }

    // Hide the mouse pointer over photos once it's been still for a moment (checked twice a second).
    var pointerHidden by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        pointerHidden = false
        if (screen != Screen.SLIDESHOW && screen != Screen.BLACK) return@LaunchedEffect
        while (true) {
            val hide = AiRotate.now() - lastMove[0] > POINTER_HIDE_MS
            if (hide != pointerHidden) pointerHidden = hide
            delay(500)
        }
    }

    val scope = rememberCoroutineScope()

    // ---- Automatic test (build machine only): every screen, a real click, screenshots, timings.
    LaunchedEffect(Unit) {
        val (out, photos) = autotest ?: return@LaunchedEffect
        out.mkdirs()
        // Never hang the build: give up after 8 minutes.
        Thread({ Thread.sleep(8 * 60_000L); Log.i("AUTOTEST FAILED: took too long"); kotlin.system.exitProcess(2) }, "autotest-timeout")
            .apply { isDaemon = true }.start()
        var step = 0
        suspend fun shot(name: String) {
            delay(1500)
            step++
            val file = java.io.File(out, "%02d-%s.png".format(step, name))
            val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize))
            javax.imageio.ImageIO.write(img, "png", file)
            Log.i("AUTOTEST screenshot ${file.name} (window ${window.bounds})")
        }
        try {
            Log.i("AUTOTEST starting: results in $out, photos in $photos")
            // Test photos: a third saved sideways on purpose (the AI should turn them back).
            val folder = java.io.File(out, "Family").apply { mkdirs() }
            photos.listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }.forEachIndexed { i, f ->
                val dst = java.io.File(folder, f.name)
                if (i % 3 == 1) javax.imageio.ImageIO.write(Photos.transform(javax.imageio.ImageIO.read(f), false, 270), "jpg", dst)
                else f.copyTo(dst, overwrite = true)
            }
            shot("home")
            // A real mouse click in the middle of the window: the log should show "Press".
            run {
                val r = java.awt.Robot()
                r.mouseMove(window.x + window.width / 2, window.y + window.height - 60)
                r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            }
            delay(800)
            val home = measureDrawing()
            Log.i("AUTOTEST drawing on home: ${"%.0f".format(home.first)} frames/s, slowest ${home.second} ms")
            update(settings.copy(developer = true)); shot("developer-settings")
            picking = true; shot("folder-picker")
            picking = false; update(settings.copy(folder = folder.path)); shot("folder-chosen")
            dialog = HomeDialog.SCHEDULE; shot("schedule"); dialog = HomeDialog.NONE
            dialog = HomeDialog.TRANSITIONS; shot("transitions"); dialog = HomeDialog.NONE
            val notFull = ArrayList<String>()
            suspend fun mustBeFull(what: String) {
                delay(1500)
                if (!coversScreen(window)) { notFull += what; Log.i("AUTOTEST PROBLEM: $what isn't full screen (window ${window.bounds}, screen ${window.graphicsConfiguration.bounds})") }
            }
            slideshowRun++; setScreen(Screen.SLIDESHOW); mustBeFull("slideshow"); delay(1500)
            val show = measureDrawing()
            Log.i("AUTOTEST drawing in slideshow: ${"%.0f".format(show.first)} frames/s, slowest ${show.second} ms")
            shot("slideshow")
            setScreen(Screen.HOME); delay(500); setScreen(Screen.ROTATE); shot("ai-rotate")
            AiRotate.start(folder.path)
            while (AiRotate.status.value.running) delay(500)
            val st = AiRotate.status.value
            val recs = AiRotate.recordsIn(folder.path)
            Log.i("AUTOTEST AI check: finished=${st.lastRunFinished} in ${st.lastRunMs} ms, ${recs.size} photos, " +
                "turned ${recs.count { it.applied != 0 }}, message=${st.message}")
            recs.sortedBy { it.name }.forEach { r ->
                Log.i("AUTOTEST   ${r.name}: AI ${r.fix}° (${(r.certainty * 100).toInt()}%) ${AiRotate.kind(r, st.threshold)}, applied ${r.applied}°, tag now ${Photos.exifOrientation(java.io.File(r.path))}")
            }
            shot("ai-rotate-done")
            setScreen(Screen.REVIEW); mustBeFull("review"); shot("review")
            setScreen(Screen.ROTATE); delay(500)
            setScreen(Screen.BLACK); mustBeFull("black screen"); shot("black")
            // The black screen must really be black right to the edges (no title bar, no taskbar).
            run {
                val sb = window.graphicsConfiguration.bounds
                val cap = java.awt.Robot().createScreenCapture(sb)
                val spots = listOf(cap.width / 2 to 8, 30 to 8, cap.width / 2 to cap.height - 8, cap.width - 30 to cap.height - 8)
                val notBlack = spots.filter { (x, y) -> val c = java.awt.Color(cap.getRGB(x, y)); c.red + c.green + c.blue > 60 }
                if (notBlack.isNotEmpty()) { notFull += "black screen edges $notBlack"; Log.i("AUTOTEST PROBLEM: black screen isn't black at $notBlack") }
                else Log.i("AUTOTEST black screen is black to every edge")
            }
            setScreen(Screen.HOME); delay(1500)
            slideshowRun++; setScreen(Screen.SLIDESHOW); mustBeFull("slideshow (second time)"); shot("slideshow-again")
            setScreen(Screen.HOME); shot("home-again")
            val ok = st.lastRunFinished && recs.isNotEmpty() && st.message == null && notFull.isEmpty()
            Log.i(if (ok) "AUTOTEST PASSED" else "AUTOTEST FAILED: " +
                listOfNotNull(
                    "AI check didn't finish cleanly".takeIf { !(st.lastRunFinished && recs.isNotEmpty() && st.message == null) },
                    "not full screen: $notFull".takeIf { notFull.isNotEmpty() },
                ).joinToString("; "))
            kotlin.system.exitProcess(if (ok) 0 else 1)
        } catch (t: Throwable) {
            Log.e("AUTOTEST FAILED", t)
            runCatching { shot("failure") }
            kotlin.system.exitProcess(1)
        }
    }
    var aiText by remember { mutableStateOf("") }
    LaunchedEffect(aiStatus.revision, aiStatus.running, aiStatus.done, settings.folder) {
        aiText = aiSummary(aiStatus, AiRotate.recordsIn(settings.folder))
    }
    val counts by Photos.counts.collectAsState()
    LaunchedEffect(settings.folder) { Photos.ensureCount(settings.folder) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Rose, secondary = Rose)) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerHoverIcon(if (pointerHidden) blankPointer else PointerIcon.Default)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            when (e.type) {
                                PointerEventType.Press -> {
                                    lastTouch = AiRotate.now(); lastMove[0] = lastTouch
                                    val c = e.changes.firstOrNull()
                                    Log.i("Press ${e.buttons} ${c?.type} at ${c?.position} on $screen")
                                }
                                PointerEventType.Release -> {
                                    val c = e.changes.firstOrNull()
                                    Log.i("Release ${c?.type} at ${c?.position} on $screen")
                                }
                                PointerEventType.Scroll -> Log.i("Scroll on $screen")
                                PointerEventType.Move -> lastMove[0] = AiRotate.now()
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    photoCount = settings.folder?.let { counts[it] },
                    dialog = dialog,
                    onDialog = { Log.i("Home: ${if (it == HomeDialog.NONE) "closed the box" else "opened $it"}"); dialog = it; lastTouch = AiRotate.now() },
                    onChooseFolder = ::chooseFolder,
                    aiText = aiText,
                    onOpenAiRotate = { setScreen(Screen.ROTATE) },
                    onPlay = { slideshowRun++; setScreen(Screen.SLIDESHOW) },
                    onChange = ::update,
                    onCopyLog = {
                        scope.launch {
                            val fps = measureDrawing()
                            val text = Log.report(
                                "Screen: $screen, drawing ${"%.0f".format(fps.first)} frames/s (slowest ${fps.second} ms), " +
                                    "folder: ${settings.folder}, photos: ${settings.folder?.let { counts[it] }}, " +
                                    "memory used ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB, " +
                                    "AI: ${aiStatus.copy(message = aiStatus.message)}"
                            )
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(text), null)
                            Log.i("Diagnostic log copied (${text.length} characters)")
                        }
                    },
                )
                Screen.SLIDESHOW -> key(slideshowRun) {
                    SlideshowScreen(settings) { setScreen(Screen.HOME) }
                }
                Screen.ROTATE -> RotateScreen(settings, onBack = { setScreen(Screen.HOME) }, onReview = { setScreen(Screen.REVIEW) })
                Screen.REVIEW -> ReviewScreen(settings) { setScreen(Screen.ROTATE) }
                Screen.BLACK -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                lastTouch = AiRotate.now()
                                setScreen(Screen.HOME)
                            }
                        }
                )
            }
            if (picking && screen == Screen.HOME) {
                FolderPicker(
                    start = settings.folder,
                    onPick = {
                        Log.i("Choose folder: chose $it")
                        update(settings.copy(folder = it)); picking = false; lastTouch = AiRotate.now()
                    },
                    onCancel = { Log.i("Choose folder: cancelled"); picking = false; lastTouch = AiRotate.now() },
                )
            }
        }
    }
}

/**
 * How smoothly the screen draws: asks for frames for 2 seconds and counts them.
 * Returns (frames per second, slowest frame in ms).
 */
private suspend fun measureDrawing(): Pair<Double, Long> {
    var first = 0L
    var last = 0L
    var frames = 0
    var worst = 0L
    while (true) {
        val t = withFrameNanos { it }
        if (first == 0L) first = t else { worst = maxOf(worst, (t - last) / 1_000_000); frames++ }
        last = t
        if (t - first > 2_000_000_000L) break
    }
    return frames / ((last - first) / 1e9) to worst
}

/** Which screen Mammy Frame was last on (Windows' name for it, e.g. "\\Display1"). */
object ScreenMemory {
    private val file get() = java.io.File(dataDir, "screen.txt")

    fun remember(id: String) = runCatching { if (file.readTextOrNull() != id) file.writeText(id) }

    /** That screen, if it's still connected. */
    fun lastDevice(): java.awt.GraphicsDevice? = runCatching {
        val id = file.readTextOrNull() ?: return null
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.firstOrNull { it.iDstring == id }
            ?.also { Log.i("Opening on the screen used last time: $id") }
    }.getOrNull()

    private fun java.io.File.readTextOrNull() = if (exists()) readText().trim().ifEmpty { null } else null
}

/** True when the window exactly covers its whole screen (real full screen, no title bar or taskbar). */
fun coversScreen(window: java.awt.Window): Boolean =
    runCatching { window.isShowing && window.bounds == window.graphicsConfiguration.bounds }.getOrDefault(false)

/** Puts the window on screen [d]: covering all of it ([full]), or all of it apart from the taskbar. */
fun placeWindow(window: java.awt.Window, d: java.awt.GraphicsDevice, full: Boolean) {
    val gc = d.defaultConfiguration
    val b = java.awt.Rectangle(gc.bounds)
    if (!full) {
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        b.x += i.left; b.y += i.top; b.width -= i.left + i.right; b.height -= i.top + i.bottom
    }
    if (window.bounds != b) {
        Log.i("Window ${if (full) "covers the whole screen" else "fills the screen above the taskbar"}: ${d.iDstring} $b")
        window.bounds = b
    }
}

/** The slim bar at the top of the menus: name, move to the next screen, minimise, close. */
@Composable
private fun TopBar(screens: Int, onNextScreen: () -> Unit, onMinimise: () -> Unit, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().height(36.dp).background(Color(0xFFF7EAE6)),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            "Mammy Frame", fontSize = 14.sp, color = Color(0xFF6B5258),
            modifier = Modifier.padding(start = 14.dp).weight(1f)
        )
        if (screens > 1) BarButton("Move to next screen", onNextScreen)
        BarButton("—", onMinimise)
        BarButton("✕", onClose)
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) { androidx.compose.material3.Text(text, fontSize = 15.sp, color = Color(0xFF6B5258)) }
}
