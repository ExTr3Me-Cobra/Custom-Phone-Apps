package com.vipervision.editor

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A scrolling row of tool bubbles that can be rearranged: hold one until it lifts, then drag it
 * along (the row scrolls when it nears an edge). The order is remembered under [prefKey]; bubbles
 * added later go at the end. A quick tap still works as usual.
 */
@Composable
internal fun ReorderableBubbleRow(
    prefKey: String,
    bubbles: List<Pair<String, @Composable () -> Unit>>,
    spacing: Dp = 8.dp,
    sidePadding: Dp = 16.dp
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val keys = bubbles.map { it.first }
    val byKey = bubbles.toMap()
    val prefs = remember { context.getSharedPreferences("Prefs", Context.MODE_PRIVATE) }
    val order = remember(keys) {
        val saved = prefs.getString(prefKey, null)?.split(',')?.filter { it in keys }.orEmpty()
        mutableStateListOf(*(saved + keys.filter { it !in saved }).toTypedArray())
    }
    val scroll = rememberScrollState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var itemWidth by remember { mutableFloatStateOf(with(density) { 80.dp.toPx() }) }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    val slot = itemWidth + with(density) { spacing.toPx() }
    val side = with(density) { sidePadding.toPx() }
    val edge = with(density) { 56.dp.toPx() }

    fun moveBy(dx: Float) {
        val key = dragging ?: return
        offset += dx
        var i = order.indexOf(key)
        while (offset > slot / 2 && i < order.lastIndex) {
            order.add(i + 1, order.removeAt(i)); i++; offset -= slot
        }
        while (offset < -slot / 2 && i > 0) {
            order.add(i - 1, order.removeAt(i)); i--; offset += slot
        }
    }

    // While holding near an edge, scroll that way (the bubble stays under the finger).
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val key = dragging ?: break
            val i = order.indexOf(key)
            val x = side + i * slot + offset - scroll.value
            val step = when {
                rowWidth <= 0f -> 0f
                x < edge * 0.5f && scroll.value > 0 -> -10f
                x + itemWidth > rowWidth - edge * 0.5f && scroll.value < scroll.maxValue -> 10f
                else -> 0f
            }
            if (step != 0f) {
                val used = scroll.dispatchRawDelta(step)
                moveBy(used)
            }
        }
    }

    Row(
        Modifier
            .onSizeChanged { rowWidth = it.width.toFloat() }
            .horizontalScroll(scroll)
            .padding(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        for (key in order) {
            val content = byKey[key] ?: continue
            // Keyed, so a bubble keeps its own state (and the hold on it) as it moves along.
            androidx.compose.runtime.key(key) {
            val lifted = dragging == key
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .onSizeChanged { if (it.width > 0) itemWidth = it.width.toFloat() }
                    .graphicsLayer {
                        translationX = if (lifted) offset else 0f
                        val s = if (lifted) 1.12f else 1f
                        scaleX = s
                        scaleY = s
                        alpha = if (lifted) 0.92f else 1f
                    }
                    .pointerInput(key) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // A tap or a scroll isn't ours; only a hold that stays put.
                            val notHeld = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                        ?: return@withTimeoutOrNull true
                                    if (!change.pressed) return@withTimeoutOrNull true
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                                }
                                @Suppress("UNREACHABLE_CODE") true
                            }
                            if (notHeld != null) return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragging = key
                            offset = 0f
                            try {
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    moveBy(change.positionChange().x)
                                    change.consume()
                                }
                            } finally {
                                // However the hold ends, the bubble drops into its place.
                                dragging = null
                                offset = 0f
                                prefs.edit().putString(prefKey, order.joinToString(",")).apply()
                            }
                        }
                    }
            ) { content() }
            }
        }
    }
}
