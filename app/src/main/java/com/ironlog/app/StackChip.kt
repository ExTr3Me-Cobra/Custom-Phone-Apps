package com.ironlog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

/**
 * Cable settings next to the exercise, saved per exercise: the pulley ratio (1:1 to 4:1; tap to change), and for a
 * both-arm move 1 or 2 stacks (both hands on one stack, or a handle on each of two). You always log the number on one
 * stack; ranks count it × your stacks ÷ your pulley ratio.
 */
@Composable
fun StackChip(e: Exercise, modifier: Modifier = Modifier) {
    if (e.equipment != "Cable") return
    val p by Store.profile.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val r = p.pulleyFor(e)
        val pm = Modifier.clip(RoundedCornerShape(8.dp)).background(if (r != 1) Blue.copy(alpha = 0.35f) else Card)
            .clickable { Store.setProfile(Store.profile.value.withPulley(e.id, r % 4 + 1)) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
        if (r != 1) OutlinedLabel("Pulley $r:1", 13.sp, pm, FontWeight.Normal) else Text("Pulley $r:1", pm, color = Sub, fontSize = 13.sp)
        if (e.hasStackChoice()) {
            val n = p.stacksFor(e)
            Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Card), verticalAlignment = Alignment.CenterVertically) {
                for (k in 1..2) {
                    val label = if (k == 1) "1 stack" else "2 stacks"
                    val sm = Modifier.clickable { Store.setProfile(Store.profile.value.withStacks(e.id, k)) }
                        .background(if (n == k) Blue.copy(alpha = 0.35f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                    if (n == k) OutlinedLabel(label, 13.sp, sm, FontWeight.Normal) else Text(label, sm, color = Sub, fontSize = 13.sp)
                }
            }
        }
    }
}
