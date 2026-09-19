package com.agent.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ChatAvatar — port of flutter widgets/chat_avatar.dart: a honeycomb identicon
// circle. Background hue from the seed; a mirror-symmetric pointy-top hexagon
// pattern (seeded per cell) in a contrast-optimized foreground.

private data class HexCell(val x: Double, val y: Double, val r: Double, val on: Boolean)

private class AvatarSpec(val bg: Color, val fg: Color, val hexes: List<HexCell>)

private fun fnv(s: String): Int {
    var hash: Long = 0x811c9dc5L
    for (ch in s) {
        hash = hash xor ch.code.toLong()
        hash = (hash * 0x01000193L)
    }
    return (hash and 0x7fffffffL).toInt()
}

private fun hueOf(source: String): Double = (fnv(source) % 360).toDouble()

/** flutter HSLColor.fromAHSL(a, h, s, l) → ARGB. */
private fun hsl(hue: Double, sat: Double, light: Double): Color {
    val c = (1 - kotlin.math.abs(2 * light - 1)) * sat
    val hp = hue / 60.0
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0.0)
        hp < 2 -> Triple(x, c, 0.0)
        hp < 3 -> Triple(0.0, c, x)
        hp < 4 -> Triple(0.0, x, c)
        hp < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = light - c / 2
    // Quantise to 8-bit channels with ROUND, matching Flutter's HSLColor.toColor
    // (`(v * 0xFF).round()`). Truncation (.toInt()) shifts every channel down by
    // up to 1/255, which flips the contrast ladder near a 3.5 boundary — e.g.
    // hue 316 (seed "e2e-gwchat") picked the dark rung on Flutter/WebUI/SwiftUI
    // but the light rung here, so the glyph came out pale.
    fun ch(v: Double) = ((v + m).coerceIn(0.0, 1.0) * 255).roundToInt()
    return Color(ch(r1), ch(g1), ch(b1))
}

private fun luminance(c: Color): Double {
    fun channel(v: Float): Double = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

private fun contrastRatio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** Strong finalizer so near-identical seeds diverge (flutter _mix). */
private fun mix(x0: Int): Int {
    var x = x0.toLong() and 0xffffffffL
    x = ((x xor (x shr 16)) * 0x7feb352dL) and 0xffffffffL
    x = ((x xor (x shr 15)) * 0x846ca68bL) and 0xffffffffL
    return (x xor (x shr 16)).toInt()
}

private fun bitAt(seed: String, i: Int): Boolean {
    if (seed.isEmpty()) return (i and 1) == 0
    return (mix(fnv("$seed#$i")) and 1) == 1
}

private const val HEX_SIZE = 0.10

private fun keyOf(x: Double, y: Double): String =
    "${(x * 1_000_000).toLong()}|${(y * 1_000_000).toLong()}"

private fun honeycombCells(seed: String, mirror: Boolean): List<HexCell> {
    val r = HEX_SIZE
    val stepX = sqrt(3.0) * r
    val stepY = 1.5 * r
    val cells = ArrayList<HexCell>()
    for (row in -8..8) {
        val y = row * stepY
        val xOff = if (row % 2 != 0) stepX / 2 else 0.0
        for (col in -8..8) {
            val x = col * stepX + xOff
            if (sqrt(x * x + y * y) > 0.5 - r) continue
            cells.add(HexCell(x, y, r, true))
        }
    }
    if (!mirror) {
        return cells.mapIndexed { i, c -> HexCell(c.x, c.y, c.r, bitAt(seed, i)) }
    }
    // Mirror-symmetric about the vertical axis: shared bit per x → -x pair.
    val byCoord = HashMap<String, Int>()
    cells.forEachIndexed { i, c -> byCoord[keyOf(-c.x, c.y)] = i }
    val on = BooleanArray(cells.size)
    cells.forEachIndexed { i, c ->
        if (on[i]) return@forEachIndexed
        val mi = byCoord[keyOf(c.x, c.y)] ?: i
        val bit = bitAt(seed, min(i, mi))
        on[i] = bit
        if (mi != i) on[mi] = bit
    }
    return cells.mapIndexed { i, c -> HexCell(c.x, c.y, c.r, on[i]) }
}

private fun avatarSpec(seed: String): AvatarSpec {
    val bg = hsl(hueOf(seed), 0.60, 0.48)
    val darkRungs = doubleArrayOf(0.34, 0.28, 0.22, 0.17, 0.12)
    val lightRungs = doubleArrayOf(0.66, 0.72, 0.78, 0.84, 0.90)
    val hue = hueOf(seed)
    var best = Color.White
    var bestRatio = -1.0
    for (l in darkRungs + lightRungs) {
        val c = hsl(hue, 0.62, l)
        val ratio = contrastRatio(c, bg)
        if (ratio > bestRatio) {
            bestRatio = ratio
            best = c
        }
        if (bestRatio >= 3.5) break
    }
    val fg = if (bestRatio < 3.0) {
        if (luminance(bg) > 0.35) Color(0xFF17181C) else Color.White
    } else best
    return AvatarSpec(bg, fg, honeycombCells(seed, mirror = true))
}

/** Circle-clipped honeycomb identicon (flutter ChatAvatar, branch level). */
@Composable
fun ChatAvatar(seed: String, radius: Dp = 20.dp) {
    val spec = remember(seed) { avatarSpec(seed) }
    Canvas(Modifier.size(radius * 2)) {
        val d = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = spec.bg, radius = d / 2f, center = center)
        val clip = Path().apply { addOval(Rect(center.x - d / 2, center.y - d / 2, center.x + d / 2, center.y + d / 2)) }
        clipPath(clip) {
            for (h in spec.hexes) {
                if (!h.on) continue
                val cx = center.x + (h.x * d).toFloat()
                val cy = center.y + (h.y * d).toFloat()
                val r = (h.r * d).toFloat()
                val path = Path()
                for (k in 0 until 6) {
                    val a = PI / 6 + k * PI / 3
                    val px = cx + r * cos(a).toFloat()
                    val py = cy + r * sin(a).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, spec.fg)
            }
        }
    }
}
