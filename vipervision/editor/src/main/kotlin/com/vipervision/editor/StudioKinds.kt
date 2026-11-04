package com.vipervision.editor

import androidx.compose.ui.graphics.vector.ImageVector
import com.t8rin.imagetoolbox.core.resources.Icons
import com.t8rin.imagetoolbox.core.resources.icons.Face6
import com.t8rin.imagetoolbox.core.resources.icons.LightMode
import com.t8rin.imagetoolbox.core.resources.icons.TextFields
import com.t8rin.imagetoolbox.core.resources.icons.TouchApp
import com.t8rin.imagetoolbox.core.resources.icons.Visibility
import com.t8rin.neural_tools.bgremover.Sam2
import com.t8rin.neural_tools.depth.DepthAnything
import com.t8rin.neural_tools.inpaint.LaMaProcessor
import com.t8rin.neural_tools.ocr.PaddleOCR
import com.t8rin.neural_tools.viper.FaceParser
import com.t8rin.neural_tools.viper.YuNet

/** The AI tab's newer tools, each with its bubble; Red eye works in one tap, the rest open a screen. */
enum class StudioKind(val label: String, val downloadMb: Int) {
    Selective("Selective adjust", 130),
    RemoveText("Remove text", 217),
    Relight("Relight", 99),
    FaceRetouch("Face retouch", 54),
    RedEye("Red eye", 1);

    val icon: ImageVector
        get() = when (this) {
            Selective -> Icons.Rounded.TouchApp
            RemoveText -> Icons.Rounded.TextFields
            Relight -> Icons.Outlined.LightMode
            FaceRetouch -> Icons.Outlined.Face6
            RedEye -> Icons.Rounded.Visibility
        }

    /** Whether its AI models are all downloaded. */
    fun ready(): Boolean = runCatching {
        when (this) {
            Selective -> Sam2.isDownloaded()
            RemoveText -> PaddleOCR.isModelDownloaded(PaddleOCR.Model.English) && LaMaProcessor.isDownloaded.value
            Relight -> DepthAnything.isDownloaded()
            FaceRetouch -> YuNet.isDownloaded() && FaceParser.isDownloaded()
            RedEye -> YuNet.isDownloaded()
        }
    }.getOrDefault(false)
}
