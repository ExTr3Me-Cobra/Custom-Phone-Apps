package com.vipervision.editor

import kotlin.math.abs
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb

/**
 * Samsung Camera's thumbnail (bottom left) always opens Samsung Gallery. While Samsung Camera is
 * on screen, this puts an invisible button exactly over that thumbnail which opens the newest
 * photo or video from the Camera folder in Viper Vision's viewer instead.
 *
 * It's an accessibility service because that's the only way an app may see which app is on
 * screen and where its buttons are, and draw over it. It only looks at Samsung Camera, only
 * reads the thumbnail and shutter buttons' positions, and does nothing on the lock screen
 * (Samsung keeps handling the thumbnail there, so nothing opens without unlocking).
 */
/** Whether the accessibility service is running, for the diagnostic report. */
object ServiceStatus {
    fun describe(context: android.content.Context): String {
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains("${context.packageName}/${CameraThumbnailService::class.java.name}")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        val running = CameraThumbnailService.connectedAt
        val last = CameraThumbnailService.lastEventAt
        return "Accessibility service: " + (if (enabled) "on in Settings" else "OFF in Settings") + ", " +
            (if (running > 0) "running since ${fmt.format(java.util.Date(running))}" else "NOT running") +
            (if (last > 0) ", last event ${fmt.format(java.util.Date(last))}" else ", no events yet")
    }
}

class CameraThumbnailService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var button: View? = null
    private var shownAt: Rect? = null
    private var cameraOnScreen = false
    private val refresh = Runnable { update() }

    /** Viper Vision's screenshot toolbar (see ScreenshotToolbar), shown after each screenshot. */
    private val screenshots = ScreenshotToolbar(this)

    /**
     * Samsung's own screenshot toolbar showed up while Viper Vision's is on: the gallery's setup
     * asks again to turn it off (MainActivity.showViperSetup, key "vv_samsung_toolbar_off").
     */
    private fun noticedSamsungToolbar() {
        // Smart capture also shows a flash and a small preview after every screenshot; only its
        // toolbar counts.
        val toolbar = runCatching {
            windows.any { window -> window.root?.let(ScreenshotToolbar::isSamsungToolbar) == true }
        }.getOrDefault(false)
        if (!toolbar) return
        screenshots.samsungToolbarSeenAt = System.currentTimeMillis()
        val prefs = getSharedPreferences("Prefs", MODE_PRIVATE)
        if (!prefs.getBoolean(ScreenshotToolbar.PREF_ENABLED, true) || !prefs.getBoolean(SAMSUNG_TOOLBAR_OFF, false)) return
        ViperLog.i("SCREENSHOT", "Samsung's screenshot toolbar is still on; the setup will ask again")
        prefs.edit().putBoolean(SAMSUNG_TOOLBAR_OFF, false).remove(ScreenshotToolbar.CHECK_RESULT).apply()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedAt = System.currentTimeMillis()
        // For the gallery's setup: the service is really running (not just switched on).
        getSharedPreferences("Prefs", MODE_PRIVATE).edit().putInt(RUNNING_PID, android.os.Process.myPid()).apply()
        ViperLog.i("ACCESSIBILITY", "Viper Vision's accessibility service is running")
        screenshots.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ViperLog.i("ACCESSIBILITY", "Viper Vision's accessibility service stopped (turned off, or Android stopped it)")
        connectedAt = 0L
        getSharedPreferences("Prefs", MODE_PRIVATE).edit().putInt(RUNNING_PID, 0).apply()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventAt = System.currentTimeMillis()
        // Windows opening or closing (e.g. Back from Viper Vision's viewer to the camera): look
        // again at what's in front.
        // Other phones' screenshot preview: covered the moment it shows.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString()?.contains("systemui") == true)
        ) {
            screenshots.onWindowsChanged()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!refreshPending) schedule(60)
            return
        }
        val pkg = event.packageName?.toString() ?: return
        handoffEvent(pkg, event)
        if (pkg == SAMSUNG_CAPTURE_PACKAGE) {
            noticedSamsungToolbar()
        }
        if (cameraOnScreen) lastCameraAt = System.currentTimeMillis()
        // Viper Vision's viewer is on screen: the camera bridge's cover can go.
        if (coverUntilViewer && pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !event.className?.toString().orEmpty().startsWith("android.")
        ) {
            handler.postDelayed({ liftCover() }, 300)
        }
        // Samsung's own preview opened from the camera (its thumbnail): log its layout, to fit
        // Viper Vision's bars over it later.
        if (pkg == SamsungHandoff.GALLERY && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            SamsungHandoff.active == null && System.currentTimeMillis() - lastCameraAt < 15_000
        ) {
            handler.postDelayed({ logPreviewLayout() }, 800)
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The system UI (notifications, keyboard) and our own overlays (plain views) don't
                // count as leaving the camera; Viper Vision's own screens do.
                if (pkg == "com.android.systemui") return
                if (pkg == packageName && event.className?.toString().orEmpty().startsWith("android.")) return
                // Not on the camera's own settings screens, only the viewfinder.
                val screenName = event.className?.toString().orEmpty()
                cameraOnScreen = isCamera(pkg) && "setting" !in screenName.lowercase()
                if (isCamera(pkg)) {
                    if (pkg != cameraPackage) ViperLog.i("CAMERA", "Camera app: $pkg")
                    cameraPackage = pkg
                    onCameraSettings = "setting" in screenName.lowercase()
                }
                loadingRetries = 0
                // Straight away at the thumbnail's last known spot (it doesn't move between
                // visits), so a quick tap after opening the camera already lands on it.
                if (cameraOnScreen && button == null && !isLocked()) lastBounds()?.let(::show)
                schedule(0)
            }
            // The viewfinder changes constantly; look at most every 60 ms instead of waiting for
            // it to settle (which kept putting the button off while the camera started).
            // The shutter: a new photo is on its way (200 MP ones take seconds to be saved).
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (isCamera(pkg)) {
                val id = event.source?.viewIdResourceName.orEmpty().lowercase()
                val label = (event.contentDescription ?: event.text.joinToString(" ")).toString().lowercase()
                val isShutter = SHUTTER_WORDS.any { it in id || it in label }
                // Every button the camera reports, so the log shows what its shutter is called.
                ViperLog.i("CAMERA", "Camera button: id=${id.substringAfter('/').ifEmpty { "-" }} label=\"$label\" class=${event.className}" +
                        if (isShutter) " (shutter)" else "")
                if (isShutter) lastShutterAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (isCamera(pkg) && !refreshPending) {
                // Back at the camera after the button was tapped: put it back at once.
                if (button == null && !onCameraSettings && !isLocked() &&
                    rootInActiveWindow?.packageName?.toString()?.let(::isCamera) == true
                ) {
                    cameraOnScreen = true
                    lastBounds()?.let(::show)
                }
                schedule(60)
            }
        }
    }

    private var refreshPending = false

    /** The camera app in use (Samsung's, or another phone's). */
    private var cameraPackage = CAMERA_PACKAGE

    /**
     * Camera apps: the phone's own (whatever answers "open the camera"), and well-known ones,
     * so the shortcut works on other phones too, not only with Samsung's camera.
     */
    private val cameraPackages: Set<String> by lazy {
        // Only the phone's own camera apps: apps like Snapchat also offer to take photos for other
        // apps, and the invisible thumbnail button ended up over them.
        val found = runCatching { packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0) }
            .getOrDefault(emptyList())
            .filter { (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            .map { it.activityInfo.packageName }
        (KNOWN_CAMERAS + found).filter { it != packageName }.toSet().also {
            ViperLog.i("CAMERA", "Camera apps: ${it.joinToString()}")
        }
    }

    private fun isCamera(pkg: String) = pkg in cameraPackages
    private var lastCameraAt = 0L

    /** Samsung's preview's buttons and texts with where they are, for the log. */
    private fun logPreviewLayout() {
        val root = windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == SamsungHandoff.GALLERY } } ?: return
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || lines.size >= 60) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
                val r = Rect().also(node::getBoundsInScreen)
                lines += "[$text|$desc|$id @${r.left},${r.top}-${r.right},${r.bottom}${if (node.isClickable) "|tap" else ""}]"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        ViperLog.i("CAMERA", "Samsung's preview (${root.className}): ${lines.joinToString(" ")}")
    }
    private var onCameraSettings = false

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(refresh)
        refreshPending = true
        handler.postDelayed(refresh, delayMs)
    }

    private fun isLocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Where the button was last put, kept so it can be shown before the camera's layout is readable. */
    private fun lastBounds(): Rect? = getSharedPreferences("Prefs", MODE_PRIVATE)
        .getString(boundsKey(), null)?.let(Rect::unflattenFromString)

    /** Each camera app's thumbnail is somewhere else; Samsung's keeps its original key. */
    private fun boundsKey() = if (cameraPackage == CAMERA_PACKAGE) LAST_BOUNDS else "${LAST_BOUNDS}_$cameraPackage"

    private var loadingRetries = 0

    /** Puts the button over the thumbnail, moves it, or removes it. */
    private fun update() {
        refreshPending = false
        // Settings → "Camera thumbnail opens Viper Vision" off: Samsung's preview as usual.
        if (!getSharedPreferences("Prefs", MODE_PRIVATE).getBoolean(SHORTCUT_PREF, true)) {
            hide()
            return
        }
        // Follows the window in front: the camera's viewfinder (also when coming back to it, e.g.
        // with Back from Viper Vision's viewer, which doesn't always announce the camera again),
        // and not Viper Vision's viewer over it.
        val front = rootInActiveWindow?.packageName?.toString()
        if (front != null) cameraOnScreen = isCamera(front) && !onCameraSettings
        if (front != null && isCamera(front)) cameraPackage = front
        if (!cameraOnScreen || isLocked()) {
            hide()
            return
        }
        val bounds = findThumbnail()
        if (bounds == null) {
            // The camera's layout isn't readable yet: keep the last known spot and look again soon.
            if (loadingRetries++ < 40) {
                if (button == null) lastBounds()?.let(::show)
                schedule(50)
            } else hide()
            return
        }
        loadingRetries = 0
        if (bounds == shownAt) return
        show(bounds)
        getSharedPreferences("Prefs", MODE_PRIVATE).edit().putString(boundsKey(), bounds.flattenToString()).apply()
    }

    /**
     * Where the thumbnail is: the camera's own thumbnail button if it describes itself. Otherwise,
     * for Samsung's camera, the spot left of the shutter where Samsung always puts it; for other
     * camera apps, the round button on the shutter's row that isn't the switch-camera one, or
     * else the spot right of the shutter where most other cameras put it.
     */
    private fun findThumbnail(): Rect? {
        val root = windows.firstOrNull { it.root?.packageName?.toString()?.let(::isCamera) == true }?.root
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString()?.let(::isCamera) == true }
            ?: return null
        val samsung = root.packageName == CAMERA_PACKAGE
        // Camera apps keep their layout portrait however the phone is held (only the icons
        // turn), so measure against the camera's own window, not the phone's current orientation
        // (which swaps width and height when the phone is turned).
        val area = Rect().also(root::getBoundsInScreen).takeIf { it.width() > 0 && it.height() > 0 } ?: run {
            val metrics = resources.displayMetrics
            Rect(0, 0, minOf(metrics.widthPixels, metrics.heightPixels), maxOf(metrics.widthPixels, metrics.heightPixels))
        }
        var thumbnail: Rect? = null
        var shutter: Rect? = null
        val buttons = mutableListOf<Pair<Rect, String>>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || !node.isVisibleToUser) return
            val id = node.viewIdResourceName.orEmpty().lowercase().substringAfter(":id/")
            val label = (node.contentDescription ?: node.text)?.toString().orEmpty().lowercase()
            val bounds = Rect().also(node::getBoundsInScreen)
            val lower = bounds.centerY() > area.centerY()
            val small = bounds.width() in 1 until area.width() / 3 && bounds.height() in 1 until area.width() / 3
            val side = if (samsung) bounds.centerX() < area.centerX() else true
            if (thumbnail == null && lower && small && side &&
                (THUMBNAIL_WORDS.any { it in id } || THUMBNAIL_WORDS.any { it in label } || label == "gallery") &&
                SWITCH_WORDS.none { it in id || it in label }
            ) {
                thumbnail = bounds
            }
            if (shutter == null && lower && (SHUTTER_WORDS.any { it in id } || "take picture" in label || "shutter" in label || "capture" in label)) {
                shutter = bounds
            }
            if (lower && small && (node.isClickable || node.isFocusable)) buttons += bounds to "$id $label"
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        thumbnail?.let { return it }
        if (samsung) {
            // Samsung Camera (One UI, portrait): the round thumbnail is ~13% of the screen width
            // across, centred 16% in from the left, on the shutter's row (83% down the screen).
            val size = (area.width() * 0.15f).toInt()
            val centerX = area.left + (area.width() * 0.16f).toInt()
            val centerY = shutter?.centerY() ?: (area.top + area.height() * 0.83f).toInt()
            return Rect(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2)
        }
        // Another camera app that doesn't name its thumbnail: the button beside the shutter (same
        // row) that isn't switch-camera, right side first (where most put it).
        shutter?.let { sh ->
            val row = buttons.filter { (b, what) ->
                abs(b.centerY() - sh.centerY()) < sh.height() / 2 && abs(b.centerX() - sh.centerX()) > sh.width() / 2 &&
                    SWITCH_WORDS.none { it in what } && SHUTTER_WORDS.none { it in what }
            }
            (row.filter { it.first.centerX() > sh.centerX() }.maxByOrNull { it.first.width() }
                ?: row.maxByOrNull { it.first.width() })?.let {
                ViperLog.i("CAMERA", "Thumbnail taken to be the button beside the shutter: ${it.second.trim().ifEmpty { "unnamed" }}")
                return it.first
            }
        }
        // Right of the shutter, three quarters across, on its row (87% down without one).
        val size = (area.width() * 0.14f).toInt()
        val centerX = area.left + (area.width() * 0.75f).toInt()
        val centerY = shutter?.centerY() ?: (area.top + area.height() * 0.87f).toInt()
        return Rect(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2)
    }

    private fun show(bounds: Rect) {
        // Just after opening the viewer, the camera is still in front for a moment: not yet.
        if (System.currentTimeMillis() < hiddenUntil) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = button
        if (view == null) {
            val created = android.widget.FrameLayout(this).apply {
                contentDescription = "Open in Viper Vision"
                // Shown over the thumbnail while waiting for a just-taken photo to be saved
                // (a 200 MP one can take 10+ seconds), so it's clear the tap worked.
                addView(android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(appAccentColor(context).toArgb())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xAA000000.toInt())
                    }
                    val pad = (bounds.width() * 0.18f).toInt()
                    setPadding(pad, pad, pad, pad)
                    visibility = View.GONE
                    tag = SPINNER
                }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    // One opening at a time: while it waits for the photo to be saved, more taps
                    // land here and do nothing (instead of opening a viewer each, or reaching
                    // Samsung's thumbnail underneath).
                    if (openPending) {
                        ViperLog.i("CAMERA", "Thumbnail tapped again while opening; ignored")
                        return@setOnClickListener
                    }
                    openPending = true
                    ViperLog.i("CAMERA", "Thumbnail tapped")
                    findViewWithTag<View>(SPINNER)?.visibility = View.VISIBLE
                    openNewest()
                }
            }
            runCatching { windowManager.addView(created, params) }.onSuccess { button = created }
        } else {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        button?.findViewWithTag<View>(SPINNER)?.visibility = if (openPending) View.VISIBLE else View.GONE
        shownAt = bounds
    }

    private fun hide() {
        button?.let { view -> runCatching { getSystemService(WindowManager::class.java)?.removeView(view) } }
        button = null
        shownAt = null
    }

    /** Set from a tap on the thumbnail until the viewer is opened. */
    private var openPending = false

    /** The button stays away until then (just after opening the viewer). */
    private var hiddenUntil = 0L

    /** When the camera's shutter was last tapped (0 if not since the service started). */
    private var lastShutterAt = 0L

    /**
     * Opens the newest photo or video in DCIM/Camera in Viper Vision's viewer. If a photo was just
     * taken and isn't saved yet (the thumbnail shows it before the file exists), waits for it
     * instead of opening the one before.
     */
    private fun openNewest() {
        // 200 MP photos have taken over 45 s to be saved.
        val started = System.currentTimeMillis()
        val deadline = started + 45_000
        var lastProbe = ""
        var bridge = false
        fun attempt() {
            val newest = newestCameraFile()
            probeCameraFolder(started).takeIf { it != lastProbe }?.let {
                lastProbe = it
                ViperLog.i("CAMERA", "After ${(System.currentTimeMillis() - started) / 100 / 10.0} s: $it")
            }
            val waitingForShutter = lastShutterAt > 0 && (newest == null || newest.addedMs < lastShutterAt - 2_000)
            val ready = newest != null && !newest.pending && !waitingForShutter
            if (ready) {
                if (bridge) {
                    // Samsung's preview goes, Viper Vision's viewer comes.
                    ViperLog.i("CAMERA", "Photo saved after ${(System.currentTimeMillis() - started) / 1000} s; leaving Samsung's preview")
                    // The cover stays up through it all (Samsung's preview closing, the camera,
                    // the viewer loading) and goes once Viper Vision's viewer is on screen.
                    bridgeActive = false
                    coverUntilViewer = true
                    if (frontPackage() == SamsungHandoff.GALLERY) performGlobalAction(GLOBAL_ACTION_BACK)
                    val closingSince = System.currentTimeMillis()
                    fun openWhenClosed() {
                        if (frontPackage() == SamsungHandoff.GALLERY && System.currentTimeMillis() - closingSince < 1_200) {
                            handler.postDelayed({ openWhenClosed() }, 100)
                            return
                        }
                        open(newest?.path)
                        handler.postDelayed({ liftCover() }, 2_500)
                    }
                    handler.postDelayed({ openWhenClosed() }, 150)
                } else {
                    open(newest?.path)
                }
                return
            }
            if (!bridge && cameraPackage != CAMERA_PACKAGE) {
                // Another camera app: no Samsung preview to open in between; just wait for the
                // photo to be saved (the spinner shows meanwhile).
            } else if (!bridge) {
                // Not saved within half a second (quick ones are, and open straight in Viper
                // Vision): Samsung's own preview has it already; open that, with Viper Vision's
                // bars over Samsung's, until the photo is saved.
                if (System.currentTimeMillis() - started >= 500) {
                    bridge = true
                    startBridge()
                }
            } else if (!bridgeActive) {
                return // cancelled: nothing opens later
            } else {
                val front = frontPackage()
                if (front == SamsungHandoff.GALLERY) sawPreview = true
                else if (sawPreview && front != null) {
                    // Back closed Samsung's preview under the cover.
                    ViperLog.i("CAMERA", "Left Samsung's preview before the photo was saved")
                    endBridge()
                    return
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                ViperLog.i("CAMERA", "Still not saved after 45 s; leaving Samsung's preview as it is")
                endBridge()
                return
            }
            handler.postDelayed({ attempt() }, 250)
        }
        attempt()
    }

    private fun frontPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Set while Samsung's preview is shown with Viper Vision's bars over it. */
    private var bridgeActive = false
    private var sawPreview = false
    private val bridgeBars = mutableListOf<View>()

    /** Opens Samsung's own preview (its thumbnail, under the invisible button). */
    private fun startBridge() {
        ViperLog.i("CAMERA", "Photo not saved yet; opening Samsung's preview under a cover (it saves sooner)")
        bridgeActive = true
        sawPreview = false
        hiddenUntil = Long.MAX_VALUE
        val at = shownAt
        hide()
        // The cover first, so nothing of Samsung's preview shows; it lets touches through for a
        // moment, so a tap on Samsung's thumbnail (if needed) reaches it.
        showBridgeCover()
        // A moment for the cover to be drawn before Samsung's preview starts opening.
        handler.postDelayed({
            if (!bridgeActive) return@postDelayed
            val camera = windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == CAMERA_PACKAGE } }
            val thumbnail = camera?.let { findById(it, "quick_view_button_layout") }
            if (thumbnail?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true && at != null) {
                val path = android.graphics.Path().apply { moveTo(at.exactCenterX(), at.exactCenterY()) }
                dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                        .build(), null, null
                )
            }
        }, 100)
        handler.postDelayed({ makeCoverTouchable() }, 600)
    }

    /** Set from the photo being ready until Viper Vision's viewer shows (the cover stays till then). */
    private var coverUntilViewer = false

    private fun liftCover() {
        if (!coverUntilViewer) return
        coverUntilViewer = false
        removeBridgeBars()
    }

    private fun makeCoverTouchable() {
        val cover = bridgeBars.firstOrNull() ?: return
        val params = cover.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { getSystemService(WindowManager::class.java)?.updateViewLayout(cover, params) }
    }

    private fun findById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 30) return
            if (node.viewIdResourceName?.endsWith("/$id") == true) {
                found = node
                return
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return found
    }

    /**
     * A black cover over the whole screen, with a spinner, while Samsung's preview runs under it
     * (opening it seems to make Samsung save the photo much sooner: 2 s against 13–45+ s).
     * Its ‹ cancels.
     */
    private fun showBridgeCover() {
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val accent = appAccentColor(this).toArgb()
        val density = resources.displayMetrics.density
        val cover = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
                }, android.widget.LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt()))
                addView(android.widget.TextView(context).apply {
                    text = "Opening in Viper Vision…"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(0, (16 * density).toInt(), 0, 0)
                })
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            addView(android.widget.TextView(context).apply {
                text = "‹"
                textSize = 40f
                setTextColor(android.graphics.Color.WHITE)
                contentDescription = "Back"
                val pad = (16 * density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                setOnClickListener {
                    ViperLog.i("CAMERA", "Cancelled from the cover")
                    val inPreview = frontPackage() == SamsungHandoff.GALLERY
                    endBridge()
                    if (inPreview) performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = systemBarHeight("status_bar_height")
            })
        }
        // Exactly the screen (allowed past its edges, the window's middle wasn't the screen's).
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(it)
            }.let { it.widthPixels to it.heightPixels }
        }
        val params = WindowManager.LayoutParams(
            screen.first, screen.second,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { windowManager.addView(cover, params) }.onSuccess { bridgeBars += cover }
    }

    private fun systemBarHeight(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun removeBridgeBars() {
        val windowManager = getSystemService(WindowManager::class.java)
        bridgeBars.forEach { runCatching { windowManager?.removeView(it) } }
        bridgeBars.clear()
    }

    /** Back to normal: no bars, no pending opening, the thumbnail button allowed again. */
    private fun endBridge() {
        removeBridgeBars()
        bridgeActive = false
        openPending = false
        hiddenUntil = System.currentTimeMillis() + 1_000
    }

    private fun open(path: String?) {
        openPending = false
        bridgeActive = false
        cameraOnScreen = false
        hiddenUntil = System.currentTimeMillis() + 2_000
        hide()
        ViperLog.i("CAMERA", "Opening ${path?.substringAfterLast('/') ?: "nothing (no photos)"}")
        if (path == null) {
            Toast.makeText(this, "No photos or videos in the Camera folder yet", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent().apply {
            setClassName(packageName, VIEWER_ACTIVITY)
            putExtra("path", path)
            putExtra("show_all", false)
            // The viewer closes Viper Vision when you leave it with Back or Home.
            putExtra("vv_from_camera_shortcut", true)
            // Its own window, so closing it never closes a Gallery you already had open.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            ViperLog.error("CAMERA", "openNewest failed", it)
            Toast.makeText(this, "Couldn't open Viper Vision: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private class CameraFile(val path: String, val addedMs: Long, val pending: Boolean)

    /** The newest Camera folder entries since shortly before [sinceMs], with size and state, for the log. */
    private fun probeCameraFolder(sinceMs: Long): String = runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val data = MediaStore.MediaColumns.DATA
        val where = "$data LIKE '%/DCIM/Camera/%' AND ${MediaStore.MediaColumns.DATE_ADDED} >= ${(sinceMs - 20_000) / 1000}"
        val projection = arrayOf(data, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.WIDTH)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contentResolver.query(collection, projection, android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else contentResolver.query(collection, projection, where, null, null)
        val rows = cursor?.use {
            buildList {
                while (it.moveToNext()) {
                    val path = it.getString(0).orEmpty()
                    val onDisk = java.io.File(path).length()
                    add("${path.substringAfterLast('/')} (${it.getLong(1) / 1024} KB listed, ${onDisk / 1024} KB on disk, " +
                            "${if (it.getInt(2) == 1) "unfinished" else "finished"}, width ${it.getInt(3)})")
                }
            }
        }.orEmpty()
        // Files that are there but not (yet) in the photo list.
        val listed = rows.map { it.substringBefore(" (") }.toSet()
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera")
        val unlisted = dir.listFiles().orEmpty()
            .filter { it.lastModified() >= sinceMs - 20_000 && it.name !in listed }
            .map { "${it.name} (${it.length() / 1024} KB on disk, not listed yet)" }
        (rows + unlisted).joinToString("; ").ifEmpty { "nothing new" }
    }.getOrElse { "couldn't look (${it.message})" }

    /** The newest photo or video in DCIM/Camera, also one Samsung is still writing (pending). */
    private fun newestCameraFile(): CameraFile? {
        val collection = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val data = MediaStore.MediaColumns.DATA
        val where = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
                "$data LIKE '%/DCIM/Camera/%'"
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, data, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.IS_PENDING)
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        return runCatching {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                contentResolver.query(collection, projection, android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }, null)
            } else {
                contentResolver.query(collection, projection, where, null, order)
            }
            cursor?.use {
                if (it.moveToFirst()) CameraFile(it.getString(1), it.getLong(2) * 1000, it.getInt(3) == 1) else null
            }
        }.getOrNull()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        schedule(300)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        screenshots.stop()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(handoffRefresh)
        hide()
        hideReturnButton()
        super.onDestroy()
    }

    // ── Samsung editor handoff (see SamsungHandoff) ─────────────────────────────────────────

    private val handoffRefresh = Runnable { handoffUpdate() }
    private var returnButton: View? = null
    private var lastScreenDump = ""

    private fun handoffEvent(pkg: String, event: AccessibilityEvent) {
        val session = SamsungHandoff.active
        if (session == null) {
            hideReturnButton()
            return
        }
        // A hand-off left another way (home screen, or long forgotten) ends here, so it never
        // drives Samsung's apps later when they're used on their own.
        val ageSec = System.currentTimeMillis() / 1000 - session.startedAtSec
        if (ageSec > HANDOFF_MAX_AGE_SEC) return abandonHandoff(session, "it's over ${HANDOFF_MAX_AGE_SEC / 60} minutes old")
        when {
            pkg in SamsungHandoff.PACKAGES -> {
                handler.removeCallbacks(handoffRefresh)
                handler.postDelayed(handoffRefresh, if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 300 else 400)
            }
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == homePackage() ->
                abandonHandoff(session, "the home screen was opened")
            // Anywhere else (Viper Vision itself included) the Back button isn't wanted.
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui" ->
                hideReturnButton()
        }
    }

    /** Ends a hand-off that won't come back: no more taps in Samsung's apps, the temporary picture deleted. */
    private fun abandonHandoff(session: SamsungHandoff.Session, why: String) {
        if (SamsungHandoff.active !== session) return
        ViperLog.i("SAMSUNG", "Hand-off ended without coming back ($why)")
        SamsungHandoff.active = null
        hideReturnButton()
        Thread { SamsungHandoff.cleanUp(applicationContext, session) }.start()
    }

    /** The phone's home screen app. */
    private fun homePackage(): String? = runCatching {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun samsungRoot(): AccessibilityNodeInfo? =
        windows.firstNotNullOfOrNull { window -> window.root?.takeIf { it.packageName in SamsungHandoff.PACKAGES } }
            ?: rootInActiveWindow?.takeIf { it.packageName in SamsungHandoff.PACKAGES }

    /** In Samsung's editor: show the Back button, and tap Galaxy AI then the chosen tool once. */
    private fun handoffUpdate() {
        val session = SamsungHandoff.active ?: return hideReturnButton()
        val root = samsungRoot() ?: return
        logScreen(root)
        // The button appears only on Photo assist's last screen, exactly over Samsung's Save copy
        // (it does that job, then comes back). Anywhere else it stays hidden.
        val saveArea = findNode(root, SAVE_LABELS, exact = true)?.let(::tappableBounds)
        if (saveArea != null) showReturnButton(saveArea) else hideReturnButton()
        // Backed all the way out of Samsung's editor to Gallery's viewer of the temporary picture
        // (it only shows that again after the tool was open): Back means "back to Viper Vision".
        if (session.toolOpened && !session.returnRequested && root.packageName == GALLERY_PACKAGE &&
            findNode(root, GALAXY_AI_LABELS, exact = false) != null
        ) {
            handler.postDelayed({
                val again = samsungRoot()
                if (SamsungHandoff.active === session && !session.returnRequested && again?.packageName == GALLERY_PACKAGE) {
                    ViperLog.i("SAMSUNG", "Backed out of Samsung's editor; going back to Viper Vision")
                    session.returnRequested = true
                    hideReturnButton()
                    bringViperBack()
                }
            }, 700)
            return
        }
        if (session.toolOpened || session.returnRequested || session.tapAttempts >= MAX_TAP_ATTEMPTS) return
        session.tapAttempts++
        val tool = findNode(root, session.tool.samsungLabels, exact = true)
        if (tool != null) {
            ViperLog.i("SAMSUNG", "Tapping the tool: ${describe(tool)} (tapped: ${click(tool)})")
            // Once only: the label stays on screen afterwards, and tapping again does nothing useful.
            session.toolOpened = true
            return
        }
        if (!session.galaxyAiTapped) {
            val galaxyAi = findNode(root, GALAXY_AI_LABELS, exact = false)
            if (galaxyAi != null) {
                ViperLog.i("SAMSUNG", "Tapping Galaxy AI: ${describe(galaxyAi)}")
                session.galaxyAiTapped = click(galaxyAi)
                return
            }
            // In Gallery's viewer without a Galaxy AI button: open Samsung's editor first.
            if (!session.editTapped) {
                val edit = findNode(root, EDIT_LABELS, exact = true)
                if (edit != null) {
                    ViperLog.i("SAMSUNG", "Tapping Edit: ${describe(edit)}")
                    session.editTapped = click(edit)
                }
            }
        }
    }

    /** Writes what's on Samsung's screen to the diagnostic log (once per screen), for tuning. */
    private fun logScreen(root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || lines.size >= 80) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || (id.isNotEmpty() && node.isClickable)) {
                lines += "[${text}|${desc}|${id}${if (node.isClickable) "|tap" else ""}]"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        val dump = lines.joinToString(" ")
        if (dump != lastScreenDump) {
            lastScreenDump = dump
            ViperLog.i("SAMSUNG", "Screen (${root.packageName}): $dump")
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, labels: List<String>, exact: Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun matches(value: CharSequence?): Boolean {
            val v = value?.toString()?.trim()?.lowercase() ?: return false
            return labels.any { label -> if (exact) v == label.lowercase() else label.lowercase() in v }
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 30) return
            if (node.isVisibleToUser && (matches(node.text) || matches(node.contentDescription))) {
                found = node
                return
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return found
    }

    /** Taps [node], or the nearest tappable thing around it. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        repeat(6) {
            val current = target ?: return false
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = current.parent
        }
        return false
    }

    private fun describe(node: AccessibilityNodeInfo) =
        "${node.text ?: ""}|${node.contentDescription ?: ""}|${node.viewIdResourceName ?: ""}"

    /** Where [node] can be tapped: its own bounds, or those of the tappable thing around it. */
    private fun tappableBounds(node: AccessibilityNodeInfo): Rect {
        var target: AccessibilityNodeInfo? = node
        repeat(6) {
            val current = target ?: return Rect().also(node::getBoundsInScreen)
            if (current.isClickable) return Rect().also(current::getBoundsInScreen)
            target = current.parent
        }
        return Rect().also(node::getBoundsInScreen)
    }

    private var returnButtonArea: Rect? = null
    private var returnButtonShown = false

    /**
     * The Back to Viper Vision button, in the app's colour (Customise colours). Over [cover]
     * exactly when given (Samsung's Save copy button), otherwise centred near the top.
     */
    private fun showReturnButton(cover: Rect?) {
        if (returnButtonShown && cover == returnButtonArea) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val density = resources.displayMetrics.density
        val accent = appAccentColor(this)
        val button = (returnButton as? android.widget.TextView) ?: android.widget.TextView(this).apply {
            text = "← Back to Viper Vision"
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            elevation = 8 * density
            setOnClickListener { returnToViper() }
        }
        button.setTextColor(accent.contentOn().toArgb())
        button.textSize = 15f
        if (cover != null) {
            // Shrink the text if Samsung's button is narrower than the label.
            button.setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        } else {
            button.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            button.textSize = 15f
        }
        button.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = cover?.height()?.div(2f) ?: (50 * density)
            setColor(accent.toArgb())
        }
        val params = WindowManager.LayoutParams(
            cover?.width() ?: WindowManager.LayoutParams.WRAP_CONTENT,
            cover?.height() ?: WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (cover != null) {
                gravity = Gravity.TOP or Gravity.START
                x = cover.left
                y = cover.top
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Below the status bar, clear of Samsung's top buttons.
                y = (90 * density).toInt()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            if (returnButton == null) windowManager.addView(button, params) else windowManager.updateViewLayout(button, params)
        }.onSuccess {
            returnButton = button
            returnButtonShown = true
            returnButtonArea = cover
            if (cover != null) ViperLog.i("SAMSUNG", "Back button placed over Save copy at $cover")
        }.onFailure { ViperLog.error("SAMSUNG", "couldn't show the Back button", it) }
    }

    private fun hideReturnButton() {
        returnButton?.let { view -> runCatching { getSystemService(WindowManager::class.java)?.removeView(view) } }
        returnButton = null
        returnButtonShown = false
        returnButtonArea = null
    }

    /**
     * The Back button: taps Samsung's Done (finishes the AI edit) and Save, then brings Viper
     * Vision's editor back, which takes in what Samsung saved.
     */
    private fun returnToViper() {
        val session = SamsungHandoff.active ?: return hideReturnButton()
        val root = samsungRoot()
        // Photo assist's last screen offers "Save copy" (and "Keep editing"); the main editor has Save.
        val save = root?.let { findNode(it, SAVE_LABELS, exact = true) }
        if (save == null) {
            ViperLog.i("SAMSUNG", "Back button tapped, but there's no Save copy / Save on screen yet")
            android.widget.Toast.makeText(
                this,
                "Finish the edit in Samsung first (tap Erase or Generate), then tap Back to Viper Vision",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        session.returnRequested = true
        hideReturnButton()
        ViperLog.i("SAMSUNG", "Back button tapped; tapping ${describe(save)} (tapped: ${click(save)})")
        // Some versions then ask how to save; prefer a copy (Viper deletes it after taking it in).
        handler.postDelayed({
            samsungRoot()?.let { findNode(it, listOf("Save as copy", "Save copy"), exact = true) }?.let {
                ViperLog.i("SAMSUNG", "Then tapping ${describe(it)} (tapped: ${click(it)})")
            }
            handler.postDelayed({ bringViperBack() }, 1500)
        }, 1200)
    }

    private fun bringViperBack() {
        ViperLog.i("SAMSUNG", "Bringing Viper Vision's editor back")
        runCatching {
            startActivity(
                Intent().setClassName(packageName, EditorActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { ViperLog.error("SAMSUNG", "couldn't bring the editor back", it) }
    }

    companion object {
        @Volatile var connectedAt = 0L
        @Volatile var lastEventAt = 0L
        /** Read by the gallery's setup (MainActivity.showViperSetup). */
        const val RUNNING_PID = "vv_a11y_running_pid"
        const val MAX_TAP_ATTEMPTS = 40
        /** A hand-off still unfinished after this long has been left: it ends. */
        const val HANDOFF_MAX_AGE_SEC = 30 * 60L
        const val GALLERY_PACKAGE = "com.sec.android.gallery3d"
        val GALAXY_AI_LABELS = listOf("Galaxy AI", "Photo Assist", "Generative edit", "AI edit")
        val EDIT_LABELS = listOf("Edit")
        val SAVE_LABELS = listOf("Save copy", "Save as copy", "Save", "Done")
        const val CAMERA_PACKAGE = "com.sec.android.app.camera"
        const val SHORTCUT_PREF = "vv_camera_shortcut"
        const val SPINNER = "vv_camera_spinner"
        val SHUTTER_WORDS = listOf("shutter", "take picture", "take photo", "capture", "record", "take_picture")
        val THUMBNAIL_WORDS = listOf("thumbnail", "thumb", "preview", "review", "gallery", "last photo", "recent photo", "view photo", "photo viewer", "peek")
        val SWITCH_WORDS = listOf("switch", "flip", "toggle_camera", "front", "rear", "selfie", "lens")
        /** Camera apps of other phone makers, besides whatever the phone reports as its camera. */
        val KNOWN_CAMERAS = listOf(
            "com.sec.android.app.camera", "com.motorola.camera3", "com.motorola.camera2", "com.motorola.camera",
            "com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2", "org.codeaurora.snapcam",
            "com.oneplus.camera", "com.oplus.camera", "com.coloros.camera", "com.huawei.camera", "com.hihonor.camera",
            "com.sonyericsson.android.camera", "com.sonymobile.camera", "com.lge.camera", "com.asus.camera",
            "com.tcl.camera", "com.nothing.camera", "com.mediatek.camera", "com.transsion.camera", "com.vivo.camera",
            "com.xiaomi.camera", "com.hmdglobal.camera2"
        )
        const val LAST_BOUNDS = "vv_camera_thumbnail_bounds"
        const val SAMSUNG_CAPTURE_PACKAGE = ScreenshotToolbar.SAMSUNG_CAPTURE_PACKAGE
        const val SAMSUNG_TOOLBAR_OFF = "vv_samsung_toolbar_off"
        const val VIEWER_ACTIVITY = "org.fossify.gallery.activities.ViewPagerActivity"
    }
}
