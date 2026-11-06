package com.clockout.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Clock Out's own camera: one window that takes all the pictures ([count], 1 or 2) in a row, then hands back the
 * files. With two, the second is taken from a slightly different angle, so glare and the screen's pixel pattern fall
 * differently and the two readings check each other.
 */
@Composable
fun RingCamera(files: List<File>, count: Int, onDone: (List<File>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) allowed = true else onCancel() }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) {
                val owner = LocalLifecycleOwner.current
                val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
                var taken by remember { mutableStateOf(0) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
                DisposableEffect(Unit) {
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        }.onFailure { error = "Couldn't open the camera: ${it.message}" }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose { runCatching { future.get().unbindAll() } }
                }
                AndroidView({ view }, Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✕", color = Color.White, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(10.dp))
                        Text(
                            if (count == 1) "Fill the picture with your rings, straight on, no glare"
                            else if (taken == 0) "Picture 1 of 2: straight on, rings filling the picture"
                            else "Picture 2 of 2: tilt a little or step to the side",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 6.dp),
                        )
                    }
                    error?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 8.dp)) }
                    Box(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        // The shutter: a white ring, filled while a picture is being saved.
                        Box(
                            Modifier.size(78.dp).clip(CircleShape).border(5.dp, Color.White, CircleShape)
                                .background(if (busy) Color.White.copy(alpha = 0.6f) else Color.Transparent)
                                .clickable(enabled = !busy) {
                                    busy = true
                                    val f = files[taken].apply { delete() }
                                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                                busy = false
                                                taken++
                                                if (taken >= count) onDone(files.take(count))
                                            }
                                            override fun onError(e: ImageCaptureException) { busy = false; error = "Couldn't take the picture: ${e.message}" }
                                        })
                                },
                        )
                    }
                    if (count == 2 && taken == 1) Text("Use just the first", color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onDone(files.take(1)) }.padding(8.dp))
                }
            }
        }
    }
}
