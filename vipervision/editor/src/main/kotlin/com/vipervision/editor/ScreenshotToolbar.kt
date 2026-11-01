package com.vipervision.editor

import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import java.io.File

/**
 * Viper Vision's screenshot toolbar, in place of Samsung's (turned off in the phone's Settings →
 * Advanced features → Screenshots). Samsung still takes the screenshot; when a new one lands in
 * a Screenshots folder this shows it full screen with:
 *
 * - Share (shares it, then deletes it once an app to share to was picked), Save (keeps it), and
 *   Share & Save (shares and keeps it);
 * - Crop and Draw: simple versions that work right there (see ScreenshotEditViews); the three
 *   above then use the edited screenshot (Save writes it over the original).
 *
 * It covers the whole screen and stays until one of the three is picked, or the red trash can
 * (deletes the screenshot without asking). Runs inside
 * [CameraThumbnailService]; Settings → "Screenshot toolbar" turns it off.
 */
class ScreenshotToolbar(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val resolver get() = service.contentResolver
    private var card: View? = null
    private var lastShownId = -1L
    private val check = Runnable { checkForScreenshot() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // The setup's check starts the moment the screenshot appears, not once it's saved.
            if (service.getSharedPreferences("Prefs", Context.MODE_PRIVATE).getBoolean(CHECK_PENDING, false)) {
                startSetupCheck()
                return
            }
            // Samsung writes the file in steps; look once it has settled.
            handler.removeCallbacks(check)
            handler.postDelayed(check, 500)
        }
    }

    fun start() {
        runCatching {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        }.onFailure { ViperLog.error("SCREENSHOT", "couldn't watch for screenshots", it) }
    }

    fun stop() {
        runCatching { resolver.unregisterContentObserver(observer) }
        handler.removeCallbacksAndMessages(null)
        removeCover()
        hide()
    }

    // On by default on every phone: on Samsung it replaces Samsung's toolbar (turned off in the
    // setup); elsewhere it only covers the phone's own toolbar with an invisible button.
    private fun enabled() =
        service.getSharedPreferences("Prefs", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

    private val samsungPhone = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** The newest screenshot, if it was just taken and not shown yet. */
    private fun checkForScreenshot() {
        if (service.getSharedPreferences("Prefs", Context.MODE_PRIVATE).getBoolean(CHECK_PENDING, false)) return
        if (!enabled()) return
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val shot = runCatching { newestScreenshot() }.getOrNull() ?: return
        if (shot.id == lastShownId) return
        lastShownId = shot.id
        ViperLog.i("SCREENSHOT", "New screenshot: ${shot.path ?: shot.uri}")
        if (samsungPhone) show(shot) else coverPhoneToolbar(shot)
    }

    // ---- Other phones: their own screenshot toolbar can't be turned off ----

    private var cover: View? = null
    private var coverAt: Rect? = null
    private var coverShot: Shot? = null

    /**
     * Puts an invisible button over the phone's own screenshot preview and its buttons (edit,
     * share, crop, delete…), for as long as they're on screen; a tap anywhere on it opens the
     * screenshot in Viper Vision's screenshot editor instead.
     */
    private fun coverPhoneToolbar(shot: Shot) {
        removeCover()
        coverShot = shot
        val started = System.currentTimeMillis()
        var missed = 0
        var everFound = false
        val poll = object : Runnable {
            override fun run() {
                if (coverShot !== shot) return
                val age = System.currentTimeMillis() - started
                val found = runCatching { phoneToolbarBounds() }.getOrNull()
                when {
                    found != null -> {
                        if (!everFound) ViperLog.i("SCREENSHOT", "Phone's screenshot toolbar at $found: covering it")
                        everFound = true
                        missed = 0
                        placeCover(found)
                    }
                    everFound -> if (++missed >= 3) {
                        // It went (timed out, swiped away or used): so does the button.
                        removeCover(); return
                    }
                    age > 1_500 && cover == null -> {
                        // Its layout can't be read: cover the corner where phones show it.
                        ViperLog.i("SCREENSHOT", "Phone's screenshot toolbar not readable: covering the bottom-left corner")
                        placeCover(fallbackBounds())
                    }
                }
                // Phones show it for about 5-8 s.
                if (age > 12_000) { removeCover(); return }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(poll)
    }

    /** Where the phone's screenshot preview and toolbar are (all of it together), or null. */
    private fun phoneToolbarBounds(): Rect? {
        val screenH = service.resources.displayMetrics.heightPixels
        var union: Rect? = null
        var named = 0
        for (window in service.windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == service.packageName) continue
            val parts = mutableListOf<Rect>()
            var windowNamed = 0
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                if (depth > 30 || !node.isVisibleToUser) return
                val id = node.viewIdResourceName.orEmpty().lowercase().substringAfter(":id/")
                val text = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase().orEmpty()
                val b = Rect().also(node::getBoundsInScreen)
                val isShotPart = "screenshot" in id || "screenshot" in text || "screen_shot" in id
                val isAction = PHONE_TOOLBAR_LABELS.any { text == it || id == it || id.endsWith("_$it") || id.startsWith("${it}_") }
                if ((isShotPart || isAction) && b.width() > 0 && b.height() > 0 && b.height() < screenH / 2) {
                    parts += b
                    if (isShotPart) windowNamed += 2 else windowNamed++
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            // A screenshot window: says "screenshot" somewhere, or has at least two of its buttons.
            if (windowNamed >= 2 && (pkg.contains("systemui") || pkg.contains("screenshot") || pkg.contains("capture") || windowNamed >= 3)) {
                for (b in parts) union = union?.apply { union(b) } ?: Rect(b)
                named += windowNamed
            }
        }
        return union?.takeIf { named >= 2 }?.apply { inset(-dp(10), -dp(10)) }
    }

    /** The bottom-left corner, where Android and most phones put the screenshot preview. */
    private fun fallbackBounds(): Rect {
        val m = service.resources.displayMetrics
        return Rect(0, (m.heightPixels * 0.55f).toInt(), (m.widthPixels * 0.55f).toInt(), m.heightPixels - systemBar("navigation_bar_height"))
    }

    private fun placeCover(at: Rect) {
        if (at == coverAt && cover != null) return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            at.width(), at.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = at.left
            y = at.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val existing = cover
        if (existing != null) {
            runCatching { wm.updateViewLayout(existing, params) }
        } else {
            val view = View(service).apply {
                contentDescription = "Edit screenshot in Viper Vision"
                setOnClickListener {
                    val shot = coverShot
                    removeCover()
                    if (shot != null) {
                        ViperLog.i("SCREENSHOT", "Phone's screenshot toolbar tapped: opening Viper Vision's editor")
                        show(shot)
                    }
                }
            }
            runCatching { wm.addView(view, params); cover = view }
                .onFailure { ViperLog.error("SCREENSHOT", "couldn't cover the phone's toolbar", it) }
        }
        coverAt = Rect(at)
    }

    private fun removeCover() {
        cover?.let { view -> runCatching { service.getSystemService(WindowManager::class.java)?.removeView(view) } }
        cover = null
        coverAt = null
        coverShot = null
    }

    private var lastCheckedId = -1L

    /** Starts the setup's check for a screenshot just taken, while it's still being saved. */
    private fun startSetupCheck() {
        val id = runCatching { newestScreenshotIncludingUnsaved() }.getOrNull() ?: return
        if (id == lastCheckedId) return
        lastCheckedId = id
        // Remembered so the setup deletes its test screenshots when Continue is tapped.
        val prefs = service.getSharedPreferences("Prefs", Context.MODE_PRIVATE)
        val shots = prefs.getString(CHECK_SHOTS, "").orEmpty().split(',').filter { it.isNotBlank() } + id.toString()
        prefs.edit().putString(CHECK_SHOTS, shots.distinct().joinToString(",")).apply()
        checkSamsungToolbar()
    }

    /** The newest screenshot's id, also while Samsung is still writing it; null if none just now. */
    private fun newestScreenshotIncludingUnsaved(): Long? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(collection, projection, android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%Screenshots%"))
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(collection, projection, "${MediaStore.Images.Media.DATA} LIKE ?", arrayOf("%/Screenshots/%"), "${MediaStore.Images.Media.DATE_ADDED} DESC")
        }
        cursor?.use {
            if (!it.moveToFirst()) return null
            if (System.currentTimeMillis() / 1000 - it.getLong(1) > MAX_AGE_SECONDS) return null
            return it.getLong(0)
        }
        return null
    }

    /** When Samsung's own screenshot toolbar was last seen on screen (set by the service). */
    @Volatile
    var samsungToolbarSeenAt = 0L

    /**
     * The gallery's setup (MainActivity.showViperSetup, step 2) asks for a screenshot to prove
     * Samsung's toolbar is off: watches for its window for a few seconds after the screenshot
     * (it may already have shown before the file landed) and writes "pass" or "fail" for the
     * setup to show. Viper Vision's own toolbar stays away meanwhile.
     */
    private fun checkSamsungToolbar() {
        ViperLog.i("SCREENSHOT", "Setup check: watching for Samsung's toolbar")
        val started = System.currentTimeMillis()
        fun samsungOnScreen(): Boolean {
            if (samsungToolbarSeenAt > started - 6000) return true
            return runCatching {
                service.windows.any { window -> window.root?.let(::isSamsungToolbar) == true }
            }.getOrDefault(false)
        }
        fun finish(passed: Boolean) {
            ViperLog.i("SCREENSHOT", "Setup check: Samsung's toolbar " + if (passed) "is off" else "is still on")
            service.getSharedPreferences("Prefs", Context.MODE_PRIVATE).edit()
                .putString(CHECK_RESULT, if (passed) "pass" else "fail").apply()
        }
        val poll = object : Runnable {
            override fun run() {
                when {
                    samsungOnScreen() -> finish(false)
                    System.currentTimeMillis() - started > 2000 -> finish(true)
                    else -> handler.postDelayed(this, 250)
                }
            }
        }
        handler.post(poll)
    }

    private class Shot(val id: Long, val uri: Uri, val path: String?, val name: String)

    private fun newestScreenshot(): Shot? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(MediaStore.Images.Media.OWNER_PACKAGE_NAME) else emptyArray()
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0" to arrayOf("%Screenshots%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Screenshots/%")
        }
        resolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val added = cursor.getLong(3)
            // Only one taken just now, not an old screenshot being renamed or edited.
            if (System.currentTimeMillis() / 1000 - added > MAX_AGE_SECONDS) return null
            // Not copies Viper Vision itself saves there (Save as copy of a screenshot).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cursor.getString(4) == service.packageName) return null
            if (cursor.getString(2)?.contains("_edited") == true) return null
            val id = cursor.getLong(0)
            return Shot(id, ContentUris.withAppendedId(collection, id), cursor.getString(1), cursor.getString(2) ?: "Screenshot.png")
        }
        return null
    }

    // The screenshot on screen now, its full-size picture, and the result of Crop / Draw (null
    // while nothing has been changed).
    private var shot: Shot? = null
    private var original: Bitmap? = null
    private var edited: Bitmap? = null
    private var stage: FrameLayout? = null
    private var controls: FrameLayout? = null
    private var actions: View? = null

    private val density get() = service.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val accent get() = appAccentColor(service).toArgb()
    private val onAccent get() = appAccentColor(service).contentOn().toArgb()
    private val grey = BubbleGrey.toArgb()

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit) = TextView(service).apply {
        this.text = text
        gravity = Gravity.CENTER
        maxLines = 1
        // Shrinks to fit ("Share & Save" with a large system font size).
        setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        setTextColor(if (filled) onAccent else android.graphics.Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (filled) accent else grey) }
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { onClick() }
    }

    private fun bubble(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(service).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grey) }
            addView(ImageView(service).apply {
                setImageResource(icon)
                setColorFilter(android.graphics.Color.WHITE)
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(TextView(service).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun systemBar(name: String): Int {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) service.resources.getDimensionPixelSize(id) else 0
    }

    private fun show(shot: Shot) {
        hide()
        this.shot = shot
        original = runCatching {
            resolver.openInputStream(shot.uri)?.use { BitmapFactory.decodeStream(it) }
        }.onFailure { ViperLog.error("SCREENSHOT", "couldn't read the screenshot", it) }.getOrNull()
        edited = null

        val stage = FrameLayout(service).also { stage = it }
        val controls = FrameLayout(service).also { controls = it }
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            fun weighted(end: Int) = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = end }
            addView(pill("Share", false) { share(keep = false) }, weighted(dp(8)))
            addView(pill("Save", true) { save() }, weighted(dp(8)))
            addView(pill("Share & Save", false) { share(keep = true) }, weighted(0))
        }.also { actions = it }

        // Full screen and opaque; it stays until Share, Save, Share & Save or the trash can.
        val page = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(dp(16), systemBar("status_bar_height") + dp(12), dp(16), systemBar("navigation_bar_height") + dp(16))
            // Touches don't fall through to the app underneath.
            isClickable = true
            addView(actions)
            addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            })
            addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(124)))
        }
        showPreview()

        // The trash can, in the screen's bottom left corner: deletes the screenshot straight away
        // (the setup made sure of All files access, so Android doesn't ask).
        val trash = FrameLayout(service).also { trashButton = it }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grey) }
            contentDescription = "Delete screenshot"
            addView(ImageView(service).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setColorFilter(0xFFE5484D.toInt())
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { deleteNow() }
        }
        val window = FrameLayout(service).apply {
            addView(page)
            addView(trash, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(10)
                bottomMargin = systemBar("navigation_bar_height") + dp(10)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            service.getSystemService(WindowManager::class.java).addView(window, params)
            card = window
        }.onFailure { ViperLog.error("SCREENSHOT", "couldn't show the toolbar", it) }
    }

    /** The screenshot (as edited so far), with the Crop and Draw bubbles under it. */
    private fun showPreview() {
        val stage = stage ?: return
        trashButton?.visibility = View.VISIBLE
        val controls = controls ?: return
        setActionsEnabled(true)
        stage.removeAllViews()
        stage.addView(ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val picture = edited ?: original
            if (picture != null) setImageBitmap(picture) else shot?.let { setImageURI(it.uri) }
        })
        controls.removeAllViews()
        controls.addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(bubble(R.drawable.vv_ic_crop, "Crop") { startCrop() }, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bubble(R.drawable.vv_ic_draw, "Draw") { startDraw() }, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** While cropping or drawing, Share / Save wait for Done or Cancel. */
    private fun setActionsEnabled(enabled: Boolean) {
        actions?.alpha = if (enabled) 1f else 0.35f
        (actions as? LinearLayout)?.let { row -> for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = enabled }
    }

    /** Cancel / Done (and anything else, in [extra]) under a tool. */
    private fun toolBar(extra: View?, onDone: () -> Unit): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        if (extra != null) addView(extra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(12) })
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            fun weighted(end: Int) = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = end }
            addView(pill("Cancel", false) { showPreview() }, weighted(dp(8)))
            addView(pill("Done", true) { onDone() }, weighted(0))
        })
    }

    // Hidden while cropping or drawing (their own buttons use the bottom of the screen).
    private var trashButton: View? = null

    private fun startCrop() {
        trashButton?.visibility = View.GONE
        val picture = edited ?: original ?: return
        val stage = stage ?: return
        val controls = controls ?: return
        setActionsEnabled(false)
        val view = ShotCropView(service, picture)
        stage.removeAllViews()
        stage.addView(view)
        val reset = pill("Reset crop", false) { view.reset() }
        controls.removeAllViews()
        controls.addView(toolBar(LinearLayout(service).apply {
            gravity = Gravity.CENTER
            addView(reset, LinearLayout.LayoutParams(dp(140), dp(44)))
        }) {
            if (view.isChanged) edited = view.cropped()
            showPreview()
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun startDraw() {
        trashButton?.visibility = View.GONE
        val picture = edited ?: original ?: return
        val stage = stage ?: return
        val controls = controls ?: return
        setActionsEnabled(false)
        val view = ShotDrawView(service, picture).apply { color = accent }
        stage.removeAllViews()
        stage.addView(view)

        // Colours (the app's first), three brush sizes and Undo.
        val colors = listOf(accent, android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0xFFFF3B30.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt())
        val dots = colors.map { ColorDot(service, it) }
        dots.first().chosen = true
        val sizes = listOf(3f, 6f, 12f).map { SizeDot(service, it) }
        sizes[1].chosen = true
        val undo = TextView(service).apply {
            text = "Undo"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(android.graphics.Color.WHITE)
            alpha = 0.4f
            setOnClickListener { view.undo() }
        }
        view.onChange = { undo.alpha = if (view.isChanged) 1f else 0.4f }
        val palette = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            dots.forEach { dot ->
                dot.setOnClickListener {
                    dots.forEach { it.chosen = it === dot }
                    view.color = dot.color
                }
                addView(dot, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
            addView(View(service), LinearLayout.LayoutParams(dp(8), 1))
            sizes.forEach { size ->
                size.setOnClickListener {
                    sizes.forEach { it.chosen = it === size }
                    view.brushDp = size.sizeDp
                }
                addView(size, LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            addView(undo, LinearLayout.LayoutParams(dp(56), dp(40)))
        }
        controls.removeAllViews()
        controls.addView(toolBar(palette) {
            if (view.isChanged) edited = view.result()
            showPreview()
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun hide() {
        card?.let { view -> runCatching { service.getSystemService(WindowManager::class.java)?.removeView(view) } }
        card = null
        stage = null
        controls = null
        actions = null
    }

    /** The edits as a file in the screenshot's own format (PNG, or JPEG for anything else). */
    private fun encode(picture: Bitmap, shot: Shot): ByteArray {
        val png = shot.name.endsWith(".png", ignoreCase = true)
        return java.io.ByteArrayOutputStream().also {
            picture.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
        }.toByteArray()
    }

    /**
     * Writes the edited screenshot over the original. If Android won't let the app change
     * Samsung's file, it's saved as a new screenshot and the old one deleted (Android may ask).
     */
    private fun writeBack(shot: Shot, bytes: ByteArray) {
        val overwritten = runCatching { writeOverOriginal(service, shot.uri, shot.path, bytes, null) }
            .onFailure { ViperLog.i("SCREENSHOT", "Couldn't write over the screenshot (${it.message}); saving a new one") }
            .isSuccess
        if (overwritten) return
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, shot.name)
            put(MediaStore.Images.Media.MIME_TYPE, if (shot.name.endsWith(".png", true)) "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val folder = shot.path?.let { File(it).parent }
                    ?.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath)?.trim('/')
                put(MediaStore.Images.Media.RELATIVE_PATH, (folder ?: "DCIM/Screenshots") + "/")
            }
        }
        val created = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("couldn't save the screenshot")
        resolver.openOutputStream(created)?.use { it.write(bytes) } ?: error("couldn't save the screenshot")
        delete(service, shot.uri, shot.path, quietly = true)
    }

    /** Save: keeps the screenshot, with the Crop / Draw edits if there are any. */
    /** The trash can: deletes the screenshot without asking and closes. */
    private fun deleteNow() {
        val shot = shot ?: return
        hide()
        ViperLog.i("SCREENSHOT", "Trash can: deleting the screenshot")
        if (!delete(service, shot.uri, shot.path, quietly = true, mayAsk = false)) {
            Toast.makeText(service, "Couldn't delete the screenshot", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val shot = shot ?: return
        val picture = edited
        hide()
        if (picture == null) {
            Toast.makeText(service, "Screenshot saved", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = runCatching { writeBack(shot, encode(picture, shot)) }
            handler.post {
                result.onSuccess { Toast.makeText(service, "Screenshot saved", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        ViperLog.error("SCREENSHOT", "save failed", it)
                        Toast.makeText(service, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    /**
     * Shares a private copy (as edited), so the app shared to can still read it after the
     * screenshot itself is deleted. With [keep] (Share & Save) the edits are saved too; without
     * it the screenshot is deleted once an app is picked (backing out of the share sheet keeps it).
     */
    private fun share(keep: Boolean) {
        val shot = shot ?: return
        val picture = edited
        hide()
        Thread {
            val result = runCatching {
                val bytes = picture?.let { encode(it, shot) }
                if (keep && bytes != null) writeBack(shot, bytes)
                val folder = File(service.filesDir, SHARE_FOLDER).apply { mkdirs() }
                // Copies from earlier shares are only needed while those apps read them.
                folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP_SHARED_COPY_MS }?.forEach { it.delete() }
                val copy = File(folder, shot.name)
                if (bytes != null) copy.writeBytes(bytes)
                else resolver.openInputStream(shot.uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    ?: error("couldn't read the screenshot")
                FileProvider.getUriForFile(service, service.getString(com.t8rin.imagetoolbox.core.resources.R.string.file_provider), copy)
            }
            handler.post {
                result.onSuccess { shared ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (shot.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, shared)
                        clipData = ClipData.newRawUri(null, shared)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val picked = PendingIntent.getBroadcast(
                        service,
                        shot.id.toInt(),
                        Intent(service, ScreenshotSharedReceiver::class.java)
                            .putExtra(EXTRA_URI, shot.uri)
                            .putExtra(EXTRA_PATH, shot.path)
                            .putExtra(EXTRA_DELETE, !keep),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    service.startActivity(
                        Intent.createChooser(send, null, picked.intentSender)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    ViperLog.i("SCREENSHOT", (if (keep) "Share & Save" else "Share (delete after)") + if (picture != null) ", edited" else "")
                }.onFailure {
                    ViperLog.error("SCREENSHOT", "share failed", it)
                    Toast.makeText(service, "Couldn't share: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        const val PREF_ENABLED = "vv_screenshot_toolbar"
        /** Shared with the gallery's setup (MainActivity.showViperSetup). */
        const val CHECK_PENDING = "vv_toolbar_check_pending"
        const val CHECK_RESULT = "vv_toolbar_check_result"
        const val CHECK_SHOTS = "vv_toolbar_check_shots"
        const val SAMSUNG_CAPTURE_PACKAGE = "com.samsung.android.app.smartcapture"

        /** Buttons only Samsung's screenshot toolbar has (not its capture flash or preview). */
        private val TOOLBAR_LABELS = listOf("scroll capture", "scroll", "draw", "crop", "tag", "edit", "share")

        /**
         * Whether [root] (a window) is Samsung's screenshot toolbar: from Smart capture, with at
         * least two of the toolbar's buttons. Smart capture also shows a flash and a small
         * preview after every screenshot with the toolbar off, which mustn't count.
         */
        fun isSamsungToolbar(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (root.packageName?.toString() != SAMSUNG_CAPTURE_PACKAGE) return false
            val found = HashSet<String>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                if (depth > 25 || found.size >= 2) return
                val text = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase().orEmpty()
                val id = node.viewIdResourceName.orEmpty().lowercase()
                TOOLBAR_LABELS.firstOrNull { text == it || id.endsWith("/$it") || id.contains("toolbar") }?.let { found += it + id }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            return found.size >= 2
        }
        /** Buttons of phones' own screenshot toolbars (Android's and others'). */
        private val PHONE_TOOLBAR_LABELS = listOf(
            "edit", "share", "crop", "delete", "trash", "markup", "scroll", "capture more", "long screenshot",
            "extended screenshot", "dismiss", "lens", "search"
        )
        private const val MAX_AGE_SECONDS = 15
        private const val SHARE_FOLDER = "vv_shared_screenshots"
        private const val KEEP_SHARED_COPY_MS = 24L * 60 * 60 * 1000
        internal const val EXTRA_URI = "com.vipervision.editor.SCREENSHOT_URI"
        internal const val EXTRA_PATH = "com.vipervision.editor.SCREENSHOT_PATH"
        internal const val EXTRA_DELETE = "com.vipervision.editor.SCREENSHOT_DELETE"
        internal const val EXTRA_QUIET = "com.vipervision.editor.SCREENSHOT_QUIET"

        /**
         * Deletes the screenshot: through the media library, or its file; if Android wants the
         * user's OK, [ScreenshotDeleteActivity] asks for it.
         */
        internal fun delete(context: Context, uri: Uri, path: String?, quietly: Boolean = false, mayAsk: Boolean = true): Boolean {
            val deleted = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) ||
                    (path != null && runCatching {
                        File(path).delete().also { if (it) MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
                    }.getOrDefault(false))
            if (deleted) {
                ViperLog.i("SCREENSHOT", "Deleted the screenshot")
                if (!quietly) Toast.makeText(context, "Shared, screenshot deleted", Toast.LENGTH_SHORT).show()
                return true
            }
            if (!mayAsk) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.startActivity(
                    Intent(context, ScreenshotDeleteActivity::class.java)
                        .putExtra(EXTRA_URI, uri)
                        .putExtra(EXTRA_QUIET, quietly)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                ViperLog.i("SCREENSHOT", "Couldn't delete the screenshot after sharing")
            }
            return false
        }
    }
}

/** Told by the share sheet which app was picked: then the screenshot is deleted (Share only). */
class ScreenshotSharedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra(ScreenshotToolbar.EXTRA_DELETE, false)) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(ScreenshotToolbar.EXTRA_URI) ?: return
        ScreenshotToolbar.delete(context, uri, intent.getStringExtra(ScreenshotToolbar.EXTRA_PATH))
    }
}

/**
 * Invisible: shows Android's "Allow Viper Vision to delete this screenshot?" when the app may not
 * delete it on its own (no "All files access" / "Media management"), then closes.
 */
class ScreenshotDeleteActivity : ComponentActivity() {

    private val request = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ViperLog.i("SCREENSHOT", "Deleted the screenshot (with permission)")
            if (!intent.getBooleanExtra(ScreenshotToolbar.EXTRA_QUIET, false)) {
                Toast.makeText(this, "Shared, screenshot deleted", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(ScreenshotToolbar.EXTRA_URI)
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || savedInstanceState != null) {
            if (savedInstanceState == null) finish()
            return
        }
        runCatching {
            val sender = MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
            request.launch(IntentSenderRequest.Builder(sender).build())
        }.onFailure {
            ViperLog.error("SCREENSHOT", "couldn't ask to delete the screenshot", it)
            finish()
        }
    }
}
