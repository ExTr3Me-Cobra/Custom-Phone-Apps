package com.vipervision.editor

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Try all: one AI tool's every model run on a screen-sized copy of the photo, to flip between
 * and keep the best; the kept one is then run on the full-size photo.
 */
class AiTrial(val tool: AiTool, val original: Bitmap, val results: List<Pair<AiChoice, Bitmap>>) {
    /** -1 = the original. */
    var shown by mutableIntStateOf(0)
}

@Composable
internal fun AiTrialScreen(trial: AiTrial, onCancel: () -> Unit, onKeep: (AiChoice) -> Unit) {
    val index = trial.shown
    val picture = if (index < 0) trial.original else trial.results[index].second
    StudioFrame(
        title = trial.tool.label,
        shown = picture,
        original = trial.original,
        photoWidth = picture.width,
        photoHeight = picture.height,
        onCancel = onCancel,
        onApply = { if (index >= 0) onKeep(trial.results[index].first) else onCancel() }
    ) {
        StudioHint(
            "${trial.results[index].first.title}: ${trial.results[index].first.about}"
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trial.results.forEachIndexed { i, (choice, _) ->
                Pill(choice.title, selected = index == i) { trial.shown = i }
            }
        }
        StudioHint("Hold the photo to see the original. Tap ✓ to keep the one shown (it's then made at full size).")
    }
}
