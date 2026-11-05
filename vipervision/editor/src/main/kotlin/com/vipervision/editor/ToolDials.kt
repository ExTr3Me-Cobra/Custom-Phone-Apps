package com.vipervision.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One setting of a tool screen, shown as a round bubble (like the Adjust tab's): [value] runs
 * -100..100 when [twoSided], else 0..100.
 */
class Dial(
    val label: String,
    val icon: ImageVector,
    val value: Int,
    val twoSided: Boolean = false,
    val onChange: (Int) -> Unit
)

/**
 * A tool screen's settings the Adjust tab's way: a row of bubbles (each showing its value as a
 * wedge) and one wheel below for the picked one. Tapping the picked bubble again sets it to 0.
 */
@Composable
internal fun DialPanel(dials: List<Dial>, modifier: Modifier = Modifier) {
    if (dials.isEmpty()) return
    var picked by rememberSaveable { mutableIntStateOf(0) }
    val index = picked.coerceIn(0, dials.lastIndex)
    val current = dials[index]
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(dials, key = { _, d -> d.label }) { i, dial ->
                DialBubble(dial, selected = i == index) {
                    if (i == index && dial.value != 0) dial.onChange(0) else picked = i
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ValueWheel(
            value = current.value,
            onValueChange = { current.onChange(if (current.twoSided) it else it.coerceAtLeast(0)) },
            onValueChangeFinished = {},
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
private fun DialBubble(dial: Dial, selected: Boolean, onClick: () -> Unit) {
    val accent = Accent
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(if (selected) Color.White else BubbleGrey)
                if (dial.value != 0) {
                    drawArc(
                        color = if (selected) accent else BubbleWedge,
                        startAngle = -90f,
                        sweepAngle = dial.value / 100f * 360f,
                        useCenter = true
                    )
                }
            }
            if (selected) {
                Text(dial.value.toString(), color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(dial.icon, contentDescription = dial.label, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            dial.label,
            color = if (selected) Accent else Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
