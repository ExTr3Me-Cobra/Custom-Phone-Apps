package com.vipervision.editor

import androidx.compose.foundation.layout.requiredSize
import com.t8rin.imagetoolbox.core.resources.icons.RotateRight
import com.t8rin.imagetoolbox.core.resources.icons.CropSmall
import com.t8rin.imagetoolbox.core.resources.icons.Speed
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import android.view.TextureView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Close
import com.t8rin.imagetoolbox.core.resources.icons.Scissors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Viper Vision's video editor, opened from Gallery's Edit button for videos: trim (the first
 * bubble) and the Adjust dials, shown live while the video plays. Saving makes a new video next
 * to the original ("<original>_edited.mp4"); the original is never changed.
 */
@OptIn(UnstableApi::class)
class VideoEditorActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var frames: VideoFrames? = null
    private lateinit var scrubber: Scrubber
    private val state = VideoEditState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data ?: run { finish(); return }
        val realPath = intent.getStringExtra(EditorActivity.EXTRA_REAL_PATH)
        enableEdgeToEdge()
        frames = runCatching { VideoFrames(this, source) }.getOrNull()
        frames?.aspect?.let { state.aspect = it }

        player = ExoPlayer.Builder(this).build().apply {
            // The dials are read for every frame, so the preview follows them while playing.
            setVideoEffects(listOf(VideoAdjustEffect { state.liveAdjustments }))
            setMediaItem(MediaItem.fromUri(source))
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // Only a fallback: with effects on, the player's reported size can be the
                    // stored (unrotated) one, so the size read from the file comes first.
                    if (frames?.aspect == null && videoSize.width > 0 && videoSize.height > 0) {
                        val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                        val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        state.aspect = if (rotated) 1f / ratio else ratio
                    }
                }

                override fun onRenderedFirstFrame() {
                    scrubber.frameShown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state.playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) scrubber.frameShown()
                    if (playbackState == Player.STATE_READY && state.durationMs == 0L) {
                        state.durationMs = duration.coerceAtLeast(1)
                        state.trimEnd = state.durationMs
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        seekTo(state.trimStart)
                        pause()
                    }
                }
            })
            prepare()
        }
        scrubber = Scrubber(player)

        setContent {
            AccentTheme(this@VideoEditorActivity) {
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    val reader = frames ?: return@LaunchedEffect
                    state.thumbnails = reader.strip(0, reader.durationMs, STRIP_FRAMES, exact = false)
                }
                VideoEditorScreen(
                    state = state,
                    player = player,
                    scrubber = scrubber,
                    frames = frames,
                    onClose = ::finish,
                    onSave = { scope.launch { save(source, realPath) } }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        frames?.close()
    }

    /** Exports the trimmed, adjusted video and saves it as a new video next to the original. */
    private suspend fun save(source: Uri, realPath: String?) {
        if (state.exportProgress != null) return
        player.pause()
        state.exportProgress = 0f
        val output = File(cacheDir, "vv_video").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            .let { File(it, "export_${System.currentTimeMillis()}.mp4") }
        runCatching {
            export(source, output)
            withContext(Dispatchers.IO) { saveVideoCopy(this@VideoEditorActivity, output, source, realPath) }
        }.onSuccess {
            Toast.makeText(this, "Saved as a new video", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure {
            ViperLog.error("VIDEO", "save failed", it)
            Toast.makeText(this, "Couldn't save the video: ${it.message}", Toast.LENGTH_LONG).show()
        }
        output.delete()
        state.exportProgress = null
    }

    private suspend fun export(source: Uri, output: File) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(state.trimStart)
            .apply { if (state.trimEnd < state.durationMs) setEndPositionMs(state.trimEnd) }
            .build()
        val item = MediaItem.Builder().setUri(source).setClippingConfiguration(clipping).build()
        val adjustments = state.adjustments
        val video = mutableListOf<androidx.media3.common.Effect>()
        // Crop (on the upright picture, before turning), then rotate, then the dials.
        state.cropRatio?.let { ratio ->
            val turned = state.turns % 2 == 1
            val kept = if (turned) 1f / ratio else ratio
            val aspect = state.aspect
            video += if (aspect > kept) {
                val w = kept / aspect
                androidx.media3.effect.Crop(-w, w, -1f, 1f)
            } else {
                val h = aspect / kept
                androidx.media3.effect.Crop(-1f, 1f, -h, h)
            }
        }
        if (state.turns % 4 != 0) {
            // Media3 turns anticlockwise.
            video += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(360f - 90f * (state.turns % 4)).build()
        }
        if (!adjustments.isIdentity) video += VideoAdjustEffect { adjustments }
        val effects = if (video.isEmpty()) Effects.EMPTY else Effects(emptyList(), video)
        val builder = EditedMediaItem.Builder(item).setEffects(effects)
        if (state.speed != 1f) {
            // Times here start at the trimmed start.
            val speed = state.speed
            val whole = state.speedWhole
            val from = ((state.speedStart - state.trimStart).coerceAtLeast(0)) * 1000
            val to = ((state.speedEnd - state.trimStart).coerceAtLeast(0)) * 1000
            builder.setSpeed(object : androidx.media3.common.audio.SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = if (whole || timeUs in from until to) speed else 1f
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = when {
                    whole -> androidx.media3.common.C.TIME_UNSET
                    timeUs < from -> from
                    timeUs < to -> to
                    else -> androidx.media3.common.C.TIME_UNSET
                }
            })
        }
        val edited = builder.build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(edited)).build())
            // The dials are made for normal (SDR) video, so HDR video is converted to SDR.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        continuation.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
            val stopProgress = pollProgress(transformer)
            continuation.invokeOnCancellation {
                stopProgress.run()
                transformer.cancel()
            }
        }
    }

    /** Updates the progress shown while exporting, until the export finishes. */
    private fun pollProgress(transformer: Transformer): Runnable {
        val holder = ProgressHolder()
        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                if (state.exportProgress == null) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    state.exportProgress = holder.progress / 100f
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(tick)
        return Runnable { handler.removeCallbacks(tick) }
    }
}

/** What's being edited: the trim range, the dials and what the screen shows. */
class VideoEditState {
    var durationMs by mutableLongStateOf(0L)
    var trimStart by mutableLongStateOf(0L)
    var trimEnd by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)
    var thumbnails by mutableStateOf<List<Bitmap>>(emptyList())

    /** Null while trimming, otherwise the dial being turned. */
    var selected by mutableStateOf<Adjustment?>(null)
    var adjustments by mutableStateOf(Adjustments())

    /** The dials for the video renderer, which runs on its own thread. */
    @Volatile
    var liveAdjustments = Adjustments()

    /** While paused: the shown frame with the dials applied (exact, unlike the player's). */
    var pausedPreview by mutableStateOf<Bitmap?>(null)
    var pausedPreviewMs by mutableLongStateOf(-1L)

    /** Pinch zoom of the video. */
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** While holding on the trim bar: the part of the video it's zoomed into, and its frames. */
    var timelineWindow by mutableStateOf<LongRange?>(null)
    var windowThumbnails by mutableStateOf<List<Bitmap?>>(emptyList())

    /** 0..1 while saving. */
    var exportProgress by mutableStateOf<Float?>(null)

    /** The tool below the video while no dial is picked. */
    var tool by mutableStateOf(VideoTool.Trim)

    /** Speed: how fast (1 = normal), for the whole video or only [speedStart]..[speedEnd]. */
    var speed by mutableFloatStateOf(1f)
    var speedWhole by mutableStateOf(true)
    var speedStart by mutableLongStateOf(0L)
    var speedEnd by mutableLongStateOf(0L)

    /** Rotate: quarter turns clockwise. */
    var turns by mutableIntStateOf(0)

    /** Crop: the shape kept (width / height as shown), centred; null keeps the whole picture. */
    var cropRatio by mutableStateOf<Float?>(null)

    /** The speed at [ms] of the source video. */
    fun speedAt(ms: Long): Float = when {
        speed == 1f -> 1f
        speedWhole -> speed
        ms in speedStart until speedEnd -> speed
        else -> 1f
    }

    val changed: Boolean
        get() = !adjustments.isIdentity || trimStart > 0 || (durationMs > 0 && trimEnd < durationMs) ||
            speed != 1f || turns % 4 != 0 || cropRatio != null
}

/** The video editor's tools besides the dials. */
enum class VideoTool(val label: String) { Trim("Trim"), Speed("Speed"), Crop("Crop"), Rotate("Rotate") }

/** Speeds offered. */
private val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

/** Crop shapes offered (width / height); null = the whole picture. */
private val CropRatios: List<Pair<String, Float?>> = listOf(
    "Original" to null, "1:1" to 1f, "4:5" to 0.8f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "4:3" to 4f / 3f, "3:4" to 0.75f
)

private const val MIN_TRIM_MS = 500L
private const val STRIP_FRAMES = 10

/**
 * Seeks for scrubbing: while the player is still finding one frame, newer positions wait and only
 * the latest is sought next, so the video keeps up with the finger instead of every seek
 * cancelling the one before it (which showed nothing until the finger stopped).
 */
class Scrubber(private val player: ExoPlayer) {
    private var seekingSince = 0L
    private var pending: Long? = null

    fun seekTo(ms: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        // If the player never confirms a frame, don't wait on it forever.
        if (seekingSince != 0L && now - seekingSince < 700) {
            pending = ms
            return
        }
        pending = null
        seekingSince = now
        player.seekTo(ms)
    }

    /** The player has shown the frame it was seeking to. */
    fun frameShown() {
        seekingSince = 0L
        pending?.let(::seekTo)
    }
}

@Composable
private fun VideoEditorScreen(
    state: VideoEditState,
    player: ExoPlayer,
    scrubber: Scrubber,
    frames: VideoFrames?,
    onClose: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    var askDiscard by remember { mutableStateOf(false) }
    val close = { if (state.changed) askDiscard = true else onClose() }
    BackHandler(onBack = close)

    // Follow playback, and loop inside the trimmed part.
    LaunchedEffect(player) {
        while (isActive) {
            val position = player.currentPosition
            state.positionMs = position
            if (state.playing && state.trimEnd > 0 && position >= state.trimEnd) {
                player.seekTo(state.trimStart)
            }
            // Speed: played at the chosen speed (only inside the section, if that's picked).
            val speed = state.speedAt(position)
            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
            delay(30)
        }
    }
    // Playing video picks the dials up by itself (the effect reads them every frame).
    LaunchedEffect(Unit) {
        snapshotFlow { state.adjustments }.collectLatest { state.liveAdjustments = it }
    }
    // Paused: show the exact frame with the dials applied by the photo editor's maths, redone on
    // every change, so the preview never waits for the player to redraw.
    LaunchedEffect(frames) {
        val reader = frames ?: return@LaunchedEffect
        var frame: Bitmap? = null
        var frameMs = -1L
        snapshotFlow { Triple(state.playing, state.positionMs, state.adjustments) }.collectLatest { (playing, position, adjustments) ->
            if (playing) {
                state.pausedPreview = null
                return@collectLatest
            }
            if (position != frameMs || frame == null) {
                delay(80) // wait for scrubbing to settle
                frame = reader.frame(position, 1440) ?: return@collectLatest
                frameMs = position
            }
            val shown = frame ?: return@collectLatest
            state.pausedPreview = withContext(Dispatchers.Default) { applyAdjustments(context, shown, adjustments) }
            state.pausedPreviewMs = position
        }
    }
    // The zoomed trim bar's frames, exact ones for fine tuning.
    LaunchedEffect(frames) {
        val reader = frames ?: return@LaunchedEffect
        snapshotFlow { state.timelineWindow }.collectLatest { window ->
            state.windowThumbnails = List(STRIP_FRAMES) { null }
            if (window == null) return@collectLatest
            delay(150) // the window may still be sliding
            // Exact frames, nearest the middle (where the finger is) first; until each one
            // arrives the bar shows the normal thumbnail for that moment.
            val order = (0 until STRIP_FRAMES).sortedBy { abs(it - (STRIP_FRAMES - 1) / 2f) }
            for (i in order) {
                val ms = window.first + (window.last - window.first) * (2 * i + 1) / (2 * STRIP_FRAMES)
                val frame = reader.thumbnail(ms, exact = true) ?: continue
                state.windowThumbnails = state.windowThumbnails.toMutableList().also { it[i] = frame }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Text("Edit video", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSave, enabled = state.changed && state.exportProgress == null) {
                Text("Save", color = if (state.changed) Color.White else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // The video, as large as fits. Tap to play or pause, pinch to zoom (drag to move around
        // while zoomed), double-tap to zoom back out.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (player.isPlaying) player.pause() else {
                                if (player.currentPosition !in state.trimStart until state.trimEnd) player.seekTo(state.trimStart)
                                player.play()
                            }
                        },
                        onDoubleTap = {
                            state.zoom = 1f
                            state.pan = Offset.Zero
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state.zoom = (state.zoom * zoom).coerceIn(1f, 6f)
                        val maxX = size.width * (state.zoom - 1f) / 2f
                        val maxY = size.height * (state.zoom - 1f) / 2f
                        state.pan = Offset(
                            (state.pan.x + pan.x).coerceIn(-maxX, maxX),
                            (state.pan.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Shown turned (Rotate) with the part Crop keeps framed.
            val turned = state.turns % 2 == 1
            val shownAspect = if (turned) 1f / state.aspect else state.aspect
            val fitsWidth = maxWidth / maxHeight < shownAspect
            val videoWidth = if (fitsWidth) maxWidth else maxHeight * shownAspect
            val videoHeight = if (fitsWidth) maxWidth / shownAspect else maxHeight
            Box(
                Modifier
                    .size(videoWidth, videoHeight)
                    .graphicsLayer {
                        scaleX = state.zoom
                        scaleY = state.zoom
                        translationX = state.pan.x
                        translationY = state.pan.y
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .requiredSize(if (turned) videoHeight else videoWidth, if (turned) videoWidth else videoHeight)
                        .graphicsLayer { rotationZ = 90f * (state.turns % 4) }
                ) {
                    AndroidView(
                        factory = { TextureView(it).also(player::setVideoTextureView) },
                        modifier = Modifier.fillMaxSize()
                    )
                    val preview = state.pausedPreview
                    if (!state.playing && preview != null && state.pausedPreviewMs == state.positionMs) {
                        Image(
                            preview.asImageBitmap(), null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                state.cropRatio?.let { ratio ->
                    Canvas(Modifier.fillMaxSize()) {
                        val w: Float
                        val h: Float
                        if (size.width / size.height > ratio) { h = size.height; w = h * ratio } else { w = size.width; h = w / ratio }
                        val left = (size.width - w) / 2f
                        val top = (size.height - h) / 2f
                        val dim = Color.Black.copy(alpha = 0.6f)
                        drawRect(dim, Offset.Zero, Size(size.width, top))
                        drawRect(dim, Offset(0f, top + h), Size(size.width, size.height - top - h))
                        drawRect(dim, Offset(0f, top), Size(left, h))
                        drawRect(dim, Offset(left + w, top), Size(size.width - left - w, h))
                        drawRect(Color.White, Offset(left, top), Size(w, h), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            if (!state.playing) PlayBadge()
        }

        Text(
            "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))

        // Trim first, then the Adjust dials.
        Text(
            state.selected?.label ?: state.tool.label,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp)
        ) {
            items(VideoTool.entries) { tool ->
                val selected = state.selected == null && state.tool == tool
                val icon = when (tool) {
                    VideoTool.Trim -> Icons.Rounded.Scissors
                    VideoTool.Speed -> Icons.Rounded.Speed
                    VideoTool.Crop -> Icons.Rounded.CropSmall
                    VideoTool.Rotate -> Icons.Rounded.RotateRight
                }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.White else BubbleGrey)
                        .clickable {
                            state.selected = null
                            state.tool = tool
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, tool.label, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(26.dp))
                }
            }
            items(Adjustment.entries) { adjustment ->
                AdjustBubble(
                    adjustment = adjustment,
                    value = state.adjustments[adjustment],
                    selected = adjustment == state.selected,
                    onClick = {
                        // Tapping the dial that's already picked sets it back to 0.
                        if (adjustment == state.selected && state.adjustments[adjustment] != 0) {
                            state.adjustments = state.adjustments.with(adjustment, 0)
                        } else {
                            state.selected = adjustment
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (state.selected == null && state.tool == VideoTool.Speed) 132.dp else 84.dp),
            contentAlignment = Alignment.Center
        ) {
            val selected = state.selected
            if (selected == null) {
                when (state.tool) {
                    VideoTool.Trim -> TrimBar(state, player, scrubber)
                    VideoTool.Speed -> SpeedPanel(state, player, scrubber)
                    VideoTool.Crop -> ChoiceRow(CropRatios.map { it.first }, CropRatios.indexOfFirst { it.second == state.cropRatio }) {
                        state.cropRatio = CropRatios[it].second
                    }
                    VideoTool.Rotate -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChoicePill("↺  Left", false) { state.turns = (state.turns + 3) % 4 }
                        Text("${(state.turns % 4) * 90}°", color = Color.White, fontSize = 15.sp)
                        ChoicePill("Right  ↻", false) { state.turns = (state.turns + 1) % 4 }
                    }
                }
            } else {
                ValueWheel(
                    value = state.adjustments[selected],
                    onValueChange = { state.adjustments = state.adjustments.with(selected, it) },
                    onValueChangeFinished = {},
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }

    state.exportProgress?.let { progress ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(progress = { progress }, color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("Saving video… ${(progress * 100).roundToInt()}%", color = Color.White)
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this video haven't been saved.") },
            confirmButton = { TextButton(onClick = { askDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing") } }
        )
    }
}

/**
 * Frames from the video with two handles: drag either end to trim, or drag anywhere else to move
 * the playhead (white line). Press and hold to zoom the bar into the couple of seconds around
 * your finger for frame-accurate moves; it zooms back out when you let go. The part that's kept
 * is framed in the accent colour.
 */
/** Speed: how fast, for the whole video or a section of it (picked like Trim). */
@Composable
private fun SpeedPanel(state: VideoEditState, player: ExoPlayer, scrubber: Scrubber) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill("Whole video", state.speedWhole) { state.speedWhole = true }
            ChoicePill("Section", !state.speedWhole) {
                state.speedWhole = false
                // First time: the middle third of what's kept.
                if (state.speedEnd <= state.speedStart || state.speedEnd > state.trimEnd || state.speedStart < state.trimStart) {
                    val third = (state.trimEnd - state.trimStart) / 3
                    state.speedStart = state.trimStart + third
                    state.speedEnd = state.trimEnd - third
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        ChoiceRow(Speeds.map { if (it % 1f == 0f) "${it.toInt()}×" else "$it×" }, Speeds.indexOf(state.speed)) { state.speed = Speeds[it] }
        if (!state.speedWhole) {
            Spacer(Modifier.height(4.dp))
            RangeBar(
                state, player, scrubber,
                start = state.speedStart, end = state.speedEnd,
                onStart = { state.speedStart = it }, onEnd = { state.speedEnd = it },
                lower = state.trimStart, upper = state.trimEnd
            )
        }
    }
}

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
        items(labels.size) { i -> ChoicePill(labels[i], i == selected) { onPick(i) } }
    }
}

@Composable
private fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else BubbleGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun TrimBar(state: VideoEditState, player: ExoPlayer, scrubber: Scrubber) = RangeBar(
    state, player, scrubber,
    start = state.trimStart, end = state.trimEnd,
    onStart = { state.trimStart = it }, onEnd = { state.trimEnd = it },
    lower = 0L, upper = state.durationMs.coerceAtLeast(1)
)

/**
 * The trim bar's look and handling for any part of the video: [start]..[end] between [lower] and
 * [upper] (Trim: the whole video; Speed's Section: the trimmed part).
 */
@Composable
private fun RangeBar(
    state: VideoEditState,
    player: ExoPlayer,
    scrubber: Scrubber,
    start: Long,
    end: Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    lower: Long,
    upper: Long
) {
    val startNow by androidx.compose.runtime.rememberUpdatedState(start)
    val endNow by androidx.compose.runtime.rememberUpdatedState(end)
    val setStart by androidx.compose.runtime.rememberUpdatedState(onStart)
    val setEnd by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val haptics = LocalHapticFeedback.current
    val duration = state.durationMs.coerceAtLeast(1)
    val window = state.timelineWindow ?: 0L..duration
    Column(Modifier.padding(horizontal = 20.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            val strip: List<Bitmap?> = if (state.timelineWindow == null) state.thumbnails else {
                // Zoomed: the exact frames as they arrive, the normal thumbnail meanwhile.
                List(STRIP_FRAMES) { i ->
                    state.windowThumbnails.getOrNull(i) ?: run {
                        val ms = window.first + (window.last - window.first) * (2 * i + 1) / (2 * STRIP_FRAMES)
                        state.thumbnails.getOrNull((ms * state.thumbnails.size / duration).toInt().coerceIn(0, state.thumbnails.size - 1))
                    }
                }
            }
            Row(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BubbleGrey)
            ) {
                strip.forEach {
                    if (it == null) Spacer(Modifier.weight(1f)) else Image(
                        it.asImageBitmap(), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                }
            }
            val trimAccent = Accent
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(duration, lower, upper) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val width = size.width.toFloat()
                            fun msAt(x: Float, range: LongRange): Long =
                                (range.first + (x / width).coerceIn(0f, 1f) * (range.last - range.first)).toLong().coerceIn(0, duration)
                            fun xOf(ms: Long): Float = ms.toFloat() / duration * width
                            val reach = 24.dp.toPx()
                            val toStart = abs(down.position.x - xOf(startNow))
                            val toEnd = abs(down.position.x - xOf(endNow))
                            // -1 start handle, 1 end handle, 0 playhead
                            val target = when {
                                toStart <= reach && toStart <= toEnd -> -1
                                toEnd <= reach -> 1
                                else -> 0
                            }
                            fun moveTo(ms: Long) {
                                when (target) {
                                    -1 -> setStart(ms.coerceAtMost(endNow - MIN_TRIM_MS).coerceAtLeast(lower))
                                    1 -> setEnd(ms.coerceAtLeast(startNow + MIN_TRIM_MS).coerceAtMost(upper))
                                }
                                scrubber.seekTo(
                                    when (target) {
                                        -1 -> startNow
                                        1 -> endNow
                                        else -> ms.coerceIn(state.trimStart, state.trimEnd)
                                    }
                                )
                            }
                            player.pause()

                            // Moved, lifted, or held still long enough to zoom?
                            var lifted = false
                            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val change = awaitPointerEvent().changes.first()
                                    if (!change.pressed) {
                                        lifted = true
                                        break
                                    }
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                }
                            } == null
                            var range = 0L..duration
                            if (lifted) {
                                if (target == 0) moveTo(msAt(down.position.x, range))
                                return@awaitEachGesture
                            }
                            if (held) {
                                // Zoom into about 2 seconds (at most the whole video) around the finger.
                                val span = minOf(duration, maxOf(2000L, duration / 12))
                                val center = msAt(down.position.x, range)
                                val from = (center - span / 2).coerceIn(0, duration - span)
                                range = from..(from + span)
                                state.timelineWindow = range
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (target == 0) moveTo(center)
                            }
                            try {
                                // While zoomed, holding near either edge slides the zoomed part of
                                // the video that way (faster the closer to the edge), so trimming
                                // can go past what's on screen. Ticks keep it sliding while the
                                // finger rests there.
                                val edge = maxOf(width * 0.12f, 32.dp.toPx())
                                var x = down.position.x
                                var lastTick = android.os.SystemClock.uptimeMillis()
                                var lastMs = -1L
                                while (true) {
                                    val event = withTimeoutOrNull(16L) { awaitPointerEvent() }
                                    if (event != null) {
                                        val change = event.changes.first()
                                        if (!change.pressed) break
                                        change.consume()
                                        x = change.position.x
                                    }
                                    val now = android.os.SystemClock.uptimeMillis()
                                    val dt = (now - lastTick).coerceAtMost(100) / 1000f
                                    lastTick = now
                                    if (state.timelineWindow != null) {
                                        val depth = when {
                                            x < edge -> -(1f - x.coerceAtLeast(0f) / edge)
                                            x > width - edge -> 1f - (width - x.coerceAtMost(width)) / edge
                                            else -> 0f
                                        }
                                        if (depth != 0f) {
                                            val span = range.last - range.first
                                            // Up to 1.5 zoomed windows per second at the very edge.
                                            val shift = (depth * span * 1.5f * dt).toLong()
                                            val from = (range.first + shift).coerceIn(0, duration - span)
                                            if (from != range.first) {
                                                range = from..(from + span)
                                                state.timelineWindow = range
                                            }
                                        }
                                    }
                                    val ms = msAt(x, range)
                                    if (ms != lastMs) {
                                        lastMs = ms
                                        moveTo(ms)
                                    }
                                }
                            } finally {
                                state.timelineWindow = null
                            }
                        }
                    }
            ) {
                val span = (window.last - window.first).coerceAtLeast(1).toFloat()
                fun x(ms: Long) = ((ms - window.first) / span * size.width).coerceIn(-size.width, 2 * size.width)
                val startX = x(start)
                val endX = x(end)
                val dim = Color.Black.copy(alpha = 0.65f)
                val accent = trimAccent
                clipRect {
                    drawRect(dim, Offset.Zero, Size(startX.coerceIn(0f, size.width), size.height))
                    drawRect(dim, Offset(endX.coerceIn(0f, size.width), 0f), Size(size.width - endX.coerceIn(0f, size.width), size.height))
                    val handle = 10.dp.toPx()
                    val border = 3.dp.toPx()
                    drawRoundRect(
                        accent,
                        Offset(startX, border / 2),
                        Size(max(endX - startX, handle), size.height - border),
                        CornerRadius(6.dp.toPx()),
                        style = Stroke(border)
                    )
                    drawRect(accent, Offset(startX, 0f), Size(handle, size.height))
                    drawRect(accent, Offset(endX - handle, 0f), Size(handle, size.height))
                    val playX = x(state.positionMs)
                    if (state.positionMs in state.trimStart..state.trimEnd) {
                        drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            val zoomed = state.timelineWindow
            Text(formatTime(zoomed?.first ?: start), color = if (zoomed != null) Accent else Color.Gray, fontSize = 12.sp)
            Text(
                if (zoomed != null) "Zoomed · ${formatTime(state.positionMs)}" else "Length ${formatTime(end - start)}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Text(formatTime(zoomed?.last ?: end), color = if (zoomed != null) Accent else Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayBadge() {
    Canvas(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.38f, w * 0.28f)
            lineTo(w * 0.74f, w * 0.5f)
            lineTo(w * 0.38f, w * 0.72f)
            close()
        }
        drawPath(path, Color.White)
    }
}

private fun formatTime(ms: Long): String {
    val hundredths = (ms.coerceAtLeast(0) / 10) % 100
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d.%02d".format(seconds / 60, seconds % 60, hundredths)
}

/**
 * Copies the exported video into the gallery as "<original>_edited.mp4", next to the original
 * when Android allows that folder (DCIM, Movies or Pictures), otherwise in Movies/Viper Vision.
 */
private fun saveVideoCopy(context: Context, exported: File, source: Uri, realPath: String?) {
    val info = sourceInfo(context, source, realPath)
    val baseName = info.displayName.substringBeforeLast('.') + "_edited"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            val folder = info.relativeFolder?.takeIf { f -> listOf("DCIM/", "Movies/", "Pictures/").any(f::startsWith) }
            put(MediaStore.Video.Media.RELATIVE_PATH, folder ?: "Movies/Viper Vision/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("couldn't create the new video")
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> exported.inputStream().use { it.copyTo(out) } } ?: error("couldn't write the new video")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }.onFailure {
            ViperLog.error("VIDEO", "saveVideoCopy failed", it)
            resolver.delete(uri, null, null)
            throw it
        }
    } else {
        @Suppress("DEPRECATION")
        val folder = info.absoluteFolder
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Viper Vision")
        folder.mkdirs()
        var file = File(folder, "$baseName.mp4")
        var n = 1
        while (file.exists()) file = File(folder, "$baseName (${n++}).mp4")
        exported.copyTo(file)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
    }
}
