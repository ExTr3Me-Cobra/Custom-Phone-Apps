package com.labelrescue.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labelrescue.app.pipeline.Images
import com.labelrescue.core.capture.CaptureGuide
import com.labelrescue.core.image.GrayImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Frames to collect in one burst from the analysis stream (plus [STILLS] full-resolution stills). */
const val BURST_FRAMES = 12
const val STILLS = 2

/** After this long without a good alignment, offer "Capture anyway" (e.g. barcode torn away). */
private const val OVERRIDE_AFTER_MS = 8000L

/**
 * Live camera with a guide box. A burst is allowed once the barcode sits inside the box, fills
 * most of its width, is straight, well exposed and in focus. The burst then grabs [BURST_FRAMES]
 * frames from the stream and [STILLS] full-resolution stills.
 */
@Composable
fun CameraScreen(onFrame: (GrayImage) -> Unit, onBurstDone: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Label Rescue needs the camera to photograph labels. Photos stay on this phone.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Allow camera") }
        }
        return
    }

    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val framesLeft = remember { AtomicInteger(0) }
    val stillsLeft = remember { AtomicInteger(0) }
    val frameTick = remember { AtomicInteger(0) }
    val checking = remember { AtomicBoolean(false) }
    val guide = remember { CaptureGuide() }
    var status by remember { mutableStateOf<CaptureGuide.Status?>(null) }
    var notReadySince by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var collected by remember { mutableIntStateOf(0) }
    var burstRunning by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var overrideOk by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }

    fun finishIfDone() {
        if (framesLeft.get() <= 0 && stillsLeft.get() <= 0 && burstRunning) {
            burstRunning = false
            onBurstDone()
        }
    }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(executor) { image: ImageProxy ->
                try {
                    val tick = frameTick.incrementAndGet()
                    if (framesLeft.get() > 0) {
                        // Burst: every third frame, so hand movement and focus vary a little.
                        if (tick % 3 == 0) {
                            val g = Images.fromYuv(image)
                            if (framesLeft.getAndDecrement() > 0) mainExecutor.execute { onFrame(g); collected++; finishIfDone() }
                        }
                    } else if (tick % 3 == 0 && checking.compareAndSet(false, true)) {
                        // Live alignment + focus check on a downscaled frame.
                        val g = Images.fromYuv(image)
                        val s = guide.evaluate(g)
                        mainExecutor.execute { status = s; checking.set(false) }
                    }
                } catch (e: Exception) {
                    checking.set(false)
                } finally {
                    image.close()
                }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            } catch (e: Exception) {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, mainExecutor)
        onDispose {
            try { future.get().unbindAll() } catch (_: Exception) {}
            executor.shutdown()
        }
    }

    // Track how long we've been not-ready, to offer an override.
    val ready = status?.ready == true
    LaunchedEffect(ready) { if (!ready) notReadySince = SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            overrideOk = !ready && SystemClock.elapsedRealtime() - notReadySince > OVERRIDE_AFTER_MS
        }
    }
    // Refocus on the guide while it's blurry.
    LaunchedEffect(status?.issue) {
        while (status?.issue == CaptureGuide.Issue.BLURRY) {
            val w = previewView.width.toFloat(); val h = previewView.height.toFloat()
            if (w > 0 && h > 0) {
                val g = guide.guide
                val p = previewView.meteringPointFactory.createPoint(w * (g.left + g.right) / 2, h * (g.top + g.bottom) / 2)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
            }
            delay(2000)
        }
    }

    fun takeStill() {
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val g = try { Images.fromJpeg(image) } finally { image.close() }
                mainExecutor.execute {
                    if (g != null) { onFrame(g); collected++ }
                    stillsLeft.decrementAndGet(); finishIfDone()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute { stillsLeft.decrementAndGet(); finishIfDone() }
            }
        })
    }

    fun startBurst() {
        if (burstRunning) return
        collected = 0
        burstRunning = true
        framesLeft.set(BURST_FRAMES)
        stillsLeft.set(STILLS)
        takeStill()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize()) { pv ->
            pv.setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP) {
                    val point = pv.meteringPointFactory.createPoint(ev.x, ev.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    v.performClick()
                }
                true
            }
        }
        GuideOverlay(guide, status, burstRunning)
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(12.dp)) {
            val s = status
            Text(
                if (burstRunning) "Capturing… hold still" else s?.message ?: "Point at the label: fit the barcode inside the box",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
            if (!burstRunning && s != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Indicator("Lined up", s.aligned)
                    Indicator("In focus", s.focused)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(16.dp)) {
            if (burstRunning) {
                LinearProgressIndicator({ collected / (BURST_FRAMES + STILLS).toFloat() }, Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                    modifier = Modifier.size(64.dp),
                ) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { startBurst() },
                    enabled = !burstRunning && ready,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                ) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            burstRunning -> "Capturing $collected…"
                            ready -> "Burst capture"
                            else -> "Line up the label"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (!burstRunning && !ready && overrideOk) {
                TextButton(onClick = { startBurst() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Capture anyway (barcode missing or badly damaged)", color = Color.White)
                }
            }
            if (!burstRunning && collected > 0) {
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onBurstDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Use these $collected frames") }
            }
        }
    }
    // One more still near the middle of the burst, for a second sharp full-resolution view.
    LaunchedEffect(collected, burstRunning) {
        if (burstRunning && collected == BURST_FRAMES / 2) takeStill()
    }
}

@Composable
private fun Indicator(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (ok) Color(0xFF66BB6A) else Color(0xFFEF5350), RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The guide box over the preview (the frame is 3:4 portrait, shown fit-centered), the area
 * outside it dimmed, its border colored by readiness, and the barcode the check found.
 */
@Composable
private fun GuideOverlay(guide: CaptureGuide, status: CaptureGuide.Status?, burst: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val vw = size.width; val vh = size.height
            // Fit a 3:4 frame in the view, like PreviewView.FIT_CENTER.
            val scale = min(vw / 3f, vh / 4f)
            val fw = 3f * scale; val fh = 4f * scale
            val ox = (vw - fw) / 2; val oy = (vh - fh) / 2
            val g = guide.guide
            val gl = ox + g.left * fw; val gt = oy + g.top * fh
            val gr = ox + g.right * fw; val gb = oy + g.bottom * fh
            val hole = Path().apply { addRect(androidx.compose.ui.geometry.Rect(gl, gt, gr, gb)) }
            clipPath(hole, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.45f)) }
            val color = when {
                burst || status?.ready == true -> Color(0xFF66BB6A)
                status?.aligned == true -> Color(0xFFFFC107)
                else -> Color(0xFFEF5350)
            }
            drawRoundRect(color, Offset(gl, gt), GSize(gr - gl, gb - gt), CornerRadius(16f, 16f), style = Stroke(6f))
            // Where the printed number should sit: the lower part of the box.
            val numY = gt + (gb - gt) * 0.78f
            drawLine(color.copy(alpha = 0.6f), Offset(gl + 24f, numY), Offset(gr - 24f, numY), strokeWidth = 3f)
            status?.barcode?.let { b ->
                drawRect(
                    Color.White.copy(alpha = 0.8f), Offset(ox + b.left * fw, oy + b.top * fh),
                    GSize((b.right - b.left) * fw, (b.bottom - b.top) * fh), style = Stroke(3f),
                )
            }
        }
    }
}
