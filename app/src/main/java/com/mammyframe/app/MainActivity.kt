package com.mammyframe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

enum class Screen { HOME, SLIDESHOW, BLACK, ROTATE }

/** How long the menu stays up after tapping the black screen, before it goes black again. */
private const val BLACK_RETURN_MS = 60_000L

/** AI rotate goes back to the frame after this long without being touched. */
private const val ROTATE_IDLE_MS = 10 * 60_000L

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.HOME)
    private var slideshowRun by mutableIntStateOf(0)
    private var settings by mutableStateOf(Settings())
    private var photoCount by mutableStateOf<Int?>(null)
    private var dialog by mutableStateOf(HomeDialog.NONE)
    private var resumed by mutableStateOf(false)
    private var resumeTick by mutableIntStateOf(0)
    private var lastTouch by mutableLongStateOf(SystemClock.elapsedRealtime())

    /** The schedule phase we last acted on, so each start/end time is acted on once. */
    private var lastPhase: Phase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A photo frame: keep the screen on, and come on over the lock screen at the start time.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        settings = Prefs.load(this)
        lastPhase = Schedule.phaseNow(settings)
        if (savedInstanceState == null) handlePhaseIntent(intent)

        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePhaseIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumeTick++
        lastTouch = SystemClock.elapsedRealtime()
        Schedule.arm(this, settings)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouch = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    /** Opened by the schedule (at a start or end time, or after a restart). */
    private fun handlePhaseIntent(intent: Intent?) {
        val name = intent?.getStringExtra(Schedule.EXTRA_PHASE) ?: return
        intent.removeExtra(Schedule.EXTRA_PHASE)
        val phase = runCatching { Phase.valueOf(name) }.getOrNull() ?: return
        // Only if the schedule still agrees (it may have been changed since).
        val now = Schedule.phaseNow(settings) ?: return
        if (now == phase) enter(phase)
        lastPhase = now
    }

    private fun enter(phase: Phase) {
        if (screen == Screen.ROTATE) return // finish what you're doing first; see the idle timer below
        dialog = HomeDialog.NONE
        when (phase) {
            Phase.ON -> if (screen != Screen.SLIDESHOW) {
                slideshowRun++
                screen = Screen.SLIDESHOW
            }
            Phase.OFF -> screen = Screen.BLACK
        }
    }

    private fun update(s: Settings) {
        val scheduleChanged = s.scheduleOn != settings.scheduleOn ||
            s.startMinute != settings.startMinute || s.endMinute != settings.endMinute
        settings = s
        Prefs.save(this, s)
        if (scheduleChanged) {
            lastPhase = Schedule.phaseNow(s)
            Schedule.arm(this, s)
        }
    }

    private fun chooseFolder(uri: Uri) {
        // Read and write: AI rotate saves turned photos back into the folder. Older folders keep
        // their permission too, so their AI rotations can still be undone.
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, rw) }
            .onFailure { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val name = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        photoCount = null
        update(settings.copy(folderUri = uri.toString(), folderName = name ?: "Photos"))
    }

    private fun applyWindow(s: Screen) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (s == Screen.HOME || s == Screen.ROTATE) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        // As dim as the screen goes while it's black at night.
        window.attributes = window.attributes.apply {
            screenBrightness = if (s == Screen.BLACK) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    @Composable
    private fun App() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) chooseFolder(uri)
        }

        LaunchedEffect(screen) { applyWindow(screen) }

        // Watch the clock for the start and end times while the app is open.
        LaunchedEffect(Unit) {
            while (true) {
                val now = Schedule.phaseNow(settings)
                if (now != null && now != lastPhase) enter(now)
                lastPhase = now
                delay(3_000)
            }
        }

        // Outside the schedule's hours, the menu goes back to black after a minute of not being used.
        LaunchedEffect(screen, dialog, resumed, lastTouch, settings) {
            if (screen != Screen.HOME || dialog != HomeDialog.NONE || !resumed) return@LaunchedEffect
            if (Schedule.phaseNow(settings) != Phase.OFF) return@LaunchedEffect
            val wait = BLACK_RETURN_MS - (SystemClock.elapsedRealtime() - lastTouch)
            if (wait > 0) delay(wait)
            if (Schedule.phaseNow(settings) == Phase.OFF) screen = Screen.BLACK
        }

        // Left on the AI rotate screen: go back to the frame (slideshow if it's that time).
        LaunchedEffect(screen, lastTouch, resumed) {
            if (screen != Screen.ROTATE || !resumed) return@LaunchedEffect
            val wait = ROTATE_IDLE_MS - (SystemClock.elapsedRealtime() - lastTouch)
            if (wait > 0) delay(wait)
            screen = Screen.HOME
            when (Schedule.phaseNow(settings)) {
                Phase.ON -> { slideshowRun++; screen = Screen.SLIDESHOW }
                Phase.OFF -> screen = Screen.BLACK
                null -> {}
            }
        }

        val aiStatus by AiRotate.status.collectAsState()
        var aiText by remember { mutableStateOf("") }
        LaunchedEffect(aiStatus.revision, aiStatus.running, aiStatus.done, settings.folderUri) {
            aiText = aiSummary(aiStatus, AiRotate.recordsIn(this@MainActivity, settings.folderUri))
        }

        LaunchedEffect(settings.folderUri, resumeTick) {
            photoCount = if (settings.folderUri == null) null else Photos.list(this@MainActivity, settings.folderUri).size
        }

        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        MaterialTheme(colorScheme = lightColorScheme(primary = Rose, secondary = Rose)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    photoCount = photoCount,
                    dialog = dialog,
                    onDialog = { dialog = it; lastTouch = SystemClock.elapsedRealtime() },
                    onChooseFolder = { runCatching { picker.launch(settings.folderUri?.let(Uri::parse)) } },
                    aiText = aiText,
                    onOpenAiRotate = { screen = Screen.ROTATE },
                    onPlay = { slideshowRun++; screen = Screen.SLIDESHOW },
                    onChange = ::update,
                    resumeTick = resumeTick,
                )
                Screen.SLIDESHOW -> androidx.compose.runtime.key(slideshowRun) {
                    SlideshowScreen(settings) { screen = Screen.HOME }
                }
                Screen.ROTATE -> RotateScreen(settings) { screen = Screen.HOME }
                Screen.BLACK -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                lastTouch = SystemClock.elapsedRealtime()
                                screen = Screen.HOME
                            }
                        }
                )
            }
        }
    }
}
