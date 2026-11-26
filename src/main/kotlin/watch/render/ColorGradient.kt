package jp.simplespace.watch.render

import java.awt.Color
import kotlin.math.roundToInt

object ColorGradient {
    private data class Stop(val percent: Double, val color: Color)

    private val stops = listOf(
        Stop(0.0, Color(0x2E, 0xCC, 0x71)),   // 緑
        Stop(40.0, Color(0xF1, 0xC4, 0x0F)),  // 黄
        Stop(70.0, Color(0xE6, 0x7E, 0x22)),  // 橙
        Stop(90.0, Color(0xE7, 0x4C, 0x3C)),  // 赤
        Stop(100.0, Color(0x8B, 0x00, 0x00)), // 深紅
    )

    fun colorForScore(score: Double): Color {
        val clamped = score.coerceIn(0.0, 100.0)
        for (i in 0 until stops.lastIndex) {
            val left = stops[i]
            val right = stops[i + 1]
            if (clamped <= right.percent) {
                val ratio = if (right.percent == left.percent) {
                    0.0
                } else {
                    (clamped - left.percent) / (right.percent - left.percent)
                }
                return lerp(left.color, right.color, ratio)
            }
        }
        return stops.last().color
    }

    fun statusLabel(score: Double): String = when {
        score >= 100.0 -> "💀 処刑執行中 / BAN"
        score >= 90.0 -> "🔴 危険 / BAN寸前"
        score >= 70.0 -> "🟠 警戒 / 危険水域"
        score >= 40.0 -> "🟡 注意 / やや不穏"
        else -> "🟢 良好 / 落ち着いています"
    }

    private fun lerp(a: Color, b: Color, t: Double): Color {
        val clampedT = t.coerceIn(0.0, 1.0)
        val r = (a.red + (b.red - a.red) * clampedT).roundToInt().coerceIn(0, 255)
        val g = (a.green + (b.green - a.green) * clampedT).roundToInt().coerceIn(0, 255)
        val bl = (a.blue + (b.blue - a.blue) * clampedT).roundToInt().coerceIn(0, 255)
        return Color(r, g, bl)
    }
}
