package mammyframe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One photo on screen, with its own slow "gentle zoom". */
private class Slide(val image: ImageBitmap) {
    val zoom = Animatable(1f)
    val origin = TransformOrigin(Random.nextFloat() * 0.6f + 0.2f, Random.nextFloat() * 0.6f + 0.2f)
}

private const val ZOOM_END = 1.08f

@Composable
fun SlideshowScreen(settings: Settings, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val maxSide = screenMaxSide()

    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var shown by remember { mutableStateOf<Slide?>(null) }
    var incoming by remember { mutableStateOf<Slide?>(null) }
    var style by remember { mutableStateOf(Transition.FADE) }
    var direction by remember { mutableIntStateOf(1) }
    val progress = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    val cache = remember { mutableStateMapOf<String, ImageBitmap>() }

    // Photos AI rotate turned inside the app only (kinds Android can't save, such as HEIC).
    var appTurns by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    suspend fun load(path: String): ImageBitmap? = cache[path]
        ?: withContext(Dispatchers.IO) {
            Photos.decode(path, maxSide, appTurns[path] ?: 0)?.toComposeImageBitmap()
        }?.also { cache[path] = it }

    fun tidyAndPrefetch(list: Playlist) {
        val keep = setOfNotNull(list.current, list.peekPrevious(), list.peekNext())
        cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
        list.peekNext()?.let { next -> scope.launch { load(next) } }
    }

    /** Moves one photo forward (+1) or back (-1) with the chosen transition. */
    suspend fun go(dir: Int) {
        val list = playlist ?: return
        if (busy || list.isEmpty) return
        if (dir < 0 && !list.canGoBack) return
        busy = true
        try {
            var bmp: ImageBitmap? = null
            var tries = 0
            while (bmp == null && tries < 25) {
                val uri = if (dir > 0) list.forward() else if (list.canGoBack) list.back() else null
                uri ?: break
                bmp = load(uri)
                tries++
            }
            if (bmp == null) return
            val slide = Slide(bmp)
            style = if (settings.transition == Transition.RANDOM) Transition.mixable.random() else settings.transition
            direction = dir
            progress.snapTo(0f)
            incoming = slide
            if (style != Transition.NONE && settings.transitionMs > 0) {
                val easing = if (style == Transition.FADE || style == Transition.FADE_BLACK) LinearEasing else FastOutSlowInEasing
                progress.animateTo(1f, tween(settings.transitionMs, easing = easing))
            }
            shown = slide
            incoming = null
            tick++
            tidyAndPrefetch(list)
        } finally {
            busy = false
        }
    }

    // Find the photos, then show the first one.
    LaunchedEffect(Unit) {
        appTurns = runCatching { AiRotate.appTurns() }.getOrDefault(emptyMap())
        // Start straight away from the saved list of photos; if it's getting old, look through
        // the folder again in the background so new photos are in the next slideshow.
        val (uris, refresh) = Photos.listQuick(settings.folder)
        val list = Playlist(uris)
        if (refresh) scope.launch { runCatching { Photos.list(settings.folder) } }
        playlist = list
        if (!list.isEmpty) go(1)
    }

    // Next photo after the chosen time (restarts whenever a photo changes or play/pause is pressed).
    LaunchedEffect(tick, paused) {
        if (paused || shown == null) return@LaunchedEffect
        delay(settings.photoSeconds * 1000L)
        scope.launch { go(1) }
    }

    // Gentle zoom on the photo being shown; it holds still while paused.
    val zoomTarget = shown
    LaunchedEffect(zoomTarget, paused) {
        val s = zoomTarget ?: return@LaunchedEffect
        if (!settings.gentleZoom || paused) return@LaunchedEffect
        val total = (settings.photoSeconds * 1000 + settings.transitionMs).coerceAtLeast(1000)
        val remaining = ((ZOOM_END - s.zoom.value) / (ZOOM_END - 1f) * total).toInt()
        if (remaining > 0) s.zoom.animateTo(ZOOM_END, tween(remaining, easing = LinearEasing))
    }
    val incomingNow = incoming
    LaunchedEffect(incomingNow) {
        val s = incomingNow ?: return@LaunchedEffect
        if (!settings.gentleZoom || paused) return@LaunchedEffect
        val total = (settings.photoSeconds * 1000 + settings.transitionMs).coerceAtLeast(1000)
        s.zoom.animateTo(ZOOM_END, tween(total, easing = LinearEasing))
    }

    DisposableEffect(Unit) { onDispose { cache.clear() } }

    // Keyboard: left/right arrows = back/forward, space = pause/play, Esc = end the slideshow.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { scope.launch { go(-1) }; true }
                    Key.DirectionRight -> { scope.launch { go(1) }; true }
                    Key.Spacebar -> { paused = !paused; true }
                    Key.Escape -> { onExit(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) { detectTapGestures { controls = !controls } }
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val p = progress.value
        val transitioning = incoming != null

        shown?.let { s ->
            PhotoLayer(s) {
                if (!transitioning) return@PhotoLayer
                when (style) {
                    Transition.FADE, Transition.ZOOM -> {
                        alpha = 1f - p
                        if (style == Transition.ZOOM) { scaleX *= 1f + 0.08f * p; scaleY *= 1f + 0.08f * p }
                    }
                    Transition.FADE_BLACK -> alpha = (1f - 2f * p).coerceIn(0f, 1f)
                    Transition.SLIDE -> translationX = -direction * p * wPx
                    Transition.SLIDE_UP -> translationY = -direction * p * hPx
                    else -> alpha = 0f
                }
            }
        }
        incoming?.let { s ->
            PhotoLayer(s) {
                if (shown == null) { alpha = p; return@PhotoLayer }
                when (style) {
                    Transition.FADE -> alpha = p
                    Transition.ZOOM -> {
                        alpha = p
                        val z = 1.25f - 0.25f * p
                        scaleX *= z; scaleY *= z
                    }
                    Transition.FADE_BLACK -> alpha = (2f * p - 1f).coerceIn(0f, 1f)
                    Transition.SLIDE -> translationX = direction * (1f - p) * wPx
                    Transition.SLIDE_UP -> translationY = direction * (1f - p) * hPx
                    else -> alpha = 1f
                }
            }
        }

        val list = playlist
        if (list != null && list.isEmpty) {
            Text(
                if (settings.folder == null) "No photos folder has been chosen yet."
                else "There are no photos in the chosen folder yet.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = if (screenW < 600.dp) 20.sp else 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                val big = min(min(screenW / 4.2f, screenH / 2.6f), 190.dp)
                Row(
                    Modifier.fillMaxWidth().align(Alignment.Center),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundButton(Glyph.BACK, big, enabled = list?.canGoBack == true) { scope.launch { go(-1) } }
                    RoundButton(if (paused) Glyph.PLAY else Glyph.PAUSE, big * 1.12f) { paused = !paused }
                    RoundButton(Glyph.FORWARD, big, enabled = list?.isEmpty == false) { scope.launch { go(1) } }
                }
                // Very small "end slideshow" button in the top left corner.
                Box(
                    Modifier
                        .padding(10.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(onClick = onExit)
                        .padding(9.dp)
                ) {
                    GlyphIcon(Glyph.CLOSE, Color.White.copy(alpha = 0.75f), Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PhotoLayer(slide: Slide, transform: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) {
    Image(
        bitmap = slide.image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = slide.origin
                scaleX = slide.zoom.value
                scaleY = slide.zoom.value
                transform()
            }
    )
}

@Composable
private fun RoundButton(glyph: Glyph, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.30f))
            .border(2.dp, Color.White.copy(alpha = if (enabled) 0.45f else 0.15f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(color = Color.White),
                onClick = onClick
            )
            .padding(size * 0.27f)
    ) {
        GlyphIcon(glyph, Color.White.copy(alpha = if (enabled) 0.85f else 0.25f), Modifier.fillMaxSize())
    }
}
