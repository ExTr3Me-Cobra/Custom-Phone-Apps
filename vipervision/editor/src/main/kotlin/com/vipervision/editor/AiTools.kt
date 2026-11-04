package com.vipervision.editor

import com.t8rin.imagetoolbox.core.resources.icons.Fullscreen
import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.t8rin.neural_tools.bgremover.BgRemover
import androidx.compose.ui.graphics.vector.ImageVector
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.AutoFixHigh
import com.t8rin.imagetoolbox.core.resources.icons.BrightnessAuto
import com.t8rin.imagetoolbox.core.resources.icons.Face6
import com.t8rin.imagetoolbox.core.resources.icons.Jpg
import com.t8rin.imagetoolbox.core.resources.icons.Person
import com.t8rin.imagetoolbox.feature.ai_tools.domain.AiProgressListener
import com.t8rin.imagetoolbox.feature.ai_tools.domain.AiToolsRepository
import com.t8rin.imagetoolbox.feature.ai_tools.domain.model.NeuralModel
import com.t8rin.imagetoolbox.feature.ai_tools.domain.model.NeuralParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * One model offered for an AI tool. [title] and [about] tell the user what it's best at; the
 * download size comes from Image Toolbox's model list.
 */
class AiChoice(val modelName: String, val title: String, val about: String) {
    val model: NeuralModel get() = NeuralModel.entries.first { it.name == modelName }

    val downloadSizeMb: Int get() = (model.downloadSize / 1_000_000).toInt().coerceAtLeast(1)

    /** U2NetP comes with the app, so it never needs a download. */
    val isBundled: Boolean get() = modelName == "u2netp.onnx"
}

/**
 * The AI bubbles (sparkle tab). Each offers the one or two models regarded as best for the job,
 * from Image Toolbox's model library plus CodeFormer, which Viper Vision adds. Models download the
 * first time they're used.
 */
enum class AiTool(val label: String, val description: String, val choices: List<AiChoice>) {
    RestoreFaces(
        "Restore faces", "Finds the faces and repairs blurry, low-quality or old ones.",
        listOf(
            AiChoice(
                "gfpgan_v1.4.onnx", "GFPGAN 1.4 (natural)",
                "Keeps people looking like themselves. Best for faces that are just soft or a bit " +
                        "blurry. The long-time standard."
            ),
            AiChoice(
                "codeformer.onnx", "CodeFormer (heavy repair)",
                "Rebuilds faces that are very blurry, tiny or damaged, like old scans. Can change " +
                        "fine features a little on faces that were already fine."
            )
        )
    ),
    Denoise(
        "Denoise", "Removes grain and noise, e.g. from night or indoor shots.",
        listOf(
            AiChoice(
                "scunet_color-GAN.onnx", "SCUNet Real (natural)",
                "Made for real camera noise. Removes it while keeping texture like skin, hair and " +
                        "fabric, so the photo doesn't look plastic."
            ),
            AiChoice(
                "scunet_color-PSNR.onnx", "SCUNet Smooth (cleanest)",
                "Removes the most noise for the cleanest result, but smooths fine texture more."
            )
        )
    ),
    Deblur(
        "Deblur", "Sharpens blur from shaky hands or slight misfocus.",
        listOf(
            AiChoice(
                "deblurring_nafnet_2025may.onnx", "NAFNet (motion blur)",
                "One of the best deblurring models. Fixes blur from a moving camera or subject."
            ),
            AiChoice(
                "1x-Fatality-DeBlur.onnx", "Fatality (soft focus)",
                "Smaller and faster. Firms up photos that are evenly soft or slightly out of focus."
            )
        )
    ),
    JpegCleanup(
        "JPEG cleanup", "Removes the blocks and smudges of heavy compression (e.g. saved from chats).",
        listOf(
            AiChoice(
                "fbcnn_color_fp16.onnx", "FBCNN (best)",
                "Works out how badly the photo was compressed and undoes exactly that much."
            ),
            AiChoice(
                "1x_SBDV-DeJPEG-Lite_130000_G.ort", "DeJPEG Lite (fast)",
                "Much smaller and quicker. Good for mild compression."
            )
        )
    ),
    LowLight(
        "Low light", "Brightens dark, underexposed photos naturally.",
        listOf(
            AiChoice(
                "zero_dcepp.onnx", "Zero-DCE++",
                "Tiny and instant. Lifts dark areas with smooth curves, without blowing out the sky."
            )
        )
    ),
    FixExposure(
        "Fix exposure", "Recovers washed-out, overexposed photos.",
        listOf(
            AiChoice(
                "1xOverExposureCorrection_compact-fp16.onnx", "Overexposure Correction",
                "Small and fast. Brings back colour and detail in too-bright photos."
            )
        )
    ),
    Upscale(
        "AI upscale", "Makes the photo bigger with AI detail (for small or cropped photos).",
        listOf(
            AiChoice(
                "x4-UltraSharpV2_Lite_fp16_op17.ort", "UltraSharp V2 Lite 4× (sharpest)",
                "Four times the size with crisp edges and fine detail. Small download, takes a little while."
            ),
            AiChoice(
                "RealESRGAN-x4v3.ort", "Real-ESRGAN 4× (quick)",
                "Four times the size, fast and tiny. A little softer on fine detail."
            ),
            AiChoice(
                "RealESRGAN_x2plus.ort", "Real-ESRGAN 2× (natural)",
                "Twice the size with a natural look, for photos that only need a bit more."
            )
        )
    ),
    RemoveBackground(
        "Auto background remover", "Cuts out the subject and makes the background transparent.",
        listOf(
            AiChoice(
                "u2netp.onnx", "U2NetP (instant)",
                "Built in, no download. Fine for clear subjects on simple backgrounds."
            ),
            AiChoice(
                "birefnet_swin_tiny.ort", "BiRefNet (best edges)",
                "The most accurate: clean edges around hair, fur and fine details. For more " +
                        "control, use Manual background remover."
            )
        )
    );

    val icon: ImageVector
        get() = when (this) {
            RestoreFaces -> Icons.Outlined.Face6
            LowLight -> Icons.Outlined.BrightnessAuto
            FixExposure -> EditorIcons.Exposure
            Denoise -> Icons.Rounded.AutoFixHigh
            Deblur -> EditorIcons.Sharpness
            JpegCleanup -> Icons.Outlined.Jpg
            RemoveBackground -> Icons.Rounded.Person
            Upscale -> Icons.Rounded.Fullscreen
        }
}

/** Runs AI tools through Image Toolbox's AI tools repository (the same engine as its AI Tools screen). */
class AiRunner(private val repository: AiToolsRepository<Bitmap>) {

    val downloadedModels get() = repository.downloadedModels

    /**
     * Frees the loaded AI models. Each holds hundreds of MB to GBs of native memory; kept loaded,
     * they ran the phone so low that the photo couldn't be drawn (a black screen, especially
     * zoomed in). The next run loads its model again.
     */
    fun release() {
        runCatching { repository.cleanup() }
        runCatching { com.t8rin.neural_tools.inpaint.LaMaProcessor.releaseSession() }
    }

    fun isReady(choice: AiChoice): Boolean =
        choice.isBundled || repository.downloadedModels.value.any { it.name == choice.modelName }

    /** Downloads the model if needed, reporting progress from 0 to 1. */
    suspend fun download(choice: AiChoice, onProgress: (Float) -> Unit) {
        if (isReady(choice) && !choice.isBundled) return
        repository.downloadModel(choice.model).collect { onProgress(it.currentPercent) }
    }

    /** Runs the model on [image] and returns the result. Throws with a readable message on failure. */
    suspend fun run(choice: AiChoice, image: Bitmap, onProgress: (done: Int, total: Int) -> Unit): Bitmap {
        val model = choice.model
        if (model.type == NeuralModel.Type.REMOVE_BG) return removeBackground(choice, image)
        repository.selectModel(model, forced = true)
        // The selection is stored in settings and read back; wait until it has landed.
        withTimeout(10_000) { repository.selectedModel.first { it?.name == model.name } }

        var failure: String? = null
        val result = repository.processImage(
            image = image,
            listener = object : AiProgressListener {
                override fun onError(error: String) {
                    failure = error
                }

                override fun onProgress(currentChunkIndex: Int, totalChunks: Int) {
                    onProgress(currentChunkIndex, totalChunks)
                }
            },
            // Image Toolbox's default settings for its AI tools; full strength for the models
            // that have a strength setting (face restoration blends by it).
            params = NeuralParams(
                strength = if (model.isFaceRestoration) 100f else 65f,
                chunkSize = 512,
                overlap = 16,
                enableChunking = !model.isNonChunkable
            )
        )
        return result ?: error(failure ?: "${choice.title} didn't work on this photo")
    }

    /**
     * Remove background, light on memory: the model only ever sees a small copy (it works at
     * 320–1024 px anyway), and its cut-out shape is scaled up and applied to the full photo in
     * one pass. Image Toolbox's own path keeps several full-size copies, which runs out of
     * memory on big photos (e.g. 50–200 MP) and closes the editor.
     */
    private suspend fun removeBackground(choice: AiChoice, image: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val type = when {
            choice.modelName.startsWith("u2netp") -> BgRemover.Type.U2NetP
            choice.modelName.startsWith("birefnet_swin_tiny") -> BgRemover.Type.BiRefNetTiny
            else -> error("${choice.title} isn't a background remover")
        }
        val scale = min(1f, 1024f / max(image.width, image.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(image, max(1, (image.width * scale).roundToInt()), max(1, (image.height * scale).roundToInt()), true)
        } else image
        ViperLog.i("BG", "Model $type on ${ViperLog.describe(small)} (full photo ${image.width}×${image.height})")
        val cut = try {
            ViperLog.step("BG", "$type model") { BgRemover.removeBackground(small, type).getOrThrow() }
        } finally {
            // BiRefNet's session holds gigabytes of native memory; free it straight away instead
            // of keeping it for a next run (U2NetP is tiny, keep it).
            if (type == BgRemover.Type.BiRefNetTiny) {
                BgRemover.getRemover(type).close()
                ViperLog.i("BG", "Closed $type to free its memory. ${ViperLog.memory()}")
            }
        }
        ViperLog.i("BG", "Model result ${ViperLog.describe(cut)}, ${ViperLog.transparency(cut)}")
        if (small !== image) small.recycle()
        // The cut-out shape, stretched to the full photo, then copied onto it one row at a time.
        // (No alpha-only bitmap: drawing one through DST_IN left the photo fully opaque on a
        // Galaxy phone — the diagnostic log showed the model's 70% transparent result become 0%.)
        val shape = Bitmap.createScaledBitmap(cut, image.width, image.height, true)
        if (shape !== cut) cut.recycle()
        val result = image.copy(Bitmap.Config.ARGB_8888, true) ?: error("not enough memory")
        result.setHasAlpha(true)
        val row = IntArray(image.width)
        val alphaRow = IntArray(image.width)
        for (y in 0 until image.height) {
            result.getPixels(row, 0, image.width, 0, y, image.width, 1)
            shape.getPixels(alphaRow, 0, image.width, 0, y, image.width, 1)
            for (x in row.indices) {
                row[x] = (row[x] and 0x00FFFFFF) or (alphaRow[x] and -0x1000000)
            }
            result.setPixels(row, 0, image.width, 0, y, image.width, 1)
        }
        shape.recycle()
        result
    }
}
