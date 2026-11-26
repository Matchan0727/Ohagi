package jp.simplespace.watch.render

import jp.simplespace.db.WatchCycleStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO

object ProgressBarRenderer {
    private const val WIDTH = 820
    private const val HEIGHT = 185

    private val BG_COLOR = Color(0x23, 0x25, 0x2A)
    private val TRACK_COLOR = Color(0x16, 0x18, 0x1C)
    private val BORDER_COLOR = Color(0x38, 0x3C, 0x45)
    private val TEXT_PRIMARY = Color(0xF2, 0xF3, 0xF5)
    private val TEXT_SECONDARY = Color(0xB5, 0xBA, 0xC1)
    private val TICK_COLOR = Color(0x55, 0x5B, 0x67, 170)

    fun render(
        displayName: String,
        cycleNumber: Int,
        score: Double,
        status: WatchCycleStatus,
        footerText: String,
    ): ByteArray {
        val clamped = score.coerceIn(0.0, 100.0)
        val img = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // カード背景
            val card = RoundRectangle2D.Double(2.0, 2.0, WIDTH - 4.0, HEIGHT - 4.0, 22.0, 22.0)
            g.color = BG_COLOR
            g.fill(card)
            g.color = BORDER_COLOR
            g.stroke = BasicStroke(2f)
            g.draw(card)

            val accent = when (status) {
                WatchCycleStatus.BANNED -> Color(0x8B, 0x00, 0x00)
                WatchCycleStatus.PENDING_BAN -> Color(0xE7, 0x4C, 0x3C)
                else -> ColorGradient.colorForScore(clamped)
            }

            // ヘッダ: ユーザー名 + サイクル
            g.font = FontLoader.bold(24f)
            g.color = TEXT_PRIMARY
            val safeName = if (displayName.length > 22) displayName.take(21) + "…" else displayName
            g.drawString(safeName, 28, 46)

            g.font = FontLoader.regular(15f)
            g.color = TEXT_SECONDARY
            val statusBadge = when (status) {
                WatchCycleStatus.ACTIVE -> "Cycle #$cycleNumber | 監視中"
                WatchCycleStatus.PENDING_BAN -> "Cycle #$cycleNumber | 遺言待機中 (BAN寸前)"
                WatchCycleStatus.BANNED -> "Cycle #$cycleNumber | BAN執行済み"
                WatchCycleStatus.ARCHIVED -> "Cycle #$cycleNumber | アーカイブ"
            }
            g.drawString(statusBadge, 28, 70)

            // 右上: パーセンテージ大きく表示
            val pctStr = String.format(Locale.US, "%.1f%%", clamped)
            g.font = FontLoader.bold(38f)
            g.color = accent
            val fmPct = g.fontMetrics
            g.drawString(pctStr, WIDTH - 28 - fmPct.stringWidth(pctStr), 58)

            // プログレスバートラック
            val barX = 28.0
            val barY = 88.0
            val barW = WIDTH - 56.0
            val barH = 38.0
            val trackShape = RoundRectangle2D.Double(barX, barY, barW, barH, 18.0, 18.0)
            g.color = TRACK_COLOR
            g.fill(trackShape)

            // プログレスバー塗りつぶし
            val fillW = (barW * (clamped / 100.0)).coerceAtLeast(0.0)
            if (fillW > 2.0) {
                val oldClip = g.clip
                g.clip(trackShape)
                val startColor = ColorGradient.colorForScore((clamped - 35.0).coerceAtLeast(0.0))
                g.paint = GradientPaint(
                    barX.toFloat(),
                    barY.toFloat(),
                    startColor,
                    (barX + fillW).toFloat(),
                    barY.toFloat(),
                    accent,
                )
                g.fill(RoundRectangle2D.Double(barX, barY, fillW.coerceAtLeast(18.0), barH, 18.0, 18.0))
                g.clip = oldClip
            }

            // 25 / 50 / 75% 目盛り線
            g.color = TICK_COLOR
            g.stroke = BasicStroke(1.5f)
            for (tick in listOf(25, 50, 75)) {
                val tx = (barX + barW * (tick / 100.0)).toInt()
                g.drawLine(tx, barY.toInt() + 4, tx, (barY + barH).toInt() - 4)
            }

            // トラック枠線
            g.color = BORDER_COLOR
            g.stroke = BasicStroke(1.5f)
            g.draw(trackShape)

            // 下部フッター
            g.font = FontLoader.regular(14f)
            g.color = TEXT_SECONDARY
            val trimmedFooter = if (footerText.length > 62) footerText.take(61) + "…" else footerText
            g.drawString(trimmedFooter, 28, 158)
        } finally {
            g.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }
}
