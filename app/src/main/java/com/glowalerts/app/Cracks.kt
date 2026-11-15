package com.glowalerts.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The lines of one crack pattern, as straight segments. Each segment knows how far along the
 * crack it starts ([dist]), so the crack can spread out from where it began.
 */
class CrackShape(
    /** x1, y1, x2, y2 for each segment. */
    val points: FloatArray,
    /** Distance from the starting point at which each segment begins. */
    val dist: FloatArray,
    val length: FloatArray,
    /** 0 = main crack, 1 = branch, 2 = small twig (drawn thinner). */
    val level: IntArray,
    val maxDist: Float,
    val originX: Float,
    val originY: Float,
) {
    val count get() = dist.size
}

/** Builds the crack patterns. Every call with a new seed gives a different crack. */
object Cracks {
    /**
     * Lightning with the phone sideways: drawn exactly as it would be on a landscape screen
     * (top of the view down to the bottom middle), then turned to match how the phone is held.
     * "Top", "left" and so on then mean as you see them.
     *
     * Left side down: view x = screen y, view y = w - screen x.
     * Right side down: view x = h - screen y, view y = screen x.
     */
    private fun sideways(
        w: Float, h: Float, origin: CrackOrigin, detail: Int, seed: Long, camX: Float, camY: Float,
        jagged: Float, branchLength: Float, count: Int, landing: Int,
    ): CrackShape {
        val left = landing == Tilt.LEFT
        fun toView(x: Float, y: Float) = if (left) floatArrayOf(y, w - x) else floatArrayOf(h - y, x)
        fun toScreen(u: Float, v: Float) = if (left) floatArrayOf(w - v, u) else floatArrayOf(v, h - u)
        val cam = toView(camX, camY)
        val view = build(
            LightEffect.CRACK_LIGHTNING, h, w, origin, detail, seed, cam[0], cam[1],
            jagged, branchLength, count, Tilt.BOTTOM, landAway = true,
        )
        val pts = FloatArray(view.points.size)
        for (i in 0 until view.count) {
            val a = toScreen(view.points[i * 4], view.points[i * 4 + 1])
            val b = toScreen(view.points[i * 4 + 2], view.points[i * 4 + 3])
            pts[i * 4] = a[0]; pts[i * 4 + 1] = a[1]; pts[i * 4 + 2] = b[0]; pts[i * 4 + 3] = b[1]
        }
        val o = toScreen(view.originX, view.originY)
        return CrackShape(pts, view.dist, view.length, view.level, view.maxDist, o[0], o[1])
    }

    /**
     * How far apart (in pixels) separate lines must stay, so their glows don't visibly touch. Set from the line
     * width before building; 0 = only exact crossings are prevented.
     */
    @Volatile var clearance = 0f

    private class Builder(val w: Float, val h: Float, val rnd: Random, val jag: Float, val branchLen: Float) {
        val clear = clearance
        /** Where the crack being grown started: lines may meet there (a branch leaves its parent). */
        var forkX = Float.NaN
        var forkY = Float.NaN
        /** The crack being grown's last few segments: it naturally runs right next to those, so they don't count. */
        var recent = ArrayDeque<Int>()
        val pts = ArrayList<Float>()
        val dist = ArrayList<Float>()
        val len = ArrayList<Float>()
        val level = ArrayList<Int>()
        val diag = hypot(w, h)
        val unit = min(w, h)

        // A coarse grid of which segments are where, so checking for crossings stays fast.
        private val cell = max(8f, unit * 0.06f)
        private val grid = HashMap<Long, ArrayList<Int>>()
        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        private fun cells(x1: Float, y1: Float, x2: Float, y2: Float, each: (Long) -> Unit) {
            val cx1 = (min(x1, x2) / cell).toInt() - 1
            val cx2 = (max(x1, x2) / cell).toInt() + 1
            val cy1 = (min(y1, y2) / cell).toInt() - 1
            val cy2 = (max(y1, y2) / cell).toInt() + 1
            for (cx in cx1..cx2) for (cy in cy1..cy2) each(key(cx, cy))
        }

        /** Adds a segment unless it would cross a line already drawn. Returns whether it was added. */
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float, d: Float, lvl: Int): Boolean {
            if (crosses(x1, y1, x2, y2)) return false
            val i = dist.size
            pts += x1; pts += y1; pts += x2; pts += y2
            dist += d
            len += hypot(x2 - x1, y2 - y1)
            level += lvl
            cells(x1, y1, x2, y2) { grid.getOrPut(it) { ArrayList() } += i }
            recent.addLast(i)
            while (recent.size > 4) recent.removeFirst()
            return true
        }

        /** True if the new segment would cross (not just touch at a joint) an existing one. */
        private fun crosses(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
            val checked = HashSet<Int>()
            var hit = false
            cells(x1, y1, x2, y2) { k ->
                if (hit) return@cells
                grid[k]?.forEach { j ->
                    if (hit || !checked.add(j)) return@forEach
                    val qx1 = pts[j * 4]; val qy1 = pts[j * 4 + 1]; val qx2 = pts[j * 4 + 2]; val qy2 = pts[j * 4 + 3]
                    if (sharesEnd(x1, y1, x2, y2, qx1, qy1, qx2, qy2)) return@forEach
                    if (intersect(x1, y1, x2, y2, qx1, qy1, qx2, qy2)) { hit = true; return@forEach }
                    // Too close to another line (their glows would touch). Near where this crack leaves its
                    // parent the lines start together, so only real crossings count there.
                    if (clear > 0f && j !in recent) {
                        val nearFork = !forkX.isNaN() && (hypot(x1 - forkX, y1 - forkY) < clear * 2.5f ||
                            min(hypot(qx1 - forkX, qy1 - forkY), hypot(qx2 - forkX, qy2 - forkY)) < clear * 2.5f)
                        if (!nearFork && segDist(x1, y1, x2, y2, qx1, qy1, qx2, qy2) < clear) hit = true
                    }
                }
            }
            return hit
        }

        private fun pointSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        /** Shortest distance between two segments that don't cross. */
        private fun segDist(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float =
            min(min(pointSeg(ax, ay, cx, cy, dx, dy), pointSeg(bx, by, cx, cy, dx, dy)),
                min(pointSeg(cx, cy, ax, ay, bx, by), pointSeg(dx, dy, ax, ay, bx, by)))

        private fun sharesEnd(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
            val e = 0.5f
            fun near(px: Float, py: Float, qx: Float, qy: Float) = abs(px - qx) < e && abs(py - qy) < e
            return near(ax, ay, cx, cy) || near(ax, ay, dx, dy) || near(bx, by, cx, cy) || near(bx, by, dx, dy)
        }

        private fun intersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
            fun orient(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) =
                (qx - px) * (ry - py) - (qy - py) * (rx - px)
            val o1 = orient(ax, ay, bx, by, cx, cy)
            val o2 = orient(ax, ay, bx, by, dx, dy)
            val o3 = orient(cx, cy, dx, dy, ax, ay)
            val o4 = orient(cx, cy, dx, dy, bx, by)
            return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)
        }

        fun inside(x: Float, y: Float, margin: Float = 0f) =
            x >= -margin && y >= -margin && x <= w + margin && y <= h + margin

        /**
         * A jagged crack starting at (x, y) heading at [angle] for up to [length] (stops a little
         * past the screen edge), with random side branches. It never crosses an existing line:
         * it steers round, and stops if it's boxed in. With a target it steers there and stops.
         */
        fun jagged(
            x0: Float, y0: Float, angle: Float, length: Float, d0: Float, lvl: Int,
            branchChance: Float, wobble: Float = 0.55f, tx: Float = Float.NaN, ty: Float = Float.NaN,
        ) {
            val savedFx = forkX; val savedFy = forkY
            val savedRecent = recent
            forkX = x0; forkY = y0
            recent = ArrayDeque()
            var x = x0
            var y = y0
            var a = angle
            var d = d0
            val wob = wobble * jag
            var travelled = 0f
            val step = unit * (if (lvl == 0) 0.035f else 0.025f)
            val homing = !tx.isNaN()
            // Branches are grown after this crack is complete, so they can never block it.
            val forks = ArrayList<FloatArray>()
            val attempts = if (lvl == 0) 14 else 8
            while (travelled < length && inside(x, y, unit * 0.05f)) {
                val heading = if (homing) atan2(ty - y, tx - x) else angle
                if (homing && hypot(tx - x, ty - y) < step * 1.2f) {
                    seg(x, y, tx, ty, d, lvl)
                    break
                }
                // Wander, but keep drifting back to the heading.
                a += (rnd.nextFloat() - 0.5f) * wob + angleDiff(heading, a) * (if (homing) 0.5f else 0.35f)
                val s = step * (0.6f + rnd.nextFloat() * 0.8f)
                var placed = false
                for (attempt in 0 until attempts) {
                    val turn = if (attempt == 0) 0f else ((attempt + 1) / 2) * 0.35f * (if (attempt % 2 == 0) 1 else -1)
                    val aa = a + turn
                    val nx = x + cos(aa) * s
                    val ny = y + sin(aa) * s
                    if (seg(x, y, nx, ny, d, lvl)) {
                        a = aa
                        x = nx
                        y = ny
                        placed = true
                        break
                    }
                }
                if (!placed) break // boxed in: this crack ends here
                d += s
                travelled += s
                if (lvl < 2 && rnd.nextFloat() < branchChance) forks += floatArrayOf(x, y, a, d)
            }
            forkX = savedFx; forkY = savedFy
            recent = savedRecent
            for (f in forks) {
                val side = if (rnd.nextBoolean()) 1 else -1
                val ba = f[2] + side * (0.4f + rnd.nextFloat() * 0.7f)
                val bl = branchLen * unit * (if (lvl == 0) 0.12f + rnd.nextFloat() * 0.18f else 0.04f + rnd.nextFloat() * 0.07f)
                jagged(f[0], f[1], ba, bl, f[3], lvl + 1, branchChance * 0.6f, wobble)
            }
        }

        private fun angleDiff(target: Float, current: Float): Float {
            var diff = (target - current) % (2 * PI.toFloat())
            if (diff > PI) diff -= 2 * PI.toFloat()
            if (diff < -PI) diff += 2 * PI.toFloat()
            return diff
        }

        fun build(ox: Float, oy: Float) = CrackShape(
            pts.toFloatArray(), dist.toFloatArray(), len.toFloatArray(), level.toIntArray(),
            max(1f, (0 until dist.size).maxOfOrNull { dist[it] + len[it] } ?: 1f), ox, oy,
        )
    }

    fun build(
        effect: LightEffect, w: Float, h: Float, origin: CrackOrigin, detail: Int, seed: Long,
        camX: Float, camY: Float, jagged: Float = 1f, branchLength: Float = 1f, count: Int = 1,
        landing: Int = Tilt.BOTTOM,
        /** Lightning only: land in the half of the bottom away from where it starts. */
        landAway: Boolean = false,
    ): CrackShape {
        if (effect == LightEffect.CRACK_LIGHTNING && landing != Tilt.BOTTOM) {
            return sideways(w, h, origin, detail, seed, camX, camY, jagged, branchLength, count, landing)
        }
        val rnd = Random(seed)
        val b = Builder(w, h, rnd, jagged.coerceIn(0.1f, 3f), branchLength.coerceIn(0.2f, 3f))
        val n = count.coerceIn(1, 8)
        // Extra main cracks fan out around the main direction.
        val fan = { i: Int -> if (n == 1) 0f else (i - (n - 1) / 2f) / (n - 1) * 1.1f + (rnd.nextFloat() - 0.5f) * 0.2f }
        val o = if (origin == CrackOrigin.RANDOM) {
            CrackOrigin.entries.filter { it != CrackOrigin.RANDOM }.random(rnd)
        } else {
            origin
        }
        val jitter = { rnd.nextFloat() - 0.5f }
        val (ox, oy) = when (o) {
            CrackOrigin.TOP -> w * (0.5f + jitter() * 0.3f) to 0f
            CrackOrigin.BOTTOM -> w * (0.5f + jitter() * 0.3f) to h
            CrackOrigin.LEFT -> 0f to h * (0.5f + jitter() * 0.3f)
            CrackOrigin.RIGHT -> w to h * (0.5f + jitter() * 0.3f)
            CrackOrigin.CENTER, CrackOrigin.RANDOM -> w * (0.5f + jitter() * 0.1f) to h * (0.5f + jitter() * 0.1f)
            CrackOrigin.TOP_LEFT -> 0f to 0f
            CrackOrigin.TOP_RIGHT -> w to 0f
            CrackOrigin.BOTTOM_LEFT -> 0f to h
            CrackOrigin.BOTTOM_RIGHT -> w to h
            CrackOrigin.CAMERA -> camX to camY
        }
        val toCentre = atan2(h / 2 - oy, w / 2 - ox)
        val centred = o == CrackOrigin.CENTER
        val k = detail.coerceIn(1, 10)

        when (effect) {
            LightEffect.CRACK_QUAKE -> {
                // One big fissure right across the screen, with side cracks.
                val heading = if (centred) (rnd.nextFloat() - 0.5f) * 0.8f else toCentre
                val chance = 0.04f + k * 0.022f
                for (i in 0 until n) {
                    val hd = heading + fan(i)
                    b.jagged(ox, oy, hd, b.diag * 1.2f, 0f, 0, chance, wobble = 0.7f)
                    if (centred) b.jagged(ox, oy, hd + PI.toFloat(), b.diag * 1.2f, 0f, 0, chance, wobble = 0.7f)
                }
            }
            LightEffect.CRACK_SHATTER -> {
                // Cracks bursting out in every direction from the impact point.
                val rays = 5 + k + (n - 1) * 3
                val start = rnd.nextFloat() * 2 * PI.toFloat()
                for (i in 0 until rays) {
                    val a = start + i * 2 * PI.toFloat() / rays + (rnd.nextFloat() - 0.5f) * 0.4f
                    b.jagged(ox, oy, a, b.diag * (0.5f + rnd.nextFloat() * 0.7f), 0f, 0, 0.02f + k * 0.012f)
                }
            }
            LightEffect.CRACK_WEB -> {
                // Spokes out from the impact, joined by rings like a spider's web.
                val rays = 6 + k / 2 + (n - 1) * 2
                val start = rnd.nextFloat() * 2 * PI.toFloat()
                val angles = FloatArray(rays) { start + it * 2 * PI.toFloat() / rays + (rnd.nextFloat() - 0.5f) * 0.3f }
                val reach = b.diag
                for (a in angles) b.jagged(ox, oy, a, reach, 0f, 0, 0.01f, wobble = 0.35f)
                val rings = 2 + k / 2
                val gap = b.unit * (0.07f + 0.03f * (10 - k) / 10f)
                for (r in 1..rings) {
                    val radius = gap * r * (1f + r * 0.18f)
                    for (i in angles.indices) {
                        if (rnd.nextFloat() < 0.15f) continue // a few gaps look more natural
                        val a1 = angles[i]
                        val a2 = angles[(i + 1) % rays].let { if (it < a1) it + 2 * PI.toFloat() else it }
                        val r1 = radius * (0.9f + rnd.nextFloat() * 0.2f)
                        val r2 = radius * (0.9f + rnd.nextFloat() * 0.2f)
                        // Each ring piece sags inwards a little between the spokes.
                        val pieces = 4
                        var px = ox + cos(a1) * r1
                        var py = oy + sin(a1) * r1
                        for (p in 1..pieces) {
                            val f = p / pieces.toFloat()
                            val a = a1 + (a2 - a1) * f
                            val rr = (r1 + (r2 - r1) * f) * (1f - 0.12f * sin(PI.toFloat() * f))
                            val nx = ox + cos(a) * rr
                            val ny = oy + sin(a) * rr
                            b.seg(px, py, nx, ny, radius, 1)
                            px = nx
                            py = ny
                        }
                    }
                }
            }
            LightEffect.CRACK_LIGHTNING -> {
                // A forking bolt that lands at the bottom middle of the screen (as you see it:
                // sideways strikes are drawn turned round, see [sideways]).
                var sx = ox
                var sy = oy
                if (o != CrackOrigin.CAMERA && sy > h * 0.7f) {
                    // Starting at the bottom, where it lands: strike from the top instead.
                    // (From the camera it always starts at the camera.)
                    sx = w * (0.5f + (rnd.nextFloat() - 0.5f) * 0.3f)
                    sy = 0f
                }
                // Where along the bottom it can land: anywhere (just in from the corners), or when
                // sideways, from halfway out to the far end (away from where it starts).
                val (from, to) = when {
                    !landAway -> 0.06f to 0.94f
                    sx < w / 2 -> 0.5f to 0.94f
                    else -> 0.06f to 0.5f
                }
                for (i in 0 until n) {
                    val tx = w * (from + rnd.nextFloat() * (to - from))
                    val ty = h
                    b.jagged(
                        sx, sy, atan2(ty - sy, tx - sx), b.diag * 2f, 0f, 0, 0.1f + k * 0.03f,
                        wobble = 0.9f, tx = tx, ty = ty,
                    )
                }
            }
            LightEffect.CRACK_FAULTS -> {
                // Several roughly parallel cracks crossing the screen.
                val lines = 2 + k / 3 + (n - 1)
                val heading = if (centred) 0f else toCentre
                val nx = -sin(heading)
                val ny = cos(heading)
                val spread = b.unit * 0.9f
                for (i in 0 until lines) {
                    val off = (i - (lines - 1) / 2f) / max(1, lines - 1) * spread + (rnd.nextFloat() - 0.5f) * b.unit * 0.08f
                    var sx = ox + nx * off
                    var sy = oy + ny * off
                    // Start just inside the screen so every line is visible.
                    sx = sx.coerceIn(0f, w)
                    sy = sy.coerceIn(0f, h)
                    val delay = rnd.nextFloat() * b.unit * 0.25f
                    b.jagged(sx, sy, heading + (rnd.nextFloat() - 0.5f) * 0.15f, b.diag * 1.2f, delay, 0, 0.03f + k * 0.01f, wobble = 0.5f)
                    if (centred) b.jagged(sx, sy, heading + PI.toFloat(), b.diag * 1.2f, delay, 0, 0.03f + k * 0.01f, wobble = 0.5f)
                }
            }
            LightEffect.CRACK_EDGES -> {
                // Cracks creeping in from all round the screen edge, starting nearest the origin.
                val count = (6 + k * 2) * n
                val perimeter = 2 * (w + h)
                for (i in 0 until count) {
                    val p = (i + rnd.nextFloat() * 0.8f) / count * perimeter
                    val (ex, ey, inward) = when {
                        p < w -> Triple(p, 0f, PI.toFloat() / 2)
                        p < w + h -> Triple(w, p - w, PI.toFloat())
                        p < 2 * w + h -> Triple(w - (p - w - h), h, -PI.toFloat() / 2)
                        else -> Triple(0f, h - (p - 2 * w - h), 0f)
                    }
                    val d0 = hypot(ex - ox, ey - oy) * 0.6f
                    val a = inward + (rnd.nextFloat() - 0.5f) * 0.9f
                    b.jagged(ex, ey, a, b.unit * (0.08f + rnd.nextFloat() * 0.2f), d0, 0, 0.08f + k * 0.02f)
                }
            }
            else -> Unit
        }
        return b.build(ox, oy)
    }
}
