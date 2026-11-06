package mammyframe

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
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
    Log.startup(args)
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { Log.e("look and feel", it) }
    // Opened by Windows at startup: behave like a photo frame straight away.
    val fromStartup = "--startup" in args
    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 820.dp))
        var screen by remember { mutableStateOf(Screen.HOME) }
        // If something goes wrong while drawing: write it in the log and start again from the home
        // screen, instead of closing (gives up if it keeps happening).
        var restarts by remember { mutableIntStateOf(0) }
        var recent by remember { mutableStateOf(listOf<Long>()) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Mammy Frame",
            state = windowState,
            icon = painterResource("icon.png"),
        ) {
            LaunchedEffect(Unit) {
                Log.i("Window open: drawing with ${runCatching { window.renderApi }.getOrNull()}, " +
                    "size ${window.width}x${window.height}")
            }
            // Photos and the black screen fill the whole screen; the menus are a normal window.
            LaunchedEffect(screen) {
                val frame = screen == Screen.SLIDESHOW || screen == Screen.BLACK || screen == Screen.REVIEW
                windowState.placement = if (frame) WindowPlacement.Fullscreen else WindowPlacement.Maximized
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
                key(restarts) {
                    App(
                        screen = screen,
                        setScreen = { Log.i("Screen: $screen -> $it"); screen = it },
                        fromStartup = fromStartup,
                        window = window,
                    )
                }
            }
        }
    }
}

@Composable
private fun App(screen: Screen, setScreen: (Screen) -> Unit, fromStartup: Boolean, window: java.awt.Window) {
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
        val scheduleChanged = s.scheduleOn != settings.scheduleOn ||
            s.startMinute != settings.startMinute || s.endMinute != settings.endMinute
        settings = s
        Prefs.save(s)
        if (scheduleChanged) lastPhase = Schedule.phaseNow(s)
    }

    fun chooseFolder() {
        Log.i("Choose folder: opening the folder window")
        // Opened just after the click has finished, on top of (and attached to) the Mammy Frame window.
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser(settings.folder).apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Choose the photos folder"
                    isAcceptAllFileFilterUsed = false
                }
                val result = chooser.showOpenDialog(window)
                Log.i("Choose folder: result=$result, chosen=${chooser.selectedFile}")
                if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
                    update(settings.copy(folder = chooser.selectedFile.absolutePath))
                }
            } catch (t: Throwable) {
                Log.e("Choose folder", t)
            }
            lastTouch = AiRotate.now()
        }
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
    LaunchedEffect(screen, dialog, lastTouch, settings) {
        if (screen != Screen.HOME || dialog != HomeDialog.NONE) return@LaunchedEffect
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
                                PointerEventType.Press -> { lastTouch = AiRotate.now(); lastMove[0] = lastTouch }
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
                    onDialog = { dialog = it; lastTouch = AiRotate.now() },
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
