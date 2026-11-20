package com.labelrescue.app.camera

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labelrescue.app.pipeline.Images
import com.labelrescue.core.image.GrayImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Frames to collect in one burst from the analysis stream (plus [STILLS] full-resolution stills). */
const val BURST_FRAMES = 12
const val STILLS = 2

/**
 * Live camera with a burst mode: grabs [BURST_FRAMES] frames from the stream (every few frames,
 * so hand movement and focus vary a little) and [STILLS] full-resolution stills.
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
    var collected by remember { mutableIntStateOf(0) }
    var burstRunning by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
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
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(executor) { image: ImageProxy ->
                try {
                    // Every third frame while a burst is running.
                    if (framesLeft.get() > 0 && frameTick.incrementAndGet() % 3 == 0) {
                        val g = Images.fromYuv(image)
                        if (framesLeft.getAndDecrement() > 0) {
                            mainExecutor.execute { onFrame(g); collected++; finishIfDone() }
                        }
                    }
                } finally {
                    image.close()
                }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            } catch (e: Exception) {
                // Some devices can't run three use cases: fall back to preview + analysis.
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, mainExecutor)
        onDispose {
            try { future.get().unbindAll() } catch (_: Exception) {}
            executor.shutdown()
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
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(16.dp)) {
            Text(
                "Fill the frame with the barcode and the number below it. Tap to focus. Hold still during the burst.",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
            if (burstRunning) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator({ collected / (BURST_FRAMES + STILLS).toFloat() }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                    modifier = Modifier.size(64.dp),
                ) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        if (burstRunning) return@Button
                        collected = 0
                        burstRunning = true
                        framesLeft.set(BURST_FRAMES)
                        stillsLeft.set(STILLS)
                        takeStill()
                    },
                    enabled = !burstRunning,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                ) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp))
                    Text(if (burstRunning) "Capturing $collected…" else "Burst capture", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!burstRunning && collected > 0) {
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onBurstDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Use these $collected frames") }
            }
        }
    }
    // One more still near the end of the burst, for a second sharp full-resolution view.
    LaunchedEffect(collected, burstRunning) {
        if (burstRunning && collected == BURST_FRAMES / 2) takeStill()
    }
}
