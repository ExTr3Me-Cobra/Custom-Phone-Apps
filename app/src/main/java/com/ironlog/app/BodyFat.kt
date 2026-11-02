package com.ironlog.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Body fat, picked from five pictures (assets/bodyfat, m1–m5 and f1–f5). Strength follows lean mass, so ranks compare you
 * as if you weighed a little less (more fat) or a little more (leaner) than you do; picture 3 is the typical lifter and
 * changes nothing. The adjustment is half of the full lean-mass difference, because extra mass also helps some lifts.
 * Bodyweight moves are the other way round: fat is extra load to move, so fewer reps are expected.
 */
object BodyFat {
    val MEN = listOf("6–12%", "13–17%", "18–24%", "25–31%", "32%+")
    val WOMEN = listOf("14–20%", "21–24%", "25–31%", "32–38%", "39%+")
    private val MID_MEN = doubleArrayOf(0.09, 0.15, 0.21, 0.28, 0.35)
    private val MID_WOMEN = doubleArrayOf(0.17, 0.225, 0.28, 0.35, 0.42)

    fun ranges(male: Boolean) = if (male) MEN else WOMEN
    /** The range text for your pick, or null when not set. */
    fun label(p: Profile) = p.bodyFat?.let { ranges(p.male).getOrNull(it - 1) }

    /** Your lean mass compared with the typical lifter's (picture 3) at the same body weight; 1 = no change. */
    private fun lean(p: Profile): Double {
        val i = (p.bodyFat ?: return 1.0) - 1
        val mid = if (p.male) MID_MEN else MID_WOMEN
        if (i !in mid.indices) return 1.0
        return (1 - mid[i]) / (1 - mid[2])
    }

    /**
     * The body weight to read the standards at. Lifts: weight × lean^0.5 (half the lean-mass difference).
     * Bodyweight moves: strength as above but the full weight to move, which is the same as reading the reps table
     * at weight × lean^-1 (reps fall with body weight as strength^1 / load, strength ∝ weight^0.67).
     */
    fun compareWeight(p: Profile, bw: Double, reps: Boolean): Double {
        val l = lean(p)
        if (l == 1.0) return bw
        val f = if (reps) Math.pow(l, -1.0) else Math.sqrt(l)
        return bw * f.coerceIn(0.75, 1.35)
    }

    private val cache = HashMap<String, ImageBitmap>()
    fun image(c: Context, male: Boolean, n: Int): ImageBitmap? {
        val key = (if (male) "m" else "f") + n
        return cache[key] ?: runCatching {
            c.assets.open("bodyfat/$key.webp").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }.getOrNull()?.also { cache[key] = it }
    }
}

/** One picture with its number and body-fat range. */
@Composable
private fun BodyFatRow(male: Boolean, n: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Blue.copy(alpha = 0.18f) else Card)
            .then(if (selected) Modifier.border(2.dp, Blue, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyFat.image(c, male, n)?.let {
            Box(Modifier.width(96.dp).height(76.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                Image(it, null, Modifier.fillMaxWidth().height(76.dp), contentScale = ContentScale.Fit)
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("Body fat ${BodyFat.ranges(male)[n - 1]}", fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp)
        }
    }
}

/** Setup: the five pictures, tap the one that looks most like you. */
@Composable
fun BodyFatPicker(p: Profile, onPick: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Body fat", fontWeight = FontWeight.Bold)
        Text("Choose by picture, or by body fat percentage if you know it. Ranks allow for it, since strength comes from lean muscle.",
            color = Sub, fontSize = 13.sp)
        for (n in 1..5) BodyFatRow(p.male, n, p.bodyFat == n) { onPick(if (p.bodyFat == n) null else n) }
    }
}

/** Settings: the same choice as a drop-down. */
@Composable
fun BodyFatDropdown(p: Profile, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Body fat", fontWeight = FontWeight.Bold)
        Box {
            GreyButton((BodyFat.label(p)?.let { "Body fat $it" } ?: "Not set (no adjustment)") + "  ▾") { open = true }
            DropdownMenu(open, { open = false }) {
                for (n in 1..5) DropdownMenuItem(
                    { Box(Modifier.width(280.dp)) { BodyFatRow(p.male, n, p.bodyFat == n) { onPick(n); open = false } } },
                    { onPick(n); open = false },
                )
                DropdownMenuItem({ Text("Not set (no adjustment)") }, { onPick(null); open = false })
            }
        }
    }
}
